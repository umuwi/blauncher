package com.example.launcher.ui.worldcalc;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.javafx.FontIcon;

/** The controls the two calculators share: numeric fields, result rows, the F3+C button. */
final class CalcUi {

    private CalcUi() {
    }

    static Node page(Node... children) {
        VBox box = new VBox(12, children);
        box.setMaxWidth(860);
        ScrollPane scroll = new ScrollPane(box);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("screen-scroll");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scroll;
    }

    static VBox panel(Node... children) {
        VBox box = new VBox(10, children);
        box.getStyleClass().add("panel");
        return box;
    }

    static Label title(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("tool-card-title");
        l.setWrapText(true);
        return l;
    }

    static Label note(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("toggle-sub");
        l.setWrapText(true);
        return l;
    }

    /** A small caption over a field, stretching with its neighbours. */
    static VBox field(String caption, TextField input, Runnable onChange) {
        input.getStyleClass().addAll("text-input", "mono");
        input.setMinWidth(70);
        input.textProperty().addListener((o, was, now) -> onChange.run());
        VBox box = new VBox(4, note(caption), input);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    /** Caption on the left, value on the right; the value can be selected only through its copy button. */
    static HBox row(String caption, String value, CalcHost host) {
        Label left = note(caption);
        left.setMinWidth(Region.USE_PREF_SIZE);
        Label right = new Label(value);
        right.getStyleClass().addAll("toggle-label", "mono");
        right.setWrapText(true);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox box = new HBox(10, left, gap, right);
        box.setAlignment(Pos.CENTER_LEFT);
        if (host != null) {
            Button copy = new Button();
            copy.setGraphic(new FontIcon("fth-copy"));
            copy.getStyleClass().add("copy-btn");
            copy.setOnAction(e -> host.copy(value));
            box.getChildren().add(copy);
        }
        return box;
    }

    /** The launcher's clipboard reading of F3+C; a toast explains anything that is not one. */
    static WorldCalc.Location pasteLocation(CalcHost host) {
        WorldCalc.Location at = WorldCalc.location(Clipboard.getSystemClipboard().getString());
        if (at == null) {
            host.toast(host.tr("calc_paste_nothing"), "info");
        }
        return at;
    }

    static Button pasteButton(CalcHost host, Runnable action) {
        Button b = new Button(host.tr("calc_paste"));
        b.setGraphic(new FontIcon("fth-clipboard"));
        b.getStyleClass().add("secondary-button");
        b.setOnAction(e -> action.run());
        return b;
    }

    static String block(WorldCalc.Block b) {
        return b.x() + " " + (b.y() == null ? "~" : b.y().toString()) + " " + b.z();
    }

    static long round(double v) {
        return Math.round(v);
    }
}
