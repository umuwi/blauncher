package com.example.launcher.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;

/**
 * The launcher's HTTP client, built on {@link HttpURLConnection}.
 *
 * <p><b>Why not {@code java.net.http}.</b> On the Java 21.0.10 runtime BLauncher ships, that stack
 * can get permanently stuck spinning after a connection breaks: {@code SSLFlowDelegate$Reader
 * .processData} -> {@code SSLEngine.unwrap}, endlessly re-submitted by {@code SequentialScheduler}.
 * Captured live on 2026-08-05 with 30 worker threads at ~45% of a core each, allocating a fresh
 * ByteBuffer per iteration - all 12 cores pinned and 2 GB resident, long after the downloads that
 * created them had finished, and only ever released by killing the launcher. We cannot ship a newer
 * runtime (auto-update replaces the jar, not the app image), so the fix is to not use that code
 * path at all. HttpURLConnection has no such machinery, and its keep-alive pool still reuses
 * connections across the thousands of small GETs an install makes.
 *
 * <p><b>Never call {@code disconnect()}</b> on a connection from here: draining and closing the
 * stream returns the socket to the keep-alive pool, {@code disconnect()} throws it away.
 */
public final class Http {

    private Http() {}

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_REDIRECTS = 5;
    private static final Map<String, String> NO_HEADERS = Map.of();

    /** An HTTP response with its body already read into memory. */
    public record Response(int status, byte[] body) {
        public boolean ok() { return status >= 200 && status < 300; }
        public String text() { return new String(body, StandardCharsets.UTF_8); }
    }

    public static Response get(String url) throws IOException {
        return get(url, NO_HEADERS, DEFAULT_TIMEOUT);
    }

    public static Response get(String url, Map<String, String> headers) throws IOException {
        return get(url, headers, DEFAULT_TIMEOUT);
    }

    public static Response get(String url, Map<String, String> headers, Duration timeout) throws IOException {
        return request("GET", url, null, null, headers, timeout, null);
    }

    /** POST with an optional body; pass {@code body == null} for a bodyless POST. */
    public static Response post(String url, String body, String contentType,
                                Map<String, String> headers, Duration timeout) throws IOException {
        return request("POST", url, body, contentType, headers, timeout, null);
    }

    /**
     * The general form. {@code sslFactory} is for callers that pin the server certificate
     * themselves (the VPS agent client) - pass null everywhere else to use the JVM's default
     * trust store. Hostname verification is left at HttpsURLConnection's default either way,
     * matching what the java.net.http client did before.
     */
    public static Response request(String method, String url, String body, String contentType,
                                   Map<String, String> headers, Duration timeout,
                                   SSLSocketFactory sslFactory) throws IOException {
        byte[] payload = body == null ? null : body.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = connect(method, url, headers, payload, contentType, timeout, sslFactory);
        // no disconnect() - see the class javadoc
        return new Response(conn.getResponseCode(), readBody(conn));
    }

    /**
     * Streams a download straight to {@code destination} instead of buffering it in memory, for the
     * cases where the body is a whole JDK or launcher jar. Writes to a sibling .part file first and
     * moves it into place, so an interrupted download can never leave a truncated file that later
     * looks complete.
     *
     * @return the number of bytes written, or -1 if the server answered with a non-2xx status
     *         (in which case nothing is written).
     */
    public static long download(String url, Path destination, Map<String, String> headers,
                                Duration timeout) throws IOException {
        return download(url, destination, headers, timeout, null);
    }

    /** Ход загрузки: сколько байт уже записано и сколько всего (-1, если сервер не сказал). */
    @FunctionalInterface
    public interface Progress {
        void update(long done, long total);
    }

    /** То же, что {@link #download(String, Path, Map, Duration)}, но сообщает ход в {@code progress}
     *  (из потока загрузки — переносить в UI-поток должен получатель). */
    public static long download(String url, Path destination, Map<String, String> headers,
                                Duration timeout, Progress progress) throws IOException {
        HttpURLConnection conn = connect("GET", url, headers, null, null, timeout, null);
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            drain(conn.getErrorStream());
            return -1;
        }
        Path parent = destination.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path part = destination.resolveSibling(destination.getFileName() + ".part");
        long length = conn.getContentLengthLong();
        long total = 0;
        if (progress != null) progress.update(0, length);
        try (InputStream in = conn.getInputStream();
             OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                total += n;
                if (progress != null) progress.update(total, length);
            }
            out.close();
            checkComplete(conn, total);   // обрезанный файл не переименовываем в настоящий — см. там
        } catch (IOException e) {
            try { Files.deleteIfExists(part); } catch (IOException ignored) {}
            throw e;
        }
        Files.move(part, destination, StandardCopyOption.REPLACE_EXISTING);
        return total;
    }

    /** Заголовки, которые нельзя отдавать чужому хосту при редиректе. */
    private static boolean isSensitiveHeader(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.equals("authorization") || n.equals("proxy-authorization")
                || n.equals("cookie") || n.equals("x-admin-password")
                || n.equals("x-api-key") || n.equals("x-auth-token");
    }

    /** Хост URL в нижнем регистре, или пустая строка, если разобрать не удалось. */
    private static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host.toLowerCase();
        } catch (Exception e) {
            return "";
        }
    }

    /** Тот же хост, что и в исходном запросе. Поддомен считается ДРУГИМ хостом: отдать токен
     *  на «что-то.example.com» ничем не безопаснее, чем на чужой домен. */
    private static boolean sameHost(String originalHost, String url) {
        String now = hostOf(url);
        return !originalHost.isEmpty() && originalHost.equals(now);
    }

    // ---------- internals ----------

    /**
     * Opens the connection, following redirects by hand. HttpURLConnection's own redirect handling
     * silently refuses to cross between http and https, which is exactly what release downloads
     * (Adoptium -> GitHub -> object storage) do, so it would hand back a 301/302 body instead of
     * the file.
     */
    private static HttpURLConnection connect(String method, String url, Map<String, String> headers,
                                             byte[] body, String contentType, Duration timeout,
                                             SSLSocketFactory sslFactory) throws IOException {
        String current = url;
        final String originalHost = hostOf(current);
        String currentMethod = method;
        byte[] currentBody = body;

        for (int hop = 0; ; hop++) {
            HttpURLConnection conn = (HttpURLConnection) URI.create(current).toURL().openConnection();
            if (sslFactory != null && conn instanceof HttpsURLConnection https) {
                https.setSSLSocketFactory(sslFactory);
            }
            conn.setInstanceFollowRedirects(false);
            conn.setRequestMethod(currentMethod);
            conn.setConnectTimeout((int) timeout.toMillis());
            conn.setReadTimeout((int) timeout.toMillis());
            conn.setRequestProperty("User-Agent", "BLauncher");
            if (headers != null) {
                // Заголовки с секретами НЕ пересылаем на другой хост. Все переданные вызывающим
                // заголовки отправлялись заново после каждого редиректа, в том числе межхостового:
                // ZeroTierService.createRoom передаёт «Authorization: bearer <токен ZeroTier
                // Central>», и ответ 302 от api.zerotier.com отдал бы этот токен цели редиректа.
                boolean sameHost = sameHost(originalHost, current);
                for (Map.Entry<String, String> h : headers.entrySet()) {
                    if (!sameHost && isSensitiveHeader(h.getKey())) {
                        continue;
                    }
                    conn.setRequestProperty(h.getKey(), h.getValue());
                }
            }
            if (currentBody != null) {
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(currentBody.length);
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(currentBody);
                }
            }

            int code = conn.getResponseCode();
            boolean redirect = code == 301 || code == 302 || code == 303 || code == 307 || code == 308;
            if (!redirect || hop >= MAX_REDIRECTS) {
                return conn;
            }

            String location = conn.getHeaderField("Location");
            drain(conn.getInputStream());
            drain(conn.getErrorStream());
            if (location == null || location.isBlank()) {
                return conn;
            }
            current = URI.create(current).resolve(location).toString();
            // 303 always downgrades to GET; 301/302 do so for non-GET in every real client.
            if (code == 303 || ((code == 301 || code == 302) && !"GET".equals(currentMethod))) {
                currentMethod = "GET";
                currentBody = null;
            }
        }
    }

    private static byte[] readBody(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        try (InputStream in = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream()) {
            if (in == null) return new byte[0];
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[16 * 1024];
            int n;
            while ((n = in.read(chunk)) != -1) buffer.write(chunk, 0, n);
            checkComplete(conn, buffer.size());
            return buffer.toByteArray();
        }
    }

    /**
     * Сервер назвал длину, а пришло меньше — это обрыв, а не ответ. HttpURLConnection не всегда
     * бросает исключение, когда соединение рвётся посреди тела: поток просто кончается. 14.10.2026
     * так приходили обрезанные netty-all.jar (Forge 1.12.2 падал при каждом запуске) и список
     * версий (JsonSyntaxException «End of input at column 146869» вместо понятной сетевой ошибки
     * и повтора). Сжатый ответ не проверяем: там длина — сжатых байт.
     */
    private static void checkComplete(HttpURLConnection conn, long received) throws IOException {
        long expected = conn.getContentLengthLong();
        String encoding = conn.getContentEncoding();
        if (expected < 0 || (encoding != null && !encoding.equalsIgnoreCase("identity"))) return;
        if ("HEAD".equals(conn.getRequestMethod())) return;
        if (received < expected) {
            throw new IOException("Соединение оборвалось: получено " + received + " из " + expected + " байт ("
                    + conn.getURL().getHost() + ")");
        }
    }

    private static void drain(InputStream in) {
        if (in == null) return;
        try (InputStream stream = in) {
            byte[] chunk = new byte[8 * 1024];
            while (stream.read(chunk) != -1) { /* discard, so the socket can be reused */ }
        } catch (IOException ignored) {
        }
    }
}
