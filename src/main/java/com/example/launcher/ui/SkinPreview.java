package com.example.launcher.ui;

import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;

/**
 * Builds crisp skin and cape previews.
 *
 * <p>Minecraft textures are 64x64 (skin) and 64x32 (cape) pixel art. Showing the
 * whole sheet in a 96x96 box - which is what the profile editor used to do -
 * scales it by 1.5 with smooth interpolation, so every pixel is smeared into its
 * neighbours and the player sees a blurry rectangle instead of their character.
 *
 * <p>These helpers instead crop the part that means something and scale it by a
 * whole number with nearest-neighbour sampling, so each source pixel becomes a
 * solid square: 8x8 face at 12x is exactly the 96x96 preview box, and the 10x16
 * cape at 6x is exactly 96 tall. No interpolation happens anywhere, which is the
 * whole point - any smoothing here would defeat the purpose.
 */
public final class SkinPreview {

    /** Preview box the profile editor lays out (see .profile-preview in the theme template). */
    public static final int BOX = 96;

    /** Head: the front face of a 64x64 skin, 8x8 at (8,8). */
    private static final int HEAD_X = 8;
    private static final int HEAD_Y = 8;
    private static final int HEAD_SIZE = 8;

    /** Cape: the 10x16 area at the origin of a 64x32 cape texture. */
    private static final int CAPE_W = 10;
    private static final int CAPE_H = 16;

    private SkinPreview() {}

    /**
     * A 96x96 image of the skin's head, or the source unchanged when it is not a
     * standard skin (arbitrary uploads, or an already-cropped image).
     */
    public static Image skinHead(Image source) {
        if (source == null) return null;
        if (source.getWidth() < HEAD_X + HEAD_SIZE || source.getHeight() < HEAD_Y + HEAD_SIZE) {
            return source;
        }
        int factor = Math.max(1, BOX / HEAD_SIZE);
        return scale(source, HEAD_X, HEAD_Y, HEAD_SIZE, HEAD_SIZE, factor);
    }

    /** A 60x96 image of the cape, or the source unchanged when it is not a standard cape. */
    public static Image cape(Image source) {
        if (source == null) return null;
        if (source.getWidth() < CAPE_W || source.getHeight() < CAPE_H) {
            return source;
        }
        int factor = Math.max(1, BOX / CAPE_H);
        return scale(source, 0, 0, CAPE_W, CAPE_H, factor);
    }

    /**
     * The whole character seen from the front: a 16x32 figure (head, body, both arms, both legs,
     * each with its outer layer on top) scaled by a whole number, nearest-neighbour.
     *
     * <p>Handles both texture formats: 64x64 (separate left limbs and outer layers) and the old
     * 64x32, where the left arm and leg are the right ones mirrored and only the head has a second
     * layer. Slim ("Alex") arms are 3 pixels wide and detected the usual way: the column just
     * past a 3-pixel arm is transparent on a slim skin and painted on a classic one.
     *
     * @return the figure, or null when the image is not a skin texture at all
     */
    public static Image fullBody(Image source, int factor) {
        if (source == null) return null;
        PixelReader reader = source.getPixelReader();
        int w = (int) source.getWidth();
        int h = (int) source.getHeight();
        if (reader == null || w != 64 || (h != 64 && h != 32)) return null;
        boolean modern = h == 64;
        boolean slim = modern && ((reader.getArgb(54, 20) >>> 24) == 0);
        int arm = slim ? 3 : 4;

        int[] canvas = new int[16 * 32];
        // head + hat
        blit(reader, canvas, 8, 8, 8, 8, 4, 0, false);
        blit(reader, canvas, 40, 8, 8, 8, 4, 0, false);
        // body
        blit(reader, canvas, 20, 20, 8, 12, 4, 8, false);
        // right arm and right leg (the viewer's left)
        blit(reader, canvas, 44, 20, arm, 12, 4 - arm, 8, false);
        blit(reader, canvas, 4, 20, 4, 12, 4, 20, false);
        if (modern) {
            blit(reader, canvas, 20, 36, 8, 12, 4, 8, false);           // jacket
            blit(reader, canvas, 44, 36, arm, 12, 4 - arm, 8, false);   // right sleeve
            blit(reader, canvas, 4, 36, 4, 12, 4, 20, false);           // right trouser
            blit(reader, canvas, 36, 52, arm, 12, 12, 8, false);        // left arm
            blit(reader, canvas, 52, 52, arm, 12, 12, 8, false);        // left sleeve
            blit(reader, canvas, 20, 52, 4, 12, 8, 20, false);          // left leg
            blit(reader, canvas, 4, 52, 4, 12, 8, 20, false);           // left trouser
        } else {
            blit(reader, canvas, 44, 20, 4, 12, 12, 8, true);
            blit(reader, canvas, 4, 20, 4, 12, 8, 20, true);
        }

        int f = Math.max(1, factor);
        WritableImage out = new WritableImage(16 * f, 32 * f);
        PixelWriter writer = out.getPixelWriter();
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 16; x++) {
                int argb = canvas[y * 16 + x];
                if ((argb >>> 24) == 0) continue;
                for (int dy = 0; dy < f; dy++) {
                    for (int dx = 0; dx < f; dx++) {
                        writer.setArgb(x * f + dx, y * f + dy, argb);
                    }
                }
            }
        }
        return out;
    }

    /** Copies one texture rectangle onto the 16x32 canvas, alpha-blended over what is there. */
    private static void blit(PixelReader reader, int[] canvas, int sx, int sy, int sw, int sh,
                             int dx, int dy, boolean mirror) {
        for (int y = 0; y < sh; y++) {
            for (int x = 0; x < sw; x++) {
                int src = reader.getArgb(sx + (mirror ? sw - 1 - x : x), sy + y);
                int index = (dy + y) * 16 + dx + x;
                canvas[index] = over(src, canvas[index]);
            }
        }
    }

    /** Porter-Duff "source over destination" for two ARGB pixels. */
    static int over(int src, int dst) {
        int sa = src >>> 24;
        if (sa == 255) return src;
        if (sa == 0) return dst;
        int da = dst >>> 24;
        int outA = sa + da * (255 - sa) / 255;
        if (outA == 0) return 0;
        int r = (((src >> 16) & 255) * sa + ((dst >> 16) & 255) * da * (255 - sa) / 255) / outA;
        int g = (((src >> 8) & 255) * sa + ((dst >> 8) & 255) * da * (255 - sa) / 255) / outA;
        int b = ((src & 255) * sa + (dst & 255) * da * (255 - sa) / 255) / outA;
        return (outA << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Nearest-neighbour scale of one source region: every source pixel becomes a
     * factor x factor block of identical pixels.
     */
    private static Image scale(Image source, int sourceX, int sourceY, int sourceW, int sourceH, int factor) {
        PixelReader reader = source.getPixelReader();
        if (reader == null) return source;

        int width = sourceW * factor;
        int height = sourceH * factor;
        WritableImage out = new WritableImage(width, height);
        PixelWriter writer = out.getPixelWriter();

        for (int y = 0; y < sourceH; y++) {
            for (int x = 0; x < sourceW; x++) {
                int argb = reader.getArgb(sourceX + x, sourceY + y);
                for (int dy = 0; dy < factor; dy++) {
                    for (int dx = 0; dx < factor; dx++) {
                        writer.setArgb(x * factor + dx, y * factor + dy, argb);
                    }
                }
            }
        }
        return out;
    }
}
