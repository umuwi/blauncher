package com.example.launcher.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Relaunches the launcher, so a setting that can only be applied at start-up (the
 * interface language, whose ~89 labels are resolved once by FXMLLoader) takes effect
 * without the player having to close and reopen the launcher by hand.
 *
 * <p>The new instance is started through {@code cmd /c ping … & start "" <exe>}: the
 * two-second pause is not decoration. BLauncher holds a single-instance lock in its
 * game directory, so a successor that starts immediately would find the lock still
 * taken and quit with "already running" - the pause gives this process time to exit
 * and release it.
 *
 * <p>The command is built by {@link #command(String)} rather than inline so that the
 * shape can be asserted in a test without spawning anything.
 */
public final class AppRestart {

    /** Pause before the successor starts, in seconds (see the class javadoc). */
    private static final int HANDOVER_SECONDS = 2;

    private AppRestart() {}

    /** The launcher's own executable, or null when it cannot be determined. */
    public static Path resolveSelf() {
        // Set by jpackage inside an app image, which is how this launcher ships.
        String packaged = System.getProperty("jpackage.app-path");
        if (packaged != null && !packaged.isBlank() && Files.isExecutable(Path.of(packaged))) {
            return Path.of(packaged);
        }
        // A plain `java -jar` run: the command that started this JVM is java.exe, and
        // relaunching *that* would start an empty JVM rather than the launcher, so it is
        // only accepted when it is not a java binary.
        String command = ProcessHandle.current().info().command().orElse(null);
        if (command != null && !command.isBlank()) {
            Path candidate = Path.of(command);
            String name = candidate.getFileName().toString().toLowerCase(Locale.ROOT);
            boolean isJava = name.startsWith("java") || name.startsWith("javaw");
            if (!isJava && Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** True when a restart can actually be performed. */
    public static boolean supported() {
        return (isWindows() || com.example.launcher.service.Os.isLinux()) && resolveSelf() != null;
    }

    /**
     * The argv of the relaunch: a detached shell that waits, then starts the launcher.
     * Exposed for tests; nothing here touches the running process.
     */
    public static List<String> command(String executable) {
        return command(executable, HANDOVER_SECONDS);
    }

    /** То же с другой паузой — см. {@link #spawnSuccessor(int)}. */
    public static List<String> command(String executable, int seconds) {
        List<String> argv = new ArrayList<>();
        if (!isWindows()) {
            // Linux (с 20.10.2026): та же пауза, путь к лаунчеру — отдельным аргументом ($0),
            // чтобы пробелы и кавычки в нём не разбирала оболочка. Рабочая папка наследуется —
            // это папка данных, куда её поставил скрипт blauncher.
            argv.add("sh");
            argv.add("-c");
            argv.add("sleep " + seconds + "; exec \"$0\"");
            argv.add(executable);
            return argv;
        }
        argv.add("cmd");
        argv.add("/c");
        argv.add("ping -n " + (seconds + 1) + " 127.0.0.1 >nul & start \"\" \"" + executable + "\"");
        return argv;
    }

    /**
     * Starts the successor and reports whether it was started. The caller is responsible
     * for exiting this process afterwards - this method deliberately does not, so that a
     * failed restart leaves a working launcher on screen instead of nothing.
     */
    public static boolean spawnSuccessor() {
        return spawnSuccessor(HANDOVER_SECONDS);
    }

    /** С паузой в {@code seconds} секунд — дольше обычной, когда ждём не только своего выхода. */
    public static boolean spawnSuccessor(int seconds) {
        Path self = resolveSelf();
        if (self == null || !supported()) return false;
        try {
            // Лок единственного экземпляра отдаём ДО запуска преемника. Он стартует с задержкой,
            // но полагаться на то, что мы успеем завершиться первыми, нельзя: при зависшем
            // сетевом чтении эта JVM живёт дольше задержки, преемник видит лок занятым и выходит
            // с «BLauncher уже запущен» — человек остаётся вообще без лаунчера.
            com.example.launcher.MainApp.releaseSingleInstanceLock();
            ProcessBuilder builder = new ProcessBuilder(command(self.toString(), seconds));
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.start();
            return true;
        } catch (Exception e) {
            System.out.println("[restart] не удалось запустить новую копию: " + e);
            return false;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
