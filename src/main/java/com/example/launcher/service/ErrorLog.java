package com.example.launcher.service;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Журнал ошибок лаунчера (05.11.2026, просьба владельца). До него исключение в обработчике кнопки или в
 * фоновом потоке уходило только в консоль, которой у игрока нет: кнопка молча не работала, а в статистике
 * было пусто. Теперь полный текст лежит на компьютере в logs/blauncher-errors.log, а на сайт уходит только
 * класс ошибки и место в нашем коде: в тексте бывают пути с именем пользователя Windows.
 */
public final class ErrorLog {

    private static final long MAX_BYTES = 512 * 1024;
    /** Одно и то же из цикла не должно завалить журнал и статистику. */
    private static final int MAX_SAME = 3;
    private static final int MAX_SENT = 30;

    private static volatile Path file;
    private static volatile BiConsumer<String, String> stat;
    private static final Map<String, Integer> seen = new ConcurrentHashMap<>();
    private static int sent;

    private ErrorLog() {
    }

    /** Ставит перехват необработанных ошибок всех потоков, в том числе потока интерфейса. */
    public static void install(Path logsDir) {
        file = logsDir.resolve("blauncher-errors.log");
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            System.err.println("[ERROR] " + thread.getName() + ": " + error);
            error.printStackTrace();
            record("uncaught", error);
        });
    }

    /** Куда слать отметки; до этого вызова ошибки пишутся только в файл. */
    public static void setStat(BiConsumer<String, String> sender) {
        stat = sender;
    }

    /** Записывает ошибку и шлёт отметку app_error; возвращает короткую метку « exc=… at=…» для своих отметок. */
    public static String record(String area, Throwable error) {
        String tag = tag(error);
        int times = seen.merge(area + tag, 1, Integer::sum);
        if (times <= MAX_SAME) write(area, error);
        BiConsumer<String, String> s = stat;
        if (times == 1 && s != null && sent < MAX_SENT) {
            sent++;
            s.accept("app_error", area + tag);
        }
        return tag;
    }

    /** « exc=NullPointerException at=MainController.onLaunch:123» — первый кадр нашего кода, без путей и текста. */
    public static String tag(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String at = "";
        for (Throwable t = error; t != null && at.isEmpty(); t = t.getCause() == t ? null : t.getCause()) {
            for (StackTraceElement e : t.getStackTrace()) {
                if (e.getClassName().startsWith("com.example.launcher")) {
                    String cls = e.getClassName().substring(e.getClassName().lastIndexOf('.') + 1);
                    int inner = cls.indexOf('$');
                    if (inner > 0) cls = cls.substring(0, inner);
                    at = cls + "." + e.getMethodName().replace("lambda$", "") + ":" + e.getLineNumber();
                    break;
                }
            }
        }
        return " exc=" + root.getClass().getSimpleName() + (at.isEmpty() ? "" : " at=" + at.replaceAll("[^A-Za-z0-9_.:]", "_"));
    }

    private static synchronized void write(String area, Throwable error) {
        Path f = file;
        if (f == null) return;
        try {
            Files.createDirectories(f.getParent());
            if (Files.isRegularFile(f) && Files.size(f) > MAX_BYTES) {
                Files.move(f, f.resolveSibling("blauncher-errors.old.log"), StandardCopyOption.REPLACE_EXISTING);
            }
            StringWriter text = new StringWriter();
            text.append(LocalDateTime.now().toString()).append(" [").append(area).append("] ");
            error.printStackTrace(new PrintWriter(text, true));
            Files.writeString(f, text + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException ignored) {
            // журнал ошибок не должен сам стать ошибкой
        }
    }
}
