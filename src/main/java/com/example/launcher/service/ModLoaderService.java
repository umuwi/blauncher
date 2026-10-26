package com.example.launcher.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.jar.JarFile;

/**
 * Установка и управление загрузчиками: Forge, Fabric, OptiFine, NeoForge.
 */
public class ModLoaderService {

    public enum Loader {
        VANILLA, FORGE, FABRIC, OPTIFINE, NEOFORGE,
        /** Standalone custom server cores — launched the same way as vanilla (java -jar X nogui). */
        PAPER, PURPUR, PUFFERFISH
    }

    private final Path gameDir;
    private final Path librariesDir;
    private final VersionDownloadService vds;
    private String javaOverride;

    public ModLoaderService(Path gameDir, VersionDownloadService vds) {
        this.gameDir = gameDir;
        this.librariesDir = gameDir.resolve("libraries");
        this.vds = vds;
    }

    /** Lets the caller point installer subprocesses at a specific java executable (e.g. the
     *  one MainController already resolved for the target MC version) instead of falling back
     *  to this process's own java.home, which - in the packaged app-image build - is a
     *  jlink-trimmed runtime that has no bin/java.exe at all. */
    public void overrideJavaPath(String javaExe) {
        this.javaOverride = javaExe;
    }

    private String resolveJavaExe() {
        if (javaOverride != null && !javaOverride.isEmpty()) return javaOverride;
        String javaExe = System.getProperty("java.home")
                + java.io.File.separator + "bin" + java.io.File.separator + "java";
        if (System.getProperty("os.name").toLowerCase().contains("win")) javaExe += ".exe";
        return javaExe;
    }

    // ---------------------------------------------------------------
    //  Установка
    // ---------------------------------------------------------------
    public void installLoader(String mcVersion, Loader loader, String loaderVersion, LogCallback log)
            throws Exception {
        if (loader == Loader.VANILLA || loaderVersion == null || loaderVersion.isEmpty()) {
            log.onLog("[LOADER] Vanilla mode, nothing to install.");
            return;
        }
        switch (loader) {
            case FORGE    -> installForge(mcVersion, loaderVersion, log);
            case FABRIC   -> installFabric(mcVersion, loaderVersion, log);
            case OPTIFINE -> installOptiFine(mcVersion, loaderVersion, log);
            case NEOFORGE -> installNeoForge(mcVersion, loaderVersion, log);
            default -> log.onLog("[LOADER] Unknown: " + loader);
        }
    }

    // -------------------- FORGE --------------------
    private void installForge(String mc, String forgeVer, LogCallback log) throws Exception {
        String combined = mc + "-forge-" + forgeVer;
        log.onLog("[FORGE] " + combined);
        String url = vds.getForgeInstallerUrl(mc, forgeVer);
        Path jar = librariesDir.resolve("forge/forge-" + mc + "-" + forgeVer + "-installer.jar");
        ensureInstallerJar(url, jar, log);
        if (!installLegacyForge(jar, mc, combined, log)) {
            log.onLog("[FORGE] Running installer...");
            runInstaller(jar, log);
        }

        Path vDir = gameDir.resolve("versions").resolve(combined);
        Path json = vDir.resolve(combined + ".json");
        String mcMain = "net.minecraftforge.bootstrap.ForgeBootstrap";
        if (Files.exists(json)) {
            JsonObject obj = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
            if (obj.has("mainClass")) mcMain = obj.get("mainClass").getAsString();
        }
        Files.writeString(librariesDir.resolve("forge/" + combined + ".mainclass"), mcMain);
        log.onLog("[FORGE] mainClass = " + mcMain);
    }

    /**
     * Старый Forge (1.7.10, 1.8.9 и прочие до перехода установщика на новый формат) ставится без
     * запуска установщика. Такие установщики не знают ключа --installClient — у них только окно:
     * 14.10.2026 прогон «новый игрок» дал на Forge 1.7.10 и 1.8.9 «UnrecognizedOptionException:
     * 'installClient' is not a recognized option», код 1, игра не запускалась вовсе.
     *
     * <p>Всё нужное лежит в самом установщике: install_profile.json (versionInfo — готовое
     * описание версии, install — где внутри jar Forge и под каким maven-именем его класть). Так их
     * ставят и MultiMC/Prism. Возвращает false для установщика нового формата — тот запускается
     * как раньше.
     */
    private boolean installLegacyForge(Path installerJar, String mc, String combined, LogCallback log) throws Exception {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(installerJar.toFile())) {
            java.util.zip.ZipEntry profileEntry = zip.getEntry("install_profile.json");
            if (profileEntry == null) return false;
            JsonObject profile;
            try (InputStream in = zip.getInputStream(profileEntry)) {
                profile = JsonParser.parseString(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                        .getAsJsonObject();
            }
            if (!profile.has("versionInfo") || !profile.has("install")) return false;   // новый формат
            JsonObject install = profile.getAsJsonObject("install");
            JsonObject versionInfo = profile.getAsJsonObject("versionInfo");
            log.onLog("[FORGE] Старый установщик — ставлю без него (install_profile.json)");

            // 1. jar Forge — внутри установщика, под maven-именем из install.path.
            String filePath = install.get("filePath").getAsString();
            Path forgeJar = mavenToPath(install.get("path").getAsString());
            java.util.zip.ZipEntry universal = zip.getEntry(filePath);
            if (forgeJar == null || universal == null) {
                throw new IOException("В установщике Forge нет " + filePath);
            }
            Files.createDirectories(forgeJar.getParent());
            Path part = forgeJar.resolveSibling(forgeJar.getFileName() + ".part");
            try (InputStream in = zip.getInputStream(universal)) {
                Files.copy(in, part, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(part, forgeJar, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            // 2. Библиотеки. У каждой свой репозиторий в "url" (пусто — Mojang); "clientreq": false —
            //    только для сервера. Старые адреса files.minecraftforge.net переехали на maven.
            com.google.gson.JsonArray keep = new com.google.gson.JsonArray();
            com.google.gson.JsonArray toFetch = new com.google.gson.JsonArray();
            for (var el : versionInfo.getAsJsonArray("libraries")) {
                if (!el.isJsonObject()) continue;
                JsonObject lib = el.getAsJsonObject();
                if (!lib.has("name")) continue;
                if (lib.has("clientreq") && !lib.get("clientreq").getAsBoolean()) continue;
                keep.add(lib);
                String name = lib.get("name").getAsString();
                Path libPath = mavenToPath(name);
                if (libPath == null) continue;
                String base = lib.has("url") && !lib.get("url").getAsString().isBlank()
                        ? lib.get("url").getAsString() : "https://libraries.minecraft.net/";
                base = base.replace("http://files.minecraftforge.net/maven/", "https://maven.minecraftforge.net/");
                JsonObject fetch = new JsonObject();
                fetch.addProperty("name", name);
                fetch.addProperty("url", mavenToUrl(name, base));
                fetch.addProperty("path", librariesDir.relativize(libPath).toString().replace('\\', '/'));
                toFetch.add(fetch);
            }
            LibResult res = downloadLibraryList(toFetch, log, null);
            if (!res.complete()) {
                throw new IOException("Не скачались библиотеки Forge: " + res.downloaded() + " из " + res.attempted()
                        + ". Проверьте интернет и нажмите «Играть» ещё раз.");
            }

            // 3. Описание версии — под тем именем, которое ищет лаунчер, поверх ванильной.
            versionInfo.addProperty("id", combined);
            if (!versionInfo.has("inheritsFrom")) versionInfo.addProperty("inheritsFrom", mc);
            versionInfo.add("libraries", keep);
            Path vDir = gameDir.resolve("versions").resolve(combined);
            Files.createDirectories(vDir);
            Files.writeString(vDir.resolve(combined + ".json"), versionInfo.toString());
            log.onLog("[FORGE] Установлен: " + keep.size() + " библиотек, jar " + forgeJar.getFileName());
            return true;
        }
    }

    // -------------------- FABRIC --------------------
    private void installFabric(String mc, String fabricVer, LogCallback log) throws Exception {
        log.onLog("[FABRIC] " + fabricVer + " for MC " + mc);
        Path profDir = librariesDir.resolve("fabric");
        Files.createDirectories(profDir);
        Path profJson = profDir.resolve("fabric-" + mc + ".json");
        // Маркер от ПРОШЛОЙ установки убираем до того, как перезапишем профиль. Он один на версию
        // игры, а профиль ниже уже новый: 19.10.2026 (e0f4dc81, Fabric 1.21.4) установка
        // оборвалась на ConnectException, старый маркер остался, следующее «Играть» сочло Fabric
        // установленным и запустило игру по новому профилю без его библиотек — ClassNotFoundException.
        Files.deleteIfExists(profDir.resolve("fabric-" + mc + ".mainclass"));
        JsonObject profile;
        try {
            profile = vds.getFabricProfileJson(mc, fabricVer);
            Files.writeString(profJson, profile.toString());
        } catch (IOException e) {
            // meta.fabricmc.net не ответил, но профиль этой самой сборки уже лежит на диске
            // (прошлая установка оборвалась на библиотеках) — доустанавливаем по нему.
            if (!fabricVer.equals(vds.installedFabricLoader(mc))) throw e;
            log.onLog("[FABRIC] meta.fabricmc.net не отвечает — беру сохранённый профиль " + fabricVer);
            profile = com.google.gson.JsonParser.parseString(Files.readString(profJson)).getAsJsonObject();
        }

        if (profile.has("libraries")) {
            var libs = profile.getAsJsonArray("libraries");
            log.onLog("[FABRIC] " + libs.size() + " libraries");
            LibResult res = downloadLibraryList(libs, log, "https://maven.fabricmc.net");
            log.onLog("[FABRIC] Downloaded " + res.downloaded() + " of " + res.attempted());
            // Маркер — единственный признак, по которому запуск решает, установлен загрузчик или
            // нет (resolveMainClass/isLoaderInstalled). Писать его при неполной загрузке нельзя:
            // раньше обрыв сети оставлял Fabric без части библиотек, маркер при этом появлялся,
            // в логе было «установлен», игра падала с NoClassDefFoundError, и все последующие
            // запуски пропускали установщик — починить можно было только удалив libraries/fabric
            // руками.
            if (!res.complete()) {
                throw new IOException("Fabric установлен не полностью: не скачано "
                        + res.failed() + " из " + res.attempted()
                        + " библиотек. Проверьте интернет и повторите установку.");
            }
        }
        String mainClass = profile.has("mainClass") ? profile.get("mainClass").getAsString()
                                                     : "net.fabricmc.loader.impl.launch.knot.KnotClient";
        Files.writeString(profDir.resolve("fabric-" + mc + ".mainclass"), mainClass);
        log.onLog("[FABRIC] mainClass = " + mainClass);
    }

    // -------------------- OPTIFINE --------------------
    // OptiFine ships no public headless install flag on its GUI Main-Class
    // (optifine.InstallerFrame), but the jar also contains optifine.Installer -
    // the same install logic with no GUI, driven entirely by the OS's default
    // Minecraft directory (APPDATA\.minecraft on Windows, ~/.minecraft elsewhere).
    // We redirect that into a temp dir, let it patch the client jar and write a
    // proper "<mc>-OptiFine_<edition>" version profile there, then copy the
    // result into our own gameDir.
    private void installOptiFine(String mc, String ofVer, LogCallback log) throws Exception {
        log.onLog("[OPTIFINE] " + ofVer + " for MC " + mc);

        String newVersionId = mc + "-OptiFine_" + ofVer;
        Path newVersionJson = gameDir.resolve("versions/" + newVersionId + "/" + newVersionId + ".json");
        if (Files.exists(newVersionJson)) {
            log.onLog("[OPTIFINE] Already installed.");
            writeOptiFineMarker(mc, newVersionJson);
            return;
        }

        String fileName = vds.resolveOptiFineFileName(mc, ofVer);
        String url = "https://optifine.net/download?f=" + fileName;
        Path sourceJar = gameDir.resolve("optifine-installers/" + fileName);
        Files.createDirectories(sourceJar.getParent());
        if (!Files.exists(sourceJar)) {
            log.onLog("[OPTIFINE] Downloading installer...");
            vds.downloadFile(url, sourceJar);
        }

        Path vanillaJar = gameDir.resolve("versions/" + mc + "/" + mc + ".jar");
        Path vanillaJson = gameDir.resolve("versions/" + mc + "/" + mc + ".json");
        if (!Files.exists(vanillaJar) || !Files.exists(vanillaJson)) {
            throw new IOException("Vanilla Minecraft " + mc + " must be downloaded before installing OptiFine");
        }

        Path tempRoot = Files.createTempDirectory("optifine-install-");
        try {
            Path tempVanillaDir = tempRoot.resolve(".minecraft/versions").resolve(mc);
            Files.createDirectories(tempVanillaDir);
            Files.copy(vanillaJar, tempVanillaDir.resolve(mc + ".jar"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.copy(vanillaJson, tempVanillaDir.resolve(mc + ".json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            log.onLog("[OPTIFINE] Running installer...");
            runOptiFineInstaller(sourceJar, tempRoot, log);

            Path producedVersionDir = tempRoot.resolve(".minecraft/versions").resolve(newVersionId);
            Path producedLibsDir = tempRoot.resolve(".minecraft/libraries/optifine");
            if (!Files.exists(producedVersionDir)) {
                throw new IOException("OptiFine installer did not produce version " + newVersionId);
            }

            copyDirectory(producedVersionDir, gameDir.resolve("versions").resolve(newVersionId));
            if (Files.exists(producedLibsDir)) {
                copyDirectory(producedLibsDir, librariesDir.resolve("optifine"));
            }
        } finally {
            deleteRecursively(tempRoot);
        }

        writeOptiFineMarker(mc, newVersionJson);
        log.onLog("[OPTIFINE] Installed.");
    }

    private void writeOptiFineMarker(String mc, Path versionJson) throws IOException {
        String mainClass = "net.minecraft.launchwrapper.Launch";
        if (Files.exists(versionJson)) {
            JsonObject obj = JsonParser.parseString(Files.readString(versionJson)).getAsJsonObject();
            if (obj.has("mainClass")) mainClass = obj.get("mainClass").getAsString();
        }
        Files.createDirectories(librariesDir.resolve("optifine"));
        Files.writeString(librariesDir.resolve("optifine/optifine-" + mc + ".mainclass"), mainClass);
    }

    private void runOptiFineInstaller(Path sourceJar, Path fakeHome, LogCallback log) throws Exception {
        String javaExe = resolveJavaExe();

        ProcessBuilder pb = new ProcessBuilder(
                javaExe, "-Duser.home=" + fakeHome.toAbsolutePath(),
                "-cp", sourceJar.toAbsolutePath().toString(), "optifine.Installer")
                .redirectErrorStream(true);
        pb.environment().put("APPDATA", fakeHome.toAbsolutePath().toString());
        Process p = pb.start();
        p.getOutputStream().close();
        try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
            // Пачками, как в runInstaller: потоковый вывод построчно заваливал бы UI-колбэки.
            StringBuilder batch = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                batch.append(line).append('\n');
                if (batch.length() >= 2048) {
                    final String chunk = batch.toString();
                    log.onLog(chunk);
                    batch.setLength(0);
                }
            }
            if (batch.length() > 0) log.onLog(batch.toString());
        }
        boolean finished = p.waitFor(3, java.util.concurrent.TimeUnit.MINUTES);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("OptiFine installer timed out");
        }
        int exit = p.exitValue();
        if (exit != 0) throw new IOException("OptiFine installer exit code " + exit);
    }

    private void copyDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        try (var stream = Files.walk(source)) {
            for (Path src : stream.toList()) {
                Path dest = target.resolve(source.relativize(src));
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }

    // -------------------- NEOFORGE --------------------
    private void installNeoForge(String mc, String neoVer, LogCallback log) throws Exception {
        log.onLog("[NEOFORGE] " + neoVer + " for MC " + mc);
        String url = vds.getNeoForgeInstallerUrl(mc, neoVer);
        Path jar = librariesDir.resolve("neoforge/neoforge-" + neoVer + "-installer.jar");
        ensureInstallerJar(url, jar, log);
        log.onLog("[NEOFORGE] Running installer...");
        runInstaller(jar, log);

        // Поиск созданной папки
        String folder = vds.findNeoForgeVersionFolder(gameDir, neoVer);
        if (folder == null) throw new IOException("NeoForge folder not found for " + neoVer);
        log.onLog("[NEOFORGE] Version folder: " + folder);

        Path json = gameDir.resolve("versions/" + folder + "/" + folder + ".json");
        String mcMain = "cpw.mods.bootstraplauncher.BootstrapLauncher";
        if (Files.exists(json)) {
            JsonObject obj = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
            if (obj.has("mainClass")) mcMain = obj.get("mainClass").getAsString();
        }
        Files.writeString(librariesDir.resolve("neoforge/" + folder + ".mainclass"), mcMain);
        log.onLog("[NEOFORGE] mainClass = " + mcMain);
    }

    // ---------------------------------------------------------------
    //  Server-side install (mirrors installLoader, but produces a
    //  self-contained dedicated-server folder instead of patching
    //  this launcher's own client install).
    // ---------------------------------------------------------------

    /** Installs (if not already present) whatever this loader needs to run a dedicated server
     *  for {@code mcVersion} into {@code serverDir}. OptiFine has no server-side component, so
     *  that case falls back to a plain vanilla server - the caller's log will say so. */
    public void installServerLoader(String mcVersion, Loader loader, String loaderVersion,
                                     Path serverDir, LogCallback log) throws Exception {
        Files.createDirectories(serverDir);
        switch (loader) {
            case VANILLA -> {
                log.onLog("[SERVER] Vanilla " + mcVersion);
                vds.downloadServerJar(mcVersion, serverDir.resolve("server.jar"));
            }
            case FORGE -> {
                log.onLog("[SERVER] Forge " + loaderVersion + " for MC " + mcVersion);
                String url = vds.getForgeInstallerUrl(mcVersion, loaderVersion);
                Path jar = librariesDir.resolve("forge/forge-" + mcVersion + "-" + loaderVersion + "-installer.jar");
                ensureInstallerJar(url, jar, log);
                log.onLog("[SERVER] Running Forge server installer...");
                runInstaller(jar, log, "--installServer", serverDir);
            }
            case NEOFORGE -> {
                log.onLog("[SERVER] NeoForge " + loaderVersion + " for MC " + mcVersion);
                String url = vds.getNeoForgeInstallerUrl(mcVersion, loaderVersion);
                Path jar = librariesDir.resolve("neoforge/neoforge-" + loaderVersion + "-installer.jar");
                ensureInstallerJar(url, jar, log);
                log.onLog("[SERVER] Running NeoForge server installer...");
                runInstaller(jar, log, "--installServer", serverDir);
            }
            case FABRIC -> installFabricServer(mcVersion, loaderVersion, serverDir, log);
            case OPTIFINE -> {
                log.onLog("[SERVER] OptiFine has no server-side component - starting a plain vanilla server for MC "
                        + mcVersion + " instead.");
                vds.downloadServerJar(mcVersion, serverDir.resolve("server.jar"));
            }
            case PAPER -> {
                log.onLog("[SERVER] Paper " + mcVersion);
                vds.downloadPaperServerJar(mcVersion, serverDir.resolve("paper.jar"));
            }
            case PURPUR -> {
                log.onLog("[SERVER] Purpur " + mcVersion);
                vds.downloadPurpurServerJar(mcVersion, serverDir.resolve("purpur.jar"));
            }
            case PUFFERFISH -> {
                log.onLog("[SERVER] Pufferfish " + mcVersion);
                vds.downloadPufferfishServerJar(mcVersion, serverDir.resolve("pufferfish.jar"));
            }
        }
    }

    /** True if installServerLoader has already produced a runnable server in serverDir. */
    public boolean isServerLoaderInstalled(Loader loader, Path serverDir) {
        return switch (loader) {
            case VANILLA, OPTIFINE -> Files.exists(serverDir.resolve("server.jar"));
            case FORGE, NEOFORGE -> Files.exists(serverDir.resolve("run.bat")) || Files.exists(serverDir.resolve("run.sh"));
            case FABRIC -> Files.exists(serverDir.resolve("fabric-server-launch.jar"));
            case PAPER -> Files.exists(serverDir.resolve("paper.jar"));
            case PURPUR -> Files.exists(serverDir.resolve("purpur.jar"));
            case PUFFERFISH -> Files.exists(serverDir.resolve("pufferfish.jar"));
        };
    }

    // -------------------- FABRIC SERVER --------------------
    // Unlike the client side (which talks to the Fabric Meta API directly, no installer jar
    // needed), a Fabric dedicated server needs the standalone installer jar's "server"
    // subcommand to lay down fabric-server-launch.jar + libraries/.
    private void installFabricServer(String mc, String loaderVer, Path serverDir, LogCallback log) throws Exception {
        log.onLog("[SERVER] Fabric " + loaderVer + " for MC " + mc);
        String url = vds.getFabricInstallerUrl();
        Path jar = librariesDir.resolve("fabric/fabric-installer.jar");
        Files.createDirectories(jar.getParent());
        if (!Files.exists(jar)) vds.downloadFile(url, jar);

        String javaExe = resolveJavaExe();

        log.onLog("[SERVER] Running Fabric server installer...");
        Process p = new ProcessBuilder(
                javaExe, "-jar", jar.toAbsolutePath().toString(),
                "server", "-mcversion", mc, "-loader", loaderVer,
                "-dir", serverDir.toAbsolutePath().toString(), "-downloadMinecraft")
                .directory(serverDir.toFile()).redirectErrorStream(true).start();
        p.getOutputStream().close();
        try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
            StringBuilder batch = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                batch.append(line).append('\n');
                if (batch.length() >= 2048) {
                    final String chunk = batch.toString();
                    log.onLog(chunk);
                    batch.setLength(0);
                }
            }
            if (batch.length() > 0) log.onLog(batch.toString());
        }
        boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("Fabric server installer timed out after 5 minutes");
        }
        int exit = p.exitValue();
        if (exit != 0) throw new IOException("Fabric server installer exit code " + exit);
    }

    // ---------------------------------------------------------------
    //  resolveMainClass
    // ---------------------------------------------------------------
    public String resolveMainClass(String versionId, String modLoader, String loaderVersion) {
        if (modLoader == null || modLoader.isEmpty() || "vanilla".equals(modLoader)) {
            // "net.minecraft.client.main.Main" only exists from ~1.6 onward - older versions
            // (launchwrapper era and earlier) declare a different mainClass in their own
            // version JSON (e.g. 1.0 uses "net.minecraft.launchwrapper.Launch"). Hardcoding the
            // modern class here made every legacy version fail with "Could not find or load
            // main class net.minecraft.client.main.Main".
            String fromJson = readMainClassFromVersionDir(versionId);
            return fromJson != null ? fromJson : "net.minecraft.client.main.Main";
        }

        Path marker = switch (modLoader) {
            case "forge" -> {
                Path m = librariesDir.resolve("forge/" + versionId + ".mainclass");
                if (!Files.exists(m) && loaderVersion != null)
                    m = librariesDir.resolve("forge/" + versionId + "-forge-" + loaderVersion + ".mainclass");
                yield m;
            }
            case "neoforge" -> {
                Path m = librariesDir.resolve("neoforge/" + versionId + ".mainclass");
                if (!Files.exists(m) && loaderVersion != null)
                    m = librariesDir.resolve("neoforge/neoforge-" + loaderVersion + ".mainclass");
                yield m;
            }
            case "fabric"   -> librariesDir.resolve("fabric/fabric-" + versionId + ".mainclass");
            case "optifine" -> librariesDir.resolve("optifine/optifine-" + versionId + ".mainclass");
            default -> null;
        };

        if (marker != null && Files.exists(marker)) {
            try { return Files.readString(marker).trim(); } catch (IOException ignored) {}
        }
        String fromJson = readMainClassFromVersionDir(versionId);
        return fromJson != null ? fromJson : "net.minecraft.client.main.Main";
    }

    /**
     * Fabric для этой версии игры стоит целиком: маркер есть, профиль той же сборки загрузчика,
     * и все библиотеки из профиля лежат на диске. Только маркера мало — он один на версию игры и
     * переживает смену сборки загрузчика (см. installFabric), а библиотеки могли удалить руками
     * или антивирусом. Для остальных загрузчиков — прежняя проверка по маркеру.
     */
    public boolean isLoaderReady(String versionId, String modLoader, String loaderVersion) {
        if (!"fabric".equals(modLoader)) {
            return !"net.minecraft.client.main.Main".equals(resolveMainClass(versionId, modLoader, loaderVersion));
        }
        Path profDir = librariesDir.resolve("fabric");
        if (!Files.exists(profDir.resolve("fabric-" + versionId + ".mainclass"))) return false;
        if (loaderVersion != null && !loaderVersion.isBlank()
                && !loaderVersion.equals(vds.installedFabricLoader(versionId))) return false;
        try {
            JsonObject profile = JsonParser.parseString(
                    Files.readString(profDir.resolve("fabric-" + versionId + ".json"))).getAsJsonObject();
            if (!profile.has("libraries")) return true;
            for (var el : profile.getAsJsonArray("libraries")) {
                if (!el.isJsonObject() || !el.getAsJsonObject().has("name")) continue;
                Path lib = mavenToPath(el.getAsJsonObject().get("name").getAsString());
                if (lib != null && (!Files.exists(lib) || Files.size(lib) == 0)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String readMainClassFromVersionDir(String versionId) {
        try {
            Path p = gameDir.resolve("versions/" + versionId + "/" + versionId + ".json");
            if (!Files.exists(p)) return null;
            JsonObject obj = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            return obj.has("mainClass") ? obj.get("mainClass").getAsString() : null;
        } catch (Exception ignored) { return null; }
    }

    // ---------------------------------------------------------------
    //  extra classpath
    // ---------------------------------------------------------------
    public List<Path> getExtraClasspath(String versionId, String modLoader, String loaderVersion) {
        List<Path> extras = new ArrayList<>();
        if ("fabric".equals(modLoader)) {
            Path d = librariesDir.resolve("fabric");
            if (Files.exists(d)) {
                try (var s = Files.list(d)) {
                    s.filter(p -> p.toString().endsWith(".jar")).sorted().forEach(extras::add);
                } catch (IOException ignored) {}
            }
        }
        // forge/neoforge/optifine — всё уже в version JSON (включая inheritsFrom)
        return extras;
    }

    // ---------------------------------------------------------------
    //  Утилиты
    // ---------------------------------------------------------------
    private static final int LIB_DOWNLOAD_THREADS = 16;

    /** Same one-file-at-a-time-was-too-slow reasoning as VersionDownloadService.downloadLibraries()
     *  — Fabric/Quilt profiles can list 40-80 libraries, and doing them sequentially means paying
     *  full per-request round-trip latency (proxy or otherwise) that many times over. */
    /** Сколько библиотек пытались скачать и сколько получилось — чтобы вызывающий мог отличить
     *  полную установку от частичной, а не считать успехом сам факт возврата. */
    record LibResult(int attempted, int downloaded) {
        int failed() { return attempted - downloaded; }
        boolean complete() { return downloaded >= attempted; }
    }

    private LibResult downloadLibraryList(com.google.gson.JsonArray libs, LogCallback log, String mavenBase) throws Exception {
        record LibDownload(String url, Path dest, String sha1) {}
        List<LibDownload> toDownload = new ArrayList<>();
        for (var el : libs) {
            if (!el.isJsonObject()) continue;
            JsonObject lib = el.getAsJsonObject();
            String name = lib.has("name") ? lib.get("name").getAsString() : null;
            Path libPath = null;
            String url = null;
            // Fabric Meta с 2024 года отдаёт sha1 у каждой библиотеки — сверяем, если он есть,
            // чтобы оборванный посреди тела ответ не лёг в libraries/ как «уже скачано».
            String sha1 = lib.has("sha1") && lib.get("sha1").isJsonPrimitive() ? lib.get("sha1").getAsString() : null;

            if (lib.has("downloads") && lib.getAsJsonObject("downloads").has("artifact")) {
                JsonObject art = lib.getAsJsonObject("downloads").getAsJsonObject("artifact");
                url = art.get("url").getAsString();
                libPath = librariesDir.resolve(art.get("path").getAsString());
                if (art.has("sha1")) sha1 = art.get("sha1").getAsString();
            } else if (lib.has("url") && lib.has("path")) {
                url = lib.get("url").getAsString();
                libPath = librariesDir.resolve(lib.get("path").getAsString());
            } else if (name != null) {
                libPath = mavenToPath(name);
                url = mavenToUrl(name, mavenBase);
            }

            if (libPath != null && url != null && !Files.exists(libPath)) {
                toDownload.add(new LibDownload(url, libPath, sha1));
            }
        }

        if (toDownload.isEmpty()) return new LibResult(0, 0);

        java.util.concurrent.atomic.AtomicInteger cnt = new java.util.concurrent.atomic.AtomicInteger(0);
        List<LibDownload> failed = java.util.Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(LIB_DOWNLOAD_THREADS, toDownload.size()),
                r -> {
                    // daemon: прерывание не разрывает блокирующее чтение сокета, и не-daemon
                    // поток не давал JVM выйти — окно закрыто, а javaw.exe остался качать.
                    Thread t = new Thread(r, "lib-download");
                    t.setDaemon(true);
                    return t;
                });
        // shutdown в finally: раньше он был последней инструкцией счастливого пути, поэтому
        // любое прерывание или исключение из колбэка оставляло пул живым.
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (LibDownload lib : toDownload) {
                futures.add(pool.submit(() -> {
                    try {
                        Files.createDirectories(lib.dest().getParent());
                        vds.downloadFile(lib.url(), lib.dest(), lib.sha1());
                        cnt.incrementAndGet();
                    } catch (Exception e) {
                        failed.add(lib);
                        log.onLog("[LIB] Failed: " + e.getMessage());
                    }
                }));
            }
            Duration perLibDeadline = Duration.ofSeconds(120);
            for (Future<?> f : futures) {
                try {
                    f.get(perLibDeadline.toMillis(), TimeUnit.MILLISECONDS);
                } catch (ExecutionException ignored) {
                } catch (TimeoutException e) {
                    // cancel(false): a running download is bounded by its own connect/read timeouts,
                    // so interrupting it buys nothing; this only stops tasks that never started.
                    f.cancel(false);
                }
            }
        } finally {
            pool.shutdown();
        }
        // Второй заход по одной, с паузами. 16 параллельных соединений на рваной сети теряют
        // сразу несколько файлов, а по одному те же файлы обычно докачиваются — раньше из-за
        // двух-трёх недокачанных библиотек падала вся установка, и игроку оставалось только
        // жать «Играть» снова и снова (c2e80bea, 16.10.2026: пять «phase=loader» подряд).
        List<LibDownload> retry;
        synchronized (failed) {
            retry = new ArrayList<>(failed);
        }
        if (!retry.isEmpty()) {
            log.onLog("[LIB] Докачиваю по одной: " + retry.size());
            for (LibDownload lib : retry) {
                if (Thread.currentThread().isInterrupted()) break;
                try {
                    vds.downloadFileWithRetries(lib.url(), lib.dest(), 3, lib.sha1());
                    cnt.incrementAndGet();
                } catch (Exception e) {
                    log.onLog("[LIB] Не скачалась и со второго раза: " + lib.dest().getFileName());
                }
            }
        }
        return new LibResult(toDownload.size(), cnt.get());
    }

    private Path mavenToPath(String artifact) {
        String[] p = artifact.split(":");
        if (p.length < 3) return null;
        String group = p[0].replace('.', '/');
        String file = p[1] + "-" + p[2] + ".jar";
        return librariesDir.resolve(group + "/" + p[1] + "/" + p[2] + "/" + file);
    }

    private String mavenToUrl(String artifact, String base) {
        if (artifact == null || base == null) return null;
        String[] p = artifact.split(":");
        if (p.length < 3) return null;
        String group = p[0].replace('.', '/');
        String file = p[1] + "-" + p[2] + ".jar";
        String b = base.endsWith("/") ? base : base + "/";
        return b + group + "/" + p[1] + "/" + p[2] + "/" + file;
    }

    private void runInstaller(Path jar, LogCallback log) throws Exception {
        Path profiles = gameDir.resolve("launcher_profiles.json");
        if (!Files.exists(profiles)) Files.writeString(profiles, "{\"profiles\":{}}");
        runInstaller(jar, log, "--installClient", gameDir);
    }

    /**
     * Makes sure {@code dest} holds a usable copy of the installer jar at {@code url}.
     *
     * <p>A plain {@code Files.exists()} check is not enough: an interrupted download (or a
     * previously failed attempt) leaves a file behind that is not a jar at all, and the install
     * then fails forever with "Invalid or corrupt jarfile" / "no main manifest attribute" - the
     * cached stub is never re-fetched, so retrying the launch changes nothing. A file that is not
     * a readable zip is therefore deleted and downloaded again, with retries.
     */
    private void ensureInstallerJar(String url, Path dest, LogCallback log) throws Exception {
        Files.createDirectories(dest.getParent());
        if (Files.exists(dest) && !isReadableJar(dest)) {
            log.onLog("[LOADER] Файл установщика повреждён, скачиваю заново: " + dest.getFileName());
            Files.deleteIfExists(dest);
        }
        if (!Files.exists(dest)) {
            log.onLog("[LOADER] Скачивание установщика: " + dest.getFileName());
            try {
                vds.downloadFileWithRetries(url, dest, 3);
            } catch (IOException e) {
                Files.deleteIfExists(dest);
                throw new IOException("Не удалось скачать установщик " + url + ": " + e.getMessage(), e);
            }
        }
        if (!isReadableJar(dest)) {
            Files.deleteIfExists(dest);
            throw new IOException("Скачанный установщик повреждён: " + url);
        }
    }

    private boolean isReadableJar(Path path) {
        try (JarFile jar = new JarFile(path.toFile())) {
            return jar.size() > 0;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }

    /** Forge/NeoForge's installer jar accepts "--installClient <dir>" (patches this launcher's
     *  own install) or "--installServer <dir>" (builds a self-contained dedicated server in
     *  dir, complete with a generated run.bat/run.sh) - same jar, same flow, different flag. */
    private void runInstaller(Path jar, LogCallback log, String installFlag, Path targetDir) throws Exception {
        Files.createDirectories(targetDir);
        String javaExe = resolveJavaExe();
        if (!Files.exists(Path.of(javaExe))) {
            // The packaged app-image runtime has no bin/java.exe at all (jpackage strips it), so
            // every installer run depends on the caller having pointed us at a real JDK - say so
            // instead of surfacing a bare "CreateProcess error=2".
            throw new IOException("Java для запуска установщика не найдена: " + javaExe
                    + ". Укажите путь к Java в настройках лаунчера.");
        }

        Process p = new ProcessBuilder(
                javaExe, "-jar", jar.toAbsolutePath().toString(),
                installFlag, targetDir.toAbsolutePath().toString())
                .directory(targetDir.toFile()).redirectErrorStream(true).start();
        // Close stdin immediately: some installer builds probe stdin for input and
        // block forever waiting for it if the pipe is left open with nothing written.
        p.getOutputStream().close();
        StringBuilder tail = new StringBuilder();
        // Сторож «нет вывода N минут». Таймаут ниже (waitFor) начинает считаться только после
        // того, как установщик закрыл вывод, а readLine ждёт без конца: 13.10.2026 у игрока
        // Forge 1.19.2 молча «устанавливался» 23 минуты, пока он не закрыл лаунчер. Считаем
        // тишину, а не общее время: на медленной сети Forge честно качает библиотеки 10+ минут,
        // но при этом печатает строку на каждый файл.
        final long silenceLimitMs = 5 * 60_000L;
        final java.util.concurrent.atomic.AtomicLong lastOutput =
                new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
        final java.util.concurrent.atomic.AtomicBoolean hung = new java.util.concurrent.atomic.AtomicBoolean();
        Thread watchdog = new Thread(() -> {
            try {
                while (p.isAlive()) {
                    Thread.sleep(5_000);
                    if (System.currentTimeMillis() - lastOutput.get() > silenceLimitMs) {
                        hung.set(true);
                        p.destroyForcibly();   // закроет поток вывода — readLine вернёт null
                        return;
                    }
                }
            } catch (InterruptedException ignored) {
            }
        }, "loader-installer-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
        try (var r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
            // Установщик печатает сотни строк; отправлять каждую отдельным колбэком — значит
            // завалить FX-очередь (колбэк обычно делает Platform.runLater), и лаунчер выглядит
            // замороженным, пока установка идёт. Копим и отдаём пачками.
            StringBuilder batch = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                lastOutput.set(System.currentTimeMillis());
                batch.append(line).append('\n');
                tail.append(line).append('\n');
                if (tail.length() > 4000) tail.delete(0, tail.length() - 4000);
                if (batch.length() >= 2048) {
                    final String chunk = batch.toString();
                    log.onLog(chunk);
                    batch.setLength(0);
                }
            }
            if (batch.length() > 0) log.onLog(batch.toString());
        } finally {
            watchdog.interrupt();
        }
        if (hung.get()) {
            String details = tail.toString().trim();
            throw new IOException("Установщик завис: " + (silenceLimitMs / 60_000)
                    + " минут без вывода — скорее всего, не может скачать библиотеки."
                    + " Проверьте интернет (или VPN) и нажмите «Играть» ещё раз"
                    + (details.isEmpty() ? "." : ". Последнее, что он написал:\n" + details));
        }
        boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
        if (!finished) {
            p.destroyForcibly();
            throw new IOException("Installer timed out after 5 minutes");
        }
        int exit = p.exitValue();
        if (exit != 0) {
            // The installer's own last lines name the actual cause (checksum mismatch, offline,
            // missing vanilla jar, ...) - without them the user only ever saw an exit code.
            String details = tail.toString().trim();
            throw new IOException("Установщик завершился с кодом " + exit
                    + (details.isEmpty() ? "" : ":\n" + details));
        }
    }

    @FunctionalInterface
    public interface LogCallback { void onLog(String msg); }
}