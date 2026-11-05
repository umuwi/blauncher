package com.example.launcher.ui.blockart;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Чертежи: .schem (Sponge, для WorldEdit и Litematica), .litematic (Litematica), .nbt (структура игры, для
 * блока структур и /place template). Только блоки: сущности и содержимое сундуков не переносятся.
 */
final class SchematicIO {

    private SchematicIO() {
    }

    static boolean isSchematic(String fileName) {
        String n = fileName.toLowerCase(Locale.ROOT);
        return n.endsWith(".schem") || n.endsWith(".schematic") || n.endsWith(".litematic") || n.endsWith(".nbt");
    }

    static VoxelGrid load(Path file) throws IOException {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        Map<String, Object> root = Nbt.read(file);
        try {
            if (n.endsWith(".litematic")) return readLitematic(root);
            if (n.endsWith(".nbt")) return readStructure(root);
            return readSponge(root);
        } catch (ClassCastException | NullPointerException | IndexOutOfBoundsException | IllegalArgumentException e) {
            throw new IOException("unsupported file: " + e, e);
        }
    }

    static void save(VoxelGrid g, Path file, int dataVersion, String name) throws IOException {
        String n = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.endsWith(".litematic")) {
            Nbt.write(file, "", litematic(g, dataVersion, name));
        } else if (n.endsWith(".nbt")) {
            Nbt.write(file, "", structure(g, dataVersion));
        } else {
            Nbt.write(file, "Schematic", sponge(g, dataVersion));
        }
    }

    // ------------------------------------------------------------------ .schem

    private static Map<String, Object> sponge(VoxelGrid g, int dataVersion) {
        Map<String, Object> palette = new LinkedHashMap<>();
        for (int i = 0; i < g.palette.size(); i++) palette.put(g.palette.get(i), i);
        ByteArrayOutputStream data = new ByteArrayOutputStream(g.cells.length);
        for (short c : g.cells) {
            int v = c & 0xFFFF;
            while ((v & ~0x7F) != 0) {
                data.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            data.write(v);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Version", 2);
        root.put("DataVersion", dataVersion);
        root.put("Width", (short) g.w);
        root.put("Height", (short) g.h);
        root.put("Length", (short) g.l);
        root.put("Offset", new int[]{0, 0, 0});
        root.put("PaletteMax", g.palette.size());
        root.put("Palette", palette);
        root.put("BlockData", data.toByteArray());
        return root;
    }

    @SuppressWarnings("unchecked")
    private static VoxelGrid readSponge(Map<String, Object> top) throws IOException {
        // Версия 3 кладёт всё в Schematic → Blocks, версия 2 — прямо в корень.
        Map<String, Object> root = top.get("Schematic") instanceof Map ? (Map<String, Object>) top.get("Schematic") : top;
        Map<String, Object> holder = root.get("Blocks") instanceof Map ? (Map<String, Object>) root.get("Blocks") : root;
        Object paletteTag = holder.get("Palette");
        byte[] data = (byte[]) (holder.containsKey("Data") ? holder.get("Data") : holder.get("BlockData"));
        if (paletteTag == null || data == null) throw new IOException("not a Sponge schematic");
        int w = ((Number) root.get("Width")).intValue() & 0xFFFF;
        int h = ((Number) root.get("Height")).intValue() & 0xFFFF;
        int l = ((Number) root.get("Length")).intValue() & 0xFFFF;
        VoxelGrid g = new VoxelGrid(w, h, l);
        Map<Integer, Short> map = new TreeMap<>();
        for (var e : ((Map<String, Object>) paletteTag).entrySet()) {
            map.put(((Number) e.getValue()).intValue(), idForState(g, e.getKey()));
        }
        int p = 0;
        for (int i = 0; i < g.cells.length; i++) {
            int v = 0, shift = 0, b;
            do {
                b = data[p++];
                v |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            Short id = map.get(v);
            g.cells[i] = id == null ? 0 : id;
        }
        return g;
    }

    private static short idForState(VoxelGrid g, String state) {
        return isAir(state) ? 0 : g.idOf(state);
    }

    private static boolean isAir(String state) {
        String id = VoxelGrid.idPart(state);
        return id.equals("air") || id.equals("cave_air") || id.equals("void_air") || id.equals("structure_void");
    }

    // ------------------------------------------------------------------ .litematic

    private static Map<String, Object> vec(int x, int y, int z) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("x", x);
        v.put("y", y);
        v.put("z", z);
        return v;
    }

    private static Map<String, Object> litematic(VoxelGrid g, int dataVersion, String name) {
        int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, g.palette.size() - 1)));
        long[] packed = new long[(int) Math.ceil(g.cells.length * (double) bits / 64)];
        long mask = (1L << bits) - 1;
        for (int i = 0; i < g.cells.length; i++) {
            long v = g.cells[i] & mask;
            long bit = (long) i * bits;
            int word = (int) (bit >>> 6);
            int off = (int) (bit & 63);
            packed[word] |= v << off;
            if (off + bits > 64) packed[word + 1] |= v >>> (64 - off);
        }
        List<Object> palette = new ArrayList<>();
        for (String state : g.palette) palette.add(stateTag(state));

        Map<String, Object> region = new LinkedHashMap<>();
        region.put("Position", vec(0, 0, 0));
        region.put("Size", vec(g.w, g.h, g.l));
        region.put("BlockStatePalette", palette);
        region.put("BlockStates", packed);
        region.put("Entities", new ArrayList<>());
        region.put("TileEntities", new ArrayList<>());
        region.put("PendingBlockTicks", new ArrayList<>());
        region.put("PendingFluidTicks", new ArrayList<>());
        Map<String, Object> regions = new LinkedHashMap<>();
        regions.put(name, region);

        long now = System.currentTimeMillis();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("Name", name);
        meta.put("Author", "BLauncher");
        meta.put("Description", "");
        meta.put("RegionCount", 1);
        meta.put("TimeCreated", now);
        meta.put("TimeModified", now);
        meta.put("TotalVolume", g.cells.length);
        meta.put("TotalBlocks", g.blockCount());
        meta.put("EnclosingSize", vec(g.w, g.h, g.l));

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Version", 6);
        root.put("SubVersion", 1);
        root.put("MinecraftDataVersion", dataVersion);
        root.put("Metadata", meta);
        root.put("Regions", regions);
        return root;
    }

    @SuppressWarnings("unchecked")
    private static VoxelGrid readLitematic(Map<String, Object> root) throws IOException {
        Map<String, Object> regions = (Map<String, Object>) root.get("Regions");
        if (regions == null || regions.isEmpty()) throw new IOException("no regions");
        // Области могут быть разного размера и с отрицательным размером: сводим в общую коробку.
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Object o : regions.values()) {
            Map<String, Object> r = (Map<String, Object>) o;
            int[] b = regionBox(r);
            minX = Math.min(minX, b[0]);
            minY = Math.min(minY, b[1]);
            minZ = Math.min(minZ, b[2]);
            maxX = Math.max(maxX, b[0] + b[3]);
            maxY = Math.max(maxY, b[1] + b[4]);
            maxZ = Math.max(maxZ, b[2] + b[5]);
        }
        VoxelGrid g = new VoxelGrid(maxX - minX, maxY - minY, maxZ - minZ);
        for (Object o : regions.values()) {
            Map<String, Object> r = (Map<String, Object>) o;
            int[] b = regionBox(r);
            List<Object> paletteTag = (List<Object>) r.get("BlockStatePalette");
            short[] ids = new short[paletteTag.size()];
            for (int i = 0; i < ids.length; i++) ids[i] = idForState(g, stateName((Map<String, Object>) paletteTag.get(i)));
            long[] packed = (long[]) r.get("BlockStates");
            int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, ids.length - 1)));
            long mask = (1L << bits) - 1;
            int i = 0;
            for (int y = 0; y < b[4]; y++) {
                for (int z = 0; z < b[5]; z++) {
                    for (int x = 0; x < b[3]; x++, i++) {
                        long bit = (long) i * bits;
                        int word = (int) (bit >>> 6);
                        int off = (int) (bit & 63);
                        long v = packed[word] >>> off;
                        if (off + bits > 64) v |= packed[word + 1] << (64 - off);
                        int id = (int) (v & mask);
                        if (id < ids.length && ids[id] != 0) g.set(b[0] - minX + x, b[1] - minY + y, b[2] - minZ + z, ids[id]);
                    }
                }
            }
        }
        return g;
    }

    /** {минимум x, y, z, размер x, y, z} области с учётом отрицательного размера. */
    @SuppressWarnings("unchecked")
    private static int[] regionBox(Map<String, Object> r) {
        Map<String, Object> pos = (Map<String, Object>) r.get("Position");
        Map<String, Object> size = (Map<String, Object>) r.get("Size");
        int[] out = new int[6];
        String[] axes = {"x", "y", "z"};
        for (int i = 0; i < 3; i++) {
            int p = ((Number) pos.get(axes[i])).intValue();
            int s = ((Number) size.get(axes[i])).intValue();
            out[i] = s < 0 ? p + s + 1 : p;
            out[i + 3] = Math.abs(s);
        }
        return out;
    }

    /**
     * Состояние блока для палитры. С 26.3 игра читает id/properties, раньше — Name/Properties, а лишние
     * поля и те и другие молча пропускают: без обоих ключей в 26.3 постройка встаёт воздухом, без ошибки
     * (05.11.2026, проверено /place template на сервере 26.3).
     */
    private static Map<String, Object> stateTag(String state) {
        Map<String, Object> tag = new LinkedHashMap<>();
        int b = state.indexOf('[');
        tag.put("Name", b >= 0 ? state.substring(0, b) : state);
        tag.put("id", b >= 0 ? state.substring(0, b) : state);
        if (b >= 0) {
            Map<String, Object> props = new LinkedHashMap<>();
            for (String pair : state.substring(b + 1, state.length() - 1).split(",")) {
                int eq = pair.indexOf('=');
                if (eq > 0) props.put(pair.substring(0, eq), pair.substring(eq + 1));
            }
            tag.put("Properties", props);
            tag.put("properties", props);
        }
        return tag;
    }

    @SuppressWarnings("unchecked")
    private static String stateName(Map<String, Object> tag) {
        String name = (String) (tag.containsKey("id") ? tag.get("id") : tag.get("Name"));
        Map<String, Object> props = (Map<String, Object>) (tag.containsKey("properties") ? tag.get("properties") : tag.get("Properties"));
        if (props == null || props.isEmpty()) return name;
        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;
        for (var e : props.entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------------ .nbt (структура игры)

    private static List<Object> ints(int... a) {
        List<Object> out = new ArrayList<>(a.length);
        for (int v : a) out.add(v);
        return out;
    }

    private static Map<String, Object> structure(VoxelGrid g, int dataVersion) {
        List<Object> palette = new ArrayList<>();
        for (String state : g.palette) palette.add(stateTag(state));
        List<Object> blocks = new ArrayList<>();
        for (int y = 0; y < g.h; y++) {
            for (int z = 0; z < g.l; z++) {
                for (int x = 0; x < g.w; x++) {
                    int id = g.at(x, y, z);
                    if (id == 0) continue;
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("pos", ints(x, y, z));
                    b.put("state", id);
                    blocks.add(b);
                }
            }
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("DataVersion", dataVersion);
        root.put("size", ints(g.w, g.h, g.l));
        root.put("palette", palette);
        root.put("blocks", blocks);
        root.put("entities", new ArrayList<>());
        return root;
    }

    @SuppressWarnings("unchecked")
    private static VoxelGrid readStructure(Map<String, Object> root) throws IOException {
        List<Object> size = (List<Object>) root.get("size");
        List<Object> paletteTag = (List<Object>) root.get("palette");
        if (paletteTag == null && root.get("palettes") instanceof List<?> many && !many.isEmpty()) paletteTag = (List<Object>) many.get(0);
        if (size == null || paletteTag == null) throw new IOException("not a structure file");
        VoxelGrid g = new VoxelGrid(((Number) size.get(0)).intValue(), ((Number) size.get(1)).intValue(), ((Number) size.get(2)).intValue());
        short[] ids = new short[paletteTag.size()];
        for (int i = 0; i < ids.length; i++) ids[i] = idForState(g, stateName((Map<String, Object>) paletteTag.get(i)));
        for (Object o : (List<Object>) root.get("blocks")) {
            Map<String, Object> b = (Map<String, Object>) o;
            List<Object> pos = (List<Object>) b.get("pos");
            g.set(((Number) pos.get(0)).intValue(), ((Number) pos.get(1)).intValue(), ((Number) pos.get(2)).intValue(),
                    ids[((Number) b.get("state")).intValue()]);
        }
        return g;
    }
}
