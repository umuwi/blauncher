package com.example.launcher.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Function;

/**
 * The "Фон" panel in Settings: no picture, one of the sixteen shipped with the launcher, or the
 * player's own.
 *
 * <p>A picture the player adds is copied into {@code launcher_root/backgrounds} rather than
 * referenced where it lies: a background that vanishes when the Downloads folder is cleaned up
 * would come back as a blank screen with no explanation.
 *
 * <p>Values are the strings stored in {@code LauncherConfig.backgroundImage}: {@code ""},
 * {@code "builtin:bg05"} or {@code "file:<absolute path>"}.
 */
public final class BackgroundPicker {

    public static final String NONE = "";
    /** The living End sky (ui/EndSky), not a picture. */
    public static final String END = "end";
    private static final String BUILTIN = "builtin:";
    private static final String FILE = "file:";
    private static final int BUILTIN_COUNT = 16;
    private static final double TILE_W = 128;
    private static final double TILE_H = 72;
    /** Pictures larger than this are refused: the whole file is decoded into memory to draw it. */
    private static final long MAX_BYTES = 25L * 1024 * 1024;

    private final Path userDir;
    private final Function<String, String> tr;
    private final Consumer<String> onPick;
    private final Consumer<String> onError;
    private final FlowPane tiles = new FlowPane(10, 10);
    private final VBox root = new VBox(12);
    private String current;

    /**
     * @param userDir  where the player's own pictures are kept
     * @param current  the stored value currently in use
     * @param dim      the stored veil strength
     * @param onPick   called with the new stored value whenever a tile is picked
     * @param onDim    called while the veil slider moves
     * @param onError  shows a message the player can act on
     */
    public BackgroundPicker(Path userDir, String current, double dim, Function<String, String> tr,
                            Consumer<String> onPick, DoubleConsumer onDim, Consumer<String> onError) {
        this.userDir = userDir;
        this.tr = tr;
        this.onPick = onPick;
        this.onError = onError;
        this.current = current == null ? NONE : current;

        Slider dimSlider = new Slider(0, 0.9, dim);
        dimSlider.setMaxWidth(260);
        HBox.setHgrow(dimSlider, Priority.ALWAYS);
        dimSlider.valueProperty().addListener((obs, o, n) -> onDim.accept(n.doubleValue()));
        Label dimLabel = new Label(tr.apply("bg_dim_label"));
        dimLabel.getStyleClass().add("toggle-sub");
        HBox dimRow = new HBox(10, dimLabel, dimSlider);
        dimRow.setAlignment(Pos.CENTER_LEFT);

        root.getChildren().addAll(tiles, dimRow);
        render();
    }

    public Node node() {
        return root;
    }

    /**
     * The CSS url of a stored value, or null when there is no picture to draw (none chosen, or
     * the file the player added is gone).
     */
    public static String resolveUrl(String stored) {
        if (stored == null || stored.isBlank()) return null;
        if (stored.startsWith(BUILTIN)) {
            String name = stored.substring(BUILTIN.length());
            if (!name.matches("bg\\d\\d")) return null;
            URL url = BackgroundPicker.class.getResource("/images/backgrounds/" + name + ".jpg");
            return url == null ? null : url.toExternalForm();
        }
        if (stored.startsWith(FILE)) {
            Path path = Path.of(stored.substring(FILE.length()));
            return Files.isRegularFile(path) ? path.toUri().toString() : null;
        }
        return null;
    }

    private void render() {
        List<Node> nodes = new ArrayList<>();
        nodes.add(tile(NONE, null, tr.apply("bg_none"), false));
        nodes.add(endTile());
        for (int i = 1; i <= BUILTIN_COUNT; i++) {
            String id = BUILTIN + String.format(Locale.ROOT, "bg%02d", i);
            nodes.add(tile(id, resolveUrl(id), null, false));
        }
        for (Path own : ownPictures()) {
            String id = FILE + own.toAbsolutePath();
            nodes.add(tile(id, own.toUri().toString(), null, true));
        }
        nodes.add(addTile());
        tiles.getChildren().setAll(nodes);
    }

    private List<Path> ownPictures() {
        List<Path> found = new ArrayList<>();
        if (!Files.isDirectory(userDir)) return found;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(userDir, "*.{png,jpg,jpeg,gif,bmp}")) {
            for (Path p : stream) found.add(p);
        } catch (IOException ignored) {
            // an unreadable folder just shows no own pictures
        }
        found.sort(null);
        return found;
    }

    private Node tile(String id, String url, String text, boolean removable) {
        StackPane tile = new StackPane();
        tile.getStyleClass().add("bg-tile");
        if (id.equals(current)) tile.getStyleClass().add("active");
        tile.setMinSize(TILE_W, TILE_H);
        tile.setPrefSize(TILE_W, TILE_H);
        tile.setMaxSize(TILE_W, TILE_H);

        if (url != null) {
            // Decoded at tile size in the background: sixteen 1920x1080 JPEGs at full size would
            // cost ~130 MB and stall the settings screen on first open.
            ImageView view = new ImageView(new Image(url, TILE_W * 2, TILE_H * 2, false, true, true));
            view.setFitWidth(TILE_W - 4);
            view.setFitHeight(TILE_H - 4);
            Rectangle clip = new Rectangle(TILE_W - 4, TILE_H - 4);
            clip.setArcWidth(14);
            clip.setArcHeight(14);
            view.setClip(clip);
            tile.getChildren().add(view);
        }
        if (text != null) {
            Label label = new Label(text);
            label.getStyleClass().add("bg-tile-text");
            tile.getChildren().add(label);
        }
        if (removable) {
            Button remove = new Button();
            remove.setGraphic(new FontIcon("fth-x"));
            remove.getStyleClass().add("bg-tile-remove");
            StackPane.setAlignment(remove, Pos.TOP_RIGHT);
            remove.setOnAction(e -> {
                e.consume();
                removeOwn(id);
            });
            tile.getChildren().add(remove);
        }
        tile.setOnMouseClicked(e -> pick(id));
        return tile;
    }

    /** A still frame of the End sky: violet-black with one flash caught at its brightest. */
    private Node endTile() {
        StackPane tile = (StackPane) tile(END, null, tr.apply("bg_end"), false);
        javafx.scene.layout.Region sky = new javafx.scene.layout.Region();
        sky.setStyle("-fx-background-color: radial-gradient(center 70% 30%, radius 60%, rgba(236,160,255,0.55), rgba(140,80,240,0.18) 45%, transparent 75%), linear-gradient(to bottom, #0C0916, #08070E);"
                + "-fx-background-radius: 7;");
        sky.setMouseTransparent(true);
        StackPane.setMargin(sky, new javafx.geometry.Insets(2));
        tile.getChildren().add(0, sky);
        return tile;
    }

    private Node addTile() {
        VBox box = new VBox(4);
        box.setAlignment(Pos.CENTER);
        FontIcon plus = new FontIcon("fth-image");
        plus.setIconSize(18);
        plus.getStyleClass().add("tool-card-icon");
        Label label = new Label(tr.apply("bg_add_own"));
        label.getStyleClass().add("bg-tile-text");
        box.getChildren().addAll(plus, label);

        StackPane tile = new StackPane(box);
        tile.getStyleClass().addAll("bg-tile", "add");
        tile.setMinSize(TILE_W, TILE_H);
        tile.setPrefSize(TILE_W, TILE_H);
        tile.setMaxSize(TILE_W, TILE_H);
        tile.setOnMouseClicked(e -> chooseOwn(tile.getScene() == null ? null : tile.getScene().getWindow()));
        return tile;
    }

    private void pick(String id) {
        current = id;
        onPick.accept(id);
        render();
    }

    private void chooseOwn(Window owner) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr.apply("bg_add_own"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(
                tr.apply("bg_file_filter"), "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp"));
        File file = chooser.showOpenDialog(owner);
        if (file == null) return;
        try {
            Path copy = importPicture(file.toPath(), userDir);
            pick(FILE + copy.toAbsolutePath());
        } catch (IOException e) {
            onError.accept(e.getMessage());
        }
    }

    /**
     * Copies a picture into {@code dir} under a name of its own and checks that it is a picture
     * JavaFX can actually draw — a renamed .webp or a broken file is turned away here instead of
     * becoming a blank background.
     */
    public static Path importPicture(Path source, Path dir) throws IOException {
        long size = Files.size(source);
        if (size > MAX_BYTES) throw new IOException("файл больше 25 МБ");
        Image probe = new Image(source.toUri().toString(), 64, 64, true, false, false);
        if (probe.isError() || probe.getWidth() <= 0) throw new IOException("это не картинка или формат не поддерживается");

        Files.createDirectories(dir);
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "png" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String base = (dot < 0 ? name : name.substring(0, dot)).replaceAll("[^\\p{L}\\p{N}_-]", "_");
        if (base.isBlank()) base = "background";
        if (base.length() > 40) base = base.substring(0, 40);
        Path target = dir.resolve(base + "." + ext);
        for (int i = 2; Files.exists(target); i++) {
            target = dir.resolve(base + "-" + i + "." + ext);
        }
        Path tmp = dir.resolve(target.getFileName() + ".part");
        Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        return target;
    }

    private void removeOwn(String id) {
        try {
            Path path = Path.of(id.substring(FILE.length()));
            // only ever inside our own folder
            if (path.toAbsolutePath().normalize().startsWith(userDir.toAbsolutePath().normalize())) {
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            onError.accept(e.getMessage());
            return;
        }
        if (id.equals(current)) {
            pick(NONE);
        } else {
            render();
        }
    }
}
