package com.example.launcher.ui.blockart;

import com.example.launcher.ui.studio.McAssets;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import javafx.scene.control.Slider;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * «Блоки из файла»: картинка или 3D-модель становятся постройкой, чертёж можно посмотреть в 3D и сохранить
 * в другом формате. Всё считается на компьютере, без сети: цвета блоков берутся из установленной игры
 * (05.11.2026, просьба владельца сделать это частью лаунчера вместо ссылки на сторонний сайт).
 */
public final class BlockArtTool implements AutoCloseable {

    public interface Host {
        String tr(String key);
        void toast(String message, String type);
        Path versionsDir();
        /** Куда сохранять: сюда смотрит Litematica. */
        Path schematicsDir();
        default boolean invertMouse() { return false; }
        default void stat(String detail) { }
        /** Сохранённый путь к blender.exe и его запись. */
        default String blenderPath() { return null; }
        default void saveBlenderPath(String path) { }
        /** Рабочая папка сцен Blender и язык интерфейса. */
        default Path blenderDir() { return schematicsDir().resolve("blender"); }
        default String language() { return "ru"; }
        default void log(String message) { }
    }

    private record Version(String id, Path jar) {}

    /** Сторон у предпросмотра больше, чем JavaFX показывает плавно: крупные постройки рисуем реже. */
    private static final int PREVIEW_FACES = 300_000;

    private final Host host;
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "block-art");
        t.setDaemon(true);
        return t;
    });
    private final HBox root;
    private final ComboBox<Version> versionBox = new ComboBox<>();
    private final Slider sizeSlider = new Slider(8, VoxelGrid.MAX_SIDE, 64);
    private final Label sizeLabel = new Label();
    private final CheckBox wall = new CheckBox();
    private final CheckBox dither = new CheckBox();
    private final CheckBox basic = new CheckBox();
    private final CheckBox fill = new CheckBox();
    private final CheckBox zUp = new CheckBox();
    private final Label fileLabel = new Label();
    private final Label infoLabel = new Label();
    private final Button save;
    private final Button blender;
    /** Открыта сцена, вернувшаяся из Blender: там 1 единица = 1 блок. */
    private boolean fromBlender;
    private volatile Voxelizer.Original blenderOriginal;
    private Thread blenderWatch;
    private final VBox imageOptions;
    private final VBox modelOptions;

    private final Group model = new Group();
    private final Rotate spinX = new Rotate(25, Rotate.X_AXIS);
    private final Rotate spinY = new Rotate(35, Rotate.Y_AXIS);
    private final PerspectiveCamera camera = new PerspectiveCamera(true);
    private double lastX;
    private double lastY;

    private Path source;
    private VoxelGrid grid;
    private int token;
    private McAssets assets;
    private BlockColors colors;
    private Version colorsFor;
    private int dataVersion = 3953;

    public BlockArtTool(Host host) {
        this.host = host;
        save = new Button(host.tr("blockart_save"));
        blender = new Button(host.tr("blockart_blender"));
        imageOptions = new VBox(8);
        modelOptions = new VBox(8);
        root = new HBox(12, buildPanel(), buildView());
        root.setMinSize(0, 0);
        HBox.setHgrow(root.getChildren().get(1), Priority.ALWAYS);
        io.submit(this::loadVersions);
    }

    public Node node() {
        return root;
    }

    @Override
    public void close() {
        token++;
        if (blenderWatch != null) blenderWatch.interrupt();
        io.submit(() -> {
            if (assets != null) assets.close();
        });
        io.shutdown();
    }

    // ------------------------------------------------------------------ layout

    private Node buildPanel() {
        Button open = new Button(host.tr("blockart_open"));
        open.getStyleClass().add("secondary-button");
        open.setMaxWidth(Double.MAX_VALUE);
        open.setOnAction(e -> choose());
        fileLabel.getStyleClass().add("toggle-sub");
        fileLabel.setWrapText(true);
        fileLabel.setText(host.tr("blockart_formats"));

        versionBox.setMaxWidth(Double.MAX_VALUE);
        versionBox.setCellFactory(v -> versionCell());
        versionBox.setButtonCell(versionCell());
        versionBox.valueProperty().addListener((o, a, b) -> rebuild());

        sizeLabel.getStyleClass().add("toggle-sub");
        sizeSlider.valueProperty().addListener((o, a, b) -> {
            sizeLabel.setText(host.tr("blockart_size") + ": " + Math.round(b.doubleValue()));
            if (!sizeSlider.isValueChanging()) rebuild();
        });
        sizeSlider.valueChangingProperty().addListener((o, a, changing) -> { if (!changing) rebuild(); });
        sizeLabel.setText(host.tr("blockart_size") + ": 64");

        check(wall, "blockart_wall", true);
        check(dither, "blockart_dither", false);
        check(basic, "blockart_basic", false);
        check(fill, "blockart_fill", false);
        check(zUp, "blockart_zup", false);
        imageOptions.getChildren().addAll(wall, dither);
        modelOptions.getChildren().addAll(fill, zUp);

        infoLabel.getStyleClass().add("toggle-sub");
        infoLabel.setWrapText(true);
        Label hint = new Label(host.tr("blockart_hint"));
        hint.getStyleClass().add("toggle-sub");
        hint.setWrapText(true);

        save.getStyleClass().addAll("play-btn", "modal-create-btn");
        save.setStyle("-fx-padding: 11 8; -fx-font-size: 14px;");
        save.setMaxWidth(Double.MAX_VALUE);
        save.setDisable(true);
        save.setOnAction(e -> saveAs());

        Label versionLabel = new Label(host.tr("blockart_version"));
        versionLabel.getStyleClass().add("toggle-sub");
        blender.getStyleClass().add("secondary-button");
        blender.setMaxWidth(Double.MAX_VALUE);
        blender.setDisable(true);
        blender.setOnAction(e -> openInBlender());
        // Настройки листаются, а кнопки всегда на виду: в окне 1050×700 подписи иначе обрезались многоточием.
        VBox settings = new VBox(10, open, fileLabel, versionLabel, versionBox, sizeLabel, sizeSlider, imageOptions, modelOptions,
                basic, infoLabel, hint);
        for (Node n : List.of(fileLabel, infoLabel, hint, wall, dither, basic, fill, zUp)) {
            if (n instanceof javafx.scene.layout.Region r) r.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        }
        javafx.scene.control.ScrollPane scroll = new javafx.scene.control.ScrollPane(settings);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(javafx.scene.control.ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("screen-scroll");
        scroll.setMinHeight(0);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        VBox box = new VBox(10, scroll, blender, save);
        box.getStyleClass().add("panel");
        box.setPadding(new Insets(14));
        box.setMinWidth(260);
        box.setPrefWidth(260);
        box.setMaxWidth(260);
        return box;
    }

    private void check(CheckBox c, String key, boolean on) {
        c.setText(host.tr(key));
        c.setWrapText(true);
        c.setSelected(on);
        c.selectedProperty().addListener((o, a, b) -> rebuild());
    }

    private ListCell<Version> versionCell() {
        return new ListCell<>() {
            @Override protected void updateItem(Version v, boolean empty) {
                super.updateItem(v, empty);
                setText(empty || v == null ? "" : v.id());
            }
        };
    }

    private Node buildView() {
        Group world = new Group(model, new AmbientLight(Color.WHITE));
        model.getTransforms().addAll(spinX, spinY);
        camera.setFieldOfView(32);
        camera.setNearClip(1);
        camera.setFarClip(20000);
        camera.setTranslateZ(-420);
        SubScene scene = new SubScene(world, 400, 400, true, SceneAntialiasing.BALANCED);
        scene.setFill(Color.TRANSPARENT);
        scene.setCamera(camera);
        Pane holder = new Pane(scene);
        holder.getStyleClass().add("panel");
        holder.setMinSize(0, 0);
        scene.widthProperty().bind(holder.widthProperty());
        scene.heightProperty().bind(holder.heightProperty());
        double sign = host.invertMouse() ? -1 : 1;
        scene.setOnMousePressed(e -> {
            lastX = e.getSceneX();
            lastY = e.getSceneY();
        });
        scene.setOnMouseDragged(e -> {
            spinY.setAngle(spinY.getAngle() + sign * (e.getSceneX() - lastX) * 0.5);
            spinX.setAngle(Math.max(-89, Math.min(89, spinX.getAngle() - sign * (e.getSceneY() - lastY) * 0.5)));
            lastX = e.getSceneX();
            lastY = e.getSceneY();
        });
        scene.addEventHandler(ScrollEvent.SCROLL, e -> {
            double z = camera.getTranslateZ() * (e.getDeltaY() > 0 ? 0.9 : 1.1);
            camera.setTranslateZ(Math.max(-3000, Math.min(-60, z)));
            e.consume();
        });
        return holder;
    }

    // ------------------------------------------------------------------ work

    private void loadVersions() {
        List<Version> found = new ArrayList<>();
        Map<Version, String> order = new HashMap<>();
        Path dir = host.versionsDir();
        if (dir != null && Files.isDirectory(dir)) {
            try (var dirs = Files.list(dir)) {
                for (Path p : (Iterable<Path>) dirs::iterator) {
                    String id = p.getFileName().toString();
                    Path jar = p.resolve(id + ".jar");
                    Path json = p.resolve(id + ".json");
                    if (!Files.isRegularFile(jar) || !Files.isRegularFile(json)) continue;
                    try {
                        JsonObject meta = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
                        if (meta.has("inheritsFrom")) continue;
                        String type = meta.has("type") ? meta.get("type").getAsString() : "";
                        Version v = new Version(id, jar);
                        order.put(v, ("release".equals(type) ? "1" : "0") + (meta.has("releaseTime") ? meta.get("releaseTime").getAsString() : ""));
                        found.add(v);
                    } catch (Exception ignored) {
                        // нечитаемая версия просто не предлагается
                    }
                }
            } catch (IOException ignored) {
                // список останется пустым, об этом ниже
            }
        }
        found.sort((a, b) -> order.get(b).compareTo(order.get(a)));
        Platform.runLater(() -> {
            versionBox.getItems().setAll(found);
            if (!found.isEmpty()) versionBox.setValue(found.get(0));
            else infoLabel.setText(host.tr("blockart_no_version"));
        });
    }

    private void choose() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(host.tr("blockart_open"));
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(host.tr("blockart_filter_all"),
                        "*.png", "*.jpg", "*.jpeg", "*.gif", "*.bmp", "*.webp", "*.obj", "*.stl", "*.schem", "*.litematic", "*.nbt"));
        File file = chooser.showOpenDialog(root.getScene() == null ? null : root.getScene().getWindow());
        if (file == null) return;
        openFile(file.toPath());
    }

    /** Открывает файл: чертёж — как есть, картинку и модель — переводит в блоки. */
    public void openFile(Path file) {
        fromBlender = false;
        source = file;
        fileLabel.setText(file.getFileName().toString());
        rebuild();
    }

    private static String kindOf(boolean schematic, boolean model) {
        return schematic ? "schem" : model ? "model" : "image";
    }

    private static String extOf(String name) {
        String ext = name.substring(name.lastIndexOf('.') + 1);
        return java.util.Set.of("png", "jpg", "jpeg", "gif", "bmp", "webp", "obj", "stl", "schem", "schematic", "litematic", "nbt")
                .contains(ext) ? ext : "other";
    }

    private static boolean isModel(String name) {
        return name.endsWith(".obj") || name.endsWith(".stl");
    }

    private void rebuild() {
        Version version = versionBox.getValue();
        if (version == null) return;
        Path file = source;
        String name = file == null ? "" : file.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean schematic = SchematicIO.isSchematic(name);
        boolean modelFile = isModel(name);
        setShown(imageOptions, file != null && !schematic && !modelFile);
        setShown(modelOptions, modelFile);
        setShown(sizeSlider, file != null && !schematic);
        setShown(sizeLabel, file != null && !schematic);
        setShown(basic, file != null && !schematic);
        int size = (int) Math.round(sizeSlider.getValue());
        boolean w = wall.isSelected(), d = dither.isSelected(), b = basic.isSelected(), f = fill.isSelected(), z = zUp.isSelected();
        int mine = ++token;
        long startedAt = System.currentTimeMillis();
        infoLabel.setText(host.tr("blockart_working"));
        save.setDisable(true);
        blender.setDisable(true);
        boolean units = fromBlender;
        io.submit(() -> {
            try {
                prepare(version);
                if (file == null) {
                    Platform.runLater(() -> infoLabel.setText(""));
                    return;
                }
                VoxelGrid g;
                if (units) g = Voxelizer.sceneUnits(file, colors.blocks(b), blenderOriginal);
                else if (schematic) g = SchematicIO.load(file);
                else if (modelFile) g = Voxelizer.model(file, size, f, z, colors.blocks(b));
                else g = Voxelizer.image(file, size, w, d, colors.blocks(b));
                PreviewMesh mesh = PreviewMesh.of(g, colors);
                host.stat("blockart ok " + kindOf(schematic, modelFile) + " " + extOf(name) + " " + g.w + "x" + g.h + "x" + g.l
                        + " blocks=" + g.blockCount() + " kinds=" + (g.palette.size() - 1) + " ms=" + (System.currentTimeMillis() - startedAt)
                        + " mc=" + version.id());
                Platform.runLater(() -> {
                    if (mine != token) return;
                    grid = g;
                    showMesh(mesh, g);
                    infoLabel.setText(g.w + " × " + g.h + " × " + g.l + " · " + g.blockCount() + " · " + (g.palette.size() - 1)
                            + " " + host.tr("blockart_kinds"));
                    save.setDisable(false);
                    blender.setDisable(false);
                });
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                // Имя файла в отметку не идёт, только вид и расширение из нашего списка.
                host.stat("blockart fail " + kindOf(schematic, modelFile) + " " + extOf(name)
                        + com.example.launcher.service.ErrorLog.record("blockart", e));
                host.log("[BLOCKART] Не удалось обработать файл: " + e);
                Platform.runLater(() -> {
                    if (mine != token) return;
                    grid = null;
                    model.getChildren().clear();
                    infoLabel.setText(host.tr("blockart_failed"));
                    host.toast(host.tr("blockart_failed"), "error");
                });
            }
        });
    }

    private static void setShown(Node n, boolean on) {
        n.setVisible(on);
        n.setManaged(on);
    }

    /** Цвета и номер данных нужной версии; считается один раз на версию. */
    private void prepare(Version version) throws IOException {
        if (version.equals(colorsFor)) return;
        if (assets != null) assets.close();
        assets = McAssets.open(version.jar(), version.id());
        colors = new BlockColors(assets);
        colorsFor = version;
        dataVersion = readDataVersion(version.jar());
    }

    /** Номер данных игры, без него чертёж не откроется правильно: он лежит в version.json внутри jar. */
    static int readDataVersion(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("version.json");
            if (entry != null) {
                JsonObject json = JsonParser.parseReader(new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)).getAsJsonObject();
                if (json.has("world_version")) return json.get("world_version").getAsInt();
            }
        } catch (IOException | RuntimeException ignored) {
            // старая версия без этого файла
        }
        return 3953;
    }

    /** Сцена в Blender: находит его, готовит папку и следит, когда сцену вернут. */
    private void openInBlender() {
        VoxelGrid g = grid;
        Version version = colorsFor;
        if (g == null || version == null) return;
        Path exe = BlenderBridge.find(host.blenderPath());
        if (exe == null) {
            host.stat("blender notfound");
            host.toast(host.tr("blockart_blender_pick"), "info");
            FileChooser chooser = new FileChooser();
            chooser.setTitle(host.tr("blockart_blender_pick"));
            File file = chooser.showOpenDialog(root.getScene() == null ? null : root.getScene().getWindow());
            if (file == null) return;
            exe = file.toPath();
            host.saveBlenderPath(file.getAbsolutePath());
        }
        String base = source == null ? "build" : source.getFileName().toString().replaceAll("\\.[^.]+$", "");
        Path dir = host.blenderDir().resolve(BlenderBridge.folderName(base));
        Path blenderExe = exe;
        blender.setDisable(true);
        infoLabel.setText(host.tr("blockart_blender_wait"));
        io.submit(() -> {
            try {
                blenderOriginal = BlenderBridge.writeScene(g, version.jar(), assets, dir, host.language());
                Process process = BlenderBridge.launch(blenderExe, dir);
                host.stat("blender open blocks=" + g.blockCount());
                watchBlender(process, dir);
                Platform.runLater(() -> {
                    blender.setDisable(false);
                    infoLabel.setText(host.tr("blockart_blender_open"));
                });
            } catch (IOException | RuntimeException e) {
                host.stat("blender fail" + com.example.launcher.service.ErrorLog.record("blender", e));
                host.log("[BLOCKART] Blender не запустился: " + e);
                Platform.runLater(() -> {
                    blender.setDisable(false);
                    infoLabel.setText(host.tr("blockart_blender_failed"));
                    host.toast(host.tr("blockart_blender_failed"), "error");
                });
            }
        });
    }

    /** Пока Blender открыт, раз в секунду смотрит, не вернули ли сцену кнопкой «Вернуть в BLauncher». */
    private void watchBlender(Process process, Path dir) {
        if (blenderWatch != null) blenderWatch.interrupt();
        Path done = dir.resolve("export").resolve("done.txt");
        Path obj = dir.resolve("export").resolve("build.obj");
        blenderWatch = new Thread(() -> {
            try {
                while (process.isAlive() || Files.exists(done)) {
                    if (Files.exists(done)) {
                        Files.delete(done);
                        host.stat("blender back");
                        Platform.runLater(() -> {
                            fromBlender = true;
                            source = obj;
                            fileLabel.setText(host.tr("blockart_blender_back"));
                            rebuild();
                        });
                    }
                    Thread.sleep(1000);
                }
            } catch (InterruptedException ignored) {
                // инструмент закрыли или открыли новую сцену
            } catch (IOException e) {
                com.example.launcher.service.ErrorLog.record("blender_watch", e);
            }
        }, "blender-watch");
        blenderWatch.setDaemon(true);
        blenderWatch.start();
    }

    private void saveAs() {
        VoxelGrid g = grid;
        if (g == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle(host.tr("blockart_save"));
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Litematica (.litematic)", "*.litematic"),
                new FileChooser.ExtensionFilter("WorldEdit (.schem)", "*.schem"),
                new FileChooser.ExtensionFilter(host.tr("blockart_filter_structure"), "*.nbt"));
        Path dir = host.schematicsDir();
        try {
            Files.createDirectories(dir);
            chooser.setInitialDirectory(dir.toFile());
        } catch (IOException ignored) {
            // откроется папка по умолчанию
        }
        String base = source == null ? "build" : source.getFileName().toString().replaceAll("\\.[^.]+$", "");
        chooser.setInitialFileName(base.replaceAll("[^\\p{L}\\p{N} _-]", "_"));
        File file = chooser.showSaveDialog(root.getScene() == null ? null : root.getScene().getWindow());
        if (file == null) return;
        int version = dataVersion;
        // Выбранный фильтр задаёт формат, если расширение не набрали.
        String ext = chooser.getSelectedExtensionFilter() == null ? ".litematic"
                : chooser.getSelectedExtensionFilter().getExtensions().get(0).substring(1);
        Path target = file.getName().contains(".") ? file.toPath() : file.toPath().resolveSibling(file.getName() + ext);
        String name = base;
        io.submit(() -> {
            try {
                SchematicIO.save(g, target, version, name);
                host.stat("blockart save " + extOf(target.getFileName().toString().toLowerCase(Locale.ROOT)) + " blocks=" + g.blockCount());
                Platform.runLater(() -> host.toast(host.tr("blockart_saved") + " " + target.getFileName(), "success"));
            } catch (IOException | RuntimeException e) {
                host.stat("blockart save fail" + com.example.launcher.service.ErrorLog.record("blockart_save", e));
                Platform.runLater(() -> host.toast(host.tr("blockart_failed"), "error"));
            }
        });
    }

    // ------------------------------------------------------------------ preview

    /** Сетка считается в фоне, а узлы создаются в потоке интерфейса. */
    private record PreviewMesh(float[] points, float[] uvs, int[] faces, int[] colors) {

        static PreviewMesh of(VoxelGrid g, BlockColors colors) {
            int count = 0;
            for (int y = 0; y < g.h; y++) {
                for (int z = 0; z < g.l; z++) {
                    for (int x = 0; x < g.w; x++) {
                        if (g.at(x, y, z) == 0) continue;
                        if (g.empty(x + 1, y, z)) count++;
                        if (g.empty(x - 1, y, z)) count++;
                        if (g.empty(x, y + 1, z)) count++;
                        if (g.empty(x, y - 1, z)) count++;
                        if (g.empty(x, y, z + 1)) count++;
                        if (g.empty(x, y, z - 1)) count++;
                    }
                }
            }
            // Слишком большие постройки показываем через блок: чертёж при этом целый.
            int stride = count > PREVIEW_FACES ? (int) Math.ceil(Math.cbrt((double) count / PREVIEW_FACES)) : 1;
            int[] colorTable = new int[g.palette.size()];
            for (int i = 0; i < colorTable.length; i++) colorTable[i] = colors.colorOf(g.palette.get(i));
            int cols = colorTable.length;
            List<float[]> quads = new ArrayList<>();
            List<int[]> quadInfo = new ArrayList<>();
            int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            int[] shadeRow = {1, 1, 0, 2, 1, 1};
            float s = stride;
            float cx = g.w / 2f, cy = g.h / 2f, cz = g.l / 2f;
            for (int y = 0; y < g.h; y += stride) {
                for (int z = 0; z < g.l; z += stride) {
                    for (int x = 0; x < g.w; x += stride) {
                        int id = g.at(x, y, z);
                        if (id == 0) continue;
                        for (int d = 0; d < 6; d++) {
                            if (!g.empty(x + dirs[d][0] * stride, y + dirs[d][1] * stride, z + dirs[d][2] * stride)) continue;
                            quads.add(face(d, x - cx, y - cy, z - cz, s));
                            quadInfo.add(new int[]{id, shadeRow[d]});
                        }
                    }
                }
            }
            float[] points = new float[quads.size() * 12];
            int[] faces = new int[quads.size() * 12];
            float[] uvs = new float[(cols * 3) * 2];
            for (int i = 0; i < cols; i++) {
                for (int r = 0; r < 3; r++) {
                    uvs[(i * 3 + r) * 2] = (i + 0.5f) / cols;
                    uvs[(i * 3 + r) * 2 + 1] = (r + 0.5f) / 3;
                }
            }
            for (int i = 0; i < quads.size(); i++) {
                System.arraycopy(quads.get(i), 0, points, i * 12, 12);
                int t = quadInfo.get(i)[0] * 3 + quadInfo.get(i)[1];
                int p = i * 4;
                int[] f = {p, t, p + 1, t, p + 2, t, p, t, p + 2, t, p + 3, t};
                System.arraycopy(f, 0, faces, i * 12, 12);
            }
            return new PreviewMesh(points, uvs, faces, colorTable);
        }

        private static float[] face(int d, float x, float y, float z, float s) {
            float a = x, b = x + s, c = y, e = y + s, f = z, g = z + s;
            return switch (d) {
                case 0 -> new float[]{b, c, f, b, e, f, b, e, g, b, c, g};
                case 1 -> new float[]{a, c, g, a, e, g, a, e, f, a, c, f};
                case 2 -> new float[]{a, e, f, a, e, g, b, e, g, b, e, f};
                case 3 -> new float[]{a, c, g, a, c, f, b, c, f, b, c, g};
                case 4 -> new float[]{b, c, g, b, e, g, a, e, g, a, c, g};
                default -> new float[]{a, c, f, a, e, f, b, e, f, b, c, f};
            };
        }
    }

    private void showMesh(PreviewMesh m, VoxelGrid g) {
        TriangleMesh mesh = new TriangleMesh();
        mesh.getPoints().setAll(m.points());
        mesh.getTexCoords().setAll(m.uvs());
        mesh.getFaces().setAll(m.faces());
        WritableImage atlas = new WritableImage(Math.max(1, m.colors().length), 3);
        PixelWriter w = atlas.getPixelWriter();
        double[] shade = {1.0, 0.8, 0.55};
        for (int i = 0; i < m.colors().length; i++) {
            int c = m.colors()[i];
            for (int r = 0; r < 3; r++) {
                w.setColor(i, r, Color.rgb((int) (((c >> 16) & 255) * shade[r]), (int) (((c >> 8) & 255) * shade[r]), (int) ((c & 255) * shade[r])));
            }
        }
        PhongMaterial material = new PhongMaterial(Color.WHITE);
        material.setDiffuseMap(atlas);
        material.setSpecularColor(Color.BLACK);
        MeshView view = new MeshView(mesh);
        view.setCullFace(CullFace.NONE);
        view.setMaterial(material);
        // В игре y вверх, на экране вниз.
        view.getTransforms().add(new javafx.scene.transform.Scale(1, -1, 1));
        model.getChildren().setAll(view);
        double longest = Math.max(g.w, Math.max(g.h, g.l));
        camera.setTranslateZ(-longest * 3.4);
    }
}
