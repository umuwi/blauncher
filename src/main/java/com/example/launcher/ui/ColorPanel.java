package com.example.launcher.ui;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * An inline colour picker laid out the way Figma and Photoshop do it: a saturation/brightness
 * field, a hue strip under it, the hex value, then swatches - ready-made skin tones first,
 * the colours just used after them.
 *
 * <p>Replaces JavaFX's ColorPicker in the skin editor: that one hides everything behind a combo
 * box and a popup, so every colour change was three clicks and a window over the canvas.
 */
public final class ColorPanel {

    private static final double FIELD_W = 196;
    private static final double FIELD_H = 132;
    private static final double HUE_H = 14;

    /** Skin tones, hair, eyes and cloth: the colours a skin is mostly made of. */
    private static final String[] PRESETS = {
            "#f9d5bb", "#e8b090", "#d9a47e", "#b97a57", "#8d5a3b", "#5c3a24",
            "#2b1d14", "#6b4a2b", "#c9a15c", "#e8d7a6", "#ffffff", "#9aa3ad",
            "#3b4a7a", "#2b4d8c", "#2fb5a6", "#3f8f3a", "#b5302f", "#e07a2e",
            "#e8c44a", "#7b4fb3", "#d65c9b", "#3a3a3a", "#1e1e1e", "#000000"};

    private final Canvas field = new Canvas(FIELD_W, FIELD_H);
    private final Canvas hueStrip = new Canvas(FIELD_W, HUE_H);
    private final TextField hex = new TextField();
    private final Region preview = new Region();
    private final FlowPane recentPane = new FlowPane(4, 4);
    private final List<Color> recent = new ArrayList<>();
    private final VBox root;
    private final Consumer<Color> onChange;

    private double hue;
    private double sat;
    private double bri;

    public ColorPanel(Color initial, Consumer<Color> onChange, String recentLabel) {
        this.onChange = onChange;
        hex.getStyleClass().addAll("text-input", "mono");
        hex.setPrefColumnCount(7);
        hex.setOnAction(e -> applyHex());
        hex.focusedProperty().addListener((o, was, now) -> { if (!now) applyHex(); });
        preview.setMinSize(30, 30);
        preview.setPrefSize(30, 30);

        field.setOnMousePressed(e -> pickField(e.getX(), e.getY()));
        field.setOnMouseDragged(e -> pickField(e.getX(), e.getY()));
        hueStrip.setOnMousePressed(e -> pickHue(e.getX()));
        hueStrip.setOnMouseDragged(e -> pickHue(e.getX()));
        field.setStyle("-fx-cursor: crosshair;");
        hueStrip.setStyle("-fx-cursor: hand;");

        FlowPane presets = new FlowPane(4, 4);
        presets.setPrefWrapLength(FIELD_W);
        for (String c : PRESETS) presets.getChildren().add(swatch(Color.web(c)));
        recentPane.setPrefWrapLength(FIELD_W);

        Label recentTitle = new Label(recentLabel);
        recentTitle.getStyleClass().add("toggle-sub");

        HBox hexRow = new HBox(8, preview, hex);
        hexRow.setAlignment(Pos.CENTER_LEFT);

        root = new VBox(8, field, hueStrip, hexRow, presets, recentTitle, recentPane);
        root.setMaxWidth(FIELD_W);
        drawHue();
        set(initial, false);
    }

    public Node node() {
        return root;
    }

    /** Sets the colour from outside (the eyedropper), without reporting it back. */
    public void set(Color c, boolean report) {
        hue = c.getHue();
        sat = c.getSaturation();
        bri = c.getBrightness();
        refresh(report);
    }

    /** Adds a colour to the "recently used" row. */
    public void remember(Color c) {
        recent.removeIf(r -> r.equals(c));
        recent.add(0, c);
        while (recent.size() > 16) recent.remove(recent.size() - 1);
        recentPane.getChildren().setAll(recent.stream().map(this::swatch).toList());
    }

    public Color color() {
        return Color.hsb(hue, sat, bri);
    }

    private void pickField(double x, double y) {
        sat = clamp(x / FIELD_W);
        bri = 1 - clamp(y / FIELD_H);
        refresh(true);
    }

    private void pickHue(double x) {
        hue = clamp(x / FIELD_W) * 359.999;
        refresh(true);
    }

    private void applyHex() {
        String t = hex.getText() == null ? "" : hex.getText().trim();
        if (!t.startsWith("#")) t = "#" + t;
        try {
            if (!t.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException();
            set(Color.web(t), true);
        } catch (Exception e) {
            hex.setText(toHex(color()));
        }
    }

    private void refresh(boolean report) {
        drawField();
        drawHueMarker();
        Color c = color();
        hex.setText(toHex(c));
        preview.setStyle("-fx-background-color: " + toHex(c) + "; -fx-background-radius: 7;"
                + "-fx-border-color: rgba(127,127,127,0.45); -fx-border-radius: 7;");
        if (report) onChange.accept(c);
    }

    private void drawField() {
        WritableImage img = new WritableImage((int) FIELD_W, (int) FIELD_H);
        PixelWriter pw = img.getPixelWriter();
        for (int y = 0; y < FIELD_H; y++) {
            for (int x = 0; x < FIELD_W; x++) {
                pw.setColor(x, y, Color.hsb(hue, x / (FIELD_W - 1), 1 - y / (FIELD_H - 1)));
            }
        }
        GraphicsContext g = field.getGraphicsContext2D();
        g.drawImage(img, 0, 0);
        double mx = sat * FIELD_W, my = (1 - bri) * FIELD_H;
        g.setLineWidth(2);
        g.setStroke(Color.WHITE);
        g.strokeOval(mx - 6, my - 6, 12, 12);
        g.setLineWidth(1);
        g.setStroke(Color.rgb(0, 0, 0, 0.6));
        g.strokeOval(mx - 7.5, my - 7.5, 15, 15);
    }

    private WritableImage hueImage;

    private void drawHue() {
        hueImage = new WritableImage((int) FIELD_W, (int) HUE_H);
        PixelWriter pw = hueImage.getPixelWriter();
        for (int x = 0; x < FIELD_W; x++) {
            Color c = Color.hsb(x / FIELD_W * 360, 1, 1);
            for (int y = 0; y < HUE_H; y++) pw.setColor(x, y, c);
        }
    }

    private void drawHueMarker() {
        GraphicsContext g = hueStrip.getGraphicsContext2D();
        g.drawImage(hueImage, 0, 0);
        double x = hue / 360 * FIELD_W;
        g.setFill(Color.WHITE);
        g.fillRect(x - 2, 0, 4, HUE_H);
        g.setStroke(Color.rgb(0, 0, 0, 0.6));
        g.strokeRect(x - 2.5, 0.5, 5, HUE_H - 1);
    }

    private Node swatch(Color c) {
        Region r = new Region();
        r.setMinSize(22, 22);
        r.setPrefSize(22, 22);
        r.setStyle("-fx-background-color: " + toHex(c) + "; -fx-background-radius: 5;"
                + "-fx-border-color: rgba(127,127,127,0.45); -fx-border-radius: 5; -fx-cursor: hand;");
        r.setOnMouseClicked(e -> set(c, true));
        return r;
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }

    static String toHex(Color c) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", (int) Math.round(c.getRed() * 255),
                (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
    }
}
