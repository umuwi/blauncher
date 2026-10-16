package com.example.launcher.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import com.example.launcher.service.GameLaunchService.GameOutputCallback;

/**
 * Downloads and runs the playit.gg agent - a free tunneling service that exposes a local
 * Minecraft server to the internet without any router/port-forward configuration. See the
 * "Server" tab's instructional text for the (unavoidably manual, one-time) steps to actually
 * claim the agent and create a tunnel on playit.gg's website.
 *
 * <p><b>Important, confirmed by testing the real binary:</b> despite GitHub publishing it as a
 * single .exe asset, playit's official Windows build is NOT a simple portable child process the
 * way the Linux "playit-cli-*" builds are. The first time it's run, it performs its own
 * self-install: it copies itself into {@code C:\Program Files\playit_gg\}, registers a
 * {@code playitd} Windows service running as LocalSystem, and provisions the secret into that
 * service over IPC. The service then keeps running independently forever, surviving past the
 * process we spawned it from (and past BLauncher closing). This is playit's own behavior on
 * Windows - not something BLauncher does deliberately - but it means the actual ground truth
 * for "is it running" / "stop it" is the Windows service, not our spawned wrapper process, and
 * starting it a second time by re-running our exe just collides ("Another instance is already
 * running") instead of doing anything useful once the service already exists.
 */
public class PlayitService {

    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/playit-cloud/playit-agent/releases/latest";
    private static final String ASSET_NAME = "playit-windows-x86_64-signed.exe";
    private static final String SERVICE_NAME = "playitd";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);

    // GitHub's asset download URLs 302-redirect to a CDN URL; Http follows redirects itself
    // (including across hosts), so the download gets the actual file and not the 302 body.
    private final Path gameDir;

    private Process playitProcess;
    private Thread outputThread;

    public PlayitService(Path gameDir) {
        this.gameDir = gameDir;
    }

    public Path agentExe() {
        return gameDir.resolve("playit").resolve("playit.exe");
    }

    public boolean isInstalled() {
        return Files.exists(agentExe());
    }

    // --- Secret key (our own storage; NOT playit's own undocumented playit.toml format) ---
    // The Windows agent build has no interactive "print a claim URL" flow - that only exists
    // on the Linux "playit-cli-*" builds (confirmed by testing the real Windows binary: with no
    // args it just waits forever for a GUI companion app to hand it a secret over IPC). The
    // Windows daemon DOES accept a secret directly via "--secret <key>" though (verified: a
    // garbage value is cleanly rejected with "The secret is not valid..." rather than hanging).
    // So BLauncher owns persistence itself - save whatever key the user pastes from their
    // playit.gg account page here, and pass it via --secret on every start.

    private Path secretFile() {
        return gameDir.resolve("playit").resolve("secret.txt");
    }

    public boolean hasSecret() {
        return Files.exists(secretFile());
    }

    private String loadSecret() throws IOException {
        return Files.readString(secretFile()).trim();
    }

    /** True if our spawned bootstrap process is alive, OR (the actual ground truth once the
     *  one-time self-install has happened - see class Javadoc) the real playitd service is. */
    public boolean isRunning() {
        if (playitProcess != null && playitProcess.isAlive()) return true;
        try {
            return isServiceRunning();
        } catch (Exception ex) {
            return false;
        }
    }

    private boolean isServiceRegistered() throws IOException, InterruptedException {
        return runCommand("sc", "query", SERVICE_NAME) == 0;
    }

    private boolean isServiceRunning() throws IOException, InterruptedException {
        String output = runCommandCapture("sc", "query", SERVICE_NAME);
        return output != null && output.contains("RUNNING");
    }

    private int runCommand(String... command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes(); // drain so the child never blocks on a full output pipe
        return p.waitFor();
    }

    /** "net start"/"net stop" on a Windows service need administrator rights, but BLauncher
     *  itself runs as a normal user (see BLauncher.vbs) - without this, every "net ..." call
     *  here just fails with "Access is denied" (exit 5) and the button looks like it does
     *  nothing. Routes the single privileged command through a UAC prompt instead of requiring
     *  the whole launcher to run elevated. */
    private int runElevatedNetCommand(String action) throws IOException, InterruptedException {
        String psCommand = "$p = Start-Process -FilePath 'net' -ArgumentList '" + action + "','" + SERVICE_NAME
                + "' -Verb RunAs -WindowStyle Hidden -PassThru -Wait; exit $p.ExitCode";
        Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", psCommand)
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        return p.waitFor();
    }

    private String runCommandCapture(String... command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        p.waitFor();
        return output;
    }

    /** Downloads the latest playit.gg agent build if missing - just gets the .exe onto disk;
     *  see the class Javadoc for what happens the first time it's actually run. */
    public void download(GameOutputCallback callback) throws IOException, InterruptedException {
        if (isInstalled()) {
            callback.onOutput("[PLAYIT] Already downloaded.");
            return;
        }
        callback.onOutput("[PLAYIT] Fetching latest release info...");
        Http.Response response = Http.get(LATEST_RELEASE_API,
                Map.of("Accept", "application/vnd.github+json"), HTTP_TIMEOUT);
        if (!response.ok()) {
            throw new IOException("Failed to fetch playit.gg release info: HTTP " + response.status());
        }

        JsonObject release = JsonParser.parseString(response.text()).getAsJsonObject();
        JsonArray assets = release.getAsJsonArray("assets");
        String downloadUrl = null;
        for (var el : assets) {
            JsonObject asset = el.getAsJsonObject();
            if (ASSET_NAME.equals(asset.get("name").getAsString())) {
                downloadUrl = asset.get("browser_download_url").getAsString();
                break;
            }
        }
        if (downloadUrl == null) {
            throw new IOException("playit.gg release is missing the expected \"" + ASSET_NAME + "\" asset.");
        }

        callback.onOutput("[PLAYIT] Downloading agent...");
        Path dest = agentExe();
        Files.createDirectories(dest.getParent());
        // GitHub serves the asset itself from a different host behind a redirect, which Http follows.
        long written = Http.download(downloadUrl, dest, Map.of(), Duration.ofMinutes(2));
        if (written < 0) {
            throw new IOException("Failed to download playit.gg agent from " + downloadUrl);
        }
        callback.onOutput("[PLAYIT] Downloaded to " + dest);
    }

    /** Starts playit.gg. The caller must make sure {@link #hasSecret()} is true first for a
     *  first-time start (the UI prompts for one via a drawer otherwise) - starting with no
     *  secret would just sit forever waiting for a GUI companion app over IPC that BLauncher
     *  doesn't provide, with no readable feedback that anything is wrong. */
    public void start(GameOutputCallback callback) throws IOException, InterruptedException {
        if (isRunning()) {
            callback.onOutput("[PLAYIT] Already running.");
            return;
        }

        if (isServiceRegistered()) {
            // Already bootstrapped on this PC in an earlier session - the playitd service keeps
            // its provisioned secret itself, so just start the service rather than re-running
            // our exe (which would only collide: "Another instance is already running").
            callback.onOutput("[PLAYIT] Starting the installed playitd service (admin approval required)...");
            int exit = runElevatedNetCommand("start");
            if (exit != 0) {
                throw new IOException("Could not start the playitd service (exit code " + exit
                        + "). If a UAC prompt appeared, make sure to click \"Yes\" - otherwise open "
                        + "Services.msc and starting \"playitd\" manually.");
            }
            callback.onOutput("[PLAYIT] Service started.");
            return;
        }

        if (!isInstalled()) {
            throw new IOException("playit.gg agent isn't downloaded yet.");
        }
        if (!hasSecret()) {
            throw new IOException("No playit.gg secret key saved yet.");
        }

        // First time on this PC: running our downloaded exe performs playit's own one-time
        // self-install (registers the playitd service, provisions this secret into it) - see
        // the class Javadoc. From then on, isServiceRegistered() is true and the branch above
        // handles every subsequent start.
        ProcessBuilder pb = new ProcessBuilder(agentExe().toAbsolutePath().toString(), "--secret", loadSecret());
        pb.directory(agentExe().getParent().toFile());
        pb.redirectErrorStream(true);
        playitProcess = pb.start();

        outputThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(playitProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    callback.onOutput("[PLAYIT] " + line);
                }
            } catch (IOException e) {
                callback.onError("Error reading playit.gg output: " + e.getMessage());
            }
        });
        outputThread.setDaemon(true);
        outputThread.start();

        new Thread(() -> {
            try {
                int exitCode = playitProcess.waitFor();
                callback.onOutput("[PLAYIT] Agent closed with exit code: " + exitCode);
            } catch (InterruptedException e) {
                callback.onError("playit.gg process interrupted: " + e.getMessage());
            } finally {
                playitProcess = null;
            }
        }).start();
    }

    /** Stops both our spawned bootstrap process (if it's still the one running) and the real
     *  playitd Windows service (the actual long-lived thing once it's been installed once). */
    public void stop() {
        if (playitProcess != null && playitProcess.isAlive()) {
            playitProcess.destroy();
        }
        try {
            if (isServiceRegistered()) {
                runElevatedNetCommand("stop");
            }
        } catch (Exception ignored) {
            // Best-effort - runElevatedNetCommand already prompts for admin approval; if the user
            // denies the UAC prompt there's nothing more we can do here.
        }
    }
}
