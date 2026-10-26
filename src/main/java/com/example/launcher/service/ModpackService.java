package com.example.launcher.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads and installs Modrinth modpacks — the {@code .mrpack} files players download from
 * Modrinth (and from the "Модпаки" tab of the Mods screen).
 *
 * <p>A {@code .mrpack} is an ordinary ZIP with one manifest at its root,
 * {@code modrinth.index.json}, plus optional {@code overrides/} and {@code client-overrides/}
 * trees. The manifest names the pack, the Minecraft version and loader it targets
 * ({@code dependencies}) and every file that has to be downloaded ({@code files}), each with
 * the paths it belongs at, a list of mirror URLs, hashes and an {@code env} block saying whether
 * the client, the server, or both need it.
 *
 * <p>"Reading" a pack therefore means exactly two things, and this class is the only place that
 * does either:
 * <ul>
 *   <li>{@link #read} — parse the manifest into a {@link PackInfo} so the launcher can show the
 *       player what the archive contains (name, Minecraft version, loader, file count, size)
 *       before anything is written to disk;</li>
 *   <li>{@link #install} — download every client file, verify it against the hashes in the
 *       manifest, drop it at the location the pack asks for, then apply the overrides tree.
 *       Files the pack marks as {@code env.client = "unsupported"} (server-only mods) are skipped.</li>
 * </ul>
 *
 * <p>Where files land follows BLauncher's per-build layout rather than the pack's own: content
 * the launcher isolates per build (mods/resourcepacks/datapacks, and shaders in the hidden
 * {@link ModrinthService#SHADER_STORAGE_DIR} stash) goes into that build's subfolder, everything
 * else (configs, options.txt, ...) goes into the shared game directory exactly as the pack
 * expects. See {@link #targetPath}.
 */
public class ModpackService {

    /** Manifest every Modrinth pack carries at the root of its ZIP. */
    public static final String INDEX_NAME = "modrinth.index.json";

    /** Modrinth asks every API client to identify itself; used for the file downloads too. */
    private static final Map<String, String> HEADERS =
            Map.of("User-Agent", "MinecraftLauncher/1.0 (github.com/example/launcher)");

    /** A manifest bigger than this is not a real modpack (a 1000-mod pack's index is ~1 MB), and
     *  reading it into memory unbounded would let a hostile archive exhaust the heap. */
    private static final int MAX_INDEX_BYTES = 32 * 1024 * 1024;

    /** Minecraft's own per-type folders that BLauncher keeps separate for every build - the same
     *  set {@link GameLaunchService} syncs out of {@code <type>/<build>/} at launch. */
    private static final String[] PER_BUILD_DIRS = {"mods", "resourcepacks", "datapacks"};

    private final Path gameDir;

    public ModpackService(Path gameDir) {
        this.gameDir = gameDir.toAbsolutePath().normalize();
    }

    // ---------------------------------------------------------------
    // Export: build -> .mrpack (17.10.2026)
    // ---------------------------------------------------------------

    /** Что получилось при экспорте — для итогового сообщения. */
    public static class ExportReport {
        /** Файлов, которые пак скачает с Modrinth. */
        public int hosted;
        /** Файлов, положенных внутрь архива (overrides/): их нет на Modrinth. */
        public int bundled;
        public long bytes;
        public final List<String> warnings = new ArrayList<>();
    }

    /** Папки сборки и путь, под которым они лежат в паке; тот же набор читает импорт (applyOverrides). */
    private static final String[][] EXPORT_DIRS = {
            {"mods", "mods"}, {"resourcepacks", "resourcepacks"}, {"datapacks", "datapacks"},
            {ModrinthService.SHADER_STORAGE_DIR, "shaderpacks"}};

    /**
     * Собрать .mrpack из сборки лаунчера: моды, наборы ресурсов, датапаки и шейдеры этой сборки.
     * Файлы, которые Modrinth узнаёт по sha1, идут в {@code modrinth.index.json} ссылками (так
     * пак весит килобайты и его примет любой лаунчер с поддержкой Modrinth), остальные — в
     * {@code overrides/}. Modrinth недоступен — всё уходит в overrides, пак всё равно рабочий.
     *
     * <p>Архив пишется во временный файл рядом и переносится на место одним шагом: оборванный
     * экспорт не оставит половину .mrpack под нужным именем.
     */
    public ExportReport export(String buildName, String mcVersion, String loader, String loaderVersion,
                               String packName, String packVersion, Path out, ModrinthService modrinth,
                               java.util.function.Consumer<String> progress) throws IOException {
        String depKey = switch (loader == null ? "vanilla" : loader.toLowerCase(java.util.Locale.ROOT)) {
            case "", "vanilla" -> null;
            case "fabric" -> "fabric-loader";
            case "quilt" -> "quilt-loader";
            case "forge" -> "forge";
            case "neoforge" -> "neoforge";
            default -> throw new IOException("Сборки на " + loader + " формат .mrpack не поддерживает"
                    + " (там только Fabric, Quilt, Forge и NeoForge)");
        };
        if (depKey != null && (loaderVersion == null || loaderVersion.isBlank())) {
            throw new IOException("У сборки не записана версия " + loader
                    + " — запустите её один раз, лаунчер её запомнит");
        }
        if (mcVersion == null || mcVersion.isBlank()) throw new IOException("У сборки не указана версия Minecraft");

        ExportReport report = new ExportReport();
        // 1. Файлы сборки и их sha1
        record Entry(Path file, String packPath, String sha1) {}
        List<Entry> entries = new ArrayList<>();
        for (String[] dir : EXPORT_DIRS) {
            Path src = gameDir.resolve(dir[0]).resolve(buildName);
            if (!Files.isDirectory(src)) continue;
            try (var list = Files.list(src)) {
                for (Path f : (Iterable<Path>) list.sorted()::iterator) {
                    String name = f.getFileName().toString();
                    // .installed.json, метки синхронизации и прочие служебные файлы лаунчера
                    if (name.startsWith(".")) continue;
                    if (!Files.isRegularFile(f)) {
                        // шейдер распакованной папкой и т.п.: в .mrpack идут только файлы
                        report.warnings.add("Папка «" + name + "» пропущена — в .mrpack кладутся только файлы");
                        continue;
                    }
                    entries.add(new Entry(f, dir[1] + "/" + name, hashHex(f, "SHA-1")));
                }
            }
        }
        if (progress != null) progress.accept("Файлов в сборке: " + entries.size() + ". Ищу их на Modrinth...");

        Map<String, ModrinthService.HostedFile> hosted = Map.of();
        try {
            List<String> hashes = new ArrayList<>();
            for (Entry e : entries) hashes.add(e.sha1());
            hosted = modrinth.hostedFilesByHash(hashes);
        } catch (IOException | RuntimeException e) {
            report.warnings.add("Modrinth недоступен (" + e.getMessage() + ") — все файлы положены внутрь архива");
        }

        // 2. modrinth.index.json
        JsonObject index = new JsonObject();
        index.addProperty("formatVersion", 1);
        index.addProperty("game", "minecraft");
        index.addProperty("versionId", packVersion);
        index.addProperty("name", packName);
        index.addProperty("summary", "Собрано в BLauncher из сборки «" + buildName + "»");
        JsonArray files = new JsonArray();
        List<Entry> bundled = new ArrayList<>();
        for (Entry e : entries) {
            ModrinthService.HostedFile h = hosted.get(e.sha1());
            if (h == null) {
                bundled.add(e);
                continue;
            }
            JsonObject f = new JsonObject();
            f.addProperty("path", e.packPath());
            JsonObject hs = new JsonObject();
            hs.addProperty("sha1", h.sha1());
            hs.addProperty("sha512", h.sha512());
            f.add("hashes", hs);
            JsonArray dl = new JsonArray();
            dl.add(h.url());
            f.add("downloads", dl);
            f.addProperty("fileSize", h.size() > 0 ? h.size() : Files.size(e.file()));
            files.add(f);
            report.hosted++;
        }
        index.add("files", files);
        JsonObject deps = new JsonObject();
        deps.addProperty("minecraft", mcVersion);
        if (depKey != null) deps.addProperty(depKey, loaderVersion);
        index.add("dependencies", deps);

        // 3. Архив: сначала во временный файл, потом на место
        Path parent = out.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = Files.createTempFile(parent, ".bl-export-", ".tmp");
        try {
            try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(tmp))) {
                zip.putNextEntry(new ZipEntry(INDEX_NAME));
                zip.write(new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
                        .toJson(index).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
                int n = 0;
                for (Entry e : bundled) {
                    if (progress != null) progress.accept("Кладу в архив (" + (++n) + "/" + bundled.size() + "): "
                            + e.file().getFileName());
                    zip.putNextEntry(new ZipEntry("overrides/" + e.packPath()));
                    Files.copy(e.file(), zip);
                    zip.closeEntry();
                    report.bundled++;
                }
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        report.bytes = Files.size(out);
        return report;
    }

    private static String hashHex(Path file, String algorithm) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) md.update(buf, 0, r);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    // ---------------------------------------------------------------
    // What a pack contains
    // ---------------------------------------------------------------

    /**
     * Everything the launcher knows about a pack after reading its manifest. Purely a value
     * object: it holds no file list and no open archive, so it is safe to hand to the UI thread
     * and to keep around while the player decides whether to install.
     */
    public static class PackInfo {
        /** {@code name} from the manifest - the launcher offers this as the new build's name. */
        public String name = "";
        /** {@code versionId} - the pack's OWN version (e.g. "1.4.2"), not a Minecraft version. */
        public String packVersion = "";
        /** {@code summary} - the one-line description Modrinth shows for the pack. */
        public String summary = "";
        public String mcVersion = "";
        /** Internal loader key: vanilla / fabric / forge / neoforge / quilt / liteloader. */
        public String loader = "vanilla";
        public String loaderVersion = "";
        /** False when the pack needs a loader BLauncher cannot install (Quilt, LiteLoader, ...). */
        public boolean loaderSupported = true;
        /** Files under {@code mods/} that the client needs - what the player thinks of as "модов". */
        public int modCount;
        /** Every file the client has to download (mods + resourcepacks + shaders + ...). */
        public int fileCount;
        /** Entries skipped because the pack marks them {@code env.client = "unsupported"}. */
        public int serverOnlyCount;
        /** Entries the pack lists with no download URL at all, so nothing can be installed. */
        public int undownloadableCount;
        /** Files inside {@code overrides/} + {@code client-overrides/} that are copied verbatim. */
        public int overrideCount;
        /** Sum of the manifest's {@code fileSize} values, for the "≈ N МБ" line. 0 if not given. */
        public long downloadBytes;
        /** Для статистики (MainController): пак пришёл из каталога Modrinth, а не из файла, и сколько секунд качался архив. */
        public boolean fromModrinth;
        public long fetchSeconds;
        /** Where the pack came from - the chosen file's name, or "Modrinth: <название>". */
        public String sourceLabel = "";
        /** Pack icon URL, when the pack was picked from Modrinth's catalogue. */
        public String iconUrl = "";
        /** Things the player should know before pressing "Установить" (unsupported loader, ...). */
        public final List<String> warnings = new ArrayList<>();

        /** "Fabric 0.15.11" / "NeoForge 21.1.9" / "Vanilla" - for the summary screen. */
        public String loaderLabel() {
            String name = switch (loader == null ? "" : loader) {
                case "fabric" -> "Fabric";
                case "forge" -> "Forge";
                case "neoforge" -> "NeoForge";
                case "quilt" -> "Quilt";
                case "liteloader" -> "LiteLoader";
                default -> "Vanilla";
            };
            return loaderVersion == null || loaderVersion.isBlank() ? name : name + " " + loaderVersion;
        }

        /** "≈ 340 МБ", or "" when the manifest carried no sizes at all. */
        public String sizeLabel() {
            return downloadBytes <= 0 ? "" : "≈ " + formatBytes(downloadBytes);
        }
    }

    /** What an {@link #install} run actually put on disk. */
    public static class InstallReport {
        public int downloaded;
        /** Files already present and still matching the manifest's hash/size. */
        public int skippedExisting;
        public int overridesApplied;
        /** Pack path → why it could not be downloaded from any of its mirrors. */
        public final Map<String, String> failures = new LinkedHashMap<>();
        /** Installed mod file name → Modrinth project id, parsed back out of its download URL.
         *  Lets the Mod Manager keep showing which mods came from Modrinth (and offer updates)
         *  instead of a folder full of anonymous jars - see ModrinthService.recordInstalledMetaBatch. */
        public final Map<String, String> modProjectIds = new LinkedHashMap<>();

        public int installedTotal() {
            return downloaded + skippedExisting;
        }
    }

    // ---------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------

    /** Reads a {@code .mrpack} and describes it. Writes nothing to disk. */
    public PackInfo read(Path mrpack) throws IOException {
        return parse(mrpack).info;
    }

    /**
     * Installs an already-read pack into {@code profileName}'s build folders.
     *
     * <p>Nothing is aborted by a single bad file: each manifest entry is tried against every
     * mirror it lists, and one that cannot be fetched is recorded in
     * {@link InstallReport#failures} so the caller can tell the player exactly what is missing -
     * losing a 300-mod install to one dead mirror is not acceptable. Only an unreadable archive
     * (or an interrupted install) throws.
     */
    public InstallReport install(Path mrpack, String profileName,
                                 ModrinthService.ProgressCallback cb)
            throws IOException, InterruptedException {
        return install(mrpack, profileName, cb, null);
    }

    /**
     * То же, плюс общий ход установки для шкалы: {@code overall.update(сделано, всего)} по всей
     * сборке. Единица — байты из манифеста; если у части файлов размер не указан, каждый такой
     * файл считается за 1 МБ, чтобы шкала всё равно двигалась. Готовые, пропущенные и неудачные
     * файлы засчитываются целиком — шкала идёт только вперёд.
     */
    public InstallReport install(Path mrpack, String profileName,
                                 ModrinthService.ProgressCallback cb, Http.Progress overall)
            throws IOException, InterruptedException {

        ParsedPack pack = parse(mrpack);
        PackInfo info = pack.info;
        InstallReport report = new InstallReport();

        final long unknownSize = 1L << 20;
        long totalUnits = 0;
        for (PackFile f : pack.files) totalUnits += f.size > 0 ? f.size : unknownSize;
        final long allUnits = Math.max(1, totalUnits);
        long doneUnits = 0;

        int total = pack.files.size();
        int index = 0;
        for (PackFile file : pack.files) {
            checkInterrupted();
            index++;
            long fileUnits = file.size > 0 ? file.size : unknownSize;
            if (overall != null) overall.update(doneUnits, allUnits);
            final long base = doneUnits;
            doneUnits += fileUnits;
            // A path the archive has no right to ask for is refused here (nothing is ever written
            // outside the game directory) and reported like any other unusable entry: aborting the
            // whole pack because one line of its manifest is broken would be worse than skipping it.
            Path dest;
            try {
                dest = targetPath(profileName, file.path);
            } catch (IOException e) {
                report.failures.put(file.path, e.getMessage());
                continue;
            }
            if (cb != null) {
                cb.onProgress("Скачивание (" + index + "/" + total + "): " + dest.getFileName());
            }
            // matches() читает файл, то есть может бросить IOException — например когда уже
            // лежащий jar ещё держит только что вышедшая JVM игры. Раньше это исключение уходило
            // наружу из install() и срывало установку всего пака с сырым сообщением
            // ввода-вывода, хотя javadoc метода обещает: «ничего не прерывается из-за одного
            // плохого файла». Теперь нечитаемый файл просто считается несовпавшим и будет
            // перекачан, а совсем неудачный случай попадёт в report.failures ниже.
            boolean alreadyGood;
            try {
                alreadyGood = Files.isRegularFile(dest) && matches(dest, file);
            } catch (IOException e) {
                alreadyGood = false;
            }
            if (alreadyGood) {
                report.skippedExisting++;
                rememberModProject(report, file);
                continue;
            }
            Http.Progress fileProgress = overall == null ? null
                    : (done, len) -> overall.update(base + Math.min(done, fileUnits), allUnits);
            String failure = downloadTo(dest, file, fileProgress);
            if (failure == null) {
                report.downloaded++;
                rememberModProject(report, file);
            } else {
                report.failures.put(file.path, failure);
            }
        }

        if (overall != null) overall.update(allUnits, allUnits);
        if (cb != null) cb.onProgress("Применяю настройки из сборки (overrides)...");
        report.overridesApplied = applyOverrides(mrpack, pack.root, profileName, report);

        if (cb != null) {
            cb.onProgress("Готово: " + report.installedTotal() + " из " + info.fileCount + " файлов.");
        }
        return report;
    }

    // ---------------------------------------------------------------
    // Manifest parsing
    // ---------------------------------------------------------------

    /** An archive that has been read once: the summary plus the entries to install. */
    private static final class ParsedPack {
        PackInfo info;
        /** Prefix inside the ZIP that contains the manifest - "" for a normal pack, "sub/dir/"
         *  for a hand-repacked archive that wraps everything in a folder. */
        String root = "";
        final List<PackFile> files = new ArrayList<>();
    }

    /** One downloadable entry of {@code files[]}. */
    private static final class PackFile {
        String path;
        final List<String> urls = new ArrayList<>();
        String sha512;
        String sha1;
        long size;
    }

    private ParsedPack parse(Path mrpack) throws IOException {
        if (mrpack == null || !Files.isRegularFile(mrpack)) {
            throw new IOException("Файл сборки не найден.");
        }
        ParsedPack pack = new ParsedPack();
        try (ZipFile zip = openZip(mrpack)) {
            pack.root = indexRoot(zip);
            JsonObject index = parseIndex(zip, pack.root);
            pack.info = parseInfo(index, mrpack.getFileName().toString());
            pack.files.addAll(parseFiles(index));
            fillCounts(index, pack.info);
            countOverrides(zip, pack.root, pack.info);
        }
        return pack;
    }

    /** Opens the archive as UTF-8: modpack paths routinely contain non-ASCII characters, and the
     *  platform default (windows-1251 on a Russian Windows) would mangle them into unusable names. */
    private static ZipFile openZip(Path mrpack) throws IOException {
        try {
            return new ZipFile(mrpack.toFile(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IOException("Не удалось открыть архив сборки (это точно .mrpack?): " + e.getMessage(), e);
        }
    }

    private static String indexRoot(ZipFile zip) throws IOException {
        if (zip.getEntry(INDEX_NAME) != null) return "";
        String found = null;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (!name.endsWith("/" + INDEX_NAME)) continue;
            String prefix = name.substring(0, name.length() - INDEX_NAME.length());
            if (found != null && !found.equals(prefix)) {
                throw new IOException("В архиве несколько " + INDEX_NAME + " — это не одна сборка.");
            }
            found = prefix;
        }
        if (found == null) {
            throw new IOException("Это не сборка Modrinth: в архиве нет " + INDEX_NAME + ".");
        }
        return found;
    }

    private static JsonObject parseIndex(ZipFile zip, String root) throws IOException {
        ZipEntry entry = zip.getEntry(root + INDEX_NAME);
        if (entry == null) throw new IOException("В архиве нет " + INDEX_NAME + ".");
        String text;
        try (InputStream in = zip.getInputStream(entry)) {
            text = new String(readAll(in), StandardCharsets.UTF_8);
        }
        try {
            JsonElement parsed = JsonParser.parseString(text);
            if (!parsed.isJsonObject()) throw new IOException("не объект JSON");
            return parsed.getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Не удалось разобрать " + INDEX_NAME + ": " + e.getMessage(), e);
        }
    }

    private static PackInfo parseInfo(JsonObject index, String sourceLabel) throws IOException {
        PackInfo info = new PackInfo();
        info.sourceLabel = sourceLabel;
        info.name = getStr(index, "name");
        info.packVersion = getStr(index, "versionId");
        info.summary = getStr(index, "summary");

        // "game" is "minecraft" in every real pack; anything else is a pack for another game that
        // happens to reuse the format, and installing it into a Minecraft instance is never right.
        String game = getStr(index, "game");
        if (!game.isBlank() && !"minecraft".equalsIgnoreCase(game)) {
            throw new IOException("Это сборка не для Minecraft (game = \"" + game + "\").");
        }

        JsonObject deps = index.has("dependencies") && index.get("dependencies").isJsonObject()
                ? index.getAsJsonObject("dependencies") : new JsonObject();
        info.mcVersion = getStr(deps, "minecraft");
        if (info.mcVersion.isBlank()) {
            throw new IOException("В сборке не указана версия Minecraft (dependencies.minecraft).");
        }
        readLoader(info, deps);

        if (info.name.isBlank()) info.name = stripExtension(sourceLabel);
        if (info.name.isBlank()) info.name = "Сборка Modrinth";
        return info;
    }

    /**
     * The manifest's {@code dependencies} names exactly one loader. Quilt and LiteLoader are
     * recognised but flagged: the launcher has no installer for them (see
     * mapDisplayLoaderToInternal in MainController), and silently launching a Quilt pack on
     * Fabric would produce a game that crashes on the first Quilt-only mod - so the caller is
     * expected to say so before installing rather than pretend it is supported.
     */
    private static void readLoader(PackInfo info, JsonObject deps) {
        String[] keys = {"fabric-loader", "quilt-loader", "neoforge", "forge", "liteloader"};
        String[] loaders = {"fabric", "quilt", "neoforge", "forge", "liteloader"};
        int found = 0;
        for (int i = 0; i < keys.length; i++) {
            if (!hasText(deps, keys[i])) continue;
            if (found == 0) {
                info.loader = loaders[i];
                info.loaderVersion = getStr(deps, keys[i]);
            }
            found++;
        }
        if (found == 0) {
            info.loader = "vanilla";
            info.loaderVersion = "";
            return;
        }
        if (found > 1) {
            info.warnings.add("В сборке указано несколько загрузчиков — выбран " + info.loaderLabel() + ".");
        }
        if ("quilt".equals(info.loader) || "liteloader".equals(info.loader)) {
            info.loaderSupported = false;
            info.warnings.add("Сборке нужен " + info.loaderLabel()
                    + " — лаунчер пока не умеет его ставить. Моды установятся, но загрузчик "
                    + "придётся поставить отдельно, иначе игра не запустится.");
        }
    }

    private static List<PackFile> parseFiles(JsonObject index) {
        List<PackFile> result = new ArrayList<>();
        if (!index.has("files") || !index.get("files").isJsonArray()) return result;
        JsonArray files = index.getAsJsonArray("files");
        for (JsonElement el : files) {
            if (!el.isJsonObject()) continue;
            JsonObject f = el.getAsJsonObject();
            String path = getStr(f, "path");
            if (path.isBlank()) continue;
            if (isServerOnly(f)) continue;

            PackFile file = new PackFile();
            file.path = path;
            if (f.has("downloads") && f.get("downloads").isJsonArray()) {
                for (JsonElement d : f.getAsJsonArray("downloads")) {
                    if (d.isJsonPrimitive()) file.urls.add(d.getAsString());
                }
            }
            if (f.has("hashes") && f.get("hashes").isJsonObject()) {
                JsonObject hashes = f.getAsJsonObject("hashes");
                file.sha512 = getStr(hashes, "sha512");
                file.sha1 = getStr(hashes, "sha1");
            }
            file.size = getLong(f, "fileSize");
            result.add(file);
        }
        return result;
    }

    /**
     * Fills in the numbers the summary screen shows. Counted straight from the manifest rather
     * than from {@link #parseFiles}'s result, because the interesting totals include the entries
     * that parseFiles deliberately drops (server-only mods, files with no download URL).
     */
    private static void fillCounts(JsonObject index, PackInfo info) {
        if (!index.has("files") || !index.get("files").isJsonArray()) return;
        JsonArray files = index.getAsJsonArray("files");
        for (JsonElement el : files) {
            if (!el.isJsonObject()) continue;
            JsonObject f = el.getAsJsonObject();
            String path = getStr(f, "path");
            if (path.isBlank()) continue;
            if (isServerOnly(f)) {
                info.serverOnlyCount++;
                continue;
            }
            info.fileCount++;
            info.downloadBytes += getLong(f, "fileSize");
            if (path.replace('\\', '/').startsWith("mods/")) info.modCount++;

            boolean hasUrl = f.has("downloads") && f.get("downloads").isJsonArray()
                    && !f.getAsJsonArray("downloads").isEmpty();
            if (!hasUrl) info.undownloadableCount++;
        }
        if (info.undownloadableCount > 0) {
            info.warnings.add(info.undownloadableCount + " файл(ов) сборка не разрешает скачивать "
                    + "напрямую — их придётся добавить вручную.");
        }
    }

    /** Counts what the summary screen needs to know about {@code overrides/}. */
    private static void countOverrides(ZipFile zip, String root, PackInfo info) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            if (overrideRelativePath(entry.getName(), root) != null) info.overrideCount++;
        }
    }

    /** Copies {@code overrides/} and {@code client-overrides/} into the instance.
     *  {@code server-overrides/} is deliberately ignored - it belongs on a dedicated server. */
    private int applyOverrides(Path mrpack, String root, String profileName, InstallReport report)
            throws IOException {
        int applied = 0;
        try (ZipFile zip = openZip(mrpack)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                String rel = overrideRelativePath(entry.getName(), root);
                if (rel == null) continue;
                Path dest;
                try {
                    dest = targetPath(profileName, rel);
                } catch (IOException e) {
                    report.failures.put(entry.getName(), e.getMessage());
                    continue;
                }
                Files.createDirectories(dest.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                applied++;
            }
        }
        return applied;
    }

    /** Path of an archive entry relative to the override tree it lives in, or null when it is not
     *  part of one (including server-overrides/, which is never installed client-side). */
    private static String overrideRelativePath(String entryName, String root) {
        String name = entryName;
        if (!root.isEmpty()) {
            if (!name.startsWith(root)) return null;
            name = name.substring(root.length());
        }
        for (String dir : new String[]{"overrides/", "client-overrides/"}) {
            if (name.startsWith(dir)) {
                String rel = name.substring(dir.length());
                return rel.isBlank() ? null : rel;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------
    // Downloading
    // ---------------------------------------------------------------

    /** Downloads one manifest entry, trying each mirror in turn; null on success, else the reason. */
    private static String downloadTo(Path dest, PackFile file, Http.Progress progress) {
        if (file.urls.isEmpty()) return "в сборке нет ссылки на скачивание";
        StringBuilder reasons = new StringBuilder();
        for (String url : file.urls) {
            try {
                Files.createDirectories(dest.getParent());
                long written = Http.download(url, dest, HEADERS, Http.DEFAULT_TIMEOUT, progress);
                if (written < 0) {
                    reasons.append("HTTP-ошибка; ");
                    continue;
                }
                if (!matches(dest, file)) {
                    // A truncated or tampered file is worse than a missing one: the game would
                    // load a half-jar and crash somewhere unrelated to the modpack.
                    deleteQuietly(dest);
                    reasons.append("не совпала контрольная сумма; ");
                    continue;
                }
                return null;
            } catch (IOException e) {
                reasons.append(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())
                        .append("; ");
                deleteQuietly(dest);
            }
        }
        String text = reasons.toString().trim();
        return text.isEmpty() ? "не удалось скачать" : text;
    }

    /** True when an already-present file still matches the manifest (size and, when given, hash).
     *  With no hash at all this degenerates to "the file is there", which is what a re-install of
     *  a pack wants anyway. */
    private static boolean matches(Path file, PackFile expected) throws IOException {
        if (!Files.isRegularFile(file)) return false;
        if (expected.size > 0 && Files.size(file) != expected.size) return false;
        if (expected.sha512 != null && !expected.sha512.isBlank()) {
            return expected.sha512.equalsIgnoreCase(digest(file, "SHA-512"));
        }
        if (expected.sha1 != null && !expected.sha1.isBlank()) {
            return expected.sha1.equalsIgnoreCase(digest(file, "SHA-1"));
        }
        return true;
    }

    private static String digest(Path file, String algorithm) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            }
            StringBuilder hex = new StringBuilder(md.getDigestLength() * 2);
            for (byte b : md.digest()) hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-512/SHA-1 are mandatory in every Java platform implementation.
            throw new IOException("Хеш " + algorithm + " недоступен в этой JVM", e);
        }
    }

    // ---------------------------------------------------------------
    // Paths
    // ---------------------------------------------------------------

    /**
     * Maps a path from inside the pack ({@code "mods/sodium.jar"}, {@code "config/x.toml"},
     * {@code "options.txt"}) to where BLauncher keeps it. Content the launcher isolates per build
     * goes into that build's subfolder, everything else into the shared game directory the pack
     * expects - the same layout the Modrinth-installed mods have always used, so
     * GameLaunchService's sync at launch picks the build's files up unchanged.
     *
     * <p>Also the single place that refuses a path trying to escape the game directory. A pack is
     * untrusted input: {@code "../../AppData/..."} or {@code "C:/Windows/..."} inside the
     * manifest must never be followed, whichever mirror it came from.
     */
    private Path targetPath(String profileName, String relPath) throws IOException {
        String norm = relPath.replace('\\', '/').trim();
        if (norm.isEmpty()) throw new IOException("Пустой путь в сборке.");
        if (norm.startsWith("/") || norm.contains(":") || norm.startsWith("~")) {
            throw new IOException("Недопустимый путь в сборке: " + relPath);
        }
        for (String segment : norm.split("/")) {
            if (segment.equals("..") || segment.equals(".") || segment.isEmpty()) {
                throw new IOException("Недопустимый путь в сборке: " + relPath);
            }
        }

        Path dest;
        if (norm.startsWith("shaderpacks/")) {
            // Shaders are stashed out of the live folder - see ModrinthService.SHADER_STORAGE_DIR.
            dest = gameDir.resolve(ModrinthService.SHADER_STORAGE_DIR).resolve(profileName)
                    .resolve(norm.substring("shaderpacks/".length()));
        } else {
            dest = null;
            for (String type : PER_BUILD_DIRS) {
                if (norm.startsWith(type + "/")) {
                    dest = gameDir.resolve(type).resolve(profileName)
                            .resolve(norm.substring(type.length() + 1));
                    break;
                }
            }
            if (dest == null) dest = gameDir.resolve(norm);
        }

        Path normalized = dest.normalize();
        if (!normalized.startsWith(gameDir)) {
            throw new IOException("Путь в сборке ведёт за пределы папки игры: " + relPath);
        }
        return normalized;
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** Records which Modrinth project a pack's mod file came from, for {@code mods/} files only -
     *  the Mod Manager's installed list is the one place that shows project identity. */
    private static void rememberModProject(InstallReport report, PackFile file) {
        String norm = file.path.replace('\\', '/');
        if (!norm.startsWith("mods/")) return;
        int slash = norm.lastIndexOf('/');
        String fileName = slash < 0 ? norm : norm.substring(slash + 1);
        if (fileName.isBlank()) return;
        String projectId = projectIdFromUrl(file.urls.isEmpty() ? null : file.urls.get(0));
        if (projectId != null) report.modProjectIds.put(fileName, projectId);
    }

    /** Modrinth's CDN URLs are {@code https://cdn.modrinth.com/data/<project>/versions/<ver>/<file>};
     *  anything else (a GitHub mirror, a pack author's own host) simply has no project id to give. */
    private static String projectIdFromUrl(String url) {
        if (url == null) return null;
        int marker = url.indexOf("/data/");
        if (marker < 0) return null;
        int start = marker + "/data/".length();
        int end = url.indexOf('/', start);
        if (end <= start) return null;
        String id = url.substring(start, end).trim();
        return id.isEmpty() ? null : id;
    }

    private static boolean isServerOnly(JsonObject file) {
        if (!file.has("env") || !file.get("env").isJsonObject()) return false;
        JsonObject env = file.getAsJsonObject("env");
        if (!hasText(env, "client")) return false;
        return "unsupported".equalsIgnoreCase(getStr(env, "client"));
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > MAX_INDEX_BYTES) {
                throw new IOException(INDEX_NAME + " подозрительно большой (> "
                        + (MAX_INDEX_BYTES / (1024 * 1024)) + " МБ) — архив повреждён?");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Установка сборки прервана.");
        }
    }

    private static String getStr(JsonObject obj, String key) {
        return hasText(obj, key) ? obj.get(key).getAsString() : "";
    }

    private static boolean hasText(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) return false;
        JsonElement el = obj.get(key);
        return el.isJsonPrimitive() && !el.getAsString().isBlank();
    }

    private static long getLong(JsonObject obj, String key) {
        try {
            return hasText(obj, key) ? obj.get(key).getAsLong() : 0L;
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    private static String stripExtension(String fileName) {
        if (fileName == null) return "";
        int dot = fileName.toLowerCase().lastIndexOf(".mrpack");
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
            // The file is being replaced anyway; a leftover is cleaned up by the next install.
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(java.util.Locale.ROOT, "%.1f ГБ", bytes / (1024.0 * 1024 * 1024));
        }
        if (bytes >= 1024L * 1024) {
            return String.format(java.util.Locale.ROOT, "%.0f МБ", bytes / (1024.0 * 1024));
        }
        return String.format(java.util.Locale.ROOT, "%.0f КБ", Math.max(1, bytes / 1024.0));
    }
}
