package com.example.launcher.service;

import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Эксклюзивы BLauncher в поиске модов (27.10.2026: первым — порт Create на 26.3). Список лежит на сайте,
 * а не в коде: новый эксклюзив или новая бета — это правка exclusive/mods.json на blauncher.ru, без выпуска
 * лаунчера. Сайт недоступен — эксклюзивов просто нет, поиск по Modrinth работает как раньше.
 */
public final class ExclusiveModsService {

    public static final String LIST_URL = "https://blauncher.ru/exclusive/mods.json";
    private static final long CACHE_MS = 10 * 60 * 1000L;

    /** Один эксклюзив: карточка в поиске и файлы под каждый загрузчик/версию игры. */
    public static final class ExclusiveMod {
        public String id;
        public String title;
        public String badge;
        public String description;
        public String icon;
        public List<String> keywords = new ArrayList<>();
        public List<ExclusiveFile> files = new ArrayList<>();

        /** Файл под сборку: загрузчик и версия игры должны совпасть точно. */
        public ExclusiveFile fileFor(String loader, String mcVersion) {
            if (files == null || loader == null || mcVersion == null) return null;
            for (ExclusiveFile f : files) {
                if (loader.equalsIgnoreCase(f.loader) && mcVersion.equals(f.mcVersion)) return f;
            }
            return null;
        }

        /** «26.3 · NeoForge, Fabric» — для подписи на карточке. */
        public String targetsLabel() {
            List<String> versions = new ArrayList<>();
            List<String> loaders = new ArrayList<>();
            if (files != null) {
                for (ExclusiveFile f : files) {
                    if (f.mcVersion != null && !versions.contains(f.mcVersion)) versions.add(f.mcVersion);
                    String l = f.loader == null || f.loader.isEmpty() ? "" : f.loader.equalsIgnoreCase("neoforge") ? "NeoForge"
                            : Character.toUpperCase(f.loader.charAt(0)) + f.loader.substring(1);
                    if (!l.isEmpty() && !loaders.contains(l)) loaders.add(l);
                }
            }
            return String.join(", ", versions) + (loaders.isEmpty() ? "" : " · " + String.join(", ", loaders));
        }

        /** Подходит ли под запрос и фильтры экрана «Моды»; пустой запрос — показываем всегда. */
        public boolean matches(String query, String mcVersion, String loader) {
            if (files != null && (mcVersion != null || loader != null)) {
                boolean any = false;
                for (ExclusiveFile f : files) {
                    if ((mcVersion == null || mcVersion.equals(f.mcVersion))
                            && (loader == null || loader.equalsIgnoreCase(f.loader))) {
                        any = true;
                        break;
                    }
                }
                if (!any) return false;
            }
            if (query == null || query.isBlank()) return true;
            String q = query.toLowerCase(Locale.ROOT).trim();
            if (title != null && title.toLowerCase(Locale.ROOT).contains(q)) return true;
            if (keywords != null) {
                for (String k : keywords) {
                    if (k != null && (k.toLowerCase(Locale.ROOT).contains(q) || q.contains(k.toLowerCase(Locale.ROOT)))) return true;
                }
            }
            return false;
        }
    }

    public static final class ExclusiveFile {
        public String loader;
        public String mcVersion;
        public String url;
        public String fileName;
        public String sha1;
        public long size;
    }

    private static final class ListJson {
        List<ExclusiveMod> mods = new ArrayList<>();
    }

    private final Gson gson = new Gson();
    private List<ExclusiveMod> cached = List.of();
    private long cachedAt;

    /** Список эксклюзивов; вызывать не из UI-потока. При ошибке сети — последний удачный (или пустой). */
    public synchronized List<ExclusiveMod> list() {
        long now = System.currentTimeMillis();
        if (cachedAt != 0 && now - cachedAt < CACHE_MS) return cached;
        try {
            Http.Response resp = Http.get(LIST_URL);
            if (resp.ok()) {
                ListJson parsed = gson.fromJson(resp.text(), ListJson.class);
                List<ExclusiveMod> mods = new ArrayList<>();
                if (parsed != null && parsed.mods != null) {
                    for (ExclusiveMod m : parsed.mods) {
                        if (m != null && m.id != null && m.title != null && m.files != null && !m.files.isEmpty()) mods.add(m);
                    }
                }
                cached = List.copyOf(mods);
            }
        } catch (Exception ignored) {
            // Нет сети или сайт лежит — не повод ронять поиск модов.
        }
        cachedAt = now;
        return cached;
    }
}
