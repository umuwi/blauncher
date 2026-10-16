package com.example.launcher.service;

import com.example.launcher.model.LauncherConfig;
import com.example.launcher.model.Profile;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Builds and launches a Minecraft process with the right JVM args,
 * classpath, and mod loader main class for the selected profile.
 */
public class GameLaunchService {
    private final Path gameDir;
    private final Path versionsDir;
    private final Path librariesDir;
    private final Path assetsDir;
    private final ModLoaderService modLoaderService;
    private final VersionDownloadService versionDownloadService;
    private final LauncherConfig config;

    private AuthService authService;

    // Several Minecraft instances can run at the same time (two accounts, a different group, or
    // just the same one twice for local multiplayer) - every live process is tracked here so a
    // second launch never overwrites the handle of the first, and isGameRunning()/stopGame()
    // cover all of them rather than only the most recent.
    private final java.util.List<Process> gameProcesses =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public GameLaunchService(Path gameDir, ModLoaderService modLoaderService,
                             VersionDownloadService versionDownloadService, LauncherConfig config) {
        this.gameDir = gameDir;
        this.versionsDir = gameDir.resolve("versions");
        this.librariesDir = gameDir.resolve("libraries");
        this.assetsDir = gameDir.resolve("assets");
        this.modLoaderService = modLoaderService;
        this.versionDownloadService = versionDownloadService;
        this.config = config;
    }

    public void launchGame(Profile profile, String versionId, GameOutputCallback callback) throws Exception {
        launchGame(profile, versionId, callback, null, null, null);
    }

    /** {@code onExit} fires with the process exit code once the game ends - lets the caller run
     *  crash analysis (e.g. detecting a Fabric/Forge mod-conflict) on the captured output rather
     *  than just logging "exit code N" and leaving the player staring at a wall of stack trace. */
    public void launchGame(Profile profile, String versionId, GameOutputCallback callback, IntConsumer onExit) throws Exception {
        launchGame(profile, versionId, callback, onExit, null, null);
    }

    /** Same as {@link #launchGame(Profile, String, GameOutputCallback, IntConsumer)}, but also passes
     *  local skin/cape URLs to Minecraft via --userProperties so offline profiles can use uploaded
     *  skin/cape files. The URLs should point to a local HTTP server (see LocalSkinServer). */
    public void launchGame(Profile profile, String versionId, GameOutputCallback callback, IntConsumer onExit,
                           String skinUrl, String capeUrl) throws Exception {
        String launchVersion = getLaunchVersionId(versionId, profile);

        Path launchJar = versionsDir.resolve(launchVersion).resolve(launchVersion + ".jar");
        if (!Files.exists(launchJar)) {
            Path baseJar = versionsDir.resolve(versionId).resolve(versionId + ".jar");
            if (!Files.exists(baseJar)) {
                throw new IOException("Version not installed: " + launchVersion + " (also tried " + versionId + ")");
            }
        }

        ensureInheritedVersionsDownloaded(launchVersion, callback);

        callback.onOutput("[LAUNCH] Building classpath for " + launchVersion + "...");
        String classpath = buildClasspath(versionId, profile);
        int cpEntries = classpath.split(System.getProperty("os.name", "").toLowerCase().contains("win") ? ";" : ":").length;
        callback.onOutput("[LAUNCH] Classpath entries: " + cpEntries);

        // Unique per launch (not just per version): two instances of the SAME version running at
        // once would otherwise share one natives/<version>/ dir, and the second launch's extract
        // would clobber/relock the .dll natives the first instance already has memory-mapped.
        // Cleaned up when this instance's process exits (see the exit thread below).
        Path nativesDir = gameDir.resolve("natives").resolve(launchVersion + "-" + System.nanoTime());
        extractNatives(classpath, nativesDir, callback);

        callback.onOutput("[LAUNCH] Resolving main class for " + profile.getModLoader() + "...");
        String mainClass = modLoaderService.resolveMainClass(launchVersion, profile.getModLoader(), profile.getModLoaderVersion());
        callback.onOutput("[LAUNCH] Main class: " + mainClass);

        String assetIndex = versionDownloadService.getAssetIndexId(launchVersion);
        if (assetIndex.equals(launchVersion) && !launchVersion.equals(versionId)) {
            assetIndex = versionDownloadService.getAssetIndexId(versionId);
        }
        callback.onOutput("[LAUNCH] Asset index: " + assetIndex);

        // Старые версии (1.6.x и ниже) ищут ассеты по именам, а не по хешам. Раскладка идёт и
        // здесь, а не только при установке: версия могла быть установлена до появления этого
        // кода, и тогда resources/ пуст. Копируется только расхождение, так что второй запуск
        // ничего не делает. См. LegacyAssets.
        try {
            int copiedAssets = LegacyAssets.materialise(gameDir, assetsDir, assetIndex);
            if (copiedAssets > 0) {
                callback.onOutput("[ASSETS] Разложено " + copiedAssets + " ресурсов для старой версии");
            }
        } catch (Exception e) {
            callback.onOutput("[ASSETS] Не удалось разложить ресурсы: " + e.getMessage());
        }

        callback.onOutput("[LAUNCH] Building JVM arguments...");
        List<String> command = buildCommand(classpath, launchVersion, profile, mainClass, assetIndex, nativesDir,
                skinUrl, capeUrl);

        syncMods(profile);
        syncGroupContentAdditive(profile, "resourcepacks");
        syncShaderpacks(profile);
        syncDatapacks(profile);

        callback.onOutput("[LAUNCH] Starting Minecraft...");
        callback.onOutput("[LAUNCH] Java executable: " + command.get(0));

        // A loader's own JVM args (e.g. NeoForge's -p <module path>, listing half a dozen
        // jars) on top of an already-large mod-loader classpath (100+ libraries) can push the
        // total command line past Windows' ~32K CreateProcess limit - failing with error 206
        // ("filename or extension is too long") even though every individual path is short.
        // Java's own @argfile mechanism (JEP 343) sidesteps the OS limit by reading arguments
        // from a file instead of the command line - but it's a Java 9+ feature. Old Minecraft
        // versions (<= 1.16) need Java 8, whose launcher has no idea what "@file" means and
        // just tries to load it as a literal main class name ("Could not find or load main
        // class @C:\...\args.txt"). Only use the argfile when the resolved java is 9+; old
        // versions' classpaths are far too small to ever hit the command-line limit anyway.
        Path argFile = null;
        ProcessBuilder pb;
        if (new JavaVersionDetector().getMajorVersion(command.get(0)) >= 9) {
            argFile = writeArgFile(command, callback);
            pb = new ProcessBuilder(command.get(0), "@" + argFile.toAbsolutePath());
        } else {
            pb = new ProcessBuilder(command);
        }
        pb.directory(gameDir.toFile());
        pb.redirectErrorStream(true);

        // Persist this launch (full command + every output line, stderr included via
        // redirectErrorStream above) to its own file. The in-app console is the only other place
        // this output goes, and it dies with the launcher window - so whenever a launch failed,
        // the one thing that said why (a stack trace, an UnsatisfiedLinkError, a missing library)
        // was already gone by the time anyone could report it. The game's own logs/latest.log is
        // no substitute: it stops at whatever line preceded the failure, because the failure
        // itself is written to stderr, not to log4j.
        final Path launchLog = createLaunchLog(launchVersion, command, callback);

        final Process proc = pb.start();
        gameProcesses.add(proc);
        final Path argFileToClean = argFile;
        final Path nativesToClean = nativesDir;

        Thread outputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                 BufferedWriter logWriter = launchLog == null ? null
                         : Files.newBufferedWriter(launchLog, StandardCharsets.UTF_8, StandardOpenOption.APPEND)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    callback.onOutput(line);
                    if (logWriter != null) {
                        logWriter.write(line);
                        logWriter.newLine();
                        // Flushed per line on purpose: a hard JVM crash (the case this log exists
                        // for) never unwinds to the close() below, and a buffered tail would take
                        // the most interesting lines with it.
                        logWriter.flush();
                    }
                }
            } catch (IOException e) {
                callback.onError("Error reading game output: " + e.getMessage());
            }
        });

        outputThread.setDaemon(true);
        outputThread.start();

        new Thread(() -> {
            int exitCode = -1;
            try {
                exitCode = proc.waitFor();
                callback.onOutput("\n[LAUNCH] Game closed with exit code: " + exitCode);
            } catch (InterruptedException e) {
                callback.onError("Game process interrupted: " + e.getMessage());
            } finally {
                gameProcesses.remove(proc);
                if (launchLog != null) {
                    try {
                        Files.writeString(launchLog, "\n[BLAUNCHER] Game exited with code " + exitCode + "\n",
                                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                    } catch (IOException ignored) {}
                }
                if (argFileToClean != null) {
                    try { Files.deleteIfExists(argFileToClean); } catch (IOException ignored) {}
                }
                // This instance's private natives dir is no longer needed once it has exited.
                try { deleteRecursively(nativesToClean); } catch (Exception ignored) {}
                if (onExit != null) onExit.accept(exitCode);
            }
        }).start();
    }

    /** How many per-launch logs to keep around before the oldest ones are pruned. */
    private static final int MAX_LAUNCH_LOGS = 20;

    /**
     * Creates logs/blauncher-launch-&lt;version&gt;-&lt;timestamp&gt;.log and writes the exact command
     * this launch is about to run into it. Returns null (and never throws) if the file can't be
     * created - a missing diagnostic log must never be the reason a launch fails.
     */
    private Path createLaunchLog(String launchVersion, List<String> command, GameOutputCallback callback) {
        try {
            Path logsDir = gameDir.resolve("logs");
            Files.createDirectories(logsDir);
            pruneLaunchLogs(logsDir);

            String stamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            Path file = logsDir.resolve("blauncher-launch-" + launchVersion.replaceAll("[^\\w.-]", "_")
                    + "-" + stamp + ".log");

            StringBuilder header = new StringBuilder();
            header.append("=== BLauncher launch ").append(stamp).append(" ===\n");
            header.append("Version: ").append(launchVersion).append('\n');
            header.append("Command:\n");
            // The command carries the live Minecraft/MSA access token (--accessToken, and the
            // "token:<...>" session string old versions take). This file exists to be *shared*
            // when reporting a broken launch, so the credential never goes in it.
            boolean redactNext = false;
            for (String token : command) {
                String shown = token;
                if (redactNext) {
                    shown = "<redacted>";
                } else if (token.startsWith("token:")) {
                    shown = "token:<redacted>";
                }
                redactNext = "--accessToken".equals(token) || "--session".equals(token);
                header.append("  ").append(shown).append('\n');
            }
            header.append("=== game output (stdout + stderr) ===\n");
            Files.writeString(file, header.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            callback.onOutput("[LAUNCH] Лог запуска: " + file.toAbsolutePath());
            return file;
        } catch (Exception e) {
            callback.onOutput("[LAUNCH] Не удалось создать лог запуска: " + e.getMessage());
            return null;
        }
    }

    /** Keeps only the newest MAX_LAUNCH_LOGS - 1 launch logs, so they can't pile up forever. */
    private void pruneLaunchLogs(Path logsDir) {
        try (Stream<Path> files = Files.list(logsDir)) {
            List<Path> existing = files
                    .filter(p -> p.getFileName().toString().startsWith("blauncher-launch-"))
                    .sorted(Comparator.comparing(p -> {
                        try { return Files.getLastModifiedTime(p); }
                        catch (IOException e) { return java.nio.file.attribute.FileTime.fromMillis(0); }
                    }))
                    .collect(Collectors.toList());
            for (int i = 0; i < existing.size() - (MAX_LAUNCH_LOGS - 1); i++) {
                try { Files.deleteIfExists(existing.get(i)); } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
    }

    /** Writes every command token (except the java executable itself) to a temp @argfile, quoting
     *  each per JEP 343 so paths with spaces survive - see launchGame() for why this is needed. */
    private Path writeArgFile(List<String> command, GameOutputCallback callback) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < command.size(); i++) {
            // Backslash is the argfile escape character (\n, \t, \\, \", ...), and Windows
            // paths/module-paths are full of literal backslashes - escape those FIRST (\ -> \\)
            // before escaping embedded quotes, or every "C:\Users\..." path gets its
            // backslashes silently eaten by the parser, breaking the module-path it's part of.
            String escaped = command.get(i).replace("\\", "\\\\").replace("\"", "\\\"");
            sb.append('"').append(escaped).append("\"\n");
        }
        // java.exe's native launcher reads @argfiles using the platform's ANSI codepage
        // (e.g. Windows-1251), not UTF-8 - confirmed by reproducing "ClassNotFoundException:
        // net.minecraft.client.main.Main" with a real Cyrillic install path (e.g. a folder
        // named "Новая папка"). Forcing UTF-8 here corrupts any non-ASCII byte of the path
        // once the launcher decodes it back with the wrong codepage, silently breaking that
        // classpath entry.
        //
        // 11.10.2026: здесь стояло Charset.defaultCharset(), но с Java 18 (JEP 400) это всегда
        // UTF-8, а не кодовая страница Windows, — то есть ровно та ошибка, от которой предупреждает
        // абзац выше, вернулась. Игрок abb67a0d нажал «Играть» 19 раз: Fabric 26.3 и 26.4 каждый
        // раз закрывались через секунду с кодом 1. Воспроизведено: лаунчер в папке «Тест игра» —
        // «Could not find or load main class ...KnotClient», в латинской папке — запускается.
        // native.encoding — настоящая ANSI-страница системы (Cp1251 на русской Windows).
        Charset ansi = nativeCharset();
        if (!ansi.newEncoder().canEncode(sb)) {
            // Символы вне кодовой страницы (например, китайское имя пользователя на русской
            // Windows) java.exe из @-файла не прочтёт ни в какой кодировке — пишем как есть,
            // но причина хотя бы будет в логе запуска.
            callback.onOutput("[LAUNCH] В путях есть символы вне кодировки " + ansi.name()
                    + " — Java может не найти файлы игры. Перенесите лаунчер в папку с латинским именем.");
        }
        Path argFile = Files.createTempFile("blauncher-args-", ".txt");
        Files.writeString(argFile, sb.toString(), ansi);
        return argFile;
    }

    /**
     * Разбирает строку своих аргументов Java из настроек: разделитель — пробелы, значение с
     * пробелами можно взять в двойные кавычки ({@code -Dfoo="a b"}). Пропускается всё, что не
     * начинается с «-» (такой токен JVM приняла бы за главный класс и игра не запустилась бы),
     * и то, что ломает запуск лаунчера: свой classpath, -jar, -version/-help.
     */
    public static List<String> parseJvmArgs(String line) {
        List<String> out = splitJvmArgs(line);
        out.removeIf(a -> !a.startsWith("-")
                || a.equals("-cp") || a.equals("-classpath") || a.startsWith("--class-path")
                || a.equals("-jar") || a.equals("-version") || a.equals("--version")
                || a.equals("-help") || a.equals("--help") || a.equals("-?"));
        return out;
    }

    /** Та же строка, разбитая на аргументы, но без отбора — чтобы показать, что пропущено. */
    public static List<String> splitJvmArgs(String line) {
        List<String> out = new ArrayList<>();
        if (line == null || line.isBlank()) return out;
        StringBuilder cur = new StringBuilder();
        boolean quoted = false, has = false;
        for (int i = 0; i <= line.length(); i++) {
            char c = i < line.length() ? line.charAt(i) : ' ';
            if (c == '"') { quoted = !quoted; has = true; continue; }
            if (Character.isWhitespace(c) && !quoted) {
                if (has) out.add(cur.toString());
                cur.setLength(0);
                has = false;
                continue;
            }
            cur.append(c);
            has = true;
        }
        return out;
    }

    /** Кодовая страница ANSI, в которой java.exe читает @-файл аргументов. */
    static Charset nativeCharset() {
        try {
            String name = System.getProperty("native.encoding");
            if (name != null && !name.isBlank()) return Charset.forName(name);
        } catch (Exception ignored) {
            // неизвестное имя кодировки — ниже запасной вариант
        }
        return Charset.defaultCharset();
    }

    /**
     * Mod loader versions can target a different (and possibly never-downloaded) vanilla
     * version than whatever was selected in the dropdown - e.g. NeoForge "21.11.42" actually
     * inheritsFrom vanilla "1.21.11", not the "1.21.1" string the user had picked. The loader's
     * own installer only fetches loader-specific libraries (FML, ASM, ...), not the full
     * vanilla library set, so without this step a missing inherited library (log4j, etc.)
     * fails the launch with NoClassDefFoundError instead of ever being downloaded. Walk
     * inheritsFrom past the loader's own (synthetic, never downloadable on its own) entry and
     * make sure every real vanilla ancestor in the chain is actually fully present.
     */
    private void ensureInheritedVersionsDownloaded(String versionId, GameOutputCallback callback) {
        Path topJson = versionsDir.resolve(versionId).resolve(versionId + ".json");
        if (!Files.exists(topJson)) return;

        String current;
        try {
            JsonObject obj = JsonParser.parseString(Files.readString(topJson)).getAsJsonObject();
            current = obj.has("inheritsFrom") ? obj.get("inheritsFrom").getAsString() : null;
        } catch (Exception ex) {
            return;
        }

        Set<String> visited = new HashSet<>();
        visited.add(versionId);
        while (current != null && visited.add(current)) {
            Path json = versionsDir.resolve(current).resolve(current + ".json");
            if (!Files.exists(json)) break;

            try {
                if (!isVanillaLibrarySetComplete(current, json)) {
                    callback.onOutput("[LAUNCH] Downloading missing libraries for " + current + "...");
                    versionDownloadService.downloadVersion(current);
                }
            } catch (Exception ex) {
                callback.onOutput("[WARN] Could not verify/download libraries for " + current + ": " + ex.getMessage());
            }

            try {
                JsonObject obj = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
                current = obj.has("inheritsFrom") ? obj.get("inheritsFrom").getAsString() : null;
            } catch (Exception ex) {
                current = null;
            }
        }
    }

    private boolean isVanillaLibrarySetComplete(String versionId, Path json) throws IOException {
        if (!Files.exists(versionsDir.resolve(versionId).resolve(versionId + ".jar"))) return false;

        JsonObject obj = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
        if (!obj.has("libraries")) return true;

        for (var el : obj.getAsJsonArray("libraries")) {
            if (!el.isJsonObject()) continue;
            JsonObject lib = el.getAsJsonObject();
            if (!libraryAppliesToCurrentOS(lib)) continue;
            if (LibraryRules.isForeignNative(lib, LibraryRules.nativeClassifier())) continue;
            if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("artifact")) {
                JsonObject artifact = lib.getAsJsonObject("downloads").getAsJsonObject("artifact");
                // Не просто «есть»: обрезанная библиотека тоже должна считаться неустановленной,
                // иначе её никто не перекачает (см. VersionDownloadService.libraryFileOk).
                if (!VersionDownloadService.libraryFileOk(librariesDir.resolve(artifact.get("path").getAsString()), artifact)) return false;
            }
        }
        return true;
    }

    private String buildClasspath(String baseVersionId, Profile profile) throws IOException {
        String launchVersion = getLaunchVersionId(baseVersionId, profile);
        Path versionJsonPath = versionsDir.resolve(launchVersion).resolve(launchVersion + ".json");

        if (!Files.exists(versionJsonPath)) {
            versionJsonPath = versionsDir.resolve(baseVersionId).resolve(baseVersionId + ".json");
        }
        if (!Files.exists(versionJsonPath)) {
            throw new IOException("Version JSON not found: " + versionJsonPath);
        }

        // group:artifact -> resolved jar path, not just a List<String> - see putLibrary()'s
        // javadoc for why: the same artifact can legitimately show up twice at two DIFFERENT
        // versions (e.g. vanilla's own version.json pins an older org.ow2.asm:asm than the
        // Fabric loader profile does), which a plain "is this exact path already in the list"
        // check never catches since the two versions resolve to two different jar paths. Fabric's
        // own Knot loader hard-fails at startup the moment it sees two ASM versions on the
        // classpath at once ("duplicate ASM classes found on classpath") - a later put() for the
        // same key overwrites the earlier one instead of adding a second entry.
        LinkedHashMap<String, String> classpath = new LinkedHashMap<>();

        Path versionDir = versionsDir.resolve(launchVersion);
        Path clientJar = versionDir.resolve(launchVersion + ".jar");
        if (Files.exists(clientJar)) {
            classpath.put(clientJar.toString(), clientJar.toString());
        } else if ((!"forge".equals(profile.getModLoader()) && !"neoforge".equals(profile.getModLoader()))
                || usesLaunchWrapper(versionJsonPath)) {
            // Старый Forge (1.12.2 и раньше, запуск через LaunchWrapper) — наоборот: своего
            // пропатченного jar у него нет, он патчит классы прямо из ванильного на classpath.
            // Без него 14.10.2026 у нового игрока Forge 1.12.2 падал через секунду с «Patcher
            // expecting non-empty class data file for ...EntityOcelot ... your vanilla jar may be
            // corrupt» (код 1) — воспроизведено: jar был целым, его просто не было в classpath.
            // Forge/NeoForge never write their own copy of the client jar under their version
            // folder - their runtime (FML's "production client provider") locates their own
            // patched client jar itself at startup. Falling back to the raw vanilla jar here
            // would put two copies of net.minecraft.* on the module path - both claiming to
            // export the same packages - and crash module resolution
            // ("Modules ... and minecraft export package net.minecraft.server").
            Path baseJar = versionsDir.resolve(baseVersionId).resolve(baseVersionId + ".jar");
            if (Files.exists(baseJar)) {
                classpath.put(baseJar.toString(), baseJar.toString());
            }
        }

        collectLibrariesFromVersion(versionJsonPath, classpath, new java.util.HashSet<>());

        for (Path extra : modLoaderService.getExtraClasspath(baseVersionId, profile.getModLoader(), profile.getModLoaderVersion())) {
            classpath.putIfAbsent(extra.toString(), extra.toString());
        }

        if ("fabric".equals(profile.getModLoader())) {
            Path fabricProfileJson = librariesDir.resolve("fabric").resolve("fabric-" + baseVersionId + ".json");
            if (Files.exists(fabricProfileJson)) {
                JsonObject fabricProfile = JsonParser.parseString(Files.readString(fabricProfileJson)).getAsJsonObject();
                if (fabricProfile.has("libraries")) {
                    // Added AFTER the vanilla/inherited libraries above, so its own required
                    // versions (like that newer ASM) are the ones that win any putLibrary()
                    // overwrite - Fabric picked these versions deliberately for its own loader,
                    // vanilla's copy was never anything more than incidental.
                    addLibrariesToClasspath(fabricProfile.getAsJsonArray("libraries"), classpath);
                }
            }
        }

        String sep = System.getProperty("os.name", "").toLowerCase().contains("win") ? ";" : ":";
        return String.join(sep, classpath.values());
    }

    /** Версия запускается через LaunchWrapper (Forge до 1.13): mainClass в её json или в родителях. */
    private boolean usesLaunchWrapper(Path jsonPath) {
        java.util.Set<String> visited = new java.util.HashSet<>();
        Path current = jsonPath;
        while (current != null && Files.exists(current) && visited.add(current.toString())) {
            try {
                JsonObject json = JsonParser.parseString(Files.readString(current)).getAsJsonObject();
                if (json.has("mainClass")) {
                    return "net.minecraft.launchwrapper.Launch".equals(json.get("mainClass").getAsString());
                }
                if (!json.has("inheritsFrom")) return false;
                String parentId = json.get("inheritsFrom").getAsString();
                current = versionsDir.resolve(parentId).resolve(parentId + ".json");
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private void collectLibrariesFromVersion(Path jsonPath, LinkedHashMap<String, String> classpath,
                                              java.util.Set<String> visited) throws IOException {
        String key = jsonPath.toAbsolutePath().normalize().toString();
        if (!visited.add(key)) return;

        if (!Files.exists(jsonPath)) return;

        JsonObject json = JsonParser.parseString(Files.readString(jsonPath)).getAsJsonObject();

        if (json.has("libraries")) {
            // Первый уровень (сама версия загрузчика) кладётся как есть; родители — только то, чего
            // ещё нет. Раньше родитель обходился ПОСЛЕ ребёнка и по правилу «последний побеждает»
            // затирал его: 14.10.2026 Forge 1.16.5 (36.2.42) получил log4j 2.8.1 от ванильной
            // 1.16.5 вместо своих 2.15.0 и падал на старте с NoSuchMethodError
            // ThrowablePatternConverter. Версия загрузчика главнее ванильной — как и у Mojang.
            addLibrariesToClasspath(json.getAsJsonArray("libraries"), classpath, visited.size() == 1);
        }

        if (json.has("inheritsFrom")) {
            String parentId = json.get("inheritsFrom").getAsString();
            Path parentJson = versionsDir.resolve(parentId).resolve(parentId + ".json");
            collectLibrariesFromVersion(parentJson, classpath, visited);
        }
    }

    /** "group:artifact[:classifier]" from a Maven-style library name
     *  ("group:artifact:version[:classifier]"), or null if {@code name} doesn't look like one
     *  (falls back to path-based keying only). The classifier (4th segment, e.g.
     *  "natives-windows") is deliberately part of the key, NOT excluded like the version is:
     *  Mojang's newer library format gives each per-OS native its own top-level entry named
     *  "group:artifact:version:natives-<os>" rather than nesting them under one entry's
     *  "downloads.classifiers", so a plain "group:artifact" key would collide the base
     *  cross-platform class jar (no classifier) with its own native-only sibling and whichever
     *  was processed last would silently overwrite the other in the classpath map - which is
     *  exactly what broke com.mojang:jtracy here: the natives-windows entry replaced the base
     *  jtracy-*.jar (holding the actual TracyClient class) that RenderSystem needs at startup.
     *  Only the version (3rd segment) is excluded - that's the part two real duplicate-version
     *  conflicts (the original bug this whole key scheme exists for) differ by. */
    private static String artifactKey(String name) {
        if (name == null) return null;
        String[] parts = name.split(":");
        if (parts.length < 2) return null;
        String key = parts[0] + ":" + parts[1];
        if (parts.length >= 4) key += ":" + parts[3];
        return key;
    }

    /** Adds one resolved library jar to the classpath map, keyed by its Maven group:artifact
     *  when known so a later call for the same artifact at a different version replaces this
     *  one instead of sitting alongside it - see buildClasspath()'s own javadoc for why that
     *  matters. Falls back to keying by the path itself (plain existence dedup, same as before)
     *  when there's no parseable name, e.g. the native-classifier branch below. */
    private void putLibrary(LinkedHashMap<String, String> classpath, String name, Path libPath) {
        if (libPath == null || !Files.exists(libPath)) return;
        String key = artifactKey(name);
        classpath.put(key != null ? key : libPath.toString(), libPath.toString());
    }

    private void addLibrariesToClasspath(JsonArray libraries, LinkedHashMap<String, String> classpath) {
        addLibrariesToClasspath(libraries, classpath, true);
    }

    /** @param override false — не заменять уже добавленную библиотеку того же group:artifact
     *                  (для родительских версий, см. collectLibrariesFromVersion). */
    private void addLibrariesToClasspath(JsonArray libraries, LinkedHashMap<String, String> classpath, boolean override) {
        String nativeClassifier = getNativeClassifier();
        for (var el : libraries) {
            if (!el.isJsonObject()) continue;
            JsonObject lib = el.getAsJsonObject();

            if (!libraryAppliesToCurrentOS(lib)) continue;
            // natives-windows-x86 и natives-windows-arm64 правила пропускают (их "os" — только
            // name, без arch), а extractNatives всё равно отбрасывает каждый файл из них по папке
            // архитектуры. В класспатхе они были мёртвым грузом и просто удлиняли командную строку.
            if (LibraryRules.isForeignNative(lib, nativeClassifier)) continue;

            String name = lib.has("name") ? lib.get("name").getAsString() : null;
            String key = artifactKey(name);
            if (override || key == null || !classpath.containsKey(key)) {
                putLibrary(classpath, name, resolveLibPath(lib, name));
            }

            if (lib.has("downloads")) {
                JsonObject downloads = lib.getAsJsonObject("downloads");
                if (downloads.has("classifiers")) {
                    JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                    if (classifiers.has(nativeClassifier)) {
                        JsonObject nativeEntry = classifiers.getAsJsonObject(nativeClassifier);
                        String nativePath = nativeEntry.get("path").getAsString();
                        Path nativeLibPath = librariesDir.resolve(nativePath);
                        // Natives are always name+"-natives-<os>" - never the same Maven
                        // coordinate as another regular library, so path-based keying (name is
                        // irrelevant here) is exactly as correct as it was before this method
                        // started caring about artifact identity for the non-native case above.
                        putLibrary(classpath, null, nativeLibPath);
                    }
                }
            }
        }
    }

    /**
     * Reads "arguments.jvm" from the version JSON chain (the launch version, then whatever it
     * inheritsFrom), substitutes the standard placeholders, and returns the flags ready to add
     * to the command line. This is how the official launcher passes loader-required JVM flags
     * (NeoForge/Forge's --add-opens/--add-exports/-p module path) instead of hand-rolling them.
     */
    private List<String> collectJvmArgsFromVersion(String versionId, Path nativesDir, String classpath) throws IOException {
        List<String> raw = new ArrayList<>();
        collectArgsRecursive(versionId, "jvm", raw, new java.util.HashSet<>());

        String sep = isWindows() ? ";" : ":";
        List<String> resolved = new ArrayList<>(raw.size());
        for (String arg : raw) {
            resolved.add(arg
                    .replace("${natives_directory}", nativesDir.toString())
                    .replace("${library_directory}", librariesDir.toString())
                    .replace("${classpath_separator}", sep)
                    .replace("${classpath}", classpath)
                    .replace("${launcher_name}", "BLauncher")
                    .replace("${launcher_version}", "2.0")
                    .replace("${version_name}", versionId));
        }
        return resolved;
    }

    /**
     * Reads "arguments.game" from the version JSON chain - mod loaders add their own required
     * flags here (e.g. NeoForge's --fml.mcVersion/--fml.neoForgeVersion, which FML's startup
     * needs and otherwise NullPointerExceptions on). Placeholders are resolved from the same
     * values buildCommand() already computed for its own --username/--uuid/... flags.
     */
    private List<String> collectGameArgsFromVersion(String versionId, Map<String, String> placeholders) throws IOException {
        List<String> raw = new ArrayList<>();
        collectArgsRecursive(versionId, "game", raw, new java.util.HashSet<>());

        List<String> resolved = new ArrayList<>(raw.size());
        for (String arg : raw) {
            for (var entry : placeholders.entrySet()) {
                arg = arg.replace(entry.getKey(), entry.getValue());
            }
            resolved.add(arg);
        }
        return resolved;
    }

    private void collectArgsRecursive(String versionId, String section, List<String> out, java.util.Set<String> visited) throws IOException {
        if (!visited.add(versionId)) return;
        Path json = versionsDir.resolve(versionId).resolve(versionId + ".json");
        if (!Files.exists(json)) return;

        JsonObject obj = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
        if (obj.has("arguments") && obj.getAsJsonObject("arguments").has(section)) {
            for (var el : obj.getAsJsonObject("arguments").getAsJsonArray(section)) {
                if (el.isJsonPrimitive()) {
                    out.add(el.getAsString());
                } else if (el.isJsonObject()) {
                    JsonObject ruleObj = el.getAsJsonObject();
                    if (!argRuleApplies(ruleObj) || !ruleObj.has("value")) continue;
                    var value = ruleObj.get("value");
                    if (value.isJsonArray()) {
                        for (var v : value.getAsJsonArray()) out.add(v.getAsString());
                    } else {
                        out.add(value.getAsString());
                    }
                }
            }
        }

        if (obj.has("inheritsFrom")) {
            collectArgsRecursive(obj.get("inheritsFrom").getAsString(), section, out, visited);
        }
    }

    /**
     * Совпадает ли блок {@code "os"} правила с текущей машиной — по имени ОС И по разрядности.
     *
     * <p>Раньше читалось только {@code os.name}, а {@code os.arch} игнорировался. Объект правила,
     * у которого есть {@code "os"}, но нет {@code "name"}, попадал в ветку с пустым именем ОС,
     * ни с чем не совпадал и пропускался — то есть оставался запрещённым. Проверено по реальным
     * данным: и {@code 1.21.1.json}, и {@code 26.2.json} содержат
     * {@code {"rules":[{"action":"allow","os":{"arch":"x86"}}],"value":"-Xss1M"}}. На 32-битной
     * JVM этот флаг молча терялся, а Mojang ставит его потому, что дефолтного стека 32-бит не
     * хватает при генерации мира.
     *
     * <p>Пустой блок {@code "os"} (ни name, ни arch) считаем совпадением: правило без условий
     * относится ко всем.
     */
    private static boolean osRuleMatches(JsonObject os, boolean isWindows, boolean isMac, boolean isLinux) {
        return LibraryRules.osMatches(os, isWindows, isMac, isLinux);
    }

    /** Same allow/disallow OS-rule evaluation as libraryAppliesToCurrentOS, plus skipping any
     *  rule gated on a "features" toggle (custom resolution, demo user, ...) we don't support. */
    private static boolean argRuleApplies(JsonObject ruleObj) {
        if (!ruleObj.has("rules")) return true;

        String currentOs = System.getProperty("os.name", "").toLowerCase();
        boolean isMac = currentOs.contains("mac");
        boolean isWindows = currentOs.contains("win");
        boolean isLinux = currentOs.contains("linux") || currentOs.contains("nux");

        boolean allowed = false;
        for (var r : ruleObj.getAsJsonArray("rules")) {
            if (!r.isJsonObject()) continue;
            JsonObject rule = r.getAsJsonObject();
            if (rule.has("features")) continue;
            String action = rule.has("action") ? rule.get("action").getAsString() : "allow";

            if (!rule.has("os")) {
                if ("allow".equals(action)) allowed = true;
                if ("disallow".equals(action)) allowed = false;
            } else {
                JsonObject os = rule.getAsJsonObject("os");
                boolean matches = osRuleMatches(os, isWindows, isMac, isLinux);

                if (matches) {
                    if ("allow".equals(action)) allowed = true;
                    if ("disallow".equals(action)) allowed = false;
                }
            }
        }
        return allowed;
    }

    /**
     * Нужна ли библиотека на нашей ОС. Правила живут в {@link LibraryRules} — одном месте на всех,
     * потому что то же решение принимают {@code VersionDownloadService.downloadLibraries} (что
     * качать) и {@code checkNativesInVersion} (что считать установленным). Разъедься они — и
     * версия либо недокачается, либо будет вечно считаться неполной.
     */
    private static boolean libraryAppliesToCurrentOS(JsonObject lib) {
        return LibraryRules.appliesToCurrentOs(lib);
    }

    private Path resolveLibPath(JsonObject lib, String name) {
        if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("artifact")) {
            JsonObject artifact = lib.getAsJsonObject("downloads").getAsJsonObject("artifact");
            return librariesDir.resolve(artifact.get("path").getAsString());
        }
        if (lib.has("path")) {
            return librariesDir.resolve(lib.get("path").getAsString());
        }
        if (name != null) {
            return mavenNameToPath(name);
        }
        return null;
    }

    private static String getNativeClassifier() {
        return LibraryRules.nativeClassifier();
    }

    private Path mavenNameToPath(String artifact) {
        String[] parts = artifact.split(":");
        if (parts.length < 3) return null;
        String group = parts[0];
        String name = parts[1];
        String version = parts[2];
        String groupPath = group.replace('.', '/');
        String fileName = name + "-" + version + ".jar";
        return librariesDir.resolve(groupPath + "/" + name + "/" + version + "/" + fileName);
    }

    private String getLaunchVersionId(String baseVersionId, Profile profile) {
        if ("forge".equals(profile.getModLoader()) && profile.getModLoaderVersion() != null
                && !profile.getModLoaderVersion().isEmpty()) {
            String combined = baseVersionId + "-forge-" + profile.getModLoaderVersion();
            if (Files.exists(versionsDir.resolve(combined))) return combined;
            String alt = baseVersionId + "-" + profile.getModLoaderVersion();
            if (Files.exists(versionsDir.resolve(alt))) return alt;
            return combined;
        }
        if ("neoforge".equals(profile.getModLoader()) && profile.getModLoaderVersion() != null
                && !profile.getModLoaderVersion().isEmpty()) {
            String folder = versionDownloadService.findNeoForgeVersionFolder(gameDir, profile.getModLoaderVersion());
            if (folder != null) return folder;
            return "neoforge-" + profile.getModLoaderVersion();
        }
        if ("optifine".equals(profile.getModLoader()) && profile.getModLoaderVersion() != null
                && !profile.getModLoaderVersion().isEmpty()) {
            return baseVersionId + "-OptiFine_" + profile.getModLoaderVersion();
        }
        return baseVersionId;
    }

    private void extractNatives(String classpathStr, Path nativesDir, GameOutputCallback callback) throws IOException {
        String sep = isWindows() ? ";" : ":";
        String[] entries = classpathStr.split(sep);
        Files.createDirectories(nativesDir);

        String os = isWindows() ? "windows" :
                    System.getProperty("os.name").toLowerCase().contains("mac") ? "macos" : "linux";
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean isArm = arch.contains("aarch64") || arch.contains("arm64");
        boolean isX86_32 = arch.equals("x86") || arch.equals("i386") || arch.equals("i686");

        for (String cpEntry : entries) {
            Path jarPath = Path.of(cpEntry);
            if (!Files.exists(jarPath)) continue;
            String fileName = jarPath.getFileName().toString().toLowerCase();
            if (!fileName.contains("native")) continue;
            if (!fileName.contains("lwjgl") && !fileName.contains("glfw") && !fileName.contains("jemalloc")
                    && !fileName.contains("openal") && !fileName.contains("jinput") && !fileName.contains("text2speech")
                    && !fileName.contains("twitch") && !fileName.contains("paulscode") && !fileName.contains("objc"))
                continue;
            boolean hasOsTag = fileName.contains("windows") || fileName.contains("macos") || fileName.contains("linux");
            if (hasOsTag) {
                if (fileName.contains("windows") && !os.equals("windows")) continue;
                if (fileName.contains("macos") && !os.equals("macos")) continue;
                if (fileName.contains("linux") && !os.equals("linux")) continue;
            }

            try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(Files.newInputStream(jarPath)))) {
                ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    String name = entry.getName();
                    if (entry.isDirectory()) continue;
                    if (!name.endsWith(".dll") && !name.endsWith(".so") && !name.endsWith(".dylib"))
                        continue;
                    // LWJGL 3 natives jars carry the same basename (.dll/.so/.dylib) under
                    // per-architecture folders: windows/x64/..., windows/x86/..., windows/arm64/...
                    // (and the linux/macos equivalents). Picking "the file by basename" without
                    // looking at that folder copied the LAST arch in classpath order - usually the
                    // x86 (32-bit) jar, which sits after the x64 one in Mojang's JSON - over the
                    // real x64 library, and the game then died with "UnsatisfiedLinkError" (or a
                    // wrong-architecture load error) on startup. Select the folder matching the
                    // current JVM's arch and skip the other variants entirely.
                    String lower = name.toLowerCase();
                    boolean inArm64 = lower.contains("/arm64/") || lower.contains("aarch64");
                    boolean inX64 = lower.contains("/x64/") || lower.contains("x86-64");
                    boolean inX86 = lower.contains("/x86/") || lower.contains("i386") || lower.contains("i686");
                    if (inArm64 && !isArm) continue;
                    if (!inArm64 && isArm) continue;   // arm64 JVM wants its own folder only
                    if (inX64 && (isArm || isX86_32)) continue;
                    if (inX86 && (isArm || is64Bit())) continue;
                    // LWJGL 2-style jars (<= 1.12) keep everything flat; keep the old name-based
                    // 32/64 heuristic for those, and it is harmless for the x64/x86 folders above
                    // since they were already resolved.
                    if (is64Bit() && !inArm64 && !inX64 && !inX86 && name.contains("32")) continue;
                    if (!is64Bit() && name.contains("64")) continue;
                    String simpleName = new File(name).getName();
                    Path dest = nativesDir.resolve(simpleName);
                    if (!Files.exists(dest) || Files.size(dest) != entry.getSize()) {
                        Files.copy(zis, dest, StandardCopyOption.REPLACE_EXISTING);
                        callback.onOutput("[NATIVES] Extracted " + simpleName);
                    }
                }
            }
        }
    }

    /**
     * Удаляет дерево целиком, НЕ останавливаясь на первом неудалённом файле.
     *
     * <p>Раньше {@code Files.deleteIfExists} бросал исключение наружу прямо из цикла, и обход
     * прекращался. Для папки natives это был штатный случай: пока выходящая игра держит
     * замапленную DLL, первый же файл не удаляется — и остальное дерево оставалось на диске
     * целиком. Замерено на рабочей машине: 11 осиротевших папок на 325 МБ.
     *
     * @return сколько объектов удалить не удалось
     */
    private static int deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return 0;
        int failed = 0;
        try (Stream<Path> stream = Files.walk(dir)) {
            for (Path path : stream.sorted(java.util.Comparator.reverseOrder()).collect(Collectors.toList())) {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    failed++;   // занято другим процессом — продолжаем с остальными
                }
            }
        }
        return failed;
    }

    /**
     * Подчищает осиротевшие папки natives при старте лаунчера.
     *
     * <p>Каталог на запуск называется по {@code System.nanoTime()} и удаляется в потоке выхода
     * того процесса, который игру запускал. Если тот процесс завершился раньше игры, или файлы
     * были ещё заняты, папка остаётся навсегда — своей чистки при старте не было вовсе.
     * К этому моменту игра уже не работает, поэтому файлы свободны и удаляются.
     *
     * @return сколько папок удалось убрать
     */
    public int cleanupOrphanNatives() {
        Path nativesRoot = gameDir.resolve("natives");
        if (!Files.isDirectory(nativesRoot)) return 0;
        int removed = 0;
        try (Stream<Path> dirs = Files.list(nativesRoot)) {
            for (Path dir : dirs.filter(Files::isDirectory).collect(Collectors.toList())) {
                try {
                    if (deleteRecursively(dir) == 0) removed++;
                } catch (IOException ignored) {
                    // следующая папка
                }
            }
        } catch (IOException ignored) {
        }
        if (removed > 0) {
            System.out.println("[NATIVES] убрано осиротевших папок: " + removed);
        }
        return removed;
    }

    /** How many timestamped world backups to keep per server before pruning the oldest. */
    private static final int MAX_WORLD_BACKUPS = 5;

    /** Best-effort snapshot of the server's "world" folder into servers/&lt;group&gt;/backups before
     *  each launch, so a corrupting crash mid-session can be rolled back. Never fails the launch -
     *  a backup problem just logs a warning and the server still starts. First run (no world yet)
     *  is a no-op. */
    private void backupWorld(Path serverDir, GameOutputCallback callback) {
        try {
            Path world = serverDir.resolve("world");
            if (!Files.isDirectory(world)) return;   // nothing to snapshot yet
            Path backupsDir = serverDir.resolve("backups");
            Files.createDirectories(backupsDir);
            String stamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            Path dest = backupsDir.resolve("world-" + stamp);
            callback.onOutput("[SERVER] Backing up world before launch...");
            copyDirectory(world, dest);
            callback.onOutput("[SERVER] World backed up to backups/" + dest.getFileName());
            pruneOldBackups(backupsDir);
        } catch (Exception ex) {
            callback.onOutput("[SERVER] World backup skipped: " + ex.getMessage());
        }
    }

    private static void copyDirectory(Path src, Path dest) throws IOException {
        try (Stream<Path> stream = Files.walk(src)) {
            for (Path path : stream.collect(Collectors.toList())) {
                Path target = dest.resolve(src.relativize(path).toString());
                if (Files.isDirectory(path)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(path, target, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    /** Keeps only the newest {@link #MAX_WORLD_BACKUPS} world-* snapshots (names sort
     *  chronologically), deleting older ones to bound disk use. */
    private static void pruneOldBackups(Path backupsDir) throws IOException {
        try (Stream<Path> stream = Files.list(backupsDir)) {
            List<Path> backups = stream
                    .filter(p -> Files.isDirectory(p) && p.getFileName().toString().startsWith("world-"))
                    .sorted(java.util.Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(Collectors.toList());
            for (int i = 0; i < backups.size() - MAX_WORLD_BACKUPS; i++) {
                deleteRecursively(backups.get(i));
            }
        }
    }

    private static boolean is64Bit() {
        String arch = System.getProperty("os.arch", "");
        return arch.contains("64") || arch.equals("amd64") || arch.equals("x86_64");
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private List<String> buildCommand(String classpath, String versionId, Profile profile,
                                      String mainClass, String assetIndex, Path nativesDir,
                                      String skinUrl, String capeUrl) throws IOException {
        List<String> command = new ArrayList<>();

        command.add(resolveJavaPath(profile));

        // The Settings tab's RAM sliders are the only RAM UI in the app - they only ever write
        // to the global config, never to a Profile (every group's minMemoryMb/maxMemoryMb is
        // frozen at its constructor default of 512/2048 forever, since nothing edits it). With
        // the profile checked first, that frozen default always won and the global setting -
        // the only thing the user can actually change - had no effect. Config takes priority.
        int minMem = config.getMinMemoryMb() > 0 ? config.getMinMemoryMb() : profile.getMinMemoryMb();
        int maxMem = config.getMaxMemoryMb() > 0 ? config.getMaxMemoryMb() : profile.getMaxMemoryMb();
        // Своя память у сборки (окно сборки → «Настройки запуска», 11.10.2026) важнее общей.
        if (profile.getBuildMemoryMb() > 0) maxMem = profile.getBuildMemoryMb();
        minMem = Math.min(minMem, maxMem);   // -Xms больше -Xmx — JVM не стартует вовсе

        command.add("-Xms" + minMem + "M");
        command.add("-Xmx" + maxMem + "M");
        command.add("-Dfile.encoding=UTF-8");
        command.add("-Duser.country=US");
        command.add("-Duser.language=en");

        command.add("-Djava.library.path=" + nativesDir.toString());

        // NeoForge/Forge's bootstrap (securejarhandler/module path setup) needs JVM flags it
        // declares itself in the version JSON's "arguments.jvm" (--add-opens, --add-exports,
        // -p <module path>, etc.) - without them the JVM throws InaccessibleObjectException
        // on startup. Vanilla versions list few/none here, so this is a no-op for them.
        command.addAll(collectJvmArgsFromVersion(versionId, nativesDir, classpath));

        // Свои аргументы Java из настроек (11.10.2026) — после наших, чтобы свой -Xmx или сборщик
        // мусора перекрывал выставленные лаунчером: у JVM при повторе побеждает последний.
        // У сборки свои аргументы — они вместо общих, а не вдобавок.
        String jvmLine = !profile.getBuildJvmArgs().isBlank() ? profile.getBuildJvmArgs()
                : (config == null ? "" : config.getJvmArgs());
        command.addAll(dropUnsupportedGcFlags(parseJvmArgs(jvmLine),
                new JavaVersionDetector().getMajorVersion(command.get(0))));

        command.add("-cp");
        command.add(classpath);

        command.add(mainClass);

        // isLoggedIn(), deliberately NOT hasLicense(): what a signed-in Microsoft account buys
        // here is the *Multiplayer button*, not licensed play. From 1.16.4 on, Minecraft builds a
        // UserApiService from the --accessToken it was given and greys out "Сетевая игра" unless
        // that service reports the multiplayerServer privilege; a fake "0" token can't build one
        // at all, so the button is dead even for offline-mode servers. A licence-less "stub" MSA
        // is enough: api.minecraftservices.com/player/attributes answers 200 with
        // "multiplayerServer": {"enabled": true} for it (verified against this account), which is
        // exactly the flag authlib reads. Requiring hasLicense() here would hand the game "0"
        // again and re-break the button for every stub account.
        //
        // The username/UUID fallbacks below still apply - a stub account has no Minecraft profile,
        // so the launch uses the local nickname and an offline UUID, which is what an
        // offline-mode server wants anyway.
        boolean signedIn = authService != null && authService.isLoggedIn();

        // Whose name/UUID the game launches as. Only a licensed account has a Minecraft profile to
        // take these from, and switching identity is what profile.isOnlineMode() is really for -
        // so that flag still gates this, exactly as before.
        boolean msaIdentity = signedIn && authService.hasLicense() && profile.isOnlineMode();

        // Whether the game gets the real access token. Separate from identity on purpose: for a
        // licence-less "stub" account the token can't change who you are (there's no profile), it
        // only lets Minecraft build a UserApiService and un-grey "Сетевая игра". profile
        // .isOnlineMode() defaults to false and nothing in the UI turns it on per build, so
        // gating the token on it left every signed-in stub account launching with "0" - i.e.
        // signing in appeared to do nothing at all, which is the bug this splits apart.
        boolean msaToken = signedIn && (profile.isOnlineMode() || !authService.hasLicense());

        // Токен действует около суток, а сессия восстанавливается только при старте лаунчера.
        // Лаунчер при этом живёт долго: он держит выделенный сервер и опрашивает ZeroTier каждые
        // 5 секунд, так что «оставить на ночь» — обычное дело. Без этой проверки утром в игру
        // уходил токен, просроченный часы назад: UserApiService в игре не поднимался, «Сетевая
        // игра» оставалась серой, и ничего об этом не сообщалось. Обновляемся прямо здесь, на
        // фоновом потоке запуска, и только если токен вообще пойдёт в игру.
        if (msaToken) {
            authService.ensureFreshToken();
        }

        String username = msaIdentity && authService.getUsername() != null
                ? authService.getUsername() : profile.getUsername();
        if (username == null || username.isBlank()) username = "Player";
        String uuid = msaIdentity && authService.getUuid() != null
                ? authService.getUuid() : profile.getUuid();
        if (uuid == null || uuid.isBlank()) {
            // Vanilla's own offline-UUID derivation, so the same username always maps to the
            // same UUID (matters for save data / skins on offline-mode servers).
            uuid = java.util.UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8)).toString();
        }
        // BL Core: the badge and capes mod, handed to Fabric from outside mods/ (see
        // BlCoreService). Its -D flags must come before -cp, so they go in where -cp sits.
        if (blCore != null) {
            List<String> blArgs = blCore.jvmArgs(profile.getModLoader(), profile.getVersionId(), username, uuid);
            int cpIndex = command.indexOf("-cp");
            if (!blArgs.isEmpty() && cpIndex > 0) command.addAll(cpIndex, blArgs);
        }

        String accessToken = msaToken && authService.getAccessToken() != null
                ? authService.getAccessToken() : "0";
        // "msa" only when a real token backs it - claiming msa while passing "0" makes the game's
        // own auth setup fail in a different, noisier way than plain offline does.
        //
        // Offline launches must say "legacy", NOT "offline": User.Type only gained an OFFLINE
        // value in ~1.20.2 (and 26.2 dropped the argument entirely - "Completely ignored
        // arguments"), while versions like 1.20.1 still parse --userType through
        // User.Type.byName() whose enum is LEGACY/MOJANG/MSA only - "offline" makes the game
        // log "Unrecognized user type: offline" and build a null User.Type, which then trips
        // every later user.getType() == User.Type.MSA check. "legacy" exists in every version
        // that parses the flag at all, so it is the safe cross-version value for a "0" token.
        String userType = "0".equals(accessToken) ? "legacy" : "msa";

        // Старые версии (до ~1.13, особенно до 1.6) используют поле
        // "minecraftArguments" в version JSON вместо набора --username/--uuid флагов.
        String legacyArgsTemplate = readLegacyMinecraftArguments(versionId);

        if (legacyArgsTemplate != null) {
            command.addAll(buildLegacyArguments(legacyArgsTemplate, username, uuid, accessToken,
                    versionId, assetIndex));
        } else {
            command.add("--username");
            command.add(username);
            command.add("--uuid");
            command.add(uuid);
            command.add("--version");
            command.add(versionId);
            command.add("--gameDir");
            command.add(gameDir.toString());
            command.add("--assetsDir");
            // Для 1.6.x индекс помечен "virtual": игра ждёт дерево по именам в
            // assets/virtual/<id>, а не каталог с объектами по хешам.
            command.add(LegacyAssets.assetsDirForLaunch(gameDir, assetsDir, assetIndex).toString());
            command.add("--assetIndex");
            command.add(assetIndex);
            command.add("--accessToken");
            command.add(accessToken);
            command.add("--userType");
            command.add(userType);
            command.add("--versionType");
            command.add("release");
            String userPropertiesJson = buildUserProperties(username, uuid, skinUrl, capeUrl);
            // Java 8 (versions <= 1.16.5) cannot use @argfile, so every argument goes straight
            // through the OS command line. The JSON value of --userProperties contains double
            // quotes, and when a plain ProcessBuilder command line reaches Windows, cmd's own
            // quote handling mangles them: the game then dies with Gson's
            // "Use JsonReader.setLenient(true) to accept malformed JSON at line 1 column 3".
            // The empty object is valid JSON that survives a raw command line untouched, and it
            // carries no skin/cape info - which those versions cannot apply from --userProperties
            // anyway. Newer versions use the @argfile path, where quotes are escaped correctly.
            if (new JavaVersionDetector().getMajorVersion(command.get(0)) < 9) {
                userPropertiesJson = "{}";
            }
            command.add("--userProperties");
            command.add(userPropertiesJson);

            // Mod loaders declare extra required game args in the version JSON's
            // "arguments.game" - e.g. NeoForge needs --fml.mcVersion/--fml.neoForgeVersion so
            // FML knows what it's bootstrapping; without them FML's startup throws its own NPE.
            // Vanilla's own "arguments.game" repeats the standard flags above (with the same
            // placeholders) - skip any flag name already added so we don't duplicate those.
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("${auth_player_name}", username);
            placeholders.put("${auth_uuid}", uuid);
            placeholders.put("${auth_access_token}", accessToken);
            placeholders.put("${auth_xuid}", "0");
            placeholders.put("${clientid}", "0");
            placeholders.put("${version_name}", versionId);
            placeholders.put("${game_directory}", gameDir.toString());
            placeholders.put("${assets_root}", assetsDir.toString());
            placeholders.put("${assets_index_name}", assetIndex);
            placeholders.put("${user_type}", userType);
            placeholders.put("${version_type}", "release");
            placeholders.put("${user_properties}", userPropertiesJson);

            Set<String> existingFlags = new HashSet<>();
            for (String token : command) {
                if (token.startsWith("--")) existingFlags.add(token);
            }
            List<String> extraGameArgs = collectGameArgsFromVersion(versionId, placeholders);
            for (int i = 0; i < extraGameArgs.size(); i++) {
                String token = extraGameArgs.get(i);
                if (token.startsWith("--")) {
                    boolean hasValue = i + 1 < extraGameArgs.size() && !extraGameArgs.get(i + 1).startsWith("--");
                    if (existingFlags.contains(token)) {
                        if (hasValue) i++; // skip its value too
                        continue;
                    }
                    command.add(token);
                    if (hasValue) command.add(extraGameArgs.get(++i));
                } else {
                    command.add(token);
                }
            }
        }

        // Окно, автовход на сервер и свои аргументы игры (11.10.2026). Версии до 1.6 берут
        // аргументы без «--» (шаблон «${auth_player_name} ${auth_session}») — им не добавляем,
        // незнакомый флаг они примут за ник или сессию.
        if (legacyArgsTemplate == null || legacyArgsTemplate.contains("--")) {
            command.addAll(extraGameArgs(profile, versionId));
        }
        nextLaunchServer = null;   // версия до 1.6 сюда не дошла — всё равно не переносим на следующий запуск

        return command;
    }

    private volatile String nextLaunchServer;

    /** Сервер, на который зайти при БЛИЖАЙШЕМ запуске (плитка сервера на главной); null — как обычно. */
    public void setNextLaunchServer(String address) {
        this.nextLaunchServer = address;
    }

    /** Размер окна, полноэкранный режим, автовход и свои аргументы игры — в этом порядке. */
    private List<String> extraGameArgs(Profile profile, String versionId) {
        List<String> out = new ArrayList<>();
        if (config == null) return out;
        if (config.getWindowWidth() > 0 && config.getWindowHeight() > 0) {
            out.add("--width");
            out.add(String.valueOf(config.getWindowWidth()));
            out.add("--height");
            out.add(String.valueOf(config.getWindowHeight()));
        }
        if (config.isFullscreen()) out.add("--fullscreen");
        // Запуск с плитки сервера на главной важнее и сборки, и общих настроек. Одноразовый:
        // читается и сбрасывается здесь, чтобы следующий обычный запуск не зашёл туда же.
        String once = nextLaunchServer;
        nextLaunchServer = null;
        String server = once != null && !once.isBlank() ? once
                : !profile.getBuildServer().isBlank() ? profile.getBuildServer() : config.getAutoJoinServer();
        out.addAll(serverArgs(server, versionSupportsQuickPlay(versionId)));
        String gameLine = !profile.getBuildGameArgs().isBlank() ? profile.getBuildGameArgs() : config.getGameArgs();
        out.addAll(parseGameArgs(gameLine));
        return out;
    }

    /**
     * Автовход на сервер. С 1.20 (23w14a) — {@code --quickPlayMultiplayer host:port}, старый
     * {@code --server/--port} там убран; до 1.20 — наоборот, есть только {@code --server/--port}.
     */
    static List<String> serverArgs(String address, boolean quickPlay) {
        List<String> out = new ArrayList<>();
        if (address == null || address.isBlank()) return out;
        String a = address.trim();
        if (quickPlay) {
            out.add("--quickPlayMultiplayer");
            out.add(a);
            return out;
        }
        String host = a, port = null;
        int colon = a.lastIndexOf(':');
        // IPv6 без скобок не разбираем: «::1» целиком уйдёт в --server.
        if (colon > 0 && a.indexOf(':') == colon && colon < a.length() - 1) {
            host = a.substring(0, colon);
            port = a.substring(colon + 1);
            if (!port.chars().allMatch(Character::isDigit)) { host = a; port = null; }
        }
        out.add("--server");
        out.add(host);
        if (port != null) {
            out.add("--port");
            out.add(port);
        }
        return out;
    }

    /** Есть ли в JSON версии (или в том, от чего она наследуется) флаг --quickPlayMultiplayer. */
    private boolean versionSupportsQuickPlay(String versionId) {
        java.util.Set<String> seen = new HashSet<>();
        String id = versionId;
        while (id != null && seen.add(id)) {
            Path json = versionsDir.resolve(id).resolve(id + ".json");
            try {
                String text = Files.readString(json);
                if (text.contains("quickPlayMultiplayer")) return true;
                JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
                id = obj.has("inheritsFrom") ? obj.get("inheritsFrom").getAsString() : null;
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    /** Флаги игры, которые ставит лаунчер: свой ник, токен или папку подставлять нельзя. */
    private static final Set<String> RESERVED_GAME_FLAGS = Set.of(
            "--username", "--uuid", "--accessToken", "--session", "--userType", "--userProperties",
            "--profileProperties", "--version", "--versionType", "--gameDir", "--assetsDir",
            "--assetIndex", "--xuid", "--clientId");

    /**
     * Свои аргументы игры: тот же разбор, что у аргументов Java (кавычки — для значений с
     * пробелами), но выкидываются флаги лаунчера вместе с их значением.
     */
    public static List<String> parseGameArgs(String line) {
        List<String> in = splitJvmArgs(line), out = new ArrayList<>();
        for (int i = 0; i < in.size(); i++) {
            String t = in.get(i);
            if (RESERVED_GAME_FLAGS.contains(t)) {
                if (i + 1 < in.size() && !in.get(i + 1).startsWith("--")) i++;
                continue;
            }
            out.add(t);
        }
        return out;
    }

    /**
     * ZGC есть только с Java 15 (раньше — экспериментальный), Shenandoah — с 12. Java 8 у версий
     * до 1.16 на «-XX:+UseZGC» вообще не стартует («Unrecognized VM option»), поэтому для старой
     * Java такие флаги из своих аргументов молча убираются — набор «ZGC» из настроек не должен
     * ломать запуск старых версий.
     */
    static List<String> dropUnsupportedGcFlags(List<String> args, int javaMajor) {
        if (javaMajor <= 0) return args;
        List<String> out = new ArrayList<>(args.size());
        for (String a : args) {
            if (javaMajor < 15 && (a.contains("UseZGC") || a.contains("ZGenerational"))) continue;
            if (javaMajor < 21 && a.contains("ZGenerational")) continue;
            if (javaMajor < 12 && a.contains("Shenandoah")) continue;
            out.add(a);
        }
        return out;
    }

    /**
     * Читает "minecraftArguments" из version JSON (старый формат, версии до 1.13).
     * Возвращает null, если версия использует современный формат "arguments" / нет поля вовсе.
     */
    private String readLegacyMinecraftArguments(String versionId) {
        try {
            Path json = versionsDir.resolve(versionId).resolve(versionId + ".json");
            if (!Files.exists(json)) return null;
            JsonObject obj = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
            if (obj.has("minecraftArguments")) {
                return obj.get("minecraftArguments").getAsString();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * Подставляет плейсхолдеры ${auth_player_name}, ${auth_uuid}, ${auth_access_token},
     * ${version_name}, ${game_directory}, ${assets_root}, ${assets_index_name},
     * ${user_type}, ${user_properties}, ${auth_session} (для очень старых версий до 1.6)
     * в старую строку minecraftArguments.
     */
    private List<String> buildLegacyArguments(String template, String username, String uuid,
                                              String accessToken, String versionId, String assetIndex) {
        // Очень старые версии (1.5.x и ниже) ожидают единый токен сессии вида "token:<token>:<uuid>"
        // вместо отдельных uuid/accessToken — это формат "legacy session" launchwrapper-а.
        String legacySession = "token:" + accessToken + ":" + uuid;

        // Подстановки держим в карте и применяем ПОСЛЕ разбиения шаблона на токены.
        // Раньше порядок был обратный: сначала подставлялись значения, потом строка резалась
        // по пробелу — и путь с пробелом разваливался на два аргумента. У пользователя Windows
        // с именем вида "John Doe" получалось "--gameDir C:\Users\John" плюс мусорный
        // позиционный аргумент, то есть игра стартовала с обрезанными gameDir и assetsDir:
        // пропавшие текстуры и звуки, сохранения не на своём месте. Затронуты были все версии
        // со старым minecraftArguments, то есть вся ваниль и Forge до 1.12.2 включительно.
        Map<String, String> values = new HashMap<>();
        values.put("${auth_player_name}", username);
        values.put("${auth_uuid}", uuid);
        values.put("${auth_access_token}", accessToken);
        values.put("${auth_session}", legacySession);
        values.put("${version_name}", versionId);
        values.put("${game_directory}", gameDir.toString());
        // Старые версии ищут файлы по именам, а не по хешам: ${game_assets} должен указывать на
        // resources/ (1.5.2 и старше) или assets/virtual/<id> (1.6.x), иначе версия не найдёт
        // ни звуков, ни языковых файлов. ${assets_root} остаётся корнем assets — его читают
        // версии 1.7.x, у которых уже обычная раскладка по хешам.
        Path launchAssets = LegacyAssets.assetsDirForLaunch(gameDir, assetsDir, assetIndex);
        values.put("${assets_root}", assetsDir.toString());
        values.put("${game_assets}", launchAssets.toString()); // версии до 1.7.10
        values.put("${assets_index_name}", assetIndex);
        values.put("${user_type}", "legacy");
        values.put("${user_properties}", "{}");
        values.put("${version_type}", "release");

        List<String> args = new ArrayList<>();
        for (String token : template.split("\\s+")) {
            if (token.isBlank()) continue;
            String resolved = token;
            for (Map.Entry<String, String> e : values.entrySet()) {
                if (resolved.contains(e.getKey())) {
                    resolved = resolved.replace(e.getKey(), e.getValue());
                }
            }
            args.add(resolved);
        }
        return args;
    }

    /**
     * Builds the --userProperties value passed to Minecraft. When local skin/cape URLs are given,
     * it embeds them as the standard "textures" property so the game can load them exactly like a
     * normal skin/cape from a session server.
     */
    private String buildUserProperties(String username, String uuid, String skinUrl, String capeUrl) {
        if (skinUrl == null && capeUrl == null) {
            return "{}";
        }
        try {
            JsonObject textures = new JsonObject();
            if (skinUrl != null) {
                JsonObject skin = new JsonObject();
                skin.addProperty("url", skinUrl);
                textures.add("SKIN", skin);
            }
            if (capeUrl != null) {
                JsonObject cape = new JsonObject();
                cape.addProperty("url", capeUrl);
                textures.add("CAPE", cape);
            }

            JsonObject value = new JsonObject();
            value.addProperty("timestamp", System.currentTimeMillis());
            value.addProperty("profileId", uuid == null ? "" : uuid.replace("-", ""));
            value.addProperty("profileName", username);
            value.add("textures", textures);

            String encoded = Base64.getEncoder().encodeToString(value.toString().getBytes(StandardCharsets.UTF_8));

            // authlib's PropertyMap.Serializer expects --userProperties values to be arrays of
            // plain base64 STRINGS (it calls element.getAsString() on each array item), not arrays
            // of {"name","value"} objects. Newer versions (1.20.2+) ignore the argument entirely,
            // so the legacy "textures":["<base64>"] shape is safe for every version that parses it.
            JsonArray array = new JsonArray();
            array.add(encoded);

            JsonObject root = new JsonObject();
            root.add("textures", array);
            return root.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    private String resolveJavaPath(Profile profile) {
        // Checked in this order - not javaOverride first - because javaOverride is sticky global
        // state (overrideJavaPath() sets it and nothing ever clears it back to null): once any
        // client launch had picked a Java for its version, every later launch of a *different*
        // Profile - including a dedicated server for a newer/older MC version - would silently
        // reuse that stale choice instead of its own profile.getJavaPath(), even though the
        // profile-specific value is the more specific and more correct one to use.
        String javaPath = profile.getJavaPath();
        if ((javaPath == null || javaPath.isEmpty()) && javaOverride != null && !javaOverride.isEmpty()) {
            javaPath = javaOverride;
        }
        if (javaPath == null || javaPath.isEmpty()) {
            javaPath = config.getJavaPath();
        }
        if (javaPath == null || javaPath.isEmpty()) {
            String javaHome = System.getProperty("java.home");
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                return javaHome + "\\bin\\java.exe";
            }
            return javaHome + "/bin/java";
        }
        return javaPath;
    }

    public void overrideJavaPath(String javaExe) {
        this.javaOverride = javaExe;
    }

    private String javaOverride = null;

    private BlCoreService blCore;

    /** Turns BL Core on for Fabric launches; null keeps every launch exactly as before. */
    public void setBlCore(BlCoreService blCore) {
        this.blCore = blCore;
    }

    public void setAuthService(AuthService authService) {
        this.authService = authService;
    }

    /** Tracks, per launch, exactly which live-mods/-folder filenames THIS launcher itself copied
     *  in - used by syncMods() so its cleanup step only ever removes files it actually placed
     *  there for a previously active profile. Previously it deleted every file in the live
     *  folder that wasn't also in the new profile's own staging subfolder, full stop - which
     *  meant a mod jar (or any other file) a player copied into mods/ by hand, outside BLauncher
     *  entirely, got silently wiped out the next time any build was launched. */
    private static final String MODS_MANAGED_MARKER = ".blauncher_managed_mods.txt";

    private void syncMods(Profile profile) {
        Path srcDir  = gameDir.resolve("mods").resolve(profile.getName());
        Path destDir = gameDir.resolve("mods");
        Path marker  = destDir.resolve(MODS_MANAGED_MARKER);
        boolean srcExists = Files.exists(srcDir);

        try {
            Files.createDirectories(destDir);
            Set<String> previouslyManaged = readManagedFileList(marker);

            // Remove only files THIS launcher copied in for whichever profile was active before -
            // never anything it doesn't recognize as its own.
            for (String prevFile : previouslyManaged) {
                Path destFile = destDir.resolve(prevFile);
                Path srcFile = srcDir.resolve(prevFile);
                if (Files.exists(destFile) && (!srcExists || !Files.exists(srcFile))) {
                    Files.deleteIfExists(destFile);
                }
            }

            Set<String> nowManaged = new HashSet<>();
            if (srcExists) {
                try (Stream<Path> stream = Files.list(srcDir)) {
                    stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                            .forEach(src -> {
                                String fname = src.getFileName().toString();
                                try {
                                    Path dest = destDir.resolve(fname);
                                    if (!Files.exists(dest)) {
                                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                                        nowManaged.add(fname);
                                    } else if (previouslyManaged.contains(fname)) {
                                        // Same file we copied in on an earlier sync of this same
                                        // profile - still ours to manage.
                                        nowManaged.add(fname);
                                    }
                                    // else: something else already occupies that name (a manual
                                    // copy, another profile's file this cleanup pass hasn't
                                    // reached yet, etc.) - leave it alone, don't claim it.
                                } catch (IOException ignored) {}
                            });
                }
            }
            writeManagedFileList(marker, nowManaged);
        } catch (IOException ignored) {}
    }

    private Set<String> readManagedFileList(Path marker) {
        try {
            if (!Files.exists(marker)) return new HashSet<>();
            return new HashSet<>(Files.readAllLines(marker, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new HashSet<>();
        }
    }

    private void writeManagedFileList(Path marker, Set<String> names) {
        try {
            Files.write(marker, names, StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }

    /**
     * Makes this group's resource packs / shader packs available in the live game by copying its
     * {@code <type>/<group>/} folder into the shared {@code <type>/} folder. Unlike syncMods(), this
     * is purely additive: it never deletes files already in the live folder, because that folder can
     * also hold packs the player added by hand (or earlier from another group) and Minecraft only
     * actually applies the ones the player selects in-game - extra available packs are harmless,
     * whereas deleting a player's hand-installed pack on a group switch would not be.
     */
    private void syncGroupContentAdditive(Profile profile, String type) {
        Path srcDir = gameDir.resolve(type).resolve(profile.getName());
        if (!Files.exists(srcDir)) return;
        Path destDir = gameDir.resolve(type);
        try {
            Files.createDirectories(destDir);
            try (Stream<Path> stream = Files.list(srcDir)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().equals(".installed.json"))
                        .forEach(src -> {
                            try {
                                Path dest = destDir.resolve(src.getFileName());
                                if (!Files.exists(dest)) Files.copy(src, dest);
                            } catch (IOException ignored) {}
                        });
            }
        } catch (IOException ignored) {}
    }

    /**
     * Shader packs work like resource packs (additive copy into the live folder) but their per-group
     * stash lives OUTSIDE shaderpacks/ - under {@link ModrinthService#SHADER_STORAGE_DIR} - because
     * Iris/OptiFine list every folder inside shaderpacks/ as a selectable pack, so a
     * shaderpacks/&lt;group&gt;/ staging folder showed up in-game as a bogus entry. This copies the
     * group's packs up into shaderpacks/ and migrates any old shaderpacks/&lt;group&gt;/ stash (from
     * before this layout) into the hidden dir so it stops appearing in the selector.
     */
    private void syncShaderpacks(Profile profile) {
        Path live = gameDir.resolve("shaderpacks");
        Path storage = gameDir.resolve(ModrinthService.SHADER_STORAGE_DIR).resolve(profile.getName());

        // One-time migration of the old layout: lift shaderpacks/<group>/ out of the live folder
        // (where it was being listed as a broken pack) and into the hidden stash.
        Path legacy = live.resolve(profile.getName());
        if (Files.isDirectory(legacy)) {
            try {
                Files.createDirectories(storage);
                try (Stream<Path> s = Files.list(legacy)) {
                    for (Path p : s.collect(Collectors.toList())) {
                        try {
                            Files.move(p, storage.resolve(p.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException ignored) {}
                    }
                }
                Files.deleteIfExists(legacy);
            } catch (IOException ignored) {}
        }

        if (!Files.exists(storage)) return;
        try {
            Files.createDirectories(live);
            try (Stream<Path> stream = Files.list(storage)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> !p.getFileName().toString().equals(".installed.json"))
                        .forEach(src -> {
                            try {
                                Path dest = live.resolve(src.getFileName());
                                if (!Files.exists(dest)) Files.copy(src, dest);
                            } catch (IOException ignored) {}
                        });
            }
        } catch (IOException ignored) {}
    }

    /**
     * Data packs are per-world in vanilla Minecraft (saves/&lt;world&gt;/datapacks/), with no global
     * folder, so a group's data packs are copied into every existing world's datapacks folder at
     * launch. Additive only (same reasoning as syncGroupContentAdditive) - never removes data packs
     * already present in a world.
     */
    private void syncDatapacks(Profile profile) {
        Path srcDir = gameDir.resolve("datapacks").resolve(profile.getName());
        if (!Files.exists(srcDir)) return;
        Path savesDir = gameDir.resolve("saves");
        if (!Files.exists(savesDir)) return;
        try (Stream<Path> worlds = Files.list(savesDir)) {
            for (Path world : worlds.filter(Files::isDirectory).collect(Collectors.toList())) {
                Path destDir = world.resolve("datapacks");
                try {
                    Files.createDirectories(destDir);
                    try (Stream<Path> stream = Files.list(srcDir)) {
                        stream.filter(Files::isRegularFile)
                                .filter(p -> !p.getFileName().toString().equals(".installed.json"))
                                .forEach(src -> {
                                    try {
                                        Path dest = destDir.resolve(src.getFileName());
                                        if (!Files.exists(dest)) Files.copy(src, dest);
                                    } catch (IOException ignored) {}
                                });
                    }
                } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {}
    }

    /** Stops every running Minecraft instance (the Stop button is a single control, so it acts on
     *  all of them at once). Each process's own exit thread then handles its cleanup. */
    public void stopGame() {
        for (Process p : new java.util.ArrayList<>(gameProcesses)) {
            if (p.isAlive()) p.destroy();
        }
    }

    public boolean isGameRunning() {
        synchronized (gameProcesses) {
            for (Process p : gameProcesses) {
                if (p.isAlive()) return true;
            }
        }
        return false;
    }

    // ===================== DEDICATED SERVER =====================
    // Mirrors launchGame()'s ProcessBuilder/output-thread/exit-thread pattern, but for a real
    // dedicated server matching the active group's version+loader+mods - kept as its own
    // separate flow rather than squeezed into launchGame() because the command shapes differ
    // too much (Forge/NeoForge servers run via their installer-generated run.bat; the client
    // never does) to share much beyond "spawn a process and stream its output".
    //
    // Keyed by server name - the same key serverDirFor() already uses for its own directory -
    // rather than a single shared field. A single field used to mean only one local dedicated
    // server could run at a time launcher-wide, so starting a *different*, differently-named
    // server while one was already up silently stopped the first instead of starting the
    // second. Each name now gets its own independent running process.

    private final java.util.Map<String, Process> serverProcesses = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Server processes this launcher did NOT spawn: leftovers from a previous launcher session
     * that was force-killed (or a server started by hand). Keyed by the same server name as
     * {@link #serverProcesses} and populated by {@link #adoptRunningServers}. We can see their
     * liveness and kill them, but - having never spawned them - cannot pipe their output into
     * the console nor send commands to their stdin.
     */
    private final java.util.Map<String, Integer> adoptedServerPids = new java.util.concurrent.ConcurrentHashMap<>();

    public Path serverDirFor(Profile group) {
        return serverDirForName(group.getName());
    }

    /** Dedicated-server directory for a server name (used both by {@link #serverDirFor(Profile)}
     *  and by process adoption, which only has the name). */
    public Path serverDirForName(String name) {
        return gameDir.resolve("servers").resolve(name);
    }

    /** True if ANY local dedicated server is currently running (spawned by us OR adopted). */
    public boolean isServerRunning() {
        if (!serverProcesses.isEmpty()) return true;
        for (Integer pid : adoptedServerPids.values()) {
            if (isAlive(pid)) return true;
        }
        return false;
    }

    /** True if the specific server named {@code serverKey} (matches serverDirFor()'s own
     *  Profile-name keying) is currently running, whether we spawned it or adopted it. */
    public boolean isServerRunning(String serverKey) {
        Process p = serverProcesses.get(serverKey);
        if (p != null && p.isAlive()) return true;
        return isAlive(adoptedServerPids.get(serverKey));
    }

    private static boolean isAlive(Integer pid) {
        if (pid == null) return false;
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    /** Runs a small PowerShell script file and returns its stdout. Written to a temp .ps1 with a
     *  UTF-8 BOM - Windows PowerShell 5.1 otherwise assumes ANSI and mangles Cyrillic paths - and
     *  invoked via -File, so no nested-quote or console-encoding issues ever reach the command
     *  line (the same trick as the ZeroTier MSI install and the firewall rule). */
    private String runPowerShellScript(String body) throws IOException, InterruptedException {
        Path script = Files.createTempFile("blauncher-ps-", ".ps1");
        try {
            Files.write(script, new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
            Files.write(script, body.getBytes(StandardCharsets.UTF_8), StandardOpenOption.APPEND);
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-File", script.toAbsolutePath().toString())
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            return out;
        } finally {
            try {
                Files.deleteIfExists(script);
            } catch (IOException ignored) {
            }
        }
    }

    /** Every java.exe process as "pid|commandline" lines. The script itself stays pure ASCII -
     *  all path matching happens in Java afterwards, so Cyrillic server names can never corrupt
     *  the script encoding. {@code [Console]::OutputEncoding} forces UTF-8 on the redirected
     *  stdout - Windows PowerShell 5.1 otherwise writes the command lines in the OEM codepage
     *  (e.g. cp866), garbling Cyrillic server dirs before Java can match them (verified live:
     *  without this line adoption and stale-kill silently found nothing). */
    private String listJavaProcesses() throws IOException, InterruptedException {
        return runPowerShellScript("[Console]::OutputEncoding = [System.Text.Encoding]::UTF8; "
                + "Get-CimInstance Win32_Process -Filter \"Name='java.exe'\" | "
                + "ForEach-Object { $_.ProcessId.ToString() + '|' + $_.CommandLine }");
    }

    /**
     * Finds java processes whose command line contains one of the given server directories and
     * registers their PIDs as adopted, so a server left running by a killed launcher session
     * shows as "running" in the UI and can be stopped from there instead of being invisible.
     * Entries whose process has since exited are dropped. Best-effort - a failure just leaves
     * adoption empty.
     *
     * @return how many servers ended up registered as adopted (for a console log line).
     */
    public int adoptRunningServers(List<String> serverNames) {
        try {
            Map<String, String> dirToName = new HashMap<>();
            for (String name : serverNames) {
                if (name == null || name.isBlank()) continue;
                dirToName.put(serverDirForName(name).toAbsolutePath().normalize().toString().toLowerCase(), name);
            }
            if (dirToName.isEmpty()) {
                adoptedServerPids.clear();
                return 0;
            }

            Map<String, Integer> found = new HashMap<>();
            for (String line : listJavaProcesses().split("\\r?\\n")) {
                int sep = line.indexOf('|');
                if (sep <= 0) continue;
                String pidStr = line.substring(0, sep).trim();
                String cmd = line.substring(sep + 1).toLowerCase();
                int pid;
                try {
                    pid = Integer.parseInt(pidStr);
                } catch (NumberFormatException ignored) {
                    continue;
                }
                for (var e : dirToName.entrySet()) {
                    if (!cmd.contains(e.getKey())) continue;
                    // Сервер, который запустил сам лаунчер, усыновлять нельзя. У такого есть
                    // живой Process с его stdin, то есть ему можно отправить "stop" и дать
                    // сохранить мир. А у усыновлённого stdin нет, поэтому stopServer убивает
                    // его через destroyForcibly — и раньше этот путь выбирался и для своего
                    // собственного сервера, стоило зайти на экран «Серверы».
                    Process own = serverProcesses.get(e.getValue());
                    if (own != null && own.isAlive()) continue;
                    found.put(e.getValue(), pid);
                }
            }

            // Drop dead adoptions, keep live ones, add newly found ones.
            adoptedServerPids.entrySet().removeIf(e -> !isAlive(e.getValue()));
            // Своё никогда не перетираем усыновлением, даже если оно попало сюда раньше.
            for (String key : serverProcesses.keySet()) {
                Process own = serverProcesses.get(key);
                if (own != null && own.isAlive()) adoptedServerPids.remove(key);
            }
            adoptedServerPids.putAll(found);
            int live = 0;
            for (Integer pid : adoptedServerPids.values()) {
                if (isAlive(pid)) live++;
            }
            return live;
        } catch (Exception ignored) {
            // Adoption is best-effort; a broken query must never break the launcher.
            return 0;
        }
    }

    /** Installs (if needed), configures, and starts a dedicated server for {@code group}.
     *  {@code onReady} fires once the server's own log confirms the world finished loading
     *  ("Done (...)!"), not just once the OS process spawned - spawning succeeds even when
     *  the server goes on to crash during world load (port conflict, OOM, mod error), so callers
     *  that want to know the server is actually joinable need this rather than a normal return.
     *  {@code onExit} fires whenever the process ends, ready or not, so callers can un-stick any
     *  "Running" UI state they set from {@code onReady} when the server dies outside of a normal
     *  {@link #stopServer(String)} call. Both may be null. */
    public void launchServer(Profile group, int port, GameOutputCallback callback, Runnable onReady, IntConsumer onExit) throws Exception {
        String serverKey = group.getName();
        if (isServerRunning(serverKey)) {
            callback.onOutput("[SERVER] Already running.");
            return;
        }

        Path serverDir = serverDirFor(group);
        Files.createDirectories(serverDir);

        String mc = group.getVersionId();
        String loaderName = group.getModLoader() == null || group.getModLoader().isBlank()
                ? "vanilla" : group.getModLoader();
        ModLoaderService.Loader loader = switch (loaderName) {
            case "forge" -> ModLoaderService.Loader.FORGE;
            case "neoforge" -> ModLoaderService.Loader.NEOFORGE;
            case "fabric" -> ModLoaderService.Loader.FABRIC;
            case "optifine" -> ModLoaderService.Loader.OPTIFINE;
            case "paper" -> ModLoaderService.Loader.PAPER;
            case "purpur" -> ModLoaderService.Loader.PURPUR;
            case "pufferfish" -> ModLoaderService.Loader.PUFFERFISH;
            default -> ModLoaderService.Loader.VANILLA;
        };

        if (!modLoaderService.isServerLoaderInstalled(loader, serverDir)) {
            callback.onOutput("[SERVER] Installing " + loaderName + " server for MC " + mc + "...");
            modLoaderService.installServerLoader(mc, loader, group.getModLoaderVersion(), serverDir, callback::onOutput);
            callback.onOutput("[SERVER] Install complete.");
        }

        syncServerMods(group, serverDir);
        writeServerDefaultsIfMissing(serverDir, port);
        // A force-killed launcher orphans its dedicated server (deliberately an independent OS
        // process - see the class javadoc), and the orphan's world/session.lock then makes the
        // next start fail with a confusing FileSystemException deep inside Minecraft. Stop any
        // leftover java process still running THIS server dir before touching the world.
        killStaleServerProcess(serverDir, callback);

        // Порт занят — говорим сразу и по-человечески. 14.10.2026: сервер на занятом порту
        // писал в консоль «FAILED TO BIND TO PORT» и выходил — ванильный с кодом 0, так что
        // лаунчер показывал просто «остановлен», без слова о причине.
        int actualPort = serverPortFromProperties(serverDir, port);
        if (!isPortFree(actualPort)) {
            throw new IOException("Порт " + actualPort + " уже занят другой программой — возможно, другим "
                    + "сервером Minecraft. Остановите её или укажите для этого сервера другой порт.");
        }
        backupWorld(serverDir, callback);

        List<String> command = buildServerCommand(group, loader, serverDir);
        callback.onOutput("[SERVER] Starting dedicated server in " + serverDir + "...");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(serverDir.toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        serverProcesses.put(serverKey, process);

        Thread serverOutputThread = new Thread(() -> {
            boolean ready = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    callback.onOutput("[SERVER] " + line);
                    if (!ready && line.contains("Done (") && onReady != null) {
                        ready = true;
                        onReady.run();
                    }
                }
            } catch (IOException e) {
                callback.onError("Error reading server output: " + e.getMessage());
            }
        });
        serverOutputThread.setDaemon(true);
        serverOutputThread.start();

        new Thread(() -> {
            try {
                int exitCode = process.waitFor();
                callback.onOutput("[SERVER] Server closed with exit code: " + exitCode);
                if (onExit != null) onExit.accept(exitCode);
            } catch (InterruptedException e) {
                callback.onError("Server process interrupted: " + e.getMessage());
            } finally {
                serverProcesses.remove(serverKey, process);
            }
        }).start();
    }

    /** Sends the standard graceful dedicated-server shutdown command rather than killing the
     *  process outright - "stop" lets it save all loaded worlds first, avoiding corruption.
     *  Vanilla servers can occasionally get stuck spinning at ~100% CPU after "stop" - most often
     *  reported after the server had already gone idle via its own "Server empty for N seconds,
     *  pausing" state - and never actually exit; previously the only way out was killing the
     *  process by hand from Task Manager. A background watchdog now does that automatically: a
     *  real graceful shutdown (world save + JVM exit) normally finishes within a few seconds, so
     *  anything still alive well past that gets force-killed instead of being left to spin. */
    public void stopServer(String serverKey) {
        if (!isServerRunning(serverKey)) return;

        // Adopted process: we never spawned it, so there is no stdin to send "stop" through -
        // kill it directly (a "stop" command would otherwise be the graceful path).
        Integer adoptedPid = adoptedServerPids.get(serverKey);
        if (adoptedPid != null) {
            ProcessHandle.of(adoptedPid).ifPresent(ProcessHandle::destroyForcibly);
            adoptedServerPids.remove(serverKey);
            return;
        }

        sendServerCommand(serverKey, "stop");

        Process process = serverProcesses.get(serverKey);
        if (process == null) return;
        Thread watchdog = new Thread(() -> {
            try {
                boolean exited = process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
                if (!exited && process.isAlive()) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                // Launcher shutting down mid-wait - the process will be left running (same as
                // any other "spawned independently, outlives this JVM" server process today).
            }
        });
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** Gracefully stops every currently-running local dedicated server - used on launcher
     *  shutdown (see MainController.shutdownBackgroundProcesses()) so none of them are left
     *  running unmanaged once BLauncher exits. */
    public void stopAllServers() {
        for (String key : new ArrayList<>(serverProcesses.keySet())) {
            stopServer(key);
        }
        for (String key : new ArrayList<>(adoptedServerPids.keySet())) {
            stopServer(key);
        }
    }

    /** Writes a line to the given running dedicated server's stdin, exactly as if it were typed
     *  into its own console - "op <name>", "whitelist add <name>", "say ...", etc. BLauncher has
     *  no visible console window for the server (everything funnels into its own in-app log), so
     *  this is the only way to drive the server once it's up. */
    public void sendServerCommand(String serverKey, String command) {
        Process process = serverProcesses.get(serverKey);
        if (process == null || !process.isAlive() || command == null || command.isBlank()) return;
        try {
            OutputStream stdin = process.getOutputStream();
            stdin.write((command.trim() + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException e) {
            process.destroy();
        }
    }

    /** Before starting, terminate any leftover java process still running THIS server dir from a
     *  previous launcher session. Force-killing BLauncher orphans its dedicated server (it is
     *  deliberately an independent OS process), and the orphan keeps {@code world/session.lock}
     *  and {@code logs/latest.log} open, so the next start fails with a confusing
     *  FileSystemException deep inside Minecraft instead of a clear message. Matches java
     *  processes whose command line contains this server's own absolute directory - a Minecraft
     *  client or a different server is never touched. Best-effort: a failure just logs and the
     *  start proceeds. */
    private void killStaleServerProcess(Path serverDir, GameOutputCallback callback) {
        try {
            String marker = serverDir.toAbsolutePath().normalize().toString().toLowerCase();
            for (String line : listJavaProcesses().split("\\r?\\n")) {
                int sep = line.indexOf('|');
                if (sep <= 0) continue;
                String pidStr = line.substring(0, sep).trim();
                if (!line.substring(sep + 1).toLowerCase().contains(marker)) continue;
                try {
                    int pid = Integer.parseInt(pidStr);
                    ProcessHandle.of(pid).ifPresent(h -> {
                        if (h.isAlive()) {
                            h.destroy();
                            callback.onOutput("[SERVER] Остановлен зависший процесс сервера от предыдущей сессии (PID " + pid + ").");
                        }
                    });
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Exception ex) {
            callback.onOutput("[SERVER] Не удалось проверить старый процесс сервера: " + ex.getMessage());
        }
    }

    private List<String> buildServerCommand(Profile group, ModLoaderService.Loader loader, Path serverDir) {
        int minMem = config.getMinMemoryMb() > 0 ? config.getMinMemoryMb() : group.getMinMemoryMb();
        int maxMem = config.getMaxMemoryMb() > 0 ? config.getMaxMemoryMb() : group.getMaxMemoryMb();

        if (loader == ModLoaderService.Loader.FORGE || loader == ModLoaderService.Loader.NEOFORGE) {
            // The installer already wrote a run script with the full classpath/JVM-arg
            // incantation (including its own @user_jvm_args.txt) - run that instead of
            // re-deriving the same arguments ourselves the way the client path has to.
            applyRamToUserJvmArgs(serverDir, minMem, maxMem);
            Path runScript = serverDir.resolve(isWindows() ? "run.bat" : "run.sh");
            // Сам скрипт не запускаем — из него берём только аргументы строки «java …». Проверено
            // 14.10.2026 на локальных серверах: (1) run.bat зовёт голый «java» из PATH, а не Java,
            // которую лаунчер подобрал под версию, — у игрока без Java в PATH сервер не стартует
            // вовсе; (2) в конце run.bat стоит «pause»: упавший сервер оставлял висеть cmd.exe, и
            // лаунчер бесконечно показывал его «работающим» (NeoForge 1.21.1, порт занят).
            List<String> fromScript = javaArgsFromRunScript(runScript);
            if (fromScript != null) {
                List<String> command = new ArrayList<>();
                command.add(resolveJavaPath(group));
                command.addAll(fromScript);
                command.add("nogui");
                return command;
            }
            // Старый Forge (до 1.17) run-скрипта не кладёт вовсе — только forge-<mc>-<версия>.jar
            // (и minecraft_server.<mc>.jar рядом). Раньше лаунчер звал несуществующий run.bat, и
            // сервер Forge 1.12.2 не запускался никогда (код 1 за секунды). Так же делает VpsInstaller.
            Path forgeJar = findForgeServerJar(serverDir);
            if (forgeJar != null) {
                return List.of(resolveJavaPath(group), "-Xms" + minMem + "M", "-Xmx" + maxMem + "M",
                        "-jar", forgeJar.toAbsolutePath().toString(), "nogui");
            }
            if (isWindows()) {
                return List.of("cmd", "/c", runScript.toAbsolutePath().toString(), "nogui");
            }
            return List.of("bash", runScript.toAbsolutePath().toString(), "nogui");
        }

        // Vanilla / Fabric / OptiFine-fallback / custom cores: a plain java -jar <name> nogui.
        // Fabric's installer produces fabric-server-launch.jar; Paper/Purpur/Pufferfish each ship
        // their own similarly-named single jar.
        String serverJarName = switch (loader) {
            case FABRIC -> "fabric-server-launch.jar";
            case PAPER -> "paper.jar";
            case PURPUR -> "purpur.jar";
            case PUFFERFISH -> "pufferfish.jar";
            default -> "server.jar";
        };
        Path serverJar = serverDir.resolve(serverJarName);
        List<String> command = new ArrayList<>();
        command.add(resolveJavaPath(group));
        command.add("-Xms" + minMem + "M");
        command.add("-Xmx" + maxMem + "M");
        command.add("-jar");
        command.add(serverJar.toAbsolutePath().toString());
        command.add("nogui");
        return command;
    }

    /**
     * Аргументы после «java» из run.bat/run.sh установщика Forge/NeoForge (обычно два @-файла:
     * {@code @user_jvm_args.txt @libraries/.../win_args.txt}), без «%*»/«"$@"». Пути в них
     * относительные — процесс сервера и так стартует в папке сервера. null — скрипта нет или в
     * нём не нашлось строки запуска java.
     */
    private static List<String> javaArgsFromRunScript(Path script) {
        if (!Files.isRegularFile(script)) return null;
        try {
            for (String raw : Files.readAllLines(script)) {
                String line = raw.trim();
                if (!line.startsWith("java ")) continue;
                List<String> args = new ArrayList<>();
                for (String token : line.substring(5).trim().split("\\s+")) {
                    if (token.isEmpty() || token.equals("%*") || token.equals("\"$@\"") || token.equals("$@")) continue;
                    args.add(token);
                }
                return args.isEmpty() ? null : args;
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /** server-port из server.properties (игрок мог поменять его руками), иначе {@code fallback}. */
    private static int serverPortFromProperties(Path serverDir, int fallback) {
        Path props = serverDir.resolve("server.properties");
        if (!Files.isRegularFile(props)) return fallback;
        try {
            for (String line : Files.readAllLines(props, StandardCharsets.ISO_8859_1)) {
                String l = line.trim();
                if (l.startsWith("server-port=")) {
                    return Integer.parseInt(l.substring("server-port=".length()).trim());
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        return fallback;
    }

    /** Порт можно занять на всех адресах — так его займёт сам сервер Minecraft. */
    private static boolean isPortFree(int port) {
        if (port <= 0 || port > 65535) return true;   // не наш случай судить — пусть скажет сервер
        try (java.net.ServerSocket s = new java.net.ServerSocket()) {
            s.setReuseAddress(false);
            s.bind(new java.net.InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** forge-&lt;mc&gt;-&lt;версия&gt;[-universal].jar в папке сервера старого Forge, кроме установщика. */
    private static Path findForgeServerJar(Path serverDir) {
        try (var files = Files.list(serverDir)) {
            return files.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.startsWith("forge-") && n.endsWith(".jar") && !n.contains("installer");
                    })
                    .sorted()
                    .findFirst().orElse(null);
        } catch (IOException e) {
            return null;
        }
    }

    /** Rewrites the installer-generated user_jvm_args.txt with this group's RAM settings, but
     *  only once - a marker file remembers we've done it so re-starting the server doesn't keep
     *  stomping on RAM values the player may have hand-edited afterward. */
    private void applyRamToUserJvmArgs(Path serverDir, int minMem, int maxMem) {
        Path marker = serverDir.resolve(".blauncher_ram_applied");
        if (Files.exists(marker)) return;
        Path argsFile = serverDir.resolve("user_jvm_args.txt");
        if (!Files.exists(argsFile)) return;
        try {
            String content = "# JVM arguments for this server - edit freely, BLauncher won't overwrite this again.\n"
                    + "-Xms" + minMem + "M\n-Xmx" + maxMem + "M\n";
            Files.writeString(argsFile, content, StandardCharsets.UTF_8);
            Files.writeString(marker, "");
        } catch (IOException ignored) {}
    }

    /** Writes eula.txt + a minimal server.properties on first install only - never overwrites
     *  either if the player has already started (and possibly edited) this server before. */
    private void writeServerDefaultsIfMissing(Path serverDir, int port) throws IOException {
        Path eula = serverDir.resolve("eula.txt");
        if (!Files.exists(eula)) {
            Files.writeString(eula, "eula=true\n");
        }
        Path props = serverDir.resolve("server.properties");
        if (!Files.exists(props)) {
            // online-mode=false matches this launcher's existing offline-account-friendly
            // default (the same accounts used elsewhere in BLauncher can join without a
            // Microsoft-licensed copy of the game). Port comes from whichever ServerEntry this
            // launch was for - it used to always be hardcoded to 25565 here regardless of what
            // port the "Create Server" modal's own field said, so a custom port was silently
            // ignored the moment the server actually started. white-list=false is spelled out
            // explicitly (rather than left for the server's own defaulting) so a friend testing
            // straight after "Создать сервер" can always join immediately - nothing in BLauncher
            // ever calls "whitelist add", so anyone relying on the vanilla default instead would
            // get rejected with "You are not white-listed" the moment that default is anything
            // other than false.
            Files.writeString(props, "server-port=" + port + "\nonline-mode=false\nwhite-list=false\nmotd=A BLauncher server\n");
        }
    }

    /** Mirrors syncMods()'s "make the target folder match the group's mods exactly" behavior,
     *  just copying into the server's own mods/ folder instead of the live client one. */
    /** Same "never delete a file it doesn't recognize as its own" fix as syncMods() - a
     *  dedicated server's mods/ folder is just as reachable by hand (FTP, a friend copying a
     *  jar in directly, etc.) as the client's, and used to lose anything not mirrored in this
     *  group's staging subfolder on every single server start. */
    private void syncServerMods(Profile group, Path serverDir) {
        Path srcDir = gameDir.resolve("mods").resolve(group.getName());
        Path destDir = serverDir.resolve("mods");
        Path marker = destDir.resolve(MODS_MANAGED_MARKER);
        boolean srcExists = Files.exists(srcDir);
        try {
            Files.createDirectories(destDir);
            Set<String> previouslyManaged = readManagedFileList(marker);

            for (String prevFile : previouslyManaged) {
                Path destFile = destDir.resolve(prevFile);
                Path srcFile = srcDir.resolve(prevFile);
                if (Files.exists(destFile) && (!srcExists || !Files.exists(srcFile))) {
                    Files.deleteIfExists(destFile);
                }
            }

            Set<String> nowManaged = new HashSet<>();
            if (srcExists) {
                try (Stream<Path> stream = Files.list(srcDir)) {
                    stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                            .forEach(src -> {
                                String fname = src.getFileName().toString();
                                try {
                                    Path dest = destDir.resolve(fname);
                                    if (!Files.exists(dest)) {
                                        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                                        nowManaged.add(fname);
                                    } else if (previouslyManaged.contains(fname)) {
                                        nowManaged.add(fname);
                                    }
                                } catch (IOException ignored) {}
                            });
                }
            }
            writeManagedFileList(marker, nowManaged);
        } catch (IOException ignored) {}
    }


    @FunctionalInterface
    public interface GameOutputCallback {
        void onOutput(String message);

        default void onError(String message) {
            onOutput("[ERROR] " + message);
        }
    }
}