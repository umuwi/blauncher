package com.example.launcher.model;

import java.util.ArrayList;
import java.util.List;

public class LauncherConfig {
    private String gameDirectory;
    private String javaPath;
    private int minMemoryMb;
    private int maxMemoryMb;
    private boolean enableAds;
    private String theme;

    /**
     * A palette the player tuned themselves. Kept next to {@code theme} rather
     * than inside it so that switching to a built-in theme and back does not lose
     * their colours; {@code theme} holds {@link Palette#CUSTOM} while this is live.
     */
    private Palette customPalette;
    private String lastSelectedVersion;
    /** Loader picked in the "Без модов и сборок" (vanilla) combo on Home — see lastSelectedVersion. */
    private String lastSelectedLoader = "Fabric";
    /** «Язык игры» той же карточки: "Java" — обычная игра, "Rust" — Rustcraft (RustcraftService). */
    private String lastSelectedEngine = "Java";
    /**
     * 1 once the quick-play loader default moved from Vanilla to Fabric (BL Core only loads on
     * Fabric). A config that still says "Vanilla" from before that is moved over once; a choice
     * made after it is never touched again.
     */
    private int loaderDefaultRevision = 0;
    /** Same idea as lastSelectedVersion/lastSelectedLoader, but for the Server tab's own vanilla
     *  version/loader pickers (hosting a local dedicated server) — kept separate on purpose:
     *  what version you PLAY and what version you HOST a dedicated server as are independent
     *  choices, so picking one must not silently overwrite the other's remembered value. */
    private String lastSelectedServerVersion;
    private String lastSelectedServerLoader = "Vanilla";
    private int scrollFps;
    private String lastUsername;
    /** Stable id of the profile (see UserProfile) currently selected. Kept next to
     *  {@code lastUsername} rather than relying on the nickname alone: a profile can be renamed,
     *  and the Microsoft account saved for it ({@code accounts/<id>.json}) has to survive that.
     *  Absent in configs written before per-profile accounts existed - the launcher then falls
     *  back to matching by nickname, and remembers the id from there. */
    private String lastProfileId = "";
    /** Saved offline player profiles / nicknames shown in the Home "Профили" popup. */
    private List<String> savedProfiles = new ArrayList<>();
    private boolean telemetryEnabled;

    // --- New settings backing the redesigned Settings screen
    // (blauncher-ui-concept-2-1.html). Absent in older saved configs -
    // Gson leaves them at these defaults, which match the concept's own
    // defaults (animation on, windowed, launcher stays open, Russian). ---
    private String language = "ru";
    private boolean launchAnimationEnabled = false;
    private int launchAnimationSeconds = 5;
    private boolean closeLauncherOnLaunch = false;
    /** id of the last server viewed in serverDetail, so reopening the Server tab can restore it. */
    private String lastSelectedServerId = "";
    /** Whether version pickers include Mojang snapshot builds alongside stable releases. */
    private boolean showSnapshots = false;
    /** Forces every launch to skip Microsoft/online auth, even if a session is signed in —
     *  for playing an already-downloaded version with no internet connection at all. */
    private boolean offlineMode = false;
    /**
     * How much bigger or smaller the interface's text is drawn, as a multiplier of the
     * sizes the stylesheet is written with.
     *
     * <p>0.9 is the standard the owner asked for, and it is the size the interface is meant to
     * be read at: the theme's own numbers are the scale the design was drawn on, so the
     * percentage is how that scale is tuned, not a user-facing preference. The chooser that
     * briefly offered 90-150 % in Settings was removed at his request - the setting stays here
     * so the value is one number in one place, and changing it needs no rebuild.
     *
     * <p>Kept as a number rather than a name so an older config that has no such field simply
     * reads as the standard. Applied by PaletteService when it renders the theme, which is why
     * a change here takes effect on the next start without any other code moving.
     */
    private double fontScale = 0.9;

    /**
     * Picture behind the launcher's screens: "" for none (the palette's gradient), "builtin:bg05"
     * for one shipped in the jar, or "file:" + an absolute path for one the player added (copied
     * into launcher_root/backgrounds, so deleting the original does not break it).
     */
    private String backgroundImage = "end";
    /** 1 once a config that had no background picked was moved to the living End sky. */
    private int backgroundRevision = 0;
    /** How strongly the palette's background colour veils the picture, 0..0.9. */
    private double backgroundDim = 0.55;
    /** Whether other BLauncher players see the BLauncher badge next to this player's nickname. */
    private boolean blBadge = true;
    /** Dragging in the 3D editors turns the model the other way. */
    private boolean invertMouse = false;
    /** Interface animations; off leaves every screen and dialog appearing at once. */
    private boolean animations = true;
    /** Minimise the launcher while the game runs, and pause what it animates in the meantime. */
    private boolean minimizeOnLaunch = true;
    /**
     * Свои аргументы Java для запуска игры (Настройки → Память), например {@code -XX:+UseZGC}.
     * Добавляются после аргументов лаунчера, поэтому при совпадении (свой -Xmx) побеждают они.
     * Пустая строка — ничего не добавлять. Поле появилось 11.10.2026, в старых конфигах его нет.
     */
    private String jvmArgs = "";
    /** Свои аргументы игры (--width, --quickPlay… и т. п.), добавляются в конец команды. */
    private String gameArgs = "";
    /** Размер окна игры при запуске; 0 — как решит сама игра (854×480). */
    private int windowWidth = 0;
    private int windowHeight = 0;
    /** Запускать игру сразу в полноэкранном режиме (--fullscreen). */
    private boolean fullscreen = false;
    /** «Сразу подключиться к серверу»: адрес вида host или host:port; пусто — в меню. */
    private String autoJoinServer = "";

    public LauncherConfig() {
        this.gameDirectory = "./launcher_root";
        this.javaPath = "";
        this.minMemoryMb = 512;
        // 0 means "not configured yet" — Settings will replace it with the recommended value
        // for this machine (see MainController.initSettingsScreen()).
        this.maxMemoryMb = 0;
        this.enableAds = true;
        this.theme = "light";
        this.lastSelectedVersion = "";
        this.scrollFps = 60;
        this.lastUsername = "";
        this.telemetryEnabled = true;
    }

    public String getGameDirectory() {
        return gameDirectory;
    }

    public void setGameDirectory(String gameDirectory) {
        this.gameDirectory = gameDirectory;
    }

    public String getGameArgs() { return gameArgs == null ? "" : gameArgs; }
    public void setGameArgs(String v) { this.gameArgs = v == null ? "" : v; }
    public int getWindowWidth() { return windowWidth; }
    public void setWindowWidth(int v) { this.windowWidth = Math.max(0, v); }
    public int getWindowHeight() { return windowHeight; }
    public void setWindowHeight(int v) { this.windowHeight = Math.max(0, v); }
    public boolean isFullscreen() { return fullscreen; }
    public void setFullscreen(boolean v) { this.fullscreen = v; }
    public String getAutoJoinServer() { return autoJoinServer == null ? "" : autoJoinServer; }
    public void setAutoJoinServer(String v) { this.autoJoinServer = v == null ? "" : v.trim(); }

    public String getJvmArgs() {
        return jvmArgs == null ? "" : jvmArgs;
    }

    public void setJvmArgs(String jvmArgs) {
        this.jvmArgs = jvmArgs == null ? "" : jvmArgs;
    }

    public String getJavaPath() {
        return javaPath;
    }

    public void setJavaPath(String javaPath) {
        this.javaPath = javaPath;
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

    public boolean isEnableAds() {
        return enableAds;
    }

    public void setEnableAds(boolean enableAds) {
        this.enableAds = enableAds;
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public Palette getCustomPalette() {
        return customPalette;
    }

    public void setCustomPalette(Palette customPalette) {
        this.customPalette = customPalette;
    }

    public String getLastSelectedVersion() {
        return lastSelectedVersion;
    }

    public void setLastSelectedVersion(String lastSelectedVersion) {
        this.lastSelectedVersion = lastSelectedVersion;
    }

    public String getLastSelectedEngine() {
        return lastSelectedEngine;
    }

    public void setLastSelectedEngine(String lastSelectedEngine) {
        this.lastSelectedEngine = lastSelectedEngine;
    }

    public String getLastSelectedLoader() {
        return lastSelectedLoader;
    }

    public void setLastSelectedLoader(String lastSelectedLoader) {
        this.lastSelectedLoader = lastSelectedLoader;
    }

    public String getLastSelectedServerVersion() {
        return lastSelectedServerVersion;
    }

    public void setLastSelectedServerVersion(String lastSelectedServerVersion) {
        this.lastSelectedServerVersion = lastSelectedServerVersion;
    }

    public String getLastSelectedServerLoader() {
        return lastSelectedServerLoader;
    }

    public void setLastSelectedServerLoader(String lastSelectedServerLoader) {
        this.lastSelectedServerLoader = lastSelectedServerLoader;
    }

    public int getScrollFps() {
        return scrollFps;
    }

    public void setScrollFps(int scrollFps) {
        this.scrollFps = scrollFps;
    }

    public String getLastUsername() {
        return lastUsername;
    }

    public void setLastUsername(String lastUsername) {
        this.lastUsername = lastUsername;
    }

    public String getLastProfileId() {
        return lastProfileId == null ? "" : lastProfileId;
    }

    public void setLastProfileId(String lastProfileId) {
        this.lastProfileId = lastProfileId == null ? "" : lastProfileId;
    }

    public List<String> getSavedProfiles() {
        if (savedProfiles == null) {
            savedProfiles = new ArrayList<>();
        }
        return savedProfiles;
    }

    public void setSavedProfiles(List<String> savedProfiles) {
        this.savedProfiles = savedProfiles != null ? savedProfiles : new ArrayList<>();
    }

    public boolean isTelemetryEnabled() {
        return telemetryEnabled;
    }

    public void setTelemetryEnabled(boolean telemetryEnabled) {
        this.telemetryEnabled = telemetryEnabled;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public boolean isLaunchAnimationEnabled() {
        return launchAnimationEnabled;
    }

    public void setLaunchAnimationEnabled(boolean launchAnimationEnabled) {
        this.launchAnimationEnabled = launchAnimationEnabled;
    }

    public int getLaunchAnimationSeconds() {
        return launchAnimationSeconds;
    }

    public void setLaunchAnimationSeconds(int launchAnimationSeconds) {
        this.launchAnimationSeconds = launchAnimationSeconds;
    }

    public boolean isCloseLauncherOnLaunch() {
        return closeLauncherOnLaunch;
    }

    public void setCloseLauncherOnLaunch(boolean closeLauncherOnLaunch) {
        this.closeLauncherOnLaunch = closeLauncherOnLaunch;
    }

    public String getLastSelectedServerId() {
        return lastSelectedServerId;
    }

    public void setLastSelectedServerId(String lastSelectedServerId) {
        this.lastSelectedServerId = lastSelectedServerId;
    }

    public boolean isShowSnapshots() {
        return showSnapshots;
    }

    public void setShowSnapshots(boolean showSnapshots) {
        this.showSnapshots = showSnapshots;
    }

    public boolean isOfflineMode() {
        return offlineMode;
    }

    public void setOfflineMode(boolean offlineMode) {
        this.offlineMode = offlineMode;
    }

    /**
     * The text-size multiplier. A config written before this setting existed has no field,
     * so Gson leaves it at 0.0 - which would render every font size as zero pixels. Anything
     * that is not a sane multiplier therefore reads as the standard, and the launcher comes up
     * with readable text rather than a blank window.
     */
    public double getFontScale() {
        return fontScale > 0.5 && fontScale < 2.5 ? fontScale : 0.9;
    }

    public void setFontScale(double fontScale) {
        this.fontScale = fontScale;
    }


    public String getBackgroundImage() {
        return backgroundImage == null ? "" : backgroundImage;
    }

    public void setBackgroundImage(String backgroundImage) {
        this.backgroundImage = backgroundImage == null ? "" : backgroundImage;
    }

    public double getBackgroundDim() {
        return backgroundDim >= 0 && backgroundDim <= 0.9 ? backgroundDim : 0.55;
    }

    public void setBackgroundDim(double backgroundDim) {
        this.backgroundDim = Math.max(0, Math.min(0.9, backgroundDim));
    }

    public boolean isBlBadge() {
        return blBadge;
    }

    public void setBlBadge(boolean blBadge) {
        this.blBadge = blBadge;
    }

    public int getLoaderDefaultRevision() {
        return loaderDefaultRevision;
    }

    public void setLoaderDefaultRevision(int loaderDefaultRevision) {
        this.loaderDefaultRevision = loaderDefaultRevision;
    }

    public boolean isInvertMouse() {
        return invertMouse;
    }

    public void setInvertMouse(boolean invertMouse) {
        this.invertMouse = invertMouse;
    }

    public boolean isAnimations() {
        return animations;
    }

    public void setAnimations(boolean animations) {
        this.animations = animations;
    }

    public int getBackgroundRevision() {
        return backgroundRevision;
    }

    public void setBackgroundRevision(int backgroundRevision) {
        this.backgroundRevision = backgroundRevision;
    }

    public boolean isMinimizeOnLaunch() {
        return minimizeOnLaunch;
    }

    public void setMinimizeOnLaunch(boolean minimizeOnLaunch) {
        this.minimizeOnLaunch = minimizeOnLaunch;
    }
}
