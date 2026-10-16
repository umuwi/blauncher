package com.example.launcher.ui;

import com.example.launcher.model.Palette;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The palette editor the Settings screen shows: a background editor at the top, then
 * one colour picker per token, then a row per remaining gradient.
 *
 * <p>Kept as a self-contained component rather than inlined into the controller so
 * that it can be exercised without a launcher: the tests build it, drive the
 * controls and check the palettes it produces.
 *
 * <p>Three details worth knowing:
 * <ul>
 *   <li>The background has an editor of its own. It is the surface every other colour
 *       is judged against and the one thing the owner reported as "не меняется", so it
 *       gets the largest control on the screen: shape (flat colour or gradient),
 *       direction, its stops, and a preview the size of a panel. The first stop can be
 *       tied to the background colour, which is what makes "Фон приложения" reach the
 *       background instead of stopping at a fixed hex value in the gradient.</li>
 *   <li>Some colour tokens hold {@code rgba(...)} - the scrim and the two veils.
 *       A {@code ColorPicker} has no alpha, so the alpha of the original value is
 *       remembered and re-applied, instead of silently making the scrim opaque.</li>
 *   <li>Gradient stops may carry a position ({@code #1b1e25 40%}); the position is
 *       preserved when the colour of that stop is changed.</li>
 * </ul>
 */
public final class PaletteEditor {

    /** Russian labels for the tokens, in the order the editor shows them. */
    public static final Map<String, String> TOKEN_LABELS = new LinkedHashMap<>();

    static {
        TOKEN_LABELS.put("bg", "Фон приложения");
        TOKEN_LABELS.put("surface", "Панели и хотбар");
        TOKEN_LABELS.put("surface-2", "Карточки и поля");
        TOKEN_LABELS.put("line", "Разделители");
        TOKEN_LABELS.put("line-strong", "Рамки бейджей");
        TOKEN_LABELS.put("console-bg", "Фон консоли");
        TOKEN_LABELS.put("scrim", "Затемнение под окном");
        TOKEN_LABELS.put("launch-veil", "Затемнение при запуске");
        TOKEN_LABELS.put("hover-veil", "Подсветка при наведении");
        TOKEN_LABELS.put("text", "Основной текст");
        TOKEN_LABELS.put("text-muted", "Второстепенный текст");
        TOKEN_LABELS.put("text-dim", "Подсказки");
        TOKEN_LABELS.put("text-on-accent", "Текст на кнопке «Играть»");
        TOKEN_LABELS.put("text-on-danger", "Текст на красной кнопке");
        TOKEN_LABELS.put("text-on-veil", "Текст на затемнении запуска");
        TOKEN_LABELS.put("accent", "Акцент");
        TOKEN_LABELS.put("accent-hover", "Акцент: наведение");
        TOKEN_LABELS.put("ok", "Успех");
        TOKEN_LABELS.put("warn", "Предупреждение");
        TOKEN_LABELS.put("danger", "Ошибка");
        TOKEN_LABELS.put("danger-text", "Текст ошибки");
        TOKEN_LABELS.put("grad-bg", "Градиент фона");
        TOKEN_LABELS.put("grad-mark", "Градиент плитки сборки");
        TOKEN_LABELS.put("grad-play", "Градиент кнопки «Играть»");
        TOKEN_LABELS.put("grad-play-year", "Градиент кнопки для 26.x");
    }

    /** Directions offered for a gradient, with the JavaFX spelling as the value. */
    private static final Map<String, String> DIRECTIONS = new LinkedHashMap<>();

    static {
        DIRECTIONS.put("to bottom", "сверху вниз");
        DIRECTIONS.put("to right", "слева вправо");
        DIRECTIONS.put("to bottom right", "по диагонали");
        DIRECTIONS.put("to top right", "по диагонали вверх");
    }

    /** The two background shapes, keyed by the label the chooser shows. */
    private static final Map<String, String> BACKGROUND_KINDS = new LinkedHashMap<>();

    static {
        BACKGROUND_KINDS.put("Градиент", Palette.BG_GRADIENT);
        BACKGROUND_KINDS.put("Один цвет", Palette.BG_SOLID);
    }

    private static final Pattern GRADIENT =
            Pattern.compile("linear-gradient\\(\\s*([^,]+?)\\s*,(.*)\\)", Pattern.DOTALL);
    private static final Pattern STOP =
            Pattern.compile("(#[0-9a-fA-F]{3,8})\\s*([0-9.]+%?)?");

    private final Palette working;
    private final Consumer<Palette> onChange;
    private final VBox backgroundBox = new VBox(8);
    private final VBox colorsBox = new VBox(6);
    private final VBox gradientsBox = new VBox(10);
    private final VBox root = new VBox(14);
    private final Map<String, ColorPicker> colorPickers = new LinkedHashMap<>();
    private final Map<String, List<ColorPicker>> gradientPickers = new LinkedHashMap<>();
    private final Map<String, ComboBox<String>> directionBoxes = new LinkedHashMap<>();
    private final Map<String, VBox> gradientBoxes = new LinkedHashMap<>();
    private final Map<String, Double> pendingAlphas = new LinkedHashMap<>();
    private final ComboBox<String> backgroundKindBox = new ComboBox<>();
    private final List<ColorPicker> backgroundStops = new ArrayList<>();
    private CheckBox backgroundLinkBox;
    private Label backgroundStatus;
    private Region backgroundPreview;

    public PaletteEditor(Palette initial, Consumer<Palette> onChange) {
        this.onChange = onChange == null ? palette -> { } : onChange;
        this.working = initial.copy();
        // Only colours since the 2026-10 redesign: the screen background is the End sky or a
        // picture (Settings → Фон), so the old background and gradient sections edited nothing
        // anyone could see. Their builders stay below for the palettes saved with them.
        root.getChildren().add(section("Цвета", colorsBox));
        reload(initial);
    }

    public Node node() {
        return root;
    }

    /** The palette as it currently stands in the editor. */
    public Palette palette() {
        return working.copy();
    }

    /** Refills every control from a palette (opening the editor, or Reset). */
    public void reload(Palette source) {
        Palette copy = source.copy();
        working.setColors(copy.getColors());
        working.setAlphas(copy.getAlphas());
        working.setGradients(copy.getGradients());
        working.setBackgroundType(copy.getBackgroundType());
        pendingAlphas.clear();

        // The template asks for @bg@ in more than one place, so a palette without it renders
        // nothing at all. Giving the editor a starting point is better than a launcher that
        // refuses a palette half-filled by hand.
        working.getColors().putIfAbsent("bg", "#14161B");
        // Same for the background gradient: a palette saved by an older build, or pasted in
        // by hand, may not carry one. Built from tokens, so it follows the colours.
        working.getGradients().putIfAbsent(Palette.BACKGROUND,
                "linear-gradient(to bottom right, @bg@ 55%, @surface-2@)");

        // The maps are cleared before the background editor is built, not after: the
        // background registers itself in the same maps the gradient rows use, and clearing
        // them afterwards unregistered the background's own controls.
        colorPickers.clear();
        gradientPickers.clear();
        directionBoxes.clear();
        gradientBoxes.clear();
        colorsBox.getChildren().clear();
        backgroundBox.getChildren().clear();
        for (Map.Entry<String, String> entry : working.getColors().entrySet()) {
            // The background colour belongs to the background editor, not to the colour list:
            // two pickers for one token would be two places to look for the background.
            VBox box = "bg".equals(entry.getKey()) ? backgroundBox : colorsBox;
            addColorRow(entry.getKey(), entry.getValue(), box);
        }
        addBackgroundControls();

        gradientsBox.getChildren().clear();
        for (Map.Entry<String, String> entry : working.getGradients().entrySet()) {
            if (Palette.BACKGROUND.equals(entry.getKey())) continue;   // shown by the background editor
            addGradientRow(entry.getKey(), entry.getValue());
        }
    }

    /** One colour token: its name and its swatch. */
    private void addColorRow(String token, String value, VBox box) {
        Double alpha = alphaOf(value);
        pendingAlphas.put(token, alpha);
        ColorPicker picker = new ColorPicker(Color.web(value, alpha == null ? 1 : alpha));
        picker.setMaxWidth(Double.MAX_VALUE);
        picker.setOnAction(e -> {
            String hex = toHex(picker.getValue());
            Double kept = pendingAlphas.get(token);
            working.getColors().put(token, kept == null ? hex : rgba(hex, kept));
            // A gradient may name this colour as one of its stops - the app background
            // starts at @bg@ - so the gradient previews have to be redrawn as well.
            paintAllGradients();
            onChange.accept(palette());
        });
        colorPickers.put(token, picker);

        HBox row = new HBox(10);
        Label label = new Label(TOKEN_LABELS.getOrDefault(token, token));
        label.getStyleClass().add("field-label");
        label.setMinWidth(210);
        HBox.setHgrow(picker, Priority.ALWAYS);
        row.getChildren().addAll(label, picker);
        box.getChildren().add(row);
    }

    /**
     * The background editor: what shape the background is, which way it runs, its stops,
     * and a preview big enough to judge a colour on.
     *
     * <p>The reason it exists as a section of its own: the owner's report was that the
     * launcher's background did not change when the palette was edited. The cause was a
     * gradient of two fixed hex values painted over the background colour, and a control
     * labelled "Фон приложения" that reached only the layer underneath it. Here the two
     * are on one screen, the tie between them is a labelled checkbox, and the preview
     * shows the result.
     */
    private void addBackgroundControls() {
        backgroundStops.clear();

        backgroundKindBox.getItems().setAll(BACKGROUND_KINDS.keySet());
        backgroundKindBox.setValue(working.isSolidBackground() ? "Один цвет" : "Градиент");
        backgroundKindBox.setOnAction(e -> {
            working.setBackgroundType(BACKGROUND_KINDS.get(backgroundKindBox.getValue()));
            refreshBackgroundPreview();
            onChange.accept(palette());
        });
        backgroundBox.getChildren().add(labelledRow("Форма фона", backgroundKindBox));

        Parsed parsed = parseGradient(working.getGradients().get(Palette.BACKGROUND), this::resolveToken);

        ComboBox<String> direction = new ComboBox<>();
        direction.getItems().addAll(DIRECTIONS.keySet());
        direction.setValue(parsed.direction);
        direction.setOnAction(e -> {
            working.getGradients().put(Palette.BACKGROUND, buildGradient(direction.getValue(),
                    parseGradient(working.getGradients().get(Palette.BACKGROUND), this::resolveToken).stops));
            refreshBackgroundPreview();
            onChange.accept(palette());
        });
        directionBoxes.put(Palette.BACKGROUND, direction);
        backgroundBox.getChildren().add(labelledRow("Направление", direction));

        HBox stops = new HBox(8);
        for (int index = 0; index < parsed.stops.size(); index++) {
            int stopIndex = index;
            ColorPicker picker = new ColorPicker(Color.web(parsed.stops.get(index).color()));
            picker.setOnAction(e -> {
                Parsed current = parseGradient(working.getGradients().get(Palette.BACKGROUND), this::resolveToken);
                List<Stop> updated = new ArrayList<>(current.stops);
                Stop old = updated.get(stopIndex);
                // Picking a colour is an explicit choice, so the stop stops following its token.
                updated.set(stopIndex, new Stop(toHex(picker.getValue()), old.position(), null));
                working.getGradients().put(Palette.BACKGROUND, buildGradient(current.direction(), updated));
                if (stopIndex == 0) syncBackgroundLink();
                refreshBackgroundPreview();
                onChange.accept(palette());
            });
            backgroundStops.add(picker);
            stops.getChildren().add(picker);
        }
        gradientPickers.put(Palette.BACKGROUND, new ArrayList<>(backgroundStops));
        if (!backgroundStops.isEmpty()) {
            HBox stopRow = new HBox(10);
            Label label = new Label("Цвета градиента");
            label.getStyleClass().add("field-label");
            label.setMinWidth(210);
            stopRow.getChildren().addAll(label, stops);
            backgroundBox.getChildren().add(stopRow);
        }

        // The tie between the background colour and the gradient. Unchecked means the
        // first stop keeps the colour it was given and will not follow "Фон приложения".
        backgroundLinkBox = new CheckBox("Первый цвет градиента = цвет фона");
        backgroundLinkBox.getStyleClass().add("check-box");
        backgroundLinkBox.setOnAction(e -> {
            Parsed current = parseGradient(working.getGradients().get(Palette.BACKGROUND), this::resolveToken);
            List<Stop> updated = new ArrayList<>(current.stops);
            if (updated.isEmpty()) return;
            Stop first = updated.get(0);
            if (backgroundLinkBox.isSelected()) {
                updated.set(0, new Stop(resolveToken("bg"), first.position(), "@bg@"));
            } else {
                updated.set(0, new Stop(resolveToken("bg"), first.position(), null));
            }
            working.getGradients().put(Palette.BACKGROUND, buildGradient(current.direction(), updated));
            refreshBackgroundPreview();
            onChange.accept(palette());
        });
        backgroundBox.getChildren().add(backgroundLinkBox);

        backgroundPreview = new Region();
        backgroundPreview.getStyleClass().add("palette-bg-preview");
        backgroundPreview.setMinHeight(96);
        backgroundPreview.setPrefHeight(96);

        backgroundStatus = new Label();
        backgroundStatus.getStyleClass().add("palette-hint");
        backgroundStatus.setWrapText(true);

        VBox preview = new VBox(6, backgroundPreview, backgroundStatus);
        VBox.setVgrow(backgroundPreview, Priority.NEVER);
        backgroundBox.getChildren().add(preview);
        syncBackgroundLink();
        refreshBackgroundPreview();
    }

    /** Keeps the checkbox in step with the first stop, whatever changed it. */
    private void syncBackgroundLink() {
        if (backgroundLinkBox == null) return;
        Parsed current = parseGradient(working.getGradients().get(Palette.BACKGROUND), this::resolveToken);
        backgroundLinkBox.setSelected(!current.stops().isEmpty()
                && "@bg@".equals(current.stops().get(0).token()));
    }

    /** Redraws the background preview and greys out what the chosen shape does not use. */
    private void refreshBackgroundPreview() {
        if (backgroundPreview == null) return;
        String css = resolveCss(working.background());
        backgroundPreview.setStyle("-fx-background-radius: 10; -fx-background-color: "
                + (css == null || css.isBlank() ? "#000000" : css) + ";");

        boolean solid = working.isSolidBackground();
        for (ColorPicker picker : backgroundStops) picker.setDisable(solid);
        ComboBox<String> direction = directionBoxes.get(Palette.BACKGROUND);
        if (direction != null) direction.setDisable(solid);
        if (backgroundLinkBox != null) backgroundLinkBox.setDisable(solid);
        if (backgroundStatus != null) {
            backgroundStatus.setText(solid
                    ? "Фон — один цвет: «Фон приложения» заливает всё окно, градиент не используется."
                    : "Фон — градиент. «Фон приложения» задаёт цвет, а ступени ниже — как он "
                            + "распределяется по окну.");
        }
    }

    /** A label and a control on one line, with the label column the colour rows use. */
    private static HBox labelledRow(String text, Node control) {
        HBox row = new HBox(10);
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        label.setMinWidth(210);
        HBox.setHgrow(control, Priority.ALWAYS);
        row.getChildren().addAll(label, control);
        return row;
    }

    private void addGradientRow(String token, String value) {
        // Tokens are resolved for display: a stop may be "@bg@", and the picker has to show
        // the colour that actually ends up on screen.
        Parsed parsed = parseGradient(value, this::resolveToken);
        VBox box = new VBox(6);

        HBox head = new HBox(10);
        Label label = new Label(TOKEN_LABELS.getOrDefault(token, token));
        label.getStyleClass().add("field-label");
        label.setMinWidth(210);

        ComboBox<String> direction = new ComboBox<>();
        for (Map.Entry<String, String> option : DIRECTIONS.entrySet()) {
            direction.getItems().add(option.getKey());
        }
        direction.setValue(parsed.direction);
        direction.setOnAction(e -> {
            working.getGradients().put(token, buildGradient(direction.getValue(), parseGradient(
                    working.getGradients().get(token), this::resolveToken).stops));
            refreshPreview(token, box);
            onChange.accept(palette());
        });
        head.getChildren().addAll(label, direction);
        directionBoxes.put(token, direction);

        HBox stops = new HBox(8);
        Region preview = new Region();
        preview.setPrefHeight(18);
        preview.setMinHeight(18);
        preview.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(preview, Priority.ALWAYS);
        preview.setStyle("-fx-background-radius: 6;");

        for (int index = 0; index < parsed.stops.size(); index++) {
            int stopIndex = index;
            ColorPicker picker = new ColorPicker(Color.web(parsed.stops.get(index).color));
            picker.setOnAction(e -> {
                Parsed current = parseGradient(working.getGradients().get(token), this::resolveToken);
                List<Stop> updated = new ArrayList<>(current.stops);
                Stop old = updated.get(stopIndex);
                // Picking a colour is an explicit choice, so the stop stops following its token.
                updated.set(stopIndex, new Stop(toHex(picker.getValue()), old.position(), null));
                working.getGradients().put(token, buildGradient(current.direction, updated));
                refreshPreview(token, box);
                onChange.accept(palette());
            });
            stops.getChildren().add(picker);
        }
        gradientPickers.put(token, new ArrayList<>(stops.getChildren().stream()
                .filter(node -> node instanceof ColorPicker)
                .map(node -> (ColorPicker) node)
                .toList()));
        stops.getChildren().add(preview);
        box.getChildren().addAll(head, stops);
        gradientsBox.getChildren().add(box);
        gradientBoxes.put(token, box);
        refreshPreview(token, box);
    }

    /** A token's current value, or null when the palette does not define it. */
    private String resolveToken(String token) {
        return working.value(token);
    }

    /** Redraws every gradient preview - used when a colour a gradient refers to changes. */
    private void paintAllGradients() {
        for (Map.Entry<String, VBox> entry : gradientBoxes.entrySet()) {
            refreshPreview(entry.getKey(), entry.getValue());
        }
        refreshBackgroundPreview();
    }

    /**
     * A CSS value with every token reference replaced, ready for an inline style.
     *
     * <p>Repeated in passes because one token may name another: the background value can be
     * {@code @bg@} while a gradient stop is {@code @surface-2@}. JavaFX refuses a value that
     * still holds an {@code @}, and a refused inline style paints nothing at all - which is
     * how a preview silently goes blank instead of being obviously wrong.
     */
    private String resolveCss(String value) {
        if (value == null) return null;
        String resolved = value;
        for (int pass = 0; pass < 4 && resolved.indexOf('@') >= 0; pass++) {
            resolved = TOKEN_IN_GRADIENT.matcher(resolved).replaceAll(match -> {
                String token = resolveToken(match.group(1));
                return java.util.regex.Matcher.quoteReplacement(token == null ? "" : token);
            });
        }
        return resolved;
    }

    private void refreshPreview(String token, VBox box) {
        // The stored gradient may name tokens ("@bg@ 55%"); the preview needs the colours
        // behind them, and JavaFX would refuse the inline style otherwise.
        String resolved = resolveCss(working.getGradients().get(token));
        for (Node node : box.getChildren()) {
            if (node instanceof HBox row) {
                for (Node child : row.getChildren()) {
                    if (child instanceof Region preview && !(child instanceof ColorPicker) && preview != row) {
                        preview.setStyle("-fx-background-radius: 6; -fx-background-color: " + resolved + ";");
                    }
                }
            }
        }
    }

    /** A token reference inside a gradient value, e.g. the @bg@ in "@bg@ 55%". */
    private static final Pattern TOKEN_IN_GRADIENT = Pattern.compile("@([a-z0-9-]+)@");

    private static VBox section(String title, VBox content) {
        Label heading = new Label(title);
        heading.getStyleClass().add("panel-title");
        VBox box = new VBox(8, heading, content);
        box.setFillWidth(true);
        return box;
    }

    // ------------------------------------------------------------- gradients ---

    /** A gradient stop: its colour, the position it had, and the token it came from, if any. */
    public record Stop(String color, String position, String token) {}

    public record Parsed(String direction, List<Stop> stops) {}

    /** Splits "linear-gradient(to right, #fff, #eee 45%)" into its parts. */
    public static Parsed parseGradient(String gradient) {
        return parseGradient(gradient, token -> null);
    }

    /**
     * Same, but resolving token stops through {@code resolver} for display.
     *
     * A stop may be a token rather than a colour - the app background's gradient starts at
     * {@code @bg@} so that it follows the background colour the player picks. The raw token
     * is kept beside the resolved colour, and written back unchanged unless the player
     * explicitly picks a colour for that stop; dropping it on load would silently detach the
     * background from the background token.
     */
    public static Parsed parseGradient(String gradient, java.util.function.Function<String, String> resolver) {
        if (gradient == null) return new Parsed("to bottom", List.of());
        Matcher head = GRADIENT.matcher(gradient.trim());
        if (!head.matches()) return new Parsed("to bottom", List.of());
        String direction = head.group(1).trim();

        List<Stop> stops = new ArrayList<>();
        for (String part : head.group(2).split(",")) {
            String text = part.trim();
            if (text.isEmpty()) continue;
            String value;
            String position = "";
            if (text.startsWith("@")) {
                int end = text.indexOf('@', 1);
                if (end < 0) continue;
                value = text.substring(0, end + 1);
                position = text.substring(end + 1).trim();
            } else {
                Matcher stop = STOP.matcher(text);
                if (!stop.matches()) continue;
                value = stop.group(1);
                position = stop.group(2) == null ? "" : stop.group(2).trim();
            }
            if (value.startsWith("@")) {
                String resolved = resolver.apply(value.substring(1, value.length() - 1));
                stops.add(new Stop(resolved == null ? value : resolved, position, value));
            } else {
                stops.add(new Stop(value, position, null));
            }
        }
        return new Parsed(direction, stops);
    }

    /** Rebuilds a gradient, keeping each stop's position and any token it was defined with. */
    public static String buildGradient(String direction, List<Stop> stops) {
        StringBuilder out = new StringBuilder("linear-gradient(").append(direction);
        for (Stop stop : stops) {
            out.append(", ").append(stop.token() != null ? stop.token() : stop.color());
            if (!stop.position().isBlank()) out.append(' ').append(stop.position());
        }
        return out.append(')').toString();
    }

    // ---------------------------------------------------------------- colour ---

    /** "#rrggbb" for a picked colour. */
    public static String toHex(Color color) {
        return String.format("#%02X%02X%02X",
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255));
    }

    /** The alpha of an {@code rgba(...)} value, or null when the value is a hex. */
    public static Double alphaOf(String value) {
        if (value == null || !value.toLowerCase().startsWith("rgba(")) return null;
        String[] parts = value.substring(value.indexOf('(') + 1, value.lastIndexOf(')')).split(",");
        if (parts.length < 4) return null;
        try {
            return Double.parseDouble(parts[3].trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String rgba(String hex, double alpha) {
        int red = Integer.parseInt(hex.substring(1, 3), 16);
        int green = Integer.parseInt(hex.substring(3, 5), 16);
        int blue = Integer.parseInt(hex.substring(5, 7), 16);
        return String.format("rgba(%d,%d,%d,%s)", red, green, blue,
                alpha == Math.rint(alpha) ? String.valueOf((int) alpha) : String.valueOf(alpha));
    }

    /** Exposed for tests: the picker for a token, so edits can be driven in code. */
    public ColorPicker picker(String token) {
        return colorPickers.get(token);
    }

    /** Exposed for tests: the stop pickers of a gradient. */
    public List<ColorPicker> gradientPickers(String token) {
        return gradientPickers.getOrDefault(token, List.of());
    }

    /** Exposed for tests: the chooser between a flat and a gradient background. */
    public ComboBox<String> backgroundKindBox() {
        return backgroundKindBox;
    }

    /** Exposed for tests: the background preview. */
    public Region backgroundPreview() {
        return backgroundPreview;
    }
}
