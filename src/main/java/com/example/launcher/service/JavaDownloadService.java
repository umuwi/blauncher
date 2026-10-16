package com.example.launcher.service;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
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

    /**
     * Скачивает и распаковывает JDK указанной версии.
     * Возвращает строку-путь к java-исполняемому файлу.
     */
    public String downloadAndInstallJava(int version, Consumer<String> log) throws Exception {
        log.accept("[JAVA] Installing Java " + version + "...");

        Path versionDir = javaDir.resolve(String.valueOf(version));

        // Проверяем, не установлена ли уже
        Path already = getJavaExecutable(version);
        if (already != null) {
            log.accept("[JAVA] Java " + version + " already installed");
            return already.toString();
        }

        Files.createDirectories(versionDir);

        // Определяем платформу и архитектуру
        String os = System.getProperty("os.name").toLowerCase();
        String arch = System.getProperty("os.arch");
        String platform = os.contains("win") ? "windows" : os.contains("mac") ? "mac" : "linux";
        String archStr = (arch.contains("aarch64") || arch.contains("arm")) ? "aarch64" : "x64";
        String osSuffix = platform.equals("windows") ? "zip" : "tar.gz";

        // Adoptium API v3
        String apiUrl = "https://api.adoptium.net/v3/binary/latest/"
                + version + "/ga/"
                + platform + "/"
                + archStr + "/jdk/hotspot/normal/eclipse?project=jdk";

        log.accept("[JAVA] Downloading from Adoptium...");

        Path tempFile = Files.createTempFile("jdk-" + version + "-", "." + osSuffix);

        try {
            // Adoptium answers with a redirect chain into GitHub release storage, which Http
            // follows by hand - HttpURLConnection's built-in redirect handling refuses to cross
            // between http and https and would hand back the redirect body instead of the JDK.
            long written = Http.download(apiUrl, tempFile, Map.of(), Duration.ofMinutes(10));
            if (written < 0) {
                throw new IOException("Adoptium rejected the request for JDK " + version
                        + " (" + platform + "/" + archStr + "). "
                        + "This JDK version may not be available for your platform yet.");
            }
            if (Files.size(tempFile) < 1024) {
                throw new IOException("Downloaded file is too small (" + Files.size(tempFile)
                        + " bytes) - JDK " + version + " may not exist for " + platform + "/" + archStr);
            }
        } catch (IOException e) {
            Files.deleteIfExists(tempFile);
            String detail = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new IOException("Failed to download JDK " + version + ": " + detail, e);
        }

        log.accept("[JAVA] Extracting...");

        try {
            if ("zip".equals(osSuffix)) {
                extractZip(tempFile, versionDir);
            } else {
                extractTarGz(tempFile, versionDir);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }

        Path javaExe = getJavaExecutable(version);
        if (javaExe == null) {
            throw new RuntimeException(
                "JDK extracted, but java executable not found in " + versionDir.toAbsolutePath()
            );
        }

        log.accept("[JAVA] Installed successfully: " + javaExe);
        return javaExe.toString();
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