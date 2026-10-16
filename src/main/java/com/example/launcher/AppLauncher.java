package com.example.launcher;

import javafx.application.Application;

/** jpackage's app.mainclass entry point - a plain (non-Application) class, since the
 *  jpackage-built BLauncher.exe needs a main() it can invoke directly before JavaFX's own
 *  module/classpath bootstrap takes over via Application.launch(). */
public class AppLauncher {
    public static void main(String[] args) {
        limitMemory();
        Application.launch(MainApp.class, args);
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
