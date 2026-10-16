package com.example.launcher.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Plain result types returned by {@link AgentClient}. Kept as simple classes
 * (not records) so this compiles under Java 11+ regardless of which Java
 * version the rest of the launcher targets.
 *
 * These mirror the response shapes documented in AGENT_API.md — if that
 * document changes, these classes (and AgentClient's parsing) need to change
 * together with it.
 */
public final class AgentModels {

    private AgentModels() {
    }

    /** GET /ping */
    public static final class PingResult {
        public final String agent;
        public final String time;

        public PingResult(String agent, String time) {
            this.agent = agent;
            this.time = time;
        }

        @Override
        public String toString() {
            return "PingResult{agent='" + agent + "', time='" + time + "'}";
        }
    }

    /** POST /server/install (the 202 response, not the polled status) */
    public static final class InstallStartedResult {
        public final String message;
        public final String pollUrl;

        public InstallStartedResult(String message, String pollUrl) {
            this.message = message;
            this.pollUrl = pollUrl;
        }
    }

    /** GET /server/install/status */
    public static final class InstallStatus {

        public enum Status { IDLE, INSTALLING, DONE, ERROR }

        public final Status status;
        /** One of "resolving" / "downloading" / "configuring" / "done", or null. */
        public final String stage;
        public final int pct;
        /** Non-null only when status == ERROR. */
        public final String error;

        public InstallStatus(Status status, String stage, int pct, String error) {
            this.status = status;
            this.stage = stage;
            this.pct = pct;
            this.error = error;
        }

        public boolean isFinished() {
            return status == Status.DONE || status == Status.ERROR;
        }

        @Override
        public String toString() {
            return "InstallStatus{status=" + status + ", stage='" + stage + "', pct=" + pct
                    + ", error='" + error + "'}";
        }
    }

    /** GET /server/plugins/install/status — see AgentClient's startPluginInstall/awaitPluginInstall. */
    public static final class PluginInstallStatus {

        public enum Status { IDLE, DOWNLOADING, VERIFYING, DONE, ERROR }

        public final Status status;
        public final int pct;
        public final String fileName;
        /** Non-null only when status == ERROR. */
        public final String error;

        public PluginInstallStatus(Status status, int pct, String fileName, String error) {
            this.status = status;
            this.pct = pct;
            this.fileName = fileName;
            this.error = error;
        }

        public boolean isFinished() {
            return status == Status.DONE || status == Status.ERROR;
        }

        @Override
        public String toString() {
            return "PluginInstallStatus{status=" + status + ", pct=" + pct
                    + ", fileName='" + fileName + "', error='" + error + "'}";
        }
    }

    /** POST /server/start */
    public static final class StartResult {
        public final boolean started;

        public StartResult(boolean started) {
            this.started = started;
        }
    }

    /** POST /server/stop */
    public static final class StopResult {
        public final boolean stopped;
        /** True if the server didn't exit within the agent's grace period and had to be killed. */
        public final boolean forced;
        /** Present (e.g. "not_running") only when stopped == false. */
        public final String reason;

        public StopResult(boolean stopped, boolean forced, String reason) {
            this.stopped = stopped;
            this.forced = forced;
            this.reason = reason;
        }
    }

    /** GET /server/status */
    public static final class ServerStatus {
        public final boolean running;
        /** What's ACTUALLY installed on the server, per the agent's own record — null/null if
         *  nothing's been installed through this agent yet (see AGENT_API.md). Callers should
         *  prefer these over a locally-cached ServerEntry.version/loader when both are known,
         *  since this reflects reality even if the server was reinstalled some other way. */
        public final String loader;
        public final String version;

        public ServerStatus(boolean running, String loader, String version) {
            this.running = running;
            this.loader = loader;
            this.version = version;
        }
    }

    /** One line from GET /server/console: [time, level, text]. */
    public static final class ConsoleLine {

        public enum Level { INFO, SERVER, ERROR, CMD, UNKNOWN }

        public final String time;
        public final Level level;
        public final String text;

        public ConsoleLine(String time, Level level, String text) {
            this.time = time;
            this.level = level;
            this.text = text;
        }

        static Level parseLevel(String raw) {
            if (raw == null) return Level.UNKNOWN;
            switch (raw) {
                case "info": return Level.INFO;
                case "server": return Level.SERVER;
                case "error": return Level.ERROR;
                case "cmd": return Level.CMD;
                default: return Level.UNKNOWN;
            }
        }

        @Override
        public String toString() {
            return "[" + time + "] (" + level + ") " + text;
        }
    }

    /** GET /server/console — the full buffer, not a delta (see AGENT_API.md). */
    public static final class ConsoleSnapshot {
        public final List<ConsoleLine> lines;

        public ConsoleSnapshot(List<ConsoleLine> lines) {
            this.lines = lines == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(lines));
        }
    }

    /**
     * One entry from GET /versions — a non-AGENT_API.md-original, additive endpoint that lists
     * every Minecraft version Mojang publishes (releases AND snapshots), so the launcher can
     * offer snapshots for a VPS install the same way it already does for local play.
     */
    public static final class VersionEntry {
        /** e.g. "1.21.1" or "25w14a" (a snapshot id). */
        public final String id;
        /** "release" | "snapshot" | "old_beta" | "old_alpha". */
        public final String type;

        public VersionEntry(String id, String type) {
            this.id = id;
            this.type = type;
        }

        public boolean isSnapshot() {
            return "snapshot".equals(type);
        }

        @Override
        public String toString() {
            return id + (isSnapshot() ? " (snapshot)" : "");
        }
    }

    /** GET /versions result — see VersionEntry. */
    public static final class VersionsResult {
        public final List<VersionEntry> versions;

        public VersionsResult(List<VersionEntry> versions) {
            this.versions = versions == null
                    ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(versions));
        }
    }

    /**
     * POST /license/validate — not in the original AGENT_API.md contract at the time it was
     * written, added so brand-plugin activation is proxied through the VPS agent instead of
     * called directly from the launcher (see AgentClient#validateLicense and
     * PluginsPanelController#onActivateBrandPluginKey). The license server binds a key to
     * whichever IP the validate request physically came from — calling it from the launcher
     * bound keys to the PLAYER's own network, not the Minecraft server's; proxying through the
     * agent means the request originates from the VPS itself, which is what "this server" is
     * actually supposed to mean.
     */
    public static final class LicenseValidateResult {
        public final boolean valid;
        /** Which catalog plugin this key activated — null if rejected or the agent/backend didn't say. */
        public final String pluginId;
        /** Human-readable message from the license server, shown to the player as-is. */
        public final String message;

        public LicenseValidateResult(boolean valid, String pluginId, String message) {
            this.valid = valid;
            this.pluginId = pluginId;
            this.message = message;
        }
    }
}
