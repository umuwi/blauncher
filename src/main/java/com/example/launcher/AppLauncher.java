package com.example.launcher;

import javafx.application.Application;

/** jpackage's app.mainclass entry point - a plain (non-Application) class, since the
 *  jpackage-built BLauncher.exe needs a main() it can invoke directly before JavaFX's own
 *  module/classpath bootstrap takes over via Application.launch(). */
public class AppLauncher {
    public static void main(String[] args) {
        if (handOverToUpdateScript()) {
            System.exit(0);
            return;
        }
        limitMemory();
        Application.launch(MainApp.class, args);
    }

    /**
     * Тихое обновление ещё ставится — уступить ему и открыться заново через несколько секунд.
     *
     * <p>29.10.2026 трое за день (bab75a54, 90686aa1 и ещё один) закрыли лаунчер и открыли снова
     * через 12–14 секунд. Скрипт замены (UpdateService.buildSelfReplaceScript) в это время ждал
     * выхода старого процесса и пытался переложить jar, а новый процесс уже держал этот jar сам —
     * замена не проходила, след «skc» без «m», и игроку показывали окно «обновление не
     * установилось». Здесь окно ещё не создано: пока след не дошёл до «m» (заменил) или «f»
     * (сдался), выходим, скрипт на следующей попытке заменяет jar, а преемник открывает уже новую
     * версию. Один раз на обновление (метка «handover» в update-pending.txt) и только в первые
     * полторы минуты — иначе зависший скрипт перезапускал бы лаунчер бесконечно.
     */
    private static boolean handOverToUpdateScript() {
        try {
            if (!com.example.launcher.service.Os.isWindows()) return false;   // на Linux jar меняется до выхода
            java.nio.file.Path pending = java.nio.file.Path.of("config", "update-pending.txt");
            if (!java.nio.file.Files.isRegularFile(pending)) return false;
            String line = java.nio.file.Files.readString(pending).trim();
            String[] p = line.split(" ");
            if (p.length < 4 || !p[3].equals("silent") || line.contains("handover")) return false;
            if (System.currentTimeMillis() - Long.parseLong(p[2]) > 90_000L) return false;
            java.nio.file.Path trace = pending.resolveSibling(
                    com.example.launcher.service.UpdateService.UPDATE_TRACE_FILE);
            String steps = java.nio.file.Files.isRegularFile(trace) ? java.nio.file.Files.readString(trace) : "";
            if (steps.contains("m") || steps.contains("f")) return false;
            if (!com.example.launcher.ui.AppRestart.supported()) return false;
            java.nio.file.Files.writeString(pending, line + " handover");
            // 4 с: скрипт пробует заменить jar раз в секунду; после нашего выхода хватает одной попытки.
            return com.example.launcher.ui.AppRestart.spawnSuccessor(4);
        } catch (Exception e) {
            return false;   // что-то не так с метками — просто запускаемся как обычно
        }
    }

    /**
     * Чтобы лаунчер, оставленный открытым, не держал гигабайты. 16.10.2026: у владельца рядом
     * лагал Blender — BLauncher через полтора часа занимал 1,5 ГБ, хотя живых объектов в куче было
     * ~40 МБ. Остальное — куча, которую G1 набрал и не отдаёт (в BLauncher.cfg нет -Xmx, потолок
     * = четверть ОЗУ, 8 ГБ), и кэш текстур Prism (по умолчанию до 512 МБ, без чистки, пока не
     * упрётся в потолок). Ставится из кода, а не в BLauncher.cfg: автообновление меняет только
     * BLauncher.jar, cfg у игроков остаётся старым.
     */
    private static void limitMemory() {
        // До запуска JavaFX — Prism читает их один раз при старте. -D в cfg/командной строке главнее.
        if (System.getProperty("prism.maxvram") == null) System.setProperty("prism.maxvram", "256m");
        if (System.getProperty("prism.targetvram") == null) System.setProperty("prism.targetvram", "160m");
        try {
            com.sun.management.HotSpotDiagnosticMXBean vm = java.lang.management.ManagementFactory
                    .getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
            // Свободного после сборки — не больше 30 %, лишнее G1 возвращает системе. Min — первым:
            // по умолчанию он 40, и Max=30 раньше него JVM отвергла бы (Min > Max).
            vm.setVMOption("MinHeapFreeRatio", "10");
            vm.setVMOption("MaxHeapFreeRatio", "30");
            // Раз в минуту простоя — фоновая сборка, на ней G1 и ужимает кучу; без неё
            // неподвижное окно не сжимается никогда.
            vm.setVMOption("G1PeriodicGCInterval", "60000");
        } catch (Throwable ignored) { /* не HotSpot/не G1 — живём с настройками по умолчанию */ }
    }
}
