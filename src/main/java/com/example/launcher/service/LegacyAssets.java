package com.example.launcher.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Раскладка ассетов для старых версий Minecraft, которым современное дерево
 * {@code assets/objects/<2 символа>/<sha1>} не подходит.
 *
 * <p>Индекс ассетов может нести два флага, и оба меняют то, где игра ищет файлы:
 * <ul>
 *   <li>{@code "map_to_resources": true} — версии 1.5.2 и старше. Файлы должны лежать по своим
 *       настоящим именам в {@code <gameDir>/resources/}: {@code sound/step/grass1.ogg},
 *       {@code lang/en_US.lang} и так далее. Именно так качал официальный лаунчер тех лет.</li>
 *   <li>{@code "virtual": true} — версии 1.6.x. То же самое, но в
 *       {@code assets/virtual/<id индекса>/}, и игре передаётся {@code --assetsDir} на этот
 *       каталог.</li>
 * </ul>
 *
 * <p>Ни один из флагов не обрабатывался: ассеты раскладывались только по хешам, а старая версия
 * искала их по именам и не находила. Это проверяется на этой машине: в
 * {@code assets/indexes/pre-1.6.json} стоит {@code "map_to_resources": true} и 749 объектов, а
 * каталога {@code resources/} рядом нет вовсе — значит установленная версия 1.0 запускается без
 * звуков и без языковых файлов.
 *
 * <p>Файлы копируются, а не связываются жёсткой ссылкой: ссылка между каталогами одного тома
 * работала бы, но игра тех версий писала в {@code resources/} сама, и правка через ссылку
 * испортила бы общий объект в {@code assets/objects}, который делят все версии.
 */
public final class LegacyAssets {

    private LegacyAssets() {}

    /** Прочитанный индекс и его флаги — чтобы не парсить один файл дважды. */
    public record Layout(boolean mapToResources, boolean virtual, String indexId) {
        public boolean isLegacy() { return mapToResources || virtual; }
    }

    /** Флаги индекса ассетов; при любой ошибке чтения — «не легаси», то есть прежнее поведение. */
    public static Layout layoutOf(Path assetsDir, String indexId) {
        if (indexId == null || indexId.isBlank()) return new Layout(false, false, indexId);
        Path indexFile = assetsDir.resolve("indexes").resolve(indexId + ".json");
        if (!Files.exists(indexFile)) return new Layout(false, false, indexId);
        try {
            JsonObject index = JsonParser.parseString(Files.readString(indexFile)).getAsJsonObject();
            return new Layout(truthy(index, "map_to_resources"), truthy(index, "virtual"), indexId);
        } catch (Exception e) {
            return new Layout(false, false, indexId);
        }
    }

    private static boolean truthy(JsonObject obj, String key) {
        try {
            return obj.has(key) && obj.get(key).isJsonPrimitive() && obj.get(key).getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Каталог, который нужно передать игре как {@code --assetsDir} / {@code ${game_assets}}.
     * Для современных версий это сам {@code assets}, для 1.6.x — виртуальный каталог, для
     * 1.5.2 и старше — {@code <gameDir>/resources}.
     */
    public static Path assetsDirForLaunch(Path gameDir, Path assetsDir, String indexId) {
        Layout layout = layoutOf(assetsDir, indexId);
        if (layout.mapToResources()) return gameDir.resolve("resources");
        if (layout.virtual()) return assetsDir.resolve("virtual").resolve(indexId);
        return assetsDir;
    }

    /**
     * Раскладывает объекты индекса по настоящим именам. Возвращает число скопированных файлов
     * (0 — если версия современная или всё уже на месте).
     *
     * <p>Вызывать после загрузки ассетов, в том числе когда качать было нечего: каталог
     * {@code resources/} мог не появиться при прошлой установке этой же версии.
     */
    public static int materialise(Path gameDir, Path assetsDir, String indexId) throws IOException {
        Layout layout = layoutOf(assetsDir, indexId);
        if (!layout.isLegacy()) return 0;

        Path indexFile = assetsDir.resolve("indexes").resolve(indexId + ".json");
        JsonObject index;
        try {
            index = JsonParser.parseString(Files.readString(indexFile)).getAsJsonObject();
        } catch (Exception e) {
            throw new IOException("Не удалось прочитать индекс ассетов " + indexId, e);
        }
        if (!index.has("objects") || !index.get("objects").isJsonObject()) return 0;
        JsonObject objects = index.getAsJsonObject("objects");

        Path target = assetsDirForLaunch(gameDir, assetsDir, indexId);
        Path targetRoot = target.toAbsolutePath().normalize();
        Files.createDirectories(targetRoot);
        Path objectsDir = assetsDir.resolve("objects");

        int copied = 0;
        for (String key : objects.keySet()) {
            if (key == null || key.isBlank()) continue;
            JsonObject obj = objects.getAsJsonObject(key);
            if (obj == null || !obj.has("hash")) continue;
            String hash = obj.get("hash").getAsString();
            if (hash.length() < 2) continue;

            Path source = objectsDir.resolve(hash.substring(0, 2)).resolve(hash);
            if (!Files.exists(source)) continue;   // не скачался — не наша забота, и не повод падать

            // Ключи приходят из индекса Mojang, но путь всё равно проверяется на выход за пределы
            // каталога: индекс — это скачанный файл, а значит внешние данные.
            Path dest;
            try {
                dest = targetRoot.resolve(key).normalize();
            } catch (Exception e) {
                continue;
            }
            if (!dest.startsWith(targetRoot)) continue;

            long sourceSize = Files.size(source);
            if (Files.exists(dest) && Files.size(dest) == sourceSize) continue;

            Files.createDirectories(dest.getParent());
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
            copied++;
        }
        return copied;
    }
}
