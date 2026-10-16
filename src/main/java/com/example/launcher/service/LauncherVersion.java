package com.example.launcher.service;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Единственное место, где лаунчер узнаёт свой номер версии.
 *
 * <p>Номер впечатывает {@code build.ps1} в {@code /version.properties} внутри jar — тот самый
 * {@code -Version}, из которого потом считается манифест. Зашитая строка ниже нужна только для
 * запуска из IDE и через {@code BLauncher.bat}, где ресурса нет.
 *
 * <p><b>Почему это вынесено в отдельный класс.</b> Копий было две, и вторая разъехалась.
 * {@code MainController} читал ресурс правильно, а {@code OnlineStatsService} держал рядом
 * собственную константу {@code "1.2"} — и именно её присылал в каждом heartbeat. Проверено по
 * серверу 4 октября: все return 21 токен в таблице {@code launches} записаны с версией
 * {@code 1.2}, хотя у людей стояли 1.12.x. То есть разбивка по версиям в отчёте показывала
 * одно и то же число для всех и не могла показать, сколько человек обновилось.
 *
 * <p>Расхождение такого рода ничем себя не выдаёт: оно не ломает ни сборку, ни запуск, а портит
 * только статистику, в которую смотрят редко. Поэтому источник теперь один на всех.
 */
public final class LauncherVersion {

    /** Запасной номер — только для запуска из IDE и через BLauncher.bat, где ресурса нет. */
    private static final String FALLBACK = "v1.13.0";

    private static final String VERSION = resolve();

    private LauncherVersion() {}

    /** Номер версии с ведущей «v», как его пишет build.ps1 и как его сравнивает UpdateService. */
    public static String get() {
        return VERSION;
    }

    /** Номер без ведущей «v» — для мест, где нужна голая цифра (User-Agent, параметр ?v=). */
    public static String plain() {
        String v = VERSION;
        return v.startsWith("v") || v.startsWith("V") ? v.substring(1) : v;
    }

    private static String resolve() {
        try (InputStream in = LauncherVersion.class.getResourceAsStream("/version.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                String stamped = props.getProperty("version", "").trim();
                if (!stamped.isEmpty()) return stamped;
            }
        } catch (Exception ignored) {
            // Ресурса нет или он испорчен — работаем по зашитому номеру.
        }
        return FALLBACK;
    }
}
