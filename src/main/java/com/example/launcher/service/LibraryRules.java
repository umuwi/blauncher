package com.example.launcher.service;

import com.google.gson.JsonObject;

/**
 * Единственное место, где вычисляется «нужна ли эта библиотека на нашей ОС».
 *
 * <p>Правила из версионного JSON ({@code "rules": [{"action":"allow","os":{"name":"osx"}}, ...]})
 * раньше разбирались только в {@link GameLaunchService} — при сборке класспатха и при проверке
 * полноты ванильного набора. {@link VersionDownloadService} их не смотрел вовсе, поэтому качал
 * нативы для всех трёх ОС: в современном формате каждый нативник — отдельная запись с собственным
 * {@code downloads.artifact}, а не вложенный {@code classifiers}, и фильтр по классификатору их
 * не отсекает. На диске это проверяется глазами: в {@code libraries/org/lwjgl/lwjgl/3.3.3/} рядом
 * с {@code natives-windows} лежат {@code natives-linux}, {@code natives-macos} и
 * {@code natives-macos-arm64}, а в {@code netty-transport-native-epoll} — сборки под Linux. На
 * версию это 60–100 МБ лишнего трафика и места.
 *
 * <p>Фильтровать нужно согласованно в трёх местах: что скачиваем, что считаем установленным и что
 * кладём в класспath. Разойдись они — и {@code isVersionFullyInstalled} станет вечно ложным, то
 * есть лаунчер будет заново «докачивать» версию при каждом запуске. Поэтому логика здесь одна, а
 * вызывающие стороны только зовут {@link #appliesToCurrentOs(JsonObject)}.
 */
public final class LibraryRules {

    private LibraryRules() {}

    /** Классификатор нативов текущей ОС: natives-windows / natives-macos / natives-linux. */
    public static String nativeClassifier() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return "natives-windows";
        if (os.contains("mac")) return "natives-macos";
        return "natives-linux";
    }

    /** Библиотека без блока {@code "rules"} нужна всегда; с блоком — только если правила разрешают. */
    public static boolean appliesToCurrentOs(JsonObject lib) {
        if (lib == null || !lib.has("rules") || !lib.get("rules").isJsonArray()) return true;

        String currentOs = System.getProperty("os.name", "").toLowerCase();
        boolean isMac = currentOs.contains("mac");
        boolean isWindows = currentOs.contains("win");
        boolean isLinux = currentOs.contains("linux") || currentOs.contains("nux");

        boolean allowed = false;
        for (var r : lib.getAsJsonArray("rules")) {
            if (!r.isJsonObject()) continue;
            JsonObject rule = r.getAsJsonObject();
            String action = rule.has("action") ? rule.get("action").getAsString() : "allow";

            if (!rule.has("os") || !rule.get("os").isJsonObject()) {
                if ("allow".equals(action)) allowed = true;
                if ("disallow".equals(action)) allowed = false;
            } else if (osMatches(rule.getAsJsonObject("os"), isWindows, isMac, isLinux)) {
                if ("allow".equals(action)) allowed = true;
                if ("disallow".equals(action)) allowed = false;
            }
        }
        return allowed;
    }

    /**
     * Совпадает ли блок {@code "os"} правила с текущей системой.
     *
     * <p>Раньше читалось только {@code os.name}, а {@code os.arch} игнорировался. Объект правила,
     * у которого есть {@code "os"}, но нет {@code "name"}, попадал в ветку с пустым именем ОС,
     * ни с чем не совпадал и пропускался — то есть оставался запрещённым. Проверено по реальным
     * данным: и {@code 1.21.1.json}, и {@code 26.2.json} содержат
     * {@code {"rules":[{"action":"allow","os":{"arch":"x86"}}],"value":"-Xss1M"}}. На 32-битной
     * JVM этот флаг молча терялся, а Mojang ставит его потому, что дефолтного стека 32-бит не
     * хватает при генерации мира.
     *
     * <p>Пустой блок {@code "os"} (ни name, ни arch) считаем совпадением: правило без условий
     * относится ко всем.
     */
    public static boolean osMatches(JsonObject os, boolean isWindows, boolean isMac, boolean isLinux) {
        boolean nameOk = true;
        if (os.has("name")) {
            String osName = os.get("name").getAsString().toLowerCase();
            if (osName.equals("osx") || osName.equals("macos")) nameOk = isMac;
            else if (osName.contains("windows")) nameOk = isWindows;
            else if (osName.contains("linux")) nameOk = isLinux;
            else nameOk = false;   // имя ОС, которого мы не знаем — правило не про нас
        }

        boolean archOk = true;
        if (os.has("arch")) {
            // os.arch у JVM: "amd64"/"x86_64" для 64-бит, "x86"/"i386" для 32-бит, "aarch64" для ARM.
            // Mojang пишет в правилах "x86", "x86_64", "arm64".
            String want = os.get("arch").getAsString().toLowerCase();
            String have = System.getProperty("os.arch", "").toLowerCase();
            boolean have64 = have.contains("64");
            boolean haveArm = have.contains("aarch") || have.startsWith("arm");
            if (want.equals("x86") || want.equals("i386") || want.equals("x32")) {
                archOk = !have64 && !haveArm;
            } else if (want.equals("x86_64") || want.equals("amd64") || want.equals("x64")) {
                archOk = have64 && !haveArm;
            } else if (want.startsWith("arm") || want.startsWith("aarch")) {
                archOk = haveArm;
            } else {
                archOk = want.equals(have);   // что-то незнакомое — сверяем как есть
            }
        }

        return nameOk && archOk;
    }

    /**
     * Нативы чужой ОС, которые у Mojang оформлены как отдельная библиотека без блока
     * {@code "rules"} — такие тоже встречаются, и правила их не отсекают.
     *
     * <p>Опознаём по классификатору в самом maven-имени ({@code "...:natives-linux"}) и по пути
     * артефакта. Нативы НАШЕЙ системы, разумеется, нужны: решение принимается сравнением с
     * {@code nativeClassifier}, а не простым «есть ли слово natives».
     */
    public static boolean isForeignNative(JsonObject lib, String nativeClassifier) {
        if (lib == null) return false;

        String name = lib.has("name") && lib.get("name").isJsonPrimitive()
                ? lib.get("name").getAsString().toLowerCase() : "";
        int colon = name.lastIndexOf(':');
        if (colon >= 0) {
            String classifier = name.substring(colon + 1);
            if (classifier.startsWith("natives-")) {
                return !matchesOurNatives(classifier, nativeClassifier);
            }
        }

        // Путь вида .../lwjgl-3.3.3-natives-macos-arm64.jar — классификатор в имени файла.
        if (lib.has("downloads") && lib.get("downloads").isJsonObject()) {
            JsonObject downloads = lib.getAsJsonObject("downloads");
            if (downloads.has("artifact") && downloads.get("artifact").isJsonObject()) {
                JsonObject artifact = downloads.getAsJsonObject("artifact");
                if (artifact.has("path") && artifact.get("path").isJsonPrimitive()) {
                    String path = artifact.get("path").getAsString().toLowerCase();
                    int idx = path.lastIndexOf("natives-");
                    if (idx >= 0) {
                        String tail = path.substring(idx);
                        if (tail.endsWith(".jar")) tail = tail.substring(0, tail.length() - 4);
                        return !matchesOurNatives(tail, nativeClassifier);
                    }
                }
            }
        }
        return false;
    }

    /**
     * Наш ли это классификатор нативов. Сравнение по префиксу нарочно: на macOS встречаются
     * {@code natives-macos} и {@code natives-macos-arm64}, и на Apple Silicon нужны оба — ARM-сборка
     * как основная, обычная как фолбэк для части библиотек. А вот {@code natives-windows-arm64} на
     * обычной Windows x64 не нужен, поэтому совпадение по префиксу дополнительно проверяется по
     * архитектуре.
     */
    private static boolean matchesOurNatives(String classifier, String nativeClassifier) {
        if (classifier.equals(nativeClassifier)) return true;
        if (!classifier.startsWith(nativeClassifier)) return false;

        String suffix = classifier.substring(nativeClassifier.length());
        if (suffix.isEmpty()) return true;

        String have = System.getProperty("os.arch", "").toLowerCase();
        boolean haveArm = have.contains("aarch") || have.startsWith("arm");
        boolean have64 = have.contains("64");

        if (suffix.startsWith("-arm") || suffix.startsWith("-aarch")) return haveArm;
        // Порядок важен: "-x86_64" и "-x86-64" — это 64 бита, а голое "-x86" — 32.
        if (suffix.startsWith("-x86_64") || suffix.startsWith("-x86-64")
                || suffix.startsWith("-x64")) {
            return have64 && !haveArm;
        }
        if (suffix.startsWith("-x86") || suffix.startsWith("-i386") || suffix.startsWith("-i686")) {
            return !have64 && !haveArm;
        }
        // Незнакомый хвост у нашей же ОС (например natives-macos-patch) — лучше скачать, чем
        // недокачать: цена ошибки в одну сторону это лишний файл, в другую — незапускающаяся игра.
        return true;
    }
}
