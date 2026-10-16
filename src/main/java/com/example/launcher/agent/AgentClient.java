package com.example.launcher.agent;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.example.launcher.service.Http;

import com.example.launcher.agent.AgentModels.ConsoleLine;
import com.example.launcher.agent.AgentModels.ConsoleSnapshot;
import com.example.launcher.agent.AgentModels.InstallStartedResult;
import com.example.launcher.agent.AgentModels.InstallStatus;
import com.example.launcher.agent.AgentModels.LicenseValidateResult;
import com.example.launcher.agent.AgentModels.PingResult;
import com.example.launcher.agent.AgentModels.PluginInstallStatus;
import com.example.launcher.agent.AgentModels.ServerStatus;
import com.example.launcher.agent.AgentModels.StartResult;
import com.example.launcher.agent.AgentModels.StopResult;

/**
 * HTTP client for the BLauncher VPS agent (see AGENT_API.md for the full
 * contract this implements). One instance per (host, port, key) — i.e. one
 * per VPS server the launcher is talking to.
 *
 * Talks HTTPS to the agent's self-signed certificate (agent.sh generates one at install time —
 * there's no domain name to get a real CA-signed cert for a bare VPS IP). Since it's
 * self-signed, the normal system trust store rejects it outright; this class instead PINS the
 * exact certificate the agent is expected to present, by its SHA-256 fingerprint, rather than
 * trusting any CA or any self-signed cert whatsoever — see {@link #fetchFingerprint} for first
 * contact (before the launcher has anything to pin yet) and the constructor overload that takes
 * an {@code expectedFingerprint} for every connection after that. Skipping the fingerprint check
 * entirely (i.e. trusting any cert) would make this no better than plain HTTP against
 * man-in-the-middle tampering — pinning is what actually buys anything here.
 *
 * All methods are synchronous/blocking. This matches the agent's own
 * blocking behavior on /server/stop (up to ~30s) — callers on the JavaFX
 * Application thread must invoke these from a background thread/executor
 * themselves (e.g. via Task/Runnable submitted through an ExecutorService),
 * exactly like the launcher already does for its other network-bound
 * services (Modrinth, version downloads, etc).
 *
 * Not thread-safe to call concurrently against the *same* AgentClient
 * instance for operations that assume sequential state (e.g. calling
 * startServer() from two threads at once) — the underlying HttpClient is
 * safe to share, but the agent's own state machine (one server at a time)
 * isn't, so serialize calls per-server at the launcher's call sites.
 */
public class AgentClient {

    private final String baseUrl;
    private final String agentKey;
    private final SSLSocketFactory pinnedSocketFactory;
    private final Duration requestTimeout;
    /** /server/stop can legitimately take up to ~30s on the agent side (see AGENT_API.md). */
    private final Duration stopTimeout;
    /** Set once a fingerprint has actually been observed via the pinning TrustManager below — see {@link #getObservedFingerprint()}. */
    private final AtomicReference<String> observedFingerprint = new AtomicReference<>();

    /**
     * Connects with no fingerprint pinned yet — ONLY intended for the very first contact with a
     * freshly-installed agent (calling {@link #fetchFingerprint()} to show the person what to
     * confirm before trusting it for real). Accepts whatever certificate the server presents
     * without verification; do not use this constructor for any call that touches the agent key
     * or actual server management — use the pinned constructor below for everything past first
     * contact.
     */
    public AgentClient(String host, int port) {
        this(host, port, null, null, Duration.ofSeconds(10), Duration.ofSeconds(35));
    }

    public AgentClient(String host, int port, String agentKey) {
        this(host, port, agentKey, null, Duration.ofSeconds(10), Duration.ofSeconds(35));
    }

    /** @param expectedFingerprint SHA-256 fingerprint (colon-separated hex, as shown by
     *                             agent.sh / GET /fingerprint) this connection must pin to.
     *                             Null only for the unpinned first-contact case above. */
    public AgentClient(String host, int port, String agentKey, String expectedFingerprint) {
        this(host, port, agentKey, expectedFingerprint, Duration.ofSeconds(10), Duration.ofSeconds(35));
    }

    public AgentClient(String host, int port, String agentKey, String expectedFingerprint,
                        Duration requestTimeout, Duration stopTimeout) {
        this.baseUrl = "https://" + host + ":" + port;
        this.agentKey = agentKey;
        this.requestTimeout = requestTimeout;
        this.stopTimeout = stopTimeout;
        this.pinnedSocketFactory = buildPinnedSslContext(expectedFingerprint).getSocketFactory();
    }

    /**
     * Builds an SSLContext whose TrustManager accepts a connection if and only if the
     * presented certificate's SHA-256 fingerprint matches expectedFingerprint (case-
     * insensitive) — or, when expectedFingerprint is null (first-contact mode), accepts
     * anything but records what it saw in observedFingerprint so the caller can read it back
     * via getObservedFingerprint() immediately after the call.
     *
     * This TrustManager is installed via SSLContext.init(null, new TrustManager[]{...}, ...),
     * which REPLACES the JVM's normal CA-chain verification for this SSLContext entirely —
     * checkServerTrusted() below is the sole authority on whether a handshake proceeds, not an
     * additional check layered on top of default trust. That matters here specifically because
     * the agent's certificate is self-signed and would otherwise fail standard CA validation
     * outright; there's no "also still check the normal chain" step to accidentally bypass.
     */
    private SSLContext buildPinnedSslContext(String expectedFingerprint) {
        TrustManager pinningTrustManager = new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
                // Client certs are never used by this agent — nothing to check.
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                if (chain == null || chain.length == 0) {
                    throw new CertificateException("Agent presented no certificate at all.");
                }
                String actual;
                try {
                    actual = sha256Fingerprint(chain[0]);
                } catch (NoSuchAlgorithmException | CertificateException e) {
                    throw new CertificateException("Could not compute certificate fingerprint: " + e.getMessage(), e);
                }
                observedFingerprint.set(actual);
                if (expectedFingerprint == null) {
                    // First-contact mode: no pin to check against yet — just record what we saw.
                    return;
                }
                if (!normalizeFingerprint(actual).equals(normalizeFingerprint(expectedFingerprint))) {
                    throw new CertificateException(
                            "Agent's certificate fingerprint does not match the pinned one. "
                                    + "Expected " + expectedFingerprint + " but got " + actual
                                    + " — this could mean the agent was reinstalled with a new certificate, "
                                    + "or that traffic is being intercepted. Re-confirm the fingerprint before trusting it again.");
                }
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };

        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{pinningTrustManager}, new SecureRandom());
            return ctx;
        } catch (Exception e) {
            throw new IllegalStateException("Could not build pinned SSLContext: " + e.getMessage(), e);
        }
    }

    private static String sha256Fingerprint(X509Certificate cert) throws NoSuchAlgorithmException, CertificateException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(cert.getEncoded());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < hash.length; i++) {
            if (i > 0) sb.append(':');
            sb.append(String.format("%02X", hash[i]));
        }
        return sb.toString();
    }

    private static String normalizeFingerprint(String fp) {
        return fp == null ? "" : fp.replace(":", "").toUpperCase(Locale.ROOT);
    }

    /** The fingerprint actually observed on the wire during the most recent request, if any —
     *  populated by the pinning TrustManager regardless of whether it accepted or rejected the
     *  connection (rejection still records what was seen, for a clearer error message upstream). */
    public String getObservedFingerprint() {
        return observedFingerprint.get();
    }

    // --- GET /fingerprint -----------------------------------------------
    // The one endpoint the agent serves with no Authorization check at all (see agent.js) —
    // meant to be called via the unpinned/first-contact constructor above, before the launcher
    // has any reason to trust this server enough to send it the real agent key.

    // --- GET /ping -----------------------------------------------------

    public PingResult ping() throws AgentException {
        Map<String, Object> body = requestObject("GET", "/ping", null, requestTimeout);
        return new PingResult(MiniJson.asString(body, "agent"), MiniJson.asString(body, "time"));
    }

    // --- POST /server/install --------------------------------------------

    /**
     * @param loader one of "vanilla", "paper", "fabric" — Forge/NeoForge/Quilt
     *               are not yet supported by the agent's installer (see AGENT_API.md)
     * @param version Minecraft version string, e.g. "1.21.1"
     */
    public InstallStartedResult startInstall(String loader, String version) throws AgentException {
        String requestBody = "{\"loader\":" + MiniJson.quote(loader)
                + ",\"version\":" + MiniJson.quote(version) + "}";
        Map<String, Object> body = requestObject("POST", "/server/install", requestBody, requestTimeout);
        return new InstallStartedResult(MiniJson.asString(body, "message"), MiniJson.asString(body, "pollUrl"));
    }

    // --- GET /server/install/status --------------------------------------

    public InstallStatus getInstallStatus() throws AgentException {
        Map<String, Object> body = requestObject("GET", "/server/install/status", null, requestTimeout);
        String rawStatus = MiniJson.asString(body, "status");
        InstallStatus.Status status;
        switch (rawStatus == null ? "" : rawStatus) {
            case "installing": status = InstallStatus.Status.INSTALLING; break;
            case "done": status = InstallStatus.Status.DONE; break;
            case "error": status = InstallStatus.Status.ERROR; break;
            default: status = InstallStatus.Status.IDLE;
        }
        return new InstallStatus(
                status,
                MiniJson.asString(body, "stage"),
                MiniJson.asInt(body, "pct", 0),
                MiniJson.asString(body, "error")
        );
    }

    /**
     * Convenience helper: blocks, polling /server/install/status until it
     * reports done or error. Runs on the calling thread — call this from a
     * background thread, not the JavaFX Application thread.
     *
     * @param pollInterval how often to poll
     * @param onProgress   called after every poll with the latest status (may be null) —
     *                     if updating UI from here, wrap the call in Platform.runLater
     * @throws AgentException if a poll request itself fails, or if the
     *                         install finishes with status == ERROR (in which
     *                         case the exception's message is the install error)
     */
    public InstallStatus awaitInstall(Duration pollInterval, java.util.function.Consumer<InstallStatus> onProgress)
            throws AgentException, InterruptedException {
        while (true) {
            InstallStatus status = getInstallStatus();
            if (onProgress != null) {
                onProgress.accept(status);
            }
            if (status.isFinished()) {
                if (status.status == InstallStatus.Status.ERROR) {
                    throw new AgentException(200, "install_error", status.error);
                }
                return status;
            }
            Thread.sleep(pollInterval.toMillis());
        }
    }

    // --- POST /server/plugins/install --------------------------------------
    // Not in the original AGENT_API.md contract — see its "POST /server/plugins/install" section
    // (added alongside this method). Places a purchased brand-plugin jar into the VPS's plugins/
    // folder; sha256 is optional but should be sent whenever known (see PluginsPanelController).

    public void startPluginInstall(String url, String fileName, String sha256) throws AgentException {
        StringBuilder json = new StringBuilder("{\"url\":").append(MiniJson.quote(url))
                .append(",\"fileName\":").append(MiniJson.quote(fileName));
        if (sha256 != null && !sha256.isBlank()) {
            json.append(",\"sha256\":").append(MiniJson.quote(sha256));
        }
        json.append("}");
        requestObject("POST", "/server/plugins/install", json.toString(), requestTimeout);
    }

    // --- POST /server/plugins/uninstall --------------------------------------
    // Synchronous, unlike install - deleting a file is instant, no poll-for-status needed.
    // @return true if a jar was actually removed, false if it wasn't there to begin with
    //   (not an error - the caller's desired end state, "no such file", is already true).

    public boolean uninstallPlugin(String fileName) throws AgentException {
        String json = "{\"fileName\":" + MiniJson.quote(fileName) + "}";
        Map<String, Object> body = requestObject("POST", "/server/plugins/uninstall", json, requestTimeout);
        return MiniJson.asBoolean(body, "removed", false);
    }

    // --- GET /server/plugins/install/status ---------------------------------

    public PluginInstallStatus getPluginInstallStatus() throws AgentException {
        Map<String, Object> body = requestObject("GET", "/server/plugins/install/status", null, requestTimeout);
        String rawStatus = MiniJson.asString(body, "status");
        PluginInstallStatus.Status status;
        switch (rawStatus == null ? "" : rawStatus) {
            case "downloading": status = PluginInstallStatus.Status.DOWNLOADING; break;
            case "verifying": status = PluginInstallStatus.Status.VERIFYING; break;
            case "done": status = PluginInstallStatus.Status.DONE; break;
            case "error": status = PluginInstallStatus.Status.ERROR; break;
            default: status = PluginInstallStatus.Status.IDLE;
        }
        return new PluginInstallStatus(
                status,
                MiniJson.asInt(body, "pct", 0),
                MiniJson.asString(body, "fileName"),
                MiniJson.asString(body, "error")
        );
    }

    /** Same blocking-poll convenience as {@link #awaitInstall}, for plugin installs. */
    public PluginInstallStatus awaitPluginInstall(Duration pollInterval, java.util.function.Consumer<PluginInstallStatus> onProgress)
            throws AgentException, InterruptedException {
        while (true) {
            PluginInstallStatus status = getPluginInstallStatus();
            if (onProgress != null) {
                onProgress.accept(status);
            }
            if (status.isFinished()) {
                if (status.status == PluginInstallStatus.Status.ERROR) {
                    throw new AgentException(200, "plugin_install_error", status.error);
                }
                return status;
            }
            Thread.sleep(pollInterval.toMillis());
        }
    }

    // --- POST /license/validate --------------------------------------------
    // Not in the original AGENT_API.md contract — see AgentModels.LicenseValidateResult's javadoc
    // for why this proxies through the VPS instead of PluginLicenseService calling blauncher.ru
    // directly from the launcher.

    public LicenseValidateResult validateLicense(String licenseKey) throws AgentException {
        String requestBody = "{\"key\":" + MiniJson.quote(licenseKey) + "}";
        Map<String, Object> body = requestObject("POST", "/license/validate", requestBody, requestTimeout);
        return new LicenseValidateResult(
                MiniJson.asBoolean(body, "valid", false),
                MiniJson.asString(body, "pluginId"),
                MiniJson.asString(body, "message")
        );
    }

    // --- POST /server/start -----------------------------------------------

    public StartResult startServer() throws AgentException {
        return startServer(2048);
    }

    public StartResult startServer(int ramMb) throws AgentException {
        String requestBody = "{\"ramMb\":" + ramMb + "}";
        Map<String, Object> body = requestObject("POST", "/server/start", requestBody, requestTimeout);
        return new StartResult(MiniJson.asBoolean(body, "started", false));
    }

    // --- POST /server/stop -------------------------------------------------
    // Uses stopTimeout, not the default requestTimeout — the agent blocks the
    // HTTP response until the Minecraft process actually exits (or is force-
    // killed after its own internal 30s grace period).

    public StopResult stopServer() throws AgentException {
        Map<String, Object> body = requestObject("POST", "/server/stop", null, stopTimeout);
        return new StopResult(
                MiniJson.asBoolean(body, "stopped", false),
                MiniJson.asBoolean(body, "forced", false),
                MiniJson.asString(body, "reason")
        );
    }

    // --- GET /server/status -------------------------------------------------

    public ServerStatus getStatus() throws AgentException {
        Map<String, Object> body = requestObject("GET", "/server/status", null, requestTimeout);
        return new ServerStatus(
                MiniJson.asBoolean(body, "running", false),
                MiniJson.asString(body, "loader"),
                MiniJson.asString(body, "version")
        );
    }

    /** Значение кортежа как строка: элемент может приехать числом, и каст к String упал бы. */
    private static String str(Object v) {
        if (v == null) return "";
        if (v instanceof String s) return s;
        if (v instanceof Double d && d == Math.floor(d) && !d.isInfinite()) {
            return String.valueOf((long) (double) d);
        }
        return String.valueOf(v);
    }

    // --- GET /server/console -------------------------------------------------

    public ConsoleSnapshot getConsole() throws AgentException {
        Map<String, Object> body = requestObject("GET", "/server/console", null, requestTimeout);
        // Пустая консоль — это ответ {"ok":true} БЕЗ поля lines, и он совершенно законный.
        // Раньше тут был непроверенный каст с последующим rawLines.size(), то есть NPE; а
        // строка с числовой меткой времени или кортеж из двух элементов давали
        // ClassCastException / IndexOutOfBounds. AgentPoller ловит RuntimeException, поэтому
        // симптомом было «Unexpected polling error: null» раз в секунду и вечно пустая консоль —
        // сообщение, не указывающее даже близко на причину.
        List<Object> rawLines = MiniJson.asArray(body.get("lines"));
        if (rawLines == null) {
            return new ConsoleSnapshot(new ArrayList<>());
        }
        List<ConsoleLine> lines = new ArrayList<>(rawLines.size());
        for (Object rawLine : rawLines) {
            List<Object> tuple = MiniJson.asArray(rawLine);
            if (tuple == null || tuple.size() < 3) continue;   // строку непонятной формы пропускаем
            String time = str(tuple.get(0));
            String levelStr = str(tuple.get(1));
            String text = str(tuple.get(2));
            lines.add(new ConsoleLine(time, ConsoleLine.parseLevel(levelStr), text));
        }
        return new ConsoleSnapshot(lines);
    }

    // --- POST /server/console/command -----------------------------------------

    public void sendCommand(String command) throws AgentException {
        String requestBody = "{\"command\":" + MiniJson.quote(command) + "}";
        requestObject("POST", "/server/console/command", requestBody, requestTimeout);
    }

    // --- GET /versions -----------------------------------------------------------
    // Additive endpoint, not in the original AGENT_API.md contract — lists every Minecraft
    // version Mojang publishes (release + snapshot), so a VPS install can offer snapshots too.

    // --- POST /agent/uninstall ----------------------------------------------
    // Irreversible: stops whatever Minecraft server is running, then has the agent tear itself
    // down entirely on the VPS (systemd service, TLS cert, /opt/blauncher-agent) - see
    // AGENT_API.md. The agent deletes itself moments after answering, so a transport-level
    // failure on THIS call specifically (connection reset, no response) isn't necessarily proof
    // the uninstall didn't happen - callers should treat "unreachable right after calling this"
    // as likely success, not retry it expecting a clean 200 every time.

    public void uninstall() throws AgentException {
        requestObject("POST", "/agent/uninstall", null, requestTimeout);
    }

    // --- internals ------------------------------------------------------------

    private Map<String, Object> requestObject(String method, String path, String jsonBody, Duration timeout)
            throws AgentException {
        Http.Response response;
        try {
            response = Http.request(
                    method,
                    baseUrl + path,
                    "GET".equals(method) ? null : jsonBody,
                    jsonBody != null ? "application/json" : null,
                    Map.of("Authorization", "Bearer " + agentKey),
                    timeout,
                    pinnedSocketFactory);
        } catch (IOException e) {
            throw new AgentException("Could not reach agent at " + baseUrl + path + ": " + e.getMessage(), e);
        }

        Map<String, Object> parsed;
        try {
            Object rawParsed = MiniJson.parse(response.text());
            parsed = MiniJson.asObject(rawParsed);
        } catch (RuntimeException e) {
            throw new AgentException("Agent returned invalid JSON from " + path + ": " + e.getMessage(), e);
        }
        // asObject теперь отдаёт null вместо ClassCastException, поэтому случай «пришёл не
        // объект» (массив, строка, HTML-заглушка прокси) надо отвергнуть здесь явно — иначе
        // ошибка всплыла бы позже и с менее понятным текстом.
        if (parsed == null) {
            throw new AgentException("Agent returned a non-object JSON body from " + path, null);
        }

        boolean ok = MiniJson.asBoolean(parsed, "ok", false);
        if (!ok || response.status() >= 300) {
            String errorField = MiniJson.asString(parsed, "error");
            String messageField = MiniJson.asString(parsed, "message");
            String display = messageField != null ? messageField : errorField;
            throw new AgentException(response.status(), errorField, display);
        }

        return parsed;
    }
}
