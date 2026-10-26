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
import java.nio.file.OpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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

        /** Связь оборвалась, через {@code waitMs} мс переподключимся и докачаем с того же места. */
        default void reconnecting(long waitMs) { }
    }

    /** То же, что {@link #download(String, Path, Map, Duration)}, но сообщает ход в {@code progress}
     *  (из потока загрузки — переносить в UI-поток должен получатель). */
    public static long download(String url, Path destination, Map<String, String> headers,
                                Duration timeout, Progress progress) throws IOException {
        Path parent = destination.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path part = destination.resolveSibling(destination.getFileName() + ".part");
        Files.deleteIfExists(part);
        long have = 0;        // сколько уже лежит в .part
        long total = -1;      // полный размер файла, если сервер его назвал
        long offlineSince = -1;
        int pause = 0;
        try {
            while (true) {
                try {
                    Map<String, String> h = headers;
                    if (have > 0) {
                        h = new java.util.HashMap<>(headers);
                        h.put("Range", "bytes=" + have + "-");
                    }
                    HttpURLConnection conn = connect("GET", url, h, null, null, timeout, null);
                    int code = conn.getResponseCode();
                    if (code < 200 || code >= 300) {
                        drain(conn.getErrorStream());
                        if (have == 0) return -1;
                        // Докачка не удалась (416 и т.п.) — начинаем файл заново.
                        have = 0;
                        Files.deleteIfExists(part);
                        continue;
                    }
                    boolean resumed = have > 0 && code == 206;
                    if (have > 0 && !resumed) have = 0;   // сервер не умеет Range — заново
                    long length = conn.getContentLengthLong();
                    if (length >= 0) total = have + length;
                    if (progress != null) progress.update(have, total);
                    long got = 0;
                    try (InputStream in = conn.getInputStream();
                         OutputStream out = Files.newOutputStream(part, resumed
                                 ? new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.APPEND}
                                 : new OpenOption[]{StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                                                    StandardOpenOption.WRITE})) {
                        byte[] buf = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            out.write(buf, 0, n);
                            have += n;
                            got += n;
                            DOWNLOADED.addAndGet(n);
                            if (got > 256 * 1024) { offlineSince = -1; pause = 0; }   // связь есть
                            if (progress != null) progress.update(have, total);
                        }
                    }
                    // Сжатый ответ: Content-Length — про сжатое тело, сверять с ним прочитанное нельзя.
                    String enc = conn.getContentEncoding();
                    boolean exact = enc == null || enc.equalsIgnoreCase("identity");
                    if (exact && length >= 0 && got < length) {
                        throw new IOException("Соединение оборвалось: получено " + have + " из " + total + " байт ("
                                + conn.getURL().getHost() + ")");
                    }
                    break;
                } catch (IOException e) {
                    // Переподключение (21.10.2026). Раньше любой обрыв — пропал Wi-Fi на 20 секунд,
                    // антивирус разорвал соединение — начинал файл с нуля или ронял всю загрузку:
                    // 7267c860 трижды получил JRE почти целиком и трижды остался без Java.
                    if (!isReconnectable(e)) throw e;
                    long now = System.currentTimeMillis();
                    if (offlineSince < 0) offlineSince = now;
                    if (now - offlineSince > RECONNECT_BUDGET.toMillis()) throw e;
                    pause = Math.min(pause == 0 ? 1000 : pause * 2, 10_000);
                    if (progress != null) progress.reconnecting(pause);
                    try {
                        Thread.sleep(pause);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new java.io.InterruptedIOException("загрузка прервана");
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            try { Files.deleteIfExists(part); } catch (IOException ignored) {}
            throw e;
        }
        Files.move(part, destination, StandardCopyOption.REPLACE_EXISTING);
        return have;
    }

    /**
     * Всего байт, принятых download() с запуска лаунчера, — для отметки «сколько скачала первая
     * загрузка игры и за сколько» (22.10.2026: новичок ждал 12 минут и закрыл лаунчер на 94 %,
     * а был ли это медленный интернет или огромная сборка — не видно).
     */
    public static final java.util.concurrent.atomic.AtomicLong DOWNLOADED = new java.util.concurrent.atomic.AtomicLong();

    /** Сколько ждать возвращения связи без единого полученного байта, прежде чем сдаться. */
    static Duration RECONNECT_BUDGET = Duration.ofSeconds(90);

    /**
     * Сбой связи, после которого есть смысл переподключиться: обрыв, таймаут, отказ в соединении,
     * пропавший DNS (так выглядит отключённый Wi-Fi). Ошибки диска и доступа к файлу — нет.
     */
    static boolean isReconnectable(IOException e) {
        if (e instanceof java.nio.file.FileSystemException) return false;
        if (e instanceof java.io.InterruptedIOException && !(e instanceof java.net.SocketTimeoutException)) return false;
        if (e instanceof java.net.SocketException || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.UnknownHostException || e instanceof javax.net.ssl.SSLException
                || e instanceof java.io.EOFException) {
            return true;
        }
        String m = e.getMessage() == null ? "" : e.getMessage();
        return m.startsWith("Соединение оборвалось") || m.contains("Premature EOF")
                || m.contains("Connection reset") || m.contains("Unexpected end of file");
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
