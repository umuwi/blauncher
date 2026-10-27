package com.example.launcher.service;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Перевод описаний модов с Modrinth на русский (27.10.2026, просьба владельца: новички не понимают
 * английские описания и уходят с экрана «Моды»). Переводит наш сервер (api/translate.php) с общим кэшем —
 * лаунчер шлёт туда только публичные описания из выдачи Modrinth, ничего об игроке. Здесь — ещё и кэш
 * в памяти, чтобы листание и повторный поиск не ходили на сервер. Сервер недоступен — остаётся оригинал.
 */
public final class ModDescriptionTranslator {

    /** -Dblauncher.translateUrl — только для проверки с локальным заменителем сервера. */
    public static final String URL = System.getProperty("blauncher.translateUrl", "https://blauncher.ru/api/translate.php");
    private static final int MAX_PER_REQUEST = 60;
    private static final int MAX_LEN = 500;
    private static final int CACHE_SIZE = 3000;

    private final Gson gson = new Gson();
    private final Map<String, String> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    /**
     * Переводы для текстов (ключ — исходный текст); чего нет в ответе — того нет и в карте.
     * Вызывать не из UI-потока.
     */
    public Map<String, String> translate(List<String> texts) {
        Map<String, String> out = new HashMap<>();
        List<String> todo = new ArrayList<>();
        synchronized (cache) {
            for (String t : texts) {
                if (t == null || t.isBlank() || t.length() > MAX_LEN) continue;
                String hit = cache.get(t);
                if (hit != null) {
                    out.put(t, hit);
                } else if (!todo.contains(t)) {
                    todo.add(t);
                }
            }
        }
        for (int from = 0; from < todo.size(); from += MAX_PER_REQUEST) {
            List<String> chunk = todo.subList(from, Math.min(todo.size(), from + MAX_PER_REQUEST));
            try {
                JsonObject body = new JsonObject();
                JsonArray arr = new JsonArray();
                chunk.forEach(arr::add);
                body.add("texts", arr);
                Http.Response resp = Http.post(URL, gson.toJson(body), "application/json; charset=utf-8",
                        Map.of(), Duration.ofSeconds(25));
                if (!resp.ok()) break;
                JsonArray tr = JsonParser.parseString(resp.text()).getAsJsonObject().getAsJsonArray("translations");
                if (tr == null) break;
                synchronized (cache) {
                    for (int i = 0; i < chunk.size() && i < tr.size(); i++) {
                        JsonElement e = tr.get(i);
                        if (e == null || e.isJsonNull()) continue;
                        String ru = e.getAsString();
                        if (ru.isBlank()) continue;
                        cache.put(chunk.get(i), ru);
                        out.put(chunk.get(i), ru);
                    }
                }
            } catch (Exception ignored) {
                // Нет сети или сервер не ответил — покажем описания как есть.
                break;
            }
        }
        return out;
    }
}
