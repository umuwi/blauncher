package com.example.launcher.model;

import java.util.UUID;

public class Profile {
    private String name;
    private String username;
    private String uuid;
    private String versionId;
    private String modLoader; // "vanilla", "forge", "fabric", "neoforge"
    private String modLoaderVersion; // e.g. "47.2.0" for forge, "0.15.6" for fabric
    private int minMemoryMb;
    private int maxMemoryMb;
    private String javaPath;
    private boolean onlineMode = false; // true = use Microsoft auth token
    private boolean networkAccessEnabled = true;

    // --- Fields below support the "build card" presentation from the UI
    // concept (blauncher-ui-concept-2-1.html): Home/Builds grids, sorting by
    // recency/name/mod count, and the "no build / Vanilla" special case.
    // Absent in older saved profiles — Gson leaves them at these defaults,
    // which is exactly the right fallback for a profile saved before this
    // UI existed. --------------------------------------------------------

    /** Stable id for card wiring/menus; independent of "name" so renaming a build doesn't break references. */
    private String cardId;
    /** Total playtime accumulated across launches, in whole minutes. */
    private long totalPlaytimeMinutes;
    /** Epoch millis of the last time this profile was launched; 0 if never. */
    private long lastLaunchedAt;
    /** Когда начался последний запуск, закончившийся без вылета (epoch millis; 0 — не было).
     *  По нему «Починка сборки» узнаёт моды, добавленные после того, как игра ещё работала. */
    private long lastGoodLaunchAt;
    /** Epoch millis this profile was created; used as a fallback recency sort key. */
    private long createdAt;
    /** 2-3 letter/character label shown on the build card icon (e.g. "AT" for "All The Mods 10"). */
    private String iconText;
    /** CSS-style gradient string for the build card icon background, e.g. "#4b5563,#1f2937". */
    private String iconGradient;
    /** "launcher" (created locally) or "player" (imported via share link) — see ModShareCodec. */
    private String origin = "launcher";
    /** True only for the special "no build / Vanilla" entry that isn't a real saved profile. */
    private boolean vanillaEntry;
    /** Starred from the Builds screen — liked builds are what Home's quick-play strip shows. */
    private boolean liked;

    // --- Свои настройки запуска сборки (11.10.2026). Пусто/0 — берётся общее из Настроек.
    // Отдельные поля, а не minMemoryMb/maxMemoryMb: те у всех сборок застыли на 512/2048 из
    // конструктора, и GameLaunchService их намеренно не слушает (см. buildCommand).
    /** Память для этой сборки в МБ; 0 — как в Настройках. */
    private int buildMemoryMb;
    /** Аргументы Java только для этой сборки; пусто — общие. */
    private String buildJvmArgs;
    /** Аргументы игры только для этой сборки; пусто — общие. */
    private String buildGameArgs;
    /** Сервер для автовхода только для этой сборки; пусто — общий. */
    private String buildServer;

    public Profile() {
        this.name = "Default";
        this.username = "Player";
        this.uuid = "";
        this.versionId = "1.20.4";
        this.modLoader = "vanilla";
        this.modLoaderVersion = "";
        this.minMemoryMb = 512;
        this.maxMemoryMb = 2048;
        this.javaPath = "";
        this.onlineMode = false;
        this.networkAccessEnabled = true;
        this.cardId = UUID.randomUUID().toString();
        this.createdAt = System.currentTimeMillis();
    }

    public Profile(String name, String username, String uuid, String versionId, String modLoader) {
        this();
        this.name = name;
        this.username = username;
        this.uuid = uuid;
        this.versionId = versionId;
        this.modLoader = modLoader;
    }

    public int getBuildMemoryMb() { return buildMemoryMb; }
    public void setBuildMemoryMb(int v) { this.buildMemoryMb = Math.max(0, v); }
    public String getBuildJvmArgs() { return buildJvmArgs == null ? "" : buildJvmArgs; }
    public void setBuildJvmArgs(String v) { this.buildJvmArgs = v == null ? "" : v.trim(); }
    public String getBuildGameArgs() { return buildGameArgs == null ? "" : buildGameArgs; }
    public void setBuildGameArgs(String v) { this.buildGameArgs = v == null ? "" : v.trim(); }
    public String getBuildServer() { return buildServer == null ? "" : buildServer; }
    public void setBuildServer(String v) { this.buildServer = v == null ? "" : v.trim(); }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getUuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String getVersionId() {
        return versionId;
    }

    public void setVersionId(String versionId) {
        this.versionId = versionId;
    }

    public String getModLoader() {
        return modLoader;
    }

    public void setModLoader(String modLoader) {
        this.modLoader = modLoader;
    }

    public String getModLoaderVersion() {
        return modLoaderVersion;
    }

    public void setModLoaderVersion(String modLoaderVersion) {
        this.modLoaderVersion = modLoaderVersion;
    }

    public int getMinMemoryMb() {
        return minMemoryMb;
    }

    public void setMinMemoryMb(int minMemoryMb) {
        this.minMemoryMb = minMemoryMb;
    }

    public int getMaxMemoryMb() {
        return maxMemoryMb;
    }

    public void setMaxMemoryMb(int maxMemoryMb) {
        this.maxMemoryMb = maxMemoryMb;
    }

    public String getJavaPath() {
        return javaPath;
    }

    public void setJavaPath(String javaPath) {
        this.javaPath = javaPath;
    }

    public boolean isOnlineMode() {
        return onlineMode;
    }

    public void setOnlineMode(boolean onlineMode) {
        this.onlineMode = onlineMode;
    }

    public boolean isNetworkAccessEnabled() {
        return networkAccessEnabled;
    }

    public void setNetworkAccessEnabled(boolean networkAccessEnabled) {
        this.networkAccessEnabled = networkAccessEnabled;
    }

    public String getCardId() {
        if (cardId == null) {
            cardId = UUID.randomUUID().toString();
        }
        return cardId;
    }

    public void setCardId(String cardId) {
        this.cardId = cardId;
    }

    public long getTotalPlaytimeMinutes() {
        return totalPlaytimeMinutes;
    }

    public void setTotalPlaytimeMinutes(long totalPlaytimeMinutes) {
        this.totalPlaytimeMinutes = totalPlaytimeMinutes;
    }

    public void addPlaytimeMinutes(long minutes) {
        if (minutes > 0) {
            this.totalPlaytimeMinutes += minutes;
        }
    }

    public long getLastLaunchedAt() {
        return lastLaunchedAt;
    }

    public void setLastLaunchedAt(long lastLaunchedAt) {
        this.lastLaunchedAt = lastLaunchedAt;
    }

    public long getLastGoodLaunchAt() {
        return lastGoodLaunchAt;
    }

    public void setLastGoodLaunchAt(long lastGoodLaunchAt) {
        this.lastGoodLaunchAt = lastGoodLaunchAt;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public String getIconText() {
        return iconText;
    }

    public void setIconText(String iconText) {
        this.iconText = iconText;
    }

    public String getIconGradient() {
        return iconGradient;
    }

    public void setIconGradient(String iconGradient) {
        this.iconGradient = iconGradient;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public boolean isVanillaEntry() {
        return vanillaEntry;
    }

    public void setVanillaEntry(boolean vanillaEntry) {
        this.vanillaEntry = vanillaEntry;
    }

    public boolean isLiked() {
        return liked;
    }

    public void setLiked(boolean liked) {
        this.liked = liked;
    }

    /** Whole hours of playtime, for card display (concept shows "212 ч", not minutes). */
    public long getTotalPlaytimeHours() {
        return totalPlaytimeMinutes / 60;
    }

    /** Days since last launch, for the recency sort/label ("3 дня назад"); -1 if never launched. */
    public long getDaysSinceLastLaunch() {
        if (lastLaunchedAt <= 0) {
            return Long.MAX_VALUE; // never launched sorts as "oldest"
        }
        long diffMs = System.currentTimeMillis() - lastLaunchedAt;
        return Math.max(0, diffMs / (24L * 60 * 60 * 1000));
    }

    @Override
    public String toString() {
        boolean showLoader = modLoader != null && !modLoader.isBlank() && !"vanilla".equalsIgnoreCase(modLoader);
        return name + " (" + versionId + (showLoader ? " " + modLoader : "") + ")";
    }
}
