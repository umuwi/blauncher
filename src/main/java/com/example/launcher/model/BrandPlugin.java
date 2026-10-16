package com.example.launcher.model;

/**
 * One entry in BLauncher's own ("brand") plugin catalog.
 *
 * <p>This is now fetched live from {@code GET https://blauncher.ru/api/plugins} via
 * {@link com.example.launcher.service.BrandPluginCatalogService}. If that request fails
 * (offline, endpoint not deployed yet), the service falls back to a small built-in list so
 * the tab never renders empty — see the service class for details.
 *
 * <p>Purchase happens off-platform on FunPay (funpayUrl) — the launcher never handles payment
 * itself, only the post-purchase key activation via PluginLicenseService.
 *
 * <p>Field names mirror the API contract: id, name, description, funpayUrl, price, iconUrl.
 * price and iconUrl are optional display extras — null/blank means "don't show" to the renderer.
 *
 * <p>downloadUrl/fileName/sha256 are what PluginsPanelController's "Установить" button needs to
 * actually place the jar (locally, or via AgentClient.startPluginInstall for a VPS) — see that
 * class. downloadUrl null/blank means "no installable jar for this entry yet" and the renderer
 * hides the button rather than offering a dead action.
 */
public class BrandPlugin {

    private String id;
    private String name;
    private String description;
    private String longDescription;
    private String funpayUrl;
    private String price;
    private String iconUrl;
    private String downloadUrl;
    private String fileName;
    private String sha256;
    private String latestVersion;
    private String mcVersions;
    private String loader;
    private boolean visible = true;

    public BrandPlugin() {
    }

    public BrandPlugin(String id, String name, String description, String funpayUrl) {
        this(id, name, description, funpayUrl, null, null);
    }

    public BrandPlugin(String id, String name, String description, String funpayUrl,
                        String price, String iconUrl) {
        this(id, name, description, funpayUrl, price, iconUrl, null, null, null);
    }

    public BrandPlugin(String id, String name, String description, String funpayUrl, String price, String iconUrl,
                        String downloadUrl, String fileName, String sha256) {
        this(id, name, description, funpayUrl, price, iconUrl, downloadUrl, fileName, sha256, null);
    }

    public BrandPlugin(String id, String name, String description, String funpayUrl, String price, String iconUrl,
                        String downloadUrl, String fileName, String sha256, String latestVersion) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.funpayUrl = funpayUrl;
        this.price = price;
        this.iconUrl = iconUrl;
        this.downloadUrl = downloadUrl;
        this.fileName = fileName;
        this.sha256 = sha256;
        this.latestVersion = latestVersion;
    }

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

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    /** Longer write-up shown when the player clicks into a plugin from the "Скачать плагины"
     *  catalog browser (see PluginsPanelController#renderPluginCatalog). Null/blank means the
     *  detail view just falls back to {@link #getDescription()}. */
    public String getLongDescription() {
        return longDescription;
    }

    public void setLongDescription(String longDescription) {
        this.longDescription = longDescription;
    }

    public String getFunpayUrl() {
        return funpayUrl;
    }

    public void setFunpayUrl(String funpayUrl) {
        this.funpayUrl = funpayUrl;
    }

    /** Display price string (e.g. "299 ₽"), already formatted server-side. Null if unset. */
    public String getPrice() {
        return price;
    }

    public void setPrice(String price) {
        this.price = price;
    }

    /** Optional icon URL for the catalog row. Null if unset. */
    public String getIconUrl() {
        return iconUrl;
    }

    public void setIconUrl(String iconUrl) {
        this.iconUrl = iconUrl;
    }

    /** Stable static URL the jar can be downloaded from. Null means no installable jar yet. */
    public String getDownloadUrl() {
        return downloadUrl;
    }

    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
    }

    /** Name to save the jar as in plugins/ — must end in ".jar", no path segments. */
    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    /** Hex SHA-256 of the file at downloadUrl, verified after download. Null skips verification. */
    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    /** Dotted-integer version string (e.g. "1.2.0") of whatever downloadUrl currently points at.
     *  Compared against ServerEntry.getInstalledPluginVersions() to decide Install vs Update in
     *  PluginsPanelController. Null means "unknown" — treated as "no update available" rather
     *  than guessing, since we can't compare against nothing. */
    public String getLatestVersion() {
        return latestVersion;
    }

    public void setLatestVersion(String latestVersion) {
        this.latestVersion = latestVersion;
    }

    /** Free-text Minecraft version range set by the admin (e.g. "1.20–1.21.x") — shown as-is in
     *  the catalog browser, never parsed/compared client-side. Null/blank means not specified. */
    public String getMcVersions() {
        return mcVersions;
    }

    public void setMcVersions(String mcVersions) {
        this.mcVersions = mcVersions;
    }

    /** Free-text loader label set by the admin (e.g. "Paper/Spigot", "Fabric"). Same
     *  display-only treatment as {@link #getMcVersions()}. */
    public String getLoader() {
        return loader;
    }

    public void setLoader(String loader) {
        this.loader = loader;
    }

    /** false = admin disabled this plugin's visibility server-side (see the secret admin panel's
     *  "Плагины" tab) — PluginsPanelController hides the row entirely when this is false, same
     *  as if the plugin didn't exist at all. Defaults true so old cached/fallback data (which
     *  predates this field) keeps showing as before. */
    public boolean isVisible() {
        return visible;
    }

    public void setVisible(boolean visible) {
        this.visible = visible;
    }
}
