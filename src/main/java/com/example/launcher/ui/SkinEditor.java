package com.example.launcher.ui;

import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.PerspectiveCamera;
import javafx.scene.PointLight;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.PickResult;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.stage.FileChooser;
import org.kordamp.ikonli.javafx.FontIcon;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

/**
 * Skin and cape painter: the texture flat on the right, the character in 3D on the left, and the
 * brush works on both - a click on the model paints the texel under the cursor.
 *
 * <p>The model is built from the same cuboids, at the same offsets and with the same UV layout as
 * vanilla's {@code PlayerModel} / {@code ModelPart.Cube}, so what is painted here is exactly what
 * the game shows. Old 64x32 skins are converted to 64x64 on load, the way the game does it, so
 * there is one layout to edit.
 *
 * <p>Two layers: the base and the outer one (hat, jacket, sleeves, trousers). Only one is under the
 * brush at a time - with the outer layer selected the outer boxes catch the clicks, otherwise they
 * are transparent to the mouse and the click reaches the body beneath.
 */
public final class SkinEditor implements AutoCloseable {

    /** What the editor needs from the launcher. */
    public interface Host {
        String tr(String key);
        void toast(String message, String type);
        /** Stores the painted texture as the current profile's skin or cape. */
        void saveToProfile(byte[] png, boolean cape);
        /** Loads the current profile's texture, if it has one, and hands it over on the FX thread. */
        void loadProfileTexture(boolean cape, Consumer<Image> onLoaded);
        /** "Инвертировать мышь" in Settings: dragging turns the model the other way. */
        default boolean invertMouse() { return false; }
    }

    private enum Tool { PENCIL, ERASER, FILL, PICKER, SELECT, BLUR, LIGHTEN, DARKEN, NOISE, REPLACE }

    /** Pixels picked with the selection tool, for "lift to the outer layer". */
    private final boolean[] selected = new boolean[TEX_W * 64];
    /** Brush size in texels for the pencil, eraser and selection brush. */
    private int brush = 1;
    private int selStartX = -1;
    private int selStartY = -1;
    private int selEndX = -1;
    private int selEndY = -1;
    private Label selectionInfo;
    private VBox selectionActions;

    /** One rectangle of the texture that is a face of some box: the only places paint can go. */
    private record Face(int x, int y, int w, int h, boolean outer) {
        boolean contains(int px, int py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    /** One cuboid of the model: size, texture origin, the part's pivot and its inflation. */
    private record Box(float x, float y, float z, int w, int h, int d, int u, int v,
                       float px, float py, float pz, float grow, boolean outer) {}

    private static final int TEX_W = 64;
    /** The texture is drawn on the model at this many texture pixels per texel (see class note). */
    private static final int UPSCALE = 8;
    private static final int UNDO_LIMIT = 80;

    private final boolean cape;
    private final int texH;
    private final Host host;
    private int[] pixels;
    private boolean slim;
    private boolean outerLayer;
    private Tool tool = Tool.PENCIL;
    private Color color = Color.web("#2fb5a6");
    private final Deque<int[]> undo = new ArrayDeque<>();
    private final Deque<int[]> redo = new ArrayDeque<>();
    private boolean dirty;

    private final List<Face> faces = new ArrayList<>();
    private final WritableImage modelTexture;
    private final PhongMaterial material = new PhongMaterial();
    private final Group baseGroup = new Group();
    private final Group outerGroup = new Group();
    private final Rotate yaw = new Rotate(25, Rotate.Y_AXIS);
    private final Rotate pitch = new Rotate(-12, Rotate.X_AXIS);
    private final PerspectiveCamera camera = new PerspectiveCamera(true);

    private final Canvas canvas;
    private int cell;
    private ColorPanel colorPanel;
    private final HBox root;

    private double dragX;
    private double dragY;
    private boolean rotating;
    private int lastX = -1;
    private int lastY = -1;

    public SkinEditor(boolean cape, Host host) {
        this.cape = cape;
        this.host = host;
        this.texH = cape ? 32 : 64;
        this.pixels = cape ? defaultCape() : defaultSkin();
        this.cell = 8;
        this.modelTexture = new WritableImage(TEX_W * UPSCALE, texH * UPSCALE);
        this.canvas = new Canvas(TEX_W * cell, texH * cell);

        rebuildFaces();
        material.setDiffuseMap(modelTexture);
        material.setSpecularColor(Color.BLACK);
        rebuildModel();
        refreshAll();

        Node tools = buildToolbar();
        Node viewport = buildViewport();
        Node flat = buildFlatEditor();
        // resizable columns: drag the gap between two panels, like a window's edge
        javafx.scene.control.SplitPane split = new javafx.scene.control.SplitPane(tools, viewport, flat);
        split.getStyleClass().add("panel-split");
        split.setDividerPositions(0.22, 0.56);
        root = new HBox(split);
        HBox.setHgrow(split, Priority.ALWAYS);
        // The texture takes whatever the window leaves after the tools and a usable 3D view:
        // the launcher can be as narrow as 1000 px, and a fixed 8 px per texel did not fit there.
        // Min width 0 is what lets this work: otherwise the row is never narrower than its
        // children's minimums, the canvas never learns there is less room, and it never shrinks.
        root.setMinWidth(0);
        flat.layoutBoundsProperty().addListener((obs, o, n) -> fitCanvas(n.getWidth(), n.getHeight()));
        root.setMinHeight(0);
        root.setFocusTraversable(true);
        root.setOnKeyPressed(e -> {
            if (e.isControlDown() && e.getCode() == KeyCode.Z) undo();
            else if (e.isControlDown() && e.getCode() == KeyCode.Y) redo();
            else if (e.getCode() == KeyCode.B) selectTool(Tool.PENCIL);
            else if (e.getCode() == KeyCode.E) selectTool(Tool.ERASER);
            else if (e.getCode() == KeyCode.G) selectTool(Tool.FILL);
            else if (e.getCode() == KeyCode.I) selectTool(Tool.PICKER);
            else if (e.getCode() == KeyCode.U) selectTool(Tool.BLUR);
            else if (e.getCode() == KeyCode.S && !cape) selectTool(Tool.SELECT);
            else if (e.getCode() == KeyCode.ESCAPE) clearSelection();
        });

        host.loadProfileTexture(cape, image -> {
            if (!dirty && image != null) load(image, false);
        });
    }

    public Node node() {
        return root;
    }

    @Override
    public void close() {
        if (walk != null) walk.stop();   // the walking preview is the one thing that runs by itself
        baseGroup.getChildren().clear();
        outerGroup.getChildren().clear();
    }

    // ------------------------------------------------------------------ layout

    private ToggleGroup toolGroup;

    private Node buildToolbar() {
        toolGroup = new ToggleGroup();
        VBox bar = new VBox(8);
        bar.getStyleClass().add("panel");
        bar.setPadding(new Insets(14));
        bar.setMinWidth(236);
        bar.setPrefWidth(236);

        Label toolsLabel = sectionLabel("skin_tools");
        FlowPane toolButtons = new FlowPane(6, 6);
        toolButtons.getChildren().addAll(
                toolButton(Tool.PENCIL, "fth-edit-2", "skin_tool_pencil"),
                toolButton(Tool.ERASER, "fth-delete", "skin_tool_eraser"),
                toolButton(Tool.FILL, "fth-droplet", "skin_tool_fill"),
                toolButton(Tool.BLUR, "fth-wind", "skin_tool_blur"),
                toolButton(Tool.LIGHTEN, "fth-sun", "skin_tool_lighten"),
                toolButton(Tool.DARKEN, "fth-moon", "skin_tool_darken"),
                toolButton(Tool.NOISE, "fth-grid", "skin_tool_noise"),
                toolButton(Tool.REPLACE, "fth-refresh-cw", "skin_tool_replace"),
                toolButton(Tool.PICKER, "fth-crosshair", "skin_tool_picker"));
        if (!cape) toolButtons.getChildren().add(toolButton(Tool.SELECT, "fth-crop", "skin_tool_select"));

        // brush size: 1-4 texels, for pencil, eraser and the selection brush
        HBox brushRow = new HBox(2);
        brushRow.getStyleClass().add("lang-toggle");
        ToggleGroup brushGroup = new ToggleGroup();
        for (int size = 1; size <= 4; size++) {
            ToggleButton b = new ToggleButton(size + "");
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

        selectionInfo = new Label();
        selectionInfo.getStyleClass().add("toggle-sub");
        selectionInfo.setWrapText(true);
        Button lift = wideButton("fth-layers", "skin_lift_outer", this::liftToOuter);
        Button drop = wideButton("fth-minus-square", "skin_drop_outer", this::dropFromOuter);
        Button clearSel = wideButton("fth-x-square", "skin_clear_selection", this::clearSelection);
        selectionActions = new VBox(6, selectionInfo, lift, drop, clearSel);
        selectionActions.setVisible(false);
        selectionActions.setManaged(false);

        colorPanel = new ColorPanel(color, c -> color = c, host.tr("skin_recent_colors"));

        Button undoBtn = iconButton("fth-corner-up-left", "skin_undo", this::undo);
        Button redoBtn = iconButton("fth-corner-up-right", "skin_redo", this::redo);
        HBox history = new HBox(6, undoBtn, redoBtn);

        bar.getChildren().addAll(toolsLabel, toolButtons, sectionLabel("skin_brush"), brushRow, history,
                selectionActions, sectionLabel("skin_color"), colorPanel.node());

        if (!cape) {
            ToggleGroup layerGroup = new ToggleGroup();
            ToggleButton base = new ToggleButton(host.tr("skin_layer_base"));
            ToggleButton outer = new ToggleButton(host.tr("skin_layer_outer"));
            for (ToggleButton b : List.of(base, outer)) {
                b.setToggleGroup(layerGroup);
                b.getStyleClass().add("share-tab");
                b.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(b, Priority.ALWAYS);
            }
            base.setSelected(true);
            layerGroup.selectedToggleProperty().addListener((obs, o, n) -> {
                if (n == null) {
                    (o == null ? base : (ToggleButton) o).setSelected(true);
                    return;
                }
                outerLayer = n == outer;
                applyLayerPicking();
                refreshAll();   // the outer layer turns into a grid, or back
            });

            ToggleButton showOuter = new ToggleButton(host.tr("skin_show_outer"));
            showOuter.getStyleClass().add("secondary-button");
            showOuter.setSelected(true);
            showOuter.setMaxWidth(Double.MAX_VALUE);
            showOuter.selectedProperty().addListener((obs, o, n) -> outerGroup.setVisible(n));

            ToggleButton slimArms = new ToggleButton(host.tr("skin_slim"));
            slimArms.getStyleClass().add("secondary-button");
            slimArms.setMaxWidth(Double.MAX_VALUE);
            slimArms.setSelected(slim);
            slimArms.selectedProperty().addListener((obs, o, n) -> {
                slim = n;
                rebuildFaces();
                rebuildModel();
                redrawCanvas();
            });
            this.slimToggle = slimArms;

            ToggleButton mirrorToggle = new ToggleButton(host.tr("skin_mirror"));
            mirrorToggle.getStyleClass().addAll("secondary-button", "skin-tool");
            mirrorToggle.setGraphic(new FontIcon("fth-columns"));
            mirrorToggle.setGraphicTextGap(8);
            mirrorToggle.setMaxWidth(Double.MAX_VALUE);
            mirrorToggle.setAlignment(Pos.CENTER_LEFT);
            mirrorToggle.setTooltip(new Tooltip(host.tr("skin_mirror_tip")));
            mirrorToggle.selectedProperty().addListener((obs, o, n) -> mirror = n);

            // pose: how the skin looks moving, the limbs turned about the shoulders and hips
            HBox poseRow = new HBox(2);
            poseRow.getStyleClass().add("lang-toggle");
            ToggleGroup poseGroup = new ToggleGroup();
            for (String[] pose : new String[][]{{"stand", "skin_pose_stand"}, {"walk", "skin_pose_walk"}, {"wave", "skin_pose_wave"}}) {
                ToggleButton b = new ToggleButton(host.tr(pose[1]));
                b.getStyleClass().add("lang-option");
                b.setToggleGroup(poseGroup);
                b.setSelected(pose[0].equals("stand"));
                b.setOnAction(e -> {
                    b.setSelected(true);
                    setPose(pose[0]);
                });
                poseRow.getChildren().add(b);
            }

            bar.getChildren().addAll(sectionLabel("skin_layer"), new HBox(4, base, outer), showOuter, slimArms,
                    mirrorToggle, sectionLabel("skin_pose"), poseRow);
        }

        Region grow = new Region();
        VBox.setVgrow(grow, Priority.ALWAYS);

        Button mine = wideButton("fth-user", cape ? "cape_take_mine" : "skin_take_mine", this::takeMine);
        javafx.scene.control.TextField nick = new javafx.scene.control.TextField();
        nick.setPromptText(host.tr("skin_by_nick_prompt"));
        nick.getStyleClass().add("text-input");
        HBox.setHgrow(nick, Priority.ALWAYS);
        Button byNick = iconButton("fth-download-cloud", "skin_by_nick", () -> loadByNick(nick.getText()));
        nick.setOnAction(e -> loadByNick(nick.getText()));
        HBox nickRow = new HBox(6, nick, byNick);
        nickRow.setAlignment(Pos.CENTER_LEFT);
        nickRow.setVisible(!cape);
        nickRow.setManaged(!cape);
        Button open = wideButton("fth-folder", "skin_open_file", this::openFile);
        Button fresh = wideButton("fth-file", "skin_new", this::newTexture);
        Button export = wideButton("fth-download", "skin_export", this::exportFile);
        Button picture = wideButton("fth-image", "skin_picture", this::savePicture);
        Button save = new Button(host.tr(cape ? "cape_save_profile" : "skin_save_profile"));
        save.getStyleClass().addAll("play-btn", "modal-create-btn");
        save.setMaxWidth(Double.MAX_VALUE);
        save.setOnAction(e -> saveToProfile());

        bar.getChildren().addAll(grow, mine, nickRow, open, fresh, export, picture, save);
        selectTool(Tool.PENCIL);
        // The column scrolls by itself when the window is short, so the editor as a whole always
        // fits the launcher and the page around it never has to scroll.
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(bar);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("screen-scroll");
        scroll.setMinWidth(250);
        scroll.setPrefWidth(250);
        scroll.setMinHeight(0);
        return scroll;
    }

    private ToggleButton slimToggle;

    private final Group world = new Group();
    /** The selected pixels as little cubes while they are being pulled off the model. */
    private final Group pullPreview = new Group();

    private SubScene modelView;

    private Node buildViewport() {
        pullPreview.setMouseTransparent(true);
        world.getChildren().setAll(baseGroup, outerGroup, pullPreview);
        world.getTransforms().addAll(yaw, pitch);
        // vanilla model space has the head at y -8..0 and the feet at y 24, the cape 0..16: both
        // are centred on y 8. The camera looks along +z at the z1 faces - the front of the
        // character, and the outer side of the cape (vanilla turns the cape round when worn).
        world.setTranslateY(-8);

        AmbientLight ambient = new AmbientLight(Color.gray(0.62));
        PointLight key = new PointLight(Color.gray(0.5));
        key.setTranslateZ(-120);
        key.setTranslateY(-40);
        key.setTranslateX(-30);
        Group scene3d = new Group(world, ambient, key);

        camera.setFieldOfView(32);
        camera.setNearClip(1);
        camera.setFarClip(1000);
        camera.setTranslateZ(cape ? -60 : -78);

        SubScene sub = new SubScene(scene3d, 360, 460, true, SceneAntialiasing.BALANCED);
        modelView = sub;
        sub.setCamera(camera);
        sub.setFill(Color.TRANSPARENT);

        StackPane holder = new StackPane(sub);
        holder.getStyleClass().add("panel");
        holder.setMinSize(280, 380);
        sub.widthProperty().bind(holder.widthProperty().subtract(2));
        sub.heightProperty().bind(holder.heightProperty().subtract(2));

        Label hint = new Label(host.tr("skin_3d_hint"));
        hint.getStyleClass().add("toggle-sub");
        StackPane.setAlignment(hint, Pos.BOTTOM_CENTER);
        StackPane.setMargin(hint, new Insets(0, 0, 10, 0));
        hint.setMouseTransparent(true);
        hint.setWrapText(true);
        hint.setMaxWidth(320);
        hint.setAlignment(Pos.CENTER);
        hint.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        holder.getChildren().add(hint);

        sub.setOnMousePressed(e -> {
            root.requestFocus();
            dragX = e.getSceneX();
            dragY = e.getSceneY();
            Point2D texel = pickTexel(e.getPickResult());
            rotating = e.getButton() != MouseButton.PRIMARY || texel == null;
            if (rotating) return;
            int tx = (int) texel.getX(), ty = (int) texel.getY();
            // pressing on a pixel that is already selected grabs the selection to pull it
            if (tool == Tool.SELECT && selected[ty * TEX_W + tx] && startPull(tx, ty, e.getScreenX(), e.getScreenY())) return;
            if (tool == Tool.SELECT && e.isAltDown()) {
                brushed(tx, ty, (bx, by) -> setSelected(bx, by, false));
                selectionChanged();
                lastX = tx;
                lastY = ty;
                return;
            }
            beginStroke();
            applyTool(tx, ty, false);
        });
        sub.setOnMouseDragged(e -> {
            if (rotating) {
                double dir = host.invertMouse() ? -1 : 1;
                yaw.setAngle(yaw.getAngle() + dir * (e.getSceneX() - dragX) * 0.6);
                pitch.setAngle(Math.max(-80, Math.min(80, pitch.getAngle() - dir * (e.getSceneY() - dragY) * 0.4)));
                dragX = e.getSceneX();
                dragY = e.getSceneY();
                return;
            }
            if (pulling) {
                dragPull(e.getScreenX(), e.getScreenY());
                return;
            }
            Point2D texel = pickTexel(e.getPickResult());
            if (texel != null && (tool == Tool.PENCIL || tool == Tool.ERASER || tool == Tool.SELECT || tool == Tool.BLUR
                    || tool == Tool.LIGHTEN || tool == Tool.DARKEN || tool == Tool.NOISE)) {
                int x = (int) texel.getX(), y = (int) texel.getY();
                if (tool == Tool.SELECT && e.isAltDown()) {
                    brushed(x, y, (bx, by) -> setSelected(bx, by, false));
                    selectionChanged();
                } else if (lastX >= 0 && faceAt(lastX, lastY) != null && faceAt(lastX, lastY) == faceAt(x, y)) {
                    // a quick stroke skips texels between two samples: join them, as on the flat texture
                    line(lastX, lastY, x, y);
                } else {
                    applyTool(x, y, true);
                }
                lastX = x;
                lastY = y;
            }
        });
        sub.setOnMouseReleased(e -> {
            if (pulling) endPull();
            endStroke();
        });
        sub.addEventHandler(ScrollEvent.SCROLL, e -> {
            double z = camera.getTranslateZ() + e.getDeltaY() * 0.08;
            camera.setTranslateZ(Math.max(-180, Math.min(-30, z)));
            e.consume();   // zooming must not scroll the page around the editor
        });
        return holder;
    }

    private Node buildFlatEditor() {
        canvas.setOnMousePressed(e -> {
            root.requestFocus();
            int x = (int) (e.getX() / cell);
            int y = (int) (e.getY() / cell);
            if (e.getButton() == MouseButton.SECONDARY) {
                pickColor(x, y);
                return;
            }
            if (tool == Tool.SELECT) {
                selStartX = selEndX = x;
                selStartY = selEndY = y;
                redrawCanvas();
                return;
            }
            beginStroke();
            applyTool(x, y, false);
        });
        canvas.setOnMouseDragged(e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            if (tool == Tool.SELECT && selStartX >= 0) {
                selEndX = clampX((int) Math.floor(e.getX() / cell));
                selEndY = clampY((int) Math.floor(e.getY() / cell));
                redrawCanvas();
                return;
            }
            if (tool != Tool.PENCIL && tool != Tool.ERASER && tool != Tool.BLUR
                    && tool != Tool.LIGHTEN && tool != Tool.DARKEN && tool != Tool.NOISE) return;
            int x = (int) Math.floor(e.getX() / cell);
            int y = (int) Math.floor(e.getY() / cell);
            line(lastX, lastY, x, y);
        });
        canvas.setOnMouseReleased(e -> {
            if (tool == Tool.SELECT && selStartX >= 0) {
                // Alt takes pixels out of the selection instead of adding them
                boolean remove = e.isAltDown();
                for (int y = Math.min(selStartY, selEndY); y <= Math.max(selStartY, selEndY); y++)
                    for (int x = Math.min(selStartX, selEndX); x <= Math.max(selStartX, selEndX); x++)
                        if (faceAt(x, y) != null) setSelected(x, y, !remove);
                selStartX = selStartY = selEndX = selEndY = -1;
                selectionChanged();
                redrawCanvas();
                return;
            }
            endStroke();
        });

        canvasFrame = new Pane(canvas);
        flatHint = new Label(host.tr("skin_flat_hint"));
        flatHint.getStyleClass().add("toggle-sub");
        flatHint.setWrapText(true);

        flatBox = new VBox(10, canvasFrame, flatHint);
        flatBox.getStyleClass().add("panel");
        flatBox.setPadding(new Insets(14));
        flatBox.setAlignment(Pos.TOP_CENTER);
        sizeCanvas();
        return flatBox;
    }

    private Pane canvasFrame;
    private Label flatHint;
    private VBox flatBox;

    /** The texture fills its panel: as many whole pixels per texel as the panel has room for. */
    private void fitCanvas(double panelWidth, double panelHeight) {
        double rootWidth = panelWidth + 250 + 280 + 14 * 2;
        double rootHeight = panelHeight;
        // tools 236 + a 3D view of at least 280 + gaps and the panel's own padding
        double free = rootWidth - 250 - 280 - 14 * 2 - 30;
        int byWidth = (int) Math.floor(free / TEX_W);
        // panel padding + the hint under the texture
        int byHeight = rootHeight > 0 ? (int) Math.floor((rootHeight - 90) / texH) : byWidth;
        int next = Math.max(4, Math.min(cape ? 12 : 10, Math.min(byWidth, byHeight)));
        if (next == cell) return;
        cell = next;
        sizeCanvas();
        redrawCanvas();
    }

    private void sizeCanvas() {
        canvas.setWidth(TEX_W * cell);
        canvas.setHeight(texH * cell);
        canvasFrame.setMinSize(canvas.getWidth(), canvas.getHeight());
        canvasFrame.setMaxSize(canvas.getWidth(), canvas.getHeight());
        flatHint.setMaxWidth(canvas.getWidth());
        // the panel may be narrower than the texture while it is being resized; it refits at once
        canvasFrame.setMinSize(0, 0);
        flatBox.setMinWidth(180);
    }

    private Label sectionLabel(String key) {
        Label label = new Label(host.tr(key));
        label.getStyleClass().add("toggle-label");
        return label;
    }

    private ToggleButton toolButton(Tool t, String icon, String key) {
        ToggleButton b = new ToggleButton();
        b.setGraphic(new FontIcon(icon));
        b.getStyleClass().addAll("secondary-button", "skin-tool");
        b.setToggleGroup(toolGroup);
        b.setUserData(t);
        b.setTooltip(new Tooltip(host.tr(key)));
        b.setOnAction(e -> selectTool(t));
        b.selectedProperty().addListener((obs, was, now) -> {
            if (!now && tool == t) b.setSelected(true);
        });
        return b;
    }

    private Button iconButton(String icon, String key, Runnable action) {
        Button b = new Button();
        b.setGraphic(new FontIcon(icon));
        b.getStyleClass().add("secondary-button");
        b.setTooltip(new Tooltip(host.tr(key)));
        b.setOnAction(e -> action.run());
        return b;
    }

    private Button wideButton(String icon, String key, Runnable action) {
        Button b = new Button(host.tr(key));
        b.setGraphic(new FontIcon(icon));
        b.setGraphicTextGap(8);
        b.getStyleClass().add("secondary-button");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        b.setOnAction(e -> action.run());
        return b;
    }

    private void selectTool(Tool t) {
        tool = t;
        if (toolGroup == null) return;
        for (var toggle : toolGroup.getToggles()) {
            toggle.setSelected(toggle.getUserData() == t);
        }
    }

    // ------------------------------------------------------------------ painting

    private void beginStroke() {
        pushUndo();
        java.util.Arrays.fill(strokeTouched, false);
        if (colorPanel != null && (tool == Tool.PENCIL || tool == Tool.FILL)) colorPanel.remember(color);
        lastX = -1;
        lastY = -1;
    }

    private void endStroke() {
        rotating = false;
        lastX = -1;
        lastY = -1;
    }

    private void applyTool(int x, int y, boolean continuing) {
        if (x < 0 || y < 0 || x >= TEX_W || y >= texH) return;
        switch (tool) {
            case PENCIL -> brushed(x, y, (bx, by) -> paint(bx, by, toArgb(color)));
            case ERASER -> brushed(x, y, (bx, by) -> paint(bx, by, 0));
            case BLUR -> {
                int[] before = pixels.clone();
                brushed(x, y, (bx, by) -> paint(bx, by, blurred(before, bx, by)));
            }
            case REPLACE -> {
                if (!continuing) replaceColour(x, y, toArgb(color));
            }
            case LIGHTEN -> brushed(x, y, (bx, by) -> shadeOnce(bx, by, 0.09));
            case DARKEN -> brushed(x, y, (bx, by) -> shadeOnce(bx, by, -0.09));
            case NOISE -> brushed(x, y, (bx, by) -> shadeOnce(bx, by, (random.nextDouble() * 2 - 1) * 0.07));
            case SELECT -> {
                brushed(x, y, (bx, by) -> setSelected(bx, by, true));
                selectionChanged();
            }
            case FILL -> {
                if (!continuing) {
                    fill(x, y, toArgb(color));
                    int m = mirror ? mirrorOf[y * TEX_W + x] : -1;
                    if (m >= 0) fill(m % TEX_W, m / TEX_W, toArgb(color));
                }
            }
            case PICKER -> {
                if (!continuing) {
                    pickColor(x, y);
                    selectTool(Tool.PENCIL);
                }
            }
        }
        lastX = x;
        lastY = y;
    }

    /**
     * The texel softened towards its neighbours on the same face: the eight around it and itself
     * (counted twice), only those with colour, so an edge never bleeds into empty texture and a
     * transparent texel stays transparent. Each pass of the brush softens a little more.
     */
    private int blurred(int[] src, int x, int y) {
        int centre = src[y * TEX_W + x];
        if ((centre >>> 24) == 0) return centre;
        Face face = faceAt(x, y);
        double a = 0, r = 0, g = 0, b = 0, weight = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx, ny = y + dy;
                if (face == null || !face.contains(nx, ny)) continue;
                int c = src[ny * TEX_W + nx];
                if ((c >>> 24) == 0) continue;
                double w = dx == 0 && dy == 0 ? 2 : 1;
                a += (c >>> 24) * w;
                r += ((c >> 16) & 255) * w;
                g += ((c >> 8) & 255) * w;
                b += (c & 255) * w;
                weight += w;
            }
        }
        if (weight == 0) return centre;
        return ((int) Math.round(a / weight) << 24) | ((int) Math.round(r / weight) << 16)
                | ((int) Math.round(g / weight) << 8) | (int) Math.round(b / weight);
    }

    /**
     * Every texel of the clicked colour, anywhere on the skin, becomes the current colour: a
     * jacket or hair recoloured in one click. Only texels that are part of the model change.
     */
    private void replaceColour(int x, int y, int argb) {
        if (faceAt(x, y) == null) return;
        int from = pixels[y * TEX_W + x];
        if (from == argb) return;
        int n = 0;
        for (Face f : faces) {
            for (int ty = f.y(); ty < f.y() + f.h() && ty < texH; ty++) {
                for (int tx = f.x(); tx < f.x() + f.w() && tx < TEX_W; tx++) {
                    if (pixels[ty * TEX_W + tx] != from) continue;
                    pixels[ty * TEX_W + tx] = argb;
                    updateTexel(tx, ty);
                    n++;
                }
            }
        }
        dirty = true;
        redrawCanvas();
        host.toast(host.tr("skin_replaced") + " " + n, "info");
    }

    /** Texels the shading brushes already changed in this stroke: one stroke changes each once. */
    private final boolean[] strokeTouched = new boolean[TEX_W * 64];
    private final java.util.Random random = new java.util.Random();

    /**
     * Lighter (positive) or darker (negative) by that share of full brightness, hue kept - the way
     * skin artists shade a fold or a lit edge. A stroke changes each texel once however long the
     * mouse lingers on it; another stroke over it goes further. Transparent texels stay as they are.
     */
    private void shadeOnce(int x, int y, double amount) {
        int i = y * TEX_W + x;
        if (strokeTouched[i]) return;
        strokeTouched[i] = true;
        int argb = pixels[i];
        if ((argb >>> 24) == 0) return;
        Color c = fromArgb(argb);
        double b = Math.max(0, Math.min(1, c.getBrightness() + amount));
        // near white, lightening also takes a little colour out, as paint does
        double sat = amount > 0 && c.getBrightness() > 0.92 ? Math.max(0, c.getSaturation() - amount * 0.6) : c.getSaturation();
        paint(x, y, toArgb(Color.hsb(c.getHue(), sat, b, c.getOpacity())));
    }

    /**
     * Runs the action on a brush-sized square around the texel, kept inside the texel's face -
     * and, with the mirror on, on the texels at the mirrored spot of the model as well.
     */
    private void brushed(int x, int y, java.util.function.BiConsumer<Integer, Integer> action) {
        Face face = faceAt(x, y);
        if (face == null) return;
        int from = -(brush - 1) / 2;
        for (int dy = from; dy < from + brush; dy++) {
            for (int dx = from; dx < from + brush; dx++) {
                int bx = x + dx, by = y + dy;
                if (!face.contains(bx, by)) continue;
                action.accept(bx, by);
                int m = mirror ? mirrorOf[by * TEX_W + bx] : -1;
                if (m >= 0 && m != by * TEX_W + bx) action.accept(m % TEX_W, m / TEX_W);
            }
        }
    }

    // ------------------------------------------------------------------ mirror

    private boolean mirror;
    /** For each texel, the texel at the mirrored spot (x -> -x) of the same layer, or -1. */
    private final int[] mirrorOf = new int[TEX_W * 64];

    /**
     * Pairs every texel with its mirror image: each texel's place on the model is flipped left to
     * right and looked up among all texels of the layer - so the right arm pairs with the left
     * arm, the left half of the face with the right half, whatever the texture layout.
     */
    private void buildMirror() {
        java.util.Arrays.fill(mirrorOf, -1);
        java.util.Map<String, Integer> byPlace = new java.util.HashMap<>();
        List<Box> list = boxes();
        double[][] place = new double[TEX_W * texH][];
        for (int y = 0; y < texH; y++) {
            for (int x = 0; x < TEX_W; x++) {
                int i = boxIndexAt(x, y);
                if (i < 0 || i >= list.size()) continue;
                double[] at = texelPoint(list.get(i), x, y);
                if (at == null) continue;
                place[y * TEX_W + x] = at;
                byPlace.put(placeKey(at[0], at[1], at[2], at[3], at[4], at[5], list.get(i).outer()), y * TEX_W + x);
            }
        }
        for (int t = 0; t < place.length; t++) {
            double[] at = place[t];
            if (at == null) continue;
            Integer m = byPlace.get(placeKey(-at[0], at[1], at[2], -at[3], at[4], at[5], list.get(boxIndexAt(t % TEX_W, t / TEX_W)).outer()));
            if (m != null) mirrorOf[t] = m;
        }
    }

    private static String placeKey(double x, double y, double z, double nx, double ny, double nz, boolean outer) {
        return Math.round(x * 8) + "," + Math.round(y * 8) + "," + Math.round(z * 8) + ","
                + Math.round(nx) + "," + Math.round(ny) + "," + Math.round(nz) + (outer ? "o" : "b");
    }

    // ------------------------------------------------------------------ pose

    /** The four limbs' turns about the X axis, at the shoulder and the hip; base and outer alike. */
    private final Rotate[] limbTurn = {new Rotate(0, Rotate.X_AXIS), new Rotate(0, Rotate.X_AXIS),
            new Rotate(0, Rotate.X_AXIS), new Rotate(0, Rotate.X_AXIS)};
    private javafx.animation.Timeline walk;

    private void setPose(String pose) {
        if (walk != null) walk.stop();
        walk = null;
        for (Rotate r : limbTurn) r.setAngle(0);
        if (pose.equals("wave")) {
            limbTurn[0].setAngle(-165);   // the right arm up over the head
        } else if (pose.equals("walk")) {
            javafx.beans.property.DoubleProperty phase = new javafx.beans.property.SimpleDoubleProperty();
            phase.addListener((obs, o, n) -> {
                double swing = Math.sin(n.doubleValue()) * 32;
                limbTurn[0].setAngle(swing);    // right arm
                limbTurn[1].setAngle(-swing);   // left arm
                limbTurn[2].setAngle(-swing);   // right leg
                limbTurn[3].setAngle(swing);    // left leg
            });
            walk = new javafx.animation.Timeline(
                    new javafx.animation.KeyFrame(javafx.util.Duration.ZERO, new javafx.animation.KeyValue(phase, 0)),
                    new javafx.animation.KeyFrame(javafx.util.Duration.millis(1100),
                            new javafx.animation.KeyValue(phase, Math.PI * 2, javafx.animation.Interpolator.LINEAR)));
            walk.setCycleCount(javafx.animation.Animation.INDEFINITE);
            walk.play();
        }
    }

    private int clampX(int x) {
        return Math.max(0, Math.min(TEX_W - 1, x));
    }

    private int clampY(int y) {
        return Math.max(0, Math.min(texH - 1, y));
    }

    // ------------------------------------------------------------------ selection

    private void setSelected(int x, int y, boolean on) {
        if (selected[y * TEX_W + x] == on) return;
        selected[y * TEX_W + x] = on;
        updateTexel(x, y);
    }

    private int selectedCount() {
        int n = 0;
        for (boolean b : selected) if (b) n++;
        return n;
    }

    private void selectionChanged() {
        if (selectionActions == null) return;
        int n = selectedCount();
        selectionActions.setVisible(n > 0);
        selectionActions.setManaged(n > 0);
        selectionInfo.setText(host.tr("skin_selected_count") + " " + n);
    }

    private void clearSelection() {
        java.util.Arrays.fill(selected, false);
        selectionChanged();
        refreshAll();
    }

    /**
     * The box a texel belongs to, by index into {@link #boxes()}: base boxes come first and the
     * outer box over the same body part sits six places later, so +6 is "the same face, outside".
     */
    private int boxIndexAt(int x, int y) {
        List<Box> list = boxes();
        for (int i = 0; i < list.size(); i++) {
            Box b = list.get(i);
            int u = b.u(), v = b.v(), w = b.w(), h = b.h(), d = b.d();
            boolean top = y >= v && y < v + d && x >= u + d && x < u + d + 2 * w;
            boolean sides = y >= v + d && y < v + d + h && x >= u && x < u + 2 * (w + d);
            if (top || sides) return i;
        }
        return -1;
    }

    /**
     * Copies the selected pixels of the base layer to the same spot of the outer layer - the
     * hat over the face, the jacket over the body, the sleeves over the arms - so they stand off
     * the model like 3D detail. The base keeps its pixels: under the outer layer there must be
     * something, or the game shows a hole.
     */
    private void liftToOuter() {
        pushUndo();
        List<Box> list = boxes();
        int moved = 0;
        for (int y = 0; y < texH; y++) {
            for (int x = 0; x < TEX_W; x++) {
                if (!selected[y * TEX_W + x]) continue;
                int i = boxIndexAt(x, y);
                if (i < 0) continue;
                // selected on the base: copy outwards; selected on the outer layer: copy the base
                // pixel underneath into that spot
                Box base = list.get(i % 6), outer = list.get(i % 6 + 6);
                int du = outer.u() - base.u(), dv = outer.v() - base.v();
                int sx = i < 6 ? x : x - du, sy = i < 6 ? y : y - dv;
                int tx = sx + du, ty = sy + dv;
                if (sx < 0 || sy < 0 || tx < 0 || ty < 0 || tx >= TEX_W || ty >= texH) continue;
                pixels[ty * TEX_W + tx] = pixels[sy * TEX_W + sx];
                moved++;
            }
        }
        java.util.Arrays.fill(selected, false);
        dirty = true;
        outerGroup.setVisible(true);
        selectionChanged();
        refreshAll();
        host.toast(host.tr("skin_lifted") + " " + moved, "success");
    }

    // ------------------------------------------------------------------ pulling pixels off the model

    private boolean pulling;
    private double pullStartX, pullStartY;
    /** Screen pixels per model unit along the grabbed pixel's outward normal. */
    private double pullDirX, pullDirY;
    private double pullAmount;
    private final List<javafx.scene.shape.Box> pullVoxels = new ArrayList<>();
    private final List<double[]> pullAnchors = new ArrayList<>();
    /** How far (in model units) the pixels have to come out, or go in, for the release to count. */
    private static final double PULL_SNAP = 0.35;

    /**
     * Grabs the selection where the pointer pressed on it. From then on the selected pixels come
     * out of the model as little cubes that follow the mouse along the surface's outward
     * direction; letting go far enough out moves them to the outer layer, pushing them in clears
     * the outer layer over them.
     */
    private boolean startPull(int x, int y, double screenX, double screenY) {
        int i = boxIndexAt(x, y);
        if (i < 0) return false;
        double[] at = texelPoint(boxes().get(i), x, y);
        if (at == null) return false;
        javafx.geometry.Point2D a = world.localToScreen(at[0], at[1], at[2]);
        javafx.geometry.Point2D b = world.localToScreen(at[0] + at[3] * 4, at[1] + at[4] * 4, at[2] + at[5] * 4);
        if (a == null || b == null) return false;
        pullDirX = (b.getX() - a.getX()) / 4;
        pullDirY = (b.getY() - a.getY()) / 4;
        // a surface seen edge-on gives no direction: then the drag goes up for "out"
        if (Math.hypot(pullDirX, pullDirY) < 2) {
            pullDirX = 0;
            pullDirY = -6;
        }
        pullStartX = screenX;
        pullStartY = screenY;
        pullAmount = 0;
        pulling = true;
        pullVoxels.clear();
        pullAnchors.clear();
        pullPreview.getChildren().clear();
        List<Box> list = boxes();
        java.util.Map<Integer, PhongMaterial> mats = new java.util.HashMap<>();
        for (int ty = 0; ty < texH; ty++) {
            for (int tx = 0; tx < TEX_W; tx++) {
                if (!selected[ty * TEX_W + tx]) continue;
                int bi = boxIndexAt(tx, ty);
                if (bi < 0) continue;
                double[] point = texelPoint(list.get(bi), tx, ty);
                if (point == null) continue;
                int argb = pixels[ty * TEX_W + tx];
                PhongMaterial m = mats.computeIfAbsent(argb, c -> {
                    PhongMaterial pm = new PhongMaterial(fromArgb(c).deriveColor(0, 1, 1, 1).interpolate(Color.web("#8D6BFA"), 0.15));
                    pm.setSpecularColor(Color.TRANSPARENT);
                    return pm;
                });
                javafx.scene.shape.Box voxel = new javafx.scene.shape.Box(1, 1, 1);
                voxel.setMaterial(m);
                voxel.setVisible(false);
                pullVoxels.add(voxel);
                pullAnchors.add(point);
            }
        }
        pullPreview.getChildren().setAll(pullVoxels);
        return true;
    }

    private void dragPull(double screenX, double screenY) {
        double dx = screenX - pullStartX, dy = screenY - pullStartY;
        double len2 = pullDirX * pullDirX + pullDirY * pullDirY;
        pullAmount = Math.max(-1.5, Math.min(2.5, (dx * pullDirX + dy * pullDirY) / len2));
        double depth = Math.abs(pullAmount);
        for (int i = 0; i < pullVoxels.size(); i++) {
            javafx.scene.shape.Box v = pullVoxels.get(i);
            double[] a = pullAnchors.get(i);
            v.setVisible(depth > 0.02);
            // a column from the surface to the pointer: out over the skin, or sunk into it
            double sx = Math.abs(a[3]) > 0.5 ? depth : 1, sy = Math.abs(a[4]) > 0.5 ? depth : 1, sz = Math.abs(a[5]) > 0.5 ? depth : 1;
            v.setWidth(sx);
            v.setHeight(sy);
            v.setDepth(sz);
            double half = pullAmount / 2;
            v.setTranslateX(a[0] + a[3] * half);
            v.setTranslateY(a[1] + a[4] * half);
            v.setTranslateZ(a[2] + a[5] * half);
            v.setOpacity(pullAmount >= 0 ? 1 : 0.5);
        }
    }

    private void endPull() {
        pulling = false;
        pullPreview.getChildren().clear();
        pullVoxels.clear();
        pullAnchors.clear();
        if (pullAmount >= PULL_SNAP) liftToOuter();
        else if (pullAmount <= -PULL_SNAP) dropFromOuter();
        pullAmount = 0;
    }

    /**
     * Where a texel sits on its box and which way that face looks, in the model's space:
     * {x, y, z, nx, ny, nz}. The inverse of {@link #cubeMesh}'s uv layout, face by face.
     */
    private double[] texelPoint(Box b, int x, int y) {
        double g = b.grow();
        double x1 = b.x() - g, y1 = b.y() - g, z1 = b.z() - g;
        double x2 = b.x() + b.w() + g, y2 = b.y() + b.h() + g, z2 = b.z() + b.d() + g;
        int u = b.u(), v = b.v(), w = b.w(), h = b.h(), d = b.d();
        int l = u, m = u + d, n = u + d + w, o = u + d + w + w, p = u + d + w + d, q = u + d + w + d + w;
        int r = v, s = v + d, t = v + d + h;
        // texel centre to model units: the inflated outer box stretches its texels a little
        double kx = (x2 - x1) / w, ky = (y2 - y1) / h, kz = (z2 - z1) / d;
        double cx = x + 0.5, cy = y + 0.5;
        double[] out;
        if (y >= r && y < s && x >= m && x < n) {                // top
            out = new double[]{x1 + (cx - m) * kx, y1, z2 - (cy - r) * kz, 0, -1, 0};
        } else if (y >= r && y < s && x >= n && x < o) {         // bottom
            out = new double[]{x1 + (cx - n) * kx, y2, z2 - (cy - r) * kz, 0, 1, 0};
        } else if (y >= s && y < t && x >= l && x < m) {         // x1 side
            out = new double[]{x1, y1 + (cy - s) * ky, z2 - (cx - l) * kz, -1, 0, 0};
        } else if (y >= s && y < t && x >= m && x < n) {         // front
            out = new double[]{x1 + (cx - m) * kx, y1 + (cy - s) * ky, z1, 0, 0, -1};
        } else if (y >= s && y < t && x >= n && x < p) {         // x2 side
            out = new double[]{x2, y1 + (cy - s) * ky, z1 + (cx - n) * kz, 1, 0, 0};
        } else if (y >= s && y < t && x >= p && x < q) {         // back
            out = new double[]{x2 - (cx - p) * kx, y1 + (cy - s) * ky, z2, 0, 0, 1};
        } else {
            return null;
        }
        out[0] += b.px();
        out[1] += b.py();
        out[2] += b.pz();
        return out;
    }

    /** Clears the outer layer over the selection (or the selected outer pixels themselves). */
    private void dropFromOuter() {
        pushUndo();
        List<Box> list = boxes();
        for (int y = 0; y < texH; y++) {
            for (int x = 0; x < TEX_W; x++) {
                if (!selected[y * TEX_W + x]) continue;
                int i = boxIndexAt(x, y);
                if (i < 0) continue;
                if (i >= 6) {
                    pixels[y * TEX_W + x] = 0;
                } else {
                    Box base = list.get(i), outer = list.get(i + 6);
                    int tx = x + outer.u() - base.u(), ty = y + outer.v() - base.v();
                    if (tx >= 0 && ty >= 0 && tx < TEX_W && ty < texH) pixels[ty * TEX_W + tx] = 0;
                }
            }
        }
        java.util.Arrays.fill(selected, false);
        dirty = true;
        selectionChanged();
        refreshAll();
    }

    /** Bresenham between two drag samples, so a quick stroke is a line and not a dotted trail. */
    private void line(int x0, int y0, int x1, int y1) {
        if (x0 < 0) {
            applyTool(x1, y1, true);
            return;
        }
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            applyTool(x0, y0, true);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    /** Paint only lands on a face of the layer being edited; everything else is unused texture. */
    private Face faceAt(int x, int y) {
        for (Face f : faces) {
            if (f.contains(x, y) && (cape || f.outer() == outerLayer)) return f;
        }
        return null;
    }

    private void paint(int x, int y, int argb) {
        if (faceAt(x, y) == null) return;
        if (pixels[y * TEX_W + x] == argb) return;
        pixels[y * TEX_W + x] = argb;
        dirty = true;
        updateTexel(x, y);
        drawCell(canvas.getGraphicsContext2D(), x, y);
    }

    /** Flood fill that stays inside the face it starts on - faces touch each other in the layout. */
    private void fill(int x, int y, int argb) {
        Face face = faceAt(x, y);
        if (face == null) return;
        int target = pixels[y * TEX_W + x];
        if (target == argb) return;
        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{x, y});
        while (!stack.isEmpty()) {
            int[] p = stack.pop();
            int px = p[0];
            int py = p[1];
            if (!face.contains(px, py) || pixels[py * TEX_W + px] != target) continue;
            pixels[py * TEX_W + px] = argb;
            updateTexel(px, py);
            stack.push(new int[]{px + 1, py});
            stack.push(new int[]{px - 1, py});
            stack.push(new int[]{px, py + 1});
            stack.push(new int[]{px, py - 1});
        }
        dirty = true;
        redrawCanvas();
    }

    private void pickColor(int x, int y) {
        if (x < 0 || y < 0 || x >= TEX_W || y >= texH) return;
        int argb = pixels[y * TEX_W + x];
        if ((argb >>> 24) == 0) return;
        setColor(fromArgb(argb), true);
    }

    private void setColor(Color c, boolean updatePicker) {
        color = c;
        if (updatePicker && colorPanel != null) colorPanel.set(c, false);
    }

    private void pushUndo() {
        undo.push(pixels.clone());
        while (undo.size() > UNDO_LIMIT) undo.removeLast();
        redo.clear();
    }

    private void undo() {
        if (undo.isEmpty()) return;
        redo.push(pixels.clone());
        pixels = undo.pop();
        refreshAll();
    }

    private void redo() {
        if (redo.isEmpty()) return;
        undo.push(pixels.clone());
        pixels = redo.pop();
        refreshAll();
    }

    private Point2D pickTexel(PickResult pick) {
        if (pick == null || !(pick.getIntersectedNode() instanceof MeshView)) return null;
        Point2D uv = pick.getIntersectedTexCoord();
        if (uv == null) return null;
        int x = (int) Math.floor(uv.getX() * TEX_W);
        int y = (int) Math.floor(uv.getY() * texH);
        if (x < 0 || y < 0 || x >= TEX_W || y >= texH) return null;
        return new Point2D(x, y);
    }

    // ------------------------------------------------------------------ drawing

    private void refreshAll() {
        updateOuterVisibility();
        for (int y = 0; y < texH; y++) {
            for (int x = 0; x < TEX_W; x++) {
                updateTexel(x, y);
            }
        }
        redrawCanvas();
    }

    /** Light lines round each texel of the outer layer: the grid shows where its pixels sit. */
    private static final int GRID_LINE = 0x66FFFFFF;
    private static final int GRID_LINE_ON_PAINT = 0x40000000;

    private void updateTexel(int x, int y) {
        PixelWriter writer = modelTexture.getPixelWriter();
        int argb = shown(x, y);
        // While the outer layer is being edited it is a grid rather than a shell: empty texels
        // are open cells, painted ones are filled cells, and the model shows through.
        boolean grid = outerLayer && !cape && outerTexel[y * TEX_W + x];
        boolean empty = (argb >>> 24) == 0;
        for (int dy = 0; dy < UPSCALE; dy++) {
            for (int dx = 0; dx < UPSCALE; dx++) {
                int c = argb;
                if (grid && (dx == 0 || dy == 0 || dx == UPSCALE - 1 || dy == UPSCALE - 1)) {
                    c = empty ? GRID_LINE : over(argb, GRID_LINE_ON_PAINT);
                }
                writer.setArgb(x * UPSCALE + dx, y * UPSCALE + dy, c);
            }
        }
    }

    /** {@code top} drawn over {@code bottom}, both ARGB. */
    private static int over(int bottom, int top) {
        double ta = (top >>> 24) / 255.0;
        int r = (int) Math.round(((top >> 16) & 255) * ta + ((bottom >> 16) & 255) * (1 - ta));
        int g = (int) Math.round(((top >> 8) & 255) * ta + ((bottom >> 8) & 255) * (1 - ta));
        int b = (int) Math.round((top & 255) * ta + (bottom & 255) * (1 - ta));
        return (bottom & 0xFF000000) | (r << 16) | (g << 8) | b;
    }

    /** What the model shows for a texel: its colour, tinted violet while selected. */
    private int shown(int x, int y) {
        int argb = pixels[y * TEX_W + x];
        if (!selected[y * TEX_W + x]) return argb;
        int a = Math.max(0x99, argb >>> 24);
        int r = (((argb >> 16) & 255) + 0x8D * 2) / 3, g = (((argb >> 8) & 255) + 0x6B * 2) / 3, b = ((argb & 255) + 0xFA * 2) / 3;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private void redrawCanvas() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setImageSmoothing(false);
        for (int y = 0; y < texH; y++) {
            for (int x = 0; x < TEX_W; x++) {
                drawCell(g, x, y);
            }
        }
        // face outlines, the edited layer's in the accent colour
        g.setLineWidth(1);
        for (Face f : faces) {
            boolean active = cape || f.outer() == outerLayer;
            g.setStroke(active ? Color.web("#2fb5a6", 0.9) : Color.web("#808080", 0.35));
            g.strokeRect(f.x() * cell + 0.5, f.y() * cell + 0.5, f.w() * cell - 1, f.h() * cell - 1);
        }
        if (selStartX >= 0) {
            g.setStroke(Color.web("#8D6BFA"));
            g.setLineWidth(2);
            g.setLineDashes(5, 4);
            int x0 = Math.min(selStartX, selEndX), y0 = Math.min(selStartY, selEndY);
            g.strokeRect(x0 * cell + 1, y0 * cell + 1, (Math.abs(selEndX - selStartX) + 1) * cell - 2,
                    (Math.abs(selEndY - selStartY) + 1) * cell - 2);
            g.setLineDashes(null);
        }
    }

    private void drawCell(GraphicsContext g, int x, int y) {
        double px = x * cell;
        double py = y * cell;
        Face face = faceAt(x, y);
        boolean used = face != null;
        // checkerboard shows transparency; unused texture is darker so it reads as "not here"
        boolean light = ((x + y) & 1) == 0;
        g.setFill(used ? (light ? Color.web("#3a3f47") : Color.web("#2f333a"))
                       : (light ? Color.web("#1d2025") : Color.web("#191b1f")));
        g.fillRect(px, py, cell, cell);
        int argb = pixels[y * TEX_W + x];
        if ((argb >>> 24) != 0) {
            Color c = fromArgb(argb);
            g.setFill(used ? c : c.deriveColor(0, 1, 0.45, 1));
            g.fillRect(px, py, cell, cell);
        }
        if (selected[y * TEX_W + x]) {
            g.setFill(Color.web("#8D6BFA", 0.45));
            g.fillRect(px, py, cell, cell);
        }
        if (used && cell >= 8) {
            g.setStroke(Color.rgb(0, 0, 0, 0.18));
            g.setLineWidth(1);
            g.strokeRect(px + 0.5, py + 0.5, cell - 1, cell - 1);
        }
        if (face != null && isFaceEdge(face, x, y)) {
            g.setStroke(Color.web("#2fb5a6", 0.9));
            g.strokeRect(face.x() * cell + 0.5, face.y() * cell + 0.5, face.w() * cell - 1, face.h() * cell - 1);
        }
    }

    private static boolean isFaceEdge(Face f, int x, int y) {
        return x == f.x() || y == f.y() || x == f.x() + f.w() - 1 || y == f.y() + f.h() - 1;
    }

    // ------------------------------------------------------------------ model

    private List<Box> boxes() {
        List<Box> list = new ArrayList<>();
        if (cape) {
            // vanilla's cape: 10x16x1 from (0,0), hanging from the shoulders
            list.add(new Box(-5, 0, -1, 10, 16, 1, 0, 0, 0, 0, 0, 0, false));
            return list;
        }
        int arm = slim ? 3 : 4;
        float armY = slim ? 2.5f : 2f;
        // base layer
        list.add(new Box(-4, -8, -4, 8, 8, 8, 0, 0, 0, 0, 0, 0, false));                       // head
        list.add(new Box(-4, 0, -2, 8, 12, 4, 16, 16, 0, 0, 0, 0, false));                     // body
        list.add(new Box(slim ? -2 : -3, -2, -2, arm, 12, 4, 40, 16, -5, armY, 0, 0, false));   // right arm
        list.add(new Box(-1, -2, -2, arm, 12, 4, 32, 48, 5, armY, 0, 0, false));               // left arm
        list.add(new Box(-2, 0, -2, 4, 12, 4, 0, 16, -1.9f, 12, 0, 0, false));                 // right leg
        list.add(new Box(-2, 0, -2, 4, 12, 4, 16, 48, 1.9f, 12, 0, 0, false));                 // left leg
        // outer layer
        list.add(new Box(-4, -8, -4, 8, 8, 8, 32, 0, 0, 0, 0, 0.5f, true));                    // hat
        list.add(new Box(-4, 0, -2, 8, 12, 4, 16, 32, 0, 0, 0, 0.25f, true));                  // jacket
        list.add(new Box(slim ? -2 : -3, -2, -2, arm, 12, 4, 40, 32, -5, armY, 0, 0.25f, true));
        list.add(new Box(-1, -2, -2, arm, 12, 4, 48, 48, 5, armY, 0, 0.25f, true));
        list.add(new Box(-2, 0, -2, 4, 12, 4, 0, 32, -1.9f, 12, 0, 0.25f, true));
        list.add(new Box(-2, 0, -2, 4, 12, 4, 0, 48, 1.9f, 12, 0, 0.25f, true));
        return list;
    }

    /** Texels that belong to a face of the outer layer. */
    private final boolean[] outerTexel = new boolean[TEX_W * 64];

    private void rebuildFaces() {
        faces.clear();
        java.util.Arrays.fill(outerTexel, false);
        for (Box b : boxes()) {
            int u = b.u(), v = b.v(), w = b.w(), h = b.h(), d = b.d();
            faces.add(new Face(u + d, v, w, d, b.outer()));             // top
            faces.add(new Face(u + d + w, v, w, d, b.outer()));         // bottom
            faces.add(new Face(u, v + d, d, h, b.outer()));             // right side
            faces.add(new Face(u + d, v + d, w, h, b.outer()));         // front
            faces.add(new Face(u + d + w, v + d, d, h, b.outer()));     // left side
            faces.add(new Face(u + d + w + d, v + d, w, h, b.outer())); // back
        }
        for (Face f : faces) {
            if (!f.outer()) continue;
            for (int y = f.y(); y < f.y() + f.h() && y < texH; y++)
                for (int x = f.x(); x < f.x() + f.w() && x < TEX_W; x++) outerTexel[y * TEX_W + x] = true;
        }
        buildMirror();
    }

    /** Outer-layer meshes and the box each one is made of, for {@link #updateOuterVisibility}. */
    private final java.util.Map<MeshView, Box> outerBoxes = new java.util.HashMap<>();

    private void rebuildModel() {
        baseGroup.getChildren().clear();
        outerGroup.getChildren().clear();
        outerBoxes.clear();
        for (Box b : boxes()) {
            MeshView view = new MeshView(cubeMesh(b));
            if (b.outer()) outerBoxes.put(view, b);
            view.setMaterial(material);
            view.setCullFace(CullFace.NONE);
            view.setTranslateX(b.px());
            view.setTranslateY(b.py());
            view.setTranslateZ(b.pz());
            int part = boxes().indexOf(b) % 6;
            if (!cape && part >= 2) view.getTransforms().setAll(limbTurn[part - 2]);
            (b.outer() ? outerGroup : baseGroup).getChildren().add(view);
        }
        applyLayerPicking();
    }

    private void applyLayerPicking() {
        outerGroup.setMouseTransparent(!outerLayer);
        baseGroup.setMouseTransparent(outerLayer);
        updateOuterVisibility();
    }

    /**
     * An outer box with nothing painted on it is still a box: its fully transparent edges catch
     * the antialiasing and draw a dark outline round the head and limbs. So while the base layer
     * is under the brush, empty outer boxes are hidden; with the outer layer selected all of them
     * stay, because that is where the paint has to land.
     */
    private void updateOuterVisibility() {
        for (var entry : outerBoxes.entrySet()) {
            entry.getKey().setVisible(outerLayer || hasPaint(entry.getValue()));
        }
    }

    private boolean hasPaint(Box b) {
        int u = b.u(), v = b.v(), w = b.w(), h = b.h(), d = b.d();
        for (int y = v; y < v + d + h; y++) {
            for (int x = u; x < u + 2 * (w + d); x++) {
                if (x >= TEX_W || y >= texH) continue;
                if (y < v + d && (x < u + d || x >= u + d + 2 * w)) continue;   // corners of the top row are unused
                if ((pixels[y * TEX_W + x] >>> 24) != 0) return true;
            }
        }
        return false;
    }

    /**
     * A cuboid with vanilla's six quads. The corner order and the (u1, v1, u2, v2) of each face are
     * ModelPart.Cube's, and so is the corner-to-UV assignment of ModelPart.Polygon: corner 0 gets
     * (u2, v1), 1 gets (u1, v1), 2 gets (u1, v2), 3 gets (u2, v2).
     */
    private TriangleMesh cubeMesh(Box b) {
        float g = b.grow();
        float x1 = b.x() - g, y1 = b.y() - g, z1 = b.z() - g;
        float x2 = b.x() + b.w() + g, y2 = b.y() + b.h() + g, z2 = b.z() + b.d() + g;
        float[][] corner = {
                {x1, y1, z1}, {x2, y1, z1}, {x2, y2, z1}, {x1, y2, z1},
                {x1, y1, z2}, {x2, y1, z2}, {x2, y2, z2}, {x1, y2, z2}};
        int u = b.u(), v = b.v(), w = b.w(), h = b.h(), d = b.d();
        float l = u, m = u + d, n = u + d + w, o = u + d + w + w, p = u + d + w + d, q = u + d + w + d + w;
        float r = v, s = v + d, t = v + d + h;
        // {corner indices}, u1, v1, u2, v2  (ModelPart.Cube; vertex1..8 -> 0..7)
        Object[][] quads = {
                {new int[]{5, 4, 0, 1}, m, r, n, s},   // y1: top
                {new int[]{2, 3, 7, 6}, n, s, o, r},   // y2: bottom
                {new int[]{0, 4, 7, 3}, l, s, m, t},   // x1
                {new int[]{1, 0, 3, 2}, m, s, n, t},   // z1: front
                {new int[]{5, 1, 2, 6}, n, s, p, t},   // x2
                {new int[]{4, 5, 6, 7}, p, s, q, t},   // z2: back
        };

        TriangleMesh mesh = new TriangleMesh();
        int texIndex = 0;
        int pointIndex = 0;
        // a hair inside each face so linear filtering never reaches the neighbouring texel
        float inset = 0.02f;
        for (Object[] quad : quads) {
            int[] idx = (int[]) quad[0];
            float u1 = (float) quad[1], v1 = (float) quad[2], u2 = (float) quad[3], v2 = (float) quad[4];
            float iu = Math.signum(u2 - u1) * inset, iv = Math.signum(v2 - v1) * inset;
            float[][] uvs = {{u2 - iu, v1 + iv}, {u1 + iu, v1 + iv}, {u1 + iu, v2 - iv}, {u2 - iu, v2 - iv}};
            for (int k = 0; k < 4; k++) {
                float[] c = corner[idx[k]];
                mesh.getPoints().addAll(c[0], c[1], c[2]);
                mesh.getTexCoords().addAll(uvs[k][0] / TEX_W, uvs[k][1] / texH);
            }
            int p0 = pointIndex, t0 = texIndex;
            mesh.getFaces().addAll(p0, t0, p0 + 1, t0 + 1, p0 + 2, t0 + 2);
            mesh.getFaces().addAll(p0, t0, p0 + 2, t0 + 2, p0 + 3, t0 + 3);
            pointIndex += 4;
            texIndex += 4;
        }
        return mesh;
    }

    // ------------------------------------------------------------------ files

    /**
     * Any player's skin by nickname, to start from or to study: Ely.by first (offline and custom
     * skins), then Mojang - the same lookup the friends list uses for heads.
     */
    private void loadByNick(String raw) {
        String nick = raw == null ? "" : raw.trim();
        if (!nick.matches("[A-Za-z0-9_]{2,16}")) {
            host.toast(host.tr("skin_by_nick_bad"), "info");
            return;
        }
        host.toast(host.tr("skin_by_nick_loading") + " " + nick, "info");
        Thread worker = new Thread(() -> {
            byte[] png = null;
            try {
                String url = com.example.launcher.service.FriendsService.resolveSkinUrlByNick(nick);
                if (url != null) {
                    var response = com.example.launcher.service.Http.get(url, java.util.Map.of("User-Agent", "BLauncher"),
                            java.time.Duration.ofSeconds(15));
                    if (response.ok()) png = response.body();
                }
            } catch (Exception ignored) {
                // reported below as "not found"
            }
            byte[] got = png;
            javafx.application.Platform.runLater(() -> {
                Image image = got == null ? null : new Image(new java.io.ByteArrayInputStream(got));
                if (image == null || image.isError()) {
                    host.toast(host.tr("skin_by_nick_none") + " " + nick, "warning");
                    return;
                }
                pushUndo();
                load(image, true);
            });
        }, "skin-by-nick");
        worker.setDaemon(true);
        worker.start();
    }

    private void takeMine() {
        host.loadProfileTexture(cape, image -> {
            if (image == null) {
                host.toast(host.tr(cape ? "cape_no_mine" : "skin_no_mine"), "info");
                return;
            }
            pushUndo();
            load(image, true);
        });
    }

    private void openFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(host.tr("skin_open_file"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG", "*.png"));
        File file = chooser.showOpenDialog(root.getScene() == null ? null : root.getScene().getWindow());
        if (file == null) return;
        Image image = new Image(file.toURI().toString());
        if (image.isError()) {
            host.toast(host.tr("skin_bad_file"), "error");
            return;
        }
        pushUndo();
        load(image, true);
    }

    private void newTexture() {
        pushUndo();
        pixels = cape ? defaultCape() : defaultSkin();
        dirty = true;
        refreshAll();
    }

    /**
     * Takes a texture in any of the sizes the game accepts: 64x64 and 64x32 skins, 64x32 and
     * 22x17 capes. Anything else (HD packs, random pictures) is refused with a message, since
     * squeezing it into 64 pixels would just produce mush.
     */
    private boolean load(Image image, boolean announce) {
        PixelReader reader = image.getPixelReader();
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        if (reader == null) return false;
        int[] next = new int[TEX_W * texH];
        if (cape) {
            if (!((w == 64 && h == 32) || (w == 22 && h == 17))) {
                if (announce) host.toast(host.tr("cape_bad_size"), "error");
                return false;
            }
            for (int y = 0; y < Math.min(h, texH); y++)
                for (int x = 0; x < Math.min(w, TEX_W); x++)
                    next[y * TEX_W + x] = reader.getArgb(x, y);
        } else {
            if (!(w == 64 && (h == 64 || h == 32))) {
                if (announce) host.toast(host.tr("skin_bad_size"), "error");
                return false;
            }
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++)
                    next[y * TEX_W + x] = reader.getArgb(x, y);
            if (h == 32) convertLegacy(next);
            boolean detectedSlim = (next[20 * TEX_W + 54] >>> 24) == 0 && (next[20 * TEX_W + 50] >>> 24) != 0;
            if (detectedSlim != slim) {
                slim = detectedSlim;
                if (slimToggle != null) slimToggle.setSelected(slim);
                rebuildFaces();
                rebuildModel();
            }
        }
        pixels = next;
        dirty = announce;
        refreshAll();
        return true;
    }

    /**
     * 64x32 -> 64x64, as the game converts an old skin: the left arm and leg are copies of the
     * right ones with every face mirrored, and the faces swap sides.
     */
    static void convertLegacy(int[] p) {
        copyMirrored(p, 4, 16, 4, 4, 20, 48);   // leg top
        copyMirrored(p, 8, 16, 4, 4, 24, 48);   // leg bottom
        copyMirrored(p, 0, 20, 4, 12, 24, 52);  // leg outer -> inner
        copyMirrored(p, 4, 20, 4, 12, 20, 52);  // leg front
        copyMirrored(p, 8, 20, 4, 12, 16, 52);  // leg inner -> outer
        copyMirrored(p, 12, 20, 4, 12, 28, 52); // leg back
        copyMirrored(p, 44, 16, 4, 4, 36, 48);  // arm top
        copyMirrored(p, 48, 16, 4, 4, 40, 48);  // arm bottom
        copyMirrored(p, 40, 20, 4, 12, 40, 52);
        copyMirrored(p, 44, 20, 4, 12, 36, 52);
        copyMirrored(p, 48, 20, 4, 12, 32, 52);
        copyMirrored(p, 52, 20, 4, 12, 44, 52);
    }

    private static void copyMirrored(int[] p, int sx, int sy, int w, int h, int dx, int dy) {
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++)
                p[(dy + y) * TEX_W + dx + x] = p[(sy + y) * TEX_W + sx + (w - 1 - x)];
    }

    private byte[] toPng() throws IOException {
        BufferedImage out = new BufferedImage(TEX_W, texH, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, TEX_W, texH, pixels, 0, TEX_W);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(out, "png", bytes);
        return bytes.toByteArray();
    }

    /** The model as it is on screen - turned, posed - as a picture to show the skin off. */
    private void savePicture() {
        if (modelView == null) return;
        try {
            File file = Snapshots.saveAsPng(modelView, host.tr("skin_picture"), cape ? "cape-preview.png" : "skin-preview.png");
            if (file != null) host.toast(host.tr("skin_exported") + " " + file.getName(), "success");
        } catch (IOException e) {
            host.toast(e.getMessage(), "error");
        }
    }

    private void exportFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(host.tr("skin_export"));
        chooser.setInitialFileName(cape ? "cape.png" : "skin.png");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG", "*.png"));
        File file = chooser.showSaveDialog(root.getScene() == null ? null : root.getScene().getWindow());
        if (file == null) return;
        try {
            java.nio.file.Files.write(file.toPath(), toPng());
            host.toast(host.tr("skin_exported") + " " + file.getName(), "success");
        } catch (IOException e) {
            host.toast(e.getMessage(), "error");
        }
    }

    private void saveToProfile() {
        try {
            host.saveToProfile(toPng(), cape);
            dirty = false;
        } catch (IOException e) {
            host.toast(e.getMessage(), "error");
        }
    }

    /** Pixels for tests and for the launcher's own checks. */
    public int[] pixels() {
        return pixels.clone();
    }

    // ------------------------------------------------------------------ defaults

    /** A plain mannequin in the launcher's colours: something to paint over, not a character. */
    private int[] defaultSkin() {
        int[] p = new int[TEX_W * 64];
        int skin = 0xFFD9A47E, shirt = 0xFF2FB5A6, trousers = 0xFF3B4A7A, hair = 0xFF4A3426, shoe = 0xFF2A2A2A;
        fillBox(p, 0, 0, 8, 8, 8, skin);         // head
        fillRect(p, 8, 0, 8, 8, hair);           // top of the head
        fillRect(p, 0, 8, 32, 2, hair);          // hairline round the head
        fillRect(p, 9, 12, 2, 1, 0xFFFFFFFF);    // eyes
        fillRect(p, 13, 12, 2, 1, 0xFFFFFFFF);
        fillRect(p, 10, 12, 1, 1, 0xFF2B4D8C);
        fillRect(p, 13, 12, 1, 1, 0xFF2B4D8C);
        fillBox(p, 16, 16, 8, 12, 4, shirt);     // body
        fillBox(p, 40, 16, 4, 12, 4, skin);      // right arm
        fillRect(p, 40, 20, 16, 4, shirt);       // sleeve
        fillBox(p, 32, 48, 4, 12, 4, skin);      // left arm
        fillRect(p, 32, 52, 16, 4, shirt);
        fillBox(p, 0, 16, 4, 12, 4, trousers);   // right leg
        fillRect(p, 0, 29, 16, 3, shoe);
        fillBox(p, 16, 48, 4, 12, 4, trousers);  // left leg
        fillRect(p, 16, 61, 16, 3, shoe);
        return p;
    }

    private int[] defaultCape() {
        int[] p = new int[TEX_W * 32];
        fillBox(p, 0, 0, 10, 16, 1, 0xFF1E6F67);
        fillRect(p, 1, 1, 10, 16, 0xFF2FB5A6);   // the outer side
        return p;
    }

    private static void fillBox(int[] p, int u, int v, int w, int h, int d, int argb) {
        fillRect(p, u + d, v, w, d, argb);
        fillRect(p, u + d + w, v, w, d, argb);
        fillRect(p, u, v + d, d + w + d + w, h, argb);
    }

    private static void fillRect(int[] p, int x, int y, int w, int h, int argb) {
        for (int j = y; j < y + h; j++)
            for (int i = x; i < x + w; i++)
                p[j * TEX_W + i] = argb;
    }

    // ------------------------------------------------------------------ colours

    private static int toArgb(Color c) {
        return ((int) Math.round(c.getOpacity() * 255) << 24)
                | ((int) Math.round(c.getRed() * 255) << 16)
                | ((int) Math.round(c.getGreen() * 255) << 8)
                | (int) Math.round(c.getBlue() * 255);
    }

    private static Color fromArgb(int argb) {
        return Color.rgb((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, ((argb >>> 24) & 255) / 255.0);
    }

}
