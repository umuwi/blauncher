package com.example.launcher.ui;

import java.awt.Image;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

/**
 * Всплывающие уведомления Windows (11.10.2026): «сервер упал», «на сервер зашёл Alex» — когда окно
 * лаунчера свёрнуто или не в фокусе. Через значок в трее AWT: другого способа показать системное
 * уведомление из JavaFX без сторонних библиотек нет.
 *
 * <p>Значок в трее создаётся при первом уведомлении, а не при запуске: кто ни разу не держал сервер
 * открытым, лишнего значка не увидит. Одинаковые уведомления не чаще раза в 20 секунд — сервер,
 * который падает и поднимается по кругу, не должен заваливать экран.
 */
public final class DesktopNotifier {

    private static TrayIcon icon;
    private static boolean unavailable;
    private static final Map<String, Long> lastShown = new HashMap<>();

    private DesktopNotifier() {}

    public static synchronized void show(String title, String text, boolean error) {
        if (unavailable) return;
        long now = System.currentTimeMillis();
        String key = title + "|" + text;
        Long prev = lastShown.get(key);
        if (prev != null && now - prev < 20_000) return;
        lastShown.put(key, now);
        try {
            if (icon == null) {
                if (!SystemTray.isSupported()) { unavailable = true; return; }
                URL url = DesktopNotifier.class.getResource("/images/logo-34.png");
                Image img = url != null ? Toolkit.getDefaultToolkit().getImage(url)
                        : Toolkit.getDefaultToolkit().createImage(new byte[0]);
                icon = new TrayIcon(img, "BLauncher");
                icon.setImageAutoSize(true);
                SystemTray.getSystemTray().add(icon);
            }
            icon.displayMessage(title, text, error ? TrayIcon.MessageType.ERROR : TrayIcon.MessageType.INFO);
        } catch (Throwable e) {
            // нет трея (сервер без рабочего стола, политика Windows) — уведомления просто не будет
            unavailable = true;
        }
    }

    /** Убрать значок из трея при выходе, иначе он висит до наведения мыши. */
    public static synchronized void dispose() {
        if (icon != null) {
            try { SystemTray.getSystemTray().remove(icon); } catch (Throwable ignored) { }
            icon = null;
        }
    }
}
