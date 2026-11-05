package com.example.launcher.ui.texpack;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayDeque;
import java.util.Deque;

/** What the brushes do to a texture: plain ARGB arrays and no JavaFX, so it can be checked without a window. */
final class PixelOps {

    /** A texture: {@code px[y * w + x]} is ARGB. */
    record Sheet(int w, int h, int[] px) {
        static Sheet blank(int w, int h) {
            return new Sheet(w, h, new int[w * h]);
        }

        Sheet copy() {
            return new Sheet(w, h, px.clone());
        }

        int at(int x, int y) {
            return px[y * w + x];
        }
    }

    /** A callback for one texel. */
    interface Texel {
        void at(int x, int y);
    }

    private PixelOps() {
    }

    /**
     * Rows in one frame of the texture. Only an {@code animation} section makes a strip of frames;
     * other sections (a GUI sprite's scaling) leave the texture whole, and so does a strip laid out
     * in several columns, which is painted as one picture.
     */
    static int frameRows(int w, int h, String mcmeta) {
        if (mcmeta == null || mcmeta.isBlank()) return h;
        try {
            JsonObject root = JsonParser.parseString(mcmeta).getAsJsonObject();
            if (!root.has("animation")) return h;
            JsonObject animation = root.getAsJsonObject("animation");
            int frameW = animation.has("width") ? animation.get("width").getAsInt() : w;
            int frameH = animation.has("height") ? animation.get("height").getAsInt() : Math.min(w, h);
            if (frameW != w || frameH <= 0 || h % frameH != 0) return h;
            return frameH;
        } catch (RuntimeException e) {
            return h;   // a broken section: the game does not animate it either
        }
    }

    /** Bresenham between two samples of a drag, so a quick stroke is a line and not a dotted trail. */
    static void line(int x0, int y0, int x1, int y1, Texel out) {
        int dx = Math.abs(x1 - x0);
        int dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            out.at(x0, y0);
            if (x0 == x1 && y0 == y1) return;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    /** A brush-sized square around the texel, kept inside rows {@code top .. top + rows - 1}. */
    static void brush(Sheet s, int x, int y, int size, int top, int rows, Texel out) {
        int from = -(size - 1) / 2;
        for (int dy = from; dy < from + size; dy++) {
            for (int dx = from; dx < from + size; dx++) {
                int bx = x + dx, by = y + dy;
                if (bx >= 0 && bx < s.w() && by >= top && by < top + rows) out.at(bx, by);
            }
        }
    }

    /** Flood fill that stays in the frame it starts on. Returns how many texels changed. */
    static int fill(Sheet s, int x, int y, int argb, int top, int rows) {
        int target = s.at(x, y);
        if (target == argb) return 0;
        int changed = 0;
        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{x, y});
        while (!stack.isEmpty()) {
            int[] p = stack.pop();
            int px = p[0], py = p[1];
            if (px < 0 || px >= s.w() || py < top || py >= top + rows || s.at(px, py) != target) continue;
            s.px()[py * s.w() + px] = argb;
            changed++;
            stack.push(new int[]{px + 1, py});
            stack.push(new int[]{px - 1, py});
            stack.push(new int[]{px, py + 1});
            stack.push(new int[]{px, py - 1});
        }
        return changed;
    }

    /** Every texel of one colour, in all frames, becomes another. Returns how many changed. */
    static int replace(Sheet s, int from, int to) {
        if (from == to) return 0;
        int changed = 0;
        for (int i = 0; i < s.px().length; i++) {
            if (s.px()[i] == from) {
                s.px()[i] = to;
                changed++;
            }
        }
        return changed;
    }

    /**
     * The texel softened towards its neighbours in the frame, only those with colour, so an edge
     * never bleeds into empty texture and a transparent texel stays transparent.
     */
    static int blurred(Sheet src, int x, int y, int top, int rows) {
        int centre = src.at(x, y);
        if ((centre >>> 24) == 0) return centre;
        double a = 0, r = 0, g = 0, b = 0, weight = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int nx = x + dx, ny = y + dy;
                if (nx < 0 || nx >= src.w() || ny < top || ny >= top + rows) continue;
                int c = src.at(nx, ny);
                if ((c >>> 24) == 0) continue;
                double w = dx == 0 && dy == 0 ? 2 : 1;
                a += (c >>> 24) * w;
                r += ((c >> 16) & 255) * w;
                g += ((c >> 8) & 255) * w;
                b += (c & 255) * w;
                weight += w;
            }
        }
        return ((int) Math.round(a / weight) << 24) | ((int) Math.round(r / weight) << 16)
                | ((int) Math.round(g / weight) << 8) | (int) Math.round(b / weight);
    }

    /** Lighter (positive) or darker (negative) by that share of full brightness, hue and alpha kept. */
    static int shaded(int argb, double amount) {
        if ((argb >>> 24) == 0) return argb;
        float[] hsb = java.awt.Color.RGBtoHSB((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, null);
        float bright = (float) Math.max(0, Math.min(1, hsb[2] + amount));
        // near white, lightening also takes a little colour out, as paint does
        float sat = amount > 0 && hsb[2] > 0.92f ? (float) Math.max(0, hsb[1] - amount * 0.6) : hsb[1];
        return (argb & 0xFF000000) | (java.awt.Color.HSBtoRGB(hsb[0], sat, bright) & 0xFFFFFF);
    }

    /** {@code top} over an opaque {@code bottom}: what the canvas shows over its checkerboard. */
    static int over(int bottom, int top) {
        double a = (top >>> 24) / 255.0;
        int r = (int) Math.round(((top >> 16) & 255) * a + ((bottom >> 16) & 255) * (1 - a));
        int g = (int) Math.round(((top >> 8) & 255) * a + ((bottom >> 8) & 255) * (1 - a));
        int b = (int) Math.round((top & 255) * a + (bottom & 255) * (1 - a));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
