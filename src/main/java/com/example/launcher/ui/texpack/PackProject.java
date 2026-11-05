package com.example.launcher.ui.texpack;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * A texture pack being painted: a folder in the pack's own layout holding only what was changed.
 * The rest of the game's textures stay in the game, so an exported pack carries the author's work
 * and no copy of Mojang's files beyond the small sidecars a replaced texture needs.
 */
final class PackProject {

    static final String ICON = "pack.png";
    private static final String META = "pack.mcmeta";
    /** First format that declares min_format and max_format instead of pack_format. */
    private static final int RANGE_FORMAT = 65;
    private static final long IMPORT_LIMIT = 2L * 1024 * 1024 * 1024;

    private final Path dir;

    PackProject(Path dir) {
        this.dir = dir;
    }

    Path dir() {
        return dir;
    }

    String name() {
        return dir.getFileName().toString();
    }

    /** Project names in the folder, sorted. */
    static List<String> list(Path root) throws IOException {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> dirs = Files.list(root)) {
            dirs.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().forEach(out::add);
        }
        return out;
    }

    /** A name that is safe as a folder and as a zip on every system the launcher runs on. */
    static String safeName(String raw) {
        String s = raw == null ? "" : raw.replaceAll("[^\\p{L}\\p{N} ._-]", "_").trim();
        s = s.replaceAll("[. ]+$", "");
        if (s.length() > 48) s = s.substring(0, 48).trim();
        if (s.matches("(?i)(con|prn|aux|nul|com\\d|lpt\\d)(\\..*)?")) s = "_" + s;
        return s;
    }

    /** A new empty project: "name", or "name 2" and so on when that is taken. */
    static PackProject create(Path root, String wanted) throws IOException {
        String base = safeName(wanted);
        if (base.isEmpty()) base = "pack";
        Path dir = root.resolve(base);
        for (int n = 2; Files.exists(dir); n++) dir = root.resolve(base + " " + n);
        Files.createDirectories(dir);
        return new PackProject(dir);
    }

    /** Null when that name is taken or unusable; the project then stays as it is. */
    PackProject rename(String wanted) throws IOException {
        String name = safeName(wanted);
        if (name.isEmpty() || name.equals(name())) return null;
        Path target = dir.resolveSibling(name);
        if (Files.exists(target)) return null;
        Files.move(dir, target);
        return new PackProject(target);
    }

    private Path resolve(String path) throws IOException {
        Path p = dir.resolve(path).normalize();
        if (!p.startsWith(dir) || p.equals(dir)) throw new IOException("outside the pack: " + path);
        return p;
    }

    boolean has(String path) throws IOException {
        return Files.isRegularFile(resolve(path));
    }

    byte[] read(String path) throws IOException {
        Path p = resolve(path);
        return Files.isRegularFile(p) ? Files.readAllBytes(p) : null;
    }

    byte[] readMeta(String path) throws IOException {
        return read(path + ".mcmeta");
    }

    /** Stores a painted texture. A sidecar already in the pack stays: it may be the pack's own. */
    void write(String path, byte[] png, byte[] sidecar) throws IOException {
        Path p = resolve(path);
        Files.createDirectories(p.getParent());
        atomicWrite(p, png);
        Path meta = resolve(path + ".mcmeta");
        if (sidecar != null && !Files.exists(meta)) atomicWrite(meta, sidecar);
    }

    /** Back to the game's texture: the pack no longer replaces it. */
    void remove(String path) throws IOException {
        Files.deleteIfExists(resolve(path));
        Files.deleteIfExists(resolve(path + ".mcmeta"));
    }

    private static void atomicWrite(Path target, byte[] data) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temp, data);
        moveOver(temp, target);
    }

    private static void moveOver(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Every picture in the pack, by its path inside the pack. */
    Set<String> pictures() throws IOException {
        Set<String> out = new TreeSet<>();
        if (Files.isRegularFile(dir.resolve(ICON))) out.add(ICON);
        Path assets = dir.resolve("assets");
        if (!Files.isDirectory(assets)) return out;
        try (Stream<Path> files = Files.walk(assets)) {
            files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".png"))
                    .forEach(p -> out.add(relative(p)));
        }
        return out;
    }

    private String relative(Path p) {
        return dir.relativize(p).toString().replace('\\', '/');
    }

    /** The pack's description when it is plain text; a text component is left alone and shows empty. */
    String description() {
        JsonObject pack = readPackSection(readMetaFile());
        JsonElement d = pack == null ? null : pack.get("description");
        return d != null && d.isJsonPrimitive() ? d.getAsString() : "";
    }

    private JsonObject readMetaFile() {
        try {
            Path meta = dir.resolve(META);
            if (Files.isRegularFile(meta)) {
                return JsonParser.parseString(Files.readString(meta, StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (IOException | RuntimeException ignored) {
            // written anew on the next save
        }
        return null;
    }

    private static JsonObject readPackSection(JsonObject root) {
        return root != null && root.has("pack") && root.get("pack").isJsonObject() ? root.getAsJsonObject("pack") : null;
    }

    /**
     * Writes pack.mcmeta for the format, keeping every other section of an imported pack. A blank
     * description keeps the old one, which may be a text component the field cannot show.
     */
    void writeMeta(String description, TextureCatalog.PackFormat format) throws IOException {
        JsonObject root = readMetaFile();
        if (root == null) root = new JsonObject();
        JsonObject pack = readPackSection(root);
        if (pack == null) pack = new JsonObject();
        if (description != null && !description.isBlank()) {
            pack.addProperty("description", description.trim());
        } else if (!pack.has("description")) {
            pack.addProperty("description", name());
        }
        pack.remove("pack_format");
        pack.remove("supported_formats");
        pack.remove("min_format");
        pack.remove("max_format");
        if (format.major() >= RANGE_FORMAT) {
            pack.add("min_format", formatArray(format));
            pack.add("max_format", formatArray(format));
        } else {
            pack.addProperty("pack_format", format.major());
        }
        root.add("pack", pack);
        Files.createDirectories(dir);
        atomicWrite(dir.resolve(META), new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                .toJson(root).getBytes(StandardCharsets.UTF_8));
    }

    private static JsonArray formatArray(TextureCatalog.PackFormat format) {
        JsonArray a = new JsonArray();
        a.add(format.major());
        a.add(format.minor());
        return a;
    }

    /** The whole pack as a zip, written beside the target first so a failure leaves the old one whole. */
    void exportZip(Path target) throws IOException {
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".part");
        List<Path> files;
        try (Stream<Path> walk = Files.walk(dir)) {
            files = walk.filter(Files::isRegularFile).filter(p -> !p.getFileName().toString().endsWith(".tmp"))
                    .sorted(Comparator.comparing(this::relative)).toList();
        }
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
            for (Path file : files) {
                zip.putNextEntry(new ZipEntry(relative(file)));
                Files.copy(file, zip);
                zip.closeEntry();
            }
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        moveOver(temp, target);
    }

    /**
     * Unpacks a pack from a zip into a new project. The pack may sit in a folder inside the zip.
     * Throws when there is no pack.mcmeta; entries pointing outside the project are skipped.
     */
    static PackProject importZip(Path root, Path zipFile) throws IOException {
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            String prefix = null;
            var scan = zip.entries();
            while (scan.hasMoreElements()) {
                String name = scan.nextElement().getName();
                if ((name.equals(META) || name.endsWith("/" + META)) && (prefix == null || name.length() - META.length() < prefix.length())) {
                    prefix = name.substring(0, name.length() - META.length());
                }
            }
            if (prefix == null) throw new IOException("no pack.mcmeta");
            String base = zipFile.getFileName().toString().replaceAll("(?i)\\.zip$", "");
            PackProject project = create(root, base);
            try {
                long total = 0;
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory() || !entry.getName().startsWith(prefix)) continue;
                    Path out = project.dir.resolve(entry.getName().substring(prefix.length())).normalize();
                    if (!out.startsWith(project.dir) || out.equals(project.dir)) continue;
                    Files.createDirectories(out.getParent());
                    try (InputStream in = zip.getInputStream(entry); OutputStream o = Files.newOutputStream(out)) {
                        byte[] buffer = new byte[65536];
                        for (int n; (n = in.read(buffer)) > 0; ) {
                            total += n;
                            if (total > IMPORT_LIMIT) throw new IOException("pack over the size limit");
                            o.write(buffer, 0, n);
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                deleteTree(project.dir);   // the folder was made just above, nothing else is in it
                throw e;
            }
            return project;
        }
    }

    private static void deleteTree(Path top) throws IOException {
        try (Stream<Path> walk = Files.walk(top)) {
            for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        }
    }
}
