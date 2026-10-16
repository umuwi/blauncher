package com.example.launcher.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Checks blauncher.ru (a DOMAIN, deliberately — see the javadoc on MANIFEST_URL below) for a
 * newer launcher version, downloads it, verifies its checksum, and replaces the currently
 * running launcher with it via a small generated batch script (Windows can't let a running
 * process overwrite its own .exe/.jar — see {@link #buildSelfReplaceScript}).
 *
 * This never blocks or auto-applies anything on its own — MainController drives the actual
 * "check → show changelog modal → Update/Later" flow and decides when to call
 * {@link #downloadAndVerify} / {@link #applyUpdateAndRestart}.
 */
public class UpdateService {

    /**
     * A domain, NOT a hardcoded IP — a bare VPS IP would break the moment that server is
     * migrated, its host changes, or it sits behind something like Cloudflare (whose edge IPs
     * rotate routinely). A domain with a short DNS TTL gives the same "can move to a different
     * server at any time" flexibility as a dynamic IP would, without ever requiring a new
     * launcher build just because the update server's address changed — the domain is the
     * only address baked into the client at all.
     */
    private static final String MANIFEST_URL = "https://blauncher.ru/update/manifest_pc.json";

    /** Parsed result of GET manifest.json — see MANIFEST_URL's format in AGENT_API.md-style docs (README). */
    public static final class UpdateManifest {
        public final String latestVersion;
        public final String changelog;
        public final String downloadUrlExe;
        public final String downloadUrlJar;
        public final String sha256Exe;
        public final String sha256Jar;

        UpdateManifest(String latestVersion, String changelog, String downloadUrlExe, String downloadUrlJar,
                       String sha256Exe, String sha256Jar) {
            this.latestVersion = latestVersion;
            this.changelog = changelog;
            this.downloadUrlExe = downloadUrlExe;
            this.downloadUrlJar = downloadUrlJar;
            this.sha256Exe = sha256Exe;
            this.sha256Jar = sha256Jar;
        }
    }

    /**
     * Fetches and parses the update manifest. Returns null (not an exception) on any network
     * failure — a failed update check should never block or interrupt normal launcher use;
     * the caller just silently skips showing the update modal for this session.
     */
    public UpdateManifest checkForUpdate() {
        try {
            Http.Response response = Http.get(MANIFEST_URL, Map.of(), Duration.ofSeconds(10));
            if (!response.ok()) {
                return null;
            }
            JsonObject obj = JsonParser.parseString(response.text()).getAsJsonObject();
            return new UpdateManifest(
                    getOrNull(obj, "latestVersion"),
                    getOrNull(obj, "changelog"),
                    getOrNull(obj, "downloadUrl"),
                    getOrNull(obj, "downloadUrlJar"),
                    getOrNull(obj, "sha256"),
                    getOrNull(obj, "sha256Jar")
            );
        } catch (Exception e) {
            return null;
        }
    }

    private static String getOrNull(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    /** True if `remoteVersion` (e.g. "v1.5") is newer than `currentVersion` (e.g. "v1.4") —
     *  compares the numeric parts after stripping a leading "v", so "v1.10" correctly beats
     *  "v1.9" (a naive string compare would get that backwards). Any unparsable version is
     *  treated as "not newer" rather than throwing, so a malformed manifest can't crash the
     *  update check — it just silently reports no update available. */
    public boolean isNewer(String remoteVersion, String currentVersion) {
        try {
            int[] remote = parseVersion(remoteVersion);
            int[] current = parseVersion(currentVersion);
            int len = Math.max(remote.length, current.length);
            for (int i = 0; i < len; i++) {
                int r = i < remote.length ? remote[i] : 0;
                int c = i < current.length ? current[i] : 0;
                if (r != c) return r > c;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private int[] parseVersion(String version) {
        String stripped = version.startsWith("v") || version.startsWith("V") ? version.substring(1) : version;
        String[] parts = stripped.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i].trim());
        }
        return result;
    }

    /**
     * Downloads the given URL to a temp file and verifies its SHA-256 against expectedSha256
     * (case-insensitive hex). Throws if the download fails OR the checksum doesn't match — a
     * self-replacing binary must never proceed on an unverified download, since a truncated or
     * tampered file would otherwise brick the launcher on next start with no way back except
     * re-downloading it manually from the website.
     *
     * @param onProgress called with 0-100 as the download proceeds, may be null
     * @return path to the verified downloaded file, under the system temp directory
     */
    public Path downloadAndVerify(String url, String expectedSha256, Consumer<Integer> onProgress) throws IOException, InterruptedException {
        Path tempDir = Files.createTempDirectory("blauncher-update-");
        String fileName = url.substring(url.lastIndexOf('/') + 1);
        Path destination = tempDir.resolve(fileName);

        // Streamed to disk rather than buffered: the jar is ~18 MB and there is no reason to hold
        // a second copy of it on the heap while the launcher is still running.
        long written = Http.download(url, destination, Map.of(), Duration.ofMinutes(10));
        if (written < 0) {
            throw new IOException("Update download failed: server rejected " + url);
        }
        if (onProgress != null) onProgress.accept(90); // progress is not streamed mid-download — see note below

        // Хеш ОБЯЗАТЕЛЕН. Раньше вся проверка была под условием «если хеш задан», а
        // checkForUpdate превращает отсутствующий ключ sha256Jar в null — то есть манифест без
        // хеша (перегенерированный, с переименованным ключом) означал установку чего угодно,
        // что пришло по сети, прямо поверх живого лаунчера. Для самозаменяющегося бинарника
        // «при сомнении довериться» — неверный дефолт; при сомнении надо отказаться.
        String want = expectedSha256 == null ? "" : expectedSha256.trim();
        if (want.isEmpty()) {
            Files.deleteIfExists(destination);
            throw new IOException("Сервер обновлений не сообщил контрольную сумму для "
                    + url + ", поэтому проверить скачанный файл нельзя. Обновление отменено —"
                    + " скачайте новую версию с сайта вручную.");
        }
        if (want.length() != 64) {
            Files.deleteIfExists(destination);
            throw new IOException("Сервер обновлений сообщил контрольную сумму в непонятном виде ("
                    + want.length() + " символов вместо 64). Обновление отменено.");
        }
        String actual = sha256Hex(destination);
        if (!actual.equalsIgnoreCase(want)) {
            Files.deleteIfExists(destination);
            throw new IOException(
                    "Downloaded update failed checksum verification (expected " + want
                            + ", got " + actual + ") — refusing to install a file that doesn't match "
                            + "what the update server said it should be.");
        }
        if (onProgress != null) onProgress.accept(100);
        return destination;
    }

    private String sha256Hex(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Потоком, а не readAllBytes: файл обновления ~18 МБ, и держать его вторую копию
            // в куче незачем — тем самым соображением мотивирована и загрузка выше.
            try (var in = Files.newInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) digest.update(buf, 0, n);
            }
            byte[] hash = digest.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("Could not compute checksum: " + e.getMessage(), e);
        }
    }

    /**
     * True only for the jpackage app-image layout this launcher can self-replace: an .exe that is
     * NOT the JDK's own java/javaw launcher, with an {@code app\BLauncher.jar} sitting next to it.
     *
     * <p><b>Both halves are load-bearing.</b> BLauncher.bat runs the launcher as
     * {@code javaw.exe --module-path lib -cp build\classes ... com.example.launcher.MainApp}
     * (BLauncher.bat's last line), and {@code ProcessHandle.current().info().command()} therefore
     * reports the JDK's {@code javaw.exe} as this process's own command. A plain
     * {@code endsWith(".exe")} test — which is what the caller used to do — answers "yes, jpackage
     * install" for every single .bat run, sends it down the app-image path, and it dies on the
     * missing app\ folder with <i>"Expected an 'app' folder next to C:\Program Files\Java\jdk-21
     * .0.10\bin\javaw.exe"</i>. That was not an edge case: it made auto-update impossible for
     * every .bat install, always, since .bat is a first-class way to run this launcher (it even
     * compiles the sources itself on first run).
     */
    public static boolean isAppImageInstall(Path exePath) {
        if (exePath == null) return false;
        Path fileName = exePath.getFileName();
        if (fileName == null) return false;
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".exe")) return false;
        if (name.equals("java.exe") || name.equals("javaw.exe")) return false;
        Path parent = exePath.getParent();
        return parent != null && Files.isRegularFile(parent.resolve("app").resolve("BLauncher.jar"));
    }

    /**
     * The directory this launcher's own compiled classes were loaded from — i.e.
     * {@code <install>\build\classes} under the BLauncher.bat layout, which runs the app straight
     * off loose class files rather than a jar. Returns null when the code source is a jar or an
     * app image (nothing to copy class files over) or can't be determined at all, which callers
     * treat as "no in-place update possible, tell the person to replace it by hand".
     */
    public static Path resolveOwnClassesDir() {
        try {
            CodeSource source = UpdateService.class.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return null;
            Path path = Path.of(source.getLocation().toURI());
            return Files.isDirectory(path) ? path : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The BLauncher.bat counterpart of {@link #applyUpdateAndRestart}: unpacks the downloaded
     * BLauncher.jar over the loose class files this launcher is running from, then relaunches via
     * BLauncher.bat and exits.
     *
     * <p>Copying the classes out of the jar (rather than dropping the jar in and pointing at it) is
     * what makes this work for people who <b>already installed</b> an old .bat: their BLauncher.bat
     * hardcodes {@code -cp "build\classes;lib\*"}, and the .bat itself can never be updated by an
     * auto-update that only ships a jar. Writing into build\classes is therefore the only route
     * that reaches an install that's already out there. It's also why the .bat won't rebuild
     * afterwards — it only compiles when {@code build\classes\...\MainApp.class} is missing, and
     * this puts a newer one right there.
     *
     * <p>The jar is fully unpacked and sanity-checked <i>here</i>, while the launcher is still
     * alive and can still show a real error message; the generated script only ever does
     * kill → copy → relaunch, so a bad download can't leave a half-overwritten install behind.
     *
     * <p><b>The sources are replaced too</b>, from {@code META-INF/src/} inside the same jar (see
     * {@link #SRC_PREFIX}). Without that, an update would leave build\classes new and {@code src\}
     * whatever shipped in the person's original zip - and BLauncher.bat compiles from {@code src\}
     * whenever build\classes is missing, including on its explicit {@code BLauncher.bat rebuild}.
     * One "rebuild" (a command support answers hand out freely, and the one the 1.11.6 -> 1.11.7
     * manual step needs) would then silently roll the launcher back to the old version - and,
     * worse, back onto the broken update path that made the manual step necessary in the first
     * place. Keeping the two in lockstep means rebuild always reproduces exactly what is installed.
     */
    public void applyClassesUpdateAndRestart(Path downloadedJar, Path classesDir) throws IOException {
        if (classesDir == null || !Files.isDirectory(classesDir)) {
            throw new IOException("Couldn't work out which folder this launcher is running from — "
                    + "please download the new version from the website and replace this folder manually.");
        }
        Path launcherBat = findLauncherBat(classesDir);
        if (launcherBat == null) {
            throw new IOException("Couldn't find BLauncher.bat above " + classesDir
                    + " — please download the new version from the website manually.");
        }

        Path staging = downloadedJar.getParent().resolve("staging");
        Path srcStaging = downloadedJar.getParent().resolve("staging-src");
        extractJarTo(downloadedJar, staging, srcStaging);
        // A jar that unpacked without MainApp.class isn't the launcher — never let the script
        // copy it over a working install.
        if (!Files.isRegularFile(staging.resolve("com").resolve("example").resolve("launcher").resolve("MainApp.class"))) {
            throw new IOException("The downloaded update doesn't contain com/example/launcher/MainApp.class "
                    + "— refusing to copy it over a working install.");
        }
        Path stagedSources = Files.isRegularFile(
                srcStaging.resolve("main").resolve("java").resolve("com").resolve("example")
                        .resolve("launcher").resolve("MainApp.java"))
                ? srcStaging : null;

        // Copy the new classes into build\classes RIGHT HERE, while this JVM is still alive. The
        // old design spawned a detached apply-update.bat to do this after exit; that meant running
        // cmd.exe from a javaw-hosted JVM, which made Windows pop a brand-new console window (the
        // stray black box users kept seeing). By copying synchronously in-process we eliminate the
        // whole cmd.exe chain — the only child process left is wscript.exe relaunching the launcher
        // hidden, which cannot create a console window.
        copyTree(staging, classesDir);

        // Обновляем и исходники, чтобы BLauncher.bat rebuild собирал именно установленную
        // версию (см. javadoc класса).
        //
        // ПОВЕРХ, а не вместо. Прежний код сносил src\ целиком и копировал заново, и это было
        // плохо дважды. Во-первых, в jar едут только текстовые исходники — java, fxml, css,
        // локализация, палитра; бинарные ресурсы (видео на 12 МБ, фоны, шрифты) не дублируются,
        // иначе jar вырос бы вдвое и это уезжало бы в каждое обновление. При удалении всей папки
        // они бы исчезли, и rebuild собрал бы лаунчер без картинок. Во-вторых, исключение
        // посреди копирования оставляло src\ пустым — а именно оттуда поддержка и предлагает
        // восстанавливаться командой rebuild.
        if (stagedSources != null) {
            Path srcDir = launcherBat.toAbsolutePath().getParent().resolve("src");
            Files.createDirectories(srcDir);
            copyTree(stagedSources, srcDir);
        }

        // Relaunch hidden through BLauncher.vbs if present (wscript = no console window);
        // otherwise fall back to launching BLauncher.bat, which is at least the prior behaviour.
        Path vbs = launcherBat.toAbsolutePath().getParent().resolve("BLauncher.vbs");
        ProcessBuilder pb = Files.isRegularFile(vbs)
                ? new ProcessBuilder("wscript.exe", vbs.toString())
                : new ProcessBuilder("cmd.exe", "/c", "start", "\"\"", "/D", launcherBat.toAbsolutePath().getParent().toString(), "\"" + launcherBat.toAbsolutePath() + "\"");
        // Лок единственного экземпляра отдаём ДО запуска преемника: иначе он может успеть
        // попробовать взять его раньше, чем эта JVM завершится, увидеть занятым и выйти с
        // «BLauncher уже запущен» — человек остался бы вообще без лаунчера.
        com.example.launcher.MainApp.releaseSingleInstanceLock();
        pb.start(); // detached — this relaunch must outlive the exiting JVM

        // Best-effort cleanup of staging temp dirs (the relaunched process is already independent).
        try { deleteRecursively(staging); } catch (IOException ignored) {}
        try { deleteRecursively(srcStaging); } catch (IOException ignored) {}
    }

    /** Recursively copies the contents of {@code from} into {@code to}, replacing existing files. */
    private void copyTree(Path from, Path to) throws IOException {
        if (!Files.isDirectory(from)) return;
        try (var stream = Files.walk(from)) {
            for (Path src : stream.toList()) {
                Path dest = to.resolve(from.relativize(src));
                if (Files.isDirectory(src)) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Recursively deletes a directory tree. */
    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var stream = Files.walk(root)) {
            stream.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }

    /** Walks up from the classes dir looking for BLauncher.bat, which lives at the install root
     *  ({@code <root>\build\classes} -> {@code <root>\BLauncher.bat}, so two levels). Bounded at
     *  four so an unexpected layout can't wander off into the rest of the disk. */
    private Path findLauncherBat(Path classesDir) {
        Path dir = classesDir.toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++) {
            Path candidate = dir.resolve("BLauncher.bat");
            if (Files.isRegularFile(candidate)) return candidate;
            dir = dir.getParent();
        }
        return null;
    }

    /** Where the release jar carries this version's own sources, so an update can keep {@code src\}
     *  in step with build\classes (see applyClassesUpdateAndRestart). Under META-INF/ because that
     *  is the one prefix guaranteed to mean nothing to a classloader — the app-image install
     *  unpacks the very same jar and must not gain a stray top-level "src" package. */
    private static final String SRC_PREFIX = "META-INF/src/";

    /** Unpacks {@code jar}, splitting it in one pass: everything under {@link #SRC_PREFIX} goes to
     *  {@code srcTarget} with that prefix stripped, everything else to {@code classesTarget}.
     *  Entries whose name escapes their target directory (zip-slip) are skipped rather than
     *  trusted — this content came off the network, checksum or not. */
    private void extractJarTo(Path jar, Path classesTarget, Path srcTarget) throws IOException {
        Path classesRoot = classesTarget.toAbsolutePath().normalize();
        Path srcRoot = srcTarget.toAbsolutePath().normalize();
        Files.createDirectories(classesRoot);
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(jar)))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                boolean isSource = name.startsWith(SRC_PREFIX);
                Path root = isSource ? srcRoot : classesRoot;
                String relative = isSource ? name.substring(SRC_PREFIX.length()) : name;
                if (relative.isEmpty()) continue;
                Path dest = root.resolve(relative).normalize();
                if (!dest.startsWith(root)) continue;
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                    continue;
                }
                Path parent = dest.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.copy(zip, dest, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    /**
     * Replaces the currently-running launcher with the verified downloaded file and restarts
     * it, then exits this JVM. Only supports the jpackage .exe install layout (matches how
     * MainApp/AppLauncher/BLauncher.bat already assume the app is installed on Windows) — for
     * a plain-jar dev run, this just tells the person to replace it manually instead of trying
     * to self-modify a file that might not even be at a stable, writable path.
     *
     * <p><b>Important:</b> the downloaded file replaces {@code app/BLauncher.jar} next to the
     * .exe, NOT the .exe itself. Confirmed by inspecting an actual jpackage app-image output:
     * {@code BLauncher.exe} is a ~500KB generic native stub that only knows how to launch
     * whatever's on the classpath declared in {@code app/BLauncher.cfg} — all of this app's own
     * compiled code (every class in {@code com.example.launcher.*}) lives in {@code app/
     * BLauncher.jar} alongside it. Copying a new build onto the .exe itself would silently do
     * nothing: the stub would still load the OLD jar right after "successfully" updating, and
     * nobody would ever see the new code. The exe stub itself never needs to change between
     * releases (it doesn't contain any of our code), so it's never touched here.
     *
     * @param downloadedFile the verified file from downloadAndVerify() — the new BLauncher.jar
     * @param currentExePath the currently-running executable's own path — callers get this
     *                       from ProcessHandle.current().info().command(), which MainController
     *                       resolves once at startup (see AppLauncher/MainApp) rather than this
     *                       service guessing at it.
     */
    public void applyUpdateAndRestart(Path downloadedFile, Path currentExePath) throws IOException {
        // One check, not two: isAppImageInstall() already covers both "is this even an .exe of
        // ours" and "is there an app\BLauncher.jar to replace" — see its javadoc for why the old
        // bare endsWith(".exe") test sent every BLauncher.bat run down this path by mistake.
        if (!isAppImageInstall(currentExePath)) {
            throw new IOException(
                    "This isn't a jpackage .exe install (running as " + currentExePath + "), so there is no "
                            + "app\\BLauncher.jar to replace. Please download the new version manually from the website.");
        }
        Path targetJar = currentExePath.getParent().resolve("app").resolve("BLauncher.jar");

        // The running jpackage app-image is actually TWO OS processes on Windows, not one —
        // confirmed by inspecting a live install with Win32_Process: a small native bootstrap
        // "BLauncher.exe" (launched by explorer.exe) that itself spawns a second, much bigger
        // "BLauncher.exe" child which is the real JVM hosting this code. ProcessHandle.current()
        // (called from Java) only ever sees that CHILD — its parent bootstrap process is
        // invisible to plain "wait for this JVM to exit" logic. That parent can outlive
        // System.exit(0) here, which used to break the self-replace script two ways: it can keep
        // its own handle open on app\BLauncher.jar (the retry-copy loop below exhausts all
        // attempts and gives up, so the exe never gets relaunched), and even when the copy did
        // win the race, a still-dying parent could contend with the freshly relaunched instance
        // over shared state files (servers.json/profiles) just after startup, which is why a
        // second manual relaunch used to be needed to see builds/mods again. Passing both PIDs
        // lets the script force-kill the whole tree before ever attempting the copy, instead of
        // hoping a timeout is long enough.
        long currentPid = ProcessHandle.current().pid();
        long parentPid = ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(-1L);

        Path scriptPath = downloadedFile.getParent().resolve("apply-update.bat");
        String script = buildSelfReplaceScript(downloadedFile, targetJar, currentExePath, currentPid, parentPid);
        Files.writeString(scriptPath, script, StandardCharsets.UTF_8);

        // Same hidden-window trick as applyClassesUpdateAndRestart: launching cmd.exe directly
        // from a javaw-hosted JVM would pop a brand-new console window. Wrapping the .bat in a VBS
        // and running it through wscript.exe keeps the whole update completely silent.
        Path vbsPath = downloadedFile.getParent().resolve("apply-update.vbs");
        Files.writeString(vbsPath,
                "Set fso = CreateObject(\"Scripting.FileSystemObject\")\r\n"
                        + "Set shell = CreateObject(\"WScript.Shell\")\r\n"
                        + "shell.Run \"\"\"" + scriptPath.toAbsolutePath() + "\"\"\", 0, False\r\n"
                        + "WScript.Sleep 1000\r\n"
                        + "fso.DeleteFile WScript.ScriptFullName\r\n",
                StandardCharsets.UTF_8);

        ProcessBuilder pb = new ProcessBuilder("wscript.exe", vbsPath.toString());
        pb.directory(scriptPath.getParent().toFile());
        // Detached — this process must keep running after the launcher's own JVM exits, exactly
        // like the existing pattern for the dedicated server / playit.gg agent elsewhere in this
        // codebase (GameLaunchService/PlayitService both spawn independent OS processes the same way).
        pb.start();
    }

    /**
     * Generates a batch script that: force-kills both PIDs of the running app-image by exact
     * PID (see the comment on the kill lines below for why not "/T"), copies the downloaded jar
     * over targetJar, relaunches the (unchanged) exe stub, then deletes itself. The retry loop
     * around the copy is a safety net for a lock held by something else entirely (antivirus,
     * etc) rather than the primary mechanism for waiting out the old process anymore.
     */
    private String buildSelfReplaceScript(Path downloadedFile, Path targetJar, Path exeToRelaunch,
                                           long currentPid, long parentPid) {
        String downloaded = downloadedFile.toAbsolutePath().toString();
        String target = targetJar.toAbsolutePath().toString();
        String exe = exeToRelaunch.toAbsolutePath().toString();
        String exeDir = exeToRelaunch.toAbsolutePath().getParent().toString();
        // Force-kill both halves of the running app-image (see applyUpdateAndRestart's javadoc)
        // by EXACT PID BEFORE ever attempting the copy, rather than trusting System.exit(0) plus
        // a fixed wait to have released every handle on app\BLauncher.jar. Deliberately NOT using
        // taskkill's "/T" (kill whole subtree) here: this very cmd.exe running the script is
        // itself a descendant of both currentPid and parentPid (JVM -> cmd.exe via ProcessBuilder,
        // under the bootstrap stub) — "/T" on either would recursively kill this script's own
        // process before it reaches the copy/restart lines below. Plain "/PID" without "/T" only
        // ever touches that one exact process. "/F" because this is a deliberate, user-confirmed
        // restart-for-update, not a graceful shutdown we need to be gentle with. Errors are
        // swallowed (>nul 2>&1) since the current process is very likely already gone by the time
        // this line runs (it called Platform.exit()/System.exit(0) right after starting this
        // script) — taskkill failing because the PID no longer exists is the expected, common
        // case, not a problem.
        String killCurrent = "taskkill /F /PID " + currentPid + " > nul 2>&1\r\n";
        String killParent = parentPid > 0 ? "taskkill /F /PID " + parentPid + " > nul 2>&1\r\n" : "";
        // Retries a bounded number of times (15 x 1s = ~15s past the initial 1s wait) rather than
        // looping forever — if the file lock genuinely never clears for some other reason
        // (antivirus holding the file, etc), this gives up and tells the person to relaunch
        // manually instead of leaving a batch window stuck retrying indefinitely with no
        // indication anything is wrong.
        return "@echo off\r\n"
                + "setlocal enabledelayedexpansion\r\n"
                + "set attempts=0\r\n"
                + killCurrent
                + killParent
                + "timeout /t 1 /nobreak > nul\r\n"
                + ":retry\r\n"
                + "copy /y \"" + downloaded + "\" \"" + target + "\" > nul 2>&1\r\n"
                + "if not errorlevel 1 goto copied\r\n"
                + "set /a attempts+=1\r\n"
                + "if !attempts! geq 15 (\r\n"
                + "  echo Update failed - could not replace the launcher file. Please relaunch it manually.\r\n"
                + "  pause\r\n"
                + "  goto cleanup\r\n"
                + ")\r\n"
                + "timeout /t 1 /nobreak > nul\r\n"
                + "goto retry\r\n"
                + ":copied\r\n"
                // "/D" is load-bearing, not cosmetic: this cmd.exe's own working directory is
                // downloadedFile's temp folder (see applyUpdateAndRestart's pb.directory(...)),
                // and plain "start "" exe" would hand that SAME temp cwd down to the relaunched
                // process. MainApp.java resolves both its config path ("config/launcher.json")
                // and gameDirectory ("./launcher_root" by default) as RELATIVE paths against
                // whatever the process's cwd happens to be — with the wrong cwd, the relaunched
                // instance finds no config, silently recreates a fresh default one inside the temp
                // folder, and points gameDir at a launcher_root that doesn't exist there either -
                // which is exactly why builds/mods used to show up empty right after an
                // auto-update restart until the person relaunched a second time by hand (a normal
                // double-click always gets a correct cwd from Explorer).
                + "start \"\" /D \"" + exeDir + "\" \"" + exe + "\"\r\n"
                + ":cleanup\r\n"
                + "del \"" + downloaded + "\" > nul 2>&1\r\n"
                + "(goto) 2>nul & del \"%~f0\"\r\n";
    }
}
