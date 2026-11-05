package com.example.launcher.service;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serves a profile's local skin/cape files over http://127.0.0.1 so Minecraft can download them
 * through the normal texture path even in offline mode.
 */
public class LocalSkinServer implements AutoCloseable {

    /** Сколько раз игра просила текстуры: 0 при запущенной игре значит, что скин до неё не дошёл (см. отметку blskins). */
    private static final java.util.concurrent.atomic.AtomicInteger skinHits = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger capeHits = new java.util.concurrent.atomic.AtomicInteger();

    /** Счётчики с прошлого вызова: {скин, плащ}. */
    public static int[] takeHits() {
        return new int[]{skinHits.getAndSet(0), capeHits.getAndSet(0)};
    }

    private final HttpServer server;
    private final ExecutorService pool;
    private final String skinUrl;
    private final String capeUrl;

    private LocalSkinServer(HttpServer server, ExecutorService pool, String skinUrl, String capeUrl) {
        this.server = server;
        this.pool = pool;
        this.skinUrl = skinUrl;
        this.capeUrl = capeUrl;
    }

    public static LocalSkinServer start(Path skinPath, Path capePath) throws IOException {
        // Файлы читаются ДО того, как занят порт. Раньше сокет биндился первым, и если скин
        // успевал исчезнуть между проверкой и чтением, readAllBytes выбрасывал наружу — а
        // привязанный порт оставался занятым до конца жизни JVM, потому что stop() не вызывался.
        byte[] skinBytes = readIfPresent(skinPath);
        byte[] capeBytes = readIfPresent(capePath);
        if (skinBytes == null && capeBytes == null) return null;

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService pool = null;
        try {
            String skinUrl = null;
            String capeUrl = null;
            int port = server.getAddress().getPort();
            if (skinBytes != null) {
                final byte[] bytes = skinBytes;
                // Игра кладёт текстуру в кэш по имени файла: с постоянным «skin.png» новый скин не появился
                // бы никогда. -slim в имени читает BlSkinsAgent: узкие руки нужно указать игре отдельно.
                String path = "/skin-" + contentId(bytes) + (isSlim(bytes) ? "-slim" : "") + ".png";
                server.createContext(path, exchange -> {
                    skinHits.incrementAndGet();
                    writeImage(exchange, bytes);
                });
                skinUrl = "http://127.0.0.1:" + port + path;
            }
            if (capeBytes != null) {
                final byte[] bytes = capeBytes;
                String path = "/cape-" + contentId(bytes) + ".png";
                server.createContext(path, exchange -> {
                    capeHits.incrementAndGet();
                    writeImage(exchange, bytes);
                });
                capeUrl = "http://127.0.0.1:" + port + path;
            }

            // Без исполнителя HttpServer обслуживает запросы на своём же потоке приёма, то есть
            // строго по одному: игра просит скин и плащ сразу, и второй запрос ждал первого.
            pool = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "local-skin-server");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(pool);
            server.start();
            return new LocalSkinServer(server, pool, skinUrl, capeUrl);
        } catch (RuntimeException | Error e) {
            server.stop(0);
            if (pool != null) pool.shutdownNow();
            throw e;
        }
    }

    private static String contentId(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 24);
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(java.util.Arrays.hashCode(bytes));
        }
    }

    /** Узкие руки: в развёртке 64x64 столбец 54 у правой руки пуст, а 50 занят (как в редакторе скинов). */
    private static boolean isSlim(byte[] png) {
        try {
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
            if (image == null || image.getWidth() != 64 || image.getHeight() != 64) return false;
            return (image.getRGB(54, 20) >>> 24) == 0 && (image.getRGB(50, 20) >>> 24) != 0;
        } catch (IOException e) {
            return false;
        }
    }

    private static byte[] readIfPresent(Path path) {
        if (path == null || !Files.exists(path)) return null;
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            // Исчезнувший или нечитаемый файл скина — не повод валить запуск игры: просто
            // не отдаём эту текстуру.
            return null;
        }
    }

    private static void writeImage(HttpExchange exchange, byte[] bytes) throws IOException {
        try {
            exchange.getResponseHeaders().set("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } finally {
            exchange.close();
        }
    }

    public String getSkinUrl() {
        return skinUrl;
    }

    public String getCapeUrl() {
        return capeUrl;
    }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
    }
}
