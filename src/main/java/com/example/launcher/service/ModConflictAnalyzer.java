package com.example.launcher.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a crashed game's captured output for a mod-loader dependency/incompatibility failure
 * (the "Incompatible mods found!" screen Fabric shows, or Forge/NeoForge's missing-dependency
 * message) and turns it into structured, actionable suggestions the launcher can offer to fix
 * automatically - rather than leaving the player to read the raw stack trace.
 *
 * <p>It deliberately keys off the loader's OWN suggested solution lines ("Replace mod 'X' with
 * any 0.8.x version", "Install ... of mod 'Y'", "Remove mod 'Z'"), since those already encode
 * exactly what action resolves the conflict.
 */
public class ModConflictAnalyzer {

    public enum Action { REPLACE, INSTALL, REMOVE }

    public static class Suggestion {
        public final Action action;
        public final String modId;          // e.g. "sodium" - usable as a Modrinth slug
        public final String modTitle;       // e.g. "Sodium"
        public final String versionPrefix;  // e.g. "0.8" from "any 0.8.x version", or null
        public final String detail;         // the raw human-readable line, for display

        public Suggestion(Action action, String modId, String modTitle, String versionPrefix, String detail) {
            this.action = action;
            this.modId = modId;
            this.modTitle = modTitle;
            this.versionPrefix = versionPrefix;
            this.detail = detail;
        }
    }

    // "Replace mod 'Sodium' (sodium) 0.9.0-beta.1+mc26.1.2 with any 0.8.x version."
    private static final Pattern REPLACE = Pattern.compile(
            "Replace mod '([^']+)' \\(([^)]+)\\) (\\S+) with (.+?)\\.?\\s*$");
    // "Install any 0.8.x version of mod 'sodium' (sodium)." / "Add mod 'sodium' (sodium) ..."
    private static final Pattern INSTALL = Pattern.compile(
            "(?:Install|Add) (.+?) of mod '([^']+)' \\(([^)]+)\\)");
    // "Remove mod 'Iris' (iris) 1.10.9+mc26.1.1."
    private static final Pattern REMOVE = Pattern.compile(
            "Remove mod '([^']+)' \\(([^)]+)\\)");
    // Forge/NeoForge: "Mod ID: 'iris', Requires: 'sodium' ... " style missing-dep lines vary a lot
    // by version; Fabric is handled precisely, Forge is best-effort via the generic "requires"
    // detail line below.
    private static final Pattern REQUIRES = Pattern.compile(
            "requires (.+?) (?:version )?of mod '([^']+)' \\(([^)]+)\\)");

    private static final Pattern FIRST_VERSION = Pattern.compile("(\\d+(?:\\.\\d+)+)");

    /** Cheap pre-check so the launcher only runs the full parse on output that actually looks
     *  like a dependency failure (avoids false positives on ordinary crashes). */
    public static boolean looksLikeModConflict(String log) {
        if (log == null) return false;
        return log.contains("Incompatible mods found")
                || log.contains("incompatible mod set")
                || log.contains("A potential solution has been determined")
                || log.contains("Mod resolution encountered")
                || log.contains("Missing or unsupported mandatory dependencies");
    }

    public static List<Suggestion> analyze(String log) {
        // Keyed by action+modId so the same fix isn't listed twice (the loader often repeats it
        // across its "potential solution" and "more details" sections).
        Map<String, Suggestion> out = new LinkedHashMap<>();
        if (log == null) return new ArrayList<>();

        for (String line : log.split("\\R")) {
            String trimmed = line.trim();

            Matcher m = REPLACE.matcher(trimmed);
            if (m.find()) {
                add(out, new Suggestion(Action.REPLACE, m.group(2), m.group(1),
                        extractPrefix(m.group(4)), trimmed));
                continue;
            }
            m = INSTALL.matcher(trimmed);
            if (m.find()) {
                add(out, new Suggestion(Action.INSTALL, m.group(3), m.group(2),
                        extractPrefix(m.group(1)), trimmed));
                continue;
            }
            m = REMOVE.matcher(trimmed);
            if (m.find() && trimmed.startsWith("Remove mod")) {
                add(out, new Suggestion(Action.REMOVE, m.group(2), m.group(1), null, trimmed));
                continue;
            }
            // Fallback for loaders that only print the "X requires Y" detail without an explicit
            // solution line - treat the required mod as something to (re)install.
            m = REQUIRES.matcher(trimmed);
            if (m.find()) {
                add(out, new Suggestion(Action.REPLACE, m.group(3), m.group(2),
                        extractPrefix(m.group(1)), trimmed));
            }
        }
        return new ArrayList<>(out.values());
    }

    private static void add(Map<String, Suggestion> out, Suggestion s) {
        // A REPLACE/REMOVE for a mod is more specific than a fallback REQUIRES-derived one; keep
        // the first (the loader lists its own "potential solution" before the details section).
        out.putIfAbsent(s.action + ":" + s.modId, s);
    }

    /** "any 0.8.x version" / "0.8.x" / ">=0.8.0 <0.9.0" -> "0.8"; "any version" -> null. */
    private static String extractPrefix(String rangeText) {
        if (rangeText == null) return null;
        Matcher m = FIRST_VERSION.matcher(rangeText);
        if (!m.find()) return null;
        String full = m.group(1);
        // Keep major.minor as the constraint line ("0.8.x" -> "0.8"); a bare "0.8" stays "0.8".
        String[] parts = full.split("\\.");
        return parts.length >= 2 ? parts[0] + "." + parts[1] : parts[0];
    }
}
