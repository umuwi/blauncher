package com.example.launcher.ui.worldcalc;

import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Two portals in either world: where each one lands in the other world, how far apart they are,
 * and whether the game will link them. One filled portal is enough for the conversion alone.
 */
public final class PortalCalculator {

    /** One portal's inputs. */
    private final class Side {
        final ComboBox<String> world = new ComboBox<>();
        final TextField x = new TextField();
        final TextField y = new TextField();
        final TextField z = new TextField();

        VBox build(String titleKey, boolean nether) {
            world.getItems().addAll(host.tr("calc_overworld"), host.tr("calc_nether"));
            world.getSelectionModel().select(nether ? 1 : 0);
            world.setMaxWidth(Double.MAX_VALUE);
            world.setOnAction(e -> render());
            HBox coords = new HBox(8, CalcUi.field("X", x, PortalCalculator.this::render),
                    CalcUi.field("Y", y, PortalCalculator.this::render),
                    CalcUi.field("Z", z, PortalCalculator.this::render));
            VBox box = CalcUi.panel(CalcUi.title(host.tr(titleKey)), world, coords, CalcUi.pasteButton(host, this::paste));
            HBox.setHgrow(box, Priority.ALWAYS);
            box.setPrefWidth(300);
            return box;
        }

        void paste() {
            WorldCalc.Location at = CalcUi.pasteLocation(host);
            if (at == null) return;
            if ("the_end".equals(at.dimension())) {
                host.toast(host.tr("calc_paste_end"), "info");
                return;
            }
            if (at.dimension() != null) world.getSelectionModel().select(at.nether() ? 1 : 0);
            x.setText(Long.toString((long) Math.floor(at.x())));
            y.setText(Long.toString((long) Math.floor(at.y())));
            z.setText(Long.toString((long) Math.floor(at.z())));
        }

        boolean nether() {
            return world.getSelectionModel().getSelectedIndex() == 1;
        }

        WorldCalc.Block block() {
            Double px = WorldCalc.number(x.getText());
            Double pz = WorldCalc.number(z.getText());
            if (px == null || pz == null) return null;
            Double py = WorldCalc.number(y.getText());
            return new WorldCalc.Block((long) Math.floor(px), py == null ? null : (int) Math.floor(py), (long) Math.floor(pz));
        }
    }

    private final CalcHost host;
    private final Side first = new Side();
    private final Side second = new Side();
    private final VBox results = CalcUi.panel();
    private final Node root;

    public PortalCalculator(CalcHost host) {
        this.host = host;
        HBox sides = new HBox(12, first.build("calc_portal_1", false), second.build("calc_portal_2", true));
        root = CalcUi.page(CalcUi.note(host.tr("calc_portal_intro")), sides, results);
        render();
    }

    public Node node() {
        return root;
    }

    private void render() {
        results.getChildren().clear();
        WorldCalc.Block a = first.block();
        WorldCalc.Block b = second.block();
        if (a == null && b == null) {
            results.getChildren().add(CalcUi.note(host.tr("calc_portal_empty")));
            return;
        }
        if (a != null) convert(host.tr("calc_portal_1"), a, first.nether());
        if (b != null) convert(host.tr("calc_portal_2"), b, second.nether());
        if (a == null || b == null) return;

        WorldCalc.Block overA = first.nether() ? WorldCalc.toOverworld(a) : a;
        WorldCalc.Block overB = second.nether() ? WorldCalc.toOverworld(b) : b;
        WorldCalc.Block netherA = first.nether() ? a : WorldCalc.toNether(a);
        WorldCalc.Block netherB = second.nether() ? b : WorldCalc.toNether(b);
        results.getChildren().addAll(
                section("calc_distance"),
                CalcUi.row(host.tr("calc_in_overworld"), blocks(WorldCalc.distance(overA, overB)), null),
                CalcUi.row(host.tr("calc_in_nether"), blocks(WorldCalc.distance(netherA, netherB)), null));
        if (first.nether() == second.nether()) return;

        WorldCalc.Block over = first.nether() ? b : a;
        WorldCalc.Block nether = first.nether() ? a : b;
        WorldCalc.Link down = WorldCalc.linkToNether(over, nether);
        WorldCalc.Link up = WorldCalc.linkToOverworld(nether, over);
        results.getChildren().addAll(section("calc_link"),
                CalcUi.row(host.tr("calc_link_down"), verdict(down), null),
                CalcUi.row(host.tr("calc_link_up"), verdict(up), null));
        // One move is enough: a Nether portal at the exact target links both ways. Advising both would undo it.
        if (!down.found()) {
            results.getChildren().add(CalcUi.row(host.tr("calc_build_nether"), CalcUi.block(down.target()), host));
        } else if (!up.found()) {
            results.getChildren().add(CalcUi.row(host.tr("calc_build_overworld"), CalcUi.block(up.target()), host));
        }
        results.getChildren().add(CalcUi.note(host.tr("calc_link_note")));
    }

    private void convert(String name, WorldCalc.Block portal, boolean nether) {
        WorldCalc.Block other = nether ? WorldCalc.toOverworld(portal) : WorldCalc.toNether(portal);
        String where = host.tr(nether ? "calc_in_overworld" : "calc_in_nether");
        results.getChildren().add(CalcUi.row(name + " · " + where, CalcUi.block(other), host));
    }

    private Label section(String key) {
        Label l = new Label(host.tr(key));
        l.getStyleClass().add("section-title");
        return l;
    }

    private String verdict(WorldCalc.Link link) {
        long off = Math.max(Math.abs(link.dx()), Math.abs(link.dz()));
        return host.tr(link.found() ? "calc_link_yes" : "calc_link_no").replace("{n}", Long.toString(off));
    }

    private String blocks(double distance) {
        return host.tr("calc_blocks").replace("{n}", Long.toString(CalcUi.round(distance)));
    }
}
