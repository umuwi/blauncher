package com.example.launcher.ui.studio;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.PerspectiveCamera;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Affine;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;
import org.kordamp.ikonli.javafx.FontIcon;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.DoubleConsumer;

/**
 * "Студия предметов": a small Blender for display entities. Blocks and items are placed, turned
 * and scaled in 3D, and the scene comes out as one command for a command block
 * (see {@link StudioCommand}).
 *
 * <p>The scene is drawn in Minecraft's own coordinates - a group turned 180 degrees about X maps
 * y-up/z-south onto JavaFX's y-down - so a position, a rotation or a scale typed here means the
 * same thing in the game. The cell [0,1]^3 is the block right above the command block; its
 * outline and the command block's are drawn for reference.
 */
public final class DisplayStudio implements AutoCloseable {

    public interface Host {
        String tr(String key);
        void toast(String message, String type);
        void copy(String text);
        /** launcher_root/versions, where a client jar with textures may be found. */
        Path versionsDir();
        /** Where the scene is kept between visits. */
        Path studioDir();
        ExecutorService executor();
        /** "Инвертировать мышь" in Settings. */
        default boolean invertMouse() { return false; }
        /** "ru" or "en": block and item names follow the launcher's language. */
        default String language() { return "ru"; }
    }

    private static final double UNIT = 16;          // JavaFX units per block
    /** Grid steps: a quarter block normally, one texel with Shift (finer divisions), free with Alt. */
    private static final double SNAP_COARSE = 0.25;
    private static final double SNAP_FINE = 1.0 / 16;
    private static final double SNAP = SNAP_FINE;   // keyboard nudge and field steps
    private double snapStep = SNAP_COARSE;
    private static final int UNDO_LIMIT = 60;
    private static final int MAX_RESULTS = 5000;   // every block and item; the list only builds what it shows

    private final Host host;
    private McAssets assets = McAssets.empty();
    private final List<StudioCommand.Item> items = new ArrayList<>();
    private final Map<StudioCommand.Item, Group> views = new HashMap<>();
    private final Deque<List<StudioCommand.Item>> undo = new ArrayDeque<>();
    private StudioCommand.Item selected;
    private String sceneTag = newTag();

    // 3D
    private final Group objects = new Group();
    private final Group mcRoot = new Group();
    private final PerspectiveCamera camera = new PerspectiveCamera(true);
    private final Rotate camYaw = new Rotate(-35, Rotate.Y_AXIS);
    private final Rotate camPitch = new Rotate(-28, Rotate.X_AXIS);
    private final Translate camDistance = new Translate(0, 0, -7 * UNIT);
    private final Translate camTarget = new Translate(0.5 * UNIT, -0.5 * UNIT, -0.5 * UNIT);
    private final Map<String, PhongMaterial> materials = new HashMap<>();
    private SubScene subScene;

    // panels
    private final BorderPane root = new BorderPane();
    private final TextField search = new TextField();
    private final FlowPane results = new FlowPane(6, 6);
    private final ListView<StudioCommand.Item> sceneList = new ListView<>();
    private final VBox properties = new VBox(10);
    private final TextArea commandArea = new TextArea();
    private final Label commandInfo = new Label();
    private final Label texturesNote = new Label();
    private final ComboBox<StudioCommand.Target> targetBox = new ComboBox<>();
    private final TextField originField = new TextField("~-0.5 ~0.5 ~-0.5");
    private boolean pickItems;

    private double dragX, dragY;
    private boolean draggingObject;

    /** What a drag on the selected object does - Blender's G, R and S. */
    private enum Mode { MOVE, ROTATE, SCALE }

    private Mode mode = Mode.MOVE;
    /** 'A' for free, or the one axis the transform is locked to (X, Y, Z - as in Blender). */
    private char axis = 'A';
    private final ToggleGroup modeGroup = new ToggleGroup();
    private final ToggleGroup axisGroup = new ToggleGroup();
    private final Group axisGuide = new Group();
    /** What a right click in the scene places, Minecraft-style: the tile picked last. */
    private String handId;
    private boolean handIsItem;
    private final StudioGizmo gizmo = new StudioGizmo();
    private final Group subGrid = new Group();
    /** The handle being dragged: "move:X", "rotate:free"..., or null for a drag on the object itself. */
    private String gizmoDrag;
    private double lastAngle;
    private double dragAccX, dragAccY, dragAccZ;
    private final javafx.animation.PauseTransition saveLater = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1));

    public DisplayStudio(Host host) {
        this.host = host;
        // every panel is resizable: drag the gap between two of them, like a window's edge
        javafx.scene.control.SplitPane columns = new javafx.scene.control.SplitPane(buildLibrary(), buildViewport(), buildProperties());
        columns.setDividerPositions(0.22, 0.76);
        columns.getStyleClass().add("panel-split");
        javafx.scene.control.SplitPane rows = new javafx.scene.control.SplitPane(columns, buildCommandPanel());
        rows.setOrientation(javafx.geometry.Orientation.VERTICAL);
        rows.setDividerPositions(0.76);
        rows.getStyleClass().add("panel-split");
        root.setCenter(rows);
        root.setFocusTraversable(true);
        root.setOnKeyPressed(e -> {
            if (e.getTarget() instanceof TextField || e.getTarget() instanceof TextArea) return;
            double step = e.isShiftDown() ? 1 : SNAP;
            if (e.isControlDown() && (e.getCode() == KeyCode.Y || (e.isShiftDown() && e.getCode() == KeyCode.Z))) redo();
            else if (e.isControlDown() && e.getCode() == KeyCode.Z) undo();
            else if (e.isControlDown() && e.getCode() == KeyCode.A) selectAll();
            else if (e.getCode() == KeyCode.ESCAPE) select(null);
            else if (e.isControlDown() && e.getCode() == KeyCode.D) duplicate();
            // checked before the bare letters: X alone locks the axis
            else if (e.isControlDown() && e.getCode() == KeyCode.C) copySelection();
            else if (e.isControlDown() && e.getCode() == KeyCode.X) cutSelection();
            else if (e.isControlDown() && e.getCode() == KeyCode.V) paste();
            else if (e.getCode() == KeyCode.DELETE || e.getCode() == KeyCode.BACK_SPACE) delete();
            else if (selected != null && e.getCode() == KeyCode.LEFT) nudge(-step, 0, 0);
            else if (selected != null && e.getCode() == KeyCode.RIGHT) nudge(step, 0, 0);
            else if (selected != null && e.getCode() == KeyCode.UP) nudge(0, 0, -step);
            else if (selected != null && e.getCode() == KeyCode.DOWN) nudge(0, 0, step);
            else if (selected != null && e.getCode() == KeyCode.PAGE_UP) nudge(0, step, 0);
            else if (selected != null && e.getCode() == KeyCode.PAGE_DOWN) nudge(0, -step, 0);
            else if (e.getCode() == KeyCode.G) setMode(Mode.MOVE);
            else if (e.getCode() == KeyCode.R) setMode(Mode.ROTATE);
            else if (e.getCode() == KeyCode.S && !e.isControlDown()) setMode(Mode.SCALE);
            else if (e.getCode() == KeyCode.X) setAxis(axis == 'X' ? 'A' : 'X');
            else if (e.getCode() == KeyCode.Y) setAxis(axis == 'Y' ? 'A' : 'Y');
            else if (e.getCode() == KeyCode.Z && !e.isControlDown()) setAxis(axis == 'Z' ? 'A' : 'Z');
            else return;
            e.consume();
        });
        saveLater.setOnFinished(e -> saveScene());
        gizmo.build(StudioGizmo.Kind.MOVE);
        gizmo.root.setVisible(false);

        loadScene();
        rebuildViews();
        renderSceneList();
        renderProperties();
        updateCommand();

        texturesNote.setText(host.tr("studio_loading_textures"));
        host.executor().submit(() -> {
            McAssets loaded = McAssets.openNewest(host.versionsDir());
            Platform.runLater(() -> {
                assets.close();
                assets = loaded;
                materials.clear();
                iconCache.clear();
                texturesNote.setText(loaded.hasTextures()
                        ? host.tr("studio_textures_from") + " " + loaded.versionLabel()
                        : host.tr("studio_no_textures"));
                rebuildViews();
                renderResults();
                sceneList.refresh();   // the scene's names and pictures come with the textures
            });
        });
        renderResults();
    }

    public Node node() {
        return root;
    }

    @Override
    public void close() {
        saveLater.stop();
        saveScene();
        assets.close();
    }

    // ------------------------------------------------------------------ library (left)

    private Node buildLibrary() {
        ToggleGroup kind = new ToggleGroup();
        ToggleButton blocks = new ToggleButton(host.tr("studio_blocks"));
        ToggleButton itemsBtn = new ToggleButton(host.tr("studio_items"));
        for (ToggleButton b : List.of(blocks, itemsBtn)) {
            b.setToggleGroup(kind);
            b.getStyleClass().add("share-tab");
            b.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(b, Priority.ALWAYS);
        }
        blocks.setSelected(true);
        kind.selectedToggleProperty().addListener((obs, o, n) -> {
            if (n == null) {
                ((ToggleButton) o).setSelected(true);
                return;
            }
            pickItems = n == itemsBtn;
            renderResults();
        });

        search.setPromptText(host.tr("studio_search"));
        search.getStyleClass().add("text-input");
        search.textProperty().addListener((obs, o, n) -> renderResults());

        ScrollPane resultScroll = new ScrollPane(results);
        resultScroll.setFitToWidth(true);
        resultScroll.getStyleClass().add("screen-scroll");
        resultScroll.setPrefHeight(230);
        resultScroll.setMinHeight(120);
        VBox.setVgrow(resultScroll, Priority.ALWAYS);

        sceneList.getStyleClass().add("studio-list");
        sceneList.setPrefHeight(170);
        sceneList.setMinHeight(90);
        sceneList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(StudioCommand.Item item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                boolean isItem = item.kind == StudioCommand.Kind.ITEM;
                setText(assets.displayName(item.id, isItem, host.language()));
                Image img = assets.hasTextures()
                        ? iconCache.computeIfAbsent((isItem ? "i:" : "b:") + item.id, k -> renderIcon(item.id, isItem)) : null;
                if (img == null) {
                    setGraphic(null);
                    setText((isItem ? "◆ " : "■ ") + getText());
                    return;
                }
                ImageView view = new ImageView(img);
                view.setFitWidth(22);
                view.setFitHeight(22);
                view.setSmooth(img.getWidth() > 32);
                setGraphic(view);
            }
        });
        sceneList.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (n != null && n != selected) {
                select(n);
                focusOn(n);   // the camera goes to what was picked in the list
            }
        });

        Label sceneLabel = new Label(host.tr("studio_scene"));
        sceneLabel.getStyleClass().add("toggle-label");
        Button clear = new Button(host.tr("studio_clear"));
        clear.getStyleClass().add("copy-btn");
        clear.setOnAction(e -> clearScene());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox sceneHeader = new HBox(8, sceneLabel, gap, clear);
        sceneHeader.setAlignment(Pos.CENTER_LEFT);

        Label addLabel = new Label(host.tr("studio_add"));
        addLabel.getStyleClass().add("toggle-label");
        texturesNote.getStyleClass().add("toggle-sub");
        texturesNote.setWrapText(true);
        texturesNote.setMinHeight(Region.USE_PREF_SIZE);

        VBox box = new VBox(8, addLabel, new HBox(4, blocks, itemsBtn), search, resultScroll, texturesNote,
                sceneHeader, buildSavedScenesRow(), sceneList);
        box.getStyleClass().add("panel");
        box.setPadding(new Insets(14));
        box.setPrefWidth(250);
        box.setMinWidth(220);
        return box;
    }

    /** Saved scenes by name: type a name and save, or pick one and open it. */
    private final ComboBox<String> savedScenes = new ComboBox<>();

    private Node buildSavedScenesRow() {
        savedScenes.setEditable(true);
        savedScenes.setPromptText(host.tr("studio_scene_name"));
        savedScenes.getStyleClass().add("combo-box");
        savedScenes.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(savedScenes, Priority.ALWAYS);
        refreshSavedScenes();
        Button save = sceneButton("fth-save", "studio_scene_save", this::saveNamedScene);
        Button open = sceneButton("fth-folder", "studio_scene_open", this::openNamedScene);
        Button remove = sceneButton("fth-trash-2", "studio_scene_delete", this::deleteNamedScene);
        HBox row = new HBox(4, savedScenes, save, open, remove);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Button sceneButton(String icon, String key, Runnable action) {
        Button b = new Button();
        b.setGraphic(new FontIcon(icon));
        b.getStyleClass().add("secondary-button");
        b.setStyle("-fx-padding: 7 9;");
        b.setTooltip(new Tooltip(host.tr(key)));
        b.setOnAction(e -> action.run());
        b.setMinWidth(Region.USE_PREF_SIZE);
        return b;
    }

    private Path scenesDir() {
        return host.studioDir().resolve("scenes");
    }

    /** A name as a file name: letters of any alphabet, digits, spaces, - and _ only. */
    private static String fileNameOf(String name) {
        String clean = name.trim().replaceAll("[^\\p{L}\\p{N} _-]", "_");
        return clean.length() > 60 ? clean.substring(0, 60) : clean;
    }

    private void refreshSavedScenes() {
        List<String> names = new ArrayList<>();
        try (var files = Files.list(scenesDir())) {
            files.map(f -> f.getFileName().toString()).filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - 5)).sorted(String.CASE_INSENSITIVE_ORDER).forEach(names::add);
        } catch (Exception ignored) {
            // no saved scenes yet
        }
        String typed = savedScenes.getEditor().getText();
        savedScenes.getItems().setAll(names);
        savedScenes.getEditor().setText(typed);
    }

    private String typedSceneName() {
        String name = savedScenes.getEditor().getText();
        if (name == null || name.isBlank()) name = savedScenes.getValue();
        return name == null ? "" : fileNameOf(name);
    }

    private void saveNamedScene() {
        String name = typedSceneName();
        if (name.isBlank()) {
            host.toast(host.tr("studio_scene_need_name"), "info");
            savedScenes.requestFocus();
            return;
        }
        try {
            Files.createDirectories(scenesDir());
            Files.writeString(scenesDir().resolve(name + ".json"), GSON.toJson(currentScene()));
            refreshSavedScenes();
            savedScenes.getEditor().setText(name);
            host.toast(host.tr("studio_scene_saved") + " " + name, "success");
        } catch (Exception e) {
            host.toast(host.tr("studio_scene_failed"), "error");
        }
    }

    private void openNamedScene() {
        String name = typedSceneName();
        Path file = scenesDir().resolve(name + ".json");
        if (name.isBlank() || !Files.isRegularFile(file)) {
            host.toast(host.tr("studio_scene_not_found"), "info");
            return;
        }
        pushUndo();   // Ctrl+Z brings back what was on screen
        items.clear();
        selected = null;
        if (!readScene(file)) {
            host.toast(host.tr("studio_load_failed"), "warning");
        }
        rebuildViews();
        commit(true);
        renderProperties();
        resetView();
    }

    private void deleteNamedScene() {
        String name = typedSceneName();
        try {
            if (!name.isBlank() && Files.deleteIfExists(scenesDir().resolve(name + ".json"))) {
                host.toast(host.tr("studio_scene_deleted") + " " + name, "info");
            }
        } catch (Exception ignored) {
            // nothing to delete
        }
        savedScenes.getEditor().setText("");
        refreshSavedScenes();
    }

    private int resultsGeneration;
    /** Blocks the game never draws (air, the light block...): nothing to place or to see. */
    private static final java.util.Set<String> UNSHOWABLE = java.util.Set.of(
            "air", "cave_air", "void_air", "bubble_column", "moving_piston", "light", "structure_void");

    private void renderResults() {
        String raw = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        String q = raw.replace(' ', '_');
        List<String> source = pickItems ? assets.itemIds() : assets.blockIds();
        List<String> shown = new ArrayList<>();
        String lang = host.language();
        for (String id : source) {
            if (UNSHOWABLE.contains(id)) continue;
            // by id, or by the name in either language
            if (q.isEmpty() || id.contains(q)
                    || assets.displayName(id, pickItems, lang).toLowerCase(Locale.ROOT).contains(raw)
                    || assets.displayName(id, pickItems, "ru".equals(lang) ? "en" : "ru").toLowerCase(Locale.ROOT).contains(raw)) {
                shown.add(id);
            }
            if (shown.size() >= MAX_RESULTS) break;
        }
        results.getChildren().clear();
        iconQueue.clear();
        int generation = ++resultsGeneration;
        boolean item = pickItems;
        McAssets a = assets;
        for (String id : shown) {
            StackPane tile = new StackPane();
            tile.getStyleClass().add("studio-tile");
            tile.setMinSize(46, 46);
            tile.setPrefSize(46, 46);
            ImageView icon = new ImageView();
            icon.setFitWidth(40);
            icon.setFitHeight(40);
            icon.setSmooth(false);
            Label letter = new Label(id.substring(0, 1).toUpperCase(Locale.ROOT));
            letter.getStyleClass().add("toggle-sub");
            tile.getChildren().addAll(letter, icon);
            // the name shows at once next to the pointer, not after the usual second
            Tooltip name = new Tooltip(a.displayName(id, item, host.language()) + "\n" + id);
            name.setShowDelay(javafx.util.Duration.millis(60));
            name.setHideDelay(javafx.util.Duration.ZERO);
            name.setShowDuration(javafx.util.Duration.INDEFINITE);
            Tooltip.install(tile, name);
            if (id.equals(handId) && item == handIsItem) tile.getStyleClass().add("in-hand");
            tile.setOnMouseClicked(e -> {
                handId = id;
                handIsItem = item;
                for (Node t : results.getChildren()) t.getStyleClass().remove("in-hand");
                tile.getStyleClass().add("in-hand");
                add(id, item);
            });
            results.getChildren().add(tile);
            if (a.hasTextures()) {
                String key = (item ? "i:" : "b:") + id;
                Image cached = iconCache.get(key);
                if (cached != null) {
                    icon.setSmooth(cached.getWidth() > 32);   // 3D icons shrink smoothly, 16 px sprites stay crisp
                    icon.setImage(cached);
                    letter.setVisible(false);
                } else {
                    iconQueue.add(() -> {
                        if (generation != resultsGeneration || a != assets) return;
                        Image img = iconCache.computeIfAbsent(key, k -> renderIcon(id, item));
                        if (img == null) return;
                        icon.setSmooth(img.getWidth() > 32);
                        icon.setImage(img);
                        letter.setVisible(false);
                    });
                }
            }
        }
        if (!iconQueue.isEmpty()) iconPump.start();
        if (shown.isEmpty()) {
            Label none = new Label(host.tr("studio_nothing_found"));
            none.getStyleClass().add("toggle-sub");
            results.getChildren().add(none);
        }
    }

    // ------------------------------------------------------------------ 3D icons

    private static final int ICON = 64;
    private final Map<String, Image> iconCache = new HashMap<>();
    private final Deque<Runnable> iconQueue = new ArrayDeque<>();
    /** Draws queued icons a few milliseconds per frame, so a list of thousands never freezes the window. */
    private final javafx.animation.AnimationTimer iconPump = new javafx.animation.AnimationTimer() {
        @Override
        public void handle(long now) {
            long start = System.nanoTime();
            while (!iconQueue.isEmpty() && System.nanoTime() - start < 6_000_000) iconQueue.poll().run();
            if (iconQueue.isEmpty()) stop();
        }
    };

    /**
     * The picker's picture of a block or item as the game's inventory shows it: blocks and
     * block-shaped items turned three-quarters (30 degrees down, 225 around), flat items as their
     * sprite. Drawn from the same model and materials as the scene, so the tile is what gets placed.
     */
    Image renderIcon(String id, boolean item) {
        List<McAssets.Quad> quads = item ? assets.itemQuads(id) : assets.blockState(id).quads();
        if (quads.isEmpty()) return null;
        boolean flat = true;
        for (McAssets.Quad q : quads) {
            for (int i = 2; i < 12; i += 3) if (Math.abs(q.pos()[i] - 0.5f) > 0.07f) flat = false;
        }
        if (flat && item) return assets.icon(id, true);
        // Materials of its own, dropped with the icon: going through the scene's cache would keep
        // an 8x texture for every block ever shown in the list, hundreds of megabytes of them.
        Map<String, PhongMaterial> own = new HashMap<>();
        Group model = new Group();
        model.getChildren().addAll(mergedViews(quads, id, q -> own.computeIfAbsent(
                q.texture() + "|" + q.tinted() + "|" + q.shade(), k -> iconMaterial(q, id))));
        double s = ICON * ICON_SCALE;
        model.getTransforms().setAll(new Translate(ICON / 2.0, ICON / 2.0, 0), new Scale(s, s, s),
                new Rotate(180, Rotate.X_AXIS), new Rotate(ICON_PITCH, Rotate.X_AXIS), new Rotate(ICON_YAW, Rotate.Y_AXIS),
                new Translate(-0.5, -0.5, -0.5));
        Group world = new Group(model);
        javafx.scene.ParallelCamera cam = new javafx.scene.ParallelCamera();
        cam.setNearClip(0.001);
        cam.setFarClip(100000);
        javafx.scene.SnapshotParameters p = new javafx.scene.SnapshotParameters();
        p.setFill(Color.TRANSPARENT);
        p.setDepthBuffer(true);
        p.setCamera(cam);
        p.setViewport(new javafx.geometry.Rectangle2D(0, 0, ICON, ICON));
        return world.snapshot(p, new WritableImage(ICON, ICON));
    }

    /**
     * A snapshot of a node outside any window ignores lights (icons came back black), so an
     * icon's faces glow by themselves: the face's shade and tint baked into its own picture.
     */
    private PhongMaterial iconMaterial(McAssets.Quad q, String id) {
        PhongMaterial g = new PhongMaterial(Color.BLACK);
        g.setSpecularColor(Color.TRANSPARENT);
        Image tex = assets.texture(q.texture());
        int hash = q.texture().indexOf('#');
        Color tint = tex == null ? Color.hsb(Math.abs(id.hashCode()) % 360, 0.45, 0.85)
                : hash >= 0 ? Color.web(q.texture().substring(hash))
                : q.tinted() ? Color.web("#7cbd6b") : Color.WHITE;
        double k = q.shade();
        if (tex == null) {
            WritableImage one = new WritableImage(1, 1);
            one.getPixelWriter().setColor(0, 0, Color.color(tint.getRed() * k, tint.getGreen() * k, tint.getBlue() * k));
            g.setSelfIlluminationMap(one);
            return g;
        }
        Image big = upscale(tex, 4);
        int w = (int) big.getWidth(), h = (int) big.getHeight();
        PixelReader r = big.getPixelReader();
        WritableImage out = new WritableImage(w, h);
        PixelWriter pw = out.getPixelWriter();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                Color t = r.getColor(x, y);
                pw.setColor(x, y, Color.color(t.getRed() * tint.getRed() * k, t.getGreen() * tint.getGreen() * k,
                        t.getBlue() * tint.getBlue() * k, t.getOpacity()));
            }
        }
        g.setDiffuseMap(big);   // keeps the cut-out pixels (leaves, glass) see-through
        g.setSelfIlluminationMap(out);
        return g;
    }

    /** The inventory's own view of a block: 30 degrees from above, the front to the lower left. */
    private static final double ICON_SCALE = 0.58, ICON_PITCH = 30, ICON_YAW = 135;

    // ------------------------------------------------------------------ viewport (centre)

    private Group floorGrid;

    private Node buildViewport() {
        mcRoot.getTransforms().addAll(new Rotate(180, Rotate.X_AXIS), new Scale(UNIT, UNIT, UNIT));
        floorGrid = buildGrid();
        mcRoot.getChildren().addAll(floorGrid, subGrid, objects, axisGuide, gizmo.root);
        Group world = new Group(mcRoot, new AmbientLight(Color.WHITE));

        camera.setFieldOfView(40);
        camera.setNearClip(0.5);
        camera.setFarClip(5000);
        camera.getTransforms().setAll(camTarget, camYaw, camPitch, camDistance);

        subScene = new SubScene(world, 500, 420, true, SceneAntialiasing.BALANCED);
        subScene.setCamera(camera);
        subScene.setFill(Color.TRANSPARENT);

        StackPane holder = new StackPane(subScene);
        holder.getStyleClass().add("panel");
        holder.setMinSize(260, 300);
        subScene.widthProperty().bind(holder.widthProperty().subtract(2));
        subScene.heightProperty().bind(holder.heightProperty().subtract(2));

        Label hint = new Label(host.tr("studio_view_hint"));
        hint.getStyleClass().addAll("toggle-sub", "studio-hint");
        hint.setMaxWidth(Region.USE_PREF_SIZE);
        hint.setWrapText(true);
        hint.setMouseTransparent(true);
        StackPane.setAlignment(hint, Pos.BOTTOM_CENTER);
        StackPane.setMargin(hint, new Insets(0, 60, 10, 12));
        Button resetView = new Button();
        resetView.setGraphic(new FontIcon("fth-maximize"));
        resetView.getStyleClass().add("secondary-button");
        resetView.setTooltip(new Tooltip(host.tr("studio_reset_view")));
        resetView.setOnAction(e -> resetView());
        StackPane.setAlignment(resetView, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(resetView, new Insets(10));
        Button shot = new Button();
        shot.setGraphic(new FontIcon("fth-camera"));
        shot.getStyleClass().add("secondary-button");
        shot.setTooltip(new Tooltip(host.tr("studio_snapshot")));
        shot.setOnAction(e -> saveScenePicture());
        StackPane.setAlignment(shot, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(shot, new Insets(10, 60, 10, 10));
        holder.getChildren().addAll(hint, resetView, shot, buildTransformBar());

        subScene.setOnMousePressed(e -> {
            root.requestFocus();
            dragX = e.getSceneX();
            dragY = e.getSceneY();
            draggingObject = false;
            gizmoDrag = null;
            Node picked = e.getPickResult().getIntersectedNode();
            if (e.getButton() == MouseButton.PRIMARY && selected != null
                    && picked != null && picked.getUserData() instanceof String id && id.startsWith("gizmo:")) {
                gizmoDrag = id.substring(6);
                pushUndo();
                // Ctrl on a move arrow: a row of copies follows the mouse instead of a move
                arrayDrag = e.isControlDown() && gizmoDrag.startsWith("move:") && !gizmoDrag.endsWith("free");
                arrayAcc = 0;
                arrayCopies.clear();
                dragAccX = dragAccY = dragAccZ = 0;
                rotAcc = 0;
                javafx.geometry.Point2D c = project(selected.x, selected.y, selected.z);
                lastAngle = Math.atan2(e.getY() - c.getY(), e.getX() - c.getX());
                showSubGrid(e.isShiftDown());
                return;
            }
            if (e.getButton() == MouseButton.SECONDARY && !e.isShiftDown() && handId != null) {
                placeAt(e.getX(), e.getY(), picked, e.getPickResult().getIntersectedPoint());
                return;
            }
            if (e.getButton() == MouseButton.PRIMARY) {
                StudioCommand.Item hit = itemAt(picked);
                if (hit != null) {
                    if (e.isShiftDown() && selected != null && hit != selected) {
                        toggleInGroup(hit);   // Shift+click adds to (or takes out of) the selection
                    } else if (group.contains(hit)) {
                        makePrimary(hit);     // a click inside the selection keeps it whole
                    } else if (hit != selected) {
                        select(hit);
                    }
                    pushUndo();
                    draggingObject = true;
                    dragAccX = dragAccY = dragAccZ = 0;
                    rotAcc = 0;
                } else if (e.getClickCount() == 2) {
                    select(null);
                }
            }
        });
        subScene.setOnMouseDragged(e -> {
            double dx = e.getSceneX() - dragX;
            double dy = e.getSceneY() - dragY;
            dragX = e.getSceneX();
            dragY = e.getSceneY();
            snapStep = e.isShiftDown() ? SNAP_FINE : SNAP_COARSE;
            if (gizmoDrag != null && selected != null) {
                dragHandle(e.getX(), e.getY(), dx, dy, e.isShiftDown(), e.isAltDown(), e.isControlDown());
                return;
            }
            if (draggingObject && selected != null) {
                switch (mode) {
                    case MOVE -> moveByScreen(dx, dy, false, e.isAltDown());
                    case ROTATE -> rotateByScreen(dx, dy, e.isAltDown() || e.isShiftDown(), e.isControlDown());
                    case SCALE -> scaleByScreen(dx, dy, e.isAltDown());
                }
            } else if (e.getButton() == MouseButton.MIDDLE || (e.getButton() == MouseButton.SECONDARY && e.isShiftDown())) {
                pan(dx, dy);
            } else {
                double dir = host.invertMouse() ? -1 : 1;
                camYaw.setAngle(camYaw.getAngle() + dir * dx * 0.4);
                camPitch.setAngle(Math.max(-89, Math.min(89, camPitch.getAngle() - dir * dy * 0.4)));
            }
        });
        subScene.setOnMouseReleased(e -> {
            if (draggingObject || gizmoDrag != null) {
                if (arrayDrag) {
                    arrayDrag = false;
                    if (!arrayCopies.isEmpty()) host.toast(host.tr("studio_array_made") + " " + arrayCopies.size(), "success");
                    arrayCopies.clear();
                    renderSceneList();
                }
                draggingObject = false;
                gizmoDrag = null;
                subGrid.getChildren().clear();
                commit(false);
                if (selected != null) focusOn(selected);   // the camera keeps the selection in the middle
            }
        });
        Label hoverName = new Label();
        hoverName.getStyleClass().addAll("toggle-sub", "studio-hint");
        hoverName.setManaged(false);
        hoverName.setMouseTransparent(true);
        hoverName.setVisible(false);
        holder.getChildren().add(hoverName);
        subScene.setOnMouseMoved(e -> {
            gizmo.hover(e.getPickResult().getIntersectedNode());
            StudioCommand.Item over = itemAt(e.getPickResult().getIntersectedNode());
            if (over == null) {
                hoverName.setVisible(false);
                return;
            }
            hoverName.setText(assets.displayName(over.id, over.kind == StudioCommand.Kind.ITEM, host.language()));
            hoverName.autosize();
            hoverName.relocate(Math.min(e.getX() + 16, holder.getWidth() - hoverName.getWidth() - 8), e.getY() + 18);
            hoverName.setVisible(true);
        });
        subScene.setOnMouseExited(e -> hoverName.setVisible(false));
        subScene.addEventHandler(ScrollEvent.SCROLL, e -> {
            double z = camDistance.getZ() * Math.pow(0.999, e.getDeltaY());
            camDistance.setZ(Math.max(-60 * UNIT, Math.min(-1.2 * UNIT, z)));
            updateGizmo();
            e.consume();   // zooming must not scroll the page around the studio
        });
        return holder;
    }

    /** The floor grid, the axes, the target cell and the command block under it. */
    private Group buildGrid() {
        Group g = new Group();
        PhongMaterial line = flat(Color.rgb(140, 150, 165, 0.35));
        PhongMaterial xAxis = flat(Color.rgb(230, 80, 80));
        PhongMaterial zAxis = flat(Color.rgb(80, 130, 240));
        int n = 8;
        double t = 0.012;
        for (int i = -n; i <= n; i++) {
            Box alongX = new Box(2 * n, t, t);
            alongX.setTranslateZ(i);
            alongX.setMaterial(i == 0 ? xAxis : line);
            Box alongZ = new Box(t, t, 2 * n);
            alongZ.setTranslateX(i);
            alongZ.setMaterial(i == 0 ? zAxis : line);
            g.getChildren().addAll(alongX, alongZ);
        }
        g.getChildren().add(wireCube(0, 0, 0, 1, 1, 1, flat(Color.rgb(47, 181, 166, 0.8)), 0.02));
        g.getChildren().add(wireCube(0, -1, 0, 1, 0, 1, flat(Color.rgb(214, 140, 60, 0.85)), 0.02));
        return g;
    }

    private static Group wireCube(double x1, double y1, double z1, double x2, double y2, double z2,
                                  PhongMaterial m, double t) {
        Group g = new Group();
        double w = x2 - x1, h = y2 - y1, d = z2 - z1;
        double[][] xs = {{y1, z1}, {y2, z1}, {y1, z2}, {y2, z2}};
        for (double[] e : xs) g.getChildren().add(bar(w + t, t, t, x1 + w / 2, e[0], e[1], m));
        double[][] ys = {{x1, z1}, {x2, z1}, {x1, z2}, {x2, z2}};
        for (double[] e : ys) g.getChildren().add(bar(t, h + t, t, e[0], y1 + h / 2, e[1], m));
        double[][] zs = {{x1, y1}, {x2, y1}, {x1, y2}, {x2, y2}};
        for (double[] e : zs) g.getChildren().add(bar(t, t, d + t, e[0], e[1], z1 + d / 2, m));
        g.setMouseTransparent(true);
        return g;
    }

    private static Box bar(double w, double h, double d, double x, double y, double z, PhongMaterial m) {
        Box b = new Box(w, h, d);
        b.setTranslateX(x);
        b.setTranslateY(y);
        b.setTranslateZ(z);
        b.setMaterial(m);
        return b;
    }

    private static PhongMaterial flat(Color c) {
        PhongMaterial m = new PhongMaterial(c);
        m.setSpecularColor(Color.TRANSPARENT);
        return m;
    }

    /** The scene as a picture: without the handles, the floor grid and the selection frames. */
    private void saveScenePicture() {
        List<StudioCommand.Item> was = selection();
        select(null);
        boolean grid = floorGrid.isVisible();
        floorGrid.setVisible(false);
        gizmo.root.setVisible(false);
        try {
            java.io.File file = com.example.launcher.ui.Snapshots.saveAsPng(subScene, host.tr("studio_snapshot"), "scene.png");
            if (file != null) host.toast(host.tr("studio_snapshot_saved") + " " + file.getName(), "success");
        } catch (Exception ex) {
            host.toast(host.tr("studio_snapshot_failed"), "error");
        } finally {
            floorGrid.setVisible(grid);
            selectMany(was);
        }
    }

    private void resetView() {
        camYaw.setAngle(-35);
        camPitch.setAngle(-28);
        camDistance.setZ(-7 * UNIT);
        camTarget.setX(0.5 * UNIT);
        camTarget.setY(-0.5 * UNIT);
        camTarget.setZ(-0.5 * UNIT);
    }

    /** World units per screen pixel at the orbit target's distance. */
    private double unitsPerPixel() {
        double h = Math.max(1, subScene.getHeight());
        return (-camDistance.getZ() / UNIT) * 2 * Math.tan(Math.toRadians(camera.getFieldOfView() / 2)) / h;
    }

    private void pan(double dx, double dy) {
        double k = unitsPerPixel() * UNIT;
        double yaw = Math.toRadians(camYaw.getAngle());
        camTarget.setX(camTarget.getX() - Math.cos(yaw) * dx * k);
        camTarget.setZ(camTarget.getZ() + Math.sin(yaw) * dx * k);
        camTarget.setY(camTarget.getY() - dy * k);
    }

    /**
     * Drags the selection across the floor, along the camera's own right and forward directions so
     * "drag right" moves it to the right whatever the view; with Shift the drag lifts it instead.
     * Positions snap to a texel unless Alt is held.
     */
    private void moveByScreen(double dx, double dy, boolean vertical, boolean free) {
        double k = unitsPerPixel();
        double yaw = Math.toRadians(camYaw.getAngle());
        // camera right and forward in Minecraft coordinates (see the class note for the mapping)
        double rightX = Math.cos(yaw), rightZ = Math.sin(yaw);
        double fwdX = Math.sin(yaw), fwdZ = -Math.cos(yaw);
        if (axis == 'Y' || (axis == 'A' && vertical)) {
            dragAccY += -dy * k;
        } else if (axis == 'X') {
            dragAccX += (dx * rightX - dy * fwdX) * k;
        } else if (axis == 'Z') {
            dragAccZ += (dx * rightZ - dy * fwdZ) * k;
        } else {
            dragAccX += (dx * rightX - dy * fwdX) * k;
            dragAccZ += (dx * rightZ - dy * fwdZ) * k;
        }
        double mx = take('x', free), my = take('y', free), mz = take('z', free);
        if (mx == 0 && my == 0 && mz == 0) return;
        selected.x = round(selected.x + mx);
        selected.y = round(selected.y + my);
        selected.z = round(selected.z + mz);
        shiftGroup(mx, my, mz);
        refreshView(selected);
        renderProperties();
        updateCommand();
    }

    private double take(char axis, boolean free) {
        double acc = axis == 'x' ? dragAccX : axis == 'y' ? dragAccY : dragAccZ;
        double moved = free ? acc : Math.signum(acc) * Math.floor(Math.abs(acc) / snapStep + 1e-6) * snapStep;
        if (axis == 'x') dragAccX -= moved;
        else if (axis == 'y') dragAccY -= moved;
        else dragAccZ -= moved;
        return moved;
    }

    private static double round(double v) {
        return Math.round(v * 10000) / 10000.0;
    }

    private double rotAcc;

    // a row of copies dragged out of the selection with Ctrl on a move arrow
    private boolean arrayDrag;
    private double arrayAcc;
    private final List<StudioCommand.Item> arrayCopies = new ArrayList<>();
    private static final int ARRAY_LIMIT = 64;

    /**
     * Copies of the selection laid one after another along the arrow's axis, as many as the
     * pointer has been dragged object-lengths: they appear and vanish under the mouse, and
     * dragging back the other way lays them out on that side instead.
     */
    private void dragArray(char axis, double dx, double dy) {
        arrayAcc += alongAxis(StudioGizmo.axisVector(axis), dx, dy);
        double size = axis == 'X' ? selected.sx : axis == 'Y' ? selected.sy : selected.sz;
        if (size <= 0) size = 1;
        int want = Math.min(ARRAY_LIMIT, (int) Math.round(Math.abs(arrayAcc) / size));
        double sign = arrayAcc < 0 ? -1 : 1;
        while (arrayCopies.size() > want) {
            StudioCommand.Item gone = arrayCopies.remove(arrayCopies.size() - 1);
            items.remove(gone);
            objects.getChildren().remove(views.remove(gone));
        }
        while (arrayCopies.size() < want) {
            StudioCommand.Item copy = selected.copy();
            items.add(copy);
            Group view = new Group();
            view.setUserData(copy);
            views.put(copy, view);
            objects.getChildren().add(view);
            arrayCopies.add(copy);
        }
        for (int k = 0; k < arrayCopies.size(); k++) {
            StudioCommand.Item copy = arrayCopies.get(k);
            double step = sign * (k + 1) * size;
            copy.x = round(selected.x + (axis == 'X' ? step : 0));
            copy.y = round(selected.y + (axis == 'Y' ? step : 0));
            copy.z = round(selected.z + (axis == 'Z' ? step : 0));
            refreshView(copy);
        }
        updateCommand();
    }

    /**
     * Where a point in Minecraft coordinates lands on the 3D view, through the same camera the
     * view uses: how far "one block along X" is on screen is what turns a mouse move into a move
     * along that axis.
     */
    private javafx.geometry.Point2D project(double x, double y, double z) {
        try {
            javafx.geometry.Point3D c = camera.getLocalToSceneTransform()
                    .inverseTransform(new javafx.geometry.Point3D(x * UNIT, -y * UNIT, -z * UNIT));
            double h = subScene.getHeight(), w = subScene.getWidth();
            double f = (h / 2) / Math.tan(Math.toRadians(camera.getFieldOfView() / 2));
            double depth = Math.max(0.01, c.getZ());
            return new javafx.geometry.Point2D(w / 2 + c.getX() / depth * f, h / 2 + c.getY() / depth * f);
        } catch (javafx.scene.transform.NonInvertibleTransformException e) {
            return new javafx.geometry.Point2D(0, 0);
        }
    }

    /** Depth of a point in front of the camera, for telling which way an axis faces. */
    private double depthOf(double x, double y, double z) {
        try {
            return camera.getLocalToSceneTransform()
                    .inverseTransform(new javafx.geometry.Point3D(x * UNIT, -y * UNIT, -z * UNIT)).getZ();
        } catch (javafx.scene.transform.NonInvertibleTransformException e) {
            return 0;
        }
    }

    /** How many blocks along the axis a mouse move is: the move projected on the axis's screen direction. */
    private double alongAxis(javafx.geometry.Point3D a, double dx, double dy) {
        javafx.geometry.Point2D p0 = project(selected.x, selected.y, selected.z);
        javafx.geometry.Point2D p1 = project(selected.x + a.getX(), selected.y + a.getY(), selected.z + a.getZ());
        double sx = p1.getX() - p0.getX(), sy = p1.getY() - p0.getY();
        double len2 = sx * sx + sy * sy;
        return len2 < 1 ? 0 : (dx * sx + dy * sy) / len2;
    }

    private void dragHandle(double mx, double my, double dx, double dy, boolean fine, boolean free, boolean snap) {
        snapStep = fine ? SNAP_FINE : SNAP_COARSE;
        String[] parts = gizmoDrag.split(":");
        String kind = parts[0], ax = parts[1];
        if (arrayDrag) {
            dragArray(ax.charAt(0), dx, dy);
            return;
        }
        showSubGrid(fine);
        if (kind.equals("move")) {
            if (ax.equals("free")) {
                moveByScreen(dx, dy, false, free);
                return;
            }
            double amt = alongAxis(StudioGizmo.axisVector(ax.charAt(0)), dx, dy);
            if (ax.equals("X")) dragAccX += amt; else if (ax.equals("Y")) dragAccY += amt; else dragAccZ += amt;
            double mxv = take('x', free), myv = take('y', free), mzv = take('z', free);
            if (mxv == 0 && myv == 0 && mzv == 0) return;
            selected.x = round(selected.x + mxv);
            selected.y = round(selected.y + myv);
            selected.z = round(selected.z + mzv);
            shiftGroup(mxv, myv, mzv);
        } else if (kind.equals("rotate")) {
            javafx.geometry.Point2D c = project(selected.x, selected.y, selected.z);
            double angle = Math.atan2(my - c.getY(), mx - c.getX());
            double delta = Math.toDegrees(angle - lastAngle);
            lastAngle = angle;
            if (delta > 180) delta -= 360;
            if (delta < -180) delta += 360;
            javafx.geometry.Point3D a = StudioGizmo.axisVector(ax.charAt(0));
            // turning clockwise on screen is a negative turn about an axis that points at you
            boolean towardViewer = depthOf(selected.x + a.getX(), selected.y + a.getY(), selected.z + a.getZ())
                    < depthOf(selected.x, selected.y, selected.z);
            rotAcc += towardViewer ? -delta : delta;
            double step = rotationStep(free || fine, snap);
            double turned = Math.signum(rotAcc) * Math.floor(Math.abs(rotAcc) / step + 1e-6) * step;
            if (turned == 0) return;
            rotAcc -= turned;
            StudioCommand.rotateWorld(selected, ax.charAt(0), turned);
        } else {
            double step = free ? 0.001 : fine ? 1.0 / 16 : 0.25;
            if (ax.equals("free")) {
                dragAccX += (dx - dy) * 0.01;
                double grown = Math.signum(dragAccX) * Math.floor(Math.abs(dragAccX) / step + 1e-6) * step;
                if (grown == 0) return;
                dragAccX -= grown;
                selected.sx = Math.max(0.0625, round(selected.sx + grown));
                selected.sy = Math.max(0.0625, round(selected.sy + grown));
                selected.sz = Math.max(0.0625, round(selected.sz + grown));
            } else {
                double amt = alongAxis(StudioGizmo.axisVector(ax.charAt(0)), dx, dy);
                dragAccX += amt;
                double grown = Math.signum(dragAccX) * Math.floor(Math.abs(dragAccX) / step + 1e-6) * step;
                if (grown == 0) return;
                dragAccX -= grown;
                switch (ax.charAt(0)) {
                    case 'X' -> selected.sx = Math.max(0.0625, round(selected.sx + grown));
                    case 'Y' -> selected.sy = Math.max(0.0625, round(selected.sy + grown));
                    default -> selected.sz = Math.max(0.0625, round(selected.sz + grown));
                }
            }
        }
        afterDrag();
    }

    /** The divisions the drag snaps to, drawn on the floor under the selection while dragging. */
    private void showSubGrid(boolean fine) {
        subGrid.getChildren().clear();
        if (selected == null) return;
        double step = fine ? SNAP_FINE : SNAP_COARSE;
        double cx = Math.floor(selected.x), cz = Math.floor(selected.z);
        double y = Math.floor(selected.y - selected.sy / 2) + 0.002;
        PhongMaterial m = flat(Color.rgb(141, 107, 250, fine ? 0.45 : 0.6));
        double span = 3, t = fine ? 0.004 : 0.008;
        for (double d = 0; d <= span + 1e-9; d += step) {
            Box alongX = new Box(span, t, t);
            alongX.setTranslateX(cx - 1 + span / 2);
            alongX.setTranslateY(y);
            alongX.setTranslateZ(cz - 1 + d);
            alongX.setMaterial(m);
            Box alongZ = new Box(t, t, span);
            alongZ.setTranslateX(cx - 1 + d);
            alongZ.setTranslateY(y);
            alongZ.setTranslateZ(cz - 1 + span / 2);
            alongZ.setMaterial(m);
            subGrid.getChildren().addAll(alongX, alongZ);
        }
        subGrid.setMouseTransparent(true);
    }

    /** The handles sit on the selection and keep their size on screen. */
    private void updateGizmo() {
        if (selected == null) {
            gizmo.root.setVisible(false);
            return;
        }
        gizmo.root.setVisible(true);
        // never smaller than the object: the arrows and rings must reach out of what they move
        double biggest = Math.max(selected.sx, Math.max(selected.sy, selected.sz));
        double size = Math.max(biggest * 0.8 + 0.4, (-camDistance.getZ() / UNIT) * 0.16);
        javafx.scene.transform.Transform orient = new javafx.scene.transform.Affine();
        gizmo.root.getTransforms().setAll(new Translate(selected.x, selected.y, selected.z),
                new Scale(size, size, size), orient);
    }

    /**
     * Turns go by whole degrees, so a block can be tilted a little; Shift or Alt for tenths, Ctrl
     * to snap to 15 like Blender. (They used to go by 15 unless Shift was held, and a turn of a
     * couple of degrees looked impossible.)
     */
    private static double rotationStep(boolean fine, boolean snap) {
        return snap ? 15 : fine ? 0.1 : 1;
    }

    /** Drag left/right turns the object about the locked axis (Y when free), about the world's axis. */
    private void rotateByScreen(double dx, double dy, boolean fine, boolean snap) {
        rotAcc += (dx - dy) * 0.6;
        double step = rotationStep(fine, snap);
        double turned = Math.signum(rotAcc) * Math.floor(Math.abs(rotAcc) / step + 1e-6) * step;
        if (turned == 0) return;
        rotAcc -= turned;
        StudioCommand.rotateWorld(selected, axis == 'A' ? 'Y' : axis, turned);
        afterDrag();
    }

    /** Drag right or up grows, left or down shrinks; on all axes, or the locked one. */
    private void scaleByScreen(double dx, double dy, boolean free) {
        double f = Math.exp((dx - dy) * 0.008);
        java.util.function.DoubleUnaryOperator s = v -> {
            double n = Math.max(0.0625, Math.min(64, v * f));
            return free ? round(n) : Math.round(n * 100) / 100.0;
        };
        switch (axis) {
            case 'X' -> selected.sx = s.applyAsDouble(selected.sx);
            case 'Y' -> selected.sy = s.applyAsDouble(selected.sy);
            case 'Z' -> selected.sz = s.applyAsDouble(selected.sz);
            default -> {
                selected.sx = s.applyAsDouble(selected.sx);
                selected.sy = s.applyAsDouble(selected.sy);
                selected.sz = s.applyAsDouble(selected.sz);
            }
        }
        afterDrag();
    }

    private void afterDrag() {
        updateGizmo();
        refreshView(selected);
        renderProperties();
        updateCommand();
        updateAxisGuide();
    }

    /** Move / Rotate / Size and the axis lock, top left of the view - the header of Blender's viewport. */
    private Node buildTransformBar() {
        HBox modes = new HBox(2);
        modes.getStyleClass().add("lang-toggle");
        Object[][] m = {{Mode.MOVE, "fth-move", "studio_mode_move"}, {Mode.ROTATE, "fth-rotate-cw", "studio_mode_rotate"},
                {Mode.SCALE, "fth-maximize-2", "studio_mode_scale"}};
        for (Object[] row : m) {
            ToggleButton b = new ToggleButton();
            b.setGraphic(new FontIcon((String) row[1]));
            b.setTooltip(new Tooltip(host.tr((String) row[2]) + " (" + switch ((Mode) row[0]) {
                case MOVE -> "G"; case ROTATE -> "R"; case SCALE -> "S"; } + ")"));
            b.setMinWidth(Region.USE_PREF_SIZE);
            b.getStyleClass().add("lang-option");
            b.setToggleGroup(modeGroup);
            b.setUserData(row[0]);
            b.setSelected(row[0] == mode);
            b.setOnAction(e -> setMode((Mode) row[0]));
            modes.getChildren().add(b);
        }
        HBox axes = new HBox(2);
        axes.getStyleClass().add("lang-toggle");
        String[][] a = {{"A", "studio_axis_free"}, {"X", null}, {"Y", null}, {"Z", null}};
        for (String[] row : a) {
            ToggleButton b = new ToggleButton(row[1] == null ? row[0] : "XYZ");
            if (row[1] != null) b.setTooltip(new Tooltip(host.tr(row[1])));
            b.setMinWidth(Region.USE_PREF_SIZE);
            b.getStyleClass().add("lang-option");
            b.setToggleGroup(axisGroup);
            b.setUserData(row[0].charAt(0));
            b.setSelected(row[0].charAt(0) == axis);
            if (row[1] == null) {
                String colour = switch (row[0]) { case "X" -> "#e65050"; case "Y" -> "#50c878"; default -> "#5082f0"; };
                b.setStyle("-fx-text-fill: " + colour + ";");
            }
            b.setOnAction(e -> setAxis(row[0].charAt(0)));
            axes.getChildren().add(b);
        }
        // two compact rows: they fit even when the view is narrow
        VBox bar = new VBox(6, modes, axes);
        modes.setMaxWidth(Region.USE_PREF_SIZE);
        axes.setMaxWidth(Region.USE_PREF_SIZE);
        bar.setPickOnBounds(false);
        bar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        StackPane.setAlignment(bar, Pos.TOP_LEFT);
        StackPane.setMargin(bar, new Insets(10));
        return bar;
    }

    private void setMode(Mode m) {
        mode = m;
        gizmo.build(switch (m) {
            case MOVE -> StudioGizmo.Kind.MOVE;
            case ROTATE -> StudioGizmo.Kind.ROTATE;
            case SCALE -> StudioGizmo.Kind.SCALE;
        });
        updateGizmo();
        for (var t : modeGroup.getToggles()) t.setSelected(t.getUserData() == m);
    }

    private void setAxis(char a) {
        axis = a;
        for (var t : axisGroup.getToggles()) t.setSelected(t.getUserData().equals(a));
        updateAxisGuide();
    }

    /** A long line through the selection along the locked axis, so the lock is visible. */
    private void updateAxisGuide() {
        axisGuide.getChildren().clear();
        if (selected == null || axis == 'A') return;
        Color c = switch (axis) { case 'X' -> Color.rgb(230, 80, 80); case 'Y' -> Color.rgb(80, 200, 120); default -> Color.rgb(80, 130, 240); };
        double len = 40, t = 0.02;
        Box line = axis == 'X' ? new Box(len, t, t) : axis == 'Y' ? new Box(t, len, t) : new Box(t, t, len);
        line.setMaterial(flat(c));
        line.setTranslateX(selected.x);
        line.setTranslateY(selected.y);
        line.setTranslateZ(selected.z);
        line.setMouseTransparent(true);
        axisGuide.getChildren().add(line);
    }

    private StudioCommand.Item itemAt(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n.getUserData() instanceof StudioCommand.Item item) return item;
        }
        return null;
    }

    // ------------------------------------------------------------------ objects in 3D

    private void rebuildViews() {
        objects.getChildren().clear();
        views.clear();
        for (StudioCommand.Item item : items) {
            Group view = new Group();
            view.setUserData(item);
            views.put(item, view);
            objects.getChildren().add(view);
            refreshView(item);
        }
    }

    /** Geometry for the object's id, and its transform: translation * rotation * scale, as in the game. */
    private void refreshView(StudioCommand.Item item) {
        Group view = views.get(item);
        if (view == null) return;
        String role = item == selected ? "main" : group.contains(item) ? "also" : "";
        String key = item.kind + ":" + item.id + ":" + role + ":" + (item.glow ? item.glowColor : "");
        if (!key.equals(view.getProperties().get("built"))) {
            view.getProperties().put("built", key);
            List<McAssets.Quad> quads = item.kind == StudioCommand.Kind.BLOCK
                    ? assets.blockState(item.id).quads()
                    : assets.itemQuads(item.id);
            Group model = new Group();
            model.getChildren().addAll(mergedViews(quads, item.id, q -> material(q, item.id)));
            boolean centred = item.kind == StudioCommand.Kind.ITEM;
            if (centred) model.getTransforms().add(new Translate(-0.5, -0.5, -0.5));
            view.getChildren().setAll(model);
            if (item.glow) {
                double lo = centred ? -0.52 : -0.02, hi = centred ? 0.52 : 1.02;
                view.getChildren().add(wireCube(lo, lo, lo, hi, hi, hi, flat(safeColor(item.glowColor)), 0.04));
            }
            if (!role.isEmpty()) {
                double lo = centred ? -0.5 : 0, hi = centred ? 0.5 : 1;
                view.getChildren().add(wireCube(lo, lo, lo, hi, hi, hi,
                        flat(Color.web(role.equals("main") ? "#ffd34d" : "#7fd3ff")), 0.025));
            }
        }
        double[][] r = StudioCommand.matrix(item.rx, item.ry, item.rz);
        float[] t = StudioCommand.translation(item);
        double[] s = {item.sx, item.sy, item.sz};
        view.getTransforms().setAll(new Affine(
                r[0][0] * s[0], r[0][1] * s[1], r[0][2] * s[2], t[0],
                r[1][0] * s[0], r[1][1] * s[1], r[1][2] * s[2], t[1],
                r[2][0] * s[0], r[2][1] * s[1], r[2][2] * s[2], t[2]));
    }

    private List<MeshView> mergedViews(List<McAssets.Quad> quads, String id,
                                       java.util.function.Function<McAssets.Quad, PhongMaterial> materialOf) {
        Map<PhongMaterial, TriangleMesh> meshes = new java.util.LinkedHashMap<>();
        for (McAssets.Quad q : quads) {
            TriangleMesh mesh = meshes.computeIfAbsent(materialOf.apply(q), k -> new TriangleMesh());
            int base = mesh.getPoints().size() / 3;
            int tex = mesh.getTexCoords().size() / 2;
            for (int i = 0; i < 12; i++) mesh.getPoints().addAll(q.pos()[i]);
            for (int i = 0; i < 8; i++) mesh.getTexCoords().addAll(q.uv()[i]);
            mesh.getFaces().addAll(base, tex, base + 1, tex + 1, base + 2, tex + 2,
                    base, tex, base + 2, tex + 2, base + 3, tex + 3);
        }
        List<MeshView> out = new ArrayList<>();
        for (var e : meshes.entrySet()) {
            MeshView view = new MeshView(e.getValue());
            view.setCullFace(CullFace.NONE);
            view.setMaterial(e.getKey());
            out.add(view);
        }
        return out;
    }

    /**
     * One material per texture, tint and face shade. The game shades faces by direction (top
     * brightest, bottom darkest) and draws without lighting otherwise, which is exactly an ambient
     * light with the shade baked into the diffuse colour.
     */
    private PhongMaterial material(McAssets.Quad q, String id) {
        String key = q.texture() + "|" + q.tinted() + "|" + q.shade() + "|" + (q.texture().isEmpty() ? id : "");
        return materials.computeIfAbsent(key, k -> {
            PhongMaterial m = new PhongMaterial();
            m.setSpecularColor(Color.TRANSPARENT);
            Image tex = assets.texture(q.texture());
            if (tex != null) {
                m.setDiffuseMap(upscale(tex, 8));
                int hash = q.texture().indexOf('#');
                Color tint = hash >= 0 ? Color.web(q.texture().substring(hash))
                        : q.tinted() ? Color.web("#7cbd6b") : Color.WHITE;
                m.setDiffuseColor(Color.color(tint.getRed() * q.shade(), tint.getGreen() * q.shade(), tint.getBlue() * q.shade()));
            } else {
                Color c = Color.hsb(Math.abs(id.hashCode()) % 360, 0.45, 0.85);
                m.setDiffuseColor(Color.color(c.getRed() * q.shade(), c.getGreen() * q.shade(), c.getBlue() * q.shade()));
            }
            return m;
        });
    }

    /** Nearest-neighbour upscale: JavaFX filters textures linearly, which smears 16 px art. */
    static Image upscale(Image src, int f) {
        int w = (int) src.getWidth(), h = (int) src.getHeight();
        PixelReader r = src.getPixelReader();
        WritableImage out = new WritableImage(w * f, h * f);
        PixelWriter pw = out.getPixelWriter();
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int argb = r.getArgb(x, y);
                for (int dy = 0; dy < f; dy++)
                    for (int dx = 0; dx < f; dx++)
                        pw.setArgb(x * f + dx, y * f + dy, argb);
            }
        return out;
    }

    // ------------------------------------------------------------------ editing

    private void add(String id, boolean asItem) {
        pushUndo();
        StudioCommand.Item item = new StudioCommand.Item();
        item.kind = asItem ? StudioCommand.Kind.ITEM : StudioCommand.Kind.BLOCK;
        item.id = id;
        if (!asItem) item.properties.putAll(assets.blockState(id).properties());
        // next to the selection, or the cell above the command block - and never inside
        // something already there: three objects added in a row used to land in one spot and
        // hide each other
        double[] at = freeCell(selected != null ? selected.x + 1 : 0.5, selected != null ? selected.y : 0.5,
                selected != null ? selected.z : 0.5);
        item.x = at[0];
        item.y = at[1];
        item.z = at[2];
        items.add(item);
        Group view = new Group();
        view.setUserData(item);
        views.put(item, view);
        objects.getChildren().add(view);
        select(item);
        commit(true);
        focusOn(item);
    }

    /**
     * Places the block in hand where the right click landed, the way the game does: on a face of
     * an object the new one goes against that face; on the floor it goes into the cell under the
     * pointer.
     */
    private void placeAt(double sx, double sy, Node picked, javafx.geometry.Point3D local) {
        double[] at = null;
        StudioCommand.Item hit = itemAt(picked);
        if (hit != null && local != null) {
            // the hit point in the model's own 0..1 box tells which face was clicked
            double lx = local.getX(), ly = local.getY(), lz = local.getZ();
            double[] d = {lx, 1 - lx, ly, 1 - ly, lz, 1 - lz};
            int face = 0;
            for (int i = 1; i < 6; i++) if (Math.abs(d[i]) < Math.abs(d[face])) face = i;
            double[] n = switch (face) {
                case 0 -> new double[]{-1, 0, 0};
                case 1 -> new double[]{1, 0, 0};
                case 2 -> new double[]{0, -1, 0};
                case 3 -> new double[]{0, 1, 0};
                case 4 -> new double[]{0, 0, -1};
                default -> new double[]{0, 0, 1};
            };
            at = new double[]{hit.x + n[0] * (hit.sx + 1) / 2, hit.y + n[1] * (hit.sy + 1) / 2, hit.z + n[2] * (hit.sz + 1) / 2};
        } else {
            double[] floor = floorUnder(sx, sy);
            if (floor != null) at = new double[]{Math.floor(floor[0]) + 0.5, 0.5, Math.floor(floor[1]) + 0.5};
        }
        if (at == null) return;
        pushUndo();
        StudioCommand.Item item = new StudioCommand.Item();
        item.kind = handIsItem ? StudioCommand.Kind.ITEM : StudioCommand.Kind.BLOCK;
        item.id = handId;
        if (!handIsItem) item.properties.putAll(assets.blockState(handId).properties());
        item.x = round(at[0]);
        item.y = round(at[1]);
        item.z = round(at[2]);
        items.add(item);
        Group view = new Group();
        view.setUserData(item);
        views.put(item, view);
        objects.getChildren().add(view);
        select(item);
        commit(true);
        com.example.launcher.ui.Motion.stagger(java.util.List.of(view));
    }

    /** Where the pointer's ray meets the floor (y = 0), in blocks; null when it points at the sky. */
    private double[] floorUnder(double sx, double sy) {
        double h = subScene.getHeight(), w = subScene.getWidth();
        double f = (h / 2) / Math.tan(Math.toRadians(camera.getFieldOfView() / 2));
        javafx.scene.transform.Transform t = camera.getLocalToSceneTransform();
        javafx.geometry.Point3D o = t.transform(0, 0, 0);
        javafx.geometry.Point3D p = t.transform((sx - w / 2) / f, (sy - h / 2) / f, 1);
        // JavaFX -> Minecraft: (x, -y, -z) / UNIT
        double oy = -o.getY() / UNIT, py = -p.getY() / UNIT;
        double dy = py - oy;
        if (Math.abs(dy) < 1e-9) return null;
        double k = -oy / dy;
        if (k <= 0) return null;
        double ox = o.getX() / UNIT, oz = -o.getZ() / UNIT;
        double dx = p.getX() / UNIT - ox, dz = -p.getZ() / UNIT - oz;
        return new double[]{ox + dx * k, oz + dz * k};
    }

    private javafx.animation.Timeline glide;

    private void focusOn(StudioCommand.Item item) {
        if (glide != null) glide.stop();   // a new target replaces the one still being flown to
        glide = new javafx.animation.Timeline(new javafx.animation.KeyFrame(
                javafx.util.Duration.millis(500),
                new javafx.animation.KeyValue(camTarget.xProperty(), item.x * UNIT, com.example.launcher.ui.Motion.EASE_OUT),
                new javafx.animation.KeyValue(camTarget.yProperty(), -item.y * UNIT, com.example.launcher.ui.Motion.EASE_OUT),
                new javafx.animation.KeyValue(camTarget.zProperty(), -item.z * UNIT, com.example.launcher.ui.Motion.EASE_OUT)));
        glide.play();
    }

    private double[] freeCell(double x, double y, double z) {
        for (int ring = 0; ring < 12; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    double cx = x + dx, cz = z + dz;
                    boolean taken = false;
                    for (StudioCommand.Item i : items) {
                        if (Math.abs(i.x - cx) < 0.5 && Math.abs(i.y - y) < 0.5 && Math.abs(i.z - cz) < 0.5) {
                            taken = true;
                            break;
                        }
                    }
                    if (!taken) return new double[]{cx, y, cz};
                }
            }
        }
        return new double[]{x, y + 1, z};
    }

    /**
     * What Ctrl+C took. Kept for the whole launcher session, so an object copied in one scene can
     * be pasted into another one opened later.
     */
    private static List<StudioCommand.Item> clipboard = List.of();

    private void copySelection() {
        if (selected == null) return;
        List<StudioCommand.Item> taken = new ArrayList<>();
        for (StudioCommand.Item it : selection()) taken.add(it.copy());
        clipboard = taken;
        host.toast(host.tr("studio_copied") + " " + (taken.size() == 1
                ? assets.displayName(selected.id, selected.kind == StudioCommand.Kind.ITEM, host.language())
                : String.valueOf(taken.size())), "info");
    }

    private void cutSelection() {
        if (selected == null) return;
        copySelection();
        delete();
    }

    /**
     * A copy of what was copied, keeping the objects' places relative to each other: its first
     * object goes to the first free cell next to the selection - or, with nothing selected, back
     * where it was if that is free (so Ctrl+X then Ctrl+V puts it back).
     */
    private void paste() {
        if (clipboard.isEmpty()) return;
        pushUndo();
        StudioCommand.Item lead = clipboard.get(0);
        double[] at = selected != null ? freeCell(selected.x + 1, selected.y, selected.z) : freeCell(lead.x, lead.y, lead.z);
        double dx = at[0] - lead.x, dy = at[1] - lead.y, dz = at[2] - lead.z;
        List<StudioCommand.Item> pasted = new ArrayList<>();
        for (StudioCommand.Item it : clipboard) {
            StudioCommand.Item copy = it.copy();
            copy.x = round(copy.x + dx);
            copy.y = round(copy.y + dy);
            copy.z = round(copy.z + dz);
            addView(copy);
            pasted.add(copy);
        }
        selectMany(pasted);
        commit(true);
        focusOn(pasted.get(0));
    }

    // ------------------------------------------------------------------ several objects at once

    /** Objects selected together with {@link #selected}, which stays the one the handles sit on. */
    private final java.util.LinkedHashSet<StudioCommand.Item> group = new java.util.LinkedHashSet<>();

    /** The selection in order: the main object first. */
    private List<StudioCommand.Item> selection() {
        List<StudioCommand.Item> all = new ArrayList<>();
        if (selected != null) all.add(selected);
        all.addAll(group);
        return all;
    }

    private void shiftGroup(double dx, double dy, double dz) {
        for (StudioCommand.Item it : group) {
            it.x = round(it.x + dx);
            it.y = round(it.y + dy);
            it.z = round(it.z + dz);
            refreshView(it);
        }
    }

    private void toggleInGroup(StudioCommand.Item item) {
        if (!group.remove(item)) group.add(item);
        refreshView(item);
        renderProperties();
    }

    /** Hands the handles to another object of the selection without breaking it up. */
    private void makePrimary(StudioCommand.Item item) {
        StudioCommand.Item old = selected;
        group.remove(item);
        if (old != null) group.add(old);
        selected = item;
        if (old != null) refreshView(old);
        refreshView(item);
        sceneList.getSelectionModel().select(item);
        updateGizmo();
        updateAxisGuide();
        renderProperties();
    }

    private void selectMany(List<StudioCommand.Item> list) {
        select(list.isEmpty() ? null : list.get(0));
        for (int i = 1; i < list.size(); i++) group.add(list.get(i));
        for (StudioCommand.Item it : group) refreshView(it);
        renderProperties();
    }

    private void selectAll() {
        if (items.isEmpty()) return;
        StudioCommand.Item keep = selected != null ? selected : items.get(0);
        List<StudioCommand.Item> all = new ArrayList<>();
        all.add(keep);
        for (StudioCommand.Item it : items) if (it != keep) all.add(it);
        selectMany(all);
    }

    private void duplicate() {
        if (selected == null) return;
        pushUndo();
        List<StudioCommand.Item> copies = new ArrayList<>();
        for (StudioCommand.Item it : selection()) {
            StudioCommand.Item copy = it.copy();
            copy.x += 1;
            addView(copy);
            copies.add(copy);
        }
        selectMany(copies);
        commit(true);
    }

    private void addView(StudioCommand.Item item) {
        items.add(item);
        Group view = new Group();
        view.setUserData(item);
        views.put(item, view);
        objects.getChildren().add(view);
    }

    private void delete() {
        if (selected == null) return;
        pushUndo();
        for (StudioCommand.Item it : selection()) {
            objects.getChildren().remove(views.remove(it));
            items.remove(it);
        }
        group.clear();
        selected = null;
        commit(true);
        renderProperties();
    }

    private void clearScene() {
        if (items.isEmpty()) return;
        pushUndo();
        items.clear();
        selected = null;
        sceneTag = newTag();
        rebuildViews();
        commit(true);
        renderProperties();
    }

    private void nudge(double dx, double dy, double dz) {
        pushUndo();
        selected.x = round(selected.x + dx);
        selected.y = round(selected.y + dy);
        selected.z = round(selected.z + dz);
        shiftGroup(dx, dy, dz);
        commit(false);
        renderProperties();
        focusOn(selected);
    }

    private void select(StudioCommand.Item item) {
        StudioCommand.Item previous = selected;
        if (!group.isEmpty()) {
            List<StudioCommand.Item> was = new ArrayList<>(group);
            group.clear();
            for (StudioCommand.Item it : was) refreshView(it);
        }
        selected = item;
        if (previous != null) refreshView(previous);
        if (item != null) refreshView(item);
        updateAxisGuide();
        updateGizmo();
        if (sceneList.getSelectionModel().getSelectedItem() != item) {
            if (item == null) sceneList.getSelectionModel().clearSelection();
            else sceneList.getSelectionModel().select(item);
        }
        renderProperties();
    }

    /** After any change: redraw what moved, rewrite the command, save a moment later. */
    private void commit(boolean listChanged) {
        if (selected != null) refreshView(selected);
        updateAxisGuide();
        updateGizmo();
        if (listChanged) renderSceneList();
        updateCommand();
        saveLater.playFromStart();
    }

    private void renderSceneList() {
        sceneList.getItems().setAll(items);
        if (selected != null) sceneList.getSelectionModel().select(selected);
    }

    private final Deque<List<StudioCommand.Item>> redo = new ArrayDeque<>();

    private List<StudioCommand.Item> snapshot() {
        List<StudioCommand.Item> copy = new ArrayList<>();
        for (StudioCommand.Item i : items) copy.add(i.copy());
        return copy;
    }

    private void pushUndo() {
        undo.push(snapshot());
        while (undo.size() > UNDO_LIMIT) undo.removeLast();
        redo.clear();   // a new change ends the line of undone ones
    }

    /** Ctrl+Y: what Ctrl+Z took back, done again. */
    private void redo() {
        if (redo.isEmpty()) return;
        undo.push(snapshot());
        items.clear();
        items.addAll(redo.pop());
        group.clear();
        selected = null;
        rebuildViews();
        commit(true);
        renderProperties();
    }

    private void undo() {
        if (undo.isEmpty()) return;
        redo.push(snapshot());
        items.clear();
        items.addAll(undo.pop());
        group.clear();
        selected = null;
        rebuildViews();
        commit(true);
        renderProperties();
    }

    // ------------------------------------------------------------------ properties (right)

    private Node buildProperties() {
        properties.getStyleClass().add("panel");
        properties.setPadding(new Insets(14));
        properties.setPrefWidth(260);
        properties.setMinWidth(230);
        ScrollPane scroll = new ScrollPane(properties);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("screen-scroll");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMinWidth(240);
        return scroll;
    }

    private void renderProperties() {
        properties.getChildren().clear();
        if (selected == null) {
            Label title = new Label(host.tr("studio_nothing_selected"));
            title.getStyleClass().add("toggle-label");
            title.setWrapText(true);
            Label sub = new Label(host.tr("studio_how_to"));
            sub.getStyleClass().add("toggle-sub");
            sub.setWrapText(true);
            properties.getChildren().addAll(title, sub);
            return;
        }
        StudioCommand.Item it = selected;
        Label title = new Label(assets.displayName(it.id, it.kind == StudioCommand.Kind.ITEM, host.language()));
        title.getStyleClass().add("tool-card-title");
        title.setWrapText(true);
        Label idLabel = new Label("minecraft:" + it.id);
        idLabel.getStyleClass().addAll("toggle-sub", "mono");
        Label kind = new Label(host.tr(it.kind == StudioCommand.Kind.BLOCK ? "studio_kind_block" : "studio_kind_item"));
        kind.getStyleClass().add("toggle-sub");

        if (it.kind == StudioCommand.Kind.BLOCK && StudioCommand.drawnAsItem(it.id)) {
            kind.setText(host.tr("studio_drawn_as_item"));
            kind.setWrapText(true);
        }
        if (!group.isEmpty()) {
            Label many = new Label(host.tr("studio_selected_many") + " " + (group.size() + 1));
            many.getStyleClass().addAll("toggle-sub");
            many.setWrapText(true);
            properties.getChildren().add(many);
        }
        properties.getChildren().addAll(title, idLabel, kind,
                section("studio_position"),
                triple(it.x, it.y, it.z, SNAP, v -> it.x = v, v -> it.y = v, v -> it.z = v),
                section("studio_rotation"),
                triple(it.rx, it.ry, it.rz, 5, v -> it.rx = v, v -> it.ry = v, v -> it.rz = v),
                section("studio_scale"));

        CheckBox uniform = new CheckBox(host.tr("studio_uniform"));
        uniform.setWrapText(true);
        uniform.setSelected(it.sx == it.sy && it.sy == it.sz);
        properties.getChildren().add(triple(it.sx, it.sy, it.sz, 0.125,
                v -> { if (uniform.isSelected()) { it.sx = it.sy = it.sz = v; renderLater(); } else it.sx = v; },
                v -> { if (uniform.isSelected()) { it.sx = it.sy = it.sz = v; renderLater(); } else it.sy = v; },
                v -> { if (uniform.isSelected()) { it.sx = it.sy = it.sz = v; renderLater(); } else it.sz = v; }));
        properties.getChildren().add(uniform);

        if (it.kind == StudioCommand.Kind.ITEM) {
            ComboBox<String> mode = new ComboBox<>();
            mode.getItems().addAll("none", "fixed", "ground", "head", "gui",
                    "thirdperson_righthand", "firstperson_righthand");
            mode.setValue(it.itemDisplay);
            mode.setMaxWidth(Double.MAX_VALUE);
            mode.setOnAction(e -> {
                pushUndo();
                it.itemDisplay = mode.getValue();
                commit(false);
            });
            Label modeHint = new Label(host.tr("studio_display_hint"));
            modeHint.getStyleClass().add("toggle-sub");
            modeHint.setWrapText(true);
            properties.getChildren().addAll(section("studio_display_mode"), mode, modeHint);
        } else if (!it.properties.isEmpty()) {
            Label props = new Label(it.properties.toString().replace("{", "").replace("}", ""));
            props.getStyleClass().add("toggle-sub");
            props.setWrapText(true);
            properties.getChildren().addAll(section("studio_state"), props);
        }

        CheckBox bright = new CheckBox(host.tr("studio_full_bright"));
        bright.setWrapText(true);
        bright.setSelected(it.fullBright);
        bright.setOnAction(e -> {
            pushUndo();
            for (StudioCommand.Item s : selection()) s.fullBright = bright.isSelected();
            commit(false);
        });
        CheckBox glowing = new CheckBox(host.tr("studio_glow"));
        glowing.setSelected(it.glow);
        javafx.scene.control.ColorPicker glowColor = new javafx.scene.control.ColorPicker(safeColor(it.glowColor));
        glowColor.getStyleClass().add("studio-color");
        glowColor.setMaxWidth(Double.MAX_VALUE);
        glowColor.setDisable(!it.glow);
        glowing.setOnAction(e -> {
            pushUndo();
            for (StudioCommand.Item s : selection()) {
                s.glow = glowing.isSelected();
                s.glowColor = toWeb(glowColor.getValue());
                refreshView(s);
            }
            glowColor.setDisable(!glowing.isSelected());
            commit(false);
        });
        glowColor.setOnAction(e -> {
            pushUndo();
            for (StudioCommand.Item s : selection()) {
                s.glowColor = toWeb(glowColor.getValue());
                refreshView(s);
            }
            commit(false);
        });
        Label lightHint = new Label(host.tr("studio_light_hint"));
        lightHint.getStyleClass().add("toggle-sub");
        lightHint.setWrapText(true);
        properties.getChildren().addAll(section("studio_light"), bright, glowing, glowColor, lightHint);

        Button dup = new Button(host.tr("studio_duplicate"));
        dup.setGraphic(new FontIcon("fth-copy"));
        dup.getStyleClass().add("secondary-button");
        dup.setMaxWidth(Double.MAX_VALUE);
        dup.setOnAction(e -> duplicate());
        Button del = new Button(host.tr("studio_delete"));
        del.setGraphic(new FontIcon("fth-trash-2"));
        del.getStyleClass().add("danger-button");
        del.setMaxWidth(Double.MAX_VALUE);
        del.setOnAction(e -> delete());
        Region gap = new Region();
        gap.setMinHeight(6);
        properties.getChildren().addAll(gap, dup, del);
    }

    private static Color safeColor(String web) {
        try {
            return Color.web(web);
        } catch (Exception e) {
            return Color.WHITE;
        }
    }

    private static String toWeb(Color c) {
        return String.format("#%02X%02X%02X", (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
    }

    private void renderLater() {
        Platform.runLater(this::renderProperties);
    }

    private Label section(String key) {
        Label l = new Label(host.tr(key));
        l.getStyleClass().add("toggle-label");
        return l;
    }

    private Node triple(double a, double b, double c, double step,
                        DoubleConsumer setA, DoubleConsumer setB, DoubleConsumer setC) {
        GridPane grid = new GridPane();
        grid.setHgap(6);
        grid.add(numberField("X", a, step, setA, "#e65050"), 0, 0);
        grid.add(numberField("Y", b, step, setB, "#50c878"), 1, 0);
        grid.add(numberField("Z", c, step, setC, "#5082f0"), 2, 0);
        return grid;
    }

    /**
     * A number with its axis letter, the way Blender does it: type a value, roll the wheel over the
     * field, or drag the letter left and right to scrub.
     */
    private Node numberField(String axis, double value, double step, DoubleConsumer setter, String colour) {
        TextField field = new TextField(format(value));
        field.getStyleClass().addAll("text-input", "mono");
        field.setPrefColumnCount(4);
        field.setMinWidth(58);   // "-0.5625" stays readable even in a narrow panel
        Label letter = new Label(axis);
        letter.setStyle("-fx-text-fill: " + colour + "; -fx-font-family: 'Inter SemiBold'; -fx-cursor: h-resize;");
        letter.setMinWidth(12);

        final double[] current = {value};
        Runnable apply = () -> {
            setter.accept(current[0]);
            field.setText(format(current[0]));
            if (selected != null) refreshView(selected);
            updateCommand();
            saveLater.playFromStart();
        };
        field.setOnAction(e -> {
            Double parsed = parse(field.getText());
            if (parsed == null) {
                field.setText(format(current[0]));
                return;
            }
            pushUndo();
            current[0] = parsed;
            apply.run();
        });
        field.focusedProperty().addListener((obs, o, focused) -> {
            if (!focused) {
                Double parsed = parse(field.getText());
                if (parsed != null && parsed != current[0]) {
                    pushUndo();
                    current[0] = parsed;
                    apply.run();
                } else {
                    field.setText(format(current[0]));
                }
            }
        });
        field.addEventFilter(ScrollEvent.SCROLL, e -> {
            pushUndo();
            current[0] = round(current[0] + Math.signum(e.getDeltaY()) * step);
            apply.run();
            e.consume();
        });
        final double[] press = new double[1];
        letter.setOnMousePressed(e -> {
            press[0] = e.getScreenX();
            pushUndo();
        });
        letter.setOnMouseDragged(e -> {
            double steps = Math.floor((e.getScreenX() - press[0]) / 6);
            if (steps == 0) return;
            press[0] += steps * 6;
            current[0] = round(current[0] + steps * step);
            apply.run();
        });

        HBox box = new HBox(4, letter, field);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    private static String format(double v) {
        String s = String.format(Locale.ROOT, "%.4f", v).replaceAll("0+$", "");
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    private static Double parse(String text) {
        try {
            double v = Double.parseDouble(text.trim().replace(',', '.'));
            return Double.isFinite(v) ? v : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ command (bottom)

    private Node buildCommandPanel() {
        targetBox.getItems().setAll(StudioCommand.Target.values());
        targetBox.setValue(StudioCommand.Target.MODERN);
        targetBox.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(StudioCommand.Target t, boolean empty) {
                super.updateItem(t, empty);
                setText(empty || t == null ? null : t.label);
            }
        });
        targetBox.setButtonCell(targetBox.getCellFactory().call(null));
        targetBox.setOnAction(e -> updateCommand());

        originField.getStyleClass().addAll("text-input", "mono");
        originField.setPrefColumnCount(14);
        originField.textProperty().addListener((obs, o, n) -> updateCommand());
        Tooltip.install(originField, new Tooltip(host.tr("studio_origin_hint")));

        Label originLabel = new Label(host.tr("studio_origin"));
        originLabel.getStyleClass().add("toggle-sub");

        Button copy = new Button(host.tr("studio_copy"));
        copy.setGraphic(new FontIcon("fth-clipboard"));
        copy.getStyleClass().addAll("play-btn", "modal-create-btn");
        copy.setOnAction(e -> {
            if (items.isEmpty()) {
                host.toast(host.tr("studio_empty"), "info");
                return;
            }
            host.copy(commandArea.getText());
            host.toast(host.tr("studio_copied"), "success");
        });
        Button copyKill = new Button(host.tr("studio_copy_kill"));
        copyKill.getStyleClass().add("secondary-button");
        copyKill.setTooltip(new Tooltip(host.tr("studio_kill_hint")));
        copyKill.setOnAction(e -> {
            host.copy(StudioCommand.kill(sceneTag));
            host.toast(host.tr("studio_copied_kill"), "success");
        });

        commandArea.setEditable(false);
        commandArea.setWrapText(true);
        commandArea.setPrefRowCount(3);
        commandArea.getStyleClass().addAll("console-area");
        commandInfo.getStyleClass().add("toggle-sub");

        for (javafx.scene.control.Control c : List.of(targetBox, copyKill, copy)) c.setMinWidth(Region.USE_PREF_SIZE);
        originLabel.setMinWidth(Region.USE_PREF_SIZE);
        commandInfo.setMinWidth(Region.USE_PREF_SIZE);
        originField.setMinWidth(150);
        HBox where = new HBox(8, targetBox, originLabel, originField);
        where.setAlignment(Pos.CENTER_LEFT);
        HBox actions = new HBox(10, commandInfo, copyKill, copy);
        actions.setAlignment(Pos.CENTER_LEFT);
        FlowPane controls = new FlowPane(16, 8, where, actions);
        controls.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(8, controls, commandArea);
        // the command fills whatever height the panel is dragged to
        VBox.setVgrow(commandArea, Priority.ALWAYS);
        commandArea.setMinHeight(48);
        commandArea.setMaxHeight(Double.MAX_VALUE);
        controls.prefWrapLengthProperty().bind(box.widthProperty().subtract(30));
        box.getStyleClass().add("panel");
        box.setPadding(new Insets(14));
        return box;
    }

    private void updateCommand() {
        if (items.isEmpty()) {
            commandArea.setText("");
            commandArea.setPromptText(host.tr("studio_empty"));
            commandInfo.setText("");
            return;
        }
        String cmd = StudioCommand.summon(items, targetBox.getValue(), originField.getText(), sceneTag);
        commandArea.setText(cmd);
        boolean tooLong = cmd.length() > StudioCommand.COMMAND_BLOCK_LIMIT;
        commandInfo.setText(host.tr("studio_objects") + " " + items.size() + " · " + host.tr("studio_chars") + " "
                + cmd.length() + (tooLong ? " / " + StudioCommand.COMMAND_BLOCK_LIMIT + " — " + host.tr("studio_too_long") : ""));
        commandInfo.getStyleClass().remove("status-error");
        if (tooLong) commandInfo.getStyleClass().add("status-error");
    }

    // ------------------------------------------------------------------ persistence

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private record SavedScene(String tag, String origin, String target, List<StudioCommand.Item> items) {}

    private Path sceneFile() {
        return host.studioDir().resolve("scene.json");
    }

    private void loadScene() {
        Path file = sceneFile();
        if (Files.isRegularFile(file) && !readScene(file)) host.toast(host.tr("studio_load_failed"), "warning");
    }

    private SavedScene currentScene() {
        return new SavedScene(sceneTag, originField.getText(),
                targetBox.getValue() == null ? null : targetBox.getValue().name(), new ArrayList<>(items));
    }

    /** Adds a saved scene's objects and takes its tag, origin and version; false when unreadable. */
    private boolean readScene(Path file) {
        try {
            SavedScene saved = GSON.fromJson(Files.readString(file), new TypeToken<SavedScene>() { }.getType());
            if (saved == null || saved.items() == null) return false;
            for (StudioCommand.Item item : saved.items()) {
                if (item == null || item.id == null || item.kind == null) continue;
                if (item.properties == null) item.properties = new java.util.LinkedHashMap<>();
                if (item.itemDisplay == null) item.itemDisplay = "none";
                items.add(item);
            }
            if (saved.tag() != null && saved.tag().matches("bls_[0-9a-f]{4,8}")) sceneTag = saved.tag();
            if (saved.origin() != null) originField.setText(saved.origin());
            if (saved.target() != null) {
                try {
                    targetBox.setValue(StudioCommand.Target.valueOf(saved.target()));
                } catch (IllegalArgumentException ignored) {
                    // an unknown target keeps the default
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void saveScene() {
        try {
            Files.createDirectories(host.studioDir());
            SavedScene saved = currentScene();
            Path file = sceneFile();
            Path tmp = file.resolveSibling("scene.json.tmp");
            Files.writeString(tmp, GSON.toJson(saved));
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            // the scene stays on screen; the next change tries again
        }
    }

    private static String newTag() {
        return "bls_" + Integer.toHexString(0x1000 + new java.util.Random().nextInt(0xefff));
    }

    // ------------------------------------------------------------------ for checks

}
