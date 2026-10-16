package com.example.launcher.service;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Watches this JVM's own CPU use and, when it stays pathologically high, writes a thread dump with
 * per-thread CPU shares to logs/blauncher-highcpu-&lt;timestamp&gt;.log.
 *
 * <p>This exists because a "the launcher eats 100% CPU" report is otherwise undiagnosable after the
 * fact: the in-app console dies with the window, jpackage's GUI build has no stdout to capture, and
 * the natural reaction - killing the process in Task Manager - destroys the only evidence there was.
 * A dump naming the exact threads and stacks turns that report into a fixable bug.
 *
 * <p>The watchdog itself must never be part of the problem: it wakes once every
 * {@value #SAMPLE_INTERVAL_MS}ms on a min-priority daemon thread, does O(threads) arithmetic, and
 * only walks stacks when it has already decided to write a dump.
 */
public final class CpuWatchdog {

    private static final long SAMPLE_INTERVAL_MS = 15_000;
    /** Share of all cores, above which a sample counts as "pathological". */
    private static final double BUSY_THRESHOLD_PCT = 60.0;
    /** How many consecutive bad samples before dumping - one busy minute is normal during a download. */
    private static final int CONSECUTIVE_SAMPLES = 4;
    /** Never write dumps closer together than this, however long the burn lasts. */
    private static final long MIN_DUMP_INTERVAL_MS = 10 * 60 * 1000L;
    private static final int MAX_DUMP_FILES = 5;
    private static final int MAX_THREADS_IN_DUMP = 25;
    private static final int MAX_FRAMES_PER_THREAD = 24;

    private final Path logsDir;
    private final Consumer<String> log;
    private final ThreadMXBean threads = ManagementFactory.getThreadMXBean();
    private final int cores = Runtime.getRuntime().availableProcessors();

    private Map<Long, Long> previous = Map.of();
    private int consecutiveBad = 0;
    private long lastDumpAt = 0;

    private CpuWatchdog(Path logsDir, Consumer<String> log) {
        this.logsDir = logsDir;
        this.log = log != null ? log : msg -> {};
        // Per-thread CPU accounting is on by default on HotSpot but is not required to be - and if
        // it is off, getThreadCpuTime() returns -1 for every thread, which would leave the watchdog
        // silently measuring 0% forever instead of doing its one job.
        try {
            if (threads.isThreadCpuTimeSupported() && !threads.isThreadCpuTimeEnabled()) {
                threads.setThreadCpuTimeEnabled(true);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Starts the watchdog on a daemon thread. Never throws - diagnostics must not break startup. */
    public static void start(Path logsDir, Consumer<String> log) {
        try {
            CpuWatchdog watchdog = new CpuWatchdog(logsDir, log);
            Thread t = new Thread(watchdog::run, "blauncher-cpu-watchdog");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            t.start();
        } catch (Throwable ignored) {
        }
    }

    private void run() {
        previous = sample();
        while (true) {
            try {
                Thread.sleep(SAMPLE_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                tick();
            } catch (Throwable ignored) {
                // A watchdog that can die is not a watchdog.
            }
        }
    }

    private void tick() {
        Map<Long, Long> current = sample();
        Map<Long, Long> deltas = new HashMap<>();
        long totalNanos = 0;
        for (Map.Entry<Long, Long> e : current.entrySet()) {
            Long before = previous.get(e.getKey());
            long d = before == null ? 0 : e.getValue() - before;
            if (d > 0) {
                deltas.put(e.getKey(), d);
                totalNanos += d;
            }
        }
        previous = current;

        double busyPct = totalNanos / 1e6 / SAMPLE_INTERVAL_MS / cores * 100.0;
        if (busyPct < BUSY_THRESHOLD_PCT) {
            consecutiveBad = 0;
            return;
        }

        consecutiveBad++;
        if (consecutiveBad < CONSECUTIVE_SAMPLES) return;

        long now = System.currentTimeMillis();
        if (now - lastDumpAt < MIN_DUMP_INTERVAL_MS) return;
        lastDumpAt = now;
        consecutiveBad = 0;

        writeDump(busyPct, deltas);
    }

    private Map<Long, Long> sample() {
        Map<Long, Long> m = new HashMap<>();
        long[] ids = threads.getAllThreadIds();
        for (long id : ids) {
            long cpu = threads.getThreadCpuTime(id);
            if (cpu > 0) m.put(id, cpu);
        }
        return m;
    }

    private void writeDump(double busyPct, Map<Long, Long> deltas) {
        try {
            Files.createDirectories(logsDir);
            pruneOldDumps();

            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            Path file = logsDir.resolve("blauncher-highcpu-" + stamp + ".log");

            List<Map.Entry<Long, Long>> top = new ArrayList<>(deltas.entrySet());
            top.sort(Map.Entry.<Long, Long>comparingByValue().reversed());

            StringBuilder sb = new StringBuilder();
            sb.append("=== BLauncher high-CPU dump ").append(stamp).append(" ===\n");
            sb.append(String.format("JVM used %.1f%% of %d cores over the last %ds, for at least %ds.%n",
                    busyPct, cores, SAMPLE_INTERVAL_MS / 1000,
                    SAMPLE_INTERVAL_MS / 1000 * CONSECUTIVE_SAMPLES));
            sb.append("Live threads: ").append(threads.getThreadCount()).append('\n');
            sb.append("Threads below are sorted by CPU burned during that last sample.\n\n");

            int shown = 0;
            for (Map.Entry<Long, Long> e : top) {
                if (shown++ >= MAX_THREADS_IN_DUMP) break;
                ThreadInfo info = threads.getThreadInfo(e.getKey(), MAX_FRAMES_PER_THREAD);
                if (info == null) continue;
                double sharePct = e.getValue() / 1e6 / SAMPLE_INTERVAL_MS * 100.0;
                sb.append(String.format("[%5.1f%% of one core] \"%s\" %s%n",
                        sharePct, info.getThreadName(), info.getThreadState()));
                if (info.getLockName() != null) {
                    sb.append("    waiting on ").append(info.getLockName()).append('\n');
                }
                for (StackTraceElement frame : info.getStackTrace()) {
                    sb.append("    at ").append(frame).append('\n');
                }
                sb.append('\n');
            }

            Files.writeString(file, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            log.accept("[WATCHDOG] Высокая загрузка процессора (" + Math.round(busyPct)
                    + "% от " + cores + " ядер). Диагностика сохранена: " + file.toAbsolutePath());
        } catch (Throwable ignored) {
        }
    }

    private void pruneOldDumps() {
        try (Stream<Path> files = Files.list(logsDir)) {
            List<Path> existing = files
                    .filter(p -> p.getFileName().toString().startsWith("blauncher-highcpu-"))
                    .sorted(Comparator.comparing(p -> {
                        try { return Files.getLastModifiedTime(p); }
                        catch (IOException e) { return java.nio.file.attribute.FileTime.fromMillis(0); }
                    }))
                    .toList();
            for (int i = 0; i < existing.size() - (MAX_DUMP_FILES - 1); i++) {
                try { Files.deleteIfExists(existing.get(i)); } catch (IOException ignored) {}
            }
        } catch (IOException ignored) {
        }
    }
}
