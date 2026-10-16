package com.example.launcher.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Microsoft OAuth2 Device Code Flow → Xbox Live → XSTS → Minecraft token.
 * Stores the latest Minecraft access token for launching with online-mode support.
 */
public class AuthService {

    // Public client ID from PrismLauncher (https://github.com/PrismLauncher/PrismLauncher),
    // an established open-source launcher already approved by Microsoft for the Minecraft API.
    // New self-registered Azure apps require manual Microsoft review (https://aka.ms/mce-reviewappid)
    // before login_with_xbox works, which can take weeks; this ID is already past that gate.
    private static final String MICROSOFT_CLIENT_ID = "c36a9fb6-4f2a-41ff-90bd-ae7cc92031eb";

    private static final String DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String TOKEN_URL      = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBL_AUTH_URL   = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTH_URL  = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_AUTH_URL    = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_PROFILE_URL = "https://api.minecraftservices.com/minecraft/profile";

    private String minecraftAccessToken;
    /**
     * Когда истекает minecraftAccessToken — АБСОЛЮТНАЯ метка времени (epoch, мс), а не
     * длительность.
     *
     * <p>Раньше срок не отслеживался вообще: рядом стоял комментарий «valid for ~24h», а
     * {@code expires_in} из ответа login_with_xbox просто отбрасывался. Восстановление сессии
     * выполняется один раз при старте, поэтому у лаунчера, оставленного открытым на ночь (а для
     * этого приложения это норма — оно держит сервер и опрашивает ZeroTier каждые 5 секунд),
     * утром {@code isLoggedIn()} по-прежнему true, и в игру уходит токен, просроченный несколько
     * часов назад. Настройка UserApiService в игре падает, «Сетевая игра» серая, диагностики
     * никакой, и вылечить это можно было только перезапуском лаунчера.
     */
    private volatile long minecraftTokenExpiresAt;
    /** За сколько до истечения считаем токен несвежим: обновиться заранее дешевле, чем узнать
     *  о просрочке уже из игры. */
    private static final long TOKEN_REFRESH_MARGIN_MS = 10 * 60 * 1000L;
    private String minecraftUsername;
    private String minecraftUuid;
    private String deviceCode;             // used for polling
    private String userCode;               // shown to user
    private String verificationUri;        // https://microsoft.com/link
    private int    expiresIn;
    private int    interval;               // poll interval in seconds

    // Internal state for the polling thread
    private volatile boolean polling = false;
    private volatile String lastStatus = "Not logged in";
    private Consumer<String> logger = msg -> {};

    // Persisted across launcher restarts so the user doesn't have to repeat the device code
    // flow every time - the "offline_access" scope requested in beginLogin() is what makes
    // Microsoft hand out this refresh_token in the first place.
    private final Path accountFile;
    private final Gson gson = new Gson();
    private String refreshToken;

    public AuthService(Path accountFile) {
        this.accountFile = accountFile;
    }

    /**
     * Lets the UI layer receive a line for every step of the auth flow
     * (device code request, poll attempts, Xbox/XSTS/Minecraft exchange).
     */
    public void setLogger(Consumer<String> logger) {
        this.logger = (logger != null) ? logger : (msg -> {});
    }

    private void log(String message) {
        logger.accept("[AUTH] " + message);
    }

    private static class SavedAccount {
        String refreshToken;
        /** Имя аккаунта на момент сохранения. Нужно списку профилей: показать, к какому профилю
         *  какой аккаунт привязан, можно только так — иначе для этого пришлось бы тянуть
         *  refresh-токен в сеть. Отсутствует в файлах, записанных до появления поля. */
        String username;
    }

    private void saveRefreshToken() {
        try {
            Files.createDirectories(accountFile.getParent());
            SavedAccount saved = new SavedAccount();
            saved.refreshToken = refreshToken;
            saved.username = minecraftUsername;
            Files.writeString(accountFile, gson.toJson(saved));
        } catch (IOException e) {
            log("Failed to save account for next time: " + e.getMessage());
        }
    }

    /**
     * Имя аккаунта, сохранённого в файле, без единого сетевого запроса: по нему список профилей
     * показывает, у каких профилей вход уже есть. Возвращает null, если файла нет, он пуст или
     * записан версией без этого поля.
     */
    public static String peekSavedUsername(Path accountFile) {
        try {
            if (accountFile == null || !Files.exists(accountFile)) return null;
            SavedAccount saved = new Gson().fromJson(Files.readString(accountFile), SavedAccount.class);
            if (saved == null || saved.refreshToken == null || saved.refreshToken.isBlank()) return null;
            return saved.username == null || saved.username.isBlank() ? "" : saved.username;
        } catch (Exception e) {
            return null;
        }
    }

    private void clearSavedAccount() {
        refreshToken = null;
        try {
            Files.deleteIfExists(accountFile);
        } catch (IOException ignored) {
        }
    }

    /**
     * Step 0 (optional) – try to silently restore the previous session using a saved
     * refresh_token, without involving the device code flow or opening a browser.
     * Call from a background thread. Returns false (without showing any error) when there's
     * no saved account, or when the saved session is no longer valid.
     */
    public boolean tryRestoreSession() {
        if (!Files.exists(accountFile)) return false;

        try {
            SavedAccount saved = gson.fromJson(Files.readString(accountFile), SavedAccount.class);
            if (saved == null || saved.refreshToken == null || saved.refreshToken.isBlank()) {
                return false;
            }
            refreshToken = saved.refreshToken;
        } catch (IOException e) {
            log("Failed to read saved account: " + e.getMessage());
            return false;
        }

        log("Restoring saved account...");

        String msAccessToken = null;
        IOException lastNetworkError = null;

        // Retried, because a single failed round-trip to login.microsoftonline.com used to cost
        // the user their whole session: one "Connect timed out" (routine on a flaky link or over
        // a VPN) dropped the launcher straight back to the offline/logged-out state and made them
        // redo the device-code flow, even though the saved refresh_token was still perfectly good.
        for (int attempt = 1; attempt <= RESTORE_ATTEMPTS && msAccessToken == null; attempt++) {
            if (attempt > 1) {
                log("Retrying session restore (attempt " + attempt + " of " + RESTORE_ATTEMPTS + ")...");
                try {
                    Thread.sleep(RESTORE_RETRY_DELAY_MS * (attempt - 1L));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            try {
                String body = "client_id=" + URLEncoder.encode(MICROSOFT_CLIENT_ID, StandardCharsets.UTF_8)
                            + "&grant_type=refresh_token"
                            + "&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)
                            + "&scope=" + URLEncoder.encode("XboxLive.signin offline_access", StandardCharsets.UTF_8);

                JsonObject resp = postJson(TOKEN_URL, body, null);

                if (resp != null && resp.has("error")) {
                    String err = resp.get("error").getAsString();
                    // Only a refusal of the token itself means "sign in again" - anything else
                    // (a 5xx, throttling, a gateway error) is Microsoft having a bad minute, and
                    // deleting the account file over it would log the user out permanently.
                    if (isTerminalRefreshError(err)) {
                        log("Saved session expired (" + err + "), please sign in again.");
                        clearSavedAccount();
                        return false;
                    }
                    log("Microsoft returned a temporary error (" + err + "), keeping saved session.");
                    continue;
                }
                if (resp == null || !resp.has("access_token")) {
                    log("Unexpected response while restoring session, keeping saved session.");
                    continue;
                }

                rememberRefreshToken(resp);
                msAccessToken = resp.get("access_token").getAsString();
            } catch (IOException e) {
                lastNetworkError = e;
                log("Could not reach Microsoft: " + e.getMessage());
            }
        }

        if (msAccessToken == null) {
            lastStatus = "Could not reach Microsoft - saved session kept, try again later.";
            log(lastNetworkError != null
                    ? "Giving up restoring the session for now (" + lastNetworkError.getMessage()
                        + "). The saved account was NOT cleared - it will be retried on next start."
                    : "Giving up restoring the session for now. The saved account was NOT cleared.");
            return false;
        }

        try {
            if (exchangeFullChain(msAccessToken)) {
                lastStatus = minecraftUsername != null ? "Logged in as " + minecraftUsername
                        : "Signed in (no Minecraft license found).";
                log(minecraftUsername != null ? "Restored session as " + minecraftUsername
                        : "Restored session (no Minecraft license found).");
                return true;
            }
            return false;
        } catch (IOException e) {
            log("Failed to restore session: " + e.getMessage());
            return false;
        }
    }

    /** How many times a session restore retries before giving up (keeping the saved account). */
    private static final int RESTORE_ATTEMPTS = 3;
    /** Base backoff between restore attempts; attempt N waits (N-1) x this. */
    private static final long RESTORE_RETRY_DELAY_MS = 2000L;

    /**
     * Whether an OAuth error from the token endpoint means the saved refresh_token is genuinely
     * dead (so the account file should be dropped), as opposed to a transient server-side problem
     * worth retrying. Names are from RFC 6749 §5.2 plus Microsoft's own extensions.
     */
    private static boolean isTerminalRefreshError(String error) {
        return switch (error) {
            // Только то, что действительно означает «токен мёртв, нужен новый вход».
            case "invalid_grant", "invalid_client", "unauthorized_client", "expired_token",
                 "interaction_required", "consent_required" -> true;
            // invalid_request и unsupported_grant_type СОЗНАТЕЛЬНО не здесь. Это ошибки
            // ЗАПРОСА, а не доказательство того, что refresh-токен негоден: искажённое тело,
            // вмешавшийся прокси, будущее изменение параметров у Microsoft. Раньше они вели к
            // clearSavedAccount(), и один неудачный обмен по причине на стороне запроса
            // уничтожал рабочий токен на диске — человека выбрасывало в device-code flow.
            // Логика повторов рядом написана именно чтобы этого избежать.
            default -> false;
        };
    }

    /** Microsoft rotates refresh_token on most grants - always persist the latest one we got. */
    private void rememberRefreshToken(JsonObject tokenResponse) {
        if (tokenResponse.has("refresh_token")) {
            refreshToken = tokenResponse.get("refresh_token").getAsString();
            saveRefreshToken();
        }
    }

    /**
     * Step 1 – initiate device code flow.
     * Returns the userCode + verificationUri so we can display them in the UI.
     */
    public Map<String, String> beginLogin() throws IOException {
        log("Requesting device code...");
        String body = "client_id=" + URLEncoder.encode(MICROSOFT_CLIENT_ID, StandardCharsets.UTF_8)
                    + "&scope=" + URLEncoder.encode("XboxLive.signin offline_access", StandardCharsets.UTF_8);

        JsonObject resp = postJson(DEVICE_CODE_URL, body, null);
        if (resp == null || !resp.has("device_code")) {
            throw new IOException("Failed to start device code login"
                    + (resp != null && resp.has("error_description")
                            ? ": " + resp.get("error_description").getAsString() : ""));
        }

        deviceCode      = resp.get("device_code").getAsString();
        userCode        = resp.get("user_code").getAsString();
        verificationUri = resp.get("verification_uri").getAsString();
        expiresIn       = resp.get("expires_in").getAsInt();
        interval        = resp.has("interval") ? resp.get("interval").getAsInt() : 5;

        log("Got user_code: " + userCode + ", verification_uri: " + verificationUri);

        Map<String, String> result = new HashMap<>();
        result.put("userCode", userCode);
        result.put("verificationUri", verificationUri);
        result.put("expiresIn", String.valueOf(expiresIn));
        return result;
    }

    /**
     * Step 2 – poll for the token (blocking, call from background thread).
     * Updates internal state and returns true when completed successfully.
     */
    public boolean pollForToken() {
        polling = true;
        lastStatus = "Waiting for Microsoft login...";

        long deadline = System.currentTimeMillis() + (expiresIn * 1000L);
        int attempt = 0;
        while (polling && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(interval * 1000L);
            } catch (InterruptedException e) {
                polling = false;
                return false;
            }

            attempt++;
            log("Polling for token (attempt " + attempt + ")...");

            try {
                String body = "client_id=" + URLEncoder.encode(MICROSOFT_CLIENT_ID, StandardCharsets.UTF_8)
                            + "&grant_type=urn:ietf:params:oauth:grant-type:device_code"
                            + "&device_code=" + URLEncoder.encode(deviceCode, StandardCharsets.UTF_8);

                JsonObject resp = postJson(TOKEN_URL, body, null);

                if (resp == null) {
                    // No parseable body - transient network/parse hiccup, just keep polling.
                    lastStatus = "Network hiccup, retrying...";
                    continue;
                }

                if (resp.has("error")) {
                    String err = resp.get("error").getAsString();
                    switch (err) {
                        case "authorization_pending" -> {
                            lastStatus = "Waiting for you to approve in browser...";
                            log("Still waiting for user to enter code...");
                            continue;
                        }
                        case "slow_down" -> {
                            interval += 5;
                            lastStatus = "Polling slower (slow_down)...";
                            continue;
                        }
                        case "expired_token" -> {
                            lastStatus = "Code expired, try again.";
                            log("Device code expired.");
                            polling = false;
                            return false;
                        }
                        case "authorization_declined" -> {
                            lastStatus = "Login was declined.";
                            log("User declined the login.");
                            polling = false;
                            return false;
                        }
                        case "bad_verification_code" -> {
                            lastStatus = "Invalid device code, try again.";
                            log("Bad verification code.");
                            polling = false;
                            return false;
                        }
                        default -> {
                            lastStatus = "Login error: " + err;
                            log("Login error: " + err);
                            polling = false;
                            return false;
                        }
                    }
                }

                // Ответ без error и без access_token — такое бывает: прокси или captive portal
                // отдают свою страницу, а не ответ Microsoft. Раньше тут был безусловный
                // get("access_token"), то есть NPE, который улетал наружу мимо всех catch
                // (IOException) и навсегда гасил кнопки входа.
                if (!resp.has("access_token") || !resp.get("access_token").isJsonPrimitive()) {
                    lastStatus = "Microsoft ответил неожидаемым образом — попробуйте ещё раз.";
                    log("Ответ без access_token и без error: " + resp);
                    polling = false;
                    return false;
                }

                // Got the MS token — exchange it through the Xbox chain.
                // The device code is now consumed: whatever happens in the chain below is
                // terminal for this login attempt, so we must stop polling either way -
                // retrying would just re-poll an already-used device code and fail with a
                // confusing "invalid_grant" instead of showing the real reason.
                String msAccessToken = resp.get("access_token").getAsString();
                rememberRefreshToken(resp);
                lastStatus = "Microsoft login OK. Authenticating with Xbox...";
                log("Token received, fetching Xbox Live token...");
                polling = false;

                try {
                    if (exchangeFullChain(msAccessToken)) {
                        lastStatus = minecraftUsername != null ? "Logged in as " + minecraftUsername
                                : "Signed in (no Minecraft license found).";
                        log(minecraftUsername != null ? "Logged in as " + minecraftUsername
                                : "Signed in (no Minecraft license found).");
                        return true;
                    } else {
                        log(lastStatus);
                        return false;
                    }
                } catch (IOException e) {
                    lastStatus = e.getMessage();
                    log(lastStatus);
                    return false;
                }

            } catch (IOException e) {
                lastStatus = "Network error: " + e.getMessage();
                log("Network error: " + e.getMessage());
                // keep polling - this was a transient failure talking to the token endpoint,
                // the device code itself hasn't been consumed yet.
            }
        }

        lastStatus = "Login timed out.";
        log("Login timed out.");
        polling = false;
        return false;
    }

    /**
     * Full chain: MS token → Xbox Live → XSTS → Minecraft
     */
    private boolean exchangeFullChain(String msAccessToken) throws IOException {
        // 1) Xbox Live token
        String xblToken = xboxLiveAuth(msAccessToken);
        if (xblToken == null) return false;

        lastStatus = "Xbox Live OK. Getting XSTS...";

        // 2) XSTS token
        XstsResult xsts = xstsAuth(xblToken);
        if (xsts == null) {
            // Often "no Xbox account" — user doesn't have an Xbox profile
            lastStatus = "XSTS failed. Do you have an Xbox profile? Create one at xbox.com";
            return false;
        }

        lastStatus = "XSTS OK. Getting Minecraft token...";
        log("Got XSTS token, requesting Minecraft token...");

        // 3) Minecraft token
        String mcToken = minecraftAuth(xsts.uhs, xsts.token);
        if (mcToken == null) {
            lastStatus = "Minecraft authentication failed.";
            return false;
        }

        minecraftAccessToken = mcToken;

        // 4) Fetch profile
        fetchMinecraftProfile();

        return true;
    }

    private String xboxLiveAuth(String msAccessToken) throws IOException {
        JsonObject props = new JsonObject();
        props.addProperty("AuthMethod", "RPS");
        props.addProperty("SiteName", "user.auth.xboxlive.com");
        props.addProperty("RpsTicket", "d=" + msAccessToken);

        JsonObject body = new JsonObject();
        body.add("Properties", props);
        body.addProperty("RelyingParty", "http://auth.xboxlive.com");
        body.addProperty("TokenType", "JWT");

        JsonObject resp = postJsonWithRetry(XBL_AUTH_URL, body.toString(), "application/json");
        if (resp == null || !resp.has("Token")) {
            log("Xbox Live auth response: " + (resp != null ? resp : "(empty body)"));
            return null;
        }
        return resp.get("Token").getAsString();
    }

    /** Holds both the XSTS token and the user hash (uhs) needed to build the Minecraft identityToken. */
    private static class XstsResult {
        final String token;
        final String uhs;
        XstsResult(String token, String uhs) { this.token = token; this.uhs = uhs; }
    }

    private XstsResult xstsAuth(String xblToken) throws IOException {
        JsonObject props = new JsonObject();
        props.addProperty("SandboxId", "RETAIL");
        JsonArray userTokens = new JsonArray();
        userTokens.add(xblToken);
        props.add("UserTokens", userTokens);

        JsonObject body = new JsonObject();
        body.add("Properties", props);
        body.addProperty("RelyingParty", "rp://api.minecraftservices.com/");
        body.addProperty("TokenType", "JWT");

        JsonObject resp = postJsonWithRetry(XSTS_AUTH_URL, body.toString(), "application/json");
        if (resp == null || !resp.has("Token") || !resp.has("DisplayClaims")) {
            // Check for XErr
            if (resp != null && resp.has("XErr")) {
                // Разобраны все известные коды, а не два из шести. Раньше остальные
                // проваливались в "return null", и человеку показывалось «нет профиля Xbox,
                // создайте на xbox.com» — вредный совет: при заблокированном регионе профиль
                // у него уже есть, он создавал второй и получал тот же отказ навсегда.
                long xerr = resp.get("XErr").getAsLong();
                throw new IOException(xstsErrorMessage(xerr));
            }
            log("XSTS auth response: " + (resp != null ? resp : "(empty body)"));
            return null;
        }

        String token = resp.get("Token").getAsString();
        String uhs = resp.getAsJsonObject("DisplayClaims")
                .getAsJsonArray("xui").get(0).getAsJsonObject()
                .get("uhs").getAsString();
        return new XstsResult(token, uhs);
    }

    /** Человеческое объяснение кода XErr от XSTS с конкретным следующим шагом. */
    private static String xstsErrorMessage(long xerr) {
        if (xerr == 2148916233L) {
            return "У этого аккаунта Microsoft нет профиля Xbox. Создайте его на xbox.com и"
                 + " войдите снова.";
        }
        if (xerr == 2148916235L) {
            return "Xbox Live недоступен в стране этого аккаунта. Профиль Xbox тут не поможет —"
                 + " проблема в регионе аккаунта Microsoft.";
        }
        if (xerr == 2148916236L || xerr == 2148916237L) {
            return "Аккаунту нужна проверка возраста (adult verification). Пройдите её на"
                 + " xbox.com, затем войдите снова.";
        }
        if (xerr == 2148916238L) {
            return "Это детский аккаунт: его нужно добавить в семью на xbox.com, иначе вход в"
                 + " Minecraft невозможен.";
        }
        if (xerr == 2148916227L) {
            return "Аккаунт заблокирован в Xbox Live за нарушение правил. Вход невозможен.";
        }
        if (xerr == 2148916262L) {
            return "Xbox Live требует подтвердить вход (многофакторная проверка). Войдите на"
                 + " xbox.com, подтвердите и повторите.";
        }
        return "Xbox Live отказал во входе (код " + xerr + "). Попробуйте войти на xbox.com —"
             + " он обычно пишет причину подробнее.";
    }

    private String minecraftAuth(String uhs, String xstsToken) throws IOException {
        JsonObject identity = new JsonObject();
        identity.addProperty("identityToken", "XBL3.0 x=" + uhs + ";" + xstsToken);

        JsonObject resp = postJsonWithRetry(MC_AUTH_URL, identity.toString(), "application/json");
        if (resp == null || !resp.has("access_token")) {
            log("Minecraft auth response: " + (resp != null ? resp : "(empty body)"));
            return null;
        }

        // expires_in приходит в секундах (обычно 86400). Переводим в абсолютную метку сразу:
        // длительность бесполезна, если её не от чего отсчитывать, а именно это и было
        // причиной того, что срок не проверялся вовсе.
        long lifetimeSec = resp.has("expires_in") && resp.get("expires_in").isJsonPrimitive()
                ? resp.get("expires_in").getAsLong() : 0L;
        if (lifetimeSec <= 0) lifetimeSec = 24 * 3600L;   // сервер не сказал — берём сутки
        minecraftTokenExpiresAt = System.currentTimeMillis() + lifetimeSec * 1000L;
        log("Токен Minecraft действителен " + (lifetimeSec / 3600) + " ч (до "
                + java.time.Instant.ofEpochMilli(minecraftTokenExpiresAt) + ")");

        return resp.get("access_token").getAsString();
    }

    private void fetchMinecraftProfile() throws IOException {
        JsonObject resp = getJsonWithRetry(MC_PROFILE_URL, minecraftAccessToken);
        if (resp != null && resp.has("name")) {
            minecraftUsername = resp.get("name").getAsString();
            minecraftUuid     = resp.get("id").getAsString();
            // Дописываем имя в файл аккаунта: токен сохраняется ещё до этого запроса, когда имя
            // неизвестно, а списку профилей нужно именно оно.
            saveRefreshToken();
        } else {
            // Most common cause: this Microsoft account has no Minecraft license, so there is
            // no game profile to fetch. The MSA session itself is still valid - accessToken is
            // a real, signed-in Microsoft token, which is the only thing 1.16.x checks before
            // unlocking the Multiplayer button.
            log("No Minecraft profile found (" + (resp != null ? resp : "empty response")
                    + ") - account likely has no Minecraft license. MSA session is still valid.");
        }
    }

    // ---------- HTTP helpers ----------

    /**
     * postJson with retries for transient I/O failures (connect/read timeouts, dropped
     * connections). Every caller of this - Xbox Live, XSTS, login_with_xbox - is a stateless
     * token exchange, so replaying one is safe; without this a single timed-out leg of the
     * four-step auth chain failed the whole login and sent the user back to the device-code
     * flow, which in practice happened on roughly every other launcher start.
     *
     * Deliberately NOT used for the refresh_token grant: that call rotates the token, so a retry
     * after a lost response could present an already-spent token. tryRestoreSession() retries
     * that one itself, re-reading the latest saved token each time.
     */
    private JsonObject postJsonWithRetry(String urlStr, String body, String contentType) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= HTTP_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                log("Network hiccup talking to " + shortHost(urlStr) + ", retry " + attempt + "/" + HTTP_ATTEMPTS);
                try {
                    Thread.sleep(HTTP_RETRY_DELAY_MS * (attempt - 1L));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw last != null ? last : new IOException("Interrupted");
                }
            }
            try {
                return postJson(urlStr, body, contentType);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    /** getJson with the same retry policy as {@link #postJsonWithRetry} - a GET is always safe to replay. */
    private JsonObject getJsonWithRetry(String urlStr, String bearerToken) throws IOException {
        IOException last = null;
        for (int attempt = 1; attempt <= HTTP_ATTEMPTS; attempt++) {
            if (attempt > 1) {
                log("Network hiccup talking to " + shortHost(urlStr) + ", retry " + attempt + "/" + HTTP_ATTEMPTS);
                try {
                    Thread.sleep(HTTP_RETRY_DELAY_MS * (attempt - 1L));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw last != null ? last : new IOException("Interrupted");
                }
            }
            try {
                return getJson(urlStr, bearerToken);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last;
    }

    private static final int HTTP_ATTEMPTS = 3;
    private static final long HTTP_RETRY_DELAY_MS = 1500L;

    private static String shortHost(String urlStr) {
        try {
            return URI.create(urlStr).getHost();
        } catch (Exception e) {
            return urlStr;
        }
    }

    private JsonObject postJson(String urlStr, String body, String contentType) throws IOException {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);

        if (contentType != null) {
            conn.setRequestProperty("Content-Type", contentType);
            conn.setRequestProperty("Accept", "application/json");
        } else {
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        }

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        return readJsonBody(conn);
    }

    private JsonObject getJson(String urlStr, String bearerToken) throws IOException {
        URL url = URI.create(urlStr).toURL();
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);

        return readJsonBody(conn);
    }

    /**
     * Microsoft/Xbox/Minecraft endpoints return their error details (e.g.
     * {"error":"authorization_pending"}) in the body of 4xx responses, not just on success -
     * we have to read the error stream too, or callers lose the information they need to decide
     * whether to keep polling, stop, or report a real failure.
     */
    private JsonObject readJsonBody(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        if (stream == null) return null;

        String resp = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        if (resp.isBlank()) return null;

        // Тело может оказаться вообще не JSON: HTML-заглушка captive portal в отеле, страница
        // ошибки прокси, иногда просто текст. Раньше это давало IllegalStateException или
        // JsonSyntaxException — ни то, ни другое не IOException, поэтому исключение проходило
        // мимо всех catch по стеку и гасило кнопки входа до перезапуска лаунчера. Теперь это
        // обычная сетевая неудача, и вызывающие её уже умеют обрабатывать.
        try {
            com.google.gson.JsonElement parsed = JsonParser.parseString(resp);
            if (!parsed.isJsonObject()) {
                throw new IOException("Сервер ответил не JSON (HTTP " + code + "): "
                        + shorten(resp));
            }
            return parsed.getAsJsonObject();
        } catch (com.google.gson.JsonParseException e) {
            throw new IOException("Не удалось разобрать ответ сервера (HTTP " + code + "): "
                    + shorten(resp), e);
        }
    }

    /** Первые 200 символов тела в одну строку — чтобы HTML-страница не залила весь лог. */
    private static String shorten(String body) {
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }

    // ---------- public accessors ----------

    public String getAccessToken()  { return minecraftAccessToken; }

    /** Токен ещё годен с запасом, или срок неизвестен (значит и обновлять нечего). */
    public boolean isTokenFresh() {
        if (minecraftAccessToken == null) return false;
        if (minecraftTokenExpiresAt <= 0) return true;   // токен из сессии без известного срока
        return System.currentTimeMillis() + TOKEN_REFRESH_MARGIN_MS < minecraftTokenExpiresAt;
    }

    /** Сколько осталось жить токену в минутах; отрицательное — уже просрочен. 0, если срок неизвестен. */
    public long tokenMinutesLeft() {
        if (minecraftTokenExpiresAt <= 0) return 0;
        return (minecraftTokenExpiresAt - System.currentTimeMillis()) / 60000L;
    }

    /**
     * Обновляет токен Minecraft, если он близок к истечению. Вызывать ПЕРЕД запуском игры.
     *
     * <p>Без этого лаунчер, открытый дольше суток, отдавал игре просроченный токен, и человек
     * видел серую «Сетевую игру» без всякого объяснения. Восстановление сессии выполнялось
     * ровно один раз — при старте.
     *
     * <p>Переиспользует {@link #tryRestoreSession()}: он читает сохранённый refresh-токен,
     * получает новый MSA-токен и прогоняет всю цепочку заново, по ходу выставляя новый срок.
     * Дублировать эту логику отдельной веткой значило бы иметь два пути обновления, которые
     * разойдутся.
     *
     * <p>Блокирующий сетевой вызов — только с фонового потока. Возвращает true, если после
     * вызова токен годен; при неудаче прежний токен и сохранённый аккаунт остаются на месте,
     * чтобы одна неудачная попытка не выбрасывала человека из аккаунта.
     *
     * @return true, если токеном можно пользоваться
     */
    public boolean ensureFreshToken() {
        if (minecraftAccessToken == null) return false;
        if (isTokenFresh()) return true;

        long left = tokenMinutesLeft();
        log(left < 0
                ? "Токен Minecraft просрочен " + (-left) + " мин назад — обновляю перед запуском..."
                : "Токену Minecraft осталось " + left + " мин — обновляю заранее...");

        String previousToken = minecraftAccessToken;
        if (tryRestoreSession()) {
            log("Токен обновлён, осталось " + tokenMinutesLeft() + " мин.");
            return true;
        }

        // Не получилось: оставляем что было. Токен может быть ещё жив (запас 10 минут), а если
        // нет — запуск произойдёт в офлайн-режиме, и это лучше, чем не запуститься совсем.
        minecraftAccessToken = previousToken;
        log("Обновить токен не удалось — запуск пойдёт с прежним токеном"
                + (tokenMinutesLeft() < 0 ? " (он просрочен, мультиплеер может быть недоступен)" : ""));
        return tokenMinutesLeft() >= 0;
    }
    public String getUsername()     { return minecraftUsername; }
    public String getUuid()         { return minecraftUuid; }
    public String getLastStatus()   { return lastStatus; }
    public boolean isLoggedIn()     { return minecraftAccessToken != null; }

    /**
     * Whether this Microsoft account actually owns a Minecraft license, distinct from
     * {@link #isLoggedIn()} (which is true for any signed-in MSA, licensed or not — see
     * {@link #fetchMinecraftProfile()}). Only licensed accounts get a Minecraft profile
     * (username/UUID), so this is what should gate "can play on licensed/online-mode servers".
     */
    public boolean hasLicense()     { return isLoggedIn() && minecraftUsername != null; }

}