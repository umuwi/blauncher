package com.example.launcher.service;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.zip.*;

/**
 * Автоматическая загрузка и установка Java через Adoptium API.
 * Кэширует диапазоны версий для каждой версии Minecraft.
 * Поддерживает Windows (zip), Linux/macOS (tar.gz).
 */
public class JavaDownloadService {

    private final Path gameDir;
    private final Path javaDir;
    private final ConcurrentHashMap<String, int[]> versionRangeCache = new ConcurrentHashMap<>();

    /**
     * Почему не встала Java в последний раз — метками для статистики, без путей и текста ошибок:
     * «m:sha-m:cut-m:cut-jre:Connect-jdk:Connect» (m — наше зеркало), «unpack:move:AccessDenied» (шаг: extract|move).
     * 21.10.2026 у 7267c860 дважды пришло голое «Exception phase=java cause=IOException», а
     * все причины по источникам были склеены в один текст, который на сервер не уходит.
     */
    private volatile String lastFailTag = "";

    public String lastFailTag() {
        return lastFailTag;
    }

    /** Метки через «-», одинаковые подряд — одной с «x3»: в detail события всего 120 символов. */
    static String joinTags(List<String> tags) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tags.size(); ) {
            int j = i;
            while (j < tags.size() && tags.get(j).equals(tags.get(i))) j++;
            if (sb.length() > 0) sb.append('-');
            sb.append(tags.get(i));
            if (j - i > 1) sb.append('x').append(j - i);
            i = j;
        }
        return sb.toString();
    }

    private static String failKind(IOException e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.startsWith("sha256 не совпал")) return "sha";
        if (m.startsWith("Соединение оборвалось")) return "cut";
        String name = e.getClass().getSimpleName();
        return name.endsWith("Exception") && name.length() > "Exception".length()
                ? name.substring(0, name.length() - "Exception".length()) : name;
    }

    public JavaDownloadService(Path gameDir) {
        this.gameDir = gameDir;
        this.javaDir = gameDir.resolve("java-runtime");
        try {
            Files.createDirectories(javaDir);
        } catch (IOException ignored) {
        }
    }

    /**
     * Возвращает [minJava, maxJava] для данной версии Minecraft.
     * Значения по умолчанию основаны на официальных требованиях.
     */
    public int[] getJavaVersionRange(String versionId) {
        return versionRangeCache.computeIfAbsent(versionId, v -> {
            // Современные версии
            if (v.contains("1.22")) return new int[]{21, Integer.MAX_VALUE};
            if (v.contains("1.21")) return new int[]{21, Integer.MAX_VALUE};
            if (v.contains("1.20")) return new int[]{17, Integer.MAX_VALUE};
            if (v.contains("1.19")) return new int[]{17, 21};
            if (v.contains("1.18")) return new int[]{17, 17};
            if (v.contains("1.17")) return new int[]{16, 17};
            // Альфы, беты и самые ранние сборки (b1.7.3, a1.2.6, c0.30, inf-20100618, rd-132211)
            // идут через LaunchWrapper и живут только на Java 8: на 9+ — ClassCastException
            // «AppClassLoader cannot be cast to URLClassLoader». 14.10.2026 b1.7.3 получала
            // «по умолчанию» Java 17 и не запускалась.
            if (v.matches("^(a\\d|b\\d|c0\\.|inf-|rd-).*")) return new int[]{8, 8};
            // Старые версии (схема "1.x.y") — не применяем к новым схемам версионирования
            // (например "26.2"), иначе минорная часть ложно трактуется как древний релиз.
            if (v.startsWith("1.")) {
                try {
                    String[] parts = v.split("\\.");
                    if (parts.length >= 2) {
                        int minor = Integer.parseInt(parts[1]);
                        if (minor <= 16) return new int[]{8, 11};
                        if (minor <= 17) return new int[]{16, 17};
                        if (minor <= 20) return new int[]{17, 21};
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            // По умолчанию — современная
            return new int[]{17, Integer.MAX_VALUE};
        });
    }

    /**
     * Ищет java-исполняемый файл в папке java-runtime/{version}.
     * Возвращает null, если не найден.
     */
    public Path getJavaExecutable(int version) {
        Path versionDir = javaDir.resolve(String.valueOf(version));
        if (!Files.exists(versionDir)) return version == 21 ? ownRuntimeJava() : null;
        return findJavaIn(versionDir);
    }

    private static Path findJavaIn(Path versionDir) {
        if (!Files.exists(versionDir)) return null;
        // Прямой путь java.exe / java
        Path exe = versionDir.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
        if (Files.exists(exe)) return exe;

        // Если bin лежит на уровень глубже (например jdk-17.0.2+8/bin/...)
        try {
            for (Path sub : Files.list(versionDir).filter(Files::isDirectory).toList()) {
                Path candidate = sub.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
                if (Files.exists(candidate)) return candidate;
            }
        } catch (IOException ignored) {
        }

        return null;
    }

    /** null — ещё не проверяли; потом путь к java.exe своей Java или Optional.empty, если её не завести. */
    private static volatile java.util.Optional<Path> ownRuntime;

    /**
     * Java 21 самого лаунчера как Java для игры (25.10.2026). В app-image она полная (все модули
     * 21.0.10), но jpackage выкидывает из неё bin/java.exe и javaw.exe — поэтому каждый игрок
     * 1.20.5–1.21.x качал ещё 49 МБ Java 21, а у кого её проверял антивирус, игра не запускалась.
     * Кладём рядом два крошечных запускателя от той же 21.0.10 (лежат в BLauncher.jar, так что
     * доезжают и автообновлением) и проверяем «java -version». Проверено вживую на ванильной
     * 1.21.4 и NeoForge 1.21.1 (и его установщике). Только Windows и только если версия Java
     * лаунчера ровно та, от которой запускатели; иначе — null, и Java 21 скачается как раньше.
     */
    static Path ownRuntimeJava() {
        java.util.Optional<Path> known = ownRuntime;
        if (known != null) return known.orElse(null);
        Path found = null;
        try {
            found = prepareOwnRuntimeJava();
        } catch (Exception e) {
            System.out.println("[JAVA] Своя Java лаунчера не подошла для игры: " + e.getMessage());
        }
        ownRuntime = java.util.Optional.ofNullable(found);
        return found;
    }

    private static Path prepareOwnRuntimeJava() throws Exception {
        if (!isWindows()) return null;
        Path home = Path.of(System.getProperty("java.home"));
        Path release = home.resolve("release");
        String want;
        try (InputStream in = JavaDownloadService.class.getResourceAsStream("/bundled-java/windows/VERSION")) {
            if (in == null) return null;
            want = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        }
        if (!Files.exists(release) || !Files.readString(release).contains("JAVA_VERSION=\"" + want + "\"")) return null;
        Path bin = home.resolve("bin");
        for (String name : new String[]{"java.exe", "javaw.exe"}) {
            Path target = bin.resolve(name);
            if (Files.exists(target) && Files.size(target) > 0) continue;
            try (InputStream in = JavaDownloadService.class.getResourceAsStream("/bundled-java/windows/" + name)) {
                if (in == null) return null;
                Path tmp = bin.resolve(name + ".tmp");
                Files.copy(in, tmp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path exe = bin.resolve("java.exe");
        Process p = new ProcessBuilder(exe.toString(), "-version").redirectErrorStream(true).start();
        p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
        if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return null;
        }
        if (p.exitValue() != 0) return null;
        System.out.println("[JAVA] Для игры — своя Java лаунчера: " + exe);
        return exe;
    }

    /**
     * Скачивает и распаковывает JDK указанной версии.
     * Возвращает строку-путь к java-исполняемому файлу.
     */
    public String downloadAndInstallJava(int version, Consumer<String> log) throws Exception {
        return downloadAndInstallJava(version, log, null);
    }

    /** Зеркало JRE на нашем сайте: jre-{версия}-{ОС}-{архитектура}.{zip|tar.gz} и рядом .sha256. */
    static final String MIRROR_BASE = "https://blauncher.ru/java/";

    /**
     * То же, с ходом загрузки в {@code progress} (байты; из потока загрузки).
     *
     * <p>Источники по очереди: JRE с blauncher.ru → JRE с Adoptium → JDK с Adoptium (как было).
     * 20.10.2026: встроенная Java лаунчера (jpackage) без java.exe, поэтому у каждого новичка без
     * своей Java первая игра качала ПОЛНЫЙ JDK ~200 МБ, а Adoptium отдаёт его через серверы GitHub,
     * которые в России часто тормозят. По статистике 13–20.10 один ждал Java 18 и 14 минут и ушёл
     * (69130832), у другого загрузка упала (85e9663b). Игре хватает JRE (~45 МБ). Таймаут
     * соединения — 30 с, а не 10 минут: зависшее соединение с GitHub держало человека на
     * крутилке без единой цифры, пока он не закрывал лаунчер.
     */
    public String downloadAndInstallJava(int version, Consumer<String> log, Http.Progress progress)
            throws Exception {
        log.accept("[JAVA] Installing Java " + version + "...");

        // Проверяем, не установлена ли уже
        Path already = getJavaExecutable(version);
        if (already != null) {
            log.accept("[JAVA] Java " + version + " already installed");
            return already.toString();
        }
        return install(String.valueOf(version), version, "jre-" + version, true, log, progress);
    }

    /**
     * Java 25, которая умеет запускать программу прямо из исходника («java Файл.java»), — для
     * Rustcraft: он один раз готовит данные игры своими ClientExtractor.java и т.п.
     *
     * <p>28.10.2026: у 11 из 17 игроков Rustcraft падал через 5–60 с с «InternalError» на каждом
     * запуске. Это «java.lang.InternalError: Module jdk.compiler not in boot Layer»: в JRE 25 с
     * нашего зеркала (и в JRE Adoptium) модуля jdk.compiler нет, а запуск из исходника без него
     * невозможен. Данные не готовились, и всё повторялось. Обычной игре он не нужен, поэтому
     * обычную jre-25 не трогаем: отдельная Java в java-runtime/25c — наша JRE + jdk.compiler
     * (jlink, jre-25c-windows-x64.zip на зеркале), запасной — полный JDK с Adoptium.
     *
     * @param current Java, выбранная для игры (может быть null) — подходит, если в ней есть jdk.compiler
     */
    public String sourceLaunchJava(String current, Consumer<String> log, Http.Progress progress) throws Exception {
        if (current != null && canRunSourceFiles(Path.of(current))) return current;
        Path own = findJavaIn(javaDir.resolve("25c"));
        if (own != null && canRunSourceFiles(own)) return own.toString();
        log.accept("[JAVA] Для Rustcraft нужна Java 25 с компилятором (jdk.compiler) — скачиваю один раз");
        return install("25c", 25, "jre-25c", false, log, progress);
    }

    /** В Java есть модуль jdk.compiler, то есть «java Файл.java» запустится. */
    static boolean canRunSourceFiles(Path javaExe) {
        if (javaExe == null || !Files.isRegularFile(javaExe)) return false;
        try {
            Process p = new ProcessBuilder(javaExe.toString(), "--list-modules").redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            if (!p.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0 && out.lines().anyMatch(l -> l.startsWith("jdk.compiler@"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Скачать и поставить Java в java-runtime/{dirName}: наше зеркало {mirrorName}-{ОС}-{арх},
     * потом Adoptium (JRE — только если {@code jreOk}, затем JDK).
     */
    private String install(String dirName, int version, String mirrorName, boolean jreOk,
                           Consumer<String> log, Http.Progress progress) throws Exception {
        Path versionDir = javaDir.resolve(dirName);

        // Определяем платформу и архитектуру
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch");
        String platform = os.contains("win") ? "windows" : os.contains("mac") ? "mac" : "linux";
        String archStr = (arch.contains("aarch64") || arch.contains("arm")) ? "aarch64" : "x64";
        String osSuffix = platform.equals("windows") ? "zip" : "tar.gz";

        String adoptium = "https://api.adoptium.net/v3/binary/latest/" + version + "/ga/"
                + platform + "/" + archStr + "/%s/hotspot/normal/eclipse?project=jdk";
        String mirror = MIRROR_BASE + mirrorName + "-" + platform + "-" + archStr + "." + osSuffix;
        // Третье поле — короткое имя источника для статистики (lastFailTag).
        List<String[]> sources = new ArrayList<>();
        sources.add(new String[]{"blauncher.ru", mirror, "m"});
        if (jreOk) sources.add(new String[]{"Adoptium JRE", String.format(adoptium, "jre"), "jre"});
        sources.add(new String[]{"Adoptium JDK", String.format(adoptium, "jdk"), "jdk"});

        Path tempFile = Files.createTempFile("jre-" + dirName + "-", "." + osSuffix);
        String errors = "";
        List<String> tags = new ArrayList<>();
        lastFailTag = "";
        boolean got = false;
        try {
            for (String[] src : sources) {
                // Своё зеркало — до трёх попыток. 21.10.2026 (7267c860) сервер трижды отдал JRE
                // целиком (nginx: 200, все байты), а у игрока то не сходился sha256, то файл
                // обрывался в самом конце — по одной попытке на источник Java не встала ни разу.
                int attempts = src[1].equals(mirror) ? 3 : 1;
                for (int attempt = 1; attempt <= attempts && !got; attempt++) {
                    log.accept("[JAVA] Downloading from " + src[0] + (attempt > 1 ? " (попытка " + attempt + ")" : "") + "...");
                    try {
                        // Adoptium answers with a redirect chain into GitHub release storage, which Http
                        // follows by hand - HttpURLConnection's built-in redirect handling refuses to cross
                        // between http and https and would hand back the redirect body instead of the JDK.
                        long written = Http.download(src[1], tempFile, Map.of(), Duration.ofSeconds(30), progress);
                        if (written < 0) {
                            errors += src[0] + ": нет файла; ";
                            tags.add(src[2] + ":http");
                            break;
                        }
                        if (Files.size(tempFile) < 1024) {
                            errors += src[0] + ": файл " + Files.size(tempFile) + " байт; ";
                            tags.add(src[2] + ":small");
                            break;
                        }
                        if (src[1].equals(mirror)) checkMirrorSha256(mirror, tempFile);
                        got = true;
                    } catch (IOException e) {
                        String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                        log.accept("[JAVA] " + src[0] + ": " + detail);
                        errors += src[0] + ": " + detail + "; ";
                        tags.add(src[2] + ":" + failKind(e));
                    }
                }
                if (got) break;
            }
            lastFailTag = joinTags(tags);
            if (!got) {
                throw new IOException("Failed to download Java " + version + " (" + platform + "/"
                        + archStr + "): " + errors);
            }

            log.accept("[JAVA] Extracting...");
            // Распаковка во временную папку и перенос целиком: оборванная распаковка не оставит
            // в java-runtime/<версия> java.exe без половины библиотек, который getJavaExecutable
            // принял бы за готовую Java.
            //
            // 22–23.10.2026 (6d339572, aabeb3f8): у двух новичков Java скачалась целиком, а дальше
            // «AccessDenied» / «DirectoryNotEmpty» на каждом нажатии «Играть». Только что распакованные
            // java.exe и dll проверяет антивирус: пока он держит файл, Windows не даёт переименовать
            // папку, а удалённый файл висит «на удалении», и папка .part не удаляется — следующая попытка
            // спотыкалась уже о неё. Поэтому: своя временная папка на каждую попытку, перенос с
            // повторами, а если не вышло — копирование файлов вместо переноса.
            cleanupOldStaging(dirName);
            // Распаковка — до трёх раз, каждый раз в новую папку. 23.10.2026 (ac5cc6dc, 1.14.25):
            // антивирус запретил запись уже при распаковке («unpack:extract:AccessDenied»), и после
            // первой же ошибки новичок ушёл. Через несколько секунд проверка обычно заканчивается.
            for (int attempt = 1; ; attempt++) {
                Path staging = javaDir.resolve(dirName + ".part" + System.currentTimeMillis());
                String step = "extract";
                try {
                    Files.createDirectories(staging);
                    if ("zip".equals(osSuffix)) {
                        extractZip(tempFile, staging);
                    } else {
                        extractTarGz(tempFile, staging);
                    }
                    step = "move";
                    installStaged(staging, versionDir, log);
                    break;
                } catch (IOException e) {
                    try { deleteTree(staging); } catch (IOException ignored) { }
                    if ("extract".equals(step) && attempt < 3 && e instanceof java.nio.file.FileSystemException) {
                        log.accept("[JAVA] Распаковка не удалась (" + e.getClass().getSimpleName() + "), повтор через 5 с...");
                        Thread.sleep(5000);
                        continue;
                    }
                    lastFailTag = (lastFailTag.isEmpty() ? "" : lastFailTag + "-") + "unpack:" + step + ":" + failKind(e)
                            + (attempt > 1 ? ":x" + attempt : "");
                    throw e;
                }
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }

        Path javaExe = findJavaIn(versionDir);
        if (javaExe == null) {
            throw new RuntimeException(
                "JDK extracted, but java executable not found in " + versionDir.toAbsolutePath()
            );
        }

        log.accept("[JAVA] Installed successfully: " + javaExe);
        return javaExe.toString();
    }

    /** Сверка с .sha256 рядом с файлом на зеркале; нет .sha256 — не сверяем (как у Adoptium). */
    private static void checkMirrorSha256(String url, Path file) throws IOException {
        Http.Response r = Http.get(url + ".sha256", Map.of(), Duration.ofSeconds(30));
        if (r.status() != 200) return;
        String expected = r.text().trim().split("\\s+")[0].toLowerCase();
        if (expected.length() != 64) return;
        String actual;
        try (InputStream in = Files.newInputStream(file)) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            actual = java.util.HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        if (!expected.equals(actual)) {
            throw new IOException("sha256 не совпал: " + actual + " вместо " + expected);
        }
    }

    /** Остатки прошлых попыток (<версия>.part…); то, что ещё держит антивирус, оставляем до следующего раза. */
    private void cleanupOldStaging(String dirName) {
        try (var list = Files.list(javaDir)) {
            for (Path p : list.filter(p -> p.getFileName().toString().startsWith(dirName + ".part")).toList()) {
                try { deleteTree(p); } catch (IOException ignored) { }
            }
        } catch (IOException ignored) { }
    }

    /**
     * Ставит распакованную Java на место: переименование папки с повторами (до ~20 с — столько
     * обычно длится проверка антивирусом), потом копирование файлов. java.exe/javaw.exe копируются
     * последними: getJavaExecutable считает Java готовой по ним, и оборванное копирование не должно
     * оставить «готовую» Java без библиотек.
     */
    private static void installStaged(Path staging, Path versionDir, Consumer<String> log) throws IOException {
        IOException last = null;
        for (int i = 0; i < 8; i++) {
            try {
                if (Files.exists(versionDir)) deleteTree(versionDir);
                Files.move(staging, versionDir);
                return;
            } catch (IOException e) {
                last = e;
                log.accept("[JAVA] Папку пока не перенести (" + e.getClass().getSimpleName() + "), жду...");
                try {
                    Thread.sleep(500L * (i + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        log.accept("[JAVA] Переношу файлы копированием...");
        try {
            List<Path> files;
            try (var walk = Files.walk(staging)) {
                files = walk.filter(Files::isRegularFile).toList();
            }
            List<Path> exes = new ArrayList<>();
            for (Path f : files) {
                String n = f.getFileName().toString();
                if (n.equals("java.exe") || n.equals("javaw.exe") || n.equals("java")) { exes.add(f); continue; }
                copyInto(staging, versionDir, f);
            }
            for (Path f : exes) copyInto(staging, versionDir, f);
        } catch (IOException e) {
            e.addSuppressed(last);
            try { deleteTree(versionDir); } catch (IOException ignored) { }
            throw e;
        }
        try { deleteTree(staging); } catch (IOException ignored) { }
    }

    private static void copyInto(Path fromRoot, Path toRoot, Path file) throws IOException {
        Path dest = toRoot.resolve(fromRoot.relativize(file).toString());
        Files.createDirectories(dest.getParent());
        Files.copy(file, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private void extractZip(Path zipFile, Path targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(
                new FileInputStream(zipFile.toFile())))) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                Path dest = targetDir.resolve(entry.getName()).normalize();
                if (!dest.startsWith(targetDir)) {
                    // защита от Zip Slip
                    continue;
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                } else {
                    Files.createDirectories(dest.getParent());
                    try (OutputStream os = Files.newOutputStream(dest)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            os.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private void extractTarGz(Path archive, Path targetDir) throws IOException, InterruptedException {
        Files.createDirectories(targetDir);
        ProcessBuilder pb = new ProcessBuilder(
                "tar", "-xzf", archive.toAbsolutePath().toString(),
                "-C", targetDir.toAbsolutePath().toString());
        pb.inheritIO();
        int exit = pb.start().waitFor();
        if (exit != 0) {
            throw new IOException("tar extraction failed with exit code " + exit);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}