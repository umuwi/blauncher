package com.example.launcher.service;

import com.example.launcher.model.BrandPlugin;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Fetches the "brand plugins" catalog for the tab described in BrandPlugin's javadoc.
 *
 * <p>{@link #ENDPOINT} is now live — {@code GET /api/plugins.php} on blauncher.ru, backed by the
 * {@code plugin_catalog} table in the license-server's SQLite DB (see the secret admin panel's
 * "Плагины" tab for managing entries: name/version/download URL/visibility). Only rows the admin
 * marked visible are returned — see {@link #parseCatalog}'s {@code visible} handling. If the
 * request fails for any reason (network down, endpoint returns something unexpected), every fetch
 * falls back to {@link #FALLBACK_CATALOG} so the tab never renders empty.
 *
 * <p>Field names read in {@link #parseCatalog}: "id", "name", "description", "longDescription",
 * "funpayUrl", "price", "iconUrl", "downloadUrl", "fileName", "sha256", "latestVersion",
 * "mcVersions", "loader", "visible".
 *
 * <p><b>Full contract: API_PLUGINS_SPEC.md</b> (repo root), §1. Note the spec calls out one
 * consequence of the always-fall-back behavior described above: a broken /api/plugins.php deploy
 * is invisible from the launcher's UI (it just shows the fallback), so verify that endpoint
 * with curl rather than by looking at the app.
 */
public class BrandPluginCatalogService {

    private static final String ENDPOINT = "https://blauncher.ru/api/plugins.php";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Used only when the real catalog endpoint can't be reached (network down, site outage) —
     * kept roughly in sync with the plugin_catalog DB rows so a temporary outage doesn't hide
     * install/update buttons entirely. id must exactly match the pluginId values
     * license-server/sehsbatwvrhhfakyacjakxwkw/generate-keys.php was called with for each batch.
     *
     * <p><b>sha256/latestVersion here MUST match the real currently-deployed jar</b> — the agent
     * hard-fails (deletes the download, no partial install) on any hash mismatch, so a stale
     * hash here doesn't just show wrong info, it makes every install/update silently fail
     * whenever a client happens to hit this fallback (e.g. api/plugins.php down but the static
     * downloads/ still serving fine — confirmed to actually happen: this exact list previously
     * carried a stale sha256 for blauction from an older jar build). Re-verify with
     * `sha256sum downloads/plugins/<file>.jar` on the server before changing these, don't
     * hand-edit from memory.
     */
    private static BrandPlugin bukkitPlugin(String id, String name, String description, String downloadUrl,
                                             String fileName, String sha256, String version, String mcVersions) {
        BrandPlugin plugin = new BrandPlugin(id, name, description, "https://t.me/theBLauncher", null, null,
                downloadUrl, fileName, sha256, version);
        plugin.setMcVersions(mcVersions);
        plugin.setLoader("Paper/Spigot");
        return plugin;
    }

    private static final List<BrandPlugin> FALLBACK_CATALOG = List.of(
            bukkitPlugin(
                    "blauction",
                    "BLAuction",
                    "Аукцион между игроками, NPC-скупщик, NPC-торговец и зарплата для BLauncher",
                    "https://blauncher.ru/downloads/plugins/blauction.jar",
                    "blauction.jar",
                    "4e03116b466c3f90181ddce0f03b7dd475ec6a0f3f19f008dee1b2ac2489b214",
                    "1.1.0",
                    "1.21.x"),
            bukkitPlugin(
                    "blprivileges",
                    "BLPrivileges",
                    "Привилегии/ранги по кодам активации — общий язык (пермишены) с остальной экосистемой BLauncher",
                    "https://blauncher.ru/downloads/plugins/blprivileges.jar",
                    "blprivileges.jar",
                    "1369212449c8fa344868e1155932f3d4e4d0b1a2cbc93b5721da5e8644bd1af5",
                    "1.1.0",
                    "1.21.x")
    );

    /**
     * Always completes successfully with a non-null, non-empty list — either the live catalog
     * or {@link #FALLBACK_CATALOG}. Runs off the calling thread; hop back with Platform.runLater
     * same as PluginLicenseService/AgentClient callers do.
     */
    public CompletableFuture<List<BrandPlugin>> fetchCatalog() {
        // Http is blocking, so the async-ness that sendAsync() used to provide now comes from
        // supplyAsync - same contract for callers: never fails, always lands on a background thread.
        return CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return parseCatalogOrFallback(Http.get(ENDPOINT,
                                Map.of("Accept", "application/json"), HTTP_TIMEOUT));
                    } catch (Exception e) {
                        return FALLBACK_CATALOG;
                    }
                })
                .exceptionally(ex -> FALLBACK_CATALOG);
    }

    private List<BrandPlugin> parseCatalogOrFallback(Http.Response response) {
        if (!response.ok()) {
            return FALLBACK_CATALOG;
        }
        try {
            return parseCatalog(response.text());
        } catch (Exception e) {
            // Malformed/unexpected body (e.g. endpoint not deployed yet and something else
            // answers on that path) — fall back rather than propagate a parse error.
            return FALLBACK_CATALOG;
        }
    }

    private List<BrandPlugin> parseCatalog(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray items = root.has("plugins") ? root.getAsJsonArray("plugins") : new JsonArray();

        List<BrandPlugin> result = new ArrayList<>();
        for (JsonElement el : items) {
            JsonObject o = el.getAsJsonObject();
            String id = getString(o, "id");
            String name = getString(o, "name");
            String funpayUrl = getString(o, "funpayUrl");
            if (id == null || name == null || funpayUrl == null) {
                // Skip malformed entries rather than failing the whole catalog over one bad row.
                continue;
            }
            BrandPlugin plugin = new BrandPlugin(
                    id,
                    name,
                    getString(o, "description"),
                    funpayUrl,
                    getString(o, "price"),
                    getString(o, "iconUrl"),
                    getString(o, "downloadUrl"),
                    getString(o, "fileName"),
                    getString(o, "sha256"),
                    getString(o, "latestVersion"));
            plugin.setLongDescription(getString(o, "longDescription"));
            plugin.setMcVersions(getString(o, "mcVersions"));
            plugin.setLoader(getString(o, "loader"));
            // Absent field (older/partial deploy) defaults true via BrandPlugin's own field
            // default — only an explicit "visible": false actually hides a row.
            if (o.has("visible") && !o.get("visible").isJsonNull()) {
                plugin.setVisible(truthy(o.get("visible")));
            }
            result.add(plugin);
        }
        return result.isEmpty() ? FALLBACK_CATALOG : result;
    }

    /**
     * Истинность значения из JSON, терпимая к тому, в каком виде его прислал сервер.
     *
     * <p>{@code JsonPrimitive.getAsBoolean()} у Gson возвращает true только для JSON {@code true}
     * или строки {@code "true"}; для ЧИСЛА 1 он делает {@code Boolean.parseBoolean("1")} и даёт
     * false. Бэкенд каталога — таблица SQLite, отдаваемая через PHP, где булев столбец
     * сериализуется как {@code 1}/{@code 0}, если его явно не приводить. Из-за этого
     * {@code "visible": 1} у всех строк давало непустой список (аварийный FALLBACK_CATALOG не
     * включался), но фильтр по isVisible отдавал пустоту: «Скачать плагины» не показывал ничего,
     * а купленные плагины тихо не устанавливались — со стороны клиента совершенно невидимо.
     */
    private static boolean truthy(com.google.gson.JsonElement el) {
        if (el == null || el.isJsonNull()) return false;
        if (!el.isJsonPrimitive()) return false;
        com.google.gson.JsonPrimitive prim = el.getAsJsonPrimitive();
        if (prim.isBoolean()) return prim.getAsBoolean();
        if (prim.isNumber()) return prim.getAsDouble() != 0;
        String text = prim.getAsString().trim().toLowerCase();
        return text.equals("true") || text.equals("1") || text.equals("yes") || text.equals("on");
    }

    private static String getString(JsonObject o, String field) {
        return o.has(field) && !o.get(field).isJsonNull() ? o.get(field).getAsString() : null;
    }
}
