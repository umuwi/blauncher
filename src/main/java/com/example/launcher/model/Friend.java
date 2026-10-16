package com.example.launcher.model;

/**
 * One row of the launcher's friends list, as returned by the friends API
 * ({"action":"sync"} in server/blauncher.ru/api/friends.php).
 *
 * <p>Identity is {@link #code} — a public friend code like {@code BL-7K3M-9QX2} that players
 * pass to each other. The nickname is only a label: two different people can both be called
 * "Steve", and it can be changed at any time, so it is never used to look a friend up.
 *
 * <p>{@link #skinUrl} is the skin the friend's own launcher published (their profile's hosted
 * texture, e.g. from MineSkin). It is null when they have no uploaded skin — the friends screen
 * then falls back to looking the nickname up on Ely.by, and shows a placeholder head if that
 * fails too.
 */
public class Friend {
    private String code;
    private String nick;
    private String about;
    private String skinUrl;
    /** True when the friend's launcher talked to the API within the last couple of minutes. */
    private boolean online;

    public Friend() {
    }

    public Friend(String code, String nick, String about, String skinUrl, boolean online) {
        this.code = code;
        this.nick = nick;
        this.about = about;
        this.skinUrl = skinUrl;
        this.online = online;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getNick() {
        return nick;
    }

    public void setNick(String nick) {
        this.nick = nick;
    }

    /** The friend's free-form "о себе" text — visible to everyone on their friends list. */
    public String getAbout() {
        return about;
    }

    public void setAbout(String about) {
        this.about = about;
    }

    public String getSkinUrl() {
        return skinUrl;
    }

    public void setSkinUrl(String skinUrl) {
        this.skinUrl = skinUrl;
    }

    public boolean isOnline() {
        return online;
    }

    public void setOnline(boolean online) {
        this.online = online;
    }
}
