package com.example.launcher.ui.texpack;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Every texture a pack can replace: the ones in an installed client jar and in the current build's
 * mods. Nothing of Mojang's ships with the launcher, so this needs some version to be installed.
 */
final class TextureCatalog implements AutoCloseable {

    /** An installed game version with its own client jar. */
    record Version(String id, Path jar) {}

    /** A resource pack format; the minor part only exists from 1.21.9 on. */
    record PackFormat(int major, int minor) {}

    private static final Pattern TEXTURE = Pattern.compile("assets/[^/]+/textures/.+\\.png");

    private final List<ZipFile> zips = new ArrayList<>();
    private final Map<String, ZipFile> textures = new LinkedHashMap<>();
    private final PackFormat format;

    private TextureCatalog(PackFormat format) {
        this.format = format;
    }

    /** Installed vanilla versions: releases first, newest first. Loader profiles have no jar of their own. */
    static List<Version> installed(Path versionsDir) {
        List<Version> out = new ArrayList<>();
        Map<Version, String> order = new HashMap<>();
        if (versionsDir == null || !Files.isDirectory(versionsDir)) return out;
        try (var dirs = Files.list(versionsDir)) {
            for (Path dir : (Iterable<Path>) dirs::iterator) {
                String id = dir.getFileName().toString();
                Path jar = dir.resolve(id + ".jar");
                Path json = dir.resolve(id + ".json");
                if (!Files.isRegularFile(jar) || !Files.isRegularFile(json)) continue;
                try {
                    JsonObject meta = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
                    if (meta.has("inheritsFrom")) continue;
                    String type = meta.has("type") ? meta.get("type").getAsString() : "";
                    Version v = new Version(id, jar);
                    order.put(v, ("release".equals(type) ? "1" : "0")
                            + (meta.has("releaseTime") ? meta.get("releaseTime").getAsString() : ""));
                    out.add(v);
                } catch (Exception ignored) {
                    // unreadable version json: not a candidate
                }
            }
        } catch (IOException e) {
            return out;
        }
        out.sort((a, b) -> order.get(b).compareTo(order.get(a)));
        return out;
    }

    /**
     * Opens a version and any mods on top of it. Throws when the jar has no textures in the pack
     * layout (versions before 1.6). A mod that cannot be read is skipped: the rest still work.
     */
    static TextureCatalog open(Version version, List<Path> modJars) throws IOException {
        ZipFile game = new ZipFile(version.jar().toFile());
        TextureCatalog catalog = new TextureCatalog(readFormat(game, version.id()));
        catalog.zips.add(game);
        catalog.index(game);
        if (catalog.textures.isEmpty()) {
            catalog.close();
            throw new IOException("no pack layout in " + version.id());
        }
        for (Path mod : modJars) {
            try {
                ZipFile zip = new ZipFile(mod.toFile());
                catalog.zips.add(zip);
                catalog.index(zip);
            } catch (IOException ignored) {
                // not a readable jar
            }
        }
        return catalog;
    }

    private void index(ZipFile zip) {
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (TEXTURE.matcher(name).matches()) textures.putIfAbsent(name, zip);
        }
    }

    private static PackFormat readFormat(ZipFile game, String id) {
        ZipEntry entry = game.getEntry("version.json");
        if (entry != null) {
            try (InputStream in = game.getInputStream(entry)) {
                PackFormat f = parseFormat(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (f != null) return f;
            } catch (IOException | RuntimeException ignored) {
                // fall through to the version number
            }
        }
        return formatOfOldVersion(id);
    }

    /** {@code pack_version} of a client's version.json in any of its three shapes; null when absent. */
    static PackFormat parseFormat(String versionJson) {
        JsonObject root = JsonParser.parseString(versionJson).getAsJsonObject();
        JsonElement p = root.get("pack_version");
        if (p == null) return null;
        if (p.isJsonPrimitive()) return new PackFormat(p.getAsInt(), 0);
        JsonObject o = p.getAsJsonObject();
        if (o.has("resource_major")) {
            return new PackFormat(o.get("resource_major").getAsInt(),
                    o.has("resource_minor") ? o.get("resource_minor").getAsInt() : 0);
        }
        return o.has("resource") ? new PackFormat(o.get("resource").getAsInt(), 0) : null;
    }

    /** Jars before 1.14 have no version.json. Numbers from the wiki's pack format table. */
    static PackFormat formatOfOldVersion(String id) {
        Matcher m = Pattern.compile("^1\\.(\\d+)").matcher(id);
        int minor = m.find() ? Integer.parseInt(m.group(1)) : 13;
        int format = minor <= 8 ? 1 : minor <= 10 ? 2 : minor <= 12 ? 3 : 4;
        return new PackFormat(format, 0);
    }

    PackFormat format() {
        return format;
    }

    List<String> paths() {
        return List.copyOf(textures.keySet());
    }

    boolean has(String path) {
        return textures.containsKey(path);
    }

    /** The texture's bytes, or null when no jar has it. */
    byte[] read(String path) throws IOException {
        ZipFile zip = textures.get(path);
        return zip == null ? null : bytes(zip, path);
    }

    /**
     * The texture's {@code .mcmeta} from the jar that has the texture. A pack that replaces a
     * texture must carry this too, or the game stops animating it and stretches a GUI sprite.
     */
    byte[] readMeta(String path) throws IOException {
        ZipFile zip = textures.get(path);
        return zip == null ? null : bytes(zip, path + ".mcmeta");
    }

    private static byte[] bytes(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) return null;
        try (InputStream in = zip.getInputStream(entry)) {
            return in.readAllBytes();
        }
    }

    /** The list's grouping: a texture's top folder, the old plural names folded into the new ones. */
    static String category(String path) {
        if (path.equals(PackProject.ICON)) return "pack";
        String[] parts = path.split("/");
        if (parts.length < 4 || !parts[0].equals("assets") || !parts[2].equals("textures")) return "other";
        if (!parts[1].equals("minecraft")) return "mods";
        if (parts.length == 4) return "misc";
        return switch (parts[3]) {
            case "blocks" -> "block";
            case "items" -> "item";
            default -> parts[3];
        };
    }

    @Override
    public void close() {
        for (ZipFile zip : zips) {
            try {
                zip.close();
            } catch (IOException ignored) {
                // closing a read-only zip
            }
        }
    }
}
