package com.example.launcher.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A single server the launcher knows about — either a local process this
 * machine runs directly (via GameLaunchService, same as before), or a VPS /
 * external server managed remotely through the agent HTTP API
 * (see com.example.launcher.agent.AgentClient and AGENT_API.md).
 *
 * This mirrors the "demoServers" data shape from the UI concept
 * (blauncher-ui-concept-2-1.html) as closely as possible, so the controller
 * logic that renders/updates the server screens can follow the same shape
 * the concept's own JS used.
 *
 * Persisted as JSON via {@link com.example.launcher.service.ServerService},
 * one file per launcher install (not one-file-per-server — the list is
 * small and is naturally read/written as a whole).
 */
public class ServerEntry {

    /** Where a server sits in a hub/satellite proxy network, if at all. */
    public static final class NetworkInfo {
        private String role; // "hub" | "satellite"
        private String hubId; // only set when role == "satellite"

        public NetworkInfo() {
        }

        public NetworkInfo(String role, String hubId) {
            this.role = role;
            this.hubId = hubId;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getHubId() {
            return hubId;
        }

        public void setHubId(String hubId) {
            this.hubId = hubId;
        }

        public boolean isHub() {
            return "hub".equals(role);
        }

        public boolean isSatellite() {
            return "satellite".equals(role);
        }
    }

    private String id;
    private String name;
    private String version;
    private String loader; // "Vanilla" | "Forge" | "Fabric" | "NeoForge" | "Quilt" | "Paper" | "Velocity" | "BungeeCord"

    // Local-only fields
    private boolean isLocal;
    private int port = 25565;
    private Integer ramMb; // null for VPS entries — the agent decides RAM on its own machine

    // VPS-only fields
    private String host;
    /** Port the launcher uses to reach the agent itself, NOT the Minecraft port above. Default matches AGENT_API.md. */
    private int agentPort = 9090;
    private String agentKey;
    /** SHA-256 fingerprint of the agent's self-signed TLS certificate, pinned on first connect
     *  (see AgentClient's certificate-pinning TrustManager). Null until the agent has been
     *  confirmed reachable at least once — see MainController.onCheckNewVpsConnection(). */
    private String agentFingerprint;
    private boolean agentConnected;

    // Панель VPS по SSH (с 07.10.2026 — замена агенту для новых серверов).
    // Здесь только то, что не секрет: адрес, пользователь, отпечаток хоста, открытый ключ.
    // Приватный ключ и пароль sudo лежат в SecretStore (config/vps_secrets.json, под DPAPI)
    // под именами secretKeyName()/secretSudoName() — в servers.json их нет и быть не должно.
    /** "ssh" — сервер панели VPS; null — локальный или старый агентский (см. agentKey). */
    private String connection;
    private int sshPort = 22;
    private String sshUser;
    /** Закреплённый отпечаток ключа хоста, SHA256:… — проверяется при каждом подключении. */
    private String sshHostKey;
    private String sshHostKeyType;
    /** Наша строка из authorized_keys — по ней ключ убирается с VPS при удалении сервера. */
    private String sshPublicKey;
    /** VpsSession.Privilege.name(): ROOT | SUDO_NOPASSWD | SUDO_PASSWORD. */
    private String sshPrivilege;
    /** PRETTY_NAME из /etc/os-release на момент последней проверки. */
    private String sshOs;
    // Установка Minecraft на VPS (этап 2). Заполняются после успешной установки — по ним экран
    // сервера понимает, что ставить уже нечего, а этап 3 — какой службой управлять.
    /** Версия загрузчика (Fabric/Forge/NeoForge), null для Vanilla и Paper. */
    private String loaderVersion;
    /** Папка сервера на VPS: /opt/blauncher/&lt;id&gt;. */
    private String sshServerDir;
    /** Имя systemd-службы без .service. */
    private String sshService;
    /** Порт RCON на VPS. Пароль RCON — в SecretStore (secretRconName). */
    private int sshRconPort;
    /** Мажорная версия Java, поставленной на VPS для этого сервера. */
    private int sshJavaMajor;
    /** Имя сборки лаунчера, если сервер развёрнут из неё; null — чистое ядро. */
    private String sshProfile;
    private boolean sshInstalled;
    /** Лаунчер в этом сеансе уже узнал у VPS, работает ли служба. Не сохраняется (transient):
     *  после перезапуска лаунчера running снова неизвестен, пока не придёт первый статус. */
    private transient boolean sshStatusKnown;

    // Shared runtime/UI state (not meaningful to persist as "current" across
    // restarts the way config is, but kept here since the concept's data
    // model keeps them alongside the rest — see ServerService for how the
    // launcher resets transient fields like running/pending on load).
    private boolean running;
    /** "starting" | "stopping" | null — transient, not persisted meaningfully across restarts. */
    private String pending;
    private List<String> plugins = new ArrayList<>();
    /** ids (BrandPlugin.getId()) of brand plugins successfully activated on this server via a
     *  license key — see PluginLicenseService and the "brandPluginsPanel" section of
     *  MainController. Separate list from the regular Modrinth-style {@link #plugins} above
     *  since these come from blauncher.ru's own catalog, not Modrinth. */
    private List<String> activatedBrandPlugins = new ArrayList<>();
    /** pluginId -> version string actually placed into this server's plugins/ folder by the
     *  launcher's own "Установить"/"Обновить" action (see PluginsPanelController). This is how
     *  the launcher decides which button to show (Install/Update/Delete) — it does NOT ask the
     *  agent to list the folder (that endpoint doesn't exist), so this record can drift from
     *  reality if a jar is removed/replaced by hand outside the launcher. Absent entry = never
     *  installed through the launcher, same as before this field existed. */
    private Map<String, String> installedPluginVersions = new HashMap<>();
    private NetworkInfo network;

    public ServerEntry() {
        this.id = UUID.randomUUID().toString();
    }

    public ServerEntry(String name, String version, String loader, boolean isLocal) {
        this();
        this.name = name;
        this.version = version;
        this.loader = loader;
        this.isLocal = isLocal;
    }

    /** A server is controllable from the launcher if it's local (always reachable),
     *  or it's a VPS whose agent has been confirmed reachable (see AgentClient.ping()). */
    public boolean canControl() {
        // Сервер панели VPS управляется по SSH, как только на нём установлен Minecraft.
        return isLocal || agentConnected || (isSsh() && sshInstalled);
    }

    // ---------- Getters / setters ----------

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getLoader() {
        return loader;
    }

    public void setLoader(String loader) {
        this.loader = loader;
    }

    public boolean isLocal() {
        return isLocal;
    }

    public void setLocal(boolean local) {
        isLocal = local;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public Integer getRamMb() {
        return ramMb;
    }

    public void setRamMb(Integer ramMb) {
        this.ramMb = ramMb;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getAgentPort() {
        return agentPort;
    }

    public void setAgentPort(int agentPort) {
        this.agentPort = agentPort;
    }

    public String getAgentKey() {
        return agentKey;
    }

    public void setAgentKey(String agentKey) {
        this.agentKey = agentKey;
    }

    public String getAgentFingerprint() {
        return agentFingerprint;
    }

    public void setAgentFingerprint(String agentFingerprint) {
        this.agentFingerprint = agentFingerprint;
    }

    public boolean isAgentConnected() {
        return agentConnected;
    }

    public void setAgentConnected(boolean agentConnected) {
        this.agentConnected = agentConnected;
    }

    public boolean isRunning() {
        return running;
    }

    public void setRunning(boolean running) {
        this.running = running;
    }

    public String getPending() {
        return pending;
    }

    public void setPending(String pending) {
        this.pending = pending;
    }

    public List<String> getPlugins() {
        return plugins;
    }

    public void setPlugins(List<String> plugins) {
        this.plugins = plugins != null ? plugins : new ArrayList<>();
    }

    public List<String> getActivatedBrandPlugins() {
        // Gson writes fields directly via reflection on load, bypassing setActivatedBrandPlugins'
        // null-guard — so a server saved before this field existed would deserialize it as null.
        // Guard here rather than in ServerService, so this stays correct regardless of how the
        // entry was constructed.
        if (activatedBrandPlugins == null) activatedBrandPlugins = new ArrayList<>();
        return activatedBrandPlugins;
    }

    public void setActivatedBrandPlugins(List<String> activatedBrandPlugins) {
        this.activatedBrandPlugins = activatedBrandPlugins != null ? activatedBrandPlugins : new ArrayList<>();
    }

    public Map<String, String> getInstalledPluginVersions() {
        // Same Gson-bypasses-setter concern as getActivatedBrandPlugins() above.
        if (installedPluginVersions == null) installedPluginVersions = new HashMap<>();
        return installedPluginVersions;
    }

    public void setInstalledPluginVersions(Map<String, String> installedPluginVersions) {
        this.installedPluginVersions = installedPluginVersions != null ? installedPluginVersions : new HashMap<>();
    }

    public NetworkInfo getNetwork() {
        return network;
    }

    public void setNetwork(NetworkInfo network) {
        this.network = network;
    }

    // ---------- Панель VPS по SSH ----------

    public boolean isSsh() {
        return "ssh".equals(connection);
    }

    public String getConnection() { return connection; }
    public void setConnection(String connection) { this.connection = connection; }
    public int getSshPort() { return sshPort; }
    public void setSshPort(int sshPort) { this.sshPort = sshPort; }
    public String getSshUser() { return sshUser; }
    public void setSshUser(String sshUser) { this.sshUser = sshUser; }
    public String getSshHostKey() { return sshHostKey; }
    public void setSshHostKey(String sshHostKey) { this.sshHostKey = sshHostKey; }
    public String getSshHostKeyType() { return sshHostKeyType; }
    public void setSshHostKeyType(String sshHostKeyType) { this.sshHostKeyType = sshHostKeyType; }
    public String getSshPublicKey() { return sshPublicKey; }
    public void setSshPublicKey(String sshPublicKey) { this.sshPublicKey = sshPublicKey; }
    public String getSshPrivilege() { return sshPrivilege; }
    public void setSshPrivilege(String sshPrivilege) { this.sshPrivilege = sshPrivilege; }
    public String getSshOs() { return sshOs; }
    public void setSshOs(String sshOs) { this.sshOs = sshOs; }
    public String getLoaderVersion() { return loaderVersion; }
    public void setLoaderVersion(String loaderVersion) { this.loaderVersion = loaderVersion; }
    public String getSshServerDir() { return sshServerDir; }
    public void setSshServerDir(String sshServerDir) { this.sshServerDir = sshServerDir; }
    public String getSshService() { return sshService; }
    public void setSshService(String sshService) { this.sshService = sshService; }
    public int getSshRconPort() { return sshRconPort; }
    public void setSshRconPort(int sshRconPort) { this.sshRconPort = sshRconPort; }
    public int getSshJavaMajor() { return sshJavaMajor; }
    public void setSshJavaMajor(int sshJavaMajor) { this.sshJavaMajor = sshJavaMajor; }
    public String getSshProfile() { return sshProfile; }
    public void setSshProfile(String sshProfile) { this.sshProfile = sshProfile; }
    public boolean isSshInstalled() { return sshInstalled; }
    public void setSshInstalled(boolean sshInstalled) { this.sshInstalled = sshInstalled; }
    public boolean isSshStatusKnown() { return sshStatusKnown; }
    public void setSshStatusKnown(boolean known) { this.sshStatusKnown = known; }

    /** Имя записи в SecretStore с приватным ключом этого сервера. */
    public String secretKeyName() { return "vps/" + id + "/key"; }
    /** Имя записи в SecretStore с паролем sudo (только для SUDO_PASSWORD). */
    public String secretSudoName() { return "vps/" + id + "/sudo"; }
    /** Имя записи в SecretStore с паролем RCON. */
    public String secretRconName() { return "vps/" + id + "/rcon"; }
    /** Префикс всех секретов этого сервера — для удаления разом. */
    public String secretPrefix() { return "vps/" + id + "/"; }

    /** Convenience: install command shown in the agent connection panel — see AGENT_API.md's agent.sh. */
    public String buildAgentInstallCommand() {
        if (agentKey == null || agentKey.isBlank()) {
            agentKey = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        return "curl -sSL https://blauncher.ru/agent.sh | bash -s -- --key=" + agentKey;
    }

    @Override
    public String toString() {
        return name + " (" + version + (loader != null && !loader.isBlank() ? " " + loader : "") + ")";
    }
}
