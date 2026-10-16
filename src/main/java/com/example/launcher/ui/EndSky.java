package com.example.launcher.ui;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.util.Random;

/**
 * The launcher's living background: the End sky as Minecraft 1.21.9 draws it - a violet-black void
 * with faint stars, where every so often a pink-violet light source flares up somewhere in the sky
 * and the whole dimension briefly takes on its colour, then fades back.
 *
 * <p>The game flashes about once a minute; here it is every 7-15 seconds, gentler, and never over
 * the content: panels sit on top. Off when animations are off (Settings), like the game's
 * "Hide Sky Flashes" option.
 */
public final class EndSky extends Pane {

    private final Canvas stars = new Canvas();
    private final Region tint = new Region();
    private final Random random = new Random();
    /** Следующая вспышка — через Motion.later, а не PauseTransition: см. там, почему. */
    private java.util.concurrent.ScheduledFuture<?> next;
    private boolean running;
    private boolean light;

    /** A pale sky for light themes: the same flashes, softer, on a blue-lavender ground. */
    public void setLight(boolean on) {
        if (on == light && getBackground() != null) return;
        light = on;
        setBackground(new javafx.scene.layout.Background(new javafx.scene.layout.BackgroundFill(on
                ? new LinearGradient(0, 0, 0.3, 1, true, CycleMethod.NO_CYCLE,
                        new Stop(0, Color.web("#DCE3F2")), new Stop(0.6, Color.web("#E3E2F5")),
                        new Stop(1, Color.web("#D6DCEE")))
                : new LinearGradient(0, 0, 0.3, 1, true, CycleMethod.NO_CYCLE,
                        new Stop(0, Color.web("#1A1430")), new Stop(0.6, Color.web("#1E1638")),
                        new Stop(1, Color.web("#130F22"))), null, null)));
        tint.setStyle(on ? "-fx-background-color: rgba(150, 120, 255, 0.10);"
                : "-fx-background-color: rgba(176, 110, 255, 0.16);");
        layoutSky();
    }

    public EndSky() {
        setMouseTransparent(true);
        setPickOnBounds(false);
        setBackground(new javafx.scene.layout.Background(new javafx.scene.layout.BackgroundFill(
                new LinearGradient(0, 0, 0.3, 1, true, CycleMethod.NO_CYCLE,
                        new Stop(0, Color.web("#1A1430")), new Stop(0.6, Color.web("#1E1638")),
                        new Stop(1, Color.web("#130F22"))), null, null)));
        tint.setStyle("-fx-background-color: rgba(176, 110, 255, 0.16);");
        tint.setOpacity(0);
        tint.setMouseTransparent(true);
        getChildren().addAll(stars, tint);
        widthProperty().addListener((o, a, b) -> layoutSky());
        heightProperty().addListener((o, a, b) -> layoutSky());
    }

    public void start() {
        if (running) return;
        running = true;
        schedule(1.5, 3);
    }

    public void stop() {
        running = false;
        if (next != null) next.cancel(false);
    }

    private void schedule(double minSeconds, double maxSeconds) {
        if (next != null) next.cancel(false);
        next = Motion.later(minSeconds + random.nextDouble() * (maxSeconds - minSeconds), () -> {
            if (!running) return;
            if (Motion.isEnabled() && isVisible() && getScene() != null) flash();
            schedule(7, 15);
        });
    }

    private void layoutSky() {
        double w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        tint.resizeRelocate(0, 0, w, h);
        stars.setWidth(w);
        stars.setHeight(h);
        // painted once per size: the End's sky is still, only the light moves
        GraphicsContext g = stars.getGraphicsContext2D();
        g.clearRect(0, 0, w, h);
        Random r = new Random(1337);
        int count = (int) (w * h / 5200);
        for (int i = 0; i < count; i++) {
            double x = r.nextDouble() * w, y = r.nextDouble() * h;
            double a = 0.08 + r.nextDouble() * 0.22;
            g.setFill(light ? Color.rgb(110, 90, 190, a * 0.6)
                    : r.nextInt(5) == 0 ? Color.rgb(214, 170, 255, a) : Color.rgb(230, 225, 255, a));
            double size = r.nextInt(9) == 0 ? 2 : 1;
            g.fillRect(Math.round(x), Math.round(y), size, size);
        }
    }

    /** One flash: a soft light source swells at a random spot, the sky tints, both fade. */
    private void flash() {
        double w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        double radius = Math.max(w, h) * (0.36 + random.nextDouble() * 0.2);
        Circle glow = new Circle(w * (0.15 + random.nextDouble() * 0.7), h * (0.08 + random.nextDouble() * 0.55), radius);
        glow.setManaged(false);
        glow.setMouseTransparent(true);
        glow.setFill(light ? new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(200, 150, 255, 0.55)),
                new Stop(0.3, Color.rgb(170, 140, 255, 0.28)),
                new Stop(1, Color.rgb(150, 130, 255, 0.0)))
                : new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(255, 205, 255, 0.85)),
                new Stop(0.16, Color.rgb(228, 140, 255, 0.55)),
                new Stop(0.5, Color.rgb(150, 92, 250, 0.22)),
                new Stop(1, Color.rgb(110, 60, 220, 0.0))));
        glow.setOpacity(0);
        glow.setScaleX(0.85);
        glow.setScaleY(0.85);
        getChildren().add(1, glow);

        Interpolator out = Motion.EASE_OUT;
        Timeline rise = new Timeline(Motion.DECOR_FPS, new KeyFrame(Duration.millis(420),
                new KeyValue(glow.opacityProperty(), 1, out),
                new KeyValue(glow.scaleXProperty(), 1.0, out),
                new KeyValue(glow.scaleYProperty(), 1.0, out),
                new KeyValue(tint.opacityProperty(), 1, out)));
        Timeline fade = new Timeline(Motion.DECOR_FPS, new KeyFrame(Duration.millis(2600),
                new KeyValue(glow.opacityProperty(), 0, Interpolator.EASE_IN),
                new KeyValue(glow.scaleXProperty(), 1.12, Interpolator.EASE_OUT),
                new KeyValue(glow.scaleYProperty(), 1.12, Interpolator.EASE_OUT),
                new KeyValue(tint.opacityProperty(), 0, Interpolator.EASE_IN)));
        SequentialTransition all = new SequentialTransition(rise, new PauseTransition(Duration.millis(260)), fade);
        all.setOnFinished(e -> getChildren().remove(glow));
        all.play();
    }
}
