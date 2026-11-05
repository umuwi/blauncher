package com.example.launcher.ui.worldcalc;

/** What a calculator needs from the launcher. */
public interface CalcHost {
    String tr(String key);
    void toast(String message, String type);
    void copy(String text);
}
