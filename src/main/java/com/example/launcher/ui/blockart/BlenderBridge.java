package com.example.launcher.ui.blockart;

import com.example.launcher.service.Os;
import com.example.launcher.ui.studio.McAssets;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Связка с Blender (05.11.2026, просьба владельца). Блендер сам чертежи не читает, поэтому лаунчер готовит ему
 * папку: настоящая геометрия блоков (ступени остаются ступенями), текстуры из игры и маленький скрипт на
 * Python. Обратно скрипт кладёт .obj, а лаунчер снова переводит его в блоки: см. blauncher_blender.py.
 */
final class BlenderBridge {

    private static final String SCRIPT = "blauncher_blender.py";

    private BlenderBridge() {
    }

    /** blender.exe: сохранённый путь, потом обычные места установки. Null — не нашёлся. */
    static Path find(String saved) {
        if (saved != null && !saved.isBlank() && Files.isRegularFile(Path.of(saved))) return Path.of(saved);
        List<Path> candidates = new ArrayList<>();
        if (Os.isWindows()) {
            for (String root : new String[]{System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)")}) {
                if (root == null) continue;
                addVersions(candidates, Path.of(root, "Blender Foundation"));
                candidates.add(Path.of(root, "Steam", "steamapps", "common", "Blender", "blender.exe"));
            }
            String local = System.getenv("LOCALAPPDATA");
            if (local != null) addVersions(candidates, Path.of(local, "Programs", "Blender Foundation"));
        } else {
            candidates.add(Path.of("/usr/bin/blender"));
            candidates.add(Path.of("/usr/local/bin/blender"));
            candidates.add(Path.of("/snap/bin/blender"));
        }
        String path = System.getenv("PATH");
        if (path != null) {
            for (String dir : path.split(java.io.File.pathSeparator)) {
                try {
                    candidates.add(Path.of(dir, Os.isWindows() ? "blender.exe" : "blender"));
                } catch (RuntimeException ignored) {
                    // кривой элемент PATH
                }
            }
        }
        for (Path p : candidates) if (Files.isRegularFile(p)) return p;
        return null;
    }

    private static void addVersions(List<Path> out, Path foundation) {
        if (!Files.isDirectory(foundation)) return;
        try (var dirs = Files.list(foundation)) {
            dirs.sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .forEach(d -> out.add(d.resolve("blender.exe")));
        } catch (IOException ignored) {
            // нет доступа к папке: поищем в других местах
        }
    }

    /** Складывает сцену в {@code dir} (старое содержимое export/ убирается) и кладёт туда скрипт. */
    static Voxelizer.Original writeScene(VoxelGrid g, Path jar, McAssets assets, Path dir, String language) throws IOException {
        Map<String, List<String>> byTexture = new HashMap<>();
        Files.createDirectories(dir.resolve("textures"));
        Files.createDirectories(dir.resolve("export"));
        Files.deleteIfExists(dir.resolve("export").resolve("done.txt"));

        ByteBuffer cells = ByteBuffer.allocate(g.cells.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        cells.asShortBuffer().put(g.cells);
        Files.write(dir.resolve("cells.bin"), cells.array());

        Map<String, String> textures = new HashMap<>();
        JsonArray palette = new JsonArray();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (String state : g.palette) {
                JsonObject entry = new JsonObject();
                entry.addProperty("state", state);
                JsonArray quads = new JsonArray();
                List<McAssets.Quad> list = state.equals(VoxelGrid.AIR) ? List.of() : quadsOf(assets, state);
                for (McAssets.Quad q : list) {
                    JsonObject o = new JsonObject();
                    JsonArray pos = new JsonArray();
                    for (float v : q.pos()) pos.add(v);
                    JsonArray uv = new JsonArray();
                    for (float v : q.uv()) uv.add(v);
                    o.add("pos", pos);
                    o.add("uv", uv);
                    String texture = textureFile(zip, q.texture(), dir, textures);
                    o.addProperty("tex", texture);
                    List<String> states = byTexture.computeIfAbsent(texture, k -> new ArrayList<>());
                    if (!texture.isEmpty() && !states.contains(state)) states.add(state);
                    if (q.tinted()) o.addProperty("tint", true);
                    o.addProperty("shade", q.shade());
                    quads.add(o);
                }
                entry.add("quads", quads);
                entry.addProperty("full", isFull(list));
                palette.add(entry);
            }
        }
        JsonObject scene = new JsonObject();
        scene.addProperty("w", g.w);
        scene.addProperty("h", g.h);
        scene.addProperty("l", g.l);
        scene.addProperty("lang", language);
        scene.add("palette", palette);
        Files.writeString(dir.resolve("scene.json"), scene.toString(), StandardCharsets.UTF_8);

        try (InputStream in = BlenderBridge.class.getResourceAsStream("/blender/" + SCRIPT)) {
            if (in == null) throw new IOException("нет скрипта Blender в сборке лаунчера");
            Files.write(dir.resolve(SCRIPT), in.readAllBytes());
        }
        return new Voxelizer.Original(g, byTexture);
    }

    private static List<McAssets.Quad> quadsOf(McAssets assets, String state) {
        try {
            List<McAssets.Quad> quads = assets.blockState(VoxelGrid.idPart(state)).quads();
            if (!quads.isEmpty()) return quads;
        } catch (RuntimeException ignored) {
            // блока нет в этой версии: ниже серый куб
        }
        return grayCube();
    }

    /** Блок, которого нет в выбранной версии: куб без текстуры, чтобы постройка не зияла дырами. */
    private static List<McAssets.Quad> grayCube() {
        float[] uv = {0, 0, 1, 0, 1, 1, 0, 1};
        List<McAssets.Quad> out = new ArrayList<>();
        float[][] faces = {
                {0, 1, 0, 1, 1, 0, 1, 1, 1, 0, 1, 1}, {0, 0, 1, 1, 0, 1, 1, 0, 0, 0, 0, 0},
                {1, 1, 1, 1, 1, 0, 1, 0, 0, 1, 0, 1}, {0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0, 0},
                {0, 1, 1, 1, 1, 1, 1, 0, 1, 0, 0, 1}, {1, 1, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0}};
        for (float[] f : faces) out.add(new McAssets.Quad(f, uv, "", false, 1));
        return out;
    }

    /** Закрывает ли блок клетку целиком: тогда прижатые к нему грани соседей не нужны. */
    private static boolean isFull(List<McAssets.Quad> quads) {
        if (quads.size() != 6) return false;
        for (McAssets.Quad q : quads) {
            float[] p = q.pos();
            int full = 0;
            for (int axis = 0; axis < 3; axis++) {
                float min = 9, max = -9;
                for (int i = axis; i < 12; i += 3) {
                    min = Math.min(min, p[i]);
                    max = Math.max(max, p[i]);
                }
                if (max - min > 0.99f) full++;
            }
            if (full != 2) return false;
        }
        return true;
    }

    /** Копирует текстуру (первый кадр анимации) и возвращает имя файла; "" — такой нет. */
    private static String textureFile(ZipFile zip, String ref, Path dir, Map<String, String> done) throws IOException {
        if (ref == null || ref.isEmpty()) return "";
        String known = done.get(ref);
        if (known != null) return known;
        String key = ref.startsWith("minecraft:") ? ref.substring(10) : ref;
        String name = "";
        ZipEntry entry = zip.getEntry("assets/minecraft/textures/" + key + ".png");
        if (entry != null) {
            byte[] bytes;
            try (InputStream in = zip.getInputStream(entry)) {
                bytes = in.readAllBytes();
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image != null) {
                name = key.replaceAll("[^a-zA-Z0-9_.-]", "_") + ".png";
                if (image.getHeight() > image.getWidth() && image.getHeight() % image.getWidth() == 0) {
                    image = image.getSubimage(0, 0, image.getWidth(), image.getWidth());
                }
                ImageIO.write(image, "png", dir.resolve("textures").resolve(name).toFile());
            }
        }
        done.put(ref, name);
        return name;
    }

    static Process launch(Path blender, Path sceneDir) throws IOException {
        return new ProcessBuilder(blender.toString(), "--python", sceneDir.resolve(SCRIPT).toString(), "--", sceneDir.toString())
                .directory(sceneDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(sceneDir.resolve("blender.log").toFile())
                .start();
    }

    /** Простое безопасное имя папки сцены. */
    static String folderName(String base) {
        String s = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
        return s.isBlank() ? "build" : s;
    }
}
