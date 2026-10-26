package com.example.launcher.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.StrokeLineCap;

/**
 * Маленькие флаги стран (20×14) для выбора дата-центра на вкладке «Купить сервер» (26.10.2026).
 * Рисуем фигурами, а не картинками: эмодзи-флаги Windows показывает буквами («RU»), а качать
 * картинки с чужих серверов ради 27 значков не хочется. Гербы и мелкие детали опущены — флаг
 * должен узнаваться с первого взгляда, как в кабинете serv.host, не более.
 */
public final class Flags {

    private static final double W = 20, H = 14;

    private Flags() {
    }

    /** Флаг по коду страны (ru, de, …); неизвестный код — серая плашка. */
    public static Node of(String code) {
        Group g = new Group();
        switch (code) {
            case "ru" -> h(g, "#ffffff", "#0039a6", "#d52b1e");
            case "de" -> h(g, "#000000", "#dd0000", "#ffce00");
            case "nl" -> h(g, "#ae1c28", "#ffffff", "#21468b");
            case "at" -> h(g, "#c8102e", "#ffffff", "#c8102e");
            case "ee" -> h(g, "#0072ce", "#000000", "#ffffff");
            case "lt" -> h(g, "#fdb913", "#006a44", "#c1272d");
            case "hu" -> h(g, "#ce2939", "#ffffff", "#477050");
            case "bg" -> h(g, "#ffffff", "#00966e", "#d62612");
            case "rs" -> h(g, "#c6363c", "#0c4076", "#ffffff");
            case "sk" -> h(g, "#ffffff", "#0b4ea2", "#ee1c25");
            case "lv" -> {
                g.getChildren().add(rect(0, 0, W, H, "#9e3039"));
                g.getChildren().add(rect(0, H * 0.4, W, H * 0.2, "#ffffff"));
            }
            case "by" -> {
                g.getChildren().add(rect(0, 0, W, H * 2 / 3, "#c8313e"));
                g.getChildren().add(rect(0, H * 2 / 3, W, H / 3, "#4aa657"));
                g.getChildren().add(rect(0, 0, 3, H, "#ffffff"));
            }
            case "fr" -> v(g, "#0055a4", "#ffffff", "#ef4135");
            case "it" -> v(g, "#009246", "#ffffff", "#ce2b37");
            case "be" -> v(g, "#000000", "#fdda24", "#ef3340");
            case "ro" -> v(g, "#002b7f", "#fcd116", "#ce1126");
            case "ca" -> {
                g.getChildren().add(rect(0, 0, W, H, "#ffffff"));
                g.getChildren().add(rect(0, 0, 5, H, "#d52b1e"));
                g.getChildren().add(rect(W - 5, 0, 5, H, "#d52b1e"));
                g.getChildren().add(poly("#d52b1e", 10, 3.5, 12.5, 7, 10, 10.5, 7.5, 7));
            }
            case "fi" -> nordic(g, "#ffffff", "#003580", null);
            case "se" -> nordic(g, "#006aa7", "#fecc00", null);
            case "dk" -> nordic(g, "#c8102e", "#ffffff", null);
            case "no" -> nordic(g, "#ba0c2f", "#ffffff", "#00205b");
            case "ch" -> {
                g.getChildren().add(rect(0, 0, W, H, "#da291c"));
                g.getChildren().add(rect(8.75, 3, 2.5, 8, "#ffffff"));
                g.getChildren().add(rect(6, 5.75, 8, 2.5, "#ffffff"));
            }
            case "cz" -> {
                g.getChildren().add(rect(0, 0, W, H / 2, "#ffffff"));
                g.getChildren().add(rect(0, H / 2, W, H / 2, "#d7141a"));
                g.getChildren().add(poly("#11457e", 0, 0, W / 2, H / 2, 0, H));
            }
            case "tr" -> {
                g.getChildren().add(rect(0, 0, W, H, "#e30a17"));
                g.getChildren().add(circle(7.5, 7, 3.6, "#ffffff"));
                g.getChildren().add(circle(8.5, 7, 2.9, "#e30a17"));
                g.getChildren().add(circle(12.3, 7, 1.1, "#ffffff"));
            }
            case "gb" -> {
                g.getChildren().add(rect(0, 0, W, H, "#012169"));
                g.getChildren().addAll(line(0, 0, W, H, 3, "#ffffff"), line(W, 0, 0, H, 3, "#ffffff"));
                g.getChildren().addAll(line(0, 0, W, H, 1, "#c8102e"), line(W, 0, 0, H, 1, "#c8102e"));
                g.getChildren().add(rect(0, 5, W, 4, "#ffffff"));
                g.getChildren().add(rect(8, 0, 4, H, "#ffffff"));
                g.getChildren().add(rect(0, 5.75, W, 2.5, "#c8102e"));
                g.getChildren().add(rect(8.75, 0, 2.5, H, "#c8102e"));
            }
            case "us" -> {
                for (int i = 0; i < 7; i++) {
                    g.getChildren().add(rect(0, i * 2, W, 1.08, i % 2 == 0 ? "#b22234" : "#ffffff"));
                    g.getChildren().add(rect(0, i * 2 + 1.08, W, 0.92, "#ffffff"));
                }
                g.getChildren().add(rect(0, 0, 9, 7.5, "#3c3b6e"));
            }
            default -> g.getChildren().add(rect(0, 0, W, H, "#9a9aa5"));
        }
        // Края — по прямоугольнику флага: диагонали британского флага иначе вылезали бы за него.
        Rectangle clip = new Rectangle(W, H);
        clip.setArcWidth(4);
        clip.setArcHeight(4);
        g.setClip(clip);
        Rectangle frame = new Rectangle(W, H);
        frame.setFill(Color.TRANSPARENT);
        frame.setStroke(Color.web("#000000", 0.18));
        frame.setStrokeWidth(0.6);
        frame.setArcWidth(4);
        frame.setArcHeight(4);
        frame.setMouseTransparent(true);
        return new Group(g, frame);
    }

    private static void h(Group g, String a, String b, String c) {
        g.getChildren().addAll(rect(0, 0, W, H / 3, a), rect(0, H / 3, W, H / 3, b), rect(0, H * 2 / 3, W, H / 3 + 0.01, c));
    }

    private static void v(Group g, String a, String b, String c) {
        g.getChildren().addAll(rect(0, 0, W / 3, H, a), rect(W / 3, 0, W / 3, H, b), rect(W * 2 / 3, 0, W / 3 + 0.01, H, c));
    }

    /** Скандинавский крест, смещённый к древку; inner — второй, узкий крест (Норвегия). */
    private static void nordic(Group g, String bg, String cross, String inner) {
        g.getChildren().add(rect(0, 0, W, H, bg));
        g.getChildren().add(rect(0, 5, W, 4, cross));
        g.getChildren().add(rect(5.5, 0, 4, H, cross));
        if (inner != null) {
            g.getChildren().add(rect(0, 6, W, 2, inner));
            g.getChildren().add(rect(6.5, 0, 2, H, inner));
        }
    }

    private static Rectangle rect(double x, double y, double w, double h, String color) {
        Rectangle r = new Rectangle(x, y, w, h);
        r.setFill(Color.web(color));
        return r;
    }

    private static Polygon poly(String color, double... points) {
        Polygon p = new Polygon(points);
        p.setFill(Color.web(color));
        return p;
    }

    private static Circle circle(double x, double y, double r, String color) {
        Circle c = new Circle(x, y, r);
        c.setFill(Color.web(color));
        return c;
    }

    private static Line line(double x1, double y1, double x2, double y2, double width, String color) {
        Line l = new Line(x1, y1, x2, y2);
        l.setStroke(Color.web(color));
        l.setStrokeWidth(width);
        l.setStrokeLineCap(StrokeLineCap.BUTT);
        return l;
    }
}
