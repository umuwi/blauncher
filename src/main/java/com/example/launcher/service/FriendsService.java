package com.example.launcher.service;

import com.example.launcher.model.Friend;
import com.example.launcher.model.FriendInvite;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The launcher's friends backend client: friend codes, the friends list and the "о себе" text
 * every friend sees.
 *
 * <p><b>Identity.</b> The launcher has no accounts, so a player is identified by a secret this
 * class generates once and keeps in {@code <gameDir>/friends.json}. The server stores only its
 * sha256, and hands back the public {@link #getMyCode() friend code} that players exchange with
 * each other. Losing friends.json means losing the code (and therefore the friends list) — the
 * file is small, plain JSON and deliberately human-readable for that reason.
 *
 * <p><b>Offline.</b> The last known friends list is cached in the same file and returned by
 * {@link #getCachedFriends()} even when the API is unreachable, so the panel never goes blank on
 * a flaky connection. Every network method blocks: call them from a background executor, never
 * from the FX Application Thread.
 */
public class FriendsService {

    /** The PHP endpoint — same site as the launcher's other API calls (see OnlineStatsService). */
    private static final String ENDPOINT = "https://blauncher.ru/api/friends.php";

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration SKIN_TIMEOUT = Duration.ofSeconds(25);
    private static final Map<String, String> UA = Map.of("User-Agent", "BLauncher");

    /** Bytes of the nick/skin signature that go into a cached head's file name. */
    private static final int HEAD_SIGNATURE_LEN = 12;

    private final Path stateFile;
    private final Path headsDir;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /** Local mirror of everything the API knows about this player; rewritten on every change. */
    private State state = new State();

    public FriendsService(Path gameDir) {
        this.stateFile = gameDir.resolve("friends.json");
        this.headsDir = gameDir.resolve("friends").resolve("heads");
        load();
    }

    /**
     * Raised when the API answered with an error (its short code, e.g. {@code not_found}) or
     * could not be reached at all ({@code offline}). Callers turn {@link #getCode()} into a
     * sentence via the i18n dictionary.
     */
    public static class FriendsException extends Exception {
        private static final long serialVersionUID = 1L;
        private final String code;

        public FriendsException(String code) {
            super(code);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }

    /** One successful round trip: who we are, everyone on our friends list, and room invitations. */
    public record Snapshot(String code, String nick, String about, String skinUrl,
                           List<Friend> friends,
                           List<FriendInvite> invites,
                           List<InviteAnswer> inviteAnswers) {}

    /** What came of an invitation *we* sent — the sender is told once, on the next sync. */
    public record InviteAnswer(String nick, String networkName, boolean accepted) {}

    /** The answer to "add this code": the friend, and whether they were already on the list. */
    public record AddResult(Friend friend, boolean already) {}

    /** The answer to "invite this friend": who was invited, and into which room. */
    public record InviteResult(String nick, String networkId, String networkName) {}

    /**
     * Everything persisted between runs. Named fields (not a Map) so the file stays readable and
     * a future field can be added without invalidating an existing one.
     */
    private static class State {
        String secret = "";
        String code = "";
        String about = "";
        String nick = "";
        String skinUrl;
        List<Friend> friends = new ArrayList<>();
    }

    // ===========================================================================================
    //  Local state
    // ===========================================================================================

    private void load() {
        try {
            if (!Files.exists(stateFile)) return;
            State loaded = gson.fromJson(Files.readString(stateFile, StandardCharsets.UTF_8), State.class);
            if (loaded != null) {
                state = loaded;
                if (state.friends == null) state.friends = new ArrayList<>();
                if (state.secret == null) state.secret = "";
                if (state.code == null) state.code = "";
                if (state.about == null) state.about = "";
            }
        } catch (Exception e) {
            // Этот файл — не настройки: в нём СЕКРЕТ, по которому сервер узнаёт человека. Новая
            // личность означает новый код дружбы и пустой список: все, кто его добавил, остаются
            // с кодом, который больше ни к кому не ведёт, и восстановить это нельзя никак.
            // Поэтому битый файл откладывается, а не затирается, — его ещё можно открыть
            // блокнотом и достать строку "secret" руками.
            quarantine(e);
            state = new State();
        }
    }

    /** Откладывает нечитаемый friends.json рядом, чтобы секрет в нём не исчез при перезаписи. */
    private void quarantine(Exception cause) {
        try {
            Path spoiled = stateFile.resolveSibling(stateFile.getFileName() + ".corrupt");
            Files.move(stateFile, spoiled, StandardCopyOption.REPLACE_EXISTING);
            System.err.println("friends.json нечитаем (" + cause.getClass().getSimpleName()
                    + "), отложен как " + spoiled.getFileName()
                    + "; код дружбы будет создан заново");
        } catch (IOException e) {
            // Переложить не удалось — тогда оставляем файл как есть. Он будет перезаписан при
            // первом же save(), но это лучше, чем удалить его здесь самим.
            System.err.println("friends.json нечитаем и не откладывается: " + e.getMessage());
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(stateFile.getParent());
            // Запись через временный файл и переименование. Прямая запись поверх живого файла
            // обрезает его в самом начале, и достаточно выключения питания или нехватки места в
            // этот момент, чтобы секрет пропал совсем — см. комментарий в load().
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Files.writeString(tmp, gson.toJson(state), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                // Бывает на сетевых и FAT-разделах: там атомарности не добиться, но замена
                // целиком записанным файлом всё равно лучше записи поверх.
                Files.move(tmp, stateFile, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (IOException ignored) {
            // Best effort: an unwritable gameDir must not take the friends panel down with it.
        }
    }

    /** The player's public friend code, or "" until the first successful sync. */
    public synchronized String getMyCode() {
        return state.code == null ? "" : state.code;
    }

    /** The "о себе" text as last saved (locally or by the server). */
    public synchronized String getMyAbout() {
        return state.about == null ? "" : state.about;
    }

    /** Last known friends list — valid offline, refreshed by {@link #sync}. */
    public synchronized List<Friend> getCachedFriends() {
        return new ArrayList<>(state.friends == null ? List.of() : state.friends);
    }

    /** Remembers the "о себе" text locally right away, so a save while offline still sticks. */
    public synchronized void setLocalAbout(String about) {
        state.about = about == null ? "" : about;
        save();
    }

    /** A stable per-install secret, generated on first use. */
    private synchronized String secret() {
        if (state.secret == null || state.secret.isBlank()) {
            // Two UUIDs: 256 bits of entropy, no dependency on a crypto provider being configured.
            state.secret = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
            save();
        }
        return state.secret;
    }

    // ===========================================================================================
    //  API
    // ===========================================================================================

    /**
     * Publishes this player's nickname, skin and "о себе", and fetches the friends list back.
     * Also serves as the heartbeat that makes {@link Friend#isOnline()} meaningful.
     */
    public Snapshot sync(String nick, String skinUrl, String about) throws FriendsException {
        JsonObject body = new JsonObject();
        body.addProperty("action", "sync");
        body.addProperty("secret", secret());
        body.addProperty("nick", nick == null ? "" : nick);
        // Present (possibly as an explicit null) on purpose: that is how the server is told the
        // skin is gone rather than "leave whatever you had".
        body.add("skinUrl", skinUrl == null || skinUrl.isBlank()
                ? com.google.gson.JsonNull.INSTANCE
                : new com.google.gson.JsonPrimitive(skinUrl));
        body.addProperty("about", about == null ? "" : about);

        JsonObject response = call(body);
        JsonObject me = response.has("me") && response.get("me").isJsonObject()
                ? response.getAsJsonObject("me") : new JsonObject();

        List<Friend> friends = new ArrayList<>();
        if (response.has("friends") && response.get("friends").isJsonArray()) {
            JsonArray array = response.getAsJsonArray("friends");
            for (JsonElement element : array) {
                Friend friend = gson.fromJson(element, Friend.class);
                if (friend != null && friend.getCode() != null) friends.add(friend);
            }
        }

        List<FriendInvite> invites = new ArrayList<>();
        if (response.has("invites") && response.get("invites").isJsonArray()) {
            for (JsonElement element : response.getAsJsonArray("invites")) {
                FriendInvite invite = gson.fromJson(element, FriendInvite.class);
                if (invite != null && invite.getNetworkId() != null) invites.add(invite);
            }
        }

        List<InviteAnswer> answers = new ArrayList<>();
        if (response.has("inviteAnswers") && response.get("inviteAnswers").isJsonArray()) {
            for (JsonElement element : response.getAsJsonArray("inviteAnswers")) {
                JsonObject answer = element.getAsJsonObject();
                answers.add(new InviteAnswer(
                        answer.has("nick") && !answer.get("nick").isJsonNull()
                                ? answer.get("nick").getAsString() : "",
                        answer.has("networkName") && !answer.get("networkName").isJsonNull()
                                ? answer.get("networkName").getAsString() : "",
                        answer.has("accepted") && answer.get("accepted").getAsBoolean()));
            }
        }

        String code = me.has("code") && !me.get("code").isJsonNull() ? me.get("code").getAsString() : "";
        String serverNick = me.has("nick") && !me.get("nick").isJsonNull() ? me.get("nick").getAsString() : nick;
        String serverAbout = me.has("about") && !me.get("about").isJsonNull() ? me.get("about").getAsString() : about;
        String serverSkin = me.has("skinUrl") && !me.get("skinUrl").isJsonNull() ? me.get("skinUrl").getAsString() : null;

        synchronized (this) {
            state.code = code;
            state.nick = serverNick == null ? "" : serverNick;
            state.about = serverAbout == null ? "" : serverAbout;
            state.skinUrl = serverSkin;
            state.friends = friends;
            save();
        }
        return new Snapshot(code, state.nick, state.about, serverSkin, friends, invites, answers);
    }

    /**
     * Invites a friend into a ZeroTier room. Only friends can be invited (the API refuses
     * anybody else), and {@code networkName} is passed along purely so the recipient can read
     * the invitation as a room name instead of a 16-character hex ID.
     */
    public InviteResult sendInvite(String code, String networkId, String networkName) throws FriendsException {
        JsonObject body = new JsonObject();
        body.addProperty("action", "invite");
        body.addProperty("secret", secret());
        body.addProperty("code", code == null ? "" : code);
        body.addProperty("networkId", networkId == null ? "" : networkId);
        body.addProperty("networkName", networkName == null ? "" : networkName);

        JsonObject response = call(body);
        return new InviteResult(
                response.has("to") && !response.get("to").isJsonNull() ? response.get("to").getAsString() : "",
                response.has("networkId") && !response.get("networkId").isJsonNull()
                        ? response.get("networkId").getAsString() : "",
                response.has("networkName") && !response.get("networkName").isJsonNull()
                        ? response.get("networkName").getAsString() : "");
    }

    /** Answers a room invitation addressed to us: {@code accept=false} declines it. */
    public void answerInvite(long inviteId, boolean accept) throws FriendsException {
        JsonObject body = new JsonObject();
        body.addProperty("action", "invite_answer");
        body.addProperty("secret", secret());
        body.addProperty("id", inviteId);
        body.addProperty("accept", accept);
        call(body);
    }

    /**
     * Adds the player behind {@code rawCode} (any formatting — spaces, lowercase and the missing
     * BL- prefix are all accepted) and makes the friendship mutual immediately, which is what
     * "добавить по коду" means to the person doing it: no separate accept step to wait for.
     */
    public AddResult addFriend(String rawCode) throws FriendsException {
        JsonObject body = new JsonObject();
        body.addProperty("action", "add");
        body.addProperty("secret", secret());
        body.addProperty("code", rawCode == null ? "" : rawCode);

        JsonObject response = call(body);
        Friend friend = response.has("friend") && response.get("friend").isJsonObject()
                ? gson.fromJson(response.get("friend"), Friend.class) : null;
        boolean already = response.has("already") && response.get("already").getAsBoolean();
        if (friend == null) throw new FriendsException("unknown_action");
        return new AddResult(friend, already);
    }

    /** Drops the friendship on both sides. */
    public void removeFriend(String code) throws FriendsException {
        JsonObject body = new JsonObject();
        body.addProperty("action", "remove");
        body.addProperty("secret", secret());
        body.addProperty("code", code == null ? "" : code);
        call(body);

        synchronized (this) {
            if (state.friends != null) {
                state.friends.removeIf(f -> f != null && code != null && code.equals(f.getCode()));
            }
            save();
        }
    }

    /** The BL Core marks API: who plays through BLauncher, and their cape (see BlCoreService). */
    private static final String BL_ENDPOINT = "https://blauncher.ru/api/bl.php";

    /**
     * Leaves this player's BLauncher mark - nickname and the UUID the game runs with - and puts the
     * cape next to it, under the same identity the friends list uses. With {@code badge == false}
     * the mark is removed instead (the player hid their badge in Settings).
     */
    public void leaveBlMark(String nick, String uuid, boolean badge, byte[] capePng) throws FriendsException {
        JsonObject mark = new JsonObject();
        mark.addProperty("action", "mark");
        mark.addProperty("secret", secret());
        mark.addProperty("nick", nick);
        mark.addProperty("uuid", uuid == null ? "" : uuid);
        mark.addProperty("badge", badge);
        try {
            call(BL_ENDPOINT, mark);
        } catch (FriendsException e) {
            if (!"unknown_player".equals(e.getCode())) throw e;
            // the identity is created by the first friends sync; this player never had one
            sync(nick, null, null);
            call(BL_ENDPOINT, mark);
        }
        if (!badge) return;
        JsonObject cape = new JsonObject();
        cape.addProperty("action", "cape");
        cape.addProperty("secret", secret());
        if (capePng != null) {
            cape.addProperty("png", java.util.Base64.getEncoder().encodeToString(capePng));
        } else {
            cape.add("png", com.google.gson.JsonNull.INSTANCE);
        }
        call(BL_ENDPOINT, cape);
    }

    private JsonObject call(JsonObject body) throws FriendsException {
        return call(ENDPOINT, body);
    }

    private JsonObject call(String endpoint, JsonObject body) throws FriendsException {
        Http.Response response;
        try {
            response = Http.post(endpoint, gson.toJson(body), "application/json; charset=utf-8",
                    UA, HTTP_TIMEOUT);
        } catch (IOException e) {
            throw new FriendsException("offline");
        }

        JsonObject parsed = null;
        try {
            JsonElement root = JsonParser.parseString(response.text());
            if (root.isJsonObject()) parsed = root.getAsJsonObject();
        } catch (Exception ignored) {
            // Handled below as an unreadable answer - a proxy error page, or the API being down.
        }
        if (parsed == null) throw new FriendsException("offline");

        boolean ok = parsed.has("ok") && parsed.get("ok").isJsonPrimitive() && parsed.get("ok").getAsBoolean();
        if (!ok) {
            String error = parsed.has("error") && parsed.get("error").isJsonPrimitive()
                    ? parsed.get("error").getAsString() : "offline";
            throw new FriendsException(error);
        }
        return parsed;
    }

    // ===========================================================================================
    //  Head textures
    // ===========================================================================================

    /**
     * A local PNG holding the friend's full skin, downloaded once and reused.
     *
     * <p>The source is the skin URL the friend's own launcher published; when they have none
     * (an offline-mode nickname usually resolves to nothing on Mojang), the nickname is looked up
     * on Ely.by, exactly like the profile editor does for its own previews.
     *
     * @return the cached file, or null when no skin could be found at all.
     */
    public Path headTextureFor(Friend friend) {
        if (friend == null) return null;
        String key = friend.getCode() != null && !friend.getCode().isBlank()
                ? friend.getCode() : friend.getNick();
        return cachedTexture(key, friend.getSkinUrl(), friend.getNick());
    }

    /** Same idea for the player's own head, so the friends panel shows them among their friends. */
    public Path headTextureForSelf(String nick, String skinUrl) {
        if (nick == null || nick.isBlank()) return null;
        return cachedTexture("self", skinUrl, nick);
    }

    private Path cachedTexture(String key, String skinUrl, String nick) {
        if (key == null || key.isBlank()) return null;
        Path dest = headsDir.resolve(safeFileName(key) + "_" + signature(skinUrl, nick) + ".png");
        try {
            if (Files.exists(dest) && Files.size(dest) > 0) return dest;
        } catch (IOException ignored) {
            // Fall through and re-download.
        }

        String url = skinUrl;
        if (url == null || url.isBlank()) url = resolveSkinUrlByNick(nick);
        if (url == null || url.isBlank()) return null;

        try {
            Files.createDirectories(headsDir);
            long written = Http.download(url, dest, UA, SKIN_TIMEOUT);
            if (written <= 0) {
                Files.deleteIfExists(dest);
                return null;
            }
            return dest;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Ely.by first (it serves custom/offline skins), then Mojang's official textures. Mirrors
     * MainController.downloadSkinByNickname, which does the same for the player's own previews.
     */
    public static String resolveSkinUrlByNick(String nick) {
        if (nick == null || nick.isBlank()) return null;
        String encoded = URLEncoder.encode(nick, StandardCharsets.UTF_8);

        try {
            Http.Response ely = Http.get("https://skinsystem.ely.by/textures/" + encoded, UA, HTTP_TIMEOUT);
            if (ely.ok()) {
                JsonObject root = JsonParser.parseString(ely.text()).getAsJsonObject();
                if (root.has("SKIN") && root.getAsJsonObject("SKIN").has("url")) {
                    return root.getAsJsonObject("SKIN").get("url").getAsString();
                }
                if (root.has("textures") && root.getAsJsonObject("textures").has("SKIN")) {
                    JsonObject skin = root.getAsJsonObject("textures").getAsJsonObject("SKIN");
                    if (skin.has("url")) return skin.get("url").getAsString();
                }
            }
        } catch (Exception ignored) {
            // Best effort - Mojang below is the fallback.
        }

        try {
            Http.Response user = Http.get("https://api.mojang.com/users/profiles/minecraft/" + encoded,
                    UA, HTTP_TIMEOUT);
            if (!user.ok()) return null;
            JsonObject userObj = JsonParser.parseString(user.text()).getAsJsonObject();
            if (!userObj.has("id")) return null;

            Http.Response profile = Http.get(
                    "https://sessionserver.mojang.com/session/minecraft/profile/" + userObj.get("id").getAsString(),
                    UA, HTTP_TIMEOUT);
            if (!profile.ok()) return null;
            JsonObject root = JsonParser.parseString(profile.text()).getAsJsonObject();
            if (!root.has("properties")) return null;
            for (JsonElement element : root.getAsJsonArray("properties")) {
                JsonObject property = element.getAsJsonObject();
                if (!property.has("name") || !"textures".equals(property.get("name").getAsString())) continue;
                if (!property.has("value")) continue;
                byte[] decoded = Base64.getDecoder().decode(property.get("value").getAsString());
                JsonObject textures = JsonParser.parseString(new String(decoded, StandardCharsets.UTF_8))
                        .getAsJsonObject().getAsJsonObject("textures");
                if (textures != null && textures.has("SKIN")) {
                    JsonObject skin = textures.getAsJsonObject("SKIN");
                    if (skin.has("url")) return skin.get("url").getAsString();
                }
                break;
            }
        } catch (Exception ignored) {
            // No skin anywhere - the panel shows the placeholder head instead.
        }
        return null;
    }

    /** Short hash of "what this head currently looks like", so a skin change re-downloads it. */
    private static String signature(String skinUrl, String nick) {
        String source = (skinUrl == null ? "" : skinUrl) + "|" + (nick == null ? "" : nick);
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.substring(0, HEAD_SIGNATURE_LEN);
        } catch (Exception e) {
            return Integer.toHexString(source.hashCode());
        }
    }

    private static String safeFileName(String raw) {
        return raw.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
