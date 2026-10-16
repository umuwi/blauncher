package com.example.launcher.service.vps;

import java.util.regex.Pattern;

/**
 * Экранирование и проверка всего, что попадает в команды на VPS.
 *
 * <p>Правило панели VPS: команда для shell собирается только из констант и аргументов, прошедших
 * через {@link #quote}. Любой пользовательский ввод (имя игрока, путь, версия) перед этим ещё и
 * проверяется на допустимые символы — экранирование защищает от инъекции, а проверка отсекает
 * значения, которым там не место в принципе (ник с пробелом, путь с {@code ..}).
 */
public final class Shell {

    private Shell() {}

    /**
     * Оборачивает значение в одинарные кавычки POSIX shell. Внутри одинарных кавычек shell не
     * раскрывает ничего ($, `, \, !), поэтому единственный символ, требующий обработки, — сама
     * кавычка: она закрывает строку, добавляется экранированной и строка открывается снова.
     */
    public static String quote(String value) {
        if (value == null) throw new IllegalArgumentException("null в аргументе команды");
        if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("NUL в аргументе команды");
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static final Pattern PLAYER = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    /** Ник Minecraft: 3–16 символов, латиница, цифры, подчёркивание. */
    public static String requirePlayerName(String name) {
        if (name == null || !PLAYER.matcher(name).matches()) {
            throw new IllegalArgumentException("Недопустимый ник: только латиница, цифры и _, от 3 до 16 символов");
        }
        return name;
    }

    private static final Pattern UNIX_USER = Pattern.compile("^[a-z_][a-z0-9_-]{0,31}$");
    /** Имя пользователя Linux в том виде, в каком его принимает useradd по умолчанию. */
    public static String requireUnixUser(String user) {
        if (user == null || !UNIX_USER.matcher(user).matches()) {
            throw new IllegalArgumentException("Недопустимое имя пользователя Linux: " + user);
        }
        return user;
    }

    private static final Pattern MC_VERSION = Pattern.compile("^[A-Za-z0-9._+-]{1,40}$");
    /** Версия Minecraft или загрузчика: 1.21.1, 26.3-snapshot-2, 0.16.5+build.1 … */
    public static String requireVersion(String version) {
        if (version == null || !MC_VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("Недопустимая версия: " + version);
        }
        return version;
    }

    /**
     * Путь внутри папки сервера, заданный относительно неё: без абсолютного начала, без {@code ..},
     * без управляющих символов. Возвращает нормализованный вид с прямыми слэшами.
     */
    public static String requireRelativePath(String path) {
        if (path == null || path.isEmpty()) throw new IllegalArgumentException("Пустой путь");
        String p = path.replace('\\', '/');
        if (p.startsWith("/") || p.startsWith("~")) throw new IllegalArgumentException("Путь должен быть внутри папки сервера: " + path);
        for (int i = 0; i < p.length(); i++) {
            if (p.charAt(i) < 0x20 || p.charAt(i) == 0x7f) throw new IllegalArgumentException("Управляющий символ в пути");
        }
        for (String part : p.split("/")) {
            if (part.equals("..")) throw new IllegalArgumentException("Путь не может выходить из папки сервера: " + path);
        }
        return p;
    }
}
