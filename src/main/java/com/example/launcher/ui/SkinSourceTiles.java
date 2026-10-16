package com.example.launcher.ui;

import com.example.launcher.service.Http;
import com.example.launcher.service.SkinSourceCatalog;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * The row of site chips in the profile editor: one selectable chip per source, each carrying the
 * site's own logo.
 *
 * <p>A chip is a {@link ToggleButton} in a group, so picking a site is a selection rather than a
 * one-off action - the chosen site stays highlighted, and the player can see where the skin or
 * cape in the profile came from. {@link #render} wires the selection callback: the caller pulls
 * the texture, and the selection stays even when that pull fails, because "nothing came from
 * OptiFine" is itself the answer.
 *
 * <p>The logo is the site's favicon, so it is never bundled with the launcher and never goes
 * stale: it is fetched once, written to {@code <gameDir>/cache/site-logos/<id>.png} and reused
 * from there. Until it arrives - and forever, if the launcher is offline - the chip shows a
 * monogram tile in the site's own colour instead, so the strip is never a row of broken images.
 * A failed fetch also drops an empty {@code <id>.none} marker, which keeps the next start from
 * waiting on three dead hosts again; the marker expires after a few days so a site that comes
 * back is picked up.
 *
 * <p>{@link #render} only builds nodes; the fetches it schedules run on the caller's background
 * executor and hand their result back on the FX thread.
 */
public final class SkinSourceTiles {

    private SkinSourceTiles() {}

    private static final int MARK = 30;
    private static final int MARK_INSET = 8;
    private static final int TILE_WIDTH = 184;
    private static final int LABEL_WIDTH = 118;
    private static final Duration LOGO_TIMEOUT = Duration.ofSeconds(6);
    private static final long MISS_RETRY_DAYS = 3;

    /**
     * Rebuilds {@code target} with one chip per source, and leaves {@code selectedId} chosen.
     *
     * @param cacheDir   where fetched logos are kept
     * @param selectedId the site to mark as chosen, or null for the first one
     * @param onSelect   called on the FX thread with the site the player just picked
     * @param subtitle   the "what this site gives" line under the site name, already translated
     * @param background runs a task off the FX thread (the caller's executor)
     * @return the id that ended up selected
     */
    public static String render(Pane target, List<SkinSourceCatalog.Source> sources, Path cacheDir,
                                String selectedId, Consumer<SkinSourceCatalog.Source> onSelect,
                                Function<SkinSourceCatalog.Source, String> subtitle,
                                Consumer<Runnable> background) {
        target.getChildren().clear();
        ToggleGroup group = new ToggleGroup();
        String chosen = pick(sources, selectedId);
        for (SkinSourceCatalog.Source source : sources) {
            ToggleButton chip = chip(source, cacheDir, group, onSelect, subtitle.apply(source), background);
            if (source.id().equals(chosen)) chip.setSelected(true);
            target.getChildren().add(chip);
        }
        return chosen;
    }

    /** The site a row should start on: the remembered one, or the first. */
    private static String pick(List<SkinSourceCatalog.Source> sources, String selectedId) {
        if (sources.isEmpty()) return null;
        if (selectedId != null) {
            for (SkinSourceCatalog.Source source : sources) {
                if (source.id().equals(selectedId)) return selectedId;
            }
        }
        return sources.get(0).id();
    }

    /** The id of the chip currently chosen in a row, or null when the row is empty. */
    public static String selectedId(Pane row) {
        for (Node node : row.getChildren()) {
            if (node instanceof ToggleButton chip && chip.isSelected()) {
                return String.valueOf(chip.getUserData());
            }
        }
        return null;
    }

    private static ToggleButton chip(SkinSourceCatalog.Source source, Path cacheDir, ToggleGroup group,
                                     Consumer<SkinSourceCatalog.Source> onSelect, String subtitle,
                                     Consumer<Runnable> background) {
        StackPane mark = new StackPane();
        mark.getStyleClass().add("source-mark");
        mark.setMinSize(MARK, MARK);
        mark.setPrefSize(MARK, MARK);
        mark.setMaxSize(MARK, MARK);
        mark.setStyle("-fx-background-color: " + source.brand() + ";");
        mark.getChildren().add(monogram(source));

        Label name = new Label(source.name());
        name.getStyleClass().add("source-name");
        name.setMaxWidth(LABEL_WIDTH);
        Label sub = new Label(subtitle);
        sub.getStyleClass().add("source-sub");
        sub.setMaxWidth(LABEL_WIDTH);

        VBox text = new VBox(1, name, sub);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox row = new HBox(9, mark, text);
        row.setAlignment(Pos.CENTER_LEFT);

        ToggleButton chip = new ToggleButton();
        chip.getStyleClass().add("source-tile");
        chip.setToggleGroup(group);
        chip.setUserData(source.id());
        chip.setGraphic(row);
        chip.setPrefWidth(TILE_WIDTH);
        chip.setMinWidth(TILE_WIDTH);
        chip.setTooltip(new Tooltip(source.name() + " — " + subtitle));
        chip.setOnAction(e -> {
            // A chip in a group cannot be switched off by clicking it again, so the row always has
            // exactly one chosen site - see ToggleButton.fire().
            chip.setSelected(true);
            onSelect.accept(source);
        });

        background.accept(() -> {
            Image logo = loadLogo(source, cacheDir);
            if (logo == null) return;
            Platform.runLater(() -> {
                ImageView view = new ImageView(logo);
                view.setFitWidth(MARK - MARK_INSET);
                view.setFitHeight(MARK - MARK_INSET);
                view.setPreserveRatio(true);
                view.setSmooth(true);
                mark.getChildren().setAll(view);
            });
        });

        return chip;
    }

    /** The letters standing in for a logo that has not loaded (or never will). */
    private static Label monogram(SkinSourceCatalog.Source source) {
        Label label = new Label(source.monogram());
        label.getStyleClass().add("source-monogram");
        // Ink or white, whichever the site's own colour can carry: the chip keeps the site's
        // brand colour, so the contrast decision has to be made per site.
        label.setStyle("-fx-font-size: " + (source.monogram().length() > 1 ? 11 : 14) + "px;"
                + " -fx-text-fill: " + (isLight(source.brand()) ? "#10131A" : "#FFFFFF") + ";");
        return label;
    }

    /** Rough perceptual luminance of an {@code #rrggbb} string; light colours take dark ink. */
    public static boolean isLight(String hex) {
        try {
            int red = Integer.parseInt(hex.substring(1, 3), 16);
            int green = Integer.parseInt(hex.substring(3, 5), 16);
            int blue = Integer.parseInt(hex.substring(5, 7), 16);
            return (0.299 * red + 0.587 * green + 0.114 * blue) / 255 > 0.6;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The site's favicon: from the cache, or fetched now; null means "keep the monogram".
     *
     * <p>Public so the probe can drive it with a seeded cache directory, where the answer is a
     * file on disk rather than a live site.
     */
    public static Image loadLogo(SkinSourceCatalog.Source source, Path cacheDir) {
        Path cached = cacheDir.resolve(source.id() + ".png");
        Path miss = cacheDir.resolve(source.id() + ".none");
        try {
            if (Files.exists(cached)) {
                try (InputStream in = Files.newInputStream(cached)) {
                    BufferedImage stored = ImageIO.read(in);
                    if (stored != null) return IconLoader.fromBufferedImage(stored);
                }
            }
            if (Files.exists(miss)
                    && Files.getLastModifiedTime(miss).toInstant()
                    .isAfter(Instant.now().minus(MISS_RETRY_DAYS, ChronoUnit.DAYS))) {
                return null;
            }

            for (String url : SkinSourceCatalog.logoUrls(source)) {
                BufferedImage image = fetch(url);
                if (image == null) continue;
                Files.createDirectories(cacheDir);
                ImageIO.write(image, "png", cached.toFile());
                Files.deleteIfExists(miss);
                return IconLoader.fromBufferedImage(image);
            }

            Files.createDirectories(cacheDir);
            Files.write(miss, new byte[0]);
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static BufferedImage fetch(String url) {
        try {
            Http.Response resp = Http.get(url, Map.of("User-Agent", "BLauncher"), LOGO_TIMEOUT);
            if (!resp.ok() || resp.body().length == 0) return null;
            byte[] body = resp.body();
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(body));
            return image != null ? image : readIco(body);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Some sites still serve a real {@code .ico} container, which ImageIO does not know. Modern
     * ones embed a PNG inside it, and that part ImageIO does read - so the container is opened
     * by hand and its largest entry handed over.
     */
    private static BufferedImage readIco(byte[] bytes) {
        try {
            if (bytes.length < 22 || bytes[0] != 0 || bytes[1] != 0 || bytes[2] != 1 || bytes[3] != 0) {
                return null;
            }
            int count = (bytes[4] & 0xFF) | ((bytes[5] & 0xFF) << 8);
            int bestOffset = -1;
            int bestSize = -1;
            int bestArea = -1;
            for (int i = 0; i < count; i++) {
                int entry = 6 + i * 16;
                if (entry + 16 > bytes.length) break;
                int width = bytes[entry] & 0xFF;
                int height = bytes[entry + 1] & 0xFF;
                if (width == 0) width = 256;
                if (height == 0) height = 256;
                // Читаем как long: readInt отдаёт знаковый int, и проверка offset + size
                // переполнялась. Два значения около двух миллиардов в сумме дают отрицательное
                // число, проверка проходила, а new byte[bestSize] дальше давал OutOfMemoryError.
                // Это Error, и его не ловит ни один из двух catch (Exception) по стеку —
                // достижимо, потому что favicon приходит со сторонних хостов.
                long size = readInt(bytes, entry + 8) & 0xFFFFFFFFL;
                long offset = readInt(bytes, entry + 12) & 0xFFFFFFFFL;
                if (size <= 0 || size > MAX_ICON_BYTES || offset + size > bytes.length) continue;
                if (width * height > bestArea) {
                    bestArea = width * height;
                    bestSize = (int) size;      // size уже проверен: <= MAX_ICON_BYTES
                    bestOffset = (int) offset;
                }
            }
            if (bestOffset < 0) return null;
            byte[] entry = new byte[bestSize];
            System.arraycopy(bytes, bestOffset, entry, 0, bestSize);
            int[] pngMagic = {0x89, 'P', 'N', 'G'};
            // Длину проверяем ДО чтения: запись короче четырёх байт давала бы
            // ArrayIndexOutOfBounds вместо честного «это не PNG».
            if (entry.length < pngMagic.length) return null;
            for (int i = 0; i < pngMagic.length; i++) {
                if (entry[i] != (byte) pngMagic[i]) return null;
            }
            return ImageIO.read(new ByteArrayInputStream(entry));
        } catch (Exception e) {
            return null;
        }
    }

    /** Разумный предел для одного изображения внутри .ico — иконка сайта столько не весит.
     *  Нужен, чтобы не выделять массив по числу из недоверенного файла. */
    private static final long MAX_ICON_BYTES = 8L * 1024 * 1024;

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }
}
