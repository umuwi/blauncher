package com.example.launcher.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects Java installations on the system and maps major version to java executable path.
 */
public class JavaVersionDetector {

    // Java 9-24 format: "21.0.2" (major.minor.security)
    private static final Pattern VERSION_PATTERN = Pattern.compile("version \"(\\d+)\\.(\\d+)\\.(\\d+)(?:_(\\d+))?(?:[^\"]*)\"");
    // Java 8 format: "1.8.0_401"
    private static final Pattern VERSION_PATTERN_OLD = Pattern.compile("version \"1\\.(\\d+)\\.0(?:_(\\d+))?(?:[^\"]*)\"");
    // Java 25+ format: "25" (single number, no dots)
    private static final Pattern VERSION_PATTERN_SINGLE = Pattern.compile("version \"(\\d+)\"");

    /** Кэш «путь к java → мажорная версия». Статический — см. {@link #getMajorVersion(String)}. */
    private static final Map<String, Integer> VERSION_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private Map<Integer, String> detectedJava = null;

    /**
     * Detects all Java installations on the system, caching results.
     * Returns a sorted map of major version to java executable path (descending).
     */
    public Map<Integer, String> detectJavaVersions() {
        if (detectedJava != null) return detectedJava;

        Map<Integer, String> result = new TreeMap<>(Comparator.reverseOrder());

        // 1. Check currently running Java
        String currentJava = Paths.get(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        int currentVer = getMajorVersion(currentJava);
        if (currentVer > 0) result.putIfAbsent(currentVer, currentJava);

        // 2. Scan common directories
        List<Path> scanDirs = new ArrayList<>();
        if (isWindows()) {
            scanDirs.add(Path.of("C:\\Program Files\\Java"));
            scanDirs.add(Path.of("C:\\Program Files\\Eclipse Adoptium"));
            scanDirs.add(Path.of("C:\\Program Files\\Microsoft"));
            scanDirs.add(Path.of("C:\\Program Files\\Eclipse Foundation"));
            scanDirs.add(Path.of("C:\\Program Files (x86)\\Java"));
        } else {
            scanDirs.add(Path.of("/usr/lib/jvm"));
            scanDirs.add(Path.of("/usr/local"));
            scanDirs.add(Path.of("/opt"));
        }

        // 3. Check JAVA_HOME variations
        for (String envKey : List.of("JAVA_HOME", "JAVA23_HOME", "JAVA25_HOME", "JDK_HOME")) {
            String env = System.getenv(envKey);
            if (env != null && !env.isBlank()) {
                Path javaExe = Path.of(env, "bin", isWindows() ? "java.exe" : "java");
                if (Files.exists(javaExe)) {
                    int ver = getMajorVersion(javaExe.toString());
                    if (ver > 0) result.putIfAbsent(ver, javaExe.toString());
                }
            }
        }

        // 4. Scan directories
        for (Path dir : scanDirs) {
            if (!Files.isDirectory(dir)) continue;
            // try-with-resources обязателен: Files.list держит открытый дескриптор каталога, и без
            // закрытия он течёт на каждый просканированный корень JVM.
            try (var entries = Files.list(dir)) {
                for (Path subDir : (Iterable<Path>) entries::iterator) {
                    if (!Files.isDirectory(subDir)) continue;
                    Path javaExe = subDir.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
                    if (Files.exists(javaExe)) {
                        int ver = getMajorVersion(javaExe.toString());
                        if (ver > 0) result.putIfAbsent(ver, javaExe.toString());
                    }
                }
            } catch (IOException ignored) {}
        }

        detectedJava = result;
        return result;
    }

    /**
     * Returns the major Java version from a java executable path
     * by running "java -version" and parsing the output.
     *
     * <p>Ответ кэшируется по пути, и кэш статический нарочно: {@link GameLaunchService} создаёт
     * новый детектор на каждый вызов, так что кэш поля ничего бы не дал — а без него каждый
     * запуск игры дважды ждёт старт отдельной JVM ради одной строки версии.
     */
    public int getMajorVersion(String javaPath) {
        if (javaPath == null || javaPath.isBlank()) return -1;
        Integer cached = VERSION_CACHE.get(javaPath);
        if (cached != null) return cached;
        int version = probeMajorVersion(javaPath);
        VERSION_CACHE.put(javaPath, version);
        return version;
    }

    private int probeMajorVersion(String javaPath) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(javaPath, "-version");
            pb.redirectErrorStream(true);
            p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Matcher m = VERSION_PATTERN.matcher(line);
                    if (m.find()) {
                        int major = Integer.parseInt(m.group(1));
                        // Old Java 8 format: "1.8.0_401" major=1, true version is group(2)
                        if (major == 1) {
                            try { return Integer.parseInt(m.group(2)); } catch (NumberFormatException e) { return 1; }
                        }
                        return major;
                    }
                    m = VERSION_PATTERN_OLD.matcher(line);
                    if (m.find()) {
                        return Integer.parseInt(m.group(1));
                    }
                    m = VERSION_PATTERN_SINGLE.matcher(line);
                    if (m.find()) {
                        return Integer.parseInt(m.group(1));
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            // Все return выше выходят изнутри try-with-resources, поэтому прежний p.waitFor() в
            // конце метода не выполнялся никогда — именно в успешном случае. Дожидаемся с
            // ограничением и добиваем: при скане каталогов таких процессов десятки.
            if (p != null) {
                try {
                    if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly();
                } catch (InterruptedException e) {
                    p.destroyForcibly();
                    Thread.currentThread().interrupt();
                }
            }
        }
        return -1;
    }

    /**
     * Finds the best Java installation that satisfies the minimum major version requirement.
     * Returns the LOWEST available version >= min (closest match), NOT the highest.
     */
    public String findBestJava(int minMajorVersion) {
        return findBestJava(minMajorVersion, Integer.MAX_VALUE);
    }

    /**
     * Finds the best Java installation in [minMajorVersion, maxMajorVersion] inclusive.
     * Returns the LOWEST version in range (closest to min), or null if none match.
     */
    public String findBestJava(int minMajorVersion, int maxMajorVersion) {
        Map<Integer, String> javas = detectJavaVersions();
        String best = null;
        int bestVer = Integer.MAX_VALUE;
        for (Map.Entry<Integer, String> entry : javas.entrySet()) {
            int ver = entry.getKey();
            if (ver >= minMajorVersion && ver <= maxMajorVersion && ver < bestVer) {
                bestVer = ver;
                best = entry.getValue();
            }
        }
        return best;
    }

    /**
     * Returns the path to a Java executable matching EXACTLY the given major version,
     * or null if none is found.
     */
    public String getJavaForExactVersion(int majorVersion) {
        Map<Integer, String> javas = detectJavaVersions();
        return javas.get(majorVersion);
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

}
