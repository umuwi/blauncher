package com.example.launcher.ui;

import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import com.example.launcher.service.Http;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Fetches and decodes a remote icon into a JavaFX {@link Image}, for cases plain
 * {@code new Image(url, ...)} can't handle: JavaFX's own Image loader has its own internal codec
 * pipeline (com.sun.javafx.iio) that is completely separate from java.awt's ImageIO/SPI mechanism
 * and only understands PNG/JPEG/GIF/BMP - notably NOT WebP. Modrinth serves the large majority of
 * project icons as WebP (only a handful of older projects still have a plain .png icon_url), so
 * {@code new Image(iconUrl, ...)} silently fails to decode most real mod/shader icons.
 *
 * <p>Routes through {@link ImageIO} instead, which DOES pick up WebP via the twelvemonkeys
 * imageio-webp plugin. That plugin (plus its imageio-core/imageio-metadata/common-* dependencies)
 * is vendored in this repository under {@code lib/} and unpacked INTO BLauncher.jar by build.ps1,
 * so the reader is registered through the standard {@code javax.imageio.spi.ImageReaderSpi}
 * ServiceLoader mechanism with no manual wiring and no extra files to ship: auto-update replaces
 * exactly one file (app\BLauncher.jar), so a plugin left as a separate jar would never reach an
 * installed launcher. The resulting {@link BufferedImage} is hand-converted to a JavaFX Image via
 * raw ARGB pixels rather than {@code javafx.embed.swing.SwingFXUtils}, since BLauncher.bat's
 * {@code --add-modules} list doesn't include {@code javafx.swing} and this avoids needing to
 * touch the build/launch scripts for what's otherwise a self-contained fix.
 *
 * <p>This does a blocking network fetch + decode - callers must run it off the JavaFX
 * Application Thread (e.g. via the existing background executor) and hand the result back via
 * {@code Platform.runLater(...)}.
 */
public final class IconLoader {

    private IconLoader() {}

    /** Returns the decoded icon, or {@code null} on any failure (bad/missing URL, network error,
     *  unsupported format) - callers should fall back to a placeholder rather than propagate. */
    public static Image load(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            Http.Response resp = Http.get(url);
            if (!resp.ok()) return null;
            BufferedImage buffered;
            try (InputStream in = new ByteArrayInputStream(resp.body())) {
                buffered = ImageIO.read(in);
            }
            return buffered != null ? toFxImage(buffered) : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The same hand-conversion for callers that already decoded the image themselves - the
     * site logos behind the profile source chips come off disk or out of an .ico container
     * and never pass through {@link #load(String)}.
     */
    public static Image fromBufferedImage(BufferedImage src) {
        return src == null ? null : toFxImage(src);
    }

    private static Image toFxImage(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] argb = src.getRGB(0, 0, w, h, null, 0, w);
        WritableImage image = new WritableImage(w, h);
        image.getPixelWriter().setPixels(0, 0, w, h, PixelFormat.getIntArgbInstance(), argb, 0, w);
        return image;
    }
}
