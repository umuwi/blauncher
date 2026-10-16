package com.example.launcher.model;

/**
 * Represents a mod, either from Modrinth search results or locally installed.
 */
public class ModInfo {

    // Used for remote Modrinth mods
    private String projectId;
    private String slug;
    private String title;
    private String description;
    private String iconUrl;
    private long downloads;
    private java.util.List<String> loaders = java.util.Collections.emptyList();
    private java.util.List<String> gameVersions = java.util.Collections.emptyList();

    // Used for locally installed mods
    private String fileName;
    private boolean enabled;

    // ---------- Constructors ----------

    /** Full remote mod (from search) */
    public ModInfo(String projectId, String slug, String title, String description,
                   String iconUrl, long downloads) {
        this.projectId = projectId;
        this.slug = slug;
        this.title = title;
        this.description = description;
        this.iconUrl = iconUrl;
        this.downloads = downloads;
    }

    /** Local installed mod */
    public ModInfo(String fileName, boolean enabled) {
        this.fileName = fileName;
        this.enabled = enabled;
    }

    // ---------- Getters / Setters ----------

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getIconUrl() {
        return iconUrl;
    }

    public void setIconUrl(String iconUrl) {
        this.iconUrl = iconUrl;
    }

    public long getDownloads() {
        return downloads;
    }

    public void setDownloads(long downloads) {
        this.downloads = downloads;
    }

    public java.util.List<String> getLoaders() {
        return loaders;
    }

    public void setLoaders(java.util.List<String> loaders) {
        this.loaders = loaders != null ? loaders : java.util.Collections.emptyList();
    }

    public java.util.List<String> getGameVersions() {
        return gameVersions;
    }

    public void setGameVersions(java.util.List<String> gameVersions) {
        this.gameVersions = gameVersions != null ? gameVersions : java.util.Collections.emptyList();
    }

    /**
     * Returns a human-readable "min - max" range of supported MC versions,
     * e.g. "1.19.2 - 1.20.4", or a single version if there's only one,
     * or "" if no version data is known.
     * Sorts numerically (not lexicographically) so "1.9" doesn't outrank "1.10".
     */
    public String getVersionRangeLabel() {
        if (gameVersions == null || gameVersions.isEmpty()) return "";
        java.util.List<String> sorted = new java.util.ArrayList<>(gameVersions);
        sorted.sort(ModInfo::compareMcVersions);
        String min = sorted.get(0);
        String max = sorted.get(sorted.size() - 1);
        return min.equals(max) ? min : (min + " - " + max);
    }

    /** Sorts a list of MC versions numerically (not lexicographically), oldest first. */
    public static java.util.List<String> sortedVersions(java.util.List<String> versions) {
        java.util.List<String> sorted = new java.util.ArrayList<>(versions);
        sorted.sort(ModInfo::compareMcVersions);
        return sorted;
    }

    private static int compareMcVersions(String a, String b) {
        String[] pa = a.replaceAll("[^0-9.].*", "").split("\\.");
        String[] pb = b.replaceAll("[^0-9.].*", "").split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            int va = i < pa.length && !pa[i].isEmpty() ? safeParse(pa[i]) : 0;
            int vb = i < pb.length && !pb[i].isEmpty() ? safeParse(pb[i]) : 0;
            if (va != vb) return Integer.compare(va, vb);
        }
        return 0;
    }

    private static int safeParse(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public String toString() {
        if (title != null) {
            StringBuilder sb = new StringBuilder(title);
            String versionRange = getVersionRangeLabel();
            boolean hasMeta = (loaders != null && !loaders.isEmpty()) || !versionRange.isEmpty();
            if (hasMeta) {
                sb.append("  (");
                if (!versionRange.isEmpty()) sb.append(versionRange);
                if (loaders != null && !loaders.isEmpty()) {
                    if (!versionRange.isEmpty()) sb.append(" \u00b7 ");
                    sb.append(String.join("/", loaders));
                }
                sb.append(")");
            }
            return sb.toString();
        }
        return fileName != null ? fileName : "Unknown Mod";
    }
}