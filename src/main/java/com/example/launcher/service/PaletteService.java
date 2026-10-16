package com.example.launcher.service;

import com.example.launcher.model.Palette;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import javafx.scene.Scene;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a {@link Palette} into the stylesheet the interface runs on.
 *
 * <p>The stylesheet is generated rather than shipped: {@code css/theme-template.css}
 * holds one copy of every rule with token slots in place of colours, and this
 * service fills them in and hands the result to the scene. That is what makes the
 * three theme files redundant - keeping three hand-edited copies is how they
 * drifted apart in the first place.
 *
 * <p>JavaFX cannot take a {@code data:} URI as a stylesheet (it ignores it
 * silently), so the generated sheet is written to a file in the temp directory
 * and that file's URL is used. The file name is derived from the palette name, so
 * repeated switches overwrite one file instead of littering.
 */
public final class PaletteService {

    private static final String TEMPLATE_RESOURCE = "/css/theme-template.css";
    private static final String PRESETS_RESOURCE = "/palette/presets.json";
    private static final Pattern TOKEN = Pattern.compile("@([a-z0-9-]+)@");
    /** {@code -fx-font-size: 15px} - the setting multiplies the number, not the unit. */
    private static final Pattern FONT_SIZE =
            Pattern.compile("(-fx-font-size:\\s*)(\\d+(?:\\.\\d+)?)(px)");

    private static String templateCache;
    private static Map<String, Palette> presetCache;

    private PaletteService() {}

    /** The built-in palettes, keyed by the names the config and Settings use. */
    public static synchronized Map<String, Palette> presets() {
        if (presetCache == null) {
            Map<String, Palette> loaded = new LinkedHashMap<>();
            try (InputStream in = PaletteService.class.getResourceAsStream(PRESETS_RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("нет ресурса " + PRESETS_RESOURCE);
                }
                Map<String, Palette> parsed = new Gson().fromJson(
                        new InputStreamReader(in, StandardCharsets.UTF_8),
                        new TypeToken<LinkedHashMap<String, Palette>>() { }.getType());
                for (Map.Entry<String, Palette> entry : parsed.entrySet()) {
                    entry.getValue().setName(entry.getKey());
                    loaded.put(entry.getKey(), entry.getValue());
                }
            } catch (Exception e) {
                throw new IllegalStateException("не удалось прочитать пресеты палитры: " + e.getMessage(), e);
            }
            presetCache = loaded;
        }
        return presetCache;
    }

    /**
     * The palette a config theme name refers to. Unknown names (including the old
     * default {@code "light"}, which never existed as a real theme) resolve to the
     * first preset, which is what the launcher has always shown in that case.
     */
    public static Palette preset(String themeName) {
        Map<String, Palette> all = presets();
        Palette exact = themeName == null ? null : all.get(themeName);
        if (exact != null) return exact.copy();
        return all.values().iterator().next().copy();
    }

    /** The stylesheet text for a palette, with every token filled in. */
    public static String render(Palette palette) {
        return render(palette, 1.0);
    }

    /**
     * The stylesheet text for a palette, with every token filled in and every font size
     * multiplied by {@code fontScale}.
     *
     * <p>Scaling here rather than in each rule is what makes the setting a setting: the
     * template keeps one size per role, the multiplier is applied to all of them at once,
     * and the ratios between a hint and a title survive any scale.
     *
     * <p>Results are rounded to whole pixels on purpose. A fractional font size is one of
     * the things that made this interface look blurred before (see the earlier font work):
     * half-pixel text gets resampled, whole-pixel text does not.
     */
    public static String render(Palette palette, double fontScale) {
        String rendered = template();
        // A gradient's value may itself name a token ("linear-gradient(..., @bg@ 55%, ...)"),
        // which is how the app background follows the background colour the player picks
        // instead of being a fixed pair of hex values. Tokens are resolved in passes until
        // none are left, so nesting keeps working however deep it goes.
        for (int pass = 0; pass < 4; pass++) {
            if (!rendered.contains("@")) break;
            Matcher matcher = TOKEN.matcher(rendered);
            StringBuilder out = new StringBuilder();
            boolean replaced = false;
            while (matcher.find()) {
                String token = matcher.group(1);
                String value = palette.value(token);
                if (value == null) {
                    throw new IllegalStateException("палитра «" + palette.getName()
                            + "» не задаёт токен " + token);
                }
                matcher.appendReplacement(out, Matcher.quoteReplacement(value));
                replaced = true;
            }
            matcher.appendTail(out);
            if (!replaced) break;
            rendered = out.toString();
        }
        java.util.regex.Matcher leftover = TOKEN.matcher(rendered);
        if (leftover.find()) {
            throw new IllegalStateException("в теме остались незаполненные токены, например "
                    + leftover.group());
        }
        return scaleFonts(rendered, fontScale);
    }

    /** Multiplies every {@code -fx-font-size} value in a rendered stylesheet. */
    static String scaleFonts(String css, double fontScale) {
        if (fontScale <= 0 || Math.abs(fontScale - 1.0) < 0.001) return css;
        Matcher matcher = FONT_SIZE.matcher(css);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            int scaled = (int) Math.round(Double.parseDouble(matcher.group(2)) * fontScale);
            matcher.appendReplacement(out, Matcher.quoteReplacement(
                    matcher.group(1) + Math.max(1, scaled) + matcher.group(3)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Writes the palette's stylesheet next to the launcher's temp files and makes it the
     * scene's only stylesheet. Called at start-up and on every palette change, including
     * every keystroke in the palette editor.
     *
     * <p>Each application writes a NEW file name. That is not tidiness: JavaFX caches a
     * parsed stylesheet by its URL, so re-applying the same path serves the version it
     * already parsed and the scene keeps the old colours. That is exactly what happened
     * while the palette editor was being used - the launcher's own background would not
     * follow the colour being picked, because every edit reused one file name.
     */
    public static void apply(Scene scene, Palette palette) {
        apply(scene, palette, 1.0);
    }

    /**
     * Same, with the player's text size applied.
     *
     * <p>The size travels with the palette because both are part of the same generated
     * stylesheet: keeping them in one file is what stops a colour edit from silently
     * resetting the text size, which is exactly what would happen if the editor applied a
     * palette rendered at the default scale.
     */
    public static void apply(Scene scene, Palette palette, double fontScale) {
        if (scene == null) return;
        try {
            Path file = tempFile(palette.getName(), revision.incrementAndGet());
            Files.writeString(file, render(palette, fontScale), StandardCharsets.UTF_8);
            scene.getStylesheets().setAll(file.toUri().toString());
            Path previous = lastApplied.getAndSet(file);
            if (previous != null && !previous.equals(file)) {
                try {
                    Files.deleteIfExists(previous);
                } catch (Exception ignored) {
                    // a leftover temp file is harmless
                }
            }
        } catch (Exception e) {
            // A theme is cosmetic: never take the launcher down over it, but leave a
            // trace, because an unstyled window is otherwise baffling to debug.
            System.out.println("[theme] не удалось применить палитру «"
                    + palette.getName() + "»: " + e);
        }
    }

    /** Bumped per application so JavaFX cannot serve a cached stylesheet - see apply. */
    private static final java.util.concurrent.atomic.AtomicInteger revision =
            new java.util.concurrent.atomic.AtomicInteger();

    /** The stylesheet currently on screen, deleted when the next one replaces it. */
    private static final java.util.concurrent.atomic.AtomicReference<Path> lastApplied =
            new java.util.concurrent.atomic.AtomicReference<>();

    private static Path tempFile(String paletteName, int revision) {
        String safe = paletteName == null || paletteName.isBlank() ? "palette"
                : paletteName.replaceAll("[^a-zA-Z0-9_-]", "_");
        return Path.of(System.getProperty("java.io.tmpdir"),
                "blauncher-theme-" + safe + "-" + revision + ".css");
    }

    private static synchronized String template() {
        if (templateCache == null) {
            try (InputStream in = PaletteService.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
                if (in == null) {
                    throw new IllegalStateException("нет ресурса " + TEMPLATE_RESOURCE);
                }
                templateCache = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                throw new IllegalStateException("не удалось прочитать шаблон темы: " + e.getMessage(), e);
            }
        }
        return templateCache;
    }
}
