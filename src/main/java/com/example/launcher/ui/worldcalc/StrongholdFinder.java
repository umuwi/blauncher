package com.example.launcher.ui.worldcalc;

import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Finds a stronghold from two throws of an eye of ender: the lines of flight cross above it. */
public final class StrongholdFinder {

    /** A guess this loose still points the way, but the player should throw again from farther off. */
    private static final double LOOSE_SPREAD = 100;

    /** One throw's inputs. */
    private final class Side {
        final TextField x = new TextField();
        final TextField z = new TextField();
        final TextField yaw = new TextField();

        VBox build(String titleKey) {
            HBox fields = new HBox(8, CalcUi.field("X", x, StrongholdFinder.this::render),
                    CalcUi.field("Z", z, StrongholdFinder.this::render),
                    CalcUi.field(host.tr("calc_yaw"), yaw, StrongholdFinder.this::render));
            VBox box = CalcUi.panel(CalcUi.title(host.tr(titleKey)), fields, CalcUi.pasteButton(host, this::paste));
            HBox.setHgrow(box, Priority.ALWAYS);
            box.setPrefWidth(300);
            return box;
        }

        void paste() {
            WorldCalc.Location at = CalcUi.pasteLocation(host);
            if (at == null) return;
            if (at.dimension() != null && !"overworld".equals(at.dimension())) {
                host.toast(host.tr("calc_eye_overworld_only"), "info");
                return;
            }
            x.setText(trim(at.x()));
            z.setText(trim(at.z()));
            yaw.setText(trim(at.yaw()));
        }

        WorldCalc.Throw value() {
            Double px = WorldCalc.number(x.getText());
            Double pz = WorldCalc.number(z.getText());
            Double a = WorldCalc.number(yaw.getText());
            return px == null || pz == null || a == null ? null : new WorldCalc.Throw(px, pz, a);
        }
    }

    private final CalcHost host;
    private final Side first = new Side();
    private final Side second = new Side();
    private final VBox results = CalcUi.panel();
    private final Node root;

    public StrongholdFinder(CalcHost host) {
        this.host = host;
        HBox sides = new HBox(12, first.build("calc_throw_1"), second.build("calc_throw_2"));
        root = CalcUi.page(CalcUi.panel(CalcUi.note(host.tr("calc_eye_how"))), sides, results);
        render();
    }

    public Node node() {
        return root;
    }

    private void render() {
        results.getChildren().clear();
        WorldCalc.Throw a = first.value();
        WorldCalc.Throw b = second.value();
        if (a == null || b == null) {
            results.getChildren().add(CalcUi.note(host.tr("calc_eye_empty")));
            return;
        }
        WorldCalc.Fix fix = WorldCalc.triangulate(a, b);
        if (fix.problem() != WorldCalc.Problem.NONE) {
            results.getChildren().add(CalcUi.note(host.tr(
                    fix.problem() == WorldCalc.Problem.TOO_PARALLEL ? "calc_eye_parallel" : "calc_eye_diverging")));
            return;
        }
        long x = CalcUi.round(fix.x());
        long z = CalcUi.round(fix.z());
        Label coords = CalcUi.title(host.tr("calc_eye_result"));
        results.getChildren().addAll(coords,
                CalcUi.row(host.tr("calc_in_overworld"), x + " ~ " + z, host),
                CalcUi.row(host.tr("calc_in_nether"), Math.floorDiv(x, 8L) + " ~ " + Math.floorDiv(z, 8L), host),
                CalcUi.row(host.tr("calc_eye_walk"), blocks(Math.hypot(fix.x() - b.x(), fix.z() - b.z())), null));
        if (Double.isInfinite(fix.spread())) {
            results.getChildren().add(CalcUi.note(host.tr("calc_eye_loose")));
            return;
        }
        results.getChildren().add(CalcUi.row(host.tr("calc_eye_spread"), "± " + blocks(fix.spread()), null));
        WorldCalc.RingInfo ring = WorldCalc.ring(fix.x(), fix.z(), fix.spread());
        if (ring.ring() > 0) {
            results.getChildren().add(CalcUi.row(host.tr("calc_eye_ring"), ring.ring() + " / 8", null));
        } else {
            results.getChildren().add(CalcUi.note(host.tr("calc_eye_off_ring")
                    .replace("{n}", Long.toString(CalcUi.round(ring.outsideBy())))));
        }
        if (fix.spread() > LOOSE_SPREAD) {
            results.getChildren().add(CalcUi.note(host.tr("calc_eye_loose")));
        }
    }

    private String blocks(double distance) {
        return host.tr("calc_blocks").replace("{n}", Long.toString(CalcUi.round(distance)));
    }

    /** Two decimals are all F3+C gives and all the sum needs. */
    private static String trim(double v) {
        return java.math.BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
