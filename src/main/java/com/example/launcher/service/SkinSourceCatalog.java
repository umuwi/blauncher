package com.example.launcher.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The sites a skin or a cape can be taken from, and how to take it.
 *
 * <p>This is the data behind the source chips in the profile editor. Every entry here is a site
 * that can actually hand over a texture for a nickname - that is the whole point of the list, so
 * a "site" that only has a page to look at does not belong in it. Three routes are in use:
 *
 * <ul>
 *   <li>{@link Pull#MOJANG} and {@link Pull#ELYBY} resolve a nickname to a skin texture;</li>
 *   <li>{@link Pull#MINECRAFT_CAPES} asks MinecraftCapes about the UUID, and falls back to
 *       capes.dev so a chip that promises a cape still delivers one if only the aggregate knows
 *       it;</li>
 *   <li>{@link Pull#CAPES_DEV} asks capes.dev, which aggregates the capes other clients hand
 *       out. The entry's {@code apiKey} picks one of them (OptiFine, LabyMod, TLauncher, 5zig,
 *       SkinMC, Minecraft's own cape); {@code null} means "whichever cape this player has".</li>
 * </ul>
 *
 * <p>Sites that were only ever browsable are deliberately absent. NameMC, MinecraftSkins and
 * NovaSkin have no API, and their pages answer an automated request with 403 (measured, not
 * assumed), so a chip for them could only open a browser - which is exactly what the profile
 * editor does not do. Their skins still reach a profile through the "link to an image" field.
 *
 * <p>Blocking network I/O - callers run {@link #fetch} off the JavaFX thread.
 */
public final class SkinSourceCatalog {

    /** Which slot of the profile a source fills. */
    public enum Kind { SKIN, CAPE }

    /** How a texture is obtained from a site. */
    public enum Pull {
        /** Mojang session server - the official skin of a premium account. */
        MOJANG,
        /** Ely.by texture service - its own accounts, custom and slim skins included. */
        ELYBY,
        /** MinecraftCapes, by UUID, with capes.dev behind it. */
        MINECRAFT_CAPES,
        /** capes.dev, which aggregates the capes every other client hands out. */
        CAPES_DEV
    }

    /**
     * One site in the source strip.
     *
     * @param id       stable key; also the logo cache file name and the {@code note} key suffix
     * @param name     what the player sees on the chip
     * @param kind     skin or cape slot
     * @param pull     how the texture is obtained
     * @param apiKey   for {@link Pull#CAPES_DEV}: the key inside its JSON. Null means "any cape".
     * @param domain   site whose favicon becomes the chip's logo
     * @param monogram letters drawn on the chip until (or instead of) that favicon
     * @param brand    the chip's colour behind the logo
     */
    public record Source(String id, String name, Kind kind, Pull pull, String apiKey,
                         String domain, String monogram, String brand) {

        /** The dictionary key of the line under the site name, saying what this site gives. */
        public String noteKey() {
            return "profile_source_note_" + id;
        }
    }

    /** Raised when a site answered fine but has nothing for this player - a hint, not an error. */
    public static class Unavailable extends IOException {
        private static final long serialVersionUID = 1L;
        public Unavailable(String message) {
            super(message);
        }
    }

    private static final String UA = "BLauncher";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private static final List<Source> SOURCES = List.of(
            // ---- skins ----
            new Source("mojang", "Minecraft", Kind.SKIN, Pull.MOJANG, null,
                    "minecraft.net", "MC", "#5E9C4B"),
            new Source("elyby", "Ely.by", Kind.SKIN, Pull.ELYBY, null,
                    "ely.by", "E", "#3D6BFF"),

            // ---- capes ----
            new Source("optifine", "OptiFine", Kind.CAPE, Pull.CAPES_DEV, "optifine",
                    "optifine.net", "OF", "#B8862B"),
            new Source("labymod", "LabyMod", Kind.CAPE, Pull.CAPES_DEV, "labymod",
                    "labymod.net", "LM", "#2BB3E8"),
            new Source("tlauncher", "TLauncher", Kind.CAPE, Pull.CAPES_DEV, "tlauncher",
                    "tlauncher.org", "TL", "#E5B93C"),
            new Source("minecraftcapes", "MinecraftCapes", Kind.CAPE, Pull.MINECRAFT_CAPES, null,
                    "minecraftcapes.net", "MC", "#C0453F"),
            new Source("skinmc", "SkinMC", Kind.CAPE, Pull.CAPES_DEV, "skinmc",
                    "skinmc.net", "SMC", "#C4562F"),
            new Source("minecraft", "Minecraft", Kind.CAPE, Pull.CAPES_DEV, "minecraft",
                    "minecraft.net", "MC", "#5E9C4B"),
            new Source("capesdev", "capes.dev", Kind.CAPE, Pull.CAPES_DEV, null,
                    "capes.dev", "CD", "#2E9E8F"));

    private SkinSourceCatalog() {
    }

    /** Every site, skins first, in the order the profile editor shows them. */
    public static List<Source> all() {
        return SOURCES;
    }

    /** The sites that fill the skin slot. */
    public static List<Source> skins() {
        return byKind(Kind.SKIN);
    }

    /** The sites that fill the cape slot. */
    public static List<Source> capes() {
        return byKind(Kind.CAPE);
    }

    /** The first site of a row - what the editor starts with before the player picks another. */
    public static Source first(Kind kind) {
        List<Source> row = byKind(kind);
        return row.isEmpty() ? null : row.get(0);
    }

    /** One site by id, or null when nothing matches (an id from an older session, say). */
    public static Source find(String id) {
        for (Source source : SOURCES) {
            if (source.id().equals(id)) return source;
        }
        return null;
    }

    private static List<Source> byKind(Kind kind) {
        List<Source> result = new ArrayList<>();
        for (Source source : SOURCES) {
            if (source.kind() == kind) result.add(source);
        }
        return result;
    }

    /** The favicon URLs tried for a chip, best quality first. */
    public static List<String> logoUrls(Source source) {
        String domain = source.domain();
        return List.of(
                "https://www.google.com/s2/favicons?domain=" + domain + "&sz=64",
                "https://icons.duckduckgo.com/ip3/" + domain + ".ico",
                "https://" + domain + "/favicon.ico");
    }

    /**
     * The texture bytes this site has for {@code nickname}.
     *
     * @throws Unavailable when the site knows the player but has no such texture
     * @throws IOException  on a network or protocol failure
     */
    public static byte[] fetch(Source source, String nickname) throws IOException {
        if (nickname == null || nickname.isBlank()) {
            throw new Unavailable("Не указан ник игрока");
        }
        // Одна повторная попытка при обрыве. Измерено 2026-10-04: три прогона подряд по всем
        // семи источникам давали от 0 до 5 таймаутов, причём отказы каждый раз приходились на
        // ДРУГИЕ сайты, а по отдельности отвечал каждый. То есть это не «сайт лежит», а
        // захлебнувшееся соединение (api.capes.dev отвечает на один запрос за 0,7 с, но на
        // череду запросов подряд — через раз). Без повтора человек видел «Read timed out» и
        // решал, что плащей у него нет.
        IOException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            if (attempt > 1) {
                try {
                    Thread.sleep(RETRY_DELAY_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw last;
                }
            }
            try {
                return switch (source.pull()) {
                    case ELYBY -> fetchElyBy(nickname);
                    case MOJANG -> fetchMojang(nickname);
                    case MINECRAFT_CAPES -> fetchMinecraftCapes(nickname);
                    case CAPES_DEV -> fetchCapesDev(source, nickname);
                };
            } catch (Unavailable e) {
                // Сайт ответил и сказал, что такой текстуры у него нет. Это окончательный
                // ответ, а не сбой — повторять нечего.
                throw e;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    private static final int ATTEMPTS = 2;
    private static final long RETRY_DELAY_MS = 700L;

    /** Ely.by's texture service, which also knows slim skins of its own accounts. */
    private static byte[] fetchElyBy(String nickname) throws IOException {
        // https, не http: по открытому каналу ответ подменяется в пути, после чего textureUrl()
        // вернёт чужой адрес и download() запишет произвольные байты как скин игрока. Ely.by
        // поддерживает https (FriendsService обращается к тому же хосту именно по нему).
        Http.Response resp = Http.get(
                "https://skinsystem.ely.by/textures/" + urlEncode(nickname),
                Map.of("User-Agent", UA), TIMEOUT);
        String url = null;
        if (resp.ok() && resp.body().length > 0) {
            JsonObject root = JsonParser.parseString(resp.text()).getAsJsonObject();
            url = textureUrl(root, "SKIN");
            if (url == null && root.has("textures")) {
                JsonObject textures = root.getAsJsonObject("textures");
                if (textures.has("SKIN")) url = textureUrl(textures, "SKIN");
            }
        }
        if (url == null) {
            throw new Unavailable("На Ely.by нет скина для " + nickname);
        }
        return download(url, "скин", nickname);
    }

    /** The official skin: nickname -&gt; UUID -&gt; session server -&gt; base64 textures property. */
    private static byte[] fetchMojang(String nickname) throws IOException {
        String uuid = mojangUuid(nickname);
        if (uuid == null) {
            throw new Unavailable("Игрок " + nickname + " не найден у Mojang");
        }
        Http.Response profileResp = Http.get(
                "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid,
                Map.of("User-Agent", UA), TIMEOUT);
        if (!profileResp.ok()) {
            throw new IOException("Mojang ответил " + profileResp.status());
        }
        JsonObject root = JsonParser.parseString(profileResp.text()).getAsJsonObject();
        String url = null;
        if (root.has("properties")) {
            for (var element : root.getAsJsonArray("properties")) {
                JsonObject property = element.getAsJsonObject();
                if (!property.has("name") || !"textures".equals(property.get("name").getAsString())) {
                    continue;
                }
                if (!property.has("value")) continue;
                byte[] decoded = java.util.Base64.getDecoder().decode(property.get("value").getAsString());
                JsonObject textures = JsonParser.parseString(new String(decoded, StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("textures");
                if (textures != null && textures.has("SKIN")) url = textureUrl(textures, "SKIN");
                break;
            }
        }
        if (url == null) {
            throw new Unavailable("У " + nickname + " нет официального скина");
        }
        return download(url, "скин", nickname);
    }

    /**
     * MinecraftCapes' own API, keyed by the dashed-free UUID Mojang hands out. Its gallery is
     * smaller than the cape world, so capes.dev answers when this site has nothing.
     */
    private static byte[] fetchMinecraftCapes(String nickname) throws IOException {
        String uuid = mojangUuid(nickname);
        if (uuid == null) {
            throw new Unavailable("Игрок " + nickname + " не найден у Mojang");
        }
        String url = null;
        try {
            Http.Response resp = Http.get("https://api.minecraftcapes.net/profile/" + uuid,
                    Map.of("User-Agent", UA), TIMEOUT);
            if (resp.ok() && resp.body().length > 0) {
                url = stringOrNull(JsonParser.parseString(resp.text()).getAsJsonObject(), "cape_url");
            }
        } catch (Exception e) {
            // Fall through to capes.dev rather than failing the chip on this site's downtime.
        }
        if (url == null) {
            return fetchCapesDev(find("capesdev"), nickname);
        }
        return download(url, "плащ", nickname);
    }

    /**
     * capes.dev asks every cape service it knows about and answers with one object per service.
     * A source with its own {@code apiKey} takes that entry; the "any source" chip takes the
     * first cape that exists.
     */
    /**
     * Последний ответ capes.dev по нику, чтобы перебор сайтов не бил в один адрес шесть раз.
     *
     * <p>Шесть из семи источников плащей — это ОДИН запрос {@code api.capes.dev/load/<ник>} и
     * разные ключи в его ответе. Без этого переключение между OptiFine, LabyMod, TLauncher,
     * SkinMC, Minecraft и «любой плащ» означало шесть одинаковых запросов, и capes.dev начинал
     * отвечать 429 (измерено 2026-10-04: три прогона по всем источникам подряд — в третьем
     * 429 на пяти из семи). Человеку это выглядело как «плащей нет».
     *
     * <p>Срок маленький: ответ должен обновиться, как только человек поставил себе новый плащ и
     * вернулся проверить.
     */
    private static final long CAPES_CACHE_MS = 60_000L;
    private static final Map<String, CachedCapes> CAPES_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedCapes(JsonObject body, long at) {
        boolean fresh() {
            return System.currentTimeMillis() - at < CAPES_CACHE_MS;
        }
    }

    private static byte[] fetchCapesDev(Source source, String nickname) throws IOException {
        String cacheKey = nickname.toLowerCase(java.util.Locale.ROOT);
        CachedCapes cached = CAPES_CACHE.get(cacheKey);
        JsonObject root;
        if (cached != null && cached.fresh()) {
            root = cached.body();
        } else {
            Http.Response resp = Http.get(
                    "https://api.capes.dev/load/" + urlEncode(nickname),
                    Map.of("User-Agent", UA), TIMEOUT);
            if (resp.status() == 429) {
                // Отдельно от остальных кодов: это «слишком часто», а не «сломалось», и
                // сказать об этом надо так, чтобы человек просто подождал.
                throw new IOException("capes.dev просит подождать — слишком много запросов подряд");
            }
            if (!resp.ok()) {
                throw new IOException("capes.dev ответил " + resp.status());
            }
            root = JsonParser.parseString(resp.text()).getAsJsonObject();
            CAPES_CACHE.put(cacheKey, new CachedCapes(root, System.currentTimeMillis()));
        }

        if (source != null && source.apiKey() != null) {
            JsonObject entry = root.has(source.apiKey()) ? root.getAsJsonObject(source.apiKey()) : null;
            String url = existingImage(entry);
            if (url == null) {
                throw new Unavailable("У " + nickname + " нет плаща " + source.name());
            }
            return download(url, "плащ", nickname);
        }

        for (String key : root.keySet()) {
            String url = existingImage(root.getAsJsonObject(key));
            if (url != null) return download(url, "плащ", nickname);
        }
        throw new Unavailable("У " + nickname + " нет плащей, известных capes.dev");
    }

    /** capes.dev's own shape: {@code exists} plus {@code imageUrl}, the PNG of the cape. */
    private static String existingImage(JsonObject entry) {
        if (entry == null) return null;
        if (entry.has("exists") && !entry.get("exists").isJsonNull()
                && !entry.get("exists").getAsBoolean()) {
            return null;
        }
        return stringOrNull(entry, "imageUrl");
    }

    /** {@code {"SKIN": {"url": ...}}} and friends. */
    private static String textureUrl(JsonObject holder, String key) {
        if (holder == null || !holder.has(key)) return null;
        JsonObject texture = holder.getAsJsonObject(key);
        return texture == null ? null : stringOrNull(texture, "url");
    }

    private static String mojangUuid(String nickname) throws IOException {
        Http.Response resp = Http.get(
                "https://api.mojang.com/users/profiles/minecraft/" + urlEncode(nickname),
                Map.of("User-Agent", UA), TIMEOUT);
        if (!resp.ok()) return null;
        JsonObject root = JsonParser.parseString(resp.text()).getAsJsonObject();
        return stringOrNull(root, "id");
    }

    private static byte[] download(String url, String what, String nickname) throws IOException {
        Http.Response resp = Http.get(url, Map.of("User-Agent", UA), Duration.ofSeconds(30));
        if (!resp.ok() || resp.body().length == 0) {
            throw new IOException("Не удалось скачать " + what + " для " + nickname
                    + " (" + resp.status() + ")");
        }
        return resp.body();
    }

    private static String stringOrNull(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) return null;
        String value = object.get(key).getAsString();
        return value == null || value.isBlank() ? null : value;
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
