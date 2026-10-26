package com.example.launcher.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Настройки server.properties, которые человек выбирает при создании сервера (17.10.2026):
 * локального (окно «Новый сервер») и на VPS (форма установки). Без JavaFX — редактор в
 * {@code ui/ServerPropsEditor}, запись в файл — здесь, установщик VPS берёт {@link #clean}.
 *
 * <p>Ключи — закрытый список: порт, RCON и прочее, чем лаунчер управляет сам, сюда не попадают,
 * поэтому выбор в форме не может их перебить. Значения проверяются и экранируются — в тексте MOTD
 * нельзя протащить перевод строки и с ним, например, {@code rcon.password=…}.
 */
public final class ServerProps {

    public enum Kind { INT, BOOL, CHOICE, TEXT }

    /**
     * Одна настройка. {@code inverted} — переключатель показывает обратное значение ключа:
     * «Пиратский» включён = {@code online-mode=false}. {@code wide} — поле на всю ширину сетки.
     */
    public record Field(String key, Kind kind, String def, int min, int max, List<String> choices,
                        boolean inverted, boolean wide) {
        /** Ключ строки в strings_*.properties: sp_max_players и т.п. */
        public String labelKey() { return "sp_" + key.replace('-', '_').replace('.', '_'); }
    }

    private static Field num(String key, int def, int min, int max) {
        return new Field(key, Kind.INT, String.valueOf(def), min, max, List.of(), false, false);
    }

    private static Field bool(String key, boolean def) {
        return new Field(key, Kind.BOOL, String.valueOf(def), 0, 0, List.of(), false, false);
    }

    private static Field choice(String key, String def, String... values) {
        return new Field(key, Kind.CHOICE, def, 0, 0, List.of(values), false, false);
    }

    private static Field text(String key, String def, int maxLen) {
        return new Field(key, Kind.TEXT, def, 0, maxLen, List.of(), false, true);
    }

    /**
     * Порядок — как на экране. Значения по умолчанию — ванильные, кроме двух, которые лаунчер ставил
     * всегда (GameLaunchService.writeServerDefaultsIfMissing): {@code online-mode=false} — игроки
     * BLauncher заходят с офлайн-аккаунтами, и {@code white-list=false}.
     */
    public static final List<Field> FIELDS = List.of(
            num("max-players", 20, 1, 1000),
            choice("gamemode", "survival", "survival", "creative", "adventure", "spectator"),
            choice("difficulty", "easy", "peaceful", "easy", "normal", "hard"),
            bool("white-list", false),
            new Field("online-mode", Kind.BOOL, "false", 0, 0, List.of(), true, false),
            bool("allow-flight", false),
            bool("pvp", true),
            bool("hardcore", false),
            bool("force-gamemode", false),
            num("spawn-protection", 16, 0, 1000),
            num("view-distance", 10, 2, 32),
            num("simulation-distance", 10, 2, 32),
            bool("allow-nether", true),
            bool("enable-command-block", false),
            bool("require-resource-pack", false),
            text("motd", "A BLauncher server", 150),
            text("level-seed", "", 64),
            text("resource-pack", "", 300),
            text("resource-pack-prompt", "", 200));

    private ServerProps() {
    }

    public static Field field(String key) {
        for (Field f : FIELDS) if (f.key().equals(key)) return f;
        return null;
    }

    public static Map<String, String> defaults() {
        Map<String, String> m = new LinkedHashMap<>();
        for (Field f : FIELDS) m.put(f.key(), f.def());
        return m;
    }

    /**
     * Проверенное значение в том виде, в каком оно пишется в файл (уже экранированное).
     *
     * @throws IllegalArgumentException с понятным текстом — показывается человеку как есть
     */
    public static String clean(String key, String raw) {
        Field f = field(key);
        if (f == null) throw new IllegalArgumentException("Неизвестная настройка " + key);
        String v = raw == null ? "" : raw.trim();
        switch (f.kind()) {
            case INT -> {
                int n;
                try {
                    n = Integer.parseInt(v);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(key + ": нужно число от " + f.min() + " до " + f.max());
                }
                if (n < f.min() || n > f.max()) {
                    throw new IllegalArgumentException(key + ": число от " + f.min() + " до " + f.max());
                }
                return String.valueOf(n);
            }
            case BOOL -> {
                if (!v.equals("true") && !v.equals("false")) throw new IllegalArgumentException(key + ": true или false");
                return v;
            }
            case CHOICE -> {
                if (!f.choices().contains(v)) throw new IllegalArgumentException(key + ": " + String.join(", ", f.choices()));
                return v;
            }
            default -> {
                StringBuilder sb = new StringBuilder();
                v.codePoints().filter(c -> c >= 0x20 && c != 0x7f).forEach(sb::appendCodePoint);
                String s = sb.toString();
                if (s.length() > f.max()) throw new IllegalArgumentException(key + ": не длиннее " + f.max() + " символов");
                if (key.equals("resource-pack") && !s.isEmpty()
                        && !s.startsWith("https://") && !s.startsWith("http://")) {
                    throw new IllegalArgumentException("Пакет ресурсов: ссылка должна начинаться с https://");
                }
                return escape(s);
            }
        }
    }

    /**
     * Экранирование значения по правилам java.util.Properties: обратный слеш удваивается, всё вне
     * ASCII — {@code \\uXXXX}. Так файл одинаково читают и старые серверы (ISO-8859-1), и новые
     * (UTF-8): кириллица в MOTD без этого превращалась бы в «Ð¡ÐµÑ€Ð²ÐµÑ€» на старых версиях.
     */
    public static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // Набранные по правилам файла \n, \\ и \\uXXXX — как есть: так в motd пишут перевод
            // строки и цвет (\\u00a7). Раньше слеш удваивался, и вместо цвета игрок видел
            // «\\u00a7» текстом (найдено ревью Android-копии 18.10.2026).
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(i + 1);
                if (n == 'n' || n == '\\') {
                    sb.append(c).append(n);
                    i++;
                    continue;
                }
                if (n == 'u' && i + 6 <= s.length() && s.substring(i + 2, i + 6).matches("[0-9a-fA-F]{4}")) {
                    sb.append(s, i, i + 6);
                    i += 5;
                    continue;
                }
            }
            if (c == '\\') sb.append("\\\\");
            else if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.toString();
    }

    /** Все значения формы, проверенные; первая ошибка — исключением с текстом для человека. */
    public static Map<String, String> cleanAll(Map<String, String> raw) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Field f : FIELDS) out.put(f.key(), clean(f.key(), raw.getOrDefault(f.key(), f.def())));
        return out;
    }

    /**
     * Вписать уже проверенные значения в server.properties: существующие строки с теми же ключами
     * заменяются, остальные строки и комментарии остаются как были, новых ключей дописывается в конец.
     * Нет файла — создаётся из {@code values} (порт и прочее передаёт вызывающий).
     */
    public static void writeInto(Path file, Map<String, String> values) throws IOException {
        List<String> lines = Files.isRegularFile(file)
                ? new ArrayList<>(Files.readAllLines(file, StandardCharsets.ISO_8859_1))
                // Комментарий латиницей: файл пишется в ISO-8859-1, кириллица стала бы «????».
                : new ArrayList<>(List.of("# Written by BLauncher when the server was created; edit freely"));
        Map<String, String> rest = new LinkedHashMap<>(values);
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            String t = l.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) continue;
            int eq = t.indexOf('=');
            String key = (eq < 0 ? t : t.substring(0, eq)).trim();
            if (rest.containsKey(key)) lines.set(i, key + "=" + rest.remove(key));
        }
        for (Map.Entry<String, String> e : rest.entrySet()) lines.add(e.getKey() + "=" + e.getValue());
        if (file.getParent() != null) Files.createDirectories(file.getParent());
        // Наши значения после escape() — чистый ASCII; байты чужих строк сохраняются ровно как были
        // (ISO-8859-1 туда-обратно не меняет ни одного байта).
        Files.write(file, lines, StandardCharsets.ISO_8859_1);
    }
}
