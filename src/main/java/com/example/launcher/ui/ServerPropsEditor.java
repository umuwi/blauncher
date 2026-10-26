package com.example.launcher.ui;

import com.example.launcher.service.ServerProps;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Плитки server.properties для окна «Новый сервер» и формы установки на VPS (17.10.2026).
 * Каждая плитка — подпись, переключатель / число / список / поле и строка «ключ=значение» под
 * ней, чтобы было видно, что именно попадёт в файл. Проверка значений — ServerProps.cleanAll.
 */
public final class ServerPropsEditor {

    private final Function<String, String> tr;
    private final GridPane grid = new GridPane();
    private final Map<String, Supplier<String>> readers = new LinkedHashMap<>();
    private final Map<String, java.util.function.Consumer<String>> writers = new LinkedHashMap<>();
    private final java.util.List<Runnable> captions = new java.util.ArrayList<>();

    /** @param columns сколько плиток в ряд: 3 на широком экране, 2 в окне поуже */
    public ServerPropsEditor(Function<String, String> tr, int columns) {
        this.tr = tr;
        grid.getStyleClass().add("sp-grid");
        grid.setHgap(10);
        grid.setVgap(10);
        for (int i = 0; i < columns; i++) {
            ColumnConstraints c = new ColumnConstraints();
            c.setPercentWidth(100.0 / columns);
            c.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(c);
        }
        int col = 0, row = 0;
        for (ServerProps.Field f : ServerProps.FIELDS) {
            Node tile = tile(f);
            if (f.wide()) {
                if (col != 0) { row++; col = 0; }
                grid.add(tile, 0, row, columns, 1);
                row++;
            } else {
                grid.add(tile, col, row);
                if (++col == columns) { col = 0; row++; }
            }
        }
        reset();
    }

    public Node node() {
        return grid;
    }

    /** Значения как есть, без проверки: ServerProps.cleanAll скажет, что не так. */
    public Map<String, String> values() {
        Map<String, String> m = new LinkedHashMap<>();
        readers.forEach((k, r) -> m.put(k, r.get()));
        return m;
    }

    /** Ванильные значения (и два лаунчерных — см. ServerProps.FIELDS). */
    public void reset() {
        ServerProps.defaults().forEach((k, v) -> {
            var w = writers.get(k);
            if (w != null) w.accept(v);
        });
        // Подпись «ключ=» обновляется по изменению значения, а пустое поле, которому снова дали
        // пустоту, не меняется — у resource-pack и level-seed подпись оставалась пустой.
        captions.forEach(Runnable::run);
    }

    private Node tile(ServerProps.Field f) {
        Label name = new Label(tr.apply(f.labelKey()));
        name.getStyleClass().add("sp-label");
        name.setWrapText(true);
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);

        Label caption = new Label();
        caption.getStyleClass().add("sp-key");
        Runnable[] refresh = new Runnable[1];

        Node control = switch (f.kind()) {
            case INT -> {
                TextField field = new TextField();
                field.getStyleClass().addAll("text-input", "mono", "sp-num");
                field.setPrefColumnCount(4);
                Button plus = stepButton("+");
                Button minus = stepButton("−");
                plus.setOnAction(e -> field.setText(String.valueOf(clamp(f, parse(field.getText(), f) + 1))));
                minus.setOnAction(e -> field.setText(String.valueOf(clamp(f, parse(field.getText(), f) - 1))));
                field.focusedProperty().addListener((o, was, now) -> {
                    if (!now) field.setText(String.valueOf(clamp(f, parse(field.getText(), f))));
                });
                field.textProperty().addListener((o, a, b) -> refresh[0].run());
                readers.put(f.key(), field::getText);
                writers.put(f.key(), field::setText);
                VBox steps = new VBox(2, plus, minus);
                HBox box = new HBox(4, field, steps);
                box.setAlignment(Pos.CENTER_RIGHT);
                yield box;
            }
            case BOOL -> {
                ToggleButton sw = new ToggleButton();
                sw.getStyleClass().add("switch-toggle");
                sw.selectedProperty().addListener((o, a, b) -> refresh[0].run());
                readers.put(f.key(), () -> String.valueOf(sw.isSelected() != f.inverted()));
                writers.put(f.key(), v -> sw.setSelected(Boolean.parseBoolean(v) != f.inverted()));
                yield sw;
            }
            case CHOICE -> {
                ComboBox<String> combo = new ComboBox<>();
                combo.getStyleClass().add("combo-box");
                combo.getItems().setAll(f.choices());
                combo.setConverter(new StringConverter<>() {
                    @Override public String toString(String v) { return v == null ? "" : tr.apply(f.labelKey() + "_" + v); }
                    @Override public String fromString(String s) { return null; }
                });
                combo.setPrefWidth(150);
                combo.valueProperty().addListener((o, a, b) -> refresh[0].run());
                readers.put(f.key(), () -> combo.getValue() == null ? f.def() : combo.getValue());
                writers.put(f.key(), combo::setValue);
                yield combo;
            }
            default -> {
                TextField field = new TextField();
                field.getStyleClass().add("text-input");
                // _hint, а не _prompt: sp_resource_pack_prompt — уже подпись настройки resource-pack-prompt.
                String hint = tr.apply(f.labelKey() + "_hint");
                if (!hint.equals(f.labelKey() + "_hint")) field.setPromptText(hint);
                HBox.setHgrow(field, Priority.ALWAYS);
                field.setMaxWidth(Double.MAX_VALUE);
                field.setPrefWidth(360);
                field.textProperty().addListener((o, a, b) -> refresh[0].run());
                readers.put(f.key(), field::getText);
                writers.put(f.key(), field::setText);
                yield field;
            }
        };
        refresh[0] = () -> caption.setText(f.key() + "=" + readers.get(f.key()).get());
        captions.add(refresh[0]);

        HBox top = new HBox(10, name, control);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox tile = new VBox(6, top, caption);
        tile.getStyleClass().add("sp-tile");
        tile.setMaxWidth(Double.MAX_VALUE);
        return tile;
    }

    private static Button stepButton(String text) {
        Button b = new Button(text);
        b.getStyleClass().add("sp-step");
        b.setFocusTraversable(false);
        return b;
    }

    private static int parse(String s, ServerProps.Field f) {
        try {
            return Integer.parseInt(s == null ? "" : s.trim());
        } catch (NumberFormatException e) {
            return Integer.parseInt(f.def());
        }
    }

    private static int clamp(ServerProps.Field f, int n) {
        return Math.max(f.min(), Math.min(f.max(), n));
    }
}
