package com.example.launcher.service;

import com.example.launcher.model.LauncherConfig;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Anonymous "online" heartbeat. On a schedule it pings the site's ping.php with a random,
 * per-install instance id (no personal data) so the website can show how many launchers are
 * currently online. Entirely best-effort: every call is fire-and-forget and swallows all errors,
 * so a missing/slow/offline server never affects the launcher. Honours
 * {@link LauncherConfig#isTelemetryEnabled()} - the user can turn it off in Settings.
 */
public class OnlineStatsService {

    // ===========================================================================================
    //  Site domain. The PHP endpoints live at <BASE_URL>/ping.php (+online.php). Use the domain,
    //  not the raw IP (HTTPS certs are issued for the domain, and the IP can change).
    // ===========================================================================================
    private static final String BASE_URL = "https://blauncher.ru/api";

    /**
     * Номер версии берётся из общего места, а НЕ зашивается здесь.
     *
     * <p>Раньше тут стояло {@code "1.2"}, и это уезжало в каждый heartbeat: на сервере все
     * строки таблицы {@code launches} оказались с версией 1.2, хотя у людей стояли 1.12.x.
     * Разбивка по версиям в отчёте показывала одно число для всех и не могла показать,
     * сколько человек обновилось. См. {@link LauncherVersion}.
     */
    private static final String LAUNCHER_VERSION = LauncherVersion.plain();
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofMinutes(5);
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    private final Path gameDir;
    private final LauncherConfig config;
    // volatile: event() зовут из потоков загрузки и игры, а start()/stop() — из потока UI.
    private volatile ScheduledExecutorService scheduler;
    private String instanceId;

    public OnlineStatsService(Path gameDir, LauncherConfig config) {
        this.gameDir = gameDir;
        this.config = config;
    }

    /** Begins periodic heartbeats (first one immediately). No-op if telemetry is disabled or the
     *  endpoint hasn't been configured yet. Safe to call once at startup. */
    public void start() {
        if (config != null && !config.isTelemetryEnabled()) return;
        if (scheduler != null && !scheduler.isShutdown()) return;   // уже идёт — второй не нужен
        if (BASE_URL.contains("CHANGE-ME")) return;   // endpoint not configured yet
        instanceId = loadOrCreateInstanceId();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "online-heartbeat");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::ping, 0, HEARTBEAT_INTERVAL.toSeconds(), TimeUnit.SECONDS);
    }

    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void ping() {
        try {
            // Флаг перечитывается на КАЖДОМ тике, а не только при старте. Раньше start() смотрел
            // его один раз, поэтому выключение по ходу сессии ни на что не влияло — хотя javadoc
            // утверждал обратное. Это пояс к подтяжкам из настроек: даже если планировщик
            // почему-то остался жив, запросы уходить перестанут.
            if (config != null && !config.isTelemetryEnabled()) return;
            // instanceId is a UUID (hex + hyphens) and LAUNCHER_VERSION is digits/dots - both are
            // already URL-safe, so no extra encoding is needed.
            String url = BASE_URL + "/ping.php?id=" + instanceId + "&v=" + LAUNCHER_VERSION;
            Http.get(url, java.util.Map.of("User-Agent", "BLauncher/" + LAUNCHER_VERSION), HTTP_TIMEOUT);
        } catch (Exception ignored) {
            // Best-effort telemetry - never surface or retry; the next scheduled tick tries again.
        }
    }

    /**
     * Событие воронки «открыл лаунчер → нажал Играть → игра запустилась → чем закончилась»
     * (api/event.php, 2026-10-11). Зачем: на 10.10 почти половина людей пробыла в лаунчере
     * меньше 10 минут и не вернулась, а heartbeat не говорит, на каком шаге они ушли.
     *
     * <p>Уходит в том же фоновом потоке, что и heartbeat, — вызывающий (поток UI или загрузки)
     * не ждёт сети. Выключенная статистика или ещё не запущенный планировщик — тишина.
     * {@code detail} — только техника (версия, загрузчик, код выхода, имя класса ошибки):
     * всё, кроме латиницы, цифр и {@code .:=_-}, вырезается здесь же, до отправки.
     */
    public void event(String name, String detail) {
        ScheduledExecutorService s = scheduler;
        if (s == null || s.isShutdown()) return;
        try {
            s.execute(() -> sendEvent(name, detail, HTTP_TIMEOUT));
        } catch (Exception ignored) {
            // планировщик закрылся между проверкой и вызовом — событие просто теряется
        }
    }

    /**
     * То же, но в текущем потоке и с коротким таймаутом — для закрытия окна, после которого
     * сразу System.exit: фоновая задача туда уже не успеет.
     */
    public void eventNow(String name, String detail) {
        if (scheduler == null) return;
        sendEvent(name, detail, Duration.ofSeconds(2));
    }

    private void sendEvent(String name, String detail, Duration timeout) {
        try {
            if (config != null && !config.isTelemetryEnabled()) return;
            String d = detail == null ? "" : detail.replaceAll("[^A-Za-z0-9 .:=_\\-]", "");
            if (d.length() > 120) d = d.substring(0, 120);
            // Все три значения уже без кавычек и обратных слешей (uuid, имя из кода, d вычищен
            // выше) — экранировать JSON нечего.
            String body = "{\"id\":\"" + instanceId + "\",\"v\":\"" + LAUNCHER_VERSION
                    + "\",\"e\":\"" + name + "\",\"d\":\"" + d + "\"}";
            Http.post(BASE_URL + "/event.php", body, "application/json",
                    java.util.Map.of("User-Agent", "BLauncher/" + LAUNCHER_VERSION), timeout);
        } catch (Exception ignored) {
            // Как и heartbeat: статистика не повод что-либо показывать человеку или повторять.
        }
    }

    /** Random anonymous id, generated once per install and persisted so the same machine isn't
     *  counted as a brand-new user on every launch. */
    private String loadOrCreateInstanceId() {
        Path idFile = gameDir.resolve("telemetry_id.txt");
        try {
            if (Files.exists(idFile)) {
                String existing = Files.readString(idFile).trim();
                if (!existing.isEmpty()) return existing;
            }
            String fresh = UUID.randomUUID().toString();
            Files.createDirectories(idFile.getParent());
            Files.writeString(idFile, fresh, StandardCharsets.UTF_8);
            return fresh;
        } catch (Exception ex) {
            // Couldn't persist - fall back to an in-memory id for this session only.
            return UUID.randomUUID().toString();
        }
    }
}
