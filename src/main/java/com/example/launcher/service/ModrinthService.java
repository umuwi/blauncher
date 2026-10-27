package com.example.launcher.service;

import com.example.launcher.model.ModInfo;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Communicates with the Modrinth REST API (v2) to search for mods
 * and download specific mod versions.
 *
 * API docs: https://docs.modrinth.com/
 */
public class ModrinthService {

    private static final String API_BASE = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "MinecraftLauncher/1.0 (github.com/example/launcher)";

    private final Path gameDir;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /** Every Modrinth call carries the same User-Agent, which their API asks clients to send. */
    private static Http.Response get(String url) throws IOException {
        return Http.get(url, Map.of("User-Agent", USER_AGENT));
    }

    public ModrinthService(Path gameDir) {
        this.gameDir = gameDir;
    }

    /** Metadata about a mod installed from Modrinth, keyed by installed file name. */
    public static class InstalledMeta {
        public String projectId;
        public String title;
    }

    // ---------------------------------------------------------------
    // Icons for mods that are already on disk
    // ---------------------------------------------------------------

    /**
     * Modrinth icon URL per project id (and per slug, when a slug was used as the handle).
     *
     * <p>Needed because a mod sitting in a build's {@code mods/} folder is just a {@code .jar}:
     * it carries neither an icon nor any Modrinth metadata, so the icon has to be looked up from
     * the project it came from. Modrinth takes the whole batch in one request, which is the
     * difference between one call and one call per mod when a build has thirty of them.
     */
    public Map<String, String> projectIcons(Collection<String> projectIds) throws IOException {
        Map<String, String> icons = new LinkedHashMap<>();
        List<String> wanted = new ArrayList<>();
        for (String id : projectIds) {
            if (id != null && !id.isBlank() && !wanted.contains(id)) wanted.add(id);
        }
        if (wanted.isEmpty()) return icons;

        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < wanted.size(); i++) {
            if (i > 0) json.append(',');
            json.append('"').append(wanted.get(i).replace("\"", "")).append('"');
        }
        json.append(']');

        Http.Response resp = get(API_BASE + "/projects?ids="
                + java.net.URLEncoder.encode(json.toString(), StandardCharsets.UTF_8));
        if (!resp.ok()) return icons;

        for (var element : JsonParser.parseString(resp.text()).getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject project = element.getAsJsonObject();
            if (!project.has("icon_url") || project.get("icon_url").isJsonNull()) continue;
            String icon = project.get("icon_url").getAsString();
            if (icon == null || icon.isBlank()) continue;
            if (project.has("id") && !project.get("id").isJsonNull()) {
                icons.put(project.get("id").getAsString(), icon);
            }
            if (project.has("slug") && !project.get("slug").isJsonNull()) {
                icons.put(project.get("slug").getAsString(), icon);
            }
        }
        return icons;
    }

    /**
     * Project id per sha1 hash, for files Modrinth hosts — how a mod that was downloaded by hand
     * (and therefore has no recorded projectId) is still traced back to its project page.
     *
     * <p>One POST for the whole batch; hashes Modrinth has never seen are simply absent from the
     * result, which is the expected answer for a mod from CurseForge or a private build.
     */
    public Map<String, String> projectIdsByHash(Collection<String> sha1Hashes) throws IOException {
        Map<String, String> found = new HashMap<>();
        JsonArray hashes = new JsonArray();
        for (String hash : sha1Hashes) {
            if (hash != null && !hash.isBlank()) hashes.add(hash.toLowerCase());
        }
        if (hashes.isEmpty()) return found;

        JsonObject body = new JsonObject();
        body.add("hashes", hashes);
        body.addProperty("algorithm", "sha1");

        Http.Response resp = Http.post(API_BASE + "/version_files", body.toString(),
                "application/json", Map.of("User-Agent", USER_AGENT), java.time.Duration.ofSeconds(20));
        if (!resp.ok()) return found;

        JsonObject root = JsonParser.parseString(resp.text()).getAsJsonObject();
        for (String hash : root.keySet()) {
            if (!root.get(hash).isJsonObject()) continue;
            JsonObject version = root.getAsJsonObject(hash);
            if (version.has("project_id") && !version.get("project_id").isJsonNull()) {
                found.put(hash.toLowerCase(), version.get("project_id").getAsString());
            }
        }
        return found;
    }

    /**
     * Версии Modrinth целиком по sha1 файлов — с зависимостями, загрузчиками и версиями игры.
     * Для «Починки сборки» (18.10.2026): по ним видно, какие обязательные моды автор указал на
     * Modrinth, хотя в самом jar их нет. Неизвестных Modrinth файлов в ответе просто не будет.
     */
    public Map<String, JsonObject> versionsByHash(Collection<String> sha1Hashes) throws IOException {
        Map<String, JsonObject> found = new HashMap<>();
        JsonArray hashes = new JsonArray();
        for (String hash : sha1Hashes) {
            if (hash != null && !hash.isBlank()) hashes.add(hash.toLowerCase());
        }
        if (hashes.isEmpty()) return found;
        JsonObject body = new JsonObject();
        body.add("hashes", hashes);
        body.addProperty("algorithm", "sha1");
        Http.Response resp = Http.post(API_BASE + "/version_files", body.toString(),
                "application/json", Map.of("User-Agent", USER_AGENT), java.time.Duration.ofSeconds(20));
        if (!resp.ok()) throw new IOException("Modrinth: HTTP " + resp.status());
        JsonObject root = JsonParser.parseString(resp.text()).getAsJsonObject();
        for (String hash : root.keySet()) {
            if (root.get(hash).isJsonObject()) found.put(hash.toLowerCase(), root.getAsJsonObject(hash));
        }
        return found;
    }

    /** Все версии проекта под эту версию игры и загрузчик, новые первыми (для точной версии в «Починке сборки»). */
    public JsonArray listVersions(String projectId, String mcVersion, String loader) throws IOException {
        StringBuilder url = new StringBuilder(API_BASE + "/project/" + URLEncoder.encode(projectId, StandardCharsets.UTF_8) + "/version?");
        if (loader != null && !loader.isBlank()) {
            url.append("loaders=").append(URLEncoder.encode("[\"" + loader.toLowerCase() + "\"]", StandardCharsets.UTF_8)).append("&");
        }
        if (mcVersion != null && !mcVersion.isBlank()) {
            url.append("game_versions=").append(URLEncoder.encode("[\"" + mcVersion + "\"]", StandardCharsets.UTF_8));
        }
        Http.Response resp = get(url.toString());
        if (!resp.ok()) throw new IOException("Modrinth: HTTP " + resp.status());
        return JsonParser.parseString(resp.text()).getAsJsonArray();
    }

    /** Проект по id или slug; нет такого — null. */
    public JsonObject project(String idOrSlug) throws IOException {
        Http.Response resp = get(API_BASE + "/project/" + URLEncoder.encode(idOrSlug, StandardCharsets.UTF_8));
        if (resp.status() == 404) return null;
        if (!resp.ok()) throw new IOException("Modrinth: HTTP " + resp.status());
        return JsonParser.parseString(resp.text()).getAsJsonObject();
    }

    /** Файл, который лежит на Modrinth: для экспорта сборки в .mrpack (17.10.2026). */
    public record HostedFile(String url, String sha1, String sha512, long size) {}

    /**
     * Ссылка, sha512 и размер для каждого sha1, который Modrinth знает, — тот же запрос
     * version_files, что у {@link #projectIdsByHash}. Чего нет в ответе, того на Modrinth нет
     * (мод с CurseForge, свой jar): экспорт кладёт такие файлы внутрь архива.
     */
    public Map<String, HostedFile> hostedFilesByHash(Collection<String> sha1Hashes) throws IOException {
        Map<String, HostedFile> found = new HashMap<>();
        JsonArray hashes = new JsonArray();
        for (String hash : sha1Hashes) {
            if (hash != null && !hash.isBlank()) hashes.add(hash.toLowerCase());
        }
        if (hashes.isEmpty()) return found;

        JsonObject body = new JsonObject();
        body.add("hashes", hashes);
        body.addProperty("algorithm", "sha1");
        Http.Response resp = Http.post(API_BASE + "/version_files", body.toString(),
                "application/json", Map.of("User-Agent", USER_AGENT), java.time.Duration.ofSeconds(30));
        if (!resp.ok()) throw new IOException("Modrinth ответил " + resp.status());

        JsonObject root = JsonParser.parseString(resp.text()).getAsJsonObject();
        for (String hash : root.keySet()) {
            if (!root.get(hash).isJsonObject()) continue;
            JsonObject version = root.getAsJsonObject(hash);
            if (!version.has("files") || !version.get("files").isJsonArray()) continue;
            for (var el : version.getAsJsonArray("files")) {
                if (!el.isJsonObject()) continue;
                JsonObject file = el.getAsJsonObject();
                JsonObject h = file.has("hashes") && file.get("hashes").isJsonObject() ? file.getAsJsonObject("hashes") : null;
                if (h == null || !h.has("sha1") || !h.has("sha512") || !file.has("url")) continue;
                if (!hash.equalsIgnoreCase(h.get("sha1").getAsString())) continue;
                long size = file.has("size") ? file.get("size").getAsLong() : 0;
                found.put(hash.toLowerCase(), new HostedFile(file.get("url").getAsString(),
                        hash.toLowerCase(), h.get("sha512").getAsString().toLowerCase(), size));
                break;
            }
        }
        return found;
    }

    // ---------------------------------------------------------------
    // Content types
    // ---------------------------------------------------------------

    /**
     * Maps a Modrinth {@code project_type} to the launcher folder its files live in (a per-group
     * subfolder inside, e.g. {@code shaderpacks/<group>/}). Mirrors how vanilla Minecraft expects
     * each kind of content to be laid out, so GameLaunchService can sync them into the live game
     * dir at launch. "modpack" has no single folder of its own - it expands into several of these.
     */
    public static String folderForType(String projectType) {
        if (projectType == null) return "mods";
        return switch (projectType) {
            case "shader"       -> "shaderpacks";
            case "resourcepack" -> "resourcepacks";
            case "datapack"     -> "datapacks";
            default              -> "mods";
        };
    }

    /** Hidden game-dir subfolder where each group's shader packs are STORED ({@code <gameDir>/
     *  .bl_shaderpacks/<group>/}). Kept out of the live shaderpacks/ folder on purpose: Iris and
     *  OptiFine list every <i>folder</i> inside shaderpacks/ as a selectable pack, so a
     *  shaderpacks/<group>/ staging dir used to appear in the in-game shader selector as a bogus
     *  entry. GameLaunchService copies the actual packs up into shaderpacks/ at launch. */
    public static final String SHADER_STORAGE_DIR = ".bl_shaderpacks";

    /** Directory a content type's per-group files (and its {@code .installed.json}) live in.
     *  Same as the live game folder for most types; shaders are stashed under
     *  {@link #SHADER_STORAGE_DIR} instead (see that field for why). */
    private Path contentStorageDir(String projectType, String profileName) {
        if ("shader".equals(projectType)) {
            return gameDir.resolve(SHADER_STORAGE_DIR).resolve(profileName);
        }
        return gameDir.resolve(folderForType(projectType)).resolve(profileName);
    }

    private Path metaFile(String projectType, String profileName) {
        return contentStorageDir(projectType, profileName).resolve(".installed.json");
    }

    /** Reads the projectId/title metadata recorded for mods installed into this profile's folder. */
    public Map<String, InstalledMeta> loadInstalledMeta(String profileName) {
        return loadInstalledMeta("mod", profileName);
    }

    /** Reads the projectId/title metadata recorded for content of the given type in this profile's folder. */
    public Map<String, InstalledMeta> loadInstalledMeta(String projectType, String profileName) {
        Path file = metaFile(projectType, profileName);
        if (!Files.exists(file)) return new HashMap<>();
        try {
            String json = Files.readString(file);
            Map<String, InstalledMeta> meta = gson.fromJson(json,
                    new TypeToken<Map<String, InstalledMeta>>() {}.getType());
            return meta != null ? meta : new HashMap<>();
        } catch (IOException e) {
            return new HashMap<>();
        }
    }

    private void recordInstalledMeta(String projectType, String profileName, String fileName,
                                     String projectId, String title) {
        InstalledMeta entry = new InstalledMeta();
        entry.projectId = projectId;
        entry.title = title;
        recordInstalledMetaBatch(projectType, profileName, Map.of(fileName, entry));
    }

    /**
     * Records metadata for many files in one go. Installing a modpack drops hundreds of mods into
     * the build at once, and going through {@link #recordInstalledMeta} per file would re-read and
     * re-write this same JSON file once per mod.
     */
    public void recordInstalledMetaBatch(String projectType, String profileName,
                                         Map<String, InstalledMeta> entries) {
        if (entries == null || entries.isEmpty()) return;
        try {
            Map<String, InstalledMeta> meta = loadInstalledMeta(projectType, profileName);
            meta.putAll(entries);
            Files.createDirectories(metaFile(projectType, profileName).getParent());
            Files.writeString(metaFile(projectType, profileName), gson.toJson(meta));
        } catch (IOException ignored) {
            // Best-effort - worst case the install/status badge just falls back to the filename.
        }
    }

    // ---------------------------------------------------------------
    // Search
    // ---------------------------------------------------------------

    /**
     * Searches Modrinth for a project of the given type (mod / shader / resourcepack / datapack /
     * modpack) compatible with the given Minecraft version. The {@code loader} filter only
     * meaningfully applies to mods (and is used purely for relevance ordering, see below); for the
     * other content types Modrinth uses a different set of "loaders" (iris/optifine for shaders,
     * "minecraft" for resource packs, "datapack" for data packs) so it's left unfiltered here.
     */
    public List<ModInfo> search(String query, String mcVersion, String loader, String projectType, int limit)
            throws IOException, InterruptedException {

        StringBuilder url = new StringBuilder(API_BASE + "/search?");
        url.append("query=").append(URLEncoder.encode(query == null ? "" : query, StandardCharsets.UTF_8));
        url.append("&limit=").append(Math.min(limit, 100));

        // Тип проекта (mod / shader / resourcepack / datapack / modpack) ОБЯЗАТЕЛЬНО задаётся
        // через facet, а НЕ через query-параметр "project_type": у эндпоинта /search такого
        // параметра нет, Modrinth его молча игнорирует и всегда возвращает моды. Из-за этого
        // кнопки "Shaders/Resource Packs/Data Packs/Modpacks" раньше показывали обычные моды.
        // Каждый внутренний массив facets - это условие AND между группами; project_type и
        // версию MC объединяем в один список фасетов.
        java.util.List<String> facetGroups = new ArrayList<>();
        facetGroups.add("[\"project_type:" + (projectType == null ? "mod" : projectType) + "\"]");

        // When the user picks a concrete Minecraft version in the Mod Manager, also filter results to
        // it via a facet. (We deliberately don't facet on loader - see the note below - and we skip
        // the version facet entirely for "Any version" so the default stays relevance-first.)
        if (mcVersion != null && !mcVersion.isBlank()) {
            facetGroups.add("[\"versions:" + mcVersion + "\"]");
        }

        String facets = "[" + String.join(",", facetGroups) + "]";
        url.append("&facets=").append(URLEncoder.encode(facets, StandardCharsets.UTF_8));

        // Ни версию MC, ни загрузчик не фильтруем здесь жёстко через facets: Modrinth
        // полностью отбрасывает текстовую релевантность запроса, если facet почти ничего
        // не matchит (например для совсем новой версии MC, которую ещё никто не
        // поддерживает) - поиск "create" в таком случае может вернуть случайные
        // популярные моды вместо Create. Кроме того, многие крупные моды (например
        // Create) публикуют Forge/NeoForge и Fabric версии как РАЗНЫЕ проекты на
        // Modrinth ("create" и "create-fabric"), и жёсткий фильтр по loader половину
        // из них вообще не найдёт по имени. Вместо фильтрации показываем версии и
        // loaders в каждом результате, а реальную совместимость проверяем при
        // установке через resolveBestVersion (там и выводится понятная ошибка).

        Http.Response resp = get(url.toString());
        if (resp.status() != 200) {
            throw new IOException("Modrinth search failed: HTTP " + resp.status());
        }

        JsonObject root = JsonParser.parseString(resp.text()).getAsJsonObject();
        JsonArray hits = root.getAsJsonArray("hits");

        // Известный список загрузчиков Modrinth, чтобы отличить их от обычных
        // тематических категорий (technology, magic и т.п.) внутри поля "categories".
        java.util.Set<String> knownLoaders = java.util.Set.of(
                "fabric", "forge", "neoforge", "quilt", "liteloader",
                "rift", "bukkit", "spigot", "paper", "purpur", "velocity", "sponge",
                "folia", "bungeecord", "waterfall", "datapack",
                "iris", "optifine", "canvas", "vanilla", "minecraft");

        List<ModInfo> results = new ArrayList<>();
        for (JsonElement el : hits) {
            JsonObject hit = el.getAsJsonObject();
            ModInfo mod = new ModInfo(
                    getStr(hit, "project_id"),
                    getStr(hit, "slug"),
                    getStr(hit, "title"),
                    getStr(hit, "description"),
                    getStr(hit, "icon_url"),
                    hit.has("downloads") ? hit.get("downloads").getAsLong() : 0
            );

            List<String> modLoaders = new ArrayList<>();
            if (hit.has("categories")) {
                for (JsonElement c : hit.getAsJsonArray("categories")) {
                    String cat = c.getAsString().toLowerCase();
                    if (knownLoaders.contains(cat)) modLoaders.add(cat);
                }
            }
            mod.setLoaders(modLoaders);

            // "versions" в результатах поиска - полный список поддерживаемых версий MC
            // этого конкретного проекта (например для "Create" это может быть
            // 1.18.2, 1.19.2, 1.20.1 и т.д. - НЕ диапазон, а явный список релизов).
            if (hit.has("versions")) {
                List<String> versions = new ArrayList<>();
                for (JsonElement v : hit.getAsJsonArray("versions")) {
                    versions.add(v.getAsString());
                }
                mod.setGameVersions(versions);
            }

            // Если пользователь выбрал конкретный загрузчик (не vanilla), отдаём
            // совместимые результаты первыми, но не выкидываем остальные —
            // так Create (Forge) и Create Fabric оба будут видны при поиске "create".
            results.add(mod);
        }

        if (loader != null && !loader.isBlank() && !"vanilla".equalsIgnoreCase(loader)) {
            String wantedLoader = loader.toLowerCase();
            results.sort((a, b) -> {
                boolean aMatch = a.getLoaders().contains(wantedLoader);
                boolean bMatch = b.getLoaders().contains(wantedLoader);
                if (aMatch == bMatch) return 0;
                return aMatch ? -1 : 1;
            });
        }

        return results;
    }

    // ---------------------------------------------------------------
    // Version resolution
    // ---------------------------------------------------------------

    /**
     * Returns the best Modrinth version ID for a project that matches
     * the given Minecraft version and loader, or {@code null} if the project has no such version.
     * IOException — только когда Modrinth не ответил (сеть, HTTP-ошибка).
     *
     * 25.10.2026: раньше «версии нет» тоже было IOException, и installModIntoBuild ни разу не дошёл
     * до своей ветки null с подсказкой «есть для 1.20–1.21.11, создайте сборку» — за два дня 7 человек
     * получили вместо неё «Не удалось поставить — подробности в журнале» около 60 раз (чаще на 26.3).
     * А «Починка сборки» принимала то же исключение за сбой сети и не говорила, что версии нет.
     */
    public String resolveBestVersion(String projectId, String mcVersion, String loader)
            throws IOException, InterruptedException {

        StringBuilder url = new StringBuilder(API_BASE + "/project/" + projectId + "/version?");
        if (loader != null && !loader.isBlank() && !"vanilla".equalsIgnoreCase(loader)) {
            String loadersJson = "[\"" + loader.toLowerCase() + "\"]";
            url.append("loaders=").append(URLEncoder.encode(loadersJson, StandardCharsets.UTF_8)).append("&");
        }
        if (mcVersion != null && !mcVersion.isBlank()) {
            String versionsJson = "[\"" + mcVersion + "\"]";
            url.append("game_versions=").append(URLEncoder.encode(versionsJson, StandardCharsets.UTF_8));
        }

        Http.Response resp = get(url.toString());
        if (resp.status() != 200) {
            throw new IOException("Modrinth version lookup failed: HTTP " + resp.status());
        }

        JsonArray versions = JsonParser.parseString(resp.text()).getAsJsonArray();
        if (versions.isEmpty()) return null;

        // First element is the newest compatible version
        return versions.get(0).getAsJsonObject().get("id").getAsString();
    }

    // ---------------------------------------------------------------
    // Required dependencies
    // ---------------------------------------------------------------

    /** Обязательный мод, которого нет в сборке. versionId == null — под эту версию игры его нет. */
    public static class MissingDependency {
        public final String projectId;
        public final String title;
        public final String versionId;

        MissingDependency(String projectId, String title, String versionId) {
            this.projectId = projectId;
            this.title = title;
            this.versionId = versionId;
        }
    }

    /**
     * Обязательные моды, без которых версия {@code versionId} не запустится и которых в сборке ещё нет, —
     * вместе с обязательными для них самих (библиотека может требовать ещё одну библиотеку).
     * Порядок — как их перечислил Modrinth, сначала прямые зависимости.
     *
     * <p>Зачем (12.10.2026): двое новых игроков подряд создали сборку на Fabric, добавили моды и
     * получили вылет «why=mods» — Fabric отказался запускаться без библиотек. Modrinth отдаёт список
     * обязательных зависимостей в самой версии, а лаунчер его не читал вовсе.
     *
     * <p>«Уже в сборке» — по .installed.json и, для файлов, положенных руками, по sha1 через
     * Modrinth: fabric-api.jar, скопированный из браузера, тоже должен считаться.
     */
    public List<MissingDependency> missingRequiredDependencies(String versionId, String mcVersion, String loader,
                                                               String profileName)
            throws IOException, InterruptedException {
        java.util.Set<String> installed = installedProjectIds(profileName);
        Map<String, String> pending = new LinkedHashMap<>();   // projectId -> versionId (null = нет подходящей)
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        queue.add(versionId);
        java.util.Set<String> seenVersions = new java.util.HashSet<>();
        // Глубина у настоящих модов — 2-3 уровня; предел — защита от кольцевых ссылок и опечаток в API.
        while (!queue.isEmpty() && seenVersions.size() < 20) {
            String current = queue.poll();
            if (!seenVersions.add(current)) continue;
            Http.Response resp = get(API_BASE + "/version/" + current);
            if (resp.status() != 200) continue;
            JsonObject version = JsonParser.parseString(resp.text()).getAsJsonObject();
            String self = getStr(version, "project_id");
            if (!self.isEmpty()) installed.add(self);
            if (!version.has("dependencies") || !version.get("dependencies").isJsonArray()) continue;
            for (JsonElement el : version.getAsJsonArray("dependencies")) {
                if (!el.isJsonObject()) continue;
                JsonObject dep = el.getAsJsonObject();
                if (!"required".equals(getStr(dep, "dependency_type"))) continue;
                String projectId = getStr(dep, "project_id");
                if (projectId.isEmpty()) {
                    // Бывает только version_id — проект узнаём по нему.
                    String pinned = getStr(dep, "version_id");
                    if (pinned.isEmpty()) continue;
                    Http.Response pv = get(API_BASE + "/version/" + pinned);
                    if (pv.status() != 200) continue;
                    projectId = getStr(JsonParser.parseString(pv.text()).getAsJsonObject(), "project_id");
                    if (projectId.isEmpty()) continue;
                }
                if (installed.contains(projectId) || pending.containsKey(projectId)) continue;
                // Не версию, прибитую автором (она часто под другую версию игры), а лучшую под эту сборку.
                String best = null;
                try {
                    best = resolveBestVersion(projectId, mcVersion, loader);   // null — версии нет
                } catch (IOException lookupFailed) {
                    // Modrinth не ответил — покажем, что такой мод нужен, но поставить его сейчас нельзя.
                }
                pending.put(projectId, best);
                if (best != null) queue.add(best);
            }
        }
        if (pending.isEmpty()) return List.of();

        Map<String, String> titles = projectTitles(pending.keySet());
        List<MissingDependency> out = new ArrayList<>();
        for (Map.Entry<String, String> e : pending.entrySet()) {
            out.add(new MissingDependency(e.getKey(), titles.getOrDefault(e.getKey(), e.getKey()), e.getValue()));
        }
        return out;
    }

    /** Проекты Modrinth, которые уже лежат в mods/<сборка>/ — включённые и выключенные. */
    private java.util.Set<String> installedProjectIds(String profileName) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        Map<String, InstalledMeta> meta = loadInstalledMeta("mod", profileName);
        Path dir = contentStorageDir("mod", profileName);
        Map<String, Path> unknown = new HashMap<>();
        if (Files.isDirectory(dir)) {
            try (var files = Files.list(dir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    String name = f.getFileName().toString();
                    if (!name.endsWith(".jar") && !name.endsWith(".jar.disabled")) continue;
                    String base = name.endsWith(".disabled") ? name.substring(0, name.length() - 9) : name;
                    InstalledMeta m = meta.containsKey(name) ? meta.get(name) : meta.get(base);
                    if (m != null && m.projectId != null && !m.projectId.isBlank()) {
                        ids.add(m.projectId);
                    } else {
                        try { unknown.put(sha1Of(f), f); } catch (IOException ignored) { }
                    }
                }
            } catch (IOException ignored) {
                // папки нет или не читается — значит, и ставить надо всё
            }
        }
        if (!unknown.isEmpty()) {
            try { ids.addAll(projectIdsByHash(unknown.keySet()).values()); } catch (IOException ignored) { }
        }
        return ids;
    }

    /** Названия проектов одним запросом; не нашлось — в ответе ключа не будет. */
    private Map<String, String> projectTitles(Collection<String> projectIds) {
        Map<String, String> titles = new HashMap<>();
        try {
            JsonArray ids = new JsonArray();
            for (String id : projectIds) ids.add(id);
            Http.Response resp = get(API_BASE + "/projects?ids="
                    + URLEncoder.encode(ids.toString(), StandardCharsets.UTF_8));
            if (!resp.ok()) return titles;
            for (JsonElement el : JsonParser.parseString(resp.text()).getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject p = el.getAsJsonObject();
                String id = getStr(p, "id");
                String title = getStr(p, "title");
                if (!id.isEmpty() && !title.isEmpty()) titles.put(id, title);
            }
        } catch (Exception ignored) {
            // без названий покажем id — хуже, но не повод отменять установку
        }
        return titles;
    }

    // ---------------------------------------------------------------
    // Download
    // ---------------------------------------------------------------

    /**
     * Like {@link #downloadMod}, but for any content type - the file lands in
     * {@code <folderForType(projectType)>/<profile>/} (e.g. {@code shaderpacks/<profile>/}).
     */
    public String downloadContent(String versionId, String profileName, String projectType,
                                  String projectId, String title, ProgressCallback progressCallback)
            throws IOException, InterruptedException {
        return downloadContent(versionId, profileName, projectType, projectId, title, progressCallback, null);
    }

    /** То же, плюс ход загрузки файла в байтах — для шкалы в интерфейсе. */
    public String downloadContent(String versionId, String profileName, String projectType,
                                  String projectId, String title, ProgressCallback progressCallback,
                                  Http.Progress bytes)
            throws IOException, InterruptedException {

        // 1. Fetch version metadata to get the primary file URL
        progressCallback.onProgress("Fetching metadata...");
        Http.Response resp = get(API_BASE + "/version/" + versionId);
        if (resp.status() != 200) {
            throw new IOException("Failed to fetch version metadata: HTTP " + resp.status());
        }

        JsonObject version = JsonParser.parseString(resp.text()).getAsJsonObject();
        JsonArray files = version.getAsJsonArray("files");

        // Find primary file
        JsonObject primaryFile = null;
        for (JsonElement fe : files) {
            JsonObject f = fe.getAsJsonObject();
            if (f.has("primary") && f.get("primary").getAsBoolean()) {
                primaryFile = f;
                break;
            }
        }
        if (primaryFile == null && !files.isEmpty()) {
            primaryFile = files.get(0).getAsJsonObject();
        }
        if (primaryFile == null) {
            throw new IOException("No downloadable file found for version " + versionId);
        }

        String fileUrl  = primaryFile.get("url").getAsString();
        String fileName = primaryFile.get("filename").getAsString();
        // Modrinth отдаёт hashes рядом с url и filename — в том же объекте, из которого мы их
        // и читаем. Раньше они просто игнорировались, хотя ModpackService те же самые хеши
        // проверяет; теперь сверяем и мы.
        String expectedSha1 = null;
        if (primaryFile.has("hashes") && primaryFile.get("hashes").isJsonObject()) {
            JsonObject hashes = primaryFile.getAsJsonObject("hashes");
            if (hashes.has("sha1") && hashes.get("sha1").isJsonPrimitive()) {
                expectedSha1 = hashes.get("sha1").getAsString();
            }
        }

        // 2. Ensure the target directory exists
        Path targetDir = contentStorageDir(projectType, profileName);
        Files.createDirectories(targetDir);
        Path dest = targetDir.resolve(fileName);

        if (Files.exists(dest)) {
            // Если хеш известен — проверяем, а не верим на слово. Прежняя проверка «файл есть»
            // означала, что обрезанный при обрыве файл считался установленным НАВСЕГДА: игра
            // падала на нём при каждом запуске, а перекачать его лаунчер отказывался.
            if (expectedSha1 == null || expectedSha1.equalsIgnoreCase(sha1Of(dest))) {
                progressCallback.onProgress("Already installed: " + fileName);
                recordInstalledMeta(projectType, profileName, fileName, projectId, title);
                return fileName;
            }
            progressCallback.onProgress("Файл повреждён, качаю заново: " + fileName);
            Files.deleteIfExists(dest);
        }

        // 3. Download
        progressCallback.onProgress("Downloading " + fileName + "...");
        // Через Http.download, а не get(): тот читает ответ целиком в byte[], и ресурспак или
        // шейдерпак на 300-800 МБ ложил лаунчер по памяти. Http.download пишет потоком в
        // .part и переименовывает — то есть обрыв не оставляет обрезанный файл под настоящим
        // именем. Этот метод существовал и использовался всеми остальными путями загрузки.
        long written = Http.download(fileUrl, dest, Map.of("User-Agent", USER_AGENT),
                java.time.Duration.ofMinutes(10), bytes);
        if (written < 0) {
            throw new IOException("Download failed: " + fileName);
        }

        if (expectedSha1 != null) {
            String actual = sha1Of(dest);
            if (!expectedSha1.equalsIgnoreCase(actual)) {
                Files.deleteIfExists(dest);
                throw new IOException("Файл скачался повреждённым (" + fileName
                        + "): sha1 " + actual + " вместо " + expectedSha1);
            }
        }

        progressCallback.onProgress("Installed: " + fileName);
        recordInstalledMeta(projectType, profileName, fileName, projectId, title);
        return fileName;
    }

    /**
     * Мод не с Modrinth, а по прямой ссылке — эксклюзивы BLauncher с blauncher.ru (ExclusiveModsService).
     * Кладётся туда же, куда и моды с Modrinth (mods/&lt;сборка&gt;/), с той же проверкой sha1 и записью в
     * .installed.json — чтобы «Моды сборки», синхронизация и «Починка» видели его как обычный мод.
     */
    public String downloadDirect(String url, String fileName, String expectedSha1, String profileName,
                                 String projectId, String title, Http.Progress bytes) throws IOException {
        Path targetDir = contentStorageDir("mod", profileName);
        Files.createDirectories(targetDir);
        Path dest = targetDir.resolve(fileName);
        if (Files.exists(dest)) {
            if (expectedSha1 == null || expectedSha1.equalsIgnoreCase(sha1Of(dest))) {
                recordInstalledMeta("mod", profileName, fileName, projectId, title);
                return fileName;
            }
            Files.deleteIfExists(dest);
        }
        long written = Http.download(url, dest, Map.of("User-Agent", USER_AGENT), java.time.Duration.ofMinutes(10), bytes);
        if (written < 0) {
            throw new IOException("Download failed: " + fileName);
        }
        if (expectedSha1 != null) {
            String actual = sha1Of(dest);
            if (!expectedSha1.equalsIgnoreCase(actual)) {
                Files.deleteIfExists(dest);
                throw new IOException("Файл скачался повреждённым (" + fileName + "): sha1 " + actual + " вместо " + expectedSha1);
            }
        }
        recordInstalledMeta("mod", profileName, fileName, projectId, title);
        return fileName;
    }

    /** sha1 файла в нижнем регистре — в таком виде его публикует Modrinth. */
    static String sha1Of(Path file) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            try (java.io.InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder(40);
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("В этой JVM нет SHA-1", e);
        }
    }

    // ---------------------------------------------------------------
    // Modpack (.mrpack) archives
    // ---------------------------------------------------------------

    /**
     * Downloads the {@code .mrpack} archive of a Modrinth version into a temporary file and returns
     * its path. The caller owns that file and is expected to delete it once the pack has been read
     * (and possibly installed).
     *
     * <p>What the archive contains and where its files belong is {@link ModpackService}'s job -
     * this method only turns Modrinth's file list into bytes on disk, and streams them there
     * instead of buffering a 200 MB pack in the heap the way the old install path did.
     */
    public Path downloadModpackArchive(String versionId, ProgressCallback cb)
            throws IOException, InterruptedException {
        return downloadModpackArchive(versionId, cb, null);
    }

    /** То же, плюс ход загрузки архива в байтах — для шкалы в интерфейсе. */
    public Path downloadModpackArchive(String versionId, ProgressCallback cb, Http.Progress bytes)
            throws IOException, InterruptedException {

        if (cb != null) cb.onProgress("Получение информации о сборке...");
        Http.Response resp = get(API_BASE + "/version/" + versionId);
        if (resp.status() != 200) {
            throw new IOException("Не удалось получить данные о сборке: HTTP " + resp.status());
        }
        JsonObject version = JsonParser.parseString(resp.text()).getAsJsonObject();
        JsonArray files = version.has("files") && version.get("files").isJsonArray()
                ? version.getAsJsonArray("files") : new JsonArray();

        // Modrinth lists every file of a version here, and older modpacks were published with a
        // plain .zip: prefer the primary .mrpack, fall back to any .mrpack, then to whatever the
        // version does offer - ModpackService rejects anything that isn't a pack anyway.
        JsonObject archive = null;
        JsonObject firstFile = null;
        for (JsonElement fe : files) {
            if (!fe.isJsonObject()) continue;
            JsonObject f = fe.getAsJsonObject();
            if (!f.has("url")) continue;
            if (firstFile == null) firstFile = f;
            String name = f.has("filename") ? f.get("filename").getAsString().toLowerCase() : "";
            if (!name.endsWith(".mrpack")) continue;
            if (f.has("primary") && f.get("primary").getAsBoolean()) { archive = f; break; }
            if (archive == null) archive = f;
        }
        if (archive == null) archive = firstFile;
        if (archive == null) throw new IOException("У этой сборки нет файла для скачивания.");

        String fileName = archive.has("filename") ? archive.get("filename").getAsString() : "";
        if (cb != null) {
            cb.onProgress("Скачивание архива сборки"
                    + (fileName.isBlank() ? "" : " (" + fileName + ")") + "...");
        }

        Path temp = Files.createTempFile("blauncher-modpack-", ".mrpack");
        long written;
        try {
            written = Http.download(archive.get("url").getAsString(), temp,
                    Map.of("User-Agent", USER_AGENT), Http.DEFAULT_TIMEOUT, bytes);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw new IOException("Не удалось скачать архив сборки: " + e.getMessage(), e);
        }
        if (written < 0) {
            Files.deleteIfExists(temp);
            throw new IOException("Не удалось скачать архив сборки: сервер вернул ошибку.");
        }
        return temp;
    }

    // ---------------------------------------------------------------
    // Local mod management
    // ---------------------------------------------------------------

    /** Lists all mods (enabled and disabled) for a profile, with friendly titles where known. */
    public List<ModInfo> listInstalledMods(String profileName) {
        Path modsDir = gameDir.resolve("mods").resolve(profileName);
        List<ModInfo> mods = new ArrayList<>();
        if (!Files.exists(modsDir)) return mods;
        Map<String, InstalledMeta> meta = loadInstalledMeta(profileName);
        try (var stream = Files.list(modsDir)) {
            stream.forEach(p -> {
                String name = p.getFileName().toString();
                ModInfo mod = null;
                if (name.endsWith(".jar")) {
                    mod = new ModInfo(name, true);
                } else if (name.endsWith(".jar.disabled")) {
                    mod = new ModInfo(name, false);
                }
                if (mod != null) {
                    InstalledMeta m = meta.get(name);
                    if (m != null) {
                        mod.setTitle(m.title);
                        mod.setProjectId(m.projectId);
                    }
                    mods.add(mod);
                }
            });
        } catch (IOException ignored) {}
        return mods;
    }

    /** Where the edit-build dialog looks for each kind of content: the folder downloadContent()
     *  writes into. Public so the dialog can hash files for icons from the same place. */
    public Path contentDir(String projectType, String profileName) {
        return contentStorageDir(projectType, profileName);
    }

    /**
     * Lists a profile's installed content of one type - resource packs, data packs or shader
     * packs - from the per-build folder downloadContent() writes into, with titles where known.
     * Mods go through {@link #listInstalledMods} (they have the .jar/.jar.disabled distinction).
     *
     * <p>Packs are .zip files, but a pack unpacked into a folder is just as valid for Minecraft,
     * so folders are listed too. The .installed.json metadata and unfinished .part downloads are not.
     */
    public List<ModInfo> listInstalledContent(String projectType, String profileName) {
        if (projectType == null || "mod".equals(projectType)) return listInstalledMods(profileName);
        Path dir = contentStorageDir(projectType, profileName);
        List<ModInfo> items = new ArrayList<>();
        if (!Files.isDirectory(dir)) return items;
        Map<String, InstalledMeta> meta = loadInstalledMeta(projectType, profileName);
        try (var stream = Files.list(dir)) {
            stream.sorted().forEach(p -> {
                String name = p.getFileName().toString();
                if (name.equals(".installed.json") || name.endsWith(".part")) return;
                ModInfo item = new ModInfo(name, true);
                InstalledMeta m = meta.get(name);
                if (m != null) {
                    item.setTitle(m.title);
                    item.setProjectId(m.projectId);
                }
                items.add(item);
            });
        } catch (IOException ignored) {}
        return items;
    }

    /**
     * Removes one item from the build's content folder (a file, or an unpacked pack folder).
     * Only the build's own copy: the copy already made into the live game folder at an earlier
     * launch is left alone, the same way the additive sync never deletes from there.
     */
    public void deleteContent(String projectType, String profileName, ModInfo item) throws IOException {
        if (projectType == null || "mod".equals(projectType)) {
            deleteMod(profileName, item);
            return;
        }
        Path dir = contentStorageDir(projectType, profileName);
        Path target = dir.resolve(item.getFileName()).normalize();
        if (!target.startsWith(dir)) throw new IOException("Путь вне папки сборки: " + item.getFileName());
        if (Files.isDirectory(target)) {
            try (var walk = Files.walk(target)) {
                for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(p);
            }
        } else {
            Files.deleteIfExists(target);
        }
        Map<String, InstalledMeta> meta = loadInstalledMeta(projectType, profileName);
        if (meta.remove(item.getFileName()) != null) {
            Files.writeString(metaFile(projectType, profileName), gson.toJson(meta));
        }
    }

    /** Deletes a mod file from the profile mods folder. */
    public void deleteMod(String profileName, ModInfo mod) throws IOException {
        Path modsDir = gameDir.resolve("mods").resolve(profileName);
        Files.deleteIfExists(modsDir.resolve(mod.getFileName()));
    }

    /** Outcome of {@link #disableModFilesMatching} - separates what actually got disabled from
     *  what was found but couldn't be touched (e.g. a locked file), so callers can tell the user
     *  the difference instead of treating "found nothing to do" the same as "tried and failed". */
    public static class DisableResult {
        public final List<String> disabled = new ArrayList<>();
        public final Map<String, String> failed = new LinkedHashMap<>();
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private String getStr(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : "";
    }

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(String message);
    }
}
