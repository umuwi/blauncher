package com.example.launcher.ui.texpack;

import com.example.launcher.ui.ColorPanel;
import com.example.launcher.ui.studio.McAssets;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.kordamp.ikonli.javafx.FontIcon;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Painter for texture packs: every texture of an installed game version and of the current build's
 * mods, listed on the left, painted in the middle, with the tools on the right.
 *
 * <p>Paint is saved by itself into a project folder, a moment after each stroke. All file work runs
 * on one background thread in the order it was asked for, so exporting after painting always packs
 * the last stroke.
 */
public final class TexturePackEditor implements AutoCloseable {

    /** What the editor needs from the launcher. All of it is asked for on the FX thread. */
    public interface Host {
        String tr(String key);
        void toast(String message, String type);
        Path versionsDir();
        /** Where the projects are kept. */
        Path projectsDir();
        /** The version to open first; null or unknown means the newest installed. */
        String preferredVersion();
        /** The current build's mods folder, or null without a build. */
        Path modsDir();
        /** Every folder an installed pack goes to. */
        List<Path> resourcePackDirs();
        String language();
        /** Отметка в статистику и строка в журнал лаунчера. */
        default void stat(String detail) { }
        default void log(String message) { }
        /** «Инвертировать мышь» из настроек: как в остальных 3D-редакторах. */
        default boolean invertMouse() { return false; }
    }

    private enum Tool { PENCIL, ERASER, FILL, BLUR, LIGHTEN, DARKEN, NOISE, REPLACE, PICKER }

    /** One line of the list. */
    private record Entry(String path, String category, String name, String search) {}

    private static final int UNDO_LIMIT = 60;
    /** Undo keeps whole textures; a big one must not take a gigabyte of memory. */
    private static final long UNDO_PIXELS = 16_000_000;
    private static final int MAX_CANVAS = 4096;
    private static final int MAX_CELL = 48;
    private static final String ALL = "all";
    private static final List<String> CATEGORIES = List.of(ALL, "block", "item", "entity", "gui", "environment",
            "particle", "painting", "mob_effect", "map", "misc", "models", "trims", "palettes", "colormap", "font",
            "effect", "mods", "pack", "other");
    /** Textures the game colours itself by biome: grey on purpose, painted in colour they come out muddy. */
    private static final Pattern TINTED = Pattern.compile("^(grass_block_top|grass_block_side_overlay|short_grass|grass"
            + "|tall_grass_.*|fern|large_fern_.*|vine|lily_pad|sugar_cane|bush"
            + "|(oak|spruce|birch|jungle|acacia|dark_oak|mangrove)_leaves|water_.*"
            + "|(attached_)?(melon|pumpkin)_stem|redstone_dust_.*)$");
    private static final int CHECK_LIGHT = 0xFF3A3F47;
    private static final int CHECK_DARK = 0xFF2F333A;

    private final Host host;
    private final String language;
    private final Path modsDir;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> daemon(r, "texpack-io"));
    private final ExecutorService thumbsIo = Executors.newSingleThreadExecutor(r -> daemon(r, "texpack-thumbs"));

    private volatile TextureCatalog catalog;
    private volatile PackProject project;
    private List<Entry> entries = List.of();
    private Set<String> changed = new HashSet<>();
    private boolean updating;

    private Entry current;
    private PixelOps.Sheet sheet;
    private byte[] currentMeta;
    private boolean dirty;
    private int token;
    private int frameRows;
    private int frame;
    private int cell = 16;
    private boolean autoFit = true;
    private boolean grid = true;

    private Tool tool = Tool.PENCIL;
    private Color color = Color.web("#2fb5a6");
    /** Непрозрачность кисти, 0..1: ColorPanel выбирает только оттенок. */
    private double opacity = 1;
    private Slider opacitySlider;
    private Label opacityLabel;
    private int brush = 1;
    private final Deque<PixelOps.Sheet> undo = new ArrayDeque<>();
    private final Deque<PixelOps.Sheet> redo = new ArrayDeque<>();
    private boolean[] touched = new boolean[0];
    private boolean strokeChanged;
    private boolean stroking;
    private int lastX = -1;
    private int lastY = -1;
    private long lastPreview;
    private final Random random = new Random();
    private final PauseTransition saveSoon = new PauseTransition(Duration.millis(1200));

    private final ComboBox<String> projectBox = new ComboBox<>();
    private final TextField nameField = new TextField();
    private final TextField descriptionField = new TextField();
    private final ComboBox<TextureCatalog.Version> versionBox = new ComboBox<>();
    private final ComboBox<String> categoryBox = new ComboBox<>();
    private final TextField search = new TextField();
    private final ToggleButton onlyChanged = new ToggleButton();
    private final ListView<Entry> list = new ListView<>();

    private final Canvas canvas = new Canvas(16, 16);
    private final ScrollPane canvasScroll = new ScrollPane();
    /** Вид 3D: объект, который можно крутить и рисовать на нём с любой стороны. */
    private TextureModelView modelView;
    private ToggleButton view3d;
    /** Модели игры открытой версии; открываются при первом включении 3D, это пара секунд. */
    private McAssets modelAssets;
    private TextureCatalog.Version openVersion;
    private boolean model3dReported;
    private boolean paint3dReported;
    private Map<String, String> blockByTexture;
    private final Label title = new Label();
    private final Label info = new Label();
    private final Label tintHint = new Label();
    private final Label frameLabel = new Label();
    private final HBox frameRow = new HBox(6);
    private final Canvas preview = new Canvas(96, 96);
    private Button resetButton;
    private ToggleGroup toolGroup;
    private ColorPanel colorPanel;

    private final Map<String, Image> thumbs = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
            return size() > 400;
        }
    };

    private final HBox root;

    public TexturePackEditor(Host host) {
        this.host = host;
        this.language = host.language();
        this.modsDir = host.modsDir();
        saveSoon.setOnFinished(e -> flush());

        SplitPane split = new SplitPane(buildBrowser(), buildCanvasPanel(), buildToolPanel());
        split.getStyleClass().add("panel-split");
        split.setDividerPositions(0.27, 0.77);
        root = new HBox(split);
        HBox.setHgrow(split, Priority.ALWAYS);
        root.setMinSize(0, 0);
        root.setFocusTraversable(true);
        root.setOnKeyPressed(e -> {
            if (e.getTarget() instanceof TextInputControl) return;   // letters typed in a field are not tool keys
            if (e.isControlDown() && e.getCode() == KeyCode.Z) undo();
            else if (e.isControlDown() && e.getCode() == KeyCode.Y) redo();
            else if (e.getCode() == KeyCode.B) selectTool(Tool.PENCIL);
            else if (e.getCode() == KeyCode.E) selectTool(Tool.ERASER);
            else if (e.getCode() == KeyCode.G) selectTool(Tool.FILL);
            else if (e.getCode() == KeyCode.I) selectTool(Tool.PICKER);
            else if (e.getCode() == KeyCode.U) selectTool(Tool.BLUR);
        });
        showNothing();
        start();
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    public Node node() {
        return root;
    }

    /** Saves what is unsaved, then lets the threads finish it and closes the jars. */
    @Override
    public void close() {
        saveSoon.stop();
        flush();
        thumbsIo.shutdownNow();
        io.submit(() -> {
            TextureCatalog c = catalog;
            if (c != null) c.close();
            if (modelAssets != null) modelAssets.close();
        });
        io.shutdown();
    }

    // ------------------------------------------------------------------ layout

    private Node buildBrowser() {
        VBox box = new VBox(8);
        box.getStyleClass().add("panel");
        box.setPadding(new Insets(14));
        box.setMinWidth(250);

        projectBox.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(projectBox, Priority.ALWAYS);
        projectBox.setOnAction(e -> {
            String name = projectBox.getValue();
            if (!updating && name != null) switchProject(new PackProject(host.projectsDir().resolve(name)));
        });
        Button add = iconButton("fth-plus", "texpack_new", this::newProject);
        Button open = iconButton("fth-folder", "texpack_import", this::importPack);
        HBox projectRow = new HBox(6, projectBox, add, open);
        projectRow.setAlignment(Pos.CENTER_LEFT);

        nameField.getStyleClass().add("text-input");
        nameField.setPromptText(host.tr("texpack_name"));
        nameField.setOnAction(e -> commitName());
        nameField.focusedProperty().addListener((o, was, now) -> { if (!now) commitName(); });
        descriptionField.getStyleClass().add("text-input");
        descriptionField.setPromptText(host.tr("texpack_description"));
        descriptionField.setOnAction(e -> commitDescription());
        descriptionField.focusedProperty().addListener((o, was, now) -> { if (!now) commitDescription(); });

        versionBox.setMaxWidth(Double.MAX_VALUE);
        versionBox.setConverter(new StringConverter<>() {
            @Override public String toString(TextureCatalog.Version v) { return v == null ? "" : v.id(); }
            @Override public TextureCatalog.Version fromString(String s) { return null; }
        });
        versionBox.setOnAction(e -> {
            if (!updating && versionBox.getValue() != null) loadCatalog(versionBox.getValue());
        });

        categoryBox.setMaxWidth(Double.MAX_VALUE);
        categoryBox.setConverter(new StringConverter<>() {
            @Override public String toString(String c) { return categoryLabel(c); }
            @Override public String fromString(String s) { return null; }
        });
        categoryBox.setOnAction(e -> { if (!updating) applyFilter(); });

        search.getStyleClass().add("text-input");
        search.setPromptText(host.tr("texpack_search"));
        search.textProperty().addListener((o, a, b) -> applyFilter());
        onlyChanged.setText(host.tr("texpack_only_changed"));
        onlyChanged.getStyleClass().add("secondary-button");
        onlyChanged.setMaxWidth(Double.MAX_VALUE);
        onlyChanged.selectedProperty().addListener((o, a, b) -> applyFilter());

        list.setId("texpack-list");
        list.getStyleClass().add("console-view");
        list.setCellFactory(v -> new EntryCell());
        list.setPlaceholder(wrapped(host.tr("texpack_nothing_found")));
        list.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> {
            if (b != null && b != current) openTexture(b);
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        box.getChildren().addAll(sectionLabel("texpack_project"), projectRow, nameField, descriptionField,
                sectionLabel("texpack_from_version"), versionBox, sectionLabel("texpack_textures"),
                categoryBox, search, onlyChanged, list);
        return box;
    }

    private Node buildCanvasPanel() {
        canvas.setId("texpack-canvas");
        canvas.setOnMousePressed(e -> {
            root.requestFocus();
            if (sheet == null) return;
            int x = (int) Math.floor(e.getX() / cell);
            int y = (int) Math.floor(e.getY() / cell) + top();
            if (x < 0 || x >= sheet.w() || y < top() || y >= top() + frameRows) return;
            if (e.getButton() == MouseButton.SECONDARY) {
                pick(x, y);
                return;
            }
            if (e.getButton() != MouseButton.PRIMARY) return;
            beginStroke();
            apply(x, y);
        });
        canvas.setOnMouseDragged(e -> {
            if (!stroking || sheet == null) return;
            if (tool == Tool.FILL || tool == Tool.REPLACE || tool == Tool.PICKER) return;
            int x = (int) Math.floor(e.getX() / cell);
            int y = (int) Math.floor(e.getY() / cell) + top();
            if (lastX < 0) {
                apply(x, y);
            } else {
                PixelOps.line(lastX, lastY, x, y, this::apply);
            }
            lastX = x;
            lastY = y;
            refreshPreview(false);
        });
        canvas.setOnMouseReleased(e -> endStroke());

        StackPane holder = new StackPane(canvas);
        holder.setAlignment(Pos.CENTER);
        holder.minWidthProperty().bind(Bindings.createDoubleBinding(
                () -> canvasScroll.getViewportBounds().getWidth(), canvasScroll.viewportBoundsProperty()));
        holder.minHeightProperty().bind(Bindings.createDoubleBinding(
                () -> canvasScroll.getViewportBounds().getHeight(), canvasScroll.viewportBoundsProperty()));
        canvasScroll.setContent(holder);
        canvasScroll.getStyleClass().add("screen-scroll");
        canvasScroll.setMinSize(0, 0);
        VBox.setVgrow(canvasScroll, Priority.ALWAYS);
        canvasScroll.viewportBoundsProperty().addListener((o, a, b) -> { if (autoFit) fitZoom(); });
        canvasScroll.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (!e.isControlDown()) return;
            zoom(e.getDeltaY() > 0 ? 1 : -1);
            e.consume();
        });

        title.getStyleClass().add("section-title");
        info.getStyleClass().add("toggle-sub");
        tintHint.getStyleClass().add("toggle-sub");
        tintHint.setWrapText(true);
        tintHint.setText(host.tr("texpack_tinted"));

        Button prev = iconButton("fth-chevron-left", "texpack_frame_prev", () -> changeFrame(-1));
        Button next = iconButton("fth-chevron-right", "texpack_frame_next", () -> changeFrame(1));
        frameLabel.getStyleClass().add("toggle-sub");
        frameRow.getChildren().addAll(prev, frameLabel, next);
        frameRow.setAlignment(Pos.CENTER_LEFT);

        Button out = iconButton("fth-zoom-out", "texpack_zoom_out", () -> zoom(-1));
        Button in = iconButton("fth-zoom-in", "texpack_zoom_in", () -> zoom(1));
        ToggleButton gridToggle = new ToggleButton();
        gridToggle.setGraphic(new FontIcon("fth-grid"));
        gridToggle.getStyleClass().add("secondary-button");
        gridToggle.setTooltip(new Tooltip(host.tr("texpack_grid")));
        gridToggle.setSelected(true);
        gridToggle.selectedProperty().addListener((o, a, b) -> {
            grid = b;
            redraw();
        });
        resetButton = new Button(host.tr("texpack_reset"));
        resetButton.setGraphic(new FontIcon("fth-rotate-ccw"));
        resetButton.setGraphicTextGap(8);
        resetButton.getStyleClass().add("secondary-button");
        // В окне минимального размера на неё остаётся ~140: подпись переносится, а не обрезается до «Вернуть ка…».
        resetButton.setWrapText(true);
        resetButton.setOnAction(e -> resetCurrent());
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        view3d = new ToggleButton("3D");
        view3d.getStyleClass().add("secondary-button");
        view3d.setTooltip(new Tooltip(host.tr("texpack_3d")));
        view3d.selectedProperty().addListener((o, a, b) -> show3d(b));
        HBox bar = new HBox(6, out, in, gridToggle, view3d, frameRow, grow, resetButton);
        bar.setAlignment(Pos.CENTER_LEFT);

        // the preview stays in sight beside the title: in the tool column it scrolled away under the colours
        VBox heading = new VBox(6, title, info, tintHint);
        heading.setMinWidth(0);
        HBox.setHgrow(heading, Priority.ALWAYS);
        Tooltip.install(preview, new Tooltip(host.tr("texpack_preview")));
        HBox top = new HBox(12, heading, preview);

        modelView = new TextureModelView(new TextureModelView.Painter() {
            @Override public void press(int x, int y) {
                if (sheet == null) return;
                if (!paint3dReported) {
                    paint3dReported = true;
                    host.stat("texpack paint3d");
                }
                beginStroke();
                apply(x, y + top());
                update3d();
            }
            @Override public void drag(int x, int y) {
                if (!stroking || sheet == null) return;
                if (tool == Tool.FILL || tool == Tool.REPLACE || tool == Tool.PICKER) return;
                int ty = y + top();
                // Переход на другую грань — это другое место развёртки: линию через всю текстуру не ведём.
                if (lastX >= 0 && Math.abs(x - lastX) <= 2 && Math.abs(ty - lastY) <= 2) {
                    PixelOps.line(lastX, lastY, x, ty, TexturePackEditor.this::apply);
                } else {
                    apply(x, ty);
                }
                lastX = x;
                lastY = ty;
                update3d();
                refreshPreview(false);
            }
            @Override public void release() {
                endStroke();
                update3d();
            }
        }, host.invertMouse());
        Node model3d = modelView.node();
        model3d.setVisible(false);
        model3d.setManaged(false);
        VBox.setVgrow(model3d, Priority.ALWAYS);
        VBox box = new VBox(8, top, canvasScroll, model3d, bar);
        box.getStyleClass().add("panel");
        box.setPadding(new Insets(14));
        box.setMinWidth(320);
        return box;
    }

    private Node buildToolPanel() {
        toolGroup = new ToggleGroup();
        VBox bar = new VBox(8);
        bar.setPadding(new Insets(14));

        FlowPane tools = new FlowPane(6, 6);
        tools.getChildren().addAll(
                toolButton(Tool.PENCIL, "fth-edit-2", "texpack_tool_pencil"),
                toolButton(Tool.ERASER, "fth-delete", "texpack_tool_eraser"),
                toolButton(Tool.FILL, "fth-droplet", "texpack_tool_fill"),
                toolButton(Tool.BLUR, "fth-wind", "texpack_tool_blur"),
                toolButton(Tool.LIGHTEN, "fth-sun", "texpack_tool_lighten"),
                toolButton(Tool.DARKEN, "fth-moon", "texpack_tool_darken"),
                toolButton(Tool.NOISE, "fth-grid", "texpack_tool_noise"),
                toolButton(Tool.REPLACE, "fth-refresh-cw", "texpack_tool_replace"),
                toolButton(Tool.PICKER, "fth-crosshair", "texpack_tool_picker"));

        HBox brushRow = new HBox(2);
        brushRow.getStyleClass().add("lang-toggle");
        ToggleGroup brushGroup = new ToggleGroup();
        for (int size = 1; size <= 4; size++) {
            ToggleButton b = new ToggleButton(Integer.toString(size));
            b.getStyleClass().add("lang-option");
            b.setToggleGroup(brushGroup);
            b.setSelected(size == brush);
            final int value = size;
            b.setOnAction(e -> {
                brush = value;
                b.setSelected(true);
            });
            brushRow.getChildren().add(b);
        }

        HBox history = new HBox(6, iconButton("fth-corner-up-left", "skin_undo", this::undo),
                iconButton("fth-corner-up-right", "skin_redo", this::redo));
        colorPanel = new ColorPanel(color, c -> color = c, host.tr("skin_recent_colors"));

        Button export = new Button(host.tr("texpack_export"));
        export.setGraphic(new FontIcon("fth-download"));
        export.setGraphicTextGap(8);
        export.getStyleClass().add("secondary-button");
        export.setMaxWidth(Double.MAX_VALUE);
        export.setAlignment(Pos.CENTER_LEFT);
        export.setOnAction(e -> exportPack());
        Button install = new Button(host.tr("texpack_install"));
        install.getStyleClass().addAll("play-btn", "modal-create-btn");
        // В колонке 250 у кнопки остаётся 180: с полями и шрифтом темы подпись обрезалась до
        // «Установить в и…» (снимок 05.11.2026). Перенос — на случай более длинного перевода.
        install.setStyle("-fx-padding: 11 8; -fx-font-size: 14px;");
        install.setWrapText(true);
        install.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        install.setMaxWidth(Double.MAX_VALUE);
        install.setOnAction(e -> installPack());

        // Прозрачный цвет (05.11.2026, просьба владельца): кистью можно класть и полупрозрачный цвет,
        // а не только стирать ластиком. В игре это видно на стекле, воде, листве и предметах; на
        // сплошных блоках игра прозрачность не показывает — об этом подсказка.
        opacityLabel = new Label();
        opacityLabel.getStyleClass().add("toggle-sub");
        opacitySlider = new Slider(0, 100, 100);
        opacitySlider.valueProperty().addListener((o, a, b) -> {
            opacity = b.doubleValue() / 100;
            opacityLabel.setText(host.tr("texpack_opacity") + ": " + Math.round(b.doubleValue()) + " %");
        });
        opacityLabel.setText(host.tr("texpack_opacity") + ": 100 %");
        Button clearColor = new Button(host.tr("texpack_transparent"));
        clearColor.getStyleClass().add("secondary-button");
        clearColor.setMaxWidth(Double.MAX_VALUE);
        clearColor.setOnAction(e -> {
            opacitySlider.setValue(0);
            selectTool(Tool.PENCIL);
        });
        Label opacityHint = wrapped(host.tr("texpack_opacity_hint"));
        opacityHint.getStyleClass().add("toggle-sub");

        bar.getChildren().addAll(sectionLabel("skin_tools"), tools, sectionLabel("skin_brush"), brushRow, history,
                sectionLabel("skin_color"), colorPanel.node(), opacityLabel, opacitySlider, clearColor, opacityHint);
        selectTool(Tool.PENCIL);

        ScrollPane scroll = new ScrollPane(bar);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("screen-scroll");
        scroll.setMinHeight(0);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        // the way out of the editor is always on screen, however short the window
        VBox footer = new VBox(6, export, install);
        footer.setPadding(new Insets(0, 14, 14, 14));
        VBox column = new VBox(scroll, footer);
        column.getStyleClass().add("panel");
        column.setMinWidth(250);
        column.setPrefWidth(250);
        column.setMinHeight(0);
        return column;
    }

    private Label sectionLabel(String key) {
        Label label = new Label(host.tr(key));
        label.getStyleClass().add("toggle-label");
        return label;
    }

    private static Label wrapped(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("toggle-sub");
        label.setWrapText(true);
        label.setPadding(new Insets(12));
        return label;
    }

    private Button iconButton(String icon, String key, Runnable action) {
        Button b = new Button();
        b.setGraphic(new FontIcon(icon));
        b.getStyleClass().add("secondary-button");
        b.setTooltip(new Tooltip(host.tr(key)));
        b.setOnAction(e -> action.run());
        return b;
    }

    private ToggleButton toolButton(Tool t, String icon, String key) {
        ToggleButton b = new ToggleButton();
        b.setGraphic(new FontIcon(icon));
        b.getStyleClass().addAll("secondary-button", "skin-tool");
        b.setToggleGroup(toolGroup);
        b.setUserData(t);
        b.setTooltip(new Tooltip(host.tr(key)));
        b.setOnAction(e -> selectTool(t));
        return b;
    }

    private void selectTool(Tool t) {
        tool = t;
        for (var toggle : toolGroup.getToggles()) toggle.setSelected(toggle.getUserData() == t);
    }

    private String categoryLabel(String category) {
        if (category == null) return "";
        return CATEGORIES.contains(category) ? host.tr("texpack_cat_" + category) : category;
    }

    // ------------------------------------------------------------------ list

    private final class EntryCell extends ListCell<Entry> {
        private final ImageView icon = new ImageView();
        private final Label name = new Label();
        private final Label path = new Label();
        private final FontIcon mark = new FontIcon("fth-edit-2");
        private final HBox box;
        private String wanted;

        EntryCell() {
            icon.setFitWidth(32);
            icon.setFitHeight(32);
            icon.setPreserveRatio(true);
            icon.setSmooth(false);
            StackPane frame = new StackPane(icon);
            frame.setMinSize(32, 32);
            frame.setPrefSize(32, 32);
            path.getStyleClass().add("toggle-sub");
            VBox text = new VBox(1, name, path);
            text.setMinWidth(0);
            HBox.setHgrow(text, Priority.ALWAYS);
            box = new HBox(8, frame, text, mark);
            box.setAlignment(Pos.CENTER_LEFT);
            mark.managedProperty().bind(mark.visibleProperty());
            setPrefWidth(0);   // a long path is cut with an ellipsis, not scrolled to
        }

        @Override
        protected void updateItem(Entry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                wanted = null;
                setGraphic(null);
                return;
            }
            wanted = item.path();
            name.setText(item.name());
            path.setText(shortPath(item.path()));
            mark.setVisible(changed.contains(item.path()));
            show(thumbs.get(item.path()));
            if (!thumbs.containsKey(item.path())) requestThumb(item.path(), this);
            setGraphic(box);
        }

        void show(Image image) {
            icon.setImage(image);
            if (image == null) return;
            int w = (int) image.getWidth(), h = (int) image.getHeight();
            // first frame of a strip: a whole strip squeezed into the icon says nothing
            icon.setViewport(h > w && w > 0 && h % w == 0 ? new Rectangle2D(0, 0, w, w) : null);
        }
    }

    private void requestThumb(String path, EntryCell cell) {
        PackProject p = project;
        TextureCatalog c = catalog;
        try {
            thumbsIo.submit(() -> {
                Image image = null;
                try {
                    byte[] bytes = p != null && p.has(path) ? p.read(path) : c == null ? null : c.read(path);
                    if (bytes != null) {
                        Image decoded = new Image(new ByteArrayInputStream(bytes));
                        if (!decoded.isError()) image = decoded;
                    }
                } catch (IOException | RuntimeException ignored) {
                    // no picture: the line still has its name
                }
                final Image loaded = image;
                Platform.runLater(() -> {
                    thumbs.put(path, loaded);
                    if (path.equals(cell.wanted)) cell.show(loaded);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // the editor has been closed
        }
    }

    private static String shortPath(String path) {
        return path.startsWith("assets/minecraft/textures/") ? path.substring(26) : path;
    }

    private void applyFilter() {
        String category = categoryBox.getValue();
        String query = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        boolean only = onlyChanged.isSelected();
        List<Entry> shown = new ArrayList<>();
        for (Entry e : entries) {
            if (category != null && !ALL.equals(category) && !category.equals(e.category())) continue;
            if (only && !changed.contains(e.path())) continue;
            if (!query.isEmpty() && !e.search().contains(query)) continue;
            shown.add(e);
        }
        list.getItems().setAll(shown);
        if (current != null) {
            for (Entry e : shown) {
                if (e.path().equals(current.path())) {
                    list.getSelectionModel().select(e);
                    break;
                }
            }
        }
    }

    /** Runs off the FX thread: the names come from the game's language file. */
    private List<Entry> buildEntries(TextureCatalog c, PackProject p, McAssets names) throws IOException {
        Set<String> paths = new java.util.LinkedHashSet<>();
        paths.add(PackProject.ICON);
        if (c != null) paths.addAll(c.paths());
        if (p != null) paths.addAll(p.pictures());
        List<Entry> out = new ArrayList<>(paths.size());
        for (String path : paths) {
            String category = TextureCatalog.category(path);
            String base = path.substring(path.lastIndexOf('/') + 1).replaceAll("\\.png$", "");
            String name;
            if (path.equals(PackProject.ICON)) {
                name = host.tr("texpack_pack_icon");
            } else if (names != null && (category.equals("block") || category.equals("item"))) {
                name = names.displayName(base, category.equals("item"), language);
            } else {
                name = readable(base);
            }
            out.add(new Entry(path, category, name, (name + " " + path).toLowerCase(Locale.ROOT)));
        }
        out.sort(java.util.Comparator.comparingInt((Entry e) -> order(e.category())).thenComparing(Entry::path));
        return out;
    }

    private static int order(String category) {
        int i = CATEGORIES.indexOf(category);
        return i < 0 ? CATEGORIES.size() : i;
    }

    private static String readable(String id) {
        String s = id.replace('_', ' ');
        return s.isEmpty() ? id : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Called on the FX thread after the list's contents changed. */
    private void showEntries(List<Entry> fresh, Set<String> pictures) {
        entries = fresh;
        changed = pictures;
        Set<String> present = new java.util.TreeSet<>(java.util.Comparator.comparingInt(TexturePackEditor::order)
                .thenComparing(s -> s));
        present.add(ALL);
        for (Entry e : fresh) present.add(e.category());
        String keep = categoryBox.getValue();
        updating = true;
        categoryBox.getItems().setAll(present);
        categoryBox.setValue(keep != null && present.contains(keep) ? keep : ALL);
        updating = false;
        thumbs.clear();
        applyFilter();
    }

    // ------------------------------------------------------------------ projects and versions

    private void start() {
        Path root = host.projectsDir();
        Path versions = host.versionsDir();
        String preferred = host.preferredVersion();
        String defaultName = host.tr("texpack_default_name");
        submit(() -> {
            List<String> names = PackProject.list(root);
            PackProject first = names.isEmpty() ? PackProject.create(root, defaultName)
                    : new PackProject(root.resolve(names.get(0)));
            List<String> all = PackProject.list(root);
            String description = first.description();
            List<TextureCatalog.Version> installed = TextureCatalog.installed(versions);
            Platform.runLater(() -> {
                project = first;
                showProject(first, all, description);
                updating = true;
                versionBox.getItems().setAll(installed);
                TextureCatalog.Version pick = installed.isEmpty() ? null : installed.get(0);
                for (TextureCatalog.Version v : installed) if (v.id().equals(preferred)) pick = v;
                versionBox.setValue(pick);
                updating = false;
                if (pick == null) {
                    list.setPlaceholder(wrapped(host.tr("texpack_no_version")));
                    return;
                }
                loadCatalog(pick);
            });
        });
    }

    private void showProject(PackProject p, List<String> all, String description) {
        updating = true;
        projectBox.getItems().setAll(all);
        projectBox.setValue(p.name());
        updating = false;
        nameField.setText(p.name());
        descriptionField.setText(description);
    }

    private void loadCatalog(TextureCatalog.Version version) {
        flush();
        submit(() -> {
            List<Path> mods = new ArrayList<>();
            if (modsDir != null && Files.isDirectory(modsDir)) {
                try (var files = Files.list(modsDir)) {
                    files.filter(f -> f.getFileName().toString().endsWith(".jar")).sorted().forEach(mods::add);
                }
            }
            TextureCatalog fresh;
            try {
                fresh = TextureCatalog.open(version, mods);
            } catch (IOException e) {
                Platform.runLater(() -> host.toast(host.tr("texpack_version_unusable").replace("{v}", version.id()), "warning"));
                return;
            }
            if (modelAssets != null) modelAssets.close();
            modelAssets = null;
            blockByTexture = null;
            openVersion = version;
            McAssets names = null;
            try {
                names = McAssets.open(version.jar(), version.id());
            } catch (IOException ignored) {
                // no blockstates in this jar: readable ids instead of the game's names
            }
            PackProject p = project;
            List<Entry> built;
            try {
                built = buildEntries(fresh, p, names);
            } finally {
                if (names != null) names.close();
            }
            Set<String> pictures = p == null ? new HashSet<>() : new HashSet<>(p.pictures());
            TextureCatalog old = catalog;
            catalog = fresh;
            if (old != null) old.close();
            Platform.runLater(() -> {
                showNothing();
                showEntries(built, pictures);
            });
        });
    }

    private void switchProject(PackProject next) {
        flush();
        commitDescription();
        project = next;
        Path root = host.projectsDir();
        submit(() -> {
            List<String> all = PackProject.list(root);
            String description = next.description();
            List<Entry> built = buildEntries(catalog, next, null);
            Set<String> pictures = new HashSet<>(next.pictures());
            Platform.runLater(() -> {
                showNothing();
                showProject(next, all, description);
                showEntries(renamed(built), pictures);
            });
        });
    }

    /** A project switch does not reopen the jar for names: the ones already known are kept. */
    private List<Entry> renamed(List<Entry> built) {
        Map<String, Entry> known = new java.util.HashMap<>();
        for (Entry e : entries) known.put(e.path(), e);
        List<Entry> out = new ArrayList<>(built.size());
        for (Entry e : built) out.add(known.getOrDefault(e.path(), e));
        return out;
    }

    private void newProject() {
        Path root = host.projectsDir();
        String name = host.tr("texpack_default_name");
        submit(() -> {
            PackProject created = PackProject.create(root, name);
            Platform.runLater(() -> switchProject(created));
        });
    }

    private void importPack() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(host.tr("texpack_import"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Zip", "*.zip"));
        File file = chooser.showOpenDialog(root.getScene().getWindow());
        if (file == null) return;
        Path projects = host.projectsDir();
        io.submit(() -> {
            try {
                PackProject imported = PackProject.importZip(projects, file.toPath());
                Platform.runLater(() -> {
                    switchProject(imported);
                    host.toast(host.tr("texpack_imported") + " «" + imported.name() + "»", "success");
                });
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> host.toast(host.tr("texpack_import_bad"), "error"));
            }
        });
    }

    private void commitName() {
        PackProject p = project;
        String wanted = nameField.getText();
        if (p == null || wanted == null || wanted.equals(p.name())) return;
        flush();
        Path root = host.projectsDir();
        submit(() -> {
            PackProject next = p.rename(wanted);
            List<String> all = PackProject.list(root);
            Platform.runLater(() -> {
                if (next == null) {
                    nameField.setText(p.name());
                    host.toast(host.tr("texpack_name_taken"), "warning");
                    return;
                }
                project = next;
                showProject(next, all, descriptionField.getText());
            });
        });
    }

    private void commitDescription() {
        PackProject p = project;
        TextureCatalog c = catalog;
        String text = descriptionField.getText();
        if (p == null || c == null || text == null || text.equals(p.description())) return;
        submit(() -> p.writeMeta(text, c.format()));
    }

    // ------------------------------------------------------------------ opening and saving a texture

    private void showNothing() {
        current = null;
        sheet = null;
        dirty = false;
        undo.clear();
        redo.clear();
        title.setText(host.tr("texpack_pick"));
        info.setText("");
        setManagedVisible(tintHint, false);
        setManagedVisible(frameRow, false);
        resetButton.setDisable(true);
        canvas.setWidth(0);
        canvas.setHeight(0);
        preview.getGraphicsContext2D().clearRect(0, 0, preview.getWidth(), preview.getHeight());
        list.getSelectionModel().clearSelection();
    }

    private static void setManagedVisible(Node n, boolean on) {
        n.setVisible(on);
        n.setManaged(on);
    }

    private void openTexture(Entry entry) {
        flush();
        int mine = ++token;
        PackProject p = project;
        TextureCatalog c = catalog;
        submit(() -> {
            byte[] bytes = p != null && p.has(entry.path()) ? p.read(entry.path()) : c == null ? null : c.read(entry.path());
            byte[] meta = p != null && p.readMeta(entry.path()) != null ? p.readMeta(entry.path())
                    : c == null ? null : c.readMeta(entry.path());
            PixelOps.Sheet read = bytes != null ? decode(bytes)
                    : entry.path().equals(PackProject.ICON) ? PixelOps.Sheet.blank(64, 64) : null;
            Platform.runLater(() -> {
                if (mine != token) return;
                if (read == null) {
                    host.toast(host.tr("texpack_open_failed"), "error");
                    return;
                }
                current = entry;
                currentMeta = meta;
                undo.clear();
                redo.clear();
                dirty = false;
                frame = 0;
                autoFit = true;
                setSheet(read);
                root.requestFocus();
            });
        });
    }

    private void setSheet(PixelOps.Sheet next) {
        sheet = next;
        touched = new boolean[next.px().length];
        String meta = currentMeta == null ? null : new String(currentMeta, StandardCharsets.UTF_8);
        frameRows = PixelOps.frameRows(next.w(), next.h(), meta);
        int frames = next.h() / frameRows;
        if (frame >= frames) frame = frames - 1;
        setManagedVisible(frameRow, frames > 1);

        String base = current.path().substring(current.path().lastIndexOf('/') + 1).replaceAll("\\.png$", "");
        title.setText(current.name());
        info.setText(shortPath(current.path()) + " · " + next.w() + " × " + next.h());
        setManagedVisible(tintHint, current.category().equals("block") && TINTED.matcher(base).matches());
        TextureCatalog c = catalog;
        resetButton.setDisable(!current.path().equals(PackProject.ICON) && (c == null || !c.has(current.path())));
        updateFrameLabel();
        if (autoFit) fitZoom();
        if (view3d != null && view3d.isSelected()) loadModel();
        redraw();
        refreshPreview(true);
    }

    private int top() {
        return frame * frameRows;
    }

    private void updateFrameLabel() {
        if (sheet == null) return;
        frameLabel.setText(host.tr("texpack_frame").replace("{n}", Integer.toString(frame + 1))
                .replace("{m}", Integer.toString(sheet.h() / frameRows)));
    }

    private void changeFrame(int delta) {
        if (sheet == null) return;
        int frames = sheet.h() / frameRows;
        frame = (frame + delta + frames) % frames;
        updateFrameLabel();
        redraw();
        refreshPreview(true);
    }

    /** Writes the texture if it changed. Runs in order with everything else on the file thread. */
    private void flush() {
        saveSoon.stop();
        if (!dirty || sheet == null || current == null || project == null) return;
        dirty = false;
        PixelOps.Sheet copy = sheet.copy();
        String path = current.path();
        PackProject p = project;
        byte[] meta = currentMeta;
        submit(() -> {
            p.write(path, encode(copy), meta);
            Platform.runLater(() -> markChanged(path, true));
        });
    }

    private void markChanged(String path, boolean on) {
        if (on) changed.add(path);
        else changed.remove(path);
        thumbs.remove(path);
        list.refresh();
    }

    /** Back to the game's texture. Ctrl+Z still brings the paint back while the texture stays open. */
    private void resetCurrent() {
        if (current == null || sheet == null) return;
        Entry entry = current;
        PackProject p = project;
        TextureCatalog c = catalog;
        int w = sheet.w(), h = sheet.h();
        saveSoon.stop();
        dirty = false;
        submit(() -> {
            if (p != null) p.remove(entry.path());
            byte[] bytes = c == null ? null : c.read(entry.path());
            byte[] meta = c == null ? null : c.readMeta(entry.path());
            PixelOps.Sheet read = bytes != null ? decode(bytes) : PixelOps.Sheet.blank(w, h);
            Platform.runLater(() -> {
                if (current != entry) return;
                pushUndo();
                currentMeta = meta;
                setSheet(read);
                markChanged(entry.path(), false);
            });
        });
    }

    private static PixelOps.Sheet decode(byte[] bytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null) throw new IOException("not an image");
        int w = image.getWidth(), h = image.getHeight();
        return new PixelOps.Sheet(w, h, image.getRGB(0, 0, w, h, null, 0, w));
    }

    static byte[] encode(PixelOps.Sheet s) throws IOException {
        BufferedImage image = new BufferedImage(s.w(), s.h(), BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, s.w(), s.h(), s.px(), 0, s.w());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** File work that reports a failure to the user instead of losing it in a future. */
    private interface Work {
        void run() throws IOException;
    }

    private void submit(Work work) {
        try {
            io.submit(() -> {
                try {
                    work.run();
                } catch (IOException | RuntimeException e) {
                    host.stat("texpack fail" + com.example.launcher.service.ErrorLog.record("texpack", e));
                    Platform.runLater(() -> host.toast(host.tr("texpack_io_failed") + " " + e.getMessage(), "error"));
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // the editor has been closed
        }
    }

    // ------------------------------------------------------------------ pack

    private void exportPack() {
        PackProject p = project;
        if (p == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle(host.tr("texpack_export"));
        chooser.setInitialFileName(p.name() + ".zip");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Zip", "*.zip"));
        File file = chooser.showSaveDialog(root.getScene().getWindow());
        if (file == null) return;
        pack(List.of(file.toPath()), host.tr("texpack_exported") + " " + file.getName());
    }

    /**
     * Into the build's folder and the game's own. The launcher copies a build's packs to the game
     * only when the file is not there yet, so a repainted pack has to replace that copy as well.
     */
    private void installPack() {
        PackProject p = project;
        if (p == null) return;
        List<Path> targets = new ArrayList<>();
        for (Path dir : host.resourcePackDirs()) targets.add(dir.resolve(p.name() + ".zip"));
        pack(targets, host.tr("texpack_installed").replace("{name}", p.name()));
    }

    private void pack(List<Path> targets, String done) {
        flush();
        commitDescription();
        PackProject p = project;
        TextureCatalog c = catalog;
        String description = descriptionField.getText();
        if (p == null || c == null) {
            host.toast(host.tr("texpack_no_version"), "warning");
            return;
        }
        io.submit(() -> {
            try {
                if (p.pictures().isEmpty()) {
                    Platform.runLater(() -> host.toast(host.tr("texpack_empty"), "warning"));
                    return;
                }
                p.writeMeta(description, c.format());
                for (Path target : targets) p.exportZip(target);
                host.stat("texpack pack ok n=" + p.pictures().size() + " targets=" + targets.size());
                Platform.runLater(() -> host.toast(done, "success"));
            } catch (IOException | RuntimeException e) {
                // on Windows a running game keeps its pack open, and that is by far the likeliest cause
                host.stat("texpack pack fail" + com.example.launcher.service.ErrorLog.record("texpack_pack", e));
                Platform.runLater(() -> host.toast(host.tr("texpack_pack_failed"), "error"));
            }
        });
    }

    // ------------------------------------------------------------------ painting

    private void pushUndo() {
        undo.push(sheet.copy());
        long total = 0;
        int kept = 0;
        for (PixelOps.Sheet s : undo) {
            total += s.px().length;
            kept++;
            if (total > UNDO_PIXELS && kept > 1) break;
        }
        while (undo.size() > Math.min(kept, UNDO_LIMIT)) undo.removeLast();
        redo.clear();
    }

    private void undo() {
        if (undo.isEmpty() || sheet == null) return;
        redo.push(sheet);
        setSheet(undo.pop());
        touchedByHistory();
    }

    private void redo() {
        if (redo.isEmpty() || sheet == null) return;
        undo.push(sheet);
        setSheet(redo.pop());
        touchedByHistory();
    }

    private void touchedByHistory() {
        dirty = true;
        saveSoon.playFromStart();
    }

    private void beginStroke() {
        stroking = true;
        strokeChanged = false;
        pushUndo();
        java.util.Arrays.fill(touched, false);
        if (tool == Tool.PENCIL || tool == Tool.FILL) colorPanel.remember(color);
        lastX = -1;
        lastY = -1;
    }

    private void endStroke() {
        if (!stroking) return;
        stroking = false;
        lastX = -1;
        lastY = -1;
        // a click that changed nothing (picker, painting a colour over itself) is no step to undo
        if (!strokeChanged && !undo.isEmpty()) undo.pop();
        refreshPreview(true);
        if (strokeChanged) {
            dirty = true;
            saveSoon.playFromStart();
        }
    }

    private void apply(int x, int y) {
        if (x < 0 || x >= sheet.w() || y < top() || y >= top() + frameRows) return;
        int rows = frameRows;
        int t = top();
        switch (tool) {
            case PENCIL -> PixelOps.brush(sheet, x, y, brush, t, rows, (bx, by) -> paint(bx, by, brushArgb()));
            case ERASER -> PixelOps.brush(sheet, x, y, brush, t, rows, (bx, by) -> paint(bx, by, 0));
            case BLUR -> {
                PixelOps.Sheet before = sheet.copy();
                PixelOps.brush(sheet, x, y, brush, t, rows, (bx, by) -> paint(bx, by, PixelOps.blurred(before, bx, by, t, rows)));
            }
            case LIGHTEN -> PixelOps.brush(sheet, x, y, brush, t, rows, (bx, by) -> shadeOnce(bx, by, 0.09));
            case DARKEN -> PixelOps.brush(sheet, x, y, brush, t, rows, (bx, by) -> shadeOnce(bx, by, -0.09));
            case NOISE -> PixelOps.brush(sheet, x, y, brush, t, rows, (bx, by) -> shadeOnce(bx, by, (random.nextDouble() * 2 - 1) * 0.07));
            case FILL -> {
                if (PixelOps.fill(sheet, x, y, brushArgb(), t, rows) > 0) {
                    strokeChanged = true;
                    redraw();
                }
            }
            case REPLACE -> {
                int n = PixelOps.replace(sheet, sheet.at(x, y), brushArgb());
                if (n > 0) {
                    strokeChanged = true;
                    redraw();
                    host.toast(host.tr("skin_replaced") + " " + n, "info");
                }
            }
            case PICKER -> {
                pick(x, y);
                selectTool(Tool.PENCIL);
            }
        }
        lastX = x;
        lastY = y;
    }

    /** A stroke shades each texel once, however long the mouse lingers on it. */
    private void shadeOnce(int x, int y, double amount) {
        int i = y * sheet.w() + x;
        if (touched[i]) return;
        touched[i] = true;
        paint(x, y, PixelOps.shaded(sheet.px()[i], amount));
    }

    private void paint(int x, int y, int argb) {
        int i = y * sheet.w() + x;
        if (sheet.px()[i] == argb) return;
        sheet.px()[i] = argb;
        strokeChanged = true;
        drawCell(canvas.getGraphicsContext2D(), x, y);
    }

    private void pick(int x, int y) {
        int argb = sheet.at(x, y);
        opacitySlider.setValue((argb >>> 24) / 255.0 * 100);
        if ((argb >>> 24) == 0) return;
        color = Color.rgb((argb >> 16) & 255, (argb >> 8) & 255, argb & 255);
        colorPanel.set(color, false);
    }

    /** Цвет кисти с её непрозрачностью. Совсем прозрачный — ровно 0, как у ластика. */
    private int brushArgb() {
        if (opacity <= 0) return 0;
        return (toArgb(color) & 0xFFFFFF) | ((int) Math.round(opacity * 255) << 24);
    }

    private static int toArgb(Color c) {
        return ((int) Math.round(c.getOpacity() * 255) << 24) | ((int) Math.round(c.getRed() * 255) << 16)
                | ((int) Math.round(c.getGreen() * 255) << 8) | (int) Math.round(c.getBlue() * 255);
    }

    // ------------------------------------------------------------------ drawing

    private void zoom(int delta) {
        if (sheet == null) return;
        autoFit = false;
        int next = delta > 0 ? cell + Math.max(1, cell / 4) : cell - Math.max(1, cell / 4);
        cell = clampCell(next);
        redraw();
    }

    private int clampCell(int wanted) {
        int side = Math.max(sheet.w(), frameRows);
        return Math.max(1, Math.min(Math.min(MAX_CELL, MAX_CANVAS / side), wanted));
    }

    /** As many whole screen pixels per texel as the panel has room for. */
    private void fitZoom() {
        if (sheet == null) return;
        double vw = canvasScroll.getViewportBounds().getWidth();
        double vh = canvasScroll.getViewportBounds().getHeight();
        if (vw <= 0 || vh <= 0) return;
        int next = clampCell((int) Math.floor(Math.min(vw / sheet.w(), vh / frameRows)));
        if (next == cell && canvas.getWidth() == sheet.w() * cell) return;
        cell = next;
        redraw();
    }

    private void show3d(boolean on) {
        setManagedVisible(modelView.node(), on);
        setManagedVisible(canvasScroll, !on);
        if (on) loadModel();
    }

    private void update3d() {
        if (view3d != null && view3d.isSelected() && sheet != null) modelView.update(sheet, top(), frameRows);
    }

    /** Ссылка из моделей игры на текстуру: «minecraft:block/stone». Текстура из мода — null. */
    private static String refOf(String path) {
        String prefix = "assets/minecraft/textures/";
        return path.startsWith(prefix) && path.endsWith(".png")
                ? "minecraft:" + path.substring(prefix.length(), path.length() - 4) : null;
    }

    /** Находит модель для открытой текстуры и показывает её; модели читаются не в потоке интерфейса. */
    private void loadModel() {
        if (sheet == null || current == null) return;
        Entry entry = current;
        int w = sheet.w(), rows = frameRows;
        String ref = refOf(entry.path());
        TextureCatalog.Version version = openVersion;
        int mine = token;
        submit(() -> {
            List<McAssets.Quad> quads = List.of();
            if (ref != null && version != null) {
                if (modelAssets == null) {
                    try {
                        modelAssets = McAssets.open(version.jar(), version.id());
                    } catch (IOException e) {
                        modelAssets = McAssets.empty();
                    }
                }
                quads = modelFor(ref, entry.category());
            }
            List<McAssets.Quad> found = quads;
            McAssets assets = modelAssets;
            if (!model3dReported) {
                model3dReported = true;
                host.stat("texpack 3d " + entry.category() + " quads=" + found.size());
            }
            Platform.runLater(() -> {
                if (mine != token || sheet == null) return;
                modelView.show(found, ref == null ? "" : ref, r -> assets == null ? null : assets.texture(r), w, rows);
                update3d();
            });
        });
    }

    /** Модель, где эта текстура есть. Для блока — первый подходящий блок, индекс строится один раз. */
    private List<McAssets.Quad> modelFor(String ref, String category) {
        String base = ref.substring(ref.lastIndexOf('/') + 1);
        if (category.equals("item")) {
            List<McAssets.Quad> quads = modelAssets.itemQuads(base);
            return usesTexture(quads, ref) ? quads : List.of();
        }
        if (!category.equals("block")) return List.of();
        if (blockByTexture == null) {
            Map<String, String> index = new HashMap<>();
            for (String id : modelAssets.blockIds()) {
                try {
                    for (McAssets.Quad q : modelAssets.blockState(id).quads()) {
                        String t = q.texture().startsWith("minecraft:") ? q.texture() : "minecraft:" + q.texture();
                        // Блок с тем же именем лучше первого попавшегося: у stone — сам камень, а не ступени.
                        String known = index.get(t);
                        String name = t.substring(t.lastIndexOf('/') + 1);
                        if (known == null || name.equals(id) || (name.startsWith(id) && !name.startsWith(known))) index.put(t, id);
                    }
                } catch (RuntimeException ignored) {
                    // сломанная модель одного блока не мешает остальным
                }
            }
            blockByTexture = index;
        }
        String id = blockByTexture.get(ref);
        return id == null ? List.of() : modelAssets.blockState(id).quads();
    }

    private static boolean usesTexture(List<McAssets.Quad> quads, String ref) {
        String want = ref.substring("minecraft:".length());
        for (McAssets.Quad q : quads) {
            String t = q.texture().startsWith("minecraft:") ? q.texture().substring(10) : q.texture();
            if (t.equals(want)) return true;
        }
        return false;
    }

    private void redraw() {
        if (sheet == null) return;
        update3d();
        int w = sheet.w(), rows = frameRows, t = top();
        canvas.setWidth(w * cell);
        canvas.setHeight(rows * cell);
        WritableImage image = new WritableImage(w, rows);
        PixelWriter writer = image.getPixelWriter();
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < w; x++) {
                writer.setArgb(x, y, PixelOps.over(check(x, y), sheet.at(x, y + t)));
            }
        }
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setImageSmoothing(false);
        g.drawImage(image, 0, 0, w * cell, rows * cell);
        if (grid && cell >= 8) {
            g.setStroke(Color.rgb(0, 0, 0, 0.18));
            g.setLineWidth(1);
            for (int x = 0; x <= w; x++) g.strokeLine(x * cell + 0.5, 0, x * cell + 0.5, rows * cell);
            for (int y = 0; y <= rows; y++) g.strokeLine(0, y * cell + 0.5, w * cell, y * cell + 0.5);
        }
    }

    private void drawCell(GraphicsContext g, int x, int sheetY) {
        int y = sheetY - top();
        if (y < 0 || y >= frameRows) return;
        int c = PixelOps.over(check(x, y), sheet.at(x, sheetY));
        g.setFill(Color.rgb((c >> 16) & 255, (c >> 8) & 255, c & 255));
        g.fillRect(x * cell, y * cell, cell, cell);
        if (grid && cell >= 8) {
            g.setStroke(Color.rgb(0, 0, 0, 0.18));
            g.setLineWidth(1);
            g.strokeRect(x * cell + 0.5, y * cell + 0.5, cell, cell);
        }
    }

    private static int check(int x, int y) {
        return ((x + y) & 1) == 0 ? CHECK_LIGHT : CHECK_DARK;
    }

    /**
     * The frame at game size: a block repeated, so a seam between tiles shows while it is painted;
     * anything else once, as large as it fits. Throttled during a stroke.
     */
    private void refreshPreview(boolean force) {
        if (sheet == null) return;
        long now = System.nanoTime();
        if (!force && now - lastPreview < 100_000_000L) return;
        lastPreview = now;
        int w = sheet.w(), rows = frameRows, t = top();
        WritableImage image = new WritableImage(w, rows);
        PixelWriter writer = image.getPixelWriter();
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < w; x++) writer.setArgb(x, y, sheet.at(x, y + t));
        }
        GraphicsContext g = preview.getGraphicsContext2D();
        double pw = preview.getWidth(), ph = preview.getHeight();
        g.clearRect(0, 0, pw, ph);
        g.setImageSmoothing(false);
        boolean tile = current != null && current.category().equals("block") && w == rows;
        if (tile) {
            double side = Math.floor(ph / 3);
            double left = Math.floor((pw - side * 3) / 2);
            for (int j = 0; j < 3; j++) {
                for (int i = 0; i < 3; i++) g.drawImage(image, left + i * side, j * side, side, side);
            }
        } else {
            double k = Math.min(pw / w, ph / rows);
            double dw = w * k, dh = rows * k;
            g.drawImage(image, (pw - dw) / 2, (ph - dh) / 2, dw, dh);
        }
    }
}
