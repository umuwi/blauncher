package com.example.launcher.model;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every colour the interface is made of, plus its translucent variants and the
 * gradients - the single source the stylesheet is generated from.
 *
 * <p>A palette is stored in {@code config/launcher.json} as-is, so a palette the
 * player tunes in Settings (or pastes in from the concept editor) is the same
 * object as a built-in preset; nothing has to be converted.
 *
 * <p>Three kinds of entry:
 * <ul>
 *   <li>{@code colors} - solid {@code #rrggbb} values;</li>
 *   <li>{@code alphas} - translucent variants, <em>derived</em> from a colour in
 *       {@code colors} plus an alpha. Deriving them is what stops a theme from
 *       inheriting another theme's tint: the black theme used to show a green
 *       success badge because it had copied the violet theme's rgba literal;</li>
 *   <li>{@code gradients} - complete JavaFX gradient strings.</li>
 * </ul>
 */
public class Palette {

    /** alpha token -> the colour token it is derived from. */
    public static final Map<String, String> ALPHA_BASE = Map.ofEntries(
            Map.entry("accent-soft", "accent"), Map.entry("accent-line", "accent"),
            Map.entry("accent-glow", "accent"), Map.entry("accent-faint", "accent"),
            Map.entry("ok-soft", "ok"), Map.entry("ok-line", "ok"), Map.entry("ok-glow", "ok"),
            Map.entry("warn-glow", "warn"),
            Map.entry("danger-soft", "danger"), Map.entry("danger-faint", "danger"),
            Map.entry("danger-line", "danger"), Map.entry("danger-glow", "danger"));

    /** Sentinel name for a palette the player edited rather than picked. */
    public static final String CUSTOM = "custom";

    /** The token whose value is the app background, whatever shape it takes. */
    public static final String BACKGROUND = "grad-bg";

    /** Background shapes: the two the background editor offers. */
    public static final String BG_GRADIENT = "gradient";
    public static final String BG_SOLID = "solid";

    private String name;
    private Map<String, String> colors = new LinkedHashMap<>();
    private Map<String, Double> alphas = new LinkedHashMap<>();
    private Map<String, String> gradients = new LinkedHashMap<>();
    /** {@link #BG_GRADIENT} or {@link #BG_SOLID} - see {@link #background()}. */
    private String backgroundType = BG_GRADIENT;

    public Palette() {
    }

    public Palette(String name, Map<String, String> colors, Map<String, Double> alphas,
                   Map<String, String> gradients) {
        this.name = name;
        this.colors = new LinkedHashMap<>(colors);
        this.alphas = new LinkedHashMap<>(alphas);
        this.gradients = new LinkedHashMap<>(gradients);
    }

    /**
     * The concrete CSS value of a token: the colour itself, a derived rgba() for
     * an alpha token, or the gradient string. Returns null for an unknown token,
     * which the template builder turns into a loud failure rather than a silently
     * unstyled rule.
     */
    /**
     * Разбирает hex-цвет в три компоненты. Понимает {@code #rgb}, {@code #rrggbb},
     * {@code #rrggbbaa} (альфа игнорируется) и те же формы без решётки. Возвращает null,
     * если строка не является hex-цветом — вызывающий тогда просто не отдаёт значение токена.
     */
    private static int[] parseHexColor(String raw) {
        if (raw == null) return null;
        String hex = raw.trim();
        if (hex.startsWith("#")) hex = hex.substring(1);
        try {
            if (hex.length() == 3) {
                // #abc означает #aabbcc
                int r = Integer.parseInt(hex.substring(0, 1).repeat(2), 16);
                int g = Integer.parseInt(hex.substring(1, 2).repeat(2), 16);
                int b = Integer.parseInt(hex.substring(2, 3).repeat(2), 16);
                return new int[]{r, g, b};
            }
            if (hex.length() == 6 || hex.length() == 8) {
                return new int[]{
                        Integer.parseInt(hex.substring(0, 2), 16),
                        Integer.parseInt(hex.substring(2, 4), 16),
                        Integer.parseInt(hex.substring(4, 6), 16)};
            }
        } catch (NumberFormatException ignored) {
            // не hex — ниже вернём null
        }
        return null;
    }

    public String value(String token) {
        String color = colors.get(token);
        if (color != null) return color;
        Double alpha = alphas.get(token);
        if (alpha != null) {
            String base = ALPHA_BASE.get(token);
            String baseColor = base == null ? null : colors.get(base);
            if (baseColor == null) return null;
            int[] rgb = parseHexColor(baseColor);
            // Непонятный цвет не должен ронять всю тему. Прежний код резал строку по
            // фиксированным позициям в расчёте строго на #rrggbb: короткий «#fff» давал
            // StringIndexOutOfBoundsException, который уходил в сплошной catch (Exception) в
            // PaletteService.apply — и единственным симптомом было окно ВООБЩЕ без стилей плюс
            // одна строка в stdout. Достижимо, потому что палитру по javadoc правят руками
            // в launcher.json.
            if (rgb == null) return null;
            return String.format("rgba(%d,%d,%d,%s)", rgb[0], rgb[1], rgb[2], trim(alpha));
        }
        if (BACKGROUND.equals(token)) return background();
        return gradients.get(token);
    }

    /**
     * The CSS value of the app background - the one thing the background editor writes.
     *
     * <p>Why this is not simply {@code gradients.get("grad-bg")}: the visible background
     * used to be a gradient of two fixed hex values, so the background colour the player
     * picked in Settings reached nothing at all. The reported symptom was exactly that -
     * "фон самого лаунчера не меняется" - and the fix has two halves: the built-in
     * gradients now start at {@code @bg@}, and the background editor can switch the
     * background to a single colour, in which case the answer is the background token
     * itself and no gradient can stand in the way.
     *
     * <p>The returned value may still be a token ({@code @bg@}); the stylesheet renderer
     * resolves tokens in passes, so nesting is fine.
     */
    public String background() {
        String gradient = gradients.get(BACKGROUND);
        if (isSolidBackground() || gradient == null || gradient.isBlank()) {
            return "@bg@";
        }
        return gradient;
    }

    /** True when the background is one flat colour rather than a gradient. */
    public boolean isSolidBackground() {
        return BG_SOLID.equalsIgnoreCase(backgroundType);
    }

    public String getBackgroundType() {
        return backgroundType == null || backgroundType.isBlank() ? BG_GRADIENT : backgroundType;
    }

    public void setBackgroundType(String backgroundType) {
        this.backgroundType = backgroundType == null || backgroundType.isBlank()
                ? BG_GRADIENT : backgroundType;
    }

    private static String trim(double alpha) {
        return alpha == Math.rint(alpha) ? String.valueOf((int) alpha) : String.valueOf(alpha);
    }

    /** Tokens this palette can resolve, in the order the stylesheet may ask for them. */
    public List<String> tokens() {
        List<String> all = new java.util.ArrayList<>(colors.keySet());
        all.addAll(alphas.keySet());
        all.addAll(gradients.keySet());
        return all;
    }

    /** A deep copy the Settings screen can edit without touching the preset. */
    public Palette copy() {
        Palette copy = new Palette(name, colors, alphas, gradients);
        copy.setBackgroundType(backgroundType);
        return copy;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Map<String, String> getColors() {
        return colors;
    }

    public void setColors(Map<String, String> colors) {
        this.colors = colors == null ? new LinkedHashMap<>() : new LinkedHashMap<>(colors);
    }

    public Map<String, Double> getAlphas() {
        return alphas;
    }

    public void setAlphas(Map<String, Double> alphas) {
        this.alphas = alphas == null ? new LinkedHashMap<>() : new LinkedHashMap<>(alphas);
    }

    public Map<String, String> getGradients() {
        return gradients;
    }

    public void setGradients(Map<String, String> gradients) {
        this.gradients = gradients == null ? new LinkedHashMap<>() : new LinkedHashMap<>(gradients);
    }
}
