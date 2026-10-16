package com.example.launcher.ui.studio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Block and item models and textures, read straight out of a Minecraft client jar the launcher
 * has already downloaded.
 *
 * <p>Nothing of Mojang's is shipped with the launcher: the studio needs some installed version to
 * draw real textures, and without one it falls back to coloured boxes (see {@link #empty()}).
 *
 * <p>Geometry comes out as {@link Quad}s in Minecraft's own coordinates (x east, y up, z south,
 * one block = 0..1), resolved the way the game does it for the parts that matter here: the
 * blockstate's default variant, the model's parent chain, {@code #texture} references, element
 * from/to, per-face uv and uv rotation, element rotation and the variant's x/y rotation. Things a
 * block entity draws in code (chests, signs, banners) have no elements and come out as a cube
 * with their particle texture.
 */
public final class McAssets implements AutoCloseable {

    /** One textured face: corners TL, TR, BR, BL in block units, uv for the same corners. */
    public record Quad(float[] pos, float[] uv, String texture, boolean tinted, float shade) {}

    /** The default state of a block: the properties written into the command and its geometry. */
    public record BlockState(Map<String, String> properties, List<Quad> quads) {}

    private final ZipFile zip;
    private final String label;
    private final List<String> blocks;
    private final List<String> items;
    private final Map<String, JsonObject> jsonCache = new ConcurrentHashMap<>();
    private final Map<String, Image> textureCache = new ConcurrentHashMap<>();
    private Path jarPath;
    private final Map<String, Map<String, String>> langs = new ConcurrentHashMap<>();

    private McAssets(ZipFile zip, String label, List<String> blocks, List<String> items) {
        this.zip = zip;
        this.label = label;
        this.blocks = blocks;
        this.items = items;
    }

    /** No game installed: a short list of common ids, drawn as plain boxes. */
    public static McAssets empty() {
        List<String> common = List.of("stone", "cobblestone", "oak_planks", "oak_log", "glass", "white_wool",
                "red_wool", "blue_wool", "gold_block", "diamond_block", "iron_block", "bricks", "sandstone",
                "quartz_block", "grass_block", "dirt", "sea_lantern", "glowstone");
        List<String> commonItems = List.of("diamond_sword", "iron_sword", "bow", "apple", "golden_apple",
                "diamond", "emerald", "stick", "torch", "shield", "totem_of_undying", "book");
        return new McAssets(null, "", common, commonItems);
    }

    /**
     * Opens a client jar. Throws when it is not one (no blockstates inside), so the caller can try
     * the next installed version.
     */
    public static McAssets open(Path jar, String label) throws IOException {
        ZipFile zip = new ZipFile(jar.toFile());
        List<String> blocks = new ArrayList<>();
        List<String> itemsNew = new ArrayList<>();
        List<String> itemsOld = new ArrayList<>();
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (!name.endsWith(".json")) continue;
            if (name.startsWith("assets/minecraft/blockstates/")) {
                blocks.add(base(name));
            } else if (name.startsWith("assets/minecraft/items/") && name.indexOf('/', 23) < 0) {
                itemsNew.add(base(name));
            } else if (name.startsWith("assets/minecraft/models/item/") && name.indexOf('/', 29) < 0) {
                itemsOld.add(base(name));
            }
        }
        if (blocks.isEmpty()) {
            zip.close();
            throw new IOException("no blockstates in " + jar.getFileName());
        }
        // 1.21.4+ lists every item in items/; older jars only have item models, which also
        // include a few helper models that are not items, but that is harmless in a picker
        List<String> items = itemsNew.isEmpty() ? itemsOld : itemsNew;
        Collections.sort(blocks);
        Collections.sort(items);
        McAssets assets = new McAssets(zip, label, List.copyOf(blocks), List.copyOf(items));
        assets.jarPath = jar;
        return assets;
    }

    /**
     * The newest installed vanilla client jar under {@code versions/}: the one whose release time
     * in its own json is latest. Modded version folders have no jar of their own and are skipped.
     */
    public static McAssets openNewest(Path versionsDir) {
        if (versionsDir == null || !Files.isDirectory(versionsDir)) return empty();
        List<Path> candidates = new ArrayList<>();
        Map<Path, String> releaseTimes = new HashMap<>();
        try (var dirs = Files.list(versionsDir)) {
            for (Path dir : (Iterable<Path>) dirs::iterator) {
                String id = dir.getFileName().toString();
                Path jar = dir.resolve(id + ".jar");
                Path json = dir.resolve(id + ".json");
                if (!Files.isRegularFile(jar) || !Files.isRegularFile(json)) continue;
                try {
                    JsonObject meta = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
                    if (meta.has("inheritsFrom")) continue;   // a loader profile, not a game jar
                    // releases first, newest first; a snapshot only when no release is installed
                    String type = meta.has("type") ? meta.get("type").getAsString() : "";
                    releaseTimes.put(jar, ("release".equals(type) ? "1" : "0")
                            + (meta.has("releaseTime") ? meta.get("releaseTime").getAsString() : ""));
                    candidates.add(jar);
                } catch (Exception ignored) {
                    // unreadable version json: not a candidate
                }
            }
        } catch (IOException e) {
            return empty();
        }
        candidates.sort((a, b) -> releaseTimes.get(b).compareTo(releaseTimes.get(a)));
        for (Path jar : candidates) {
            try {
                return open(jar, jar.getParent().getFileName().toString());
            } catch (IOException ignored) {
                // a partial download; try the next
            }
        }
        return empty();
    }

    public boolean hasTextures() {
        return zip != null;
    }

    /** The version whose textures are shown, for the "textures from 1.21.4" note. */
    public String versionLabel() {
        return label;
    }

    public List<String> blockIds() {
        return blocks;
    }

    public List<String> itemIds() {
        return items;
    }

    @Override
    public void close() {
        if (zip != null) {
            try {
                zip.close();
            } catch (IOException ignored) {
                // closing a read-only zip
            }
        }
    }

    // ------------------------------------------------------------------ blocks

    public BlockState blockState(String id) {
        JsonObject state = json("assets/minecraft/blockstates/" + id + ".json");
        Map<String, String> props = new LinkedHashMap<>();
        List<Quad> quads = new ArrayList<>();
        if (state != null && state.has("variants")) {
            JsonObject variants = state.getAsJsonObject("variants");
            String key = bestVariant(variants.keySet());
            if (key != null) {
                if (!key.isEmpty()) {
                    for (String pair : key.split(",")) {
                        int eq = pair.indexOf('=');
                        if (eq > 0) props.put(pair.substring(0, eq), pair.substring(eq + 1));
                    }
                }
                addVariant(quads, variants.get(key));
            }
        } else if (state != null && state.has("multipart")) {
            JsonArray parts = state.getAsJsonArray("multipart");
            // Panes and bars on their own are a thin post that is hard to even see, so they are
            // shown (and placed) as a straight piece running north to south.
            Map<String, String> straight = id.endsWith("_pane") || id.endsWith("bars")
                    ? Map.of("north", "true", "south", "true", "east", "false", "west", "false") : Map.of();
            if (!straight.isEmpty()) props.putAll(new java.util.TreeMap<>(straight));
            boolean any = false;
            for (JsonElement part : parts) {
                JsonObject p = part.getAsJsonObject();
                if (!p.has("when") || (!straight.isEmpty() && matches(p.get("when"), straight))) {
                    addVariant(quads, p.get("apply"));
                    any = true;
                }
            }
            if (!any && !parts.isEmpty()) addVariant(quads, parts.get(0).getAsJsonObject().get("apply"));
        }
        if (quads.isEmpty()) quads.addAll(entityModel(id));
        if (quads.isEmpty() && id.startsWith("waxed_")) quads.addAll(blockState(id.substring(6)).quads());
        if (quads.isEmpty()) quads.addAll(fallbackCube(id, "block/" + id));
        return new BlockState(props, quads);
    }

    /**
     * The state a placed block most often is in: upright logs, bottom slabs, straight stairs facing
     * north, nothing connected. Scored rather than hard-coded, so new blocks fall in line.
     */
    static String bestVariant(java.util.Set<String> keys) {
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String key : keys) {
            int score = 0;
            if (key.isEmpty() || key.equals("normal")) score += 10;
            if (key.contains("axis=y")) score += 4;
            if (key.contains("half=bottom") || key.contains("half=lower")) score += 2;
            if (key.contains("type=bottom") || key.contains("type=single")) score += 2;
            if (key.contains("shape=straight")) score += 2;
            if (key.contains("facing=north")) score += 1;
            if (key.contains("face=floor")) score += 1;
            if (key.contains("powered=false") || key.contains("lit=false") || key.contains("open=false")) score += 1;
            score -= countOf(key, "=true");
            if (score > bestScore) {
                bestScore = score;
                best = key;
            }
        }
        return best;
    }

    /** A multipart "when" (plain, or with OR/AND) against a state given as property -> value. */
    private static boolean matches(JsonElement when, Map<String, String> state) {
        if (when == null || !when.isJsonObject()) return false;
        JsonObject w = when.getAsJsonObject();
        if (w.has("OR")) {
            for (JsonElement e : w.getAsJsonArray("OR")) if (matches(e, state)) return true;
            return false;
        }
        if (w.has("AND")) {
            for (JsonElement e : w.getAsJsonArray("AND")) if (!matches(e, state)) return false;
            return true;
        }
        for (var e : w.entrySet()) {
            String have = state.get(e.getKey());
            if (have == null) return false;
            boolean ok = false;
            for (String option : e.getValue().getAsString().split("\\|")) if (option.equals(have)) ok = true;
            if (!ok) return false;
        }
        return true;
    }

    private static int countOf(String s, String part) {
        int n = 0;
        for (int i = s.indexOf(part); i >= 0; i = s.indexOf(part, i + 1)) n++;
        return n;
    }

    private void addVariant(List<Quad> out, JsonElement variant) {
        if (variant == null) return;
        if (variant.isJsonArray()) {
            if (variant.getAsJsonArray().isEmpty()) return;
            variant = variant.getAsJsonArray().get(0);   // random variants: the first is as good as any
        }
        JsonObject v = variant.getAsJsonObject();
        if (!v.has("model")) return;
        int rx = v.has("x") ? v.get("x").getAsInt() : 0;
        int ry = v.has("y") ? v.get("y").getAsInt() : 0;
        String model = v.get("model").getAsString();
        // before 1.13 blockstates named models relative to models/block/
        if (!model.contains("/")) model = "block/" + model;
        List<Quad> quads = modelQuads(model, false);
        for (Quad q : quads) out.add(rotateVariant(q, rx, ry));
    }

    // ------------------------------------------------------------------ items

    /** An item as item_display draws it in the "none" context: block items as their block. */
    public List<Quad> itemQuads(String id) {
        String model = null;
        JsonObject def = json("assets/minecraft/items/" + id + ".json");
        if (def != null) model = firstModelName(def);
        if (model == null) model = "minecraft:item/" + id;
        List<Quad> quads = modelQuads(model, true);
        if (quads.isEmpty()) {
            // a "special" item (chest, shield, banner...): the block, or at least something to see
            if (blocks.contains(id)) return blockState(id).quads();
            quads = fallbackCube(id, "item/" + id);
        }
        return quads;
    }

    /** The first model reference inside an item definition, whatever selector wraps it. */
    private static String firstModelName(JsonElement e) {
        if (e == null) return null;
        if (e.isJsonObject()) {
            JsonObject o = e.getAsJsonObject();
            String type = o.has("type") ? o.get("type").getAsString() : "";
            if (o.has("model") && o.get("model").isJsonPrimitive() && !type.endsWith("special")) {
                return o.get("model").getAsString();
            }
            if (type.endsWith("special") && o.has("base")) return o.get("base").getAsString();
            for (String key : new String[]{"model", "fallback", "on_false", "on_true", "cases", "entries", "models"}) {
                String found = firstModelName(o.get(key));
                if (found != null) return found;
            }
        } else if (e.isJsonArray()) {
            for (JsonElement child : e.getAsJsonArray()) {
                String found = firstModelName(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ models

    /** A model with its parent chain resolved: merged textures, the elements that apply. */
    private record Resolved(Map<String, String> textures, JsonArray elements, boolean generated) {}

    private Resolved resolve(String modelName) {
        Map<String, String> textures = new HashMap<>();
        JsonArray elements = null;
        boolean generated = false;
        String name = modelName;
        for (int depth = 0; depth < 16 && name != null; depth++) {
            String path = modelPath(name);
            if (path == null) {
                if (name.endsWith("builtin/generated")) generated = true;
                break;
            }
            JsonObject model = json(path);
            if (model == null) break;
            if (model.has("textures")) {
                for (var t : model.getAsJsonObject("textures").entrySet()) {
                    // 26.x writes some as {"sprite": "...", "force_translucent": true}
                    JsonElement v = t.getValue();
                    String ref = v.isJsonPrimitive() ? v.getAsString()
                            : v.isJsonObject() && v.getAsJsonObject().has("sprite")
                                    ? v.getAsJsonObject().get("sprite").getAsString() : null;
                    if (ref != null) textures.putIfAbsent(t.getKey(), ref);
                }
            }
            if (elements == null && model.has("elements")) elements = model.getAsJsonArray("elements");
            String parent = model.has("parent") ? model.get("parent").getAsString() : null;
            if (parent != null && (parent.endsWith("builtin/generated") || parent.endsWith("item/generated"))) {
                generated = true;
            }
            name = parent;
        }
        return new Resolved(textures, elements, generated);
    }

    private static String modelPath(String name) {
        if (name == null || name.startsWith("builtin/")) return null;
        String n = name.startsWith("minecraft:") ? name.substring(10) : name;
        if (n.contains(":")) return null;
        return "assets/minecraft/models/" + n + ".json";
    }

    private List<Quad> modelQuads(String modelName, boolean asItem) {
        if (zip == null) return List.of();
        Resolved r = resolve(modelName);
        if (r.elements() != null) return elementQuads(r);
        if (r.generated() || asItem) return spriteQuads(r);
        return List.of();
    }

    /**
     * item/generated: what the game builds from a flat sprite - the picture on the front and the
     * back of a one-pixel-thick slab, and a thin side wall along every edge where an opaque pixel
     * meets a transparent one. Without the walls an item is a paper card, which is exactly how a
     * dropped item looks and not how an item_display does.
     */
    private List<Quad> spriteQuads(Resolved r) {
        List<Quad> out = new ArrayList<>();
        float half = 1f / 32f;
        for (int layer = 0; layer < 5; layer++) {
            String tex = textureRef(r.textures(), "#layer" + layer);
            if (tex == null) break;
            float lift = layer * 0.001f;
            float zf = 0.5f + half + lift, zb = 0.5f - half - lift;
            // front (south) and back (north), the back mirrored as the game draws it
            out.add(new Quad(new float[]{0, 1, zf, 1, 1, zf, 1, 0, zf, 0, 0, zf},
                    new float[]{0, 0, 1, 0, 1, 1, 0, 1}, tex, false, 1f));
            out.add(new Quad(new float[]{1, 1, zb, 0, 1, zb, 0, 0, zb, 1, 0, zb},
                    new float[]{1, 0, 0, 0, 0, 1, 1, 1}, tex, false, 0.8f));
            if (layer > 0) continue;   // overlay layers sit on the walls the base already has
            Image image = texture(tex);
            if (image == null) continue;
            javafx.scene.image.PixelReader px = image.getPixelReader();
            int w = (int) image.getWidth(), h = (int) image.getHeight();
            if (px == null || w > 64 || h > 64) continue;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    if (!opaque(px, x, y, w, h)) continue;
                    float x0 = (float) x / w, x1 = (float) (x + 1) / w;
                    float y0 = 1 - (float) (y + 1) / h, y1 = 1 - (float) y / h;
                    float u0 = (x + 0.25f) / w, u1 = (x + 0.75f) / w, v0 = (y + 0.25f) / h, v1 = (y + 0.75f) / h;
                    float[] uv = {u0, v0, u1, v0, u1, v1, u0, v1};
                    if (!opaque(px, x, y - 1, w, h))   // top wall
                        out.add(new Quad(new float[]{x0, y1, zb, x1, y1, zb, x1, y1, zf, x0, y1, zf}, uv, tex, false, 1f));
                    if (!opaque(px, x, y + 1, w, h))   // bottom wall
                        out.add(new Quad(new float[]{x0, y0, zf, x1, y0, zf, x1, y0, zb, x0, y0, zb}, uv, tex, false, 0.5f));
                    if (!opaque(px, x - 1, y, w, h))   // left wall
                        out.add(new Quad(new float[]{x0, y1, zb, x0, y1, zf, x0, y0, zf, x0, y0, zb}, uv, tex, false, 0.6f));
                    if (!opaque(px, x + 1, y, w, h))   // right wall
                        out.add(new Quad(new float[]{x1, y1, zf, x1, y1, zb, x1, y0, zb, x1, y0, zf}, uv, tex, false, 0.6f));
                }
            }
        }
        return out;
    }

    private static boolean opaque(javafx.scene.image.PixelReader px, int x, int y, int w, int h) {
        return x >= 0 && y >= 0 && x < w && y < h && (px.getArgb(x, y) >>> 24) > 16;
    }

    private List<Quad> elementQuads(Resolved r) {
        List<Quad> out = new ArrayList<>();
        int index = 0;
        for (JsonElement e : r.elements()) {
            JsonObject el = e.getAsJsonObject();
            float[] from = vec(el.getAsJsonArray("from"));
            float[] to = vec(el.getAsJsonArray("to"));
            // overlays (grass sides) lie on the faces of the element below them: lift each
            // element a hair so the two do not flicker through each other
            float lift = index++ * 0.002f;
            from = new float[]{from[0] - lift, from[1] - lift, from[2] - lift};
            to = new float[]{to[0] + lift, to[1] + lift, to[2] + lift};
            if (!el.has("faces")) continue;
            JsonObject faces = el.getAsJsonObject("faces");
            for (var f : faces.entrySet()) {
                JsonObject face = f.getValue().getAsJsonObject();
                String tex = textureRef(r.textures(), face.has("texture") ? face.get("texture").getAsString() : null);
                if (tex == null) continue;
                Quad q = faceQuad(f.getKey(), from, to, face, tex);
                if (q == null) continue;
                if (el.has("rotation")) q = rotateElement(q, el.getAsJsonObject("rotation"));
                out.add(q);
            }
        }
        return out;
    }

    private static float[] vec(JsonArray a) {
        return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat()};
    }

    /**
     * One face of an element: its corners as the viewer standing outside sees them (top-left,
     * top-right, bottom-right, bottom-left) and the uv for each, rotated as the face asks.
     * Positions are in 0..16 here and divided by 16 at the end.
     */
    private Quad faceQuad(String dir, float[] f, float[] t, JsonObject face, String tex) {
        float x1 = f[0], y1 = f[1], z1 = f[2], x2 = t[0], y2 = t[1], z2 = t[2];
        float[] p;
        float[] uv;   // default uv, as the game computes it from the element's position
        float shade;
        switch (dir) {
            case "north" -> { p = new float[]{x2, y2, z1, x1, y2, z1, x1, y1, z1, x2, y1, z1};
                uv = new float[]{16 - x2, 16 - y2, 16 - x1, 16 - y1}; shade = 0.8f; }
            case "south" -> { p = new float[]{x1, y2, z2, x2, y2, z2, x2, y1, z2, x1, y1, z2};
                uv = new float[]{x1, 16 - y2, x2, 16 - y1}; shade = 0.8f; }
            case "west" -> { p = new float[]{x1, y2, z1, x1, y2, z2, x1, y1, z2, x1, y1, z1};
                uv = new float[]{z1, 16 - y2, z2, 16 - y1}; shade = 0.6f; }
            case "east" -> { p = new float[]{x2, y2, z2, x2, y2, z1, x2, y1, z1, x2, y1, z2};
                uv = new float[]{16 - z2, 16 - y2, 16 - z1, 16 - y1}; shade = 0.6f; }
            case "up" -> { p = new float[]{x1, y2, z1, x2, y2, z1, x2, y2, z2, x1, y2, z2};
                uv = new float[]{x1, z1, x2, z2}; shade = 1f; }
            case "down" -> { p = new float[]{x1, y1, z2, x2, y1, z2, x2, y1, z1, x1, y1, z1};
                uv = new float[]{x1, 16 - z2, x2, 16 - z1}; shade = 0.5f; }
            default -> { return null; }
        }
        if (face.has("uv")) uv = vec4(face.getAsJsonArray("uv"));
        float u1 = uv[0] / 16f, v1 = uv[1] / 16f, u2 = uv[2] / 16f, v2 = uv[3] / 16f;
        float[] corners = {u1, v1, u2, v1, u2, v2, u1, v2};
        int rotation = face.has("rotation") ? face.get("rotation").getAsInt() : 0;
        int steps = ((rotation / 90) % 4 + 4) % 4;
        // a texture turned clockwise: each corner takes the uv of the corner after it
        float[] rotated = new float[8];
        for (int i = 0; i < 4; i++) {
            int src = (i + steps) % 4;
            rotated[i * 2] = corners[src * 2];
            rotated[i * 2 + 1] = corners[src * 2 + 1];
        }
        for (int i = 0; i < p.length; i++) p[i] /= 16f;
        return new Quad(p, rotated, tex, face.has("tintindex"), shade);
    }

    private static float[] vec4(JsonArray a) {
        return new float[]{a.get(0).getAsFloat(), a.get(1).getAsFloat(), a.get(2).getAsFloat(), a.get(3).getAsFloat()};
    }

    private static Quad rotateElement(Quad q, JsonObject rot) {
        float[] origin = rot.has("origin") ? vec(rot.getAsJsonArray("origin")) : new float[]{8, 8, 8};
        String axis = rot.has("axis") ? rot.get("axis").getAsString() : "y";
        double angle = Math.toRadians(rot.has("angle") ? rot.get("angle").getAsDouble() : 0);
        return transform(q, origin[0] / 16f, origin[1] / 16f, origin[2] / 16f, axis, angle);
    }

    /** The blockstate's x then y rotation, about the block's centre. */
    private static Quad rotateVariant(Quad q, int rx, int ry) {
        Quad out = q;
        if (rx != 0) out = transform(out, 0.5f, 0.5f, 0.5f, "x-variant", Math.toRadians(rx));
        if (ry != 0) out = transform(out, 0.5f, 0.5f, 0.5f, "y-variant", Math.toRadians(ry));
        return out;
    }

    private static Quad transform(Quad q, float ox, float oy, float oz, String axis, double a) {
        float[] p = q.pos().clone();
        double c = Math.cos(a), s = Math.sin(a);
        for (int i = 0; i < 12; i += 3) {
            double x = p[i] - ox, y = p[i + 1] - oy, z = p[i + 2] - oz;
            double nx = x, ny = y, nz = z;
            switch (axis) {
                // element rotation: counter-clockwise looking down the positive axis, as in the game
                case "x" -> { ny = y * c - z * s; nz = y * s + z * c; }
                case "y" -> { nx = x * c + z * s; nz = -x * s + z * c; }
                case "z" -> { nx = x * c - y * s; ny = x * s + y * c; }
                // blockstate rotation: "y": 90 turns a north-facing model to face east,
                // "x": 90 turns it to face down
                case "x-variant" -> { ny = y * c + z * s; nz = -y * s + z * c; }
                case "y-variant" -> { nx = x * c - z * s; nz = x * s + z * c; }
                default -> { }
            }
            p[i] = (float) (nx + ox);
            p[i + 1] = (float) (ny + oy);
            p[i + 2] = (float) (nz + oz);
        }
        return new Quad(p, q.uv(), q.texture(), q.tinted(), q.shade());
    }

    /** "#side" -> "minecraft:block/oak_log", following references; null when it never lands. */
    private static String textureRef(Map<String, String> textures, String ref) {
        String r = ref;
        for (int i = 0; i < 10 && r != null; i++) {
            // newer models (heavy_core) name a variable without the '#': "texture": "all"
            if (!r.startsWith("#") && !r.contains(":") && !r.contains("/") && textures.containsKey(r)) r = "#" + r;
            if (!r.startsWith("#")) return r;
            r = textures.get(r.substring(1));
        }
        return null;
    }

    private List<Quad> fallbackCube(String id, String guess) {
        String tex = null;
        if (zip != null) {
            Resolved r = resolve("minecraft:" + guess);
            tex = textureRef(r.textures(), "#particle");
            if (tex == null && zip.getEntry("assets/minecraft/textures/" + guess + ".png") != null) tex = guess;
        }
        List<Quad> out = new ArrayList<>();
        JsonObject noUv = new JsonObject();
        for (String dir : new String[]{"north", "south", "west", "east", "up", "down"}) {
            out.add(faceQuad(dir, new float[]{0, 0, 0}, new float[]{16, 16, 16}, noUv, tex == null ? "" : tex));
        }
        return out;
    }

    // ------------------------------------------------------------------ block entities

    private static final java.util.Map<String, String> DYES = java.util.Map.ofEntries(
            java.util.Map.entry("white", "F9FFFE"), java.util.Map.entry("orange", "F9801D"),
            java.util.Map.entry("magenta", "C74EBD"), java.util.Map.entry("light_blue", "3AB3DA"),
            java.util.Map.entry("yellow", "FED83D"), java.util.Map.entry("lime", "80C71F"),
            java.util.Map.entry("pink", "F38BAA"), java.util.Map.entry("gray", "474F52"),
            java.util.Map.entry("light_gray", "9D9D97"), java.util.Map.entry("cyan", "169C9C"),
            java.util.Map.entry("purple", "8932B8"), java.util.Map.entry("blue", "3C44AA"),
            java.util.Map.entry("brown", "835432"), java.util.Map.entry("green", "5E7C16"),
            java.util.Map.entry("red", "B02E26"), java.util.Map.entry("black", "1D1D21"));

    /**
     * Blocks the game draws with code have no block model; these are built here from the same
     * entity textures and box layout the game's own models use (chests, shulker boxes, banners,
     * heads), so the studio shows them as they look rather than as a plain cube.
     */
    private List<Quad> entityModel(String id) {
        List<Quad> out = new ArrayList<>();
        if (zip == null) return out;
        String base = id.replace("_wall_", "_");
        if (base.endsWith("copper_chest")) {
            String age = base.replace("waxed_", "").replace("copper_chest", "").replace("_", "");
            String tex = "minecraft:entity/chest/copper" + (age.isEmpty() ? "" : "_" + age);
            box(out, tex, 64, 64, 1, 0, 1, 14, 10, 14, 0, 19, 1, null);
            box(out, tex, 64, 64, 1, 9, 1, 14, 5, 14, 0, 0, 1, null);
            box(out, tex, 64, 64, 7, 7, 15, 2, 4, 1, 0, 0, 1, null);
        } else if (base.equals("dragon_head")) {
            // the skull part of the dragon's head, from its 256 x 256 texture
            box(out, "minecraft:entity/enderdragon/dragon", 256, 256, 0, 0, 0, 16, 16, 16, 112, 30, 1, null);
        } else if (base.equals("chest") || base.equals("trapped_chest") || base.equals("ender_chest")) {
            String tex = "minecraft:entity/chest/" + (base.equals("chest") ? "normal" : base.equals("trapped_chest") ? "trapped" : "ender");
            box(out, tex, 64, 64, 1, 0, 1, 14, 10, 14, 0, 19, 1, null);   // body
            box(out, tex, 64, 64, 1, 9, 1, 14, 5, 14, 0, 0, 1, null);     // lid
            box(out, tex, 64, 64, 7, 7, 15, 2, 4, 1, 0, 0, 1, null);      // latch
        } else if (base.endsWith("shulker_box")) {
            String color = base.equals("shulker_box") ? "" : "_" + base.substring(0, base.length() - "_shulker_box".length());
            String tex = "minecraft:entity/shulker/shulker" + color;
            box(out, tex, 64, 64, 0, 0, 0, 16, 8, 16, 0, 28, 1, null);
            box(out, tex, 64, 64, 0, 4, 0, 16, 12, 16, 0, 0, 1, null);
        } else if (base.endsWith("_banner")) {
            String dye = DYES.getOrDefault(base.substring(0, base.length() - "_banner".length()), "F9FFFE");
            String tex = "minecraft:entity/banner/base";
            float k = 2f / 3f;   // the game draws standing banners at two thirds of the model
            box(out, tex, 64, 64, 7, 0, 7, 2, 42, 2, 44, 0, k, null);     // pole
            box(out, tex, 64, 64, -2, 40, 7, 20, 2, 2, 0, 42, k, null);   // bar
            box(out, tex, 64, 64, -2, 1, 9, 20, 40, 1, 0, 0, k, dye);     // flag, dyed
        } else if (base.endsWith("_head") || base.endsWith("_skull")) {
            String tex;
            int th = 64;
            switch (base) {
                case "player_head" -> tex = "minecraft:entity/player/wide/steve";
                case "skeleton_skull" -> { tex = "minecraft:entity/skeleton/skeleton"; th = 32; }
                case "wither_skeleton_skull" -> { tex = "minecraft:entity/skeleton/wither_skeleton"; th = 32; }
                case "zombie_head" -> tex = "minecraft:entity/zombie/zombie";
                case "creeper_head" -> { tex = "minecraft:entity/creeper/creeper"; th = 32; }
                case "piglin_head" -> tex = "minecraft:entity/piglin/piglin";
                default -> { return out; }
            }
            if (base.equals("piglin_head")) {
                box(out, tex, 64, th, 3, 0, 4, 10, 8, 8, 0, 0, 1, null);
            } else {
                box(out, tex, 64, th, 4, 0, 4, 8, 8, 8, 0, 0, 1, null);
                // the hat layer of player and zombie skins, a hair larger
                if (th == 64) boxGrow(out, tex, 64, th, 4, 0, 4, 8, 8, 8, 32, 0, 0.5f);
            }
        }
        out.removeIf(q -> texture(q.texture()) == null);
        return out;
    }

    private void box(List<Quad> out, String tex, int tw, int th, float x, float y, float z,
                     int w, int h, int d, int u, int v, float scale, String tint) {
        boxAt(out, tex, tw, th, x, y, z, x + w, y + h, z + d, w, h, d, u, v, scale, tint);
    }

    private void boxGrow(List<Quad> out, String tex, int tw, int th, float x, float y, float z,
                         int w, int h, int d, int u, int v, float g) {
        boxAt(out, tex, tw, th, x - g, y - g, z - g, x + w + g, y + h + g, z + d + g, w, h, d, u, v, 1, null);
    }

    /**
     * One cuboid with the entity texture's box layout (top and bottom over the side strip). The
     * strip's front rectangle is put on the south face, the side the camera starts on, so the
     * latch of a chest or the face of a head looks at the viewer.
     */
    private void boxAt(List<Quad> out, String tex, int tw, int th, float x1, float y1, float z1,
                       float x2, float y2, float z2, int w, int h, int d, int u, int v, float scale, String tint) {
        float cx = 8;   // scale about the block's bottom centre
        float[] from = {cx + (x1 - cx) * scale, y1 * scale, cx + (z1 - cx) * scale};
        float[] to = {cx + (x2 - cx) * scale, y2 * scale, cx + (z2 - cx) * scale};
        String ref = tint == null ? tex : tex + "#" + tint;
        int[][] rects = {
                {u + d, v, u + d + w, v + d},                       // up
                {u + d + w, v, u + d + 2 * w, v + d},               // down
                {u, v + d, u + d, v + d + h},                       // west
                {u + 2 * d + w, v + d, u + 2 * d + 2 * w, v + d + h}, // north (the back)
                {u + d + w, v + d, u + 2 * d + w, v + d + h},       // east
                {u + d, v + d, u + d + w, v + d + h}};              // south (the front)
        String[] dirs = {"up", "down", "west", "north", "east", "south"};
        for (int i = 0; i < 6; i++) {
            JsonObject face = new JsonObject();
            JsonArray uv = new JsonArray();
            uv.add(rects[i][0] * 16f / tw);
            uv.add(rects[i][1] * 16f / th);
            uv.add(rects[i][2] * 16f / tw);
            uv.add(rects[i][3] * 16f / th);
            face.add("uv", uv);
            Quad q = faceQuad(dirs[i], from, to, face, ref);
            if (q != null) out.add(q);
        }
    }

    // ------------------------------------------------------------------ textures

    /**
     * A texture by its model reference ("minecraft:block/stone", "block/stone"), first frame only
     * for animated ones. Null when it cannot be found - the caller draws a plain colour.
     */
    public Image texture(String ref) {
        if (zip == null || ref == null || ref.isEmpty()) return null;
        String n = ref.startsWith("minecraft:") ? ref.substring(10) : ref;
        int hash = n.indexOf('#');
        if (hash >= 0) n = n.substring(0, hash);
        if (n.contains(":")) return null;
        return textureCache.computeIfAbsent(n, key -> {
            ZipEntry entry = zip.getEntry("assets/minecraft/textures/" + key + ".png");
            if (entry == null) return MISSING;
            try (InputStream in = zip.getInputStream(entry)) {
                Image image = new Image(new ByteArrayInputStream(in.readAllBytes()));
                if (image.isError()) return MISSING;
                return firstFrame(image);
            } catch (IOException e) {
                return MISSING;
            }
        }) == MISSING ? null : textureCache.get(n);
    }

    private static final Image MISSING = new WritableImage(1, 1);

    private static Image firstFrame(Image image) {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        if (h <= w || h % w != 0) return image;
        PixelReader reader = image.getPixelReader();
        return new WritableImage(reader, 0, 0, w, w);
    }

    /** The picture shown in the picker: an item's sprite, a block's side. */
    public Image icon(String id, boolean item) {
        if (zip == null) return null;
        if (item) {
            List<Quad> quads = itemQuads(id);
            for (Quad q : quads) {
                Image t = texture(q.texture());
                if (t != null) return t;
            }
            return null;
        }
        for (Quad q : blockState(id).quads()) {
            if (q.shade() == 0.8f) {   // a north or south face
                Image t = texture(q.texture());
                if (t != null) return t;
            }
        }
        for (Quad q : blockState(id).quads()) {
            Image t = texture(q.texture());
            if (t != null) return t;
        }
        return null;
    }

    // ------------------------------------------------------------------ names

    /**
     * The game's own name for a block or item in the launcher's language: Russian from the
     * ru_ru.json the game downloaded with its assets, English from the en_us.json inside the jar.
     * Falls back to a readable form of the id.
     */
    public String displayName(String id, boolean item, String language) {
        Map<String, String> m = lang("en".equals(language) ? "en_us" : "ru_ru");
        String v = item ? m.get("item.minecraft." + id) : null;
        if (v == null) v = m.get("block.minecraft." + id);
        if (v == null) v = m.get("item.minecraft." + id);
        if (v == null) {
            String s = id.replace('_', ' ');
            v = s.isEmpty() ? id : Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }
        return v;
    }

    private Map<String, String> lang(String code) {
        return langs.computeIfAbsent(code, c -> {
            Map<String, String> out = new HashMap<>();
            try {
                JsonObject json = null;
                if (c.equals("en_us")) {
                    json = json("assets/minecraft/lang/en_us.json");
                } else if (jarPath != null) {
                    // versions/<v>/<v>.json -> assetIndex id -> assets/indexes/<id>.json -> objects/<hash>
                    Path versionDir = jarPath.getParent();
                    Path root = versionDir.getParent().getParent();
                    String v = versionDir.getFileName().toString();
                    JsonObject meta = JsonParser.parseString(Files.readString(versionDir.resolve(v + ".json"))).getAsJsonObject();
                    String index = meta.getAsJsonObject("assetIndex").get("id").getAsString();
                    JsonObject idx = JsonParser.parseString(Files.readString(
                            root.resolve("assets").resolve("indexes").resolve(index + ".json"))).getAsJsonObject();
                    JsonObject obj = idx.getAsJsonObject("objects").getAsJsonObject("minecraft/lang/" + c + ".json");
                    if (obj != null) {
                        String hash = obj.get("hash").getAsString();
                        Path file = root.resolve("assets").resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
                        if (Files.isRegularFile(file)) {
                            json = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                        }
                    }
                }
                if (json == null && !c.equals("en_us")) return lang("en_us");
                if (json != null) {
                    for (var e : json.entrySet()) {
                        String k = e.getKey();
                        if ((k.startsWith("block.minecraft.") || k.startsWith("item.minecraft.")) && e.getValue().isJsonPrimitive()) {
                            out.put(k, e.getValue().getAsString());
                        }
                    }
                }
            } catch (Exception ignored) {
                // no translation: ids are shown readable instead
            }
            return out;
        });
    }

    // ------------------------------------------------------------------ json

    private JsonObject json(String path) {
        if (zip == null) return null;
        JsonObject cached = jsonCache.get(path);
        if (cached != null) return cached == NO_JSON ? null : cached;
        JsonObject parsed = NO_JSON;
        ZipEntry entry = zip.getEntry(path);
        if (entry != null) {
            try (var reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8)) {
                JsonElement e = JsonParser.parseReader(reader);
                if (e.isJsonObject()) parsed = e.getAsJsonObject();
            } catch (Exception ignored) {
                // a broken model is treated as a missing one
            }
        }
        jsonCache.put(path, parsed);
        return parsed == NO_JSON ? null : parsed;
    }

    private static final JsonObject NO_JSON = new JsonObject();

    private static String base(String entryName) {
        int slash = entryName.lastIndexOf('/');
        return entryName.substring(slash + 1, entryName.length() - 5);
    }
}
