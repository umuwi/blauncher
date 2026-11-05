package com.example.launcher.ui.texpack;

import com.example.launcher.ui.studio.McAssets;
import javafx.geometry.Point2D;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.PerspectiveCamera;
import javafx.scene.SceneAntialiasing;
import javafx.scene.SubScene;
import javafx.scene.image.Image;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.PickResult;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Рисование текстуры прямо на объекте, который можно крутить (05.11.2026, просьба владельца).
 * Показывает настоящую модель блока или предмета из игры: левая кнопка рисует на гранях с этой текстурой,
 * правая (и левая мимо объекта) вращает, колесо приближает. У текстур без модели в игровых файлах
 * (существа, интерфейс) — плоская пластина, которую тоже можно рисовать с двух сторон.
 */
final class TextureModelView {

    interface Painter {
        /** Нажатие левой кнопкой на текстуру; {@code pick} — правая на текстуре, пипетка. */
        void press(int x, int y);
        void drag(int x, int y);
        void release();
    }

    private static final double SIZE = 100;

    private final Pane holder = new Pane();
    private final Group model = new Group();
    private final Rotate spinX = new Rotate(28, Rotate.X_AXIS);
    private final Rotate spinY = new Rotate(38, Rotate.Y_AXIS);
    private final PerspectiveCamera camera = new PerspectiveCamera(true);
    private final SubScene scene;
    private final Painter painter;
    private final List<MeshView> own = new ArrayList<>();
    private final Map<String, PhongMaterial> others = new LinkedHashMap<>();
    private final boolean invert;

    private WritableImage image;
    private int texW = 1;
    private int texH = 1;
    private int detail = 1;
    private double lastX;
    private double lastY;
    private boolean painting;

    TextureModelView(Painter painter, boolean invertMouse) {
        this.painter = painter;
        this.invert = invertMouse;
        Group world = new Group(model, new AmbientLight(Color.WHITE));
        model.getTransforms().addAll(spinX, spinY);
        camera.setFieldOfView(32);
        camera.setNearClip(1);
        camera.setFarClip(2000);
        camera.setTranslateZ(-340);
        scene = new SubScene(world, 300, 300, true, SceneAntialiasing.BALANCED);
        scene.setFill(Color.TRANSPARENT);
        scene.setCamera(camera);
        scene.widthProperty().bind(holder.widthProperty());
        scene.heightProperty().bind(holder.heightProperty());
        holder.getChildren().add(scene);
        holder.setMinSize(0, 0);

        scene.setOnMousePressed(e -> {
            lastX = e.getSceneX();
            lastY = e.getSceneY();
            Point2D texel = e.getButton() == MouseButton.PRIMARY ? texel(e.getPickResult()) : null;
            painting = texel != null;
            if (painting) painter.press((int) texel.getX(), (int) texel.getY());
        });
        scene.setOnMouseDragged(e -> {
            if (painting) {
                Point2D texel = texel(e.getPickResult());
                if (texel != null) painter.drag((int) texel.getX(), (int) texel.getY());
                return;
            }
            double sign = invert ? -1 : 1;
            spinY.setAngle(spinY.getAngle() + sign * (e.getSceneX() - lastX) * 0.6);
            spinX.setAngle(Math.max(-89, Math.min(89, spinX.getAngle() - sign * (e.getSceneY() - lastY) * 0.6)));
            lastX = e.getSceneX();
            lastY = e.getSceneY();
        });
        scene.setOnMouseReleased(e -> {
            if (painting) painter.release();
            painting = false;
        });
        scene.addEventHandler(ScrollEvent.SCROLL, e -> {
            double z = camera.getTranslateZ() * (e.getDeltaY() > 0 ? 0.9 : 1.1);
            camera.setTranslateZ(Math.max(-900, Math.min(-120, z)));
            e.consume();
        });
    }

    Node node() {
        return holder;
    }

    /** Ставит модель. {@code quads} пусто — плоская пластина; {@code ownRef} — ссылка на рисуемую текстуру. */
    void show(List<McAssets.Quad> quads, String ownRef, Function<String, Image> textures, int w, int h) {
        model.getChildren().clear();
        own.clear();
        others.clear();
        texW = Math.max(1, w);
        texH = Math.max(1, h);
        // Материал сглаживает: чтобы пиксели остались квадратными, отдаём ему текстуру в несколько раз больше.
        detail = Math.max(1, Math.min(8, 256 / Math.max(texW, texH)));
        image = new WritableImage(texW * detail, texH * detail);

        List<McAssets.Quad> list = quads.isEmpty() ? plate(w, h, ownRef) : quads;
        Map<String, List<McAssets.Quad>> groups = new LinkedHashMap<>();
        for (McAssets.Quad q : list) {
            boolean mine = same(q.texture(), ownRef);
            groups.computeIfAbsent((mine ? "1|" : "0|") + q.texture() + "|" + q.tinted() + "|" + q.shade(),
                    k -> new ArrayList<>()).add(q);
        }
        for (var e : groups.entrySet()) {
            McAssets.Quad first = e.getValue().get(0);
            boolean mine = same(first.texture(), ownRef);
            MeshView view = new MeshView(mesh(e.getValue()));
            view.setCullFace(CullFace.NONE);
            view.setMaterial(material(first, mine, textures));
            // Модель в единицах блока: 0..1, y вверх. На экране — центр в нуле, y вниз.
            view.getTransforms().addAll(new Scale(SIZE, -SIZE, SIZE), new Translate(-0.5, -0.5, -0.5));
            model.getChildren().add(view);
            if (mine) own.add(view);
        }
    }

    private static boolean same(String quadTexture, String ownRef) {
        if (quadTexture == null || ownRef == null) return false;
        String a = quadTexture.startsWith("minecraft:") ? quadTexture.substring(10) : quadTexture;
        String b = ownRef.startsWith("minecraft:") ? ownRef.substring(10) : ownRef;
        return a.equals(b);
    }

    private PhongMaterial material(McAssets.Quad q, boolean mine, Function<String, Image> textures) {
        double k = q.shade();
        Color tint = q.tinted() ? Color.web("#7cbd6b") : Color.WHITE;
        Color shade = new Color(tint.getRed() * k, tint.getGreen() * k, tint.getBlue() * k, 1);
        PhongMaterial m = new PhongMaterial(shade);
        m.setSpecularColor(Color.BLACK);
        Image map = mine ? image : textures.apply(q.texture());
        if (map != null) m.setDiffuseMap(map);
        return m;
    }

    private static TriangleMesh mesh(List<McAssets.Quad> quads) {
        TriangleMesh mesh = new TriangleMesh();
        for (McAssets.Quad q : quads) {
            int base = mesh.getPoints().size() / 3;
            int tex = mesh.getTexCoords().size() / 2;
            mesh.getPoints().addAll(q.pos());
            mesh.getTexCoords().addAll(q.uv());
            mesh.getFaces().addAll(base, tex, base + 1, tex + 1, base + 2, tex + 2,
                    base, tex, base + 2, tex + 2, base + 3, tex + 3);
        }
        return mesh;
    }

    /** Пластина с той же текстурой спереди и сзади, по пропорциям картинки. */
    private static List<McAssets.Quad> plate(int w, int h, String ref) {
        float k = 1f / Math.max(w, h);
        float hw = w * k / 2, hh = h * k / 2;
        float l = 0.5f - hw, r = 0.5f + hw, b = 0.5f - hh, t = 0.5f + hh;
        float[] uv = {0, 0, 1, 0, 1, 1, 0, 1};
        float[] back = {1, 0, 0, 0, 0, 1, 1, 1};
        List<McAssets.Quad> out = new ArrayList<>();
        out.add(new McAssets.Quad(new float[]{l, t, 0.5f, r, t, 0.5f, r, b, 0.5f, l, b, 0.5f}, uv, ref, false, 1));
        out.add(new McAssets.Quad(new float[]{r, t, 0.499f, l, t, 0.499f, l, b, 0.499f, r, b, 0.499f}, back, ref, false, 1));
        return out;
    }

    /** Перерисовывает материал из текущего кадра текстуры. */
    void update(PixelOps.Sheet sheet, int top, int rows) {
        if (image == null || sheet == null || sheet.w() != texW || rows != texH) return;
        PixelWriter out = image.getPixelWriter();
        for (int y = 0; y < rows; y++) {
            for (int x = 0; x < texW; x++) {
                int c = sheet.at(x, y + top);
                for (int j = 0; j < detail; j++) {
                    for (int i = 0; i < detail; i++) out.setArgb(x * detail + i, y * detail + j, c);
                }
            }
        }
    }

    /** Пиксель текстуры под курсором, в координатах кадра; null — мимо или другая текстура. */
    private Point2D texel(PickResult pick) {
        if (pick == null || !(pick.getIntersectedNode() instanceof MeshView view) || !own.contains(view)) return null;
        Point2D uv = pick.getIntersectedTexCoord();
        if (uv == null) return null;
        int x = (int) Math.floor(uv.getX() * texW);
        int y = (int) Math.floor(uv.getY() * texH);
        return new Point2D(Math.max(0, Math.min(texW - 1, x)), Math.max(0, Math.min(texH - 1, y)));
    }
}
