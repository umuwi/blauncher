package com.example.launcher.ui;

import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Control;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.text.Text;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * English for the text the launcher builds in code (02.11.2026, owner: «переведи всё»).
 *
 * <p>main.fxml already reads strings_en.properties, but about 1,700 messages are Russian literals in
 * the code — toasts, dialogs, statuses, the VPS panel. Rewriting every call site would touch hundreds
 * of places, so the translation happens where text reaches the screen instead: every window's
 * labels, buttons, tooltips, prompt texts, plain Text nodes and window titles are watched, and Russian
 * text is swapped for English from i18n/code_en.tsv. A whole string is looked up first; a string
 * glued from pieces ("Скачиваю Minecraft " + version + …) gets each known piece of six or more
 * characters replaced, longest first, then numbers' units (ГБ, МБ, мин, с…).
 *
 * <p>Only what is on screen changes: the model (combo items, settings, build names) keeps its own
 * text, and typed text and the game log (TextInputControl) are never touched. Russian that is not in
 * the dictionary — a player's build called «Моя сборка», a Russian mod description — stays as it is:
 * pieces shorter than six characters are matched only as a whole string.
 */
public final class UiTranslator {

    private UiTranslator() {
    }

    private static volatile boolean english;
    private static final Map<String, String> EXACT = new HashMap<>();
    /** {ru, en}, longest ru first. */
    private static final List<String[]> PIECES = new ArrayList<>();
    private static final int MIN_PIECE = 6;
    private static final Pattern CYRILLIC = Pattern.compile("[А-Яа-яЁё]");
    private static final String[][] UNITS = {
            {"(\\d)\\s?ГБ", "$1 GB"}, {"(\\d)\\s?МБ", "$1 MB"}, {"(\\d)\\s?КБ", "$1 KB"},
            {"(?U)(\\d)\\s?мин\\b", "$1 min"}, {"(?U)(\\d)\\s?мс\\b", "$1 ms"},
            {"(?U)(\\d)\\s?с\\b", "$1 s"}, {"(?U)(\\d)\\s?ч\\b", "$1 h"}, {"(?U)\\bбайт\\b", "bytes"},
    };
    private static final Pattern[] UNIT_PATTERNS = new Pattern[UNITS.length];
    private static final String HOOKED = "bl-i18n-hooked";
    private static boolean installed;

    static {
        for (int i = 0; i < UNITS.length; i++) UNIT_PATTERNS[i] = Pattern.compile(UNITS[i][0]);
        load();
    }

    private static void load() {
        try (InputStream in = UiTranslator.class.getResourceAsStream("/i18n/code_en.tsv")) {
            if (in == null) return;
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                String ru = unescape(line.substring(0, tab));
                String en = unescape(line.substring(tab + 1));
                EXACT.put(ru, en);
                if (ru.length() >= MIN_PIECE) PIECES.add(new String[]{ru, en});
            }
            PIECES.sort((a, b) -> b[0].length() - a[0].length());
        } catch (Exception e) {
            System.out.println("[i18n] code_en.tsv не прочитан: " + e.getMessage());
        }
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                b.append(n == 'n' ? '\n' : n == 't' ? '\t' : n);
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    public static void setEnglish(boolean on) {
        english = on;
    }

    public static boolean isEnglish() {
        return english;
    }

    /** The English for a Russian text, or the text itself (Russian interface, nothing known, no Cyrillic). */
    public static String tx(String s) {
        if (!english || s == null || s.isEmpty() || !CYRILLIC.matcher(s).find()) return s;
        String whole = EXACT.get(s);
        if (whole != null) return whole;
        String r = s;
        for (String[] p : PIECES) {
            if (r.contains(p[0])) r = r.replace(p[0], p[1]);
        }
        if (CYRILLIC.matcher(r).find()) {
            for (int i = 0; i < UNIT_PATTERNS.length; i++) r = UNIT_PATTERNS[i].matcher(r).replaceAll(UNITS[i][1]);
            String again = EXACT.get(r.trim());
            if (again != null) r = r.replace(r.trim(), again);
        }
        return r;
    }

    /** Watches every window, now and later. Call once on the FX thread, before the first window shows. */
    public static void install() {
        if (installed) return;
        installed = true;
        Window.getWindows().addListener((ListChangeListener<Window>) c -> {
            while (c.next()) for (Window w : c.getAddedSubList()) hookWindow(w);
        });
        for (Window w : new ArrayList<>(Window.getWindows())) hookWindow(w);
    }

    private static void hookWindow(Window w) {
        if (w.getProperties().containsKey(HOOKED)) return;
        w.getProperties().put(HOOKED, Boolean.TRUE);
        if (w instanceof Stage st) {
            translateTitle(st);
            st.titleProperty().addListener((o, a, b) -> translateTitle(st));
        }
        if (w.getScene() != null) hookScene(w.getScene());
        w.sceneProperty().addListener((o, a, b) -> { if (b != null) hookScene(b); });
    }

    /** Hooks a scene that is not (yet) in a window — tests render one this way. */
    public static void hookScene(Scene s) {
        if (s.getRoot() != null) hookNode(s.getRoot());
        s.rootProperty().addListener((o, a, b) -> { if (b != null) hookNode(b); });
    }

    private static void translateTitle(Stage st) {
        if (st.titleProperty().isBound()) return;
        String t = tx(st.getTitle());
        if (t != null && !t.equals(st.getTitle())) st.setTitle(t);
    }

    private static void hookNode(Node n) {
        if (n.getProperties().containsKey(HOOKED)) return;
        n.getProperties().put(HOOKED, Boolean.TRUE);
        if (n instanceof Labeled l) {
            translate(l);
            l.textProperty().addListener((o, a, b) -> translate(l));
        } else if (n instanceof TextInputControl t) {
            translatePrompt(t);
            t.promptTextProperty().addListener((o, a, b) -> translatePrompt(t));
        } else if (n instanceof Text t && !insideTextOwner(t)) {
            translate(t);
            t.textProperty().addListener((o, a, b) -> translate(t));
        }
        if (n instanceof Control c) {
            hookTooltip(c.getTooltip());
            c.tooltipProperty().addListener((o, a, b) -> hookTooltip(b));
        }
        if (n instanceof Parent p && !(n instanceof TextInputControl)) {
            for (Node k : p.getChildrenUnmodifiable()) hookNode(k);
            p.getChildrenUnmodifiable().addListener((ListChangeListener<Node>) c -> {
                while (c.next()) for (Node k : c.getAddedSubList()) hookNode(k);
            });
        }
    }

    /** Text drawn by a label's or a text field's own skin is handled through its owner (or never). */
    private static boolean insideTextOwner(Text t) {
        if (t.getClass() != Text.class) return true;   // LabeledText and other skin subclasses
        for (Parent p = t.getParent(); p != null; p = p.getParent()) {
            if (p instanceof Labeled || p instanceof TextInputControl) return true;
        }
        return false;
    }

    private static void hookTooltip(Tooltip tip) {
        if (tip == null || tip.getProperties().containsKey(HOOKED)) return;
        tip.getProperties().put(HOOKED, Boolean.TRUE);
        translate(tip);
        tip.textProperty().addListener((o, a, b) -> translate(tip));
    }

    private static void translate(Labeled l) {
        if (l.textProperty().isBound()) return;
        String t = tx(l.getText());
        if (t != null && !t.equals(l.getText())) l.setText(t);
    }

    private static void translate(Text n) {
        if (n.textProperty().isBound()) return;
        String t = tx(n.getText());
        if (t != null && !t.equals(n.getText())) n.setText(t);
    }

    private static void translate(Tooltip tip) {
        if (tip.textProperty().isBound()) return;
        String t = tx(tip.getText());
        if (t != null && !t.equals(tip.getText())) tip.setText(t);
    }

    private static void translatePrompt(TextInputControl c) {
        if (c.promptTextProperty().isBound()) return;
        String t = tx(c.getPromptText());
        if (t != null && !t.equals(c.getPromptText())) c.setPromptText(t);
    }
}
