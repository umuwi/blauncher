package com.example.launcher.ui.blockart;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Картинка или 3D-модель превращается в блоки. Без JavaFX: считается в фоне, и это можно проверить без окна. */
final class Voxelizer {

    /** Ближайший блок к цвету: сравнение в пространстве, близком к глазу, с кэшем по цвету. */
    static final class Matcher {
        private final List<BlockColors.Block> blocks;
        private final float[][] lab;
        private final short[] ids;
        private final Map<Integer, Short> cache = new HashMap<>();

        Matcher(List<BlockColors.Block> blocks, VoxelGrid grid) {
            this.blocks = blocks;
            lab = new float[blocks.size()][];
            ids = new short[blocks.size()];
            for (int i = 0; i < blocks.size(); i++) {
                lab[i] = lab(blocks.get(i).rgb());
                ids[i] = -1;
            }
            this.grid = grid;
        }

        private final VoxelGrid grid;

        int nearest(int rgb) {
            float[] c = lab(rgb);
            int best = 0;
            float bestD = Float.MAX_VALUE;
            for (int i = 0; i < lab.length; i++) {
                float dl = c[0] - lab[i][0], da = c[1] - lab[i][1], db = c[2] - lab[i][2];
                float d = dl * dl + da * da + db * db;
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            return best;
        }

        short id(int rgb) {
            return cache.computeIfAbsent(rgb & 0xF8F8F8, k -> {
                int i = nearest(k | 0x040404);
                if (ids[i] < 0) ids[i] = grid.idOf(blocks.get(i).state());
                return ids[i];
            });
        }

        int colorOf(int rgb) {
            return blocks.get(nearest(rgb)).rgb();
        }

        private static float[] lab(int rgb) {
            double r = lin((rgb >> 16) & 255), g = lin((rgb >> 8) & 255), b = lin(rgb & 255);
            double x = f((0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047);
            double y = f(0.2126 * r + 0.7152 * g + 0.0722 * b);
            double z = f((0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883);
            return new float[]{(float) (116 * y - 16), (float) (500 * (x - y)), (float) (200 * (y - z))};
        }

        private static double lin(int c) {
            double v = c / 255.0;
            return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }

        private static double f(double t) {
            return t > 0.008856 ? Math.cbrt(t) : 7.787 * t + 16.0 / 116;
        }
    }

    private Voxelizer() {
    }

    /** Картинка блоками: стена (стоит, смотрит на юг) или пол. Прозрачные места остаются воздухом. */
    static VoxelGrid image(Path file, int width, boolean wall, boolean dither, List<BlockColors.Block> blocks) throws IOException {
        BufferedImage src = ImageIO.read(file.toFile());
        if (src == null) throw new IOException("not an image");
        int w = Math.max(1, Math.min(VoxelGrid.MAX_SIDE, width));
        int h = Math.max(1, Math.min(VoxelGrid.MAX_SIDE, Math.round((float) src.getHeight() * w / src.getWidth())));
        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gfx = small.createGraphics();
        gfx.setRenderingHint(RenderingHints.KEY_INTERPOLATION, w < src.getWidth() / 2
                ? RenderingHints.VALUE_INTERPOLATION_BILINEAR : RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        gfx.drawImage(src.getScaledInstance(w, h, java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null);
        gfx.dispose();

        VoxelGrid g = wall ? new VoxelGrid(w, h, 1) : new VoxelGrid(w, 1, h);
        Matcher m = new Matcher(blocks, g);
        float[][] err = dither ? new float[h + 1][(w + 2) * 3] : null;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int c = small.getRGB(x, y);
                if ((c >>> 24) < 128) continue;
                int r = (c >> 16) & 255, gr = (c >> 8) & 255, b = c & 255;
                if (dither) {
                    r = clamp(r + err[y][(x + 1) * 3]);
                    gr = clamp(gr + err[y][(x + 1) * 3 + 1]);
                    b = clamp(b + err[y][(x + 1) * 3 + 2]);
                }
                int rgb = r << 16 | gr << 8 | b;
                short id = m.id(rgb);
                if (wall) g.set(x, h - 1 - y, 0, id);
                else g.set(x, 0, y, id);
                if (dither) {
                    int got = m.colorOf(rgb);
                    spread(err, x, y, r - ((got >> 16) & 255), 0);
                    spread(err, x, y, gr - ((got >> 8) & 255), 1);
                    spread(err, x, y, b - (got & 255), 2);
                }
            }
        }
        return g;
    }

    private static void spread(float[][] e, int x, int y, int d, int ch) {
        // Флойд–Стейнберг
        e[y][(x + 2) * 3 + ch] += d * 7 / 16f;
        e[y + 1][x * 3 + ch] += d * 3 / 16f;
        e[y + 1][(x + 1) * 3 + ch] += d * 5 / 16f;
        e[y + 1][(x + 2) * 3 + ch] += d / 16f;
    }

    private static int clamp(float v) {
        return Math.max(0, Math.min(255, Math.round(v)));
    }

    // ------------------------------------------------------------------ 3D

    private record Tri(float[] a, float[] b, float[] c, float[] uva, float[] uvb, float[] uvc, int rgb, BufferedImage map, String mat) {}

    /** Что лаунчер отдавал в Blender: блоки по клеткам и какие блоки рисуются какой текстурой. */
    record Original(VoxelGrid grid, Map<String, List<String>> statesByTexture) {}

    /** Модель .obj (с цветами и текстурой из .mtl) или .stl. Самая длинная сторона — {@code size} блоков. */
    static VoxelGrid model(Path file, int size, boolean fill, boolean zUp, List<BlockColors.Block> blocks) throws IOException {
        String n = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        List<Tri> tris = n.endsWith(".stl") ? readStl(file) : readObj(file);
        if (tris.isEmpty()) throw new IOException("no triangles");
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (Tri t : tris) {
            for (float[] p : new float[][]{t.a(), t.b(), t.c()}) {
                if (zUp) {
                    float y = p[1];
                    p[1] = p[2];
                    p[2] = -y;
                }
                for (int i = 0; i < 3; i++) {
                    min[i] = Math.min(min[i], p[i]);
                    max[i] = Math.max(max[i], p[i]);
                }
            }
        }
        float longest = Math.max(max[0] - min[0], Math.max(max[1] - min[1], max[2] - min[2]));
        if (longest <= 0) throw new IOException("flat model");
        int side = Math.max(2, Math.min(VoxelGrid.MAX_SIDE, size));
        float k = (side - 1) / longest;
        int w = (int) Math.ceil((max[0] - min[0]) * k) + 1;
        int h = (int) Math.ceil((max[1] - min[1]) * k) + 1;
        int l = (int) Math.ceil((max[2] - min[2]) * k) + 1;
        VoxelGrid g = new VoxelGrid(Math.min(w, side), Math.min(h, side), Math.min(l, side));
        Matcher m = new Matcher(blocks, g);
        for (Tri t : tris) {
            float[] a = scaled(t.a(), min, k), b = scaled(t.b(), min, k), c = scaled(t.c(), min, k);
            // Шаг меньше блока, чтобы в поверхности не осталось дыр.
            int steps = (int) Math.ceil(Math.max(dist(a, b), Math.max(dist(b, c), dist(a, c))) * 2) + 1;
            for (int i = 0; i <= steps; i++) {
                for (int j = 0; j <= steps - i; j++) {
                    float u = (float) i / steps, v = (float) j / steps, s = 1 - u - v;
                    int x = (int) (a[0] * s + b[0] * u + c[0] * v + 0.5f);
                    int y = (int) (a[1] * s + b[1] * u + c[1] * v + 0.5f);
                    int z = (int) (a[2] * s + b[2] * u + c[2] * v + 0.5f);
                    if (x < 0 || y < 0 || z < 0 || x >= g.w || y >= g.h || z >= g.l || g.at(x, y, z) != 0) continue;
                    int color = colorAt(t, s, u, v);
                    if (color >= 0) g.set(x, y, z, m.id(color));
                }
            }
        }
        if (fill) fillInside(g);
        return g;
    }

    /**
     * Сцена из Blender: 1 единица = 1 блок, клетки не сдвигаются. Грань блока лежит ровно на границе двух
     * клеток, поэтому точка берётся чуть внутри от нормали: так грань попадает в свой блок, а не в соседа.
     */
    static VoxelGrid sceneUnits(Path file, List<BlockColors.Block> blocks, Original original) throws IOException {
        List<Tri> tris = readObj(file);
        if (tris.isEmpty()) throw new IOException("no triangles");
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (Tri t : tris) {
            for (float[] p : new float[][]{t.a(), t.b(), t.c()}) {
                for (int i = 0; i < 3; i++) {
                    min[i] = Math.min(min[i], p[i]);
                    max[i] = Math.max(max[i], p[i]);
                }
            }
        }
        for (int i = 0; i < 3; i++) min[i] = (float) Math.floor(min[i] + 0.001f);
        int w = Math.max(1, (int) Math.ceil(max[0] - min[0] - 0.001f));
        int h = Math.max(1, (int) Math.ceil(max[1] - min[1] - 0.001f));
        int l = Math.max(1, (int) Math.ceil(max[2] - min[2] - 0.001f));
        VoxelGrid g = new VoxelGrid(w, h, l);
        Matcher m = new Matcher(blocks, g);
        for (Tri t : tris) {
            float[] a = t.a(), b = t.b(), c = t.c();
            float ux = b[0] - a[0], uy = b[1] - a[1], uz = b[2] - a[2];
            float vx = c[0] - a[0], vy = c[1] - a[1], vz = c[2] - a[2];
            float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1e-6f) continue;
            nx /= len;
            ny /= len;
            nz /= len;
            int steps = (int) Math.ceil(Math.max(dist(a, b), Math.max(dist(b, c), dist(a, c))) * 2) + 1;
            for (int i = 0; i <= steps; i++) {
                for (int j = 0; j <= steps - i; j++) {
                    float u = (float) i / steps, v = (float) j / steps, s = 1 - u - v;
                    // Чуть внутрь и чуть от рёбер: иначе точка на ребре попадёт в клетку рядом.
                    float cu = (u + 0.02f) / 1.06f, cv = (v + 0.02f) / 1.06f, cs = (s + 0.02f) / 1.06f;
                    int x = (int) Math.floor(a[0] * cs + b[0] * cu + c[0] * cv - nx * 0.05f - min[0]);
                    int y = (int) Math.floor(a[1] * cs + b[1] * cu + c[1] * cv - ny * 0.05f - min[1]);
                    int z = (int) Math.floor(a[2] * cs + b[2] * cu + c[2] * cv - nz * 0.05f - min[2]);
                    if (x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= l || g.at(x, y, z) != 0) continue;
                    String state = original == null ? null : sameBlock(original, t.mat(), x + (int) min[0], y + (int) min[1], z + (int) min[2]);
                    if (state != null) {
                        g.set(x, y, z, g.idOf(state));
                        continue;
                    }
                    int color = colorAt(t, s, u, v);
                    if (color >= 0) g.set(x, y, z, m.id(color));
                }
            }
        }
        return g;
    }

    /**
     * Блок по материалу, а не по цвету: иначе после Blender каменные кирпичи становились камнем, трава землёй,
     * а стекло терракотой (проверка 05.11.2026). Если клетка осталась на месте — прежний блок со всеми
     * свойствами, если её передвинули — блок с этой текстурой. Новые материалы идут по цвету.
     */
    private static String sameBlock(Original o, String material, int x, int y, int z) {
        if (material == null) return null;
        // Blender дописывает .001 к повторному имени материала.
        List<String> states = o.statesByTexture().get(material.replaceAll("\\.\\d{3}$", ""));
        if (states == null || states.isEmpty()) return null;
        VoxelGrid g = o.grid();
        if (x >= 0 && y >= 0 && z >= 0 && x < g.w && y < g.h && z < g.l) {
            String was = g.palette.get(g.at(x, y, z));
            if (states.contains(was)) return was;
        }
        return states.get(0);
    }

    private static float[] scaled(float[] p, float[] min, float k) {
        return new float[]{(p[0] - min[0]) * k, (p[1] - min[1]) * k, (p[2] - min[2]) * k};
    }

    private static float dist(float[] a, float[] b) {
        float x = a[0] - b[0], y = a[1] - b[1], z = a[2] - b[2];
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static int colorAt(Tri t, float s, float u, float v) {
        if (t.map() != null && t.uva() != null) {
            float tu = t.uva()[0] * s + t.uvb()[0] * u + t.uvc()[0] * v;
            float tv = t.uva()[1] * s + t.uvb()[1] * u + t.uvc()[1] * v;
            int x = Math.floorMod((int) (tu * t.map().getWidth()), t.map().getWidth());
            int y = Math.floorMod((int) ((1 - tv) * t.map().getHeight()), t.map().getHeight());
            int c = t.map().getRGB(x, y);
            return (c >>> 24) < 128 ? -1 : c & 0xFFFFFF;
        }
        return t.rgb();
    }

    /** Закрашивает всё, куда снаружи не пройти воздухом, ближайшим блоком сверху по столбцу. */
    private static void fillInside(VoxelGrid g) {
        boolean[] outside = new boolean[g.cells.length];
        ArrayDeque<int[]> queue = new ArrayDeque<>();
        for (int y = 0; y < g.h; y++) {
            for (int z = 0; z < g.l; z++) {
                for (int x = 0; x < g.w; x++) {
                    boolean edge = x == 0 || y == 0 || z == 0 || x == g.w - 1 || y == g.h - 1 || z == g.l - 1;
                    int i = x + z * g.w + y * g.w * g.l;
                    if (edge && g.cells[i] == 0) {
                        outside[i] = true;
                        queue.add(new int[]{x, y, z});
                    }
                }
            }
        }
        int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        while (!queue.isEmpty()) {
            int[] p = queue.poll();
            for (int[] d : dirs) {
                int x = p[0] + d[0], y = p[1] + d[1], z = p[2] + d[2];
                if (x < 0 || y < 0 || z < 0 || x >= g.w || y >= g.h || z >= g.l) continue;
                int i = x + z * g.w + y * g.w * g.l;
                if (outside[i] || g.cells[i] != 0) continue;
                outside[i] = true;
                queue.add(new int[]{x, y, z});
            }
        }
        for (int z = 0; z < g.l; z++) {
            for (int x = 0; x < g.w; x++) {
                short above = 0;
                for (int y = g.h - 1; y >= 0; y--) {
                    int i = x + z * g.w + y * g.w * g.l;
                    if (g.cells[i] != 0) above = g.cells[i];
                    else if (!outside[i] && above != 0) g.cells[i] = above;
                }
            }
        }
    }

    private static List<Tri> readStl(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        List<Tri> out = new ArrayList<>();
        int gray = 0xB8B8B8;
        boolean binary = bytes.length >= 84;
        if (binary) {
            long count = ByteBuffer.wrap(bytes, 80, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
            binary = 84 + count * 50 == bytes.length;
        }
        if (binary) {
            ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            b.position(80);
            int count = b.getInt();
            for (int i = 0; i < count; i++) {
                b.position(b.position() + 12);
                float[] p1 = {b.getFloat(), b.getFloat(), b.getFloat()};
                float[] p2 = {b.getFloat(), b.getFloat(), b.getFloat()};
                float[] p3 = {b.getFloat(), b.getFloat(), b.getFloat()};
                b.getShort();
                out.add(new Tri(p1, p2, p3, null, null, null, gray, null, null));
            }
            return out;
        }
        List<float[]> pts = new ArrayList<>();
        for (String line : new String(bytes, StandardCharsets.ISO_8859_1).split("\\R")) {
            String t = line.trim();
            if (!t.startsWith("vertex")) continue;
            String[] w = t.split("\\s+");
            if (w.length < 4) continue;
            pts.add(new float[]{Float.parseFloat(w[1]), Float.parseFloat(w[2]), Float.parseFloat(w[3])});
            if (pts.size() == 3) {
                out.add(new Tri(pts.get(0), pts.get(1), pts.get(2), null, null, null, gray, null, null));
                pts.clear();
            }
        }
        return out;
    }

    private record Material(int rgb, BufferedImage map) {}

    private static List<Tri> readObj(Path file) throws IOException {
        List<float[]> vs = new ArrayList<>();
        List<float[]> vts = new ArrayList<>();
        Map<String, Material> materials = new HashMap<>();
        Material current = new Material(0xB8B8B8, null);
        String currentName = null;
        List<Tri> out = new ArrayList<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.ISO_8859_1)) {
            String line = raw.trim();
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            String[] w = line.split("\\s+");
            switch (w[0]) {
                case "v" -> vs.add(new float[]{Float.parseFloat(w[1]), Float.parseFloat(w[2]), Float.parseFloat(w[3])});
                case "vt" -> vts.add(new float[]{Float.parseFloat(w[1]), w.length > 2 ? Float.parseFloat(w[2]) : 0});
                case "mtllib" -> readMtl(file.resolveSibling(line.substring(7).trim()), materials);
                case "usemtl" -> {
                    currentName = line.substring(7).trim();
                    current = materials.getOrDefault(currentName, current);
                }
                case "f" -> {
                    int[][] c = new int[w.length - 1][];
                    for (int i = 1; i < w.length; i++) {
                        String[] parts = w[i].split("/");
                        int v = index(parts[0], vs.size());
                        int vt = parts.length > 1 && !parts[1].isEmpty() ? index(parts[1], vts.size()) : -1;
                        c[i - 1] = new int[]{v, vt};
                    }
                    for (int i = 1; i + 1 < c.length; i++) {
                        float[] ua = c[0][1] >= 0 ? vts.get(c[0][1]) : null;
                        float[] ub = c[i][1] >= 0 ? vts.get(c[i][1]) : null;
                        float[] uc = c[i + 1][1] >= 0 ? vts.get(c[i + 1][1]) : null;
                        boolean textured = ua != null && ub != null && uc != null;
                        out.add(new Tri(vs.get(c[0][0]).clone(), vs.get(c[i][0]).clone(), vs.get(c[i + 1][0]).clone(),
                                textured ? ua : null, textured ? ub : null, textured ? uc : null, current.rgb(), textured ? current.map() : null, currentName));
                    }
                }
                default -> { }
            }
        }
        return out;
    }

    private static int index(String s, int size) {
        int i = Integer.parseInt(s);
        return i < 0 ? size + i : i - 1;
    }

    /**
     * Маленькая текстура — это текстура блока: берём её средний цвет. По отдельным её пикселям одна
     * доска превратилась бы в россыпь разных блоков, а так дуб остаётся дубом.
     */
    private static Material material(int rgb, BufferedImage map) {
        if (map == null || Math.max(map.getWidth(), map.getHeight()) > 64) return new Material(rgb, map);
        long r = 0, g = 0, b = 0, n = 0;
        for (int y = 0; y < map.getHeight(); y++) {
            for (int x = 0; x < map.getWidth(); x++) {
                int c = map.getRGB(x, y);
                if ((c >>> 24) < 128) continue;
                r += (c >> 16) & 255;
                g += (c >> 8) & 255;
                b += c & 255;
                n++;
            }
        }
        if (n == 0) return new Material(rgb, map);
        return new Material((int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n), null);
    }

    private static void readMtl(Path file, Map<String, Material> out) {
        if (!Files.isRegularFile(file)) return;
        try {
            String name = null;
            int rgb = 0xB8B8B8;
            BufferedImage map = null;
            for (String raw : Files.readAllLines(file, StandardCharsets.ISO_8859_1)) {
                String line = raw.trim();
                String[] w = line.split("\\s+");
                if (w[0].equals("newmtl")) {
                    if (name != null) out.put(name, material(rgb, map));
                    name = line.substring(7).trim();
                    rgb = 0xB8B8B8;
                    map = null;
                } else if (w[0].equals("Kd") && w.length >= 4) {
                    rgb = (int) (Float.parseFloat(w[1]) * 255) << 16 | (int) (Float.parseFloat(w[2]) * 255) << 8 | (int) (Float.parseFloat(w[3]) * 255);
                } else if (w[0].equals("map_Kd") && w.length >= 2) {
                    // В пути бывают пробелы, а параметры вроде -s идут перед ним.
                    String name2 = line.substring(6).trim();
                    Path image = file.resolveSibling(name2);
                    if (!Files.isRegularFile(image)) image = file.resolveSibling(w[w.length - 1]);
                    if (Files.isRegularFile(image)) map = ImageIO.read(image.toFile());
                }
            }
            if (name != null) out.put(name, material(rgb, map));
        } catch (IOException | RuntimeException ignored) {
            // без материалов модель будет серой, это не повод отказать
        }
    }
}
