package ru.blauncher.blcore;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Who on screen plays through BLauncher.
 *
 * <p>The launcher leaves the mark: when it starts the game it tells blauncher.ru "this nickname, with
 * this UUID, plays through BLauncher" (and uploads the cape, if any). This class reads those marks
 * for the players the game is about to draw. Lookups never block a frame: an unknown player is
 * queued, answered as "not BL" for now, and the queue goes out as one batched request a moment
 * later. Answers are cached for a few minutes, so a full tab list costs one request every five
 * minutes, and nothing at all while the server is unreachable.
 *
 * <p>A name only counts together with its UUID: the server compares it with the UUID the mark was
 * left with, or with the name's offline UUID. That way a stranger who happens to share a nickname
 * on an online-mode server does not get somebody else's badge.
 */
public final class BlRegistry {

    public record Mark(boolean bl, String capeHash) {
        static final Mark NONE = new Mark(false, null);
    }

    private record Entry(Mark mark, long expiresAt) {}

    private static final String API = "https://blauncher.ru/api/bl.php";
    private static final long TTL_MS = 5 * 60_000;
    private static final long RETRY_MS = 60_000;
    private static final int BATCH = 80;

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> PENDING = ConcurrentHashMap.newKeySet();
    private static volatile long pausedUntil;
    private static ScheduledExecutorService worker;

    private BlRegistry() {}

    static synchronized void start() {
        if (worker != null) return;
        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BLCore registry");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        worker.scheduleWithFixedDelay(BlRegistry::flush, 1, 1, TimeUnit.SECONDS);
    }

    /**
     * The mark for a player, from cache; schedules a lookup when there is none or it is old.
     * Never null, never blocks.
     */
    public static Mark get(String name, UUID uuid) {
        if (name == null || name.isEmpty()) return Mark.NONE;
        if (BlConfig.isSelf(name)) {
            return BlConfig.selfBadge() ? new Mark(true, BlConfig.selfCapeHash()) : Mark.NONE;
        }
        String key = key(name, uuid);
        Entry e = CACHE.get(key);
        long now = System.currentTimeMillis();
        if (e == null || e.expiresAt() < now) {
            if (PENDING.size() < 2000) PENDING.add(key);
        }
        return e == null ? Mark.NONE : e.mark();
    }

    private static String key(String name, UUID uuid) {
        return name.toLowerCase(Locale.ROOT) + ":" + (uuid == null ? "" : uuid.toString().replace("-", ""));
    }

    private static void flush() {
        try {
            if (PENDING.isEmpty() || System.currentTimeMillis() < pausedUntil) return;
            List<String> batch = new ArrayList<>();
            Iterator<String> it = PENDING.iterator();
            while (it.hasNext() && batch.size() < BATCH) {
                batch.add(it.next());
                it.remove();
            }
            Map<String, Mark> answer = query(batch);
            long expires = System.currentTimeMillis() + TTL_MS;
            for (String key : batch) {
                CACHE.put(key, new Entry(answer.getOrDefault(key, Mark.NONE), expires));
            }
            if (CACHE.size() > 5000) CACHE.clear();
        } catch (Exception e) {
            // offline, blocked, or the site is down: try again in a minute, draw no badges meanwhile
            pausedUntil = System.currentTimeMillis() + RETRY_MS;
        }
    }

    private static Map<String, Mark> query(List<String> keys) throws Exception {
        String q = URLEncoder.encode(String.join(",", keys), StandardCharsets.UTF_8);
        HttpURLConnection c = (HttpURLConnection) URI.create(API + "?q=" + q).toURL().openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "BLCore/" + BlConfig.VERSION);
        try {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            String body = readLimited(c.getInputStream(), 256 * 1024);
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            Map<String, Mark> out = new ConcurrentHashMap<>();
            if (!root.has("marks")) return out;
            for (Map.Entry<String, JsonElement> m : root.getAsJsonObject("marks").entrySet()) {
                JsonElement cape = m.getValue().isJsonObject() ? m.getValue().getAsJsonObject().get("cape") : null;
                String hash = cape == null || cape.isJsonNull() ? null : cape.getAsString();
                if (hash != null && !hash.matches("[0-9a-f]{16,64}")) hash = null;
                out.put(m.getKey(), new Mark(true, hash));
            }
            return out;
        } finally {
            c.disconnect();
        }
    }

    static String readLimited(InputStream in, int limit) throws Exception {
        try (in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > limit) throw new IllegalStateException("response too large");
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    /** Raw cape bytes from the site; size-capped, PNG only. */
    public static byte[] downloadCape(String hash) throws Exception {
        if (!hash.matches("[0-9a-f]{16,64}")) throw new IllegalArgumentException("bad hash");
        HttpURLConnection c = (HttpURLConnection) URI.create("https://blauncher.ru/bl/capes/" + hash + ".png")
                .toURL().openConnection();
        c.setConnectTimeout(5000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "BLCore/" + BlConfig.VERSION);
        try (InputStream in = c.getInputStream()) {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 64 * 1024) throw new IllegalStateException("cape too large");
            }
            byte[] bytes = out.toByteArray();
            if (bytes.length < 8 || bytes[0] != (byte) 0x89 || bytes[1] != 'P') throw new IllegalStateException("not a png");
            return bytes;
        } finally {
            c.disconnect();
        }
    }

    /** Runs a background job on the registry's own thread. */
    public static void submit(Runnable job) {
        start();
        worker.execute(() -> {
            try {
                job.run();
            } catch (Throwable ignored) {
                // a cape that fails to load is simply not drawn
            }
        });
    }
}
