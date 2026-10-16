package com.example.launcher.service.vps;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.LongConsumer;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Перенос локального сервера на VPS (этап 4 панели VPS): что из папки сервера везти и как.
 *
 * <p>Везём то, что делает сервер «тем самым»: миры, настройки, плагины с их данными, конфиги модов,
 * списки ops/whitelist/банов, иконку. НЕ везём то, что VPS поставит сам и под свою систему: ядро
 * (jar в корне, {@code libraries/}, {@code versions/}, {@code .fabric/}, {@code run.bat/run.sh}),
 * а также логи, отчёты о падениях и локальные бэкапы — это сотни мегабайт, которые на VPS не нужны.
 * Моды едут отдельным шагом установщика (с отсевом клиентских), поэтому {@code mods/} здесь тоже
 * пропускается.
 *
 * <p>Архив — tar.gz, а не zip: tar на VPS уже проверен шагом «Системные пакеты», а unzip там есть
 * не всегда. Пишем его сами: формат простой, а длинные и русские имена файлов идут через
 * PAX-заголовки, которые понимает GNU tar.
 */
public final class ServerMigration {

    private ServerMigration() {}

    /** Верхний уровень папки сервера, который не везём (сравнение без учёта регистра). */
    private static final Set<String> SKIP_TOP = Set.of(
            "libraries", "versions", ".fabric", "cache", "logs", "crash-reports", "debug", "backups",
            "mods", "eula.txt", "run.bat", "run.sh", "user_jvm_args.txt", "server.jar",
            ".blauncher-core", "usercache.json.bak");

    /** Где угодно в дереве. */
    private static final Set<String> SKIP_ANYWHERE = Set.of("session.lock", "thumbs.db", "desktop.ini");

    /** Сводка для окна: сколько везём и что внутри. */
    public record Summary(long bytes, int files, List<String> worlds, boolean plugins, boolean properties) {}

    public static boolean skip(Path root, Path p) {
        Path rel = root.relativize(p);
        String top = rel.getName(0).toString().toLowerCase(Locale.ROOT);
        if (rel.getNameCount() == 1) {
            if (SKIP_TOP.contains(top)) return true;
            // Ядро и его установщики лежат jar-файлами в корне (paper-1.21.4-123.jar,
            // fabric-server-launch.jar, forge-…-installer.jar). Плагины и моды — в своих папках.
            if (top.endsWith(".jar") || top.endsWith(".log") || top.endsWith(".tmp")) return true;
        } else if (SKIP_TOP.contains(top)) {
            return true;
        }
        String name = rel.getFileName().toString().toLowerCase(Locale.ROOT);
        if (SKIP_ANYWHERE.contains(name)) return true;
        // Кэш перемапленных плагинов Paper: пересоздаётся сам и весит как все плагины ещё раз.
        return rel.getNameCount() >= 2 && top.equals("plugins")
                && rel.getName(1).toString().equalsIgnoreCase(".paper-remapped");
    }

    public static Summary scan(Path dir) throws IOException {
        long[] bytes = {0};
        int[] files = {0};
        Set<String> worlds = new TreeSet<>();
        boolean[] plugins = {false};
        walk(dir, (p, attrs) -> {
            bytes[0] += attrs.size();
            files[0]++;
            Path rel = dir.relativize(p);
            if (rel.getNameCount() == 2 && rel.getFileName().toString().equals("level.dat")) worlds.add(rel.getName(0).toString());
            if (rel.getNameCount() >= 2 && rel.getName(0).toString().equalsIgnoreCase("plugins")) plugins[0] = true;
        });
        return new Summary(bytes[0], files[0], new ArrayList<>(worlds), plugins[0],
                Files.isRegularFile(dir.resolve("server.properties")));
    }

    /**
     * Пишет архив папки сервера в {@code out}. {@code progress} получает число уже упакованных байт
     * исходных файлов — для строки «упаковываю 120 из 800 МБ».
     */
    public static void writeArchive(Path dir, Path out, LongConsumer progress) throws IOException {
        try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(out), 1 << 16);
             GZIPOutputStream gz = new GZIPOutputStream(raw, 1 << 16)) {
            Tar tar = new Tar(gz);
            long[] done = {0};
            byte[] buf = new byte[1 << 16];
            IOException[] err = {null};
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                    if (d.equals(dir)) return FileVisitResult.CONTINUE;
                    if (skip(dir, d)) return FileVisitResult.SKIP_SUBTREE;
                    tar.entry(relName(dir, d) + "/", 0, attrs.lastModifiedTime().toMillis(), true);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                    // Ярлыки и прочее не-обычное не везём: ссылка могла бы указать куда угодно на VPS.
                    if (!attrs.isRegularFile() || skip(dir, f)) return FileVisitResult.CONTINUE;
                    long size = attrs.size();
                    tar.entry(relName(dir, f), size, attrs.lastModifiedTime().toMillis(), false);
                    long left = size;
                    try (InputStream in = Files.newInputStream(f)) {
                        int n;
                        while (left > 0 && (n = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                            gz.write(buf, 0, n);
                            left -= n;
                            done[0] += n;
                        }
                    }
                    // Файл укоротился, пока читали (сервер всё же пишет) — заголовок уже обещал size
                    // байт; добиваем нулями, иначе весь хвост архива съедет и не распакуется.
                    while (left > 0) {
                        int n = (int) Math.min(buf.length, left);
                        java.util.Arrays.fill(buf, 0, n, (byte) 0);
                        gz.write(buf, 0, n);
                        left -= n;
                    }
                    tar.pad(size);
                    progress.accept(done[0]);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path f, IOException e) {
                    err[0] = new IOException("Не читается " + dir.relativize(f) + ": " + e.getMessage(), e);
                    return FileVisitResult.TERMINATE;
                }
            });
            if (err[0] != null) throw err[0];
            tar.finish();
        }
    }

    private static String relName(Path root, Path p) throws IOException {
        Path rel = root.relativize(p);
        StringBuilder sb = new StringBuilder();
        for (Path part : rel) {
            String s = part.toString();
            // relativize внутри своей же папки не даёт «..», но имя из чужой ФС проверяем всё равно:
            // распаковка на VPS не должна выйти за папку сервера.
            if (s.isEmpty() || s.equals("..") || s.equals(".") || s.contains("/") || s.contains("\\")) {
                throw new IOException("Странное имя в папке сервера: " + rel);
            }
            if (sb.length() > 0) sb.append('/');
            sb.append(s);
        }
        return sb.toString();
    }

    private interface FileSink {
        void accept(Path p, BasicFileAttributes attrs) throws IOException;
    }

    private static void walk(Path dir, FileSink sink) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) {
                return d.equals(dir) || !skip(dir, d) ? FileVisitResult.CONTINUE : FileVisitResult.SKIP_SUBTREE;
            }

            @Override
            public FileVisitResult visitFile(Path f, BasicFileAttributes attrs) throws IOException {
                if (attrs.isRegularFile() && !skip(dir, f)) sink.accept(f, attrs);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path f, IOException e) {
                return FileVisitResult.CONTINUE;   // для сводки — пропускаем, архив сам скажет, что не так
            }
        });
    }

    /**
     * Версия Forge/NeoForge, которая стоит у локального сервера, — по папке libraries. Мир, сделанный
     * на конкретной версии загрузчика, надёжнее открывать на ней же, а не на «последней».
     * null — не нашли (тогда установщик возьмёт последнюю).
     */
    public static String detectLoaderVersion(Path dir, VpsInstaller.Core core, String mc) {
        try {
            if (core == VpsInstaller.Core.NEOFORGE) {
                return newestChild(dir.resolve("libraries/net/neoforged/neoforge"), null);
            }
            if (core == VpsInstaller.Core.FORGE) {
                // libraries/net/minecraftforge/forge/1.20.1-47.3.0 → 47.3.0
                String full = newestChild(dir.resolve("libraries/net/minecraftforge/forge"), mc + "-");
                return full == null ? null : full.substring(mc.length() + 1);
            }
        } catch (IOException ignored) {
            // не нашли — не беда
        }
        return null;
    }

    private static String newestChild(Path parent, String prefix) throws IOException {
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return null;
        String best = null;
        try (Stream<Path> st = Files.list(parent)) {
            for (Path p : st.toList()) {
                String n = p.getFileName().toString();
                if (prefix != null && !n.startsWith(prefix)) continue;
                if (!n.matches("[0-9A-Za-z.+\\-]{1,64}")) continue;
                if (best == null || n.compareTo(best) > 0) best = n;
            }
        }
        return best;
    }

    // ================================================================ tar

    /** Минимальный писатель ustar + PAX: только каталоги и обычные файлы. */
    static final class Tar {
        private final OutputStream out;
        private int paxSeq;

        Tar(OutputStream out) { this.out = out; }

        void entry(String name, long size, long mtimeMs, boolean dir) throws IOException {
            byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
            boolean ascii = nameBytes.length == name.length();
            String shortName = name;
            if (!ascii || nameBytes.length > 99) {
                // Полное имя — в PAX-заголовке; в самом заголовке остаётся заглушка (её tar не использует).
                byte[] rec = paxRecord("path", name);
                header("PaxHeaders/" + (++paxSeq), rec.length, mtimeMs, 'x');
                out.write(rec);
                pad(rec.length);
                shortName = "pax" + paxSeq + (dir ? "/" : "");
            }
            header(shortName, dir ? 0 : size, mtimeMs, dir ? '5' : '0');
        }

        private void header(String name, long size, long mtimeMs, char type) throws IOException {
            byte[] h = new byte[512];
            put(h, 0, 100, name.getBytes(StandardCharsets.US_ASCII));
            octal(h, 100, 8, type == '5' ? 0755 : 0644);
            octal(h, 108, 8, 0);
            octal(h, 116, 8, 0);
            octal(h, 124, 12, size);
            octal(h, 136, 12, Math.max(0, mtimeMs / 1000));
            java.util.Arrays.fill(h, 148, 156, (byte) ' ');
            h[156] = (byte) type;
            put(h, 257, 6, "ustar\0".getBytes(StandardCharsets.US_ASCII));
            put(h, 263, 2, "00".getBytes(StandardCharsets.US_ASCII));
            long sum = 0;
            for (byte b : h) sum += (b & 0xff);
            byte[] chk = String.format("%06o\0 ", sum).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(chk, 0, h, 148, 8);
            out.write(h);
        }

        /** «длина ключ=значение\n», где длина считает и саму себя. */
        private static byte[] paxRecord(String key, String value) {
            byte[] body = (" " + key + "=" + value + "\n").getBytes(StandardCharsets.UTF_8);
            int len = body.length;
            int total = len + String.valueOf(len).length();
            if (String.valueOf(total).length() != String.valueOf(len).length()) total = len + String.valueOf(total).length();
            byte[] prefix = String.valueOf(total).getBytes(StandardCharsets.US_ASCII);
            byte[] rec = new byte[prefix.length + body.length];
            System.arraycopy(prefix, 0, rec, 0, prefix.length);
            System.arraycopy(body, 0, rec, prefix.length, body.length);
            return rec;
        }

        void pad(long size) throws IOException {
            int rem = (int) (size % 512);
            if (rem != 0) out.write(new byte[512 - rem]);
        }

        void finish() throws IOException {
            out.write(new byte[1024]);
        }

        private static void put(byte[] h, int off, int len, byte[] v) {
            System.arraycopy(v, 0, h, off, Math.min(len, v.length));
        }

        private static void octal(byte[] h, int off, int len, long v) {
            String s = Long.toOctalString(v);
            if (s.length() > len - 1) throw new IllegalArgumentException("Файл больше, чем помещается в tar: " + v);
            s = "0".repeat(len - 1 - s.length()) + s;
            put(h, off, len - 1, s.getBytes(StandardCharsets.US_ASCII));
        }
    }
}
