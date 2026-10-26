package com.example.launcher.service;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/**
 * Какая ОС под лаунчером и то немногое, что на разных ОС делается по-разному.
 *
 * <p>Появился 20.10.2026 вместе с Linux-сборкой. Проверка «os.name содержит win» уже повторена
 * в десятке классов — их не трогаем, а новый код берёт её отсюда.
 */
public final class Os {

    private static final String NAME = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);

    private Os() {
    }

    public static boolean isWindows() {
        return NAME.contains("win");
    }

    public static boolean isMac() {
        return NAME.contains("mac");
    }

    public static boolean isLinux() {
        return NAME.contains("linux");
    }

    /**
     * Открывает ссылку в браузере по умолчанию.
     *
     * <p>На Linux {@code java.awt.Desktop.browse} работает не везде: ему нужен GNOME-овский
     * libgnome/GTK, а в WSL, на KDE и в «голых» оконных менеджерах он бросает
     * UnsupportedOperationException — проверено 20.10.2026 в WSL Ubuntu 24.04. Поэтому там
     * сразу {@code xdg-open}: его ставит любой рабочий стол, и выбирает он ровно тот браузер,
     * что человек назначил сам.
     */
    /** Открывает папку в проводнике системы (Windows — explorer, macOS — open, Linux — xdg-open). */
    public static void openFolder(java.nio.file.Path dir) throws IOException {
        String path = dir.toAbsolutePath().toString();
        String[] cmd = isWindows() ? new String[]{"explorer.exe", path}
                : isMac() ? new String[]{"open", path} : new String[]{"xdg-open", path};
        new ProcessBuilder(cmd)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    public static void browse(String url) throws IOException {
        if (!isWindows() && !isMac()) {
            try {
                new ProcessBuilder("xdg-open", url)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                return;
            } catch (IOException noXdgOpen) {
                // Ниже — последняя попытка через AWT.
            }
        }
        try {
            java.awt.Desktop.getDesktop().browse(URI.create(url));
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            throw new IOException("Не удалось открыть браузер: " + e.getMessage(), e);
        }
    }
}
