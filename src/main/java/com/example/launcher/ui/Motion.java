package com.example.launcher.ui;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

import java.util.List;
import java.util.Set;

/**
 * The launcher's motion: short, decelerating, and always explaining something - a screen arriving
 * from the side it was reached from, a list appearing as a list, a press being felt. Every
 * animation starts from a visible state and ends where layout already put the node, so turning
 * motion off (Settings) or interrupting it never leaves anything hidden or misplaced.
 */
public final class Motion {

    /**
     * Паузы между повторами декоративных анимаций — здесь, а не в PauseTransition/Timeline:
     * пока в JavaFX идёт хоть одна анимация, даже стоящая на месте, он считает и рисует кадры
     * 60 раз в секунду. 16.10.2026 профиль простоя: переливу «Играть» (Timeline на 6 с, из них
     * 4,7 с без движения) и паузам неба Края хватало, чтобы лаунчер не засыпал никогда и
     * держал видеокарту, пока рядом тормозил Blender.
     */
    private static final java.util.concurrent.ScheduledExecutorService TIMER =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "motion-timer");
                t.setDaemon(true);
                return t;
            });

    /**
     * Частота кадров фоновых красот (перелив «Играть», вспышки неба Края). Они идут почти половину
     * времени простоя, и при 60 кадрах/с лаунчер всё это время рисовал с полной частотой; на глаз
     * плавное медленное свечение на 30 не отличить, а видеокарте вдвое легче (16.10.2026).
     */
    public static final double DECOR_FPS = 30;

    /** Выполнить на потоке JavaFX через {@code seconds}, не держа анимацию на время ожидания. */
    public static java.util.concurrent.ScheduledFuture<?> later(double seconds, Runnable onFx) {
        return TIMER.schedule(() -> javafx.application.Platform.runLater(onFx),
                Math.round(seconds * 1000), java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * Периодический опрос на потоке JavaFX без вечного Timeline (почему — см. {@link #TIMER}).
     * Следующий тик не ставится в очередь, пока предыдущий не выполнился: подвисший поток
     * интерфейса не копит пачку одинаковых опросов.
     */
    public static final class Every {
        private final double seconds;
        private final Runnable onFx;
        private final java.util.concurrent.atomic.AtomicBoolean queued = new java.util.concurrent.atomic.AtomicBoolean();
        private java.util.concurrent.ScheduledFuture<?> task;

        public Every(double seconds, Runnable onFx) {
            this.seconds = seconds;
            this.onFx = onFx;
        }

        public void play() {
            if (task != null) return;
            long ms = Math.round(seconds * 1000);
            task = TIMER.scheduleWithFixedDelay(() -> {
                if (!queued.compareAndSet(false, true)) return;
                javafx.application.Platform.runLater(() -> {
                    queued.set(false);
                    onFx.run();
                });
            }, ms, ms, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        public void pause() {
            if (task != null) task.cancel(false);
            task = null;
        }

        public void stop() {
            pause();
        }
    }

    /** Повторяющийся перелив кнопки «Играть»: сам проход — анимация, ожидание — {@link #later}. */
    public static final class Shimmer {
        private final Timeline sweep;
        private java.util.concurrent.ScheduledFuture<?> wait;
        private boolean on;

        private Shimmer(Timeline sweep) {
            this.sweep = sweep;
            sweep.setOnFinished(e -> { if (on) wait = later(4.7, this::sweepOnce); });
        }

        public void play() {
            if (on) return;
            on = true;
            wait = later(2, this::sweepOnce);
        }

        public void pause() {
            on = false;
            if (wait != null) wait.cancel(false);
            sweep.stop();
        }

        private void sweepOnce() {
            if (on) sweep.playFromStart();
        }
    }

    /** cubic-bezier(0.16, 1, 0.3, 1): a confident arrival, no bounce. */
    public static final Interpolator EASE_OUT = Interpolator.SPLINE(0.16, 1, 0.3, 1);

    private static volatile boolean enabled = true;

    private static final Set<String> LIFT_CLASSES = Set.of(
            "build-card", "tool-card", "bg-tile", "studio-tile", "theme-swatch");

    private Motion() {}

    public static void setEnabled(boolean on) {
        enabled = on;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    // ------------------------------------------------------------------ screens and lists

    /** A screen arriving: rises (or drops) 14px and fades in over 260ms. */
    public static void enterScreen(Node screen, int direction) {
        if (!enabled || screen == null) return;
        screen.setOpacity(0);
        screen.setTranslateY(28 * Integer.signum(direction == 0 ? 1 : direction));
        play(screen, 480, 0, 0, 1);
        // the screen's own blocks (title, panels) follow one after another
        if (screen instanceof Parent p) {
            int i = 0;
            for (Node child : p.getChildrenUnmodifiable()) {
                child.setOpacity(0);
                child.setTranslateY(18);
                play(child, 460, 60 + Math.min(420, i++ * 70), 0, 1);
            }
        }
    }

    /**
     * Siblings appearing as a list: each one 200ms, 35ms apart, the whole batch capped at 300ms of
     * delay so a long list never makes anyone wait.
     */
    public static void stagger(List<? extends Node> nodes) {
        if (!enabled || nodes == null) return;
        int i = 0;
        for (Node n : nodes) {
            double delay = 120 + Math.min(600, i++ * 55);
            n.setOpacity(0);
            n.setTranslateY(16);
            n.setScaleX(0.97);
            n.setScaleY(0.97);
            play(n, 420, delay, 0, 1);
            ScaleTransition grow = new ScaleTransition(Duration.millis(420), n);
            grow.setToX(1);
            grow.setToY(1);
            grow.setDelay(Duration.millis(delay));
            grow.setInterpolator(EASE_OUT);
            grow.play();
        }
    }

    private static void play(Node n, double ms, double delay, double toY, double toOpacity) {
        FadeTransition fade = new FadeTransition(Duration.millis(ms), n);
        fade.setToValue(toOpacity);
        fade.setInterpolator(EASE_OUT);
        TranslateTransition move = new TranslateTransition(Duration.millis(ms), n);
        move.setToY(toY);
        move.setInterpolator(EASE_OUT);
        ParallelTransition both = new ParallelTransition(fade, move);
        both.setDelay(Duration.millis(delay));
        both.setOnFinished(e -> {
            n.setOpacity(toOpacity);
            n.setTranslateY(toY);
        });
        both.play();
    }

    // ------------------------------------------------------------------ dialogs and toasts

    /** A dialog opening from its centre: the scrim fades in, the card scales 0.96 -> 1. */
    public static void openDialog(Node scrim, Node card) {
        if (!enabled) return;
        scrim.setOpacity(0);
        FadeTransition fade = new FadeTransition(Duration.millis(260), scrim);
        fade.setToValue(1);
        fade.play();
        if (card != null) {
            card.setScaleX(0.92);
            card.setScaleY(0.92);
            ScaleTransition grow = new ScaleTransition(Duration.millis(420), card);
            grow.setToX(1);
            grow.setToY(1);
            grow.setInterpolator(EASE_OUT);
            grow.play();
        }
    }

    /** A toast sliding up into place. */
    public static void toastIn(Node toast) {
        if (!enabled) return;
        toast.setOpacity(0);
        toast.setTranslateY(30);
        play(toast, 380, 0, 0, 1);
    }

    // ------------------------------------------------------------------ rail

    /** Moves the rail's accent marker to sit beside {@code target}. */
    public static void moveMarker(Region marker, Node target) {
        if (marker == null || target == null || target.getParent() == null) return;
        // unmanaged, so nobody else sizes it
        if (marker.getWidth() == 0) marker.resize(marker.getPrefWidth(), marker.getPrefHeight());
        double y = target.getBoundsInParent().getMinY() + (target.getBoundsInParent().getHeight() - marker.getHeight()) / 2;
        if (marker.getOpacity() == 0 || !enabled) {
            marker.setTranslateY(y);
            marker.setOpacity(1);
            return;
        }
        Timeline slide = new Timeline(new KeyFrame(Duration.millis(420),
                new KeyValue(marker.translateYProperty(), y, EASE_OUT)));
        slide.play();
    }

    // ------------------------------------------------------------------ feedback

    /**
     * Press and hover feedback for the whole window: buttons sink 3% while pressed, cards and
     * tiles lift 1.5% under the pointer. Installed once on the scene, so every control built later
     * in code gets it too.
     */
    public static void installFeedback(Scene scene) {
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            ButtonBase b = buttonOf(e.getTarget());
            if (b != null && !b.isDisabled()) scale(b, 0.95, 140);
        });
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            ButtonBase b = buttonOf(e.getTarget());
            if (b != null) scale(b, 1, 320);
        });
        scene.addEventFilter(MouseEvent.MOUSE_ENTERED_TARGET, e -> {
            if (e.getTarget() instanceof Node n && lifts(n)) scale(n, 1.035, 260);
            if (e.getTarget() instanceof Node n && n.getStyleClass().contains("rail-btn")) railIcon(n, 1.18);
        });
        scene.addEventFilter(MouseEvent.MOUSE_EXITED_TARGET, e -> {
            if (e.getTarget() instanceof Node n && lifts(n)) scale(n, 1, 260);
            if (e.getTarget() instanceof Node n && n.getStyleClass().contains("rail-btn")) railIcon(n, 1);
        });
    }

    private static boolean lifts(Node n) {
        for (String c : n.getStyleClass()) if (LIFT_CLASSES.contains(c)) return true;
        return false;
    }

    private static ButtonBase buttonOf(Object target) {
        for (Node n = target instanceof Node t ? t : null; n != null; n = n.getParent()) {
            if (n instanceof ButtonBase b) return b.getStyleClass().contains("rail-btn") ? null : b;
        }
        return null;
    }

    private static void scale(Node n, double to, double ms) {
        if (!enabled) {
            n.setScaleX(1);
            n.setScaleY(1);
            return;
        }
        ScaleTransition t = new ScaleTransition(Duration.millis(ms), n);
        t.setToX(to);
        t.setToY(to);
        t.setInterpolator(EASE_OUT);
        t.play();
    }

    // ------------------------------------------------------------------ the Play moment

    /**
     * The one authored moment: pressing Play sends a ring of light out from the point of the click
     * across the button, clipped to its shape, gone in 520ms.
     */
    public static void ripple(Region host, Parent layer, double x, double y) {
        if (!enabled || !(layer instanceof javafx.scene.layout.Pane pane)) return;
        double w = host.getWidth(), h = host.getHeight();
        double reach = Math.hypot(Math.max(x, w - x), Math.max(y, h - y));
        Circle ring = new Circle(x, y, 1);
        ring.setManaged(false);
        ring.setMouseTransparent(true);
        ring.setFill(new RadialGradient(0, 0, 0.5, 0.5, 0.5, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(255, 255, 255, 0.0)),
                new Stop(0.7, Color.rgb(255, 255, 255, 0.10)),
                new Stop(1, Color.rgb(255, 255, 255, 0.35))));
        Rectangle clip = new Rectangle(w, h);
        clip.setArcWidth(16);
        clip.setArcHeight(16);
        pane.setClip(clip);
        pane.getChildren().add(ring);
        Timeline grow = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(ring.radiusProperty(), 1), new KeyValue(ring.opacityProperty(), 1)),
                new KeyFrame(Duration.millis(850), new KeyValue(ring.radiusProperty(), reach, EASE_OUT),
                        new KeyValue(ring.opacityProperty(), 0, Interpolator.EASE_IN)));
        grow.setOnFinished(e -> pane.getChildren().remove(ring));
        grow.play();
    }

    // ------------------------------------------------------------------ scrolling

    private static final String SCROLL_TARGET = "motion.scrollTarget";
    /** Put in a ScrollPane's properties to keep the smooth wheel scrolling off it. */
    public static final String NO_SCROLL = "motion.noScroll";

    /**
     * Wheel scrolling that glides instead of jumping: each notch moves the target, and the view
     * eases there in 260ms; notches that arrive mid-glide extend it. Whatever scrolls by itself is
     * left alone - 3D views zoom with the wheel, text areas and lists scroll their own content, and
     * the studio's number fields change their value on the wheel.
     */
    public static void installSmoothScroll(javafx.scene.Scene scene) {
        scene.addEventFilter(javafx.scene.input.ScrollEvent.SCROLL, e -> {
            if (!enabled || e.getDeltaY() == 0 || e.isControlDown()) return;
            javafx.scene.control.ScrollPane pane = null;
            for (Node n = e.getTarget() instanceof Node t ? t : null; n != null; n = n.getParent()) {
                if (n instanceof javafx.scene.SubScene || n instanceof javafx.scene.control.TextInputControl
                        || n instanceof javafx.scene.control.ListView || n instanceof javafx.scene.control.ComboBoxBase) {
                    return;
                }
                // a page that must stand still (Home) handles the wheel itself
                if (n instanceof javafx.scene.control.ScrollPane sp && sp.getProperties().containsKey(NO_SCROLL)) return;
                if (n instanceof javafx.scene.control.ScrollPane sp && canScroll(sp)) {
                    pane = sp;
                    break;
                }
            }
            if (pane == null) return;
            e.consume();
            glide(pane, e.getDeltaY());
        });
    }

    private static boolean canScroll(javafx.scene.control.ScrollPane sp) {
        Node content = sp.getContent();
        if (content == null) return false;
        double contentH = content.getLayoutBounds().getHeight();
        double viewH = sp.getViewportBounds().getHeight();
        return contentH > viewH + 1;
    }

    private static void glide(javafx.scene.control.ScrollPane sp, double deltaY) {
        double contentH = sp.getContent().getLayoutBounds().getHeight();
        double viewH = sp.getViewportBounds().getHeight();
        double range = Math.max(1, contentH - viewH);
        Object stored = sp.getProperties().get(SCROLL_TARGET);
        double from = stored instanceof Double d && isGliding(sp) ? d : sp.getVvalue();
        double span = sp.getVmax() - sp.getVmin();
        double target = from - deltaY * 1.4 / range * span;
        target = Math.max(sp.getVmin(), Math.min(sp.getVmax(), target));
        sp.getProperties().put(SCROLL_TARGET, target);
        Object running = sp.getProperties().get("motion.scrollAnim");
        if (running instanceof Timeline t) t.stop();
        Timeline t = new Timeline(new KeyFrame(Duration.millis(420),
                new KeyValue(sp.vvalueProperty(), target, EASE_OUT)));
        t.setOnFinished(ev -> sp.getProperties().remove("motion.scrollAnim"));
        sp.getProperties().put("motion.scrollAnim", t);
        t.play();
    }

    private static boolean isGliding(javafx.scene.control.ScrollPane sp) {
        return sp.getProperties().get("motion.scrollAnim") instanceof Timeline;
    }

    // ------------------------------------------------------------------ more moments

    /** The rail icon grows under the pointer; the label stays put. */
    private static void railIcon(Node railButton, double to) {
        Node icon = railButton.lookup(".rail-icon");
        if (icon != null) scale(icon, to, 220);
    }

    /** A switch that was just flipped bumps once, so the change is felt. */
    public static void installToggleBump(javafx.scene.Scene scene) {
        scene.addEventHandler(javafx.event.ActionEvent.ACTION, e -> {
            if (!enabled || !(e.getTarget() instanceof javafx.scene.control.ToggleButton t)) return;
            if (!t.getStyleClass().contains("switch-toggle")) return;
            t.setScaleX(1.14);
            t.setScaleY(1.14);
            scale(t, 1, 380);
        });
    }

    /**
     * A band of light sweeps across the Play button every few seconds - the End flash's light,
     * passing over the one action the launcher exists for.
     */
    public static Shimmer playShimmer(Region host, javafx.scene.layout.Pane layer) {
        javafx.scene.shape.Rectangle band = new javafx.scene.shape.Rectangle(90, 200);
        band.setManaged(false);
        band.setMouseTransparent(true);
        band.setRotate(18);
        band.setFill(new javafx.scene.paint.LinearGradient(0, 0, 1, 0, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.rgb(255, 255, 255, 0)), new Stop(0.5, Color.rgb(255, 255, 255, 0.28)),
                new Stop(1, Color.rgb(255, 255, 255, 0))));
        band.setOpacity(0);
        layer.getChildren().add(band);
        Timeline sweep = new Timeline(DECOR_FPS,
                new KeyFrame(Duration.ZERO, e -> {
                    double w = host.getWidth(), h = host.getHeight();
                    javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle(w, h);
                    clip.setArcWidth(16);
                    clip.setArcHeight(16);
                    layer.setClip(clip);
                    band.setHeight(h * 3);
                    band.setLayoutY(-h);
                    band.setOpacity(enabled ? 1 : 0);
                }, new KeyValue(band.layoutXProperty(), -160)),
                new KeyFrame(Duration.millis(1300), new KeyValue(band.layoutXProperty(), 1400, Interpolator.EASE_BOTH)));
        Shimmer shimmer = new Shimmer(sweep);
        shimmer.play();
        return shimmer;
    }

    /**
     * Theme changes cross-fade: a still of the old look lies over the window and dissolves, so the
     * colours flow into the new theme instead of jumping.
     */
    public static void crossFade(javafx.scene.Scene scene) {
        if (!enabled || scene == null || !(scene.getRoot() instanceof javafx.scene.layout.StackPane root)) return;
        javafx.scene.image.WritableImage still = root.snapshot(null, null);
        javafx.scene.image.ImageView cover = new javafx.scene.image.ImageView(still);
        cover.setManaged(false);
        cover.setMouseTransparent(true);
        root.getChildren().add(cover);
        FadeTransition fade = new FadeTransition(Duration.millis(650), cover);
        fade.setFromValue(1);
        fade.setToValue(0);
        fade.setInterpolator(Interpolator.EASE_BOTH);
        fade.setOnFinished(e -> root.getChildren().remove(cover));
        fade.play();
    }
}
