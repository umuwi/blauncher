package com.example.launcher.ui.studio;

import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.Sphere;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.transform.Rotate;

import java.util.HashMap;
import java.util.Map;

/**
 * Blender's transform handles for the studio: arrows to move along an axis, rings to turn about
 * one, axis lines with cubes to stretch, and a centre handle for the free version of each.
 *
 * <p>Built in Minecraft coordinates (y up) as a unit-sized widget; the studio places it on the
 * selection and scales it with the camera distance so it keeps its size on screen. Every handle
 * carries {@code "gizmo:<move|rotate|scale>:<X|Y|Z|free>"} as user data, which is how a click on
 * it is told apart from a click on the scene.
 */
final class StudioGizmo {

    static final Color X = Color.web("#E8505B");
    static final Color Y = Color.web("#6CC94F");
    static final Color Z = Color.web("#4F8BF0");
    static final Color HOVER = Color.web("#FFD34D");
    static final Color CENTER = Color.web("#F2F2F7");

    final Group root = new Group();
    private final Map<Node, PhongMaterial> base = new HashMap<>();
    private Node hovered;

    enum Kind { MOVE, ROTATE, SCALE }

    void build(Kind kind) {
        root.getChildren().clear();
        base.clear();
        hovered = null;
        for (char axis : new char[]{'X', 'Y', 'Z'}) {
            Color c = axis == 'X' ? X : axis == 'Y' ? Y : Z;
            Group handle = switch (kind) {
                case MOVE -> arrow(c);
                case ROTATE -> ring(c);
                case SCALE -> stretch(c);
            };
            orient(handle, axis);
            tag(handle, kind, String.valueOf(axis), c);
            root.getChildren().add(handle);
        }
        if (kind != Kind.ROTATE) {
            Node center = kind == Kind.MOVE ? new Sphere(0.09, 16) : new Box(0.16, 0.16, 0.16);
            Group g = new Group(center);
            tag(g, kind, "free", CENTER);
            root.getChildren().add(g);
        }
    }

    private void tag(Group handle, Kind kind, String axis, Color c) {
        String id = "gizmo:" + kind.name().toLowerCase() + ":" + axis;
        handle.setUserData(id);
        for (Node n : handle.getChildren()) {
            n.setUserData(id);
            if (n instanceof javafx.scene.shape.Shape3D s) {
                PhongMaterial m = flat(c);
                s.setMaterial(m);
                base.put(s, m);
            }
        }
    }

    /** Highlights the handle under the pointer, Blender-yellow; null clears it. */
    void hover(Node node) {
        Node handle = node == null ? null : node.getParent();
        if (handle == hovered) return;
        if (hovered instanceof Group g) {
            for (Node n : g.getChildren()) if (n instanceof javafx.scene.shape.Shape3D s) s.setMaterial(base.get(s));
        }
        hovered = handle != null && handle.getUserData() instanceof String id && id.startsWith("gizmo:") ? handle : null;
        if (hovered instanceof Group g) {
            PhongMaterial hot = flat(HOVER);
            for (Node n : g.getChildren()) if (n instanceof javafx.scene.shape.Shape3D s) s.setMaterial(hot);
        }
    }

    /** Unit vector of an axis in Minecraft coordinates. */
    static Point3D axisVector(char axis) {
        return axis == 'X' ? new Point3D(1, 0, 0) : axis == 'Y' ? new Point3D(0, 1, 0) : new Point3D(0, 0, 1);
    }

    // ------------------------------------------------------------------ shapes (built along +Y)

    private static Group arrow(Color c) {
        Cylinder shaft = new Cylinder(0.035, 0.78);
        shaft.setTranslateY(0.39 + 0.12);
        MeshView head = new MeshView(cone(0.1, 0.26, 20));
        head.setTranslateY(0.9);
        head.setCullFace(CullFace.NONE);
        return new Group(shaft, head);
    }

    private static Group stretch(Color c) {
        Cylinder shaft = new Cylinder(0.035, 0.8);
        shaft.setTranslateY(0.4 + 0.12);
        Box cube = new Box(0.17, 0.17, 0.17);
        cube.setTranslateY(1.0);
        return new Group(shaft, cube);
    }

    private static Group ring(Color c) {
        MeshView torus = new MeshView(torus(0.95, 0.035, 64, 10));
        torus.setCullFace(CullFace.NONE);
        return new Group(torus);
    }

    /** Turns a +Y shape onto the given axis. */
    private static void orient(Group g, char axis) {
        if (axis == 'X') g.getTransforms().add(new Rotate(-90, Rotate.Z_AXIS));
        if (axis == 'Z') g.getTransforms().add(new Rotate(90, Rotate.X_AXIS));
    }

    private static PhongMaterial flat(Color c) {
        PhongMaterial m = new PhongMaterial(c);
        m.setSpecularColor(Color.TRANSPARENT);
        return m;
    }

    /** A cone standing on y=0 with its tip at y=h. */
    static TriangleMesh cone(double r, double h, int n) {
        TriangleMesh m = new TriangleMesh();
        m.getTexCoords().addAll(0, 0);
        m.getPoints().addAll(0, (float) h, 0);   // tip
        m.getPoints().addAll(0, 0, 0);           // base centre
        for (int i = 0; i < n; i++) {
            double a = 2 * Math.PI * i / n;
            m.getPoints().addAll((float) (r * Math.cos(a)), 0, (float) (r * Math.sin(a)));
        }
        for (int i = 0; i < n; i++) {
            int p = 2 + i, q = 2 + (i + 1) % n;
            m.getFaces().addAll(0, 0, q, 0, p, 0);
            m.getFaces().addAll(1, 0, p, 0, q, 0);
        }
        return m;
    }

    /** A ring in the XZ plane (around +Y): radius R, tube radius r. */
    static TriangleMesh torus(double R, double r, int seg, int side) {
        TriangleMesh m = new TriangleMesh();
        m.getTexCoords().addAll(0, 0);
        for (int i = 0; i < seg; i++) {
            double u = 2 * Math.PI * i / seg;
            for (int j = 0; j < side; j++) {
                double v = 2 * Math.PI * j / side;
                double w = R + r * Math.cos(v);
                m.getPoints().addAll((float) (w * Math.cos(u)), (float) (r * Math.sin(v)), (float) (w * Math.sin(u)));
            }
        }
        for (int i = 0; i < seg; i++) {
            for (int j = 0; j < side; j++) {
                int a = i * side + j, b = ((i + 1) % seg) * side + j;
                int c = ((i + 1) % seg) * side + (j + 1) % side, d = i * side + (j + 1) % side;
                m.getFaces().addAll(a, 0, b, 0, c, 0, a, 0, c, 0, d, 0);
            }
        }
        return m;
    }
}
