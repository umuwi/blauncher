package com.example.launcher.model;

/**
 * A saved launcher profile: an offline nickname plus optional local skin/cape files.
 * The id is stable so skin/cape files survive profile renames.
 */
public class UserProfile {
    private String id;
    private String name;
    private String skinPath;
    private String capePath;
    /** Hosted skin URL (e.g. from MineSkin) used instead of the local file when available. */
    private String skinUrl;

    public UserProfile() {
    }

    public UserProfile(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSkinPath() {
        return skinPath;
    }

    public void setSkinPath(String skinPath) {
        this.skinPath = skinPath;
    }

    public String getCapePath() {
        return capePath;
    }

    public void setCapePath(String capePath) {
        this.capePath = capePath;
    }

    public String getSkinUrl() {
        return skinUrl;
    }

    public void setSkinUrl(String skinUrl) {
        this.skinUrl = skinUrl;
    }
}
