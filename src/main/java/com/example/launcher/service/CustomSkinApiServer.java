package com.example.launcher.service;

import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Serves one launcher profile as a local CustomSkinAPI endpoint. CustomSkinLoader can be pointed
 * at this server, which lets it load locally uploaded skins/capes in modded Minecraft without any
 * third-party skin site.
 */
public class CustomSkinApiServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService pool;
    private final String rootUrl;

    private CustomSkinApiServer(HttpServer server, ExecutorService pool, String rootUrl) {
        this.server = server;
        this.pool = pool;
        this.rootUrl = rootUrl;
    }

    public static CustomSkinApiServer start(String username, Path skinPath, Path capePath) throws IOException {
        String skinHash = "skin";
        String capeHash = "cape";
        // Читаем файлы до бинда порта — иначе сорвавшееся чтение оставляло занятый порт навсегда
        // (stop() не вызывался). См. тот же разбор в LocalSkinServer.
        byte[] skinBytes = readIfPresent(skinPath);
        byte[] capeBytes = readIfPresent(capePath);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService pool = null;
        try {
            server.createContext("/", exchange ->
                    handle(exchange, username, skinHash, skinBytes, capeHash, capeBytes));
            // CustomSkinLoader запрашивает профиль и обе текстуры подряд; без исполнителя они
            // обслуживались строго по очереди на потоке приёма.
            pool = Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "custom-skin-api");
                t.setDaemon(true);
                return t;
            });
            server.setExecutor(pool);
            server.start();

            String rootUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
            return new CustomSkinApiServer(server, pool, rootUrl);
        } catch (RuntimeException | Error e) {
            server.stop(0);
            if (pool != null) pool.shutdownNow();
            throw e;
        }
    }

    private static byte[] readIfPresent(Path path) {
        if (path == null || !Files.exists(path)) return null;
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            return null;
        }
    }

    private static void handle(HttpExchange exchange, String username, String skinHash, byte[] skinBytes,
                               String capeHash, byte[] capeBytes) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.startsWith("/textures/")) {
                String hash = path.substring("/textures/".length());
                if (skinHash.equals(hash) && skinBytes != null) {
                    writeImage(exchange, skinBytes);
                } else if (capeHash.equals(hash) && capeBytes != null) {
                    writeImage(exchange, capeBytes);
                } else {
                    exchange.sendResponseHeaders(404, -1);
                }
                return;
            }

            if (path.endsWith(".json")) {
                String name = path.substring(1, path.length() - ".json".length());
                // CustomSkinLoader requests "/<name>.json"; when the name is literally "." the
                // resulting URL is "/.json", which would otherwise be parsed as an empty name.
                if (name.isEmpty() && ".".equals(username)) {
                    name = ".";
                }
                if (username != null && username.equalsIgnoreCase(name)) {
                    JsonObject root = new JsonObject();
                    root.addProperty("username", username);
                    JsonObject textures = new JsonObject();
                    if (skinBytes != null) textures.addProperty("default", skinHash);
                    if (capeBytes != null) textures.addProperty("cape", capeHash);
                    root.add("textures", textures);

                    byte[] body = root.toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(body);
                    }
                    return;
                }
            }

            exchange.sendResponseHeaders(404, -1);
        } finally {
            exchange.close();
        }
    }

    private static void writeImage(HttpExchange exchange, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "image/png");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    public String getRootUrl() {
        return rootUrl;
    }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
    }
}
