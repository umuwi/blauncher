package com.example.launcher.ui;

import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.stage.FileChooser;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * A picture of a 3D view saved as PNG, for showing a build or a skin to friends: drawn at twice
 * the size on screen so it stays sharp, on a transparent background.
 */
public final class Snapshots {

    private Snapshots() {}

    /** Asks where to save, then writes the picture; returns the file, or null when cancelled. */
    public static File saveAsPng(Node view, String title, String fileName) throws IOException {
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        params.setTransform(Transform.scale(2, 2));
        WritableImage shot = view.snapshot(params, null);

        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        chooser.setInitialFileName(fileName);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG", "*.png"));
        File file = chooser.showSaveDialog(view.getScene() == null ? null : view.getScene().getWindow());
        if (file == null) return null;
        ImageIO.write(toBuffered(shot), "png", file);
        return file;
    }

    /** No javafx.swing needed: the pixels are copied across as ARGB. */
    static BufferedImage toBuffered(WritableImage image) {
        int w = (int) image.getWidth(), h = (int) image.getHeight();
        int[] argb = new int[w * h];
        PixelReader reader = image.getPixelReader();
        reader.getPixels(0, 0, w, h, javafx.scene.image.PixelFormat.getIntArgbInstance(), argb, 0, w);
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        out.setRGB(0, 0, w, h, argb, 0, w);
        return out;
    }
}
