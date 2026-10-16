package com.example.launcher.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class VersionDownloadService {
    private static final String VERSION_MANIFEST = "https://launchermeta.mojang.com/mc/game/version_manifest.json";
    private static final String FABRIC_META = "https://meta.fabricmc.net/v2/versions/";
    /** NeoForge's own maven "versions" API and the static file it is generated from. The API is a
     *  dynamic endpoint (and has been seen answering 404/429/-empty during Cloudflare hiccups),
     *  while maven-metadata.xml is a plain artifact served straight off the repo - so when the API
     *  does not answer 200 the metadata file is what we fall back to. */
    private static final String NEOFORGE_MAVEN_API =
            "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge";
    private static final String NEOFORGE_MAVEN_METADATA =
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml";
    /** NeoForge для 1.20.1 — ещё форк Forge под старым артефактом net.neoforged:forge, версии вида
     *  «1.20.1-47.1.106». В списке net.neoforged:neoforge (начинается с 1.20.2) их нет. */
    private static final String NEOFORGE_LEGACY_MC = "1.20.1";
    private static final String NEOFORGE_LEGACY_MAVEN_API =
            "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/forge";
    /** Loader lists come from small public APIs; a short memo is enough to survive the burst of
     *  lookups a launch makes (Home refresh fetches all four loaders, then the launch resolves one
     *  of them again a second later) without ever showing a stale list for long. */
    private static final Duration LOADER_LIST_TTL = Duration.ofMinutes(15);
    private static final int LOADER_LIST_ATTEMPTS = 3;
    // Asset/library objects are many but tiny, so the download is latency-bound, not bandwidth-
    // bound - more parallel connections finish the thousands of small GETs noticeably faster on a
    // typical connection (the shared HttpClient pools/reuses connections, and Mojang's CDN serves
    // this level of concurrency fine). Kept modest enough not to look like a DoS to the CDN.
    private static final int ASSET_DOWNLOAD_THREADS = 16;
    private static final int MAX_RETRIES = 3;
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    private final Path versionsDir;
    private final Path librariesDir;
    private final Path assetsDir;
    private final Path gameDir;

    /** Memoized NeoForge maven version list (see {@link #fetchNeoForgeMavenVersions()}). */
    private volatile List<String> neoForgeMavenCache;
    private volatile long neoForgeMavenCacheAt;
    /** Why the last {@link #getNeoForgeVersions(String)} call came back empty, or null when it
     *  succeeded - an empty list means both "NeoForge has nothing for this MC version" and "the
     *  list could not be fetched", and callers (the launch flow) need to tell those apart before
     *  deciding what to tell the player. Best-effort: set under concurrency, last writer wins. */
    private volatile String lastNeoForgeListError;

    public VersionDownloadService(Path gameDir) {
        this.gameDir = gameDir;
        this.versionsDir = gameDir.resolve("versions");
        this.librariesDir = gameDir.resolve("libraries");
        this.assetsDir = gameDir.resolve("assets");
    }

    /** Lets callers (MainController) surface real progress in the visible UI log instead of
     *  only the plain System.out/err lines below, which a jpackage GUI-subsystem build (no
     *  console) silently discards - leaving the UI showing nothing between "Downloading
     *  Minecraft X" and either success or failure, indistinguishable from a hang. */
    public interface ProgressCallback { void onProgress(String msg); }

    private static final ProgressCallback NO_OP = msg -> {};

    // ---------- HTTP ----------
    // Deliberately HttpURLConnection, NOT java.net.http. On the bundled Java 21.0.10 runtime,
    // java.net.http's TLS read path can get stuck spinning: SSLFlowDelegate$Reader.processData ->
    // SSLEngine.unwrap, endlessly re-submitted by SequentialScheduler. Captured live on this
    // launcher 2026-08-05: 30 "HttpClient-1-Worker" threads at ~45% of a core each (all 12 cores
    // pinned, 2 GB resident, 22 of them allocating a fresh ByteBuffer every iteration) - and they
    // outlive the download that spawned them, so the launcher stays hot until it is killed. The
    // downloads themselves had already finished. The runtime can't be swapped from our side:
    // auto-update ships a new jar, not a new app image, so the fix has to be to stop using that
    // code path. HttpURLConnection has none of that machinery (AuthService already uses it), and
    // its keep-alive pool still gives connection reuse across the thousands of small asset GETs -
    // provided we never call disconnect(), which would close the socket instead of returning it.
    private record TextResponse(int statusCode, String body) {}
    private record BinaryResponse(int statusCode, byte[] body) {}

    private static Map<String, String> ua(String userAgent) {
        return userAgent == null ? Map.of() : Map.of("User-Agent", userAgent);
    }

    private TextResponse httpGetString(String url) throws IOException {
        return httpGetString(url, null);
    }

    private TextResponse httpGetString(String url, String userAgent) throws IOException {
        Http.Response r = Http.get(url, ua(userAgent), HTTP_TIMEOUT);
        return new TextResponse(r.status(), r.text());
    }

    private BinaryResponse httpGetBytes(String url) throws IOException {
        return httpGetBytes(url, null);
    }

    private BinaryResponse httpGetBytes(String url, String userAgent) throws IOException {
        Http.Response r = Http.get(url, ua(userAgent), HTTP_TIMEOUT);
        return new BinaryResponse(r.status(), r.body());
    }

    /**
     * Same as {@link #httpGetString(String, String)} but retried a few times, and only accepting a
     * 200. Kept for the small loader lookup APIs: those are single-shot calls whose failure used to
     * be indistinguishable from "this loader has no builds for that Minecraft version" (both came
     * back as an empty list), which made a one-off connection reset silently turn into a vanilla
     * launch. Retries also recover from a keep-alive socket that the server has already closed -
     * the second attempt opens a fresh connection.
     */
    private TextResponse httpGetStringRetry(String url, String userAgent, int attempts)
            throws IOException, InterruptedException {
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= Math.max(1, attempts); attempt++) {
            try {
                TextResponse response = httpGetString(url, userAgent);
                if (response.statusCode() == 200) return response;
                lastFailure = new IOException("HTTP " + response.statusCode() + " для " + url);
                if (attempt == attempts) return response;
            } catch (IOException e) {
                lastFailure = e;
                if (attempt == attempts) throw e;
            }
            Thread.sleep(400L * attempt);
        }
        throw lastFailure != null ? lastFailure : new IOException("Не удалось получить " + url);
    }

    /** Releases only — unchanged behavior for existing callers. */
    public List<String> getAvailableVersions() throws IOException, InterruptedException {
        return getAvailableVersions(false);
    }

    /**
     * @param includeSnapshots if true, also includes Mojang's "snapshot" versions (weekly
     *                         development builds, e.g. "25w14a") alongside stable releases,
     *                         letting the launcher's version picker offer them the same way
     *                         the VPS agent's own /versions endpoint now does.
     */
    public List<String> getAvailableVersions(boolean includeSnapshots) throws IOException, InterruptedException {
        TextResponse response = httpGetString(VERSION_MANIFEST);
        if (response.statusCode() != 200) {
            throw new IOException("Failed to fetch version manifest: " + response.statusCode());
        }

        JsonObject manifest = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray versions = manifest.getAsJsonArray("versions");

        return versions.asList().stream()
                .map(v -> v.getAsJsonObject())
                .filter(v -> {
                    String type = v.get("type").getAsString();
                    return "release".equals(type) || (includeSnapshots && "snapshot".equals(type));
                })
                .map(v -> v.get("id").getAsString())
                .collect(Collectors.toList());
    }

    public List<String> getForgeVersions(String mcVersion) throws IOException, InterruptedException {
        try {
            String url = "https://maven.minecraftforge.net/api/maven/versions/releases/net/minecraftforge/forge";
            TextResponse response = httpGetStringRetry(url, null, LOADER_LIST_ATTEMPTS);
            if (response.statusCode() != 200) return Collections.emptyList();
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray arr = root.getAsJsonArray("versions");
            List<String> result = new ArrayList<>();
            for (var el : arr.asList()) {
                String v = el.getAsString();
                if (v.startsWith(mcVersion + "-")) {
                    result.add(v.substring(mcVersion.length() + 1));
                }
            }
            Collections.reverse(result);
            return result;
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    public List<String> getFabricVersions(String mcVersion) throws IOException, InterruptedException {
        try {
            String url = FABRIC_META + "loader/" + mcVersion;
            TextResponse response = httpGetStringRetry(url, null, LOADER_LIST_ATTEMPTS);
            if (response.statusCode() != 200) return Collections.emptyList();
            JsonArray arr = JsonParser.parseString(response.body()).getAsJsonArray();
            return arr.asList().stream()
                    .map(o -> o.getAsJsonObject().get("loader").getAsJsonObject().get("version").getAsString())
                    .limit(20)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * The NeoForge maven version prefixes that belong to {@code mcVersion}, most specific first.
     *
     * <p>Three naming schemes exist:
     * <ul>
     *   <li>Minecraft 1.x.y (up to 1.21.x): NeoForge drops the leading "1.", so 1.21.1 -&gt; "21.1.",
     *       and because NeoForge sometimes keeps building on the previous patch's line while a new
     *       Minecraft patch is out, the walk continues downwards (21.1., 21.0.) without ever
     *       dropping the minor - a bare "21." would pull in every 1.21.x line at once.</li>
     *   <li>Minecraft year.minor ("26.2"): NeoForge mirrors it directly, so 26.2 -&gt; "26.2." with
     *       builds named "26.2.0.NN".</li>
     *   <li>Pre-releases and weeklies: "26.3-snapshot-10" belongs to the 26.3 development cycle
     *       ("26.3.0.NN-beta"), and a weekly like "25w14craftmine" is published as
     *       "0.25w14craftmine.N-beta". Returning no prefixes at all here is what made NeoForge look
     *       unavailable for every snapshot ever selected.</li>
     * </ul>
     */
    private List<String> neoForgePrefixes(String mcVersion) {
        List<String> prefixes = new ArrayList<>();
        if (mcVersion == null || mcVersion.isBlank()) return prefixes;

        if (mcVersion.startsWith("1.")) {
            String[] parts = mcVersion.split("\\.");
            if (parts.length < 2) return prefixes;
            int mcMinor;
            int mcPatch;
            try {
                mcMinor = Integer.parseInt(parts[1]);
                // A release with no patch number ("1.21") is NeoForge's "<minor>.0." line, not a
                // version it never built for - skipping these (the old "parts.length < 3" bail-out)
                // is why 1.21 offered no NeoForge builds at all.
                mcPatch = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
            } catch (NumberFormatException e) {
                return prefixes;
            }
            for (int p = mcPatch; p >= 0; p--) {
                prefixes.add(mcMinor + "." + p);
            }
            return prefixes;
        }

        // Weekly snapshot id (25w14a, 25w14craftmine, ...): NeoForge's "0.<id>.<build>-beta" line.
        if (mcVersion.matches("\\d{2}w\\d{2}[a-z]*")) {
            prefixes.add("0." + mcVersion);
            return prefixes;
        }

        // year.minor release ("26.2") or pre-release of one ("26.3-snapshot-10") - both live on the
        // same "<year>.<minor>." line.
        int dash = mcVersion.indexOf('-');
        prefixes.add(dash > 0 ? mcVersion.substring(0, dash) : mcVersion);
        return prefixes;
    }

    /** Why the most recent {@link #getNeoForgeVersions(String)} returned nothing, or null if it
     *  succeeded (an empty list then really means "NeoForge has no build for that MC version"). */
    public String getLastNeoForgeListError() {
        return lastNeoForgeListError;
    }

    /**
     * The full NeoForge maven version list, memoized for {@link #LOADER_LIST_TTL} and fetched from
     * the "versions" API with maven-metadata.xml as a fallback source. A hard failure falls back to
     * the last successful copy if there is one, so a momentary outage cannot make an already-known
     * loader version disappear.
     */
    private List<String> fetchNeoForgeMavenVersions() throws IOException, InterruptedException {
        long now = System.currentTimeMillis();
        List<String> cached = neoForgeMavenCache;
        if (cached != null && now - neoForgeMavenCacheAt < LOADER_LIST_TTL.toMillis()) return cached;

        String problem = null;
        List<String> fresh = null;

        try {
            TextResponse response = httpGetStringRetry(NEOFORGE_MAVEN_API, null, LOADER_LIST_ATTEMPTS);
            if (response.statusCode() == 200) {
                JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                JsonArray versions = root.getAsJsonArray("versions");
                if (versions != null) {
                    fresh = new ArrayList<>(versions.size());
                    for (var el : versions.asList()) fresh.add(el.getAsString());
                }
            } else {
                problem = "HTTP " + response.statusCode();
            }
        } catch (IOException e) {
            problem = String.valueOf(e.getMessage());
        }

        if (fresh == null || fresh.isEmpty()) {
            fresh = null;
            try {
                TextResponse meta = httpGetStringRetry(NEOFORGE_MAVEN_METADATA, null, LOADER_LIST_ATTEMPTS);
                if (meta.statusCode() == 200) {
                    List<String> fromMetadata = new ArrayList<>();
                    java.util.regex.Matcher m = java.util.regex.Pattern
                            .compile("<version>([^<]+)</version>").matcher(meta.body());
                    while (m.find()) fromMetadata.add(m.group(1));
                    if (!fromMetadata.isEmpty()) fresh = fromMetadata;
                } else if (problem == null) {
                    problem = "HTTP " + meta.statusCode() + " (maven-metadata.xml)";
                }
            } catch (IOException e) {
                if (problem == null) problem = String.valueOf(e.getMessage());
            }
        }

        if (fresh == null) {
            if (cached != null) {
                System.err.println("[NEOFORGE] Не удалось обновить список версий (" + problem
                        + ") - использую ранее загруженный список (" + cached.size() + " версий).");
                return cached;
            }
            throw new IOException("не удалось скачать список версий NeoForge"
                    + (problem != null && !problem.isBlank() ? ": " + problem : ""));
        }

        neoForgeMavenCache = fresh;
        neoForgeMavenCacheAt = now;
        return fresh;
    }

    /**
     * Версии NeoForge для 1.20.1 — полные имена «1.20.1-47.1.106», новые сверху. 12.10.2026 владелец
     * сообщил «неофордж не ищется»: для 1.20.1, самой популярной версии со сборками, список был пуст,
     * потому что искали только в net.neoforged:neoforge. В списке есть и мусор вроде «47.1.82» без
     * версии игры — его установщика на сервере нет (404), отсекаем.
     */
    private List<String> fetchNeoForgeLegacyVersions() throws IOException, InterruptedException {
        TextResponse response = httpGetStringRetry(NEOFORGE_LEGACY_MAVEN_API, null, LOADER_LIST_ATTEMPTS);
        if (response.statusCode() != 200) {
            throw new IOException("не удалось скачать список версий NeoForge для 1.20.1: HTTP " + response.statusCode());
        }
        JsonArray versions = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("versions");
        List<String> out = new ArrayList<>();
        String prefix = NEOFORGE_LEGACY_MC + "-";
        if (versions != null) {
            for (var el : versions.asList()) {
                String v = el.getAsString();
                if (v.startsWith(prefix)) out.add(v);
            }
        }
        out.sort((a, b) -> compareNumericDesc(a.substring(prefix.length()), b.substring(prefix.length())));
        return out;
    }

    /** «47.1.106» раньше «47.1.99»: по числам, новые первыми. */
    private static int compareNumericDesc(String a, String b) {
        String[] x = a.split("[.\\-]");
        String[] y = b.split("[.\\-]");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int cmp;
            try {
                cmp = Integer.compare(Integer.parseInt(y[i]), Integer.parseInt(x[i]));
            } catch (NumberFormatException e) {
                cmp = y[i].compareTo(x[i]);
            }
            if (cmp != 0) return cmp;
        }
        return Integer.compare(y.length, x.length);
    }

    /** Версия NeoForge из старой линейки 1.20.1 («1.20.1-47.1.106»). */
    public static boolean isLegacyNeoForge(String neoVersion) {
        return neoVersion != null && neoVersion.startsWith(NEOFORGE_LEGACY_MC + "-");
    }

    public List<String> getNeoForgeVersions(String mcVersion) throws IOException, InterruptedException {
        try {
            if (NEOFORGE_LEGACY_MC.equals(mcVersion)) {
                List<String> legacy = fetchNeoForgeLegacyVersions();
                lastNeoForgeListError = null;
                return legacy;
            }
            List<String> prefixes = neoForgePrefixes(mcVersion);
            if (prefixes.isEmpty()) {
                lastNeoForgeListError = null;
                return Collections.emptyList();
            }

            List<String> allVersions = fetchNeoForgeMavenVersions();

            // Exact-prefix matches first, looser (previous-patch) ones after, each group newest
            // first - so the "latest build" a launch resolves to is always one published for the
            // requested Minecraft version whenever such a build exists at all.
            List<String> exact = new ArrayList<>();
            List<String> fallback = new ArrayList<>();
            for (String v : allVersions) {
                for (int i = 0; i < prefixes.size(); i++) {
                    String prefix = prefixes.get(i);
                    if (v.startsWith(prefix + ".") || v.startsWith(prefix + "-")) {
                        (i == 0 ? exact : fallback).add(v);
                        break;
                    }
                }
            }

            sortNeoForgeVersionsDescending(exact);
            sortNeoForgeVersionsDescending(fallback);
            List<String> result = new ArrayList<>(exact.size() + fallback.size());
            result.addAll(exact);
            result.addAll(fallback);

            lastNeoForgeListError = null;
            return result;
        } catch (Exception e) {
            // Never a silent empty list: the launch flow reports this string to the player instead
            // of dropping them into a vanilla game with no explanation.
            lastNeoForgeListError = e.getMessage() != null ? e.getMessage() : e.toString();
            System.err.println("[NEOFORGE] Список версий для MC " + mcVersion + " недоступен: "
                    + lastNeoForgeListError);
            return Collections.emptyList();
        }
    }

    private static void sortNeoForgeVersionsDescending(List<String> versions) {
        versions.sort((a, b) -> {
            String[] aParts = a.split("\\.");
            String[] bParts = b.split("\\.");
            int len = Math.min(aParts.length, bParts.length);
            for (int i = 0; i < len; i++) {
                try {
                    int aNum = Integer.parseInt(aParts[i].split("-")[0]);
                    int bNum = Integer.parseInt(bParts[i].split("-")[0]);
                    if (aNum != bNum) return Integer.compare(bNum, aNum);
                } catch (NumberFormatException e) {
                    int cmp = bParts[i].compareTo(aParts[i]);
                    if (cmp != 0) return cmp;
                }
            }
            return Integer.compare(bParts.length, aParts.length);
        });
    }

    /**
     * Scrapes the OptiFine downloads page for build codes (e.g. "HD_U_I6") available
     * for the given Minecraft version. OptiFine has no public version API - the
     * download filenames embed both the MC version and the build code
     * (e.g. "OptiFine_1.20.1_HD_U_I6.jar"), newest first.
     */
    public List<String> getOptiFineVersions(String mcVersion) throws IOException, InterruptedException {
        TextResponse response = httpGetString("https://optifine.net/downloads", "Mozilla/5.0");
        if (response.statusCode() != 200) return Collections.emptyList();

        String prefix = "OptiFine_" + mcVersion + "_";
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(prefix) + "([A-Za-z0-9_.]+)\\.jar");
        java.util.regex.Matcher matcher = pattern.matcher(response.body());

        List<String> result = new ArrayList<>();
        while (matcher.find()) {
            String code = matcher.group(1);
            if (!result.contains(code)) result.add(code);
        }
        return result;
    }

    /**
     * Resolves the actual downloadable filename for an OptiFine build - NOT always
     * {@code "OptiFine_<mc>_<ofVer>.jar"}. For Minecraft versions with no stable OptiFine release
     * yet (the common case right after a new MC version ships - confirmed against the live site
     * for MC 26.1.2, whose only listed build is "HD_U_K1_pre2"), optifine.net serves the file
     * under a {@code "preview_"}-prefixed name instead
     * ({@code "preview_OptiFine_26.1.2_HD_U_K1_pre2.jar"}), even though the version code text on
     * the downloads page (and so the code {@link #getOptiFineVersions} returns) never carries that
     * prefix. Requesting the plain name for one of these builds doesn't 404 - optifine.net answers
     * with HTTP 200 and a 16-byte "File not found." HTML body, which the generic
     * {@link #downloadFile} would otherwise write out verbatim as if it were a real jar, so the
     * "installed" OptiFine silently never actually works. Tries the plain name first (the common
     * case for older/stable MC versions) and falls back to the "preview_" name.
     */
    public String resolveOptiFineFileName(String mcVersion, String ofVersion) throws IOException, InterruptedException {
        String plain = "OptiFine_" + mcVersion + "_" + ofVersion + ".jar";
        if (optiFineFileExists(plain)) return plain;
        String preview = "preview_" + plain;
        if (optiFineFileExists(preview)) return preview;
        throw new IOException("OptiFine build " + ofVersion + " for MC " + mcVersion
                + " could not be located under either its plain or \"preview_\" filename.");
    }

    /** optifine.net answers an unknown/mismatched filename with HTTP 200 and a tiny "File not
     *  found." HTML body rather than a real 404 status - so status alone can't distinguish a
     *  real jar from a miss. A genuine OptiFine jar is always multiple megabytes; 4KB is a huge
     *  margin above the 16-byte error body without risking rejecting a real (if unusually small)
     *  response. */
    private boolean optiFineFileExists(String fileName) throws IOException, InterruptedException {
        BinaryResponse response = httpGetBytes("https://optifine.net/download?f=" + fileName, "Mozilla/5.0");
        return response.statusCode() == 200 && response.body().length > 4096;
    }

    public String getForgeInstallerUrl(String mcVersion, String forgeVersion) {
        return "https://maven.minecraftforge.net/net/minecraftforge/forge/" + mcVersion + "-" + forgeVersion + "/forge-" + mcVersion + "-" + forgeVersion + "-installer.jar";
    }

    public String getNeoForgeInstallerUrl(String mcVersion, String neoVersion) {
        // NeoForge only serves artifacts from the "releases" maven repo - there is no
        // separate "beta" repo (it 404s). Versions with a "-beta" suffix in their name
        // still live under releases/.
        if (isLegacyNeoForge(neoVersion)) {
            return "https://maven.neoforged.net/releases/net/neoforged/forge/" + neoVersion + "/forge-" + neoVersion + "-installer.jar";
        }
        return "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + neoVersion + "/neoforge-" + neoVersion + "-installer.jar";
    }

    /**
     * Finds the actual NeoForge version folder created by the installer.
     * The installer may create "neoforge-21.0.14-beta" or "neoforge-21.0.14"
     * (stripping the -beta suffix).
     */
    /** Папка версии, которую создаёт установщик NeoForge 1.20.1, или null для обычной линейки. */
    public static String legacyNeoForgeFolder(String neoVersion) {
        if (!isLegacyNeoForge(neoVersion)) return null;
        return NEOFORGE_LEGACY_MC + "-forge-" + neoVersion.substring(NEOFORGE_LEGACY_MC.length() + 1);
    }

    public String findNeoForgeVersionFolder(Path gameDir, String neoVersion) {
        Path versionsDir = gameDir.resolve("versions");
        // Линейка 1.20.1 ставится как Forge: «1.20.1-47.1.106» → папка «1.20.1-forge-47.1.106».
        String legacy = legacyNeoForgeFolder(neoVersion);
        if (legacy != null) {
            return Files.exists(versionsDir.resolve(legacy).resolve(legacy + ".json")) ? legacy : null;
        }
        // Try exact match first
        Path exact = versionsDir.resolve("neoforge-" + neoVersion);
        if (Files.exists(exact) && Files.exists(exact.resolve("neoforge-" + neoVersion + ".json"))) {
            return "neoforge-" + neoVersion;
        }
        // Try without beta suffix
        if (neoVersion.contains("-beta")) {
            String stripped = neoVersion.replace("-beta", "");
            Path strippedPath = versionsDir.resolve("neoforge-" + stripped);
            if (Files.exists(strippedPath) && Files.exists(strippedPath.resolve("neoforge-" + stripped + ".json"))) {
                return "neoforge-" + stripped;
            }
        }
        return null;
    }

    public String getFabricInstallerUrl() {
        return "https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.0.0/fabric-installer-1.0.0.jar";
    }

    /** Fetches the full launch profile for Fabric Loader from the Fabric Meta API. */
    public com.google.gson.JsonObject getFabricProfileJson(String mcVersion, String fabricVersion)
            throws IOException, InterruptedException {
        String url = "https://meta.fabricmc.net/v2/versions/loader/" + mcVersion + "/" + fabricVersion + "/profile/json";
        TextResponse response = httpGetString(url);
        if (response.statusCode() != 200) {
            throw new IOException("Fabric profile not found for " + mcVersion + "/" + fabricVersion + " (HTTP " + response.statusCode() + ")");
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    public void downloadVersion(String versionId) throws IOException, InterruptedException {
        downloadVersion(versionId, NO_OP);
    }

    /**
     * Критичные сбои текущей загрузки: библиотеки и клиентский jar, без которых игра не
     * запустится. Ассеты сюда не попадают — без части текстур или звуков игра работает.
     *
     * <p>Раньше сбои только считались и печатались в System.out, который GUI-сборка jpackage
     * отбрасывает (об этом сказано в комментарии самого класса), а downloadVersion возвращался
     * нормально. Из-за этого после 12 неудачных библиотек из 300 показывалось «Загрузка
     * завершена!», игра падала с NoClassDefFoundError, и в видимом логе не было ничего, что
     * объясняло бы причину.
     */
    private final List<String> criticalFailures =
            java.util.Collections.synchronizedList(new ArrayList<>());

    /** Сколько ассетов не скачалось в последний раз — не критично, но стоит показать. */
    public int lastAssetFailures() {
        return lastAssetFailures;
    }

    private volatile int lastAssetFailures;

    public void downloadVersion(String versionId, ProgressCallback progress) throws IOException, InterruptedException {
        criticalFailures.clear();
        lastAssetFailures = 0;
        progress.onProgress("Скачивается: " + versionId + ".json");
        System.out.println("[DOWNLOAD] Fetching version manifest for: " + versionId);

        Path versionDir = versionsDir.resolve(versionId);
        Files.createDirectories(versionDir);

        String manifestUrl = getVersionManifestUrl(versionId);
        System.out.println("[DOWNLOAD] Manifest URL: " + manifestUrl);

        Path versionJson = versionDir.resolve(versionId + ".json");
        downloadFile(manifestUrl, versionJson);
        System.out.println("[DOWNLOAD] Downloaded version.json");

        JsonObject versionData = JsonParser.parseString(Files.readString(versionJson)).getAsJsonObject();

        JsonObject clientInfo = versionData.getAsJsonObject("downloads").getAsJsonObject("client");
        String clientUrl = clientInfo.get("url").getAsString();
        // sha1 клиентского jar Mojang публикует рядом с url — сверяем именно его, потому что
        // битый клиентский jar даёт ClassNotFoundException на главном классе, то есть игра
        // просто не запускается, а причина в логе ничем не подсказана.
        String clientSha1 = clientInfo.has("sha1") ? clientInfo.get("sha1").getAsString() : null;

        progress.onProgress("Скачивается: " + versionId + ".jar");
        Path clientJar = versionDir.resolve(versionId + ".jar");
        downloadFile(clientUrl, clientJar, clientSha1);
        System.out.println("[DOWNLOAD] Downloaded client.jar");
        // Patch client JAR for offline multiplayer
        try { VersionPatcher.patchClientJar(clientJar); } catch (Exception e) { System.err.println("[VersionPatcher] " + e.getMessage()); }

        progress.onProgress("Скачивается: библиотеки");
        System.out.println("[DOWNLOAD] Downloading libraries...");
        JsonArray libraries = versionData.getAsJsonArray("libraries");
        downloadLibraries(libraries);
        progress.onProgress("Библиотеки скачаны");
        System.out.println("[DOWNLOAD] Downloaded libraries");

        progress.onProgress("Скачивается: ресурсы");
        System.out.println("[DOWNLOAD] Downloading assets...");
        String assetIndexUrl = versionData.getAsJsonObject("assetIndex").get("url").getAsString();
        String assetIndexId = versionData.getAsJsonObject("assetIndex").get("id").getAsString();
        downloadAssets(assetIndexUrl, assetIndexId, progress);
        progress.onProgress("Ресурсы скачаны");
        System.out.println("[DOWNLOAD] Assets downloaded");

        // Молчаливый успех после неудавшихся загрузок — худший из возможных исходов: игра
        // всё равно не запустится, но человек об этом узнает из непонятного стектрейса.
        if (!criticalFailures.isEmpty()) {
            int shown = Math.min(3, criticalFailures.size());
            StringBuilder sb = new StringBuilder("Не удалось скачать ")
                    .append(criticalFailures.size())
                    .append(" обязательных файлов — игра без них не запустится. Проверьте интернет"
                            + " и нажмите «Играть» снова (докачается только недостающее).");
            sb.append(" Например: ");
            for (int i = 0; i < shown; i++) {
                if (i > 0) sb.append("; ");
                sb.append(criticalFailures.get(i));
            }
            throw new IOException(sb.toString());
        }
        if (lastAssetFailures > 0) {
            progress.onProgress("Внимание: не скачалось ресурсов: " + lastAssetFailures
                    + " (возможны пропавшие звуки или текстуры)");
        }
    }

    /**
     * Reads "downloads.server.url" from this version's manifest JSON - fetches+caches the
     * manifest first if it hasn't been downloaded yet (e.g. user only ever ran a server for
     * this version, never the client). Returns null if Mojang doesn't publish a server jar for
     * this version (very old snapshots before the dedicated server existed).
     */
    public String getServerJarUrl(String versionId) throws IOException, InterruptedException {
        ServerJarInfo info = getServerJarInfo(versionId);
        return info.url();
    }

    /**
     * Серверный jar версии и то, что о ней нужно знать установщику на VPS: sha1 (Mojang публикует
     * его рядом со ссылкой) и мажорную версию Java из того же JSON. url и sha1 — null, если у
     * версии нет выделенного сервера; javaMajor — 8 для старых версий без поля javaVersion.
     */
    public record ServerJarInfo(String url, String sha1, int javaMajor) {}

    public ServerJarInfo getServerJarInfo(String versionId) throws IOException, InterruptedException {
        Path versionJson = versionsDir.resolve(versionId).resolve(versionId + ".json");
        if (!Files.exists(versionJson)) {
            Files.createDirectories(versionJson.getParent());
            downloadFile(getVersionManifestUrl(versionId), versionJson);
        }
        JsonObject versionData = JsonParser.parseString(Files.readString(versionJson)).getAsJsonObject();
        int javaMajor = 8;
        if (versionData.has("javaVersion") && versionData.getAsJsonObject("javaVersion").has("majorVersion")) {
            javaMajor = versionData.getAsJsonObject("javaVersion").get("majorVersion").getAsInt();
        }
        JsonObject downloads = versionData.getAsJsonObject("downloads");
        if (downloads == null || !downloads.has("server")) return new ServerJarInfo(null, null, javaMajor);
        JsonObject server = downloads.getAsJsonObject("server");
        return new ServerJarInfo(server.get("url").getAsString(),
                server.has("sha1") ? server.get("sha1").getAsString() : null, javaMajor);
    }

    /** Downloads this version's dedicated server jar to {@code destJar} (no-op if it already exists). */
    public void downloadServerJar(String versionId, Path destJar) throws IOException, InterruptedException {
        String url = getServerJarUrl(versionId);
        if (url == null) {
            throw new IOException("Mojang doesn't publish a dedicated server for " + versionId);
        }
        downloadFile(url, destJar);
    }

    /** Последняя сборка Paper: ссылка и sha256 из ответа API. */
    public record PaperBuild(String url, String sha256, String fileName) {}

    /**
     * Resolves the download URL of the latest Paper server build for the given MC version.
     * Throws if Paper doesn't publish a build for this version.
     */
    public String getPaperServerJarUrl(String versionId) throws IOException {
        return getPaperBuild(versionId).url();
    }

    /**
     * Через Fill API v3. API v2 (api.papermc.io/v2) PaperMC отключила: на 07.10.2026 он отвечает
     * 410 Gone на любую версию, и «Paper» не ставился ни локально, ни на VPS. v3 требует
     * осмысленный User-Agent — без него запрос отклоняется.
     */
    public PaperBuild getPaperBuild(String versionId) throws IOException {
        String url = "https://fill.papermc.io/v3/projects/paper/versions/" + versionId + "/builds/latest";
        TextResponse resp = httpGetString(url, "BLauncher/1.0 (https://blauncher.ru)");
        if (resp.statusCode() != 200) {
            throw new IOException("У Paper нет сборки сервера для Minecraft " + versionId);
        }
        JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
        JsonObject downloads = obj.getAsJsonObject("downloads");
        JsonObject server = downloads == null ? null : downloads.getAsJsonObject("server:default");
        if (server == null || !server.has("url")) {
            throw new IOException("PaperMC вернул неожиданный ответ для Minecraft " + versionId);
        }
        String sha256 = null;
        if (server.has("checksums") && server.getAsJsonObject("checksums").has("sha256")) {
            sha256 = server.getAsJsonObject("checksums").get("sha256").getAsString();
        }
        return new PaperBuild(server.get("url").getAsString(), sha256,
                server.has("name") ? server.get("name").getAsString() : "paper.jar");
    }

    /** Текущая стабильная версия установщика Fabric (для ссылки на серверный jar). */
    public String getLatestFabricInstallerVersion() throws IOException, InterruptedException {
        TextResponse resp = httpGetStringRetry(FABRIC_META + "installer", null, LOADER_LIST_ATTEMPTS);
        if (resp.statusCode() != 200) throw new IOException("Fabric Meta недоступен (HTTP " + resp.statusCode() + ")");
        for (var el : JsonParser.parseString(resp.body()).getAsJsonArray()) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("stable") && o.get("stable").getAsBoolean()) return o.get("version").getAsString();
        }
        throw new IOException("Fabric Meta не вернул стабильный установщик");
    }

    /** Fabric Meta: готовый серверный лаунчер (один jar, остальное докачивает при первом запуске). */
    public String getFabricServerLauncherUrl(String mcVersion, String loaderVersion, String installerVersion) {
        return FABRIC_META + "loader/" + mcVersion + "/" + loaderVersion + "/" + installerVersion + "/server/jar";
    }

    /** sha1 артефакта maven (файл url + ".sha1") или null, если его нет. */
    public String fetchMavenSha1(String artifactUrl) {
        try {
            TextResponse resp = httpGetString(artifactUrl + ".sha1");
            if (resp.statusCode() != 200) return null;
            String s = resp.body().trim();
            int sp = s.indexOf(' ');
            if (sp > 0) s = s.substring(0, sp);
            return s.matches("[0-9a-fA-F]{40}") ? s.toLowerCase() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Downloads the latest Paper server jar for {@code versionId} to {@code destJar}. */
    public void downloadPaperServerJar(String versionId, Path destJar) throws IOException, InterruptedException {
        downloadFile(getPaperServerJarUrl(versionId), destJar);
    }

    /**
     * Resolves the download URL of the latest Purpur server build for the given MC version.
     * Uses the PurpurMC v2 API. Throws if Purpur doesn't publish a build for this version.
     */
    public String getPurpurServerJarUrl(String versionId) throws IOException {
        String latestUrl = "https://api.purpurmc.org/v2/purpur/" + versionId + "/latest";
        TextResponse resp = httpGetString(latestUrl, "BLauncher/1.0");
        if (resp.statusCode() != 200) {
            throw new IOException("PurpurMC has no server build for MC " + versionId);
        }
        JsonObject obj = JsonParser.parseString(resp.body()).getAsJsonObject();
        if (!obj.has("build")) {
            throw new IOException("PurpurMC returned an invalid response for MC " + versionId);
        }
        String build = obj.get("build").getAsString();
        return "https://api.purpurmc.org/v2/purpur/" + versionId + "/" + build + "/download";
    }

    /** Downloads the latest Purpur server jar for {@code versionId} to {@code destJar}. */
    public void downloadPurpurServerJar(String versionId, Path destJar) throws IOException, InterruptedException {
        downloadFile(getPurpurServerJarUrl(versionId), destJar);
    }

    /**
     * Resolves the download URL of the latest Pufferfish server build for the given MC version.
     * Pufferfish publishes a single "latest" jar per MC version on their CDN — best-effort URL.
     */
    public String getPufferfishServerJarUrl(String versionId) {
        return "https://cdn.pufferfish.host/downloads/pufferfish/pufferfish-" + versionId + "-latest.jar";
    }

    /** Downloads the latest Pufferfish server jar for {@code versionId} to {@code destJar}. */
    public void downloadPufferfishServerJar(String versionId, Path destJar) throws IOException, InterruptedException {
        downloadFile(getPufferfishServerJarUrl(versionId), destJar);
    }

    public String getAssetIndexId(String versionId) throws IOException {
        Path versionJson = versionsDir.resolve(versionId).resolve(versionId + ".json");
        if (!Files.exists(versionJson)) return versionId;
        JsonObject versionData = JsonParser.parseString(Files.readString(versionJson)).getAsJsonObject();
        if (versionData.has("assetIndex")) {
            return versionData.getAsJsonObject("assetIndex").get("id").getAsString();
        }
        return versionId;
    }

    private String getVersionManifestUrl(String versionId) throws IOException, InterruptedException {
        TextResponse response = httpGetString(VERSION_MANIFEST);
        JsonObject manifest = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray versions = manifest.getAsJsonArray("versions");

        for (var v : versions.asList()) {
            JsonObject vObj = v.getAsJsonObject();
            if (vObj.get("id").getAsString().equals(versionId)) {
                return vObj.get("url").getAsString();
            }
        }
        throw new IOException("Version not found: " + versionId);
    }

    /** One file this library entry needs — either the main artifact or its OS-native classifier. */
    private record LibraryDownload(String url, Path dest, String sha1) {}

    /**
     * Библиотека на месте и того размера, что заявил Mojang (поле {@code size} рядом с url).
     * Раньше хватало «файл существует»: 14.10.2026 netty-all-4.1.9.Final.jar для 1.12.2 скачался
     * обрезанным (2 260 214 байт из 2 521 113, ни одного класса внутри), считался установленным
     * навсегда, и Forge 1.12.2 падал с NoClassDefFoundError: io/netty/buffer/ByteBufOutputStream
     * при каждом запуске. Размер — дёшево проверить на каждом запуске, sha1 сверяется при загрузке.
     */
    static boolean libraryFileOk(Path file, JsonObject artifact) {
        try {
            if (!Files.isRegularFile(file)) return false;
            if (artifact != null && artifact.has("size") && artifact.get("size").isJsonPrimitive()) {
                long expected = artifact.get("size").getAsLong();
                if (expected > 0 && Files.size(file) != expected) return false;
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** jar изменил VersionPatcher и записал рядом свой размер (.blpatched) — и он совпадает. */
    private static boolean patchedSizeMatches(Path jar) {
        try {
            Path mark = jar.resolveSibling(jar.getFileName() + ".blpatched");
            return Files.isRegularFile(mark) && Files.isRegularFile(jar)
                    && Long.parseLong(Files.readString(mark).trim()) == Files.size(jar);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static String sha1Field(JsonObject artifact) {
        return artifact.has("sha1") && artifact.get("sha1").isJsonPrimitive() ? artifact.get("sha1").getAsString() : null;
    }

    /** Downloads every library (and matching OS-native classifier) in parallel — libraries can
     *  number in the hundreds for Forge/NeoForge, and each is a small file, so downloading them
     *  one at a time (as this used to) is dominated by per-request round-trip latency rather than
     *  bandwidth; the same ASSET_DOWNLOAD_THREADS pool used for assets fixes that here too. */
    private void downloadLibraries(JsonArray libraries) throws IOException, InterruptedException {
        String nativeClassifier = getNativeClassifier();
        List<LibraryDownload> toDownload = new ArrayList<>();
        int skipped = 0;
        for (var libElement : libraries.asList()) {
            JsonObject lib = libElement.getAsJsonObject();
            // Библиотеки чужих ОС и чужой архитектуры НЕ качаем. Проверено по 1.21.1.json: каждый
            // нативник — отдельная запись вида "org.lwjgl:lwjgl:3.3.3:natives-linux" со своим
            // downloads.artifact, и фильтр по classifiers его не касался. На этой машине это
            // 91 МБ: natives-linux, natives-macos, natives-macos-arm64, а также natives-windows-x86
            // и natives-windows-arm64 — последние два правила пропускают (у них os.name=windows без
            // arch), а extractNatives потом отбрасывает каждый их файл по папке архитектуры.
            //
            // Те же два условия обязаны стоять в checkNativesInVersion ниже и в
            // GameLaunchService — иначе isVersionFullyInstalled навсегда станет ложным и лаунчер
            // начнёт «докачивать» версию при каждом запуске. См. LibraryRules.
            if (!LibraryRules.appliesToCurrentOs(lib) || LibraryRules.isForeignNative(lib, nativeClassifier)) {
                skipped++;
                continue;
            }
            if (!lib.has("downloads")) continue;
            JsonObject downloads = lib.getAsJsonObject("downloads");

            if (downloads.has("artifact")) {
                JsonObject artifact = downloads.getAsJsonObject("artifact");
                String path = artifact.get("path").getAsString();
                String url = artifact.get("url").getAsString();
                Path libPath = librariesDir.resolve(path);
                if (!libraryFileOk(libPath, artifact)) {
                    toDownload.add(new LibraryDownload(url, libPath, sha1Field(artifact)));
                }
            }

            if (downloads.has("classifiers")) {
                JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                if (classifiers.has(nativeClassifier)) {
                    JsonObject nativeEntry = classifiers.getAsJsonObject(nativeClassifier);
                    String nativePath = nativeEntry.get("path").getAsString();
                    String nativeUrl = nativeEntry.get("url").getAsString();
                    Path nativeLibPath = librariesDir.resolve(nativePath);
                    if (!libraryFileOk(nativeLibPath, nativeEntry)) {
                        toDownload.add(new LibraryDownload(nativeUrl, nativeLibPath, sha1Field(nativeEntry)));
                    }
                }
            }
        }

        if (skipped > 0) {
            System.out.println("[LIBRARIES] Skipped " + skipped + " libraries for other OS/arch");
        }
        if (toDownload.isEmpty()) return;

        System.out.println("[LIBRARIES] Downloading " + toDownload.size() + " libraries with " + ASSET_DOWNLOAD_THREADS + " threads...");
        ExecutorService pool = Executors.newFixedThreadPool(ASSET_DOWNLOAD_THREADS);
        List<Future<?>> futures = new ArrayList<>();
        for (LibraryDownload lib : toDownload) {
            futures.add(pool.submit(() -> {
                try {
                    Files.createDirectories(lib.dest().getParent());
                    downloadFileWithRetries(lib.url(), lib.dest(), MAX_RETRIES, lib.sha1());
                } catch (Exception e) {
                    System.out.println("[LIBRARIES] Failed: " + lib.url() + " - " + e.getMessage());
                    criticalFailures.add(lib.dest().getFileName() + " (" + e.getMessage() + ")");
                }
            }));
        }
        // Same reasoning as downloadAssets(): bound each future so one hung connection (past
        // connectTimeout, but stalled mid-response) can't stall this whole step forever.
        // cancel(false), never cancel(true) - see the comment in downloadAssets().
        Duration perLibDeadline = HTTP_TIMEOUT.multipliedBy(MAX_RETRIES + 1);
        for (Future<?> f : futures) {
            try {
                f.get(perLibDeadline.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException ignored) {
            } catch (TimeoutException e) {
                f.cancel(false);
            }
        }
        pool.shutdown();
        System.out.println("[LIBRARIES] Downloaded.");
    }

    /** Returns the Minecraft classifier string for the current OS (natives-windows, natives-linux, natives-macos). */
    private static String getNativeClassifier() {
        return LibraryRules.nativeClassifier();
    }

    private void downloadAssets(String assetIndexUrl, String assetIndexId, ProgressCallback progress) throws IOException, InterruptedException {
        Path indexesDir = assetsDir.resolve("indexes");
        Files.createDirectories(indexesDir);
        Path indexFile = indexesDir.resolve(assetIndexId + ".json");
        if (!Files.exists(indexFile)) {
            downloadFile(assetIndexUrl, indexFile);
        }

        JsonObject assetIndex = JsonParser.parseString(Files.readString(indexFile)).getAsJsonObject();
        JsonObject objects = assetIndex.getAsJsonObject("objects");

        // Collect all asset entries to download
        record AssetEntry(String hash, String url, Path dest) {}
        List<AssetEntry> entries = new ArrayList<>();
        for (String key : objects.keySet()) {
            JsonObject obj = objects.getAsJsonObject(key);
            String hash = obj.get("hash").getAsString();
            String url = "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash;
            Path assetPath = assetsDir.resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
            if (!Files.exists(assetPath)) {
                Files.createDirectories(assetPath.getParent());
                entries.add(new AssetEntry(hash, url, assetPath));
            }
        }

        if (entries.isEmpty()) {
            System.out.println("[ASSETS] All assets already downloaded.");
            // Раскладка всё равно нужна: каталог resources/ (или assets/virtual/<id>) мог не
            // появиться при прошлой установке этой же версии — качать было нечего, а файлов по
            // настоящим именам так и нет. См. LegacyAssets.
            materialiseLegacyAssets(assetIndexId, progress);
            return;
        }

        int total = entries.size();
        AtomicInteger completed = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);
        ExecutorService pool = Executors.newFixedThreadPool(ASSET_DOWNLOAD_THREADS);

        System.out.println("[ASSETS] Downloading " + total + " assets with " + ASSET_DOWNLOAD_THREADS + " threads...");

        List<Future<?>> futures = new ArrayList<>();
        for (AssetEntry entry : entries) {
            futures.add(pool.submit(() -> {
                try {
                    // Имя ассета — его sha1: сверяем, обрезанный ответ не ляжет под настоящим именем.
                    downloadFileWithRetries(entry.url, entry.dest, MAX_RETRIES, entry.hash);
                } catch (Exception e) {
                    failed.incrementAndGet();
                }
                int done = completed.incrementAndGet();
                if (done % 100 == 0 || done == total) {
                    System.out.println("[ASSETS] Progress: " + done + "/" + total + " (failed: " + failed.get() + ")");
                    progress.onProgress("Скачивается: ресурсы " + done + "/" + total
                            + (failed.get() > 0 ? " (ошибок: " + failed.get() + ")" : ""));
                }
            }));
        }

        // Wait for all tasks to complete. Each future is bounded - downloadFileWithRetries()
        // already retries/timeouts per-attempt, but a connection that hangs *after* connecting
        // (so HTTP_TIMEOUT's connectTimeout never trips) could otherwise stall this whole loop,
        // and by extension the entire downloadVersion() call, forever with zero visible error.
        //
        // cancel(false), not cancel(true): a running download is already bounded by the connect and
        // read timeouts on its own connection, so interrupting it buys nothing, while cancel(false)
        // still stops tasks that never started - the case that could actually stall this loop.
        // (An earlier version of this comment blamed cancel(true) for the launcher's 100%-CPU bug.
        // That was wrong: the spin came from java.net.http itself and survived the switch to
        // cancel(false). See the HTTP section at the top of this class for what actually fixed it.)
        Duration perAssetDeadline = HTTP_TIMEOUT.multipliedBy(MAX_RETRIES + 1);
        for (Future<?> f : futures) {
            try {
                f.get(perAssetDeadline.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException ignored) {
            } catch (TimeoutException e) {
                f.cancel(false);
                failed.incrementAndGet();
            }
        }
        pool.shutdown();

        // Второй проход по недоставшимся — по одному, без спешки. 14.10.2026 в чистой 1.21.4 один
        // ассет из 4039 (иконка окна) не скачался за обрыв сети, и игра пошла без него; проверка
        // isVersionFullyInstalled смотрит лишь 25 ассетов наугад, так что сама дыра не заросла бы.
        // Обрывы через VPN короткие — к концу основной загрузки связь обычно уже есть.
        if (failed.get() > 0) {
            int recovered = 0;
            for (AssetEntry entry : entries) {
                if (Files.exists(entry.dest)) continue;
                try {
                    downloadFileWithRetries(entry.url, entry.dest, MAX_RETRIES, entry.hash);
                    recovered++;
                } catch (Exception ignored) {
                }
            }
            if (recovered > 0) {
                System.out.println("[ASSETS] Докачано со второго прохода: " + recovered);
                failed.addAndGet(-recovered);
            }
        }

        System.out.println("[ASSETS] Downloaded " + (total - failed.get()) + "/" + total + " assets.");
        if (failed.get() > 0) {
            System.out.println("[ASSETS] Warning: " + failed.get() + " assets failed to download (non-critical).");
            lastAssetFailures = failed.get();
        }

        materialiseLegacyAssets(assetIndexId, progress);
    }

    /**
     * Старые версии ищут ассеты не по хешам, а по именам — раскладываем их туда, где версия их
     * ждёт ({@code resources/} до 1.5.2, {@code assets/virtual/<id>} для 1.6.x).
     *
     * <p>Ошибка раскладки НЕ срывает установку: современных версий это не касается вовсе, а для
     * старой лучше запуститься без части звуков, чем не запуститься. Поэтому сообщение в лог, а
     * не исключение наружу.
     */
    private void materialiseLegacyAssets(String assetIndexId, ProgressCallback progress) {
        try {
            LegacyAssets.Layout layout = LegacyAssets.layoutOf(assetsDir, assetIndexId);
            if (!layout.isLegacy()) return;
            progress.onProgress("Раскладываем ресурсы для старой версии...");
            int copied = LegacyAssets.materialise(gameDir, assetsDir, assetIndexId);
            Path where = LegacyAssets.assetsDirForLaunch(gameDir, assetsDir, assetIndexId);
            System.out.println("[ASSETS] Legacy layout (" + assetIndexId + "): скопировано "
                    + copied + " файлов в " + where);
        } catch (Exception e) {
            System.out.println("[ASSETS] Не удалось разложить ресурсы старой версии: " + e.getMessage());
        }
    }

    /**
     * Downloads a file with automatic retry on failure.
     */
    public void downloadFileWithRetries(String url, Path dest, int maxRetries) throws IOException, InterruptedException {
        downloadFileWithRetries(url, dest, maxRetries, null);
    }

    /** То же, со сверкой sha1: битый ответ (обрыв посреди тела) отбрасывается и качается заново. */
    public void downloadFileWithRetries(String url, Path dest, int maxRetries, String expectedSha1)
            throws IOException, InterruptedException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt < Math.max(1, maxRetries); attempt++) {
            try {
                downloadFile(url, dest, expectedSha1);
                return;
            } catch (IOException e) {
                lastFailure = e;
                Files.deleteIfExists(dest);
                if (attempt == Math.max(1, maxRetries) - 1) throw e;
                Thread.sleep(500L * (attempt + 1)); // exponential backoff
            }
        }
        throw lastFailure != null ? lastFailure : new IOException("Не удалось скачать " + url);
    }

    public void downloadFile(String url, Path destination) throws IOException, InterruptedException {
        downloadFile(url, destination, null);
    }

    /**
     * Скачивает файл и, если известен ожидаемый sha1, сверяет его.
     *
     * <p>Mojang отдаёт {@code sha1} и {@code size} для каждой библиотеки, каждого ассета и самого
     * клиентского jar — раньше не читалось ничего из этого, а признаком «уже скачано» было
     * «файл есть и он непустой». Последствия были ровно те, что видны в присланном логе:
     * страница captive portal или ошибка Cloudflare, отданные с кодом 200, записывались как
     * {@code 26.2.jar} и кешировались навсегда, после чего игра падала с
     * {@code ClassNotFoundException} на главном классе при каждом запуске, а перекачать файл
     * было нельзя — проверки существования считали его готовым.
     *
     * <p>Запись теперь через временный файл с переименованием: обрыв не оставляет обрезанный
     * файл под настоящим именем, который следующая проверка признала бы установленным.
     *
     * @param expectedSha1 ожидаемый sha1 или null, если он неизвестен
     */
    public void downloadFile(String url, Path destination, String expectedSha1)
            throws IOException, InterruptedException {
        if (Files.exists(destination) && Files.size(destination) > 0) {
            // Файл на месте. Если хеш известен — сверяем: именно так битый файл из прошлых
            // версий лаунчера наконец заменяется сам, без ручного удаления.
            if (expectedSha1 == null || expectedSha1.isBlank()) return;
            if (expectedSha1.equalsIgnoreCase(sha1Of(destination))) return;
            System.out.println("[DOWNLOAD] " + destination.getFileName()
                    + ": sha1 не совпал с заявленным, качаю заново");
            Files.deleteIfExists(destination);
        }
        Files.createDirectories(destination.getParent() != null ? destination.getParent() : Path.of("."));

        BinaryResponse response = httpGetBytes(url);

        if (response.statusCode() != 200) {
            throw new IOException("Failed to download: " + url + " (status: " + response.statusCode() + ")");
        }
        if (response.body().length == 0) {
            throw new IOException("Сервер вернул пустой ответ: " + url);
        }

        Path tmp = destination.resolveSibling(destination.getFileName() + ".part");
        Files.write(tmp, response.body(), StandardOpenOption.CREATE,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);

        if (expectedSha1 != null && !expectedSha1.isBlank()) {
            String actual = sha1Of(tmp);
            if (!expectedSha1.equalsIgnoreCase(actual)) {
                Files.deleteIfExists(tmp);
                throw new IOException("Файл скачался повреждённым (" + destination.getFileName()
                        + "): sha1 " + actual + " вместо " + expectedSha1);
            }
        }

        try {
            Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(tmp, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** sha1 файла в нижнем регистре — в таком виде его публикует Mojang. */
    private static String sha1Of(Path file) throws IOException {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            try (java.io.InputStream in = Files.newInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder(40);
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("В этой JVM нет SHA-1", e);
        }
    }

    public boolean isVersionInstalled(String versionId) {
        Path versionJar = versionsDir.resolve(versionId).resolve(versionId + ".jar");
        return Files.exists(versionJar);
    }

    /**
     * Checks whether both the version JAR AND all asset objects are fully downloaded.
     * Samples ~20 evenly-spaced asset hashes and verifies all OS-native classifier JARs
     * (LWJGL dll containers) are present to catch incomplete downloads.
     */
    public boolean isVersionFullyInstalled(String versionId) {
        if (!isVersionInstalled(versionId)) return false;
        try {
            // --- Сам jar игры: размер как у Mojang ---
            // «Файл есть» мало: 14.10.2026 обрезанный 1.21.4.jar давал «Could not find or load main
            // class» при каждом запуске, а «Играть» его не перекачивал — версия считалась готовой.
            // При докачке downloadFile сверит sha1 и заменит файл сам.
            Path json = versionsDir.resolve(versionId).resolve(versionId + ".json");
            if (Files.exists(json)) {
                JsonObject vj = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
                if (vj.has("downloads") && vj.getAsJsonObject("downloads").has("client")) {
                    JsonObject client = vj.getAsJsonObject("downloads").getAsJsonObject("client");
                    Path jar = versionsDir.resolve(versionId).resolve(versionId + ".jar");
                    if (!libraryFileOk(jar, client) && !patchedSizeMatches(jar)) {
                        System.out.println("[DOWNLOAD] " + versionId + ".jar повреждён (размер не совпал) — докачаю");
                        return false;
                    }
                }
            }

            // --- Check assets ---
            String assetIndexId = getAssetIndexId(versionId);
            Path indexesDir = assetsDir.resolve("indexes");
            Path indexFile = indexesDir.resolve(assetIndexId + ".json");
            if (Files.exists(indexFile)) {
                JsonObject assetIndex = JsonParser.parseString(Files.readString(indexFile)).getAsJsonObject();
                if (assetIndex.has("objects")) {
                    JsonObject objects = assetIndex.getAsJsonObject("objects");
                    int total = objects.size();
                    if (total > 0) {
                        int checkCount = Math.min(total, 25);
                        List<String> keys = new ArrayList<>(objects.keySet());
                        Path objectsDir = assetsDir.resolve("objects");
                        for (int i = 0; i < checkCount; i++) {
                            int idx = (int) ((long) i * total / checkCount);
                            JsonObject obj = objects.getAsJsonObject(keys.get(idx));
                            if (obj == null || !obj.has("hash")) continue;
                            String hash = obj.get("hash").getAsString();
                            Path assetFile = objectsDir.resolve(hash.substring(0, 2)).resolve(hash);
                            if (!Files.exists(assetFile)) {
                                System.out.println("[ASSETS] Missing: " + hash + " -> " + assetFile);
                                return false;
                            }
                        }
                    }
                }
            }

            // --- Check native classifier libraries (LWJGL dll/so/dylib containers) ---
            // Old installs skipped these; verify the OS-native JARs exist on disk.
            String nativeClassifier = getNativeClassifier();
            Path versionJson = versionsDir.resolve(versionId).resolve(versionId + ".json");
            if (Files.exists(versionJson)) {
                java.util.Set<String> seen = new java.util.HashSet<>();
                if (!checkNativesInVersion(versionJson, nativeClassifier, seen)) {
                    return false;
                }
            }

            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Recursively walks a version JSON and its inheritsFrom chain,
     *  checking that every library artifact (including OS-native classifier JARs)
     *  is present on disk. */
    private boolean checkNativesInVersion(Path jsonPath, String nativeClassifier,
                                          java.util.Set<String> visited) throws IOException {
        String key = jsonPath.toAbsolutePath().normalize().toString();
        if (!visited.add(key)) return true;
        if (!Files.exists(jsonPath)) return true;

        JsonObject json = JsonParser.parseString(Files.readString(jsonPath)).getAsJsonObject();

        if (json.has("libraries")) {
            for (var el : json.getAsJsonArray("libraries")) {
                if (!el.isJsonObject()) continue;
                JsonObject lib = el.getAsJsonObject();
                // Ровно тот же фильтр, что в downloadLibraries: требовать наличия того, что мы
                // сознательно не качаем, значит сделать проверку невыполнимой навсегда.
                if (!LibraryRules.appliesToCurrentOs(lib) || LibraryRules.isForeignNative(lib, nativeClassifier)) {
                    continue;
                }
                if (!lib.has("downloads")) continue;
                JsonObject downloads = lib.getAsJsonObject("downloads");

                if (downloads.has("artifact")) {
                    JsonObject artifact = downloads.getAsJsonObject("artifact");
                    Path libPath = librariesDir.resolve(artifact.get("path").getAsString());
                    if (!libraryFileOk(libPath, artifact)) {
                        System.out.println("[LIBRARIES] Missing or damaged: " + libPath);
                        return false;
                    }
                }

                if (!downloads.has("classifiers")) continue;
                JsonObject classifiers = downloads.getAsJsonObject("classifiers");
                if (!classifiers.has(nativeClassifier)) continue;

                JsonObject nativeEntry = classifiers.getAsJsonObject(nativeClassifier);
                Path nativeLibPath = librariesDir.resolve(nativeEntry.get("path").getAsString());
                if (!libraryFileOk(nativeLibPath, nativeEntry)) {
                    System.out.println("[NATIVES] Missing or damaged: " + nativeLibPath);
                    return false;
                }
            }
        }

        if (json.has("inheritsFrom")) {
            String parentId = json.get("inheritsFrom").getAsString();
            Path parentJson = versionsDir.resolve(parentId).resolve(parentId + ".json");
            if (!checkNativesInVersion(parentJson, nativeClassifier, visited)) return false;
        }

        return true;
    }

    /**
     * Returns the required Java major version from the version JSON, or 0 if not specified.
     */
    public int getRequiredJavaMajorVersion(String versionId) {
        Path versionJson = versionsDir.resolve(versionId).resolve(versionId + ".json");
        if (!Files.exists(versionJson)) return 0;
        try {
            String content = Files.readString(versionJson);
            int idx = content.indexOf("\"javaVersion\"");
            if (idx < 0) return 0;
            int brace = content.indexOf('{', idx);
            if (brace < 0) return 0;
            int endBrace = content.indexOf('}', brace);
            if (endBrace < 0) return 0;
            String block = content.substring(brace, endBrace + 1);

            int mjIdx = block.indexOf("\"majorVersion\"");
            if (mjIdx < 0) return 0;
            int colon = block.indexOf(':', mjIdx);
            if (colon < 0) return 0;
            int numStart = colon + 1;
            while (numStart < block.length() && Character.isWhitespace(block.charAt(numStart))) numStart++;
            int numEnd = numStart;
            while (numEnd < block.length() && Character.isDigit(block.charAt(numEnd))) numEnd++;
            if (numEnd > numStart) {
                return Integer.parseInt(block.substring(numStart, numEnd));
            }
        } catch (Exception ignored) {}
        return 0;
    }

    /** Символ, который может быть частью номера версии — цифра или точка. */
    private static boolean isVersionChar(char c) {
        return (c >= '0' && c <= '9') || c == '.';
    }

    /**
     * Removes empty directories recursively starting from the given path.
     */
    private void cleanEmptyDirs(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.list(dir)) {
            var subdirs = stream.filter(Files::isDirectory).toList();
            for (Path sub : subdirs) {
                cleanEmptyDirs(sub);
            }
        }
        try (var emptyStream = Files.list(dir)) {
            if (emptyStream.findAny().isEmpty()) {
                Files.deleteIfExists(dir);
            }
        }
    }

}
