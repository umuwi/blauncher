package com.example.launcher.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Text;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Подсказка в строке консоли сервера (11.10.2026, просьба владельца): пока пишешь команду, строка
 * бледным текстом дописывает, что, скорее всего, будет дальше — команду, её аргумент или ник.
 *
 * <p>Нарочно ОДНА подсказка прямо в строке, без выпадающих списков: владелец просил «чтобы не
 * захламлять». Появляется только после двух букв текущего слова. Принять — Tab или → в конце
 * строки; Enter отправляет ровно то, что набрано, — случайно отправить чужой ник нельзя.
 * ↑/↓ — история отправленных команд, как в терминале.
 *
 * <p>Откуда берутся варианты: целые строки из истории (повторить вчерашнюю команду), команды
 * Minecraft и те, что сервер показал в ответ на «help», значения частых аргументов (gamemode,
 * time, weather…), ники — их даёт вызывающий (онлайн, ops, вайтлист, кто заходил по консоли).
 */
public final class CommandAssist {

    /** Команды ванильного сервера (Java 1.21). Плагинные подмешиваются из вывода «help». */
    private static final List<String> VANILLA = List.of(
            "advancement", "attribute", "ban", "ban-ip", "banlist", "bossbar", "clear", "clone", "damage",
            "data", "datapack", "debug", "defaultgamemode", "deop", "difficulty", "effect", "enchant",
            "execute", "experience", "fill", "fillbiome", "forceload", "function", "gamemode", "gamerule",
            "give", "help", "item", "kick", "kill", "list", "locate", "loot", "me", "msg", "op", "pardon",
            "pardon-ip", "particle", "place", "playsound", "random", "recipe", "reload", "ride", "save-all",
            "save-off", "save-on", "say", "schedule", "scoreboard", "seed", "setblock", "setidletimeout",
            "setworldspawn", "spawnpoint", "spectate", "spreadplayers", "stop", "stopsound", "summon", "tag",
            "team", "teleport", "tell", "tellraw", "tick", "time", "title", "tp", "transfer", "trigger",
            "weather", "whitelist", "worldborder", "xp");

    /** Слово «PLAYER» в списке — подставлять ники. Позиция в массиве = номер аргумента. */
    private static final String P = "PLAYER";
    private static final Map<String, List<List<String>>> ARGS = Map.ofEntries(
            Map.entry("gamemode", List.of(List.of("survival", "creative", "adventure", "spectator"), List.of(P))),
            Map.entry("defaultgamemode", List.of(List.of("survival", "creative", "adventure", "spectator"))),
            Map.entry("difficulty", List.of(List.of("peaceful", "easy", "normal", "hard"))),
            Map.entry("time", List.of(List.of("set", "add", "query"), List.of("day", "night", "noon", "midnight", "daytime", "gametime"))),
            Map.entry("weather", List.of(List.of("clear", "rain", "thunder"))),
            Map.entry("whitelist", List.of(List.of("add", "remove", "on", "off", "list", "reload"), List.of(P))),
            Map.entry("gamerule", List.of(List.of("keepInventory", "doDaylightCycle", "doWeatherCycle", "doMobSpawning",
                    "mobGriefing", "doFireTick", "doInsomnia", "playersSleepingPercentage", "pvp", "naturalRegeneration",
                    "announceAdvancements", "showDeathMessages", "doImmediateRespawn", "spawnRadius", "randomTickSpeed"),
                    List.of("true", "false"))),
            Map.entry("tp", List.of(List.of(P), List.of(P))),
            Map.entry("teleport", List.of(List.of(P), List.of(P))),
            Map.entry("give", List.of(List.of(P), List.of("minecraft:diamond", "minecraft:netherite_ingot", "minecraft:elytra",
                    "minecraft:totem_of_undying", "minecraft:golden_apple", "minecraft:enchanted_golden_apple", "minecraft:command_block")))
    );

    private static final int HISTORY_MAX = 100;

    private final TextField field;
    private final Supplier<Collection<String>> players;
    private final Supplier<Collection<String>> extraCommands;
    private final Text typedGhost = new Text();
    private final Text restGhost = new Text();
    private final HBox ghost = new HBox(typedGhost, restGhost);
    private final List<String> history = new ArrayList<>();
    private int historyPos = -1;
    private String historyDraft = "";
    /** Что подставится по Tab: полный текст строки после принятия. null — подсказки нет. */
    private String completion;

    private CommandAssist(TextField field, Supplier<Collection<String>> players, Supplier<Collection<String>> extraCommands) {
        this.field = field;
        this.players = players;
        this.extraCommands = extraCommands;
    }

    /**
     * Встраивает подсказку в поле: поле оборачивается в StackPane на том же месте родителя
     * (FXML не меняется). {@code players} и {@code extraCommands} спрашиваются при каждом вводе —
     * в момент набора, а не один раз, чтобы ник зашедшего минуту назад уже подсказывался.
     */
    public static CommandAssist attach(TextField field, Supplier<Collection<String>> players,
                                       Supplier<Collection<String>> extraCommands) {
        CommandAssist a = new CommandAssist(field, players, extraCommands);
        a.install();
        return a;
    }

    private void install() {
        Parent parent = field.getParent();
        if (!(parent instanceof Pane pane)) return;
        int idx = pane.getChildren().indexOf(field);
        StackPane wrap = new StackPane();
        wrap.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(wrap, HBox.getHgrow(field) != null ? HBox.getHgrow(field) : Priority.ALWAYS);
        pane.getChildren().remove(idx);
        wrap.getChildren().addAll(field, ghost);
        pane.getChildren().add(idx, wrap);
        field.setMaxWidth(Double.MAX_VALUE);

        ghost.setMouseTransparent(true);
        ghost.setManaged(true);
        ghost.setAlignment(Pos.CENTER_LEFT);
        ghost.setMaxWidth(Double.MAX_VALUE);
        typedGhost.setFill(Color.TRANSPARENT);
        restGhost.setFill(Color.web("#8a8f9c", 0.75));
        StackPane.setAlignment(ghost, Pos.CENTER_LEFT);

        field.textProperty().addListener((o, a, b) -> refresh());
        field.caretPositionProperty().addListener((o, a, b) -> refresh());
        field.focusedProperty().addListener((o, a, b) -> refresh());
        field.fontProperty().addListener((o, a, b) -> refresh());
        field.disabledProperty().addListener((o, a, b) -> refresh());
        field.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        refresh();
    }

    /** Отправленная команда — в историю (её зовут из обработчика «Отправить»). */
    public void remember(String cmd) {
        if (cmd == null || cmd.isBlank()) return;
        String c = cmd.strip();
        history.remove(c);
        history.add(c);
        while (history.size() > HISTORY_MAX) history.remove(0);
        historyPos = -1;
    }

    private void onKey(KeyEvent e) {
        KeyCode k = e.getCode();
        boolean atEnd = field.getCaretPosition() == field.getLength() && field.getSelection().getLength() == 0;
        if ((k == KeyCode.TAB || (k == KeyCode.RIGHT && atEnd)) && completion != null) {
            // Копия: setText сразу пересчитывает подсказку и обнуляет поле completion.
            String accepted = completion;
            field.setText(accepted);
            field.positionCaret(accepted.length());
            e.consume();
        } else if (k == KeyCode.TAB) {
            e.consume();   // без подсказки Tab не уводит фокус из консоли: его жмут по привычке
        } else if (k == KeyCode.UP && !history.isEmpty()) {
            if (historyPos == -1) { historyDraft = field.getText(); historyPos = history.size(); }
            historyPos = Math.max(0, historyPos - 1);
            setFromHistory(history.get(historyPos));
            e.consume();
        } else if (k == KeyCode.DOWN && historyPos != -1) {
            historyPos++;
            if (historyPos >= history.size()) { historyPos = -1; setFromHistory(historyDraft); }
            else setFromHistory(history.get(historyPos));
            e.consume();
        } else if (k == KeyCode.ESCAPE && completion != null) {
            completion = null;
            ghost.setVisible(false);
            e.consume();
        }
    }

    private void setFromHistory(String s) {
        field.setText(s);
        field.positionCaret(s.length());
    }

    private void refresh() {
        completion = null;
        String text = field.getText() == null ? "" : field.getText();
        boolean atEnd = field.getCaretPosition() == text.length();
        if (!field.isFocused() || field.isDisabled() || !atEnd || text.isEmpty()) {
            ghost.setVisible(false);
            return;
        }
        String full = suggest(text);
        if (full == null || full.length() <= text.length()) {
            ghost.setVisible(false);
            return;
        }
        completion = full;
        typedGhost.setFont(field.getFont());
        restGhost.setFont(field.getFont());
        // Хвост рисуется после невидимой копии набранного — так он встаёт ровно за кареткой.
        // Регистр набранной части может отличаться от подсказки («al» → «Alex»), длина та же.
        typedGhost.setText(text);
        restGhost.setText(full.substring(text.length()));
        ghost.setPadding(new Insets(0, 0, 0, field.getInsets().getLeft()));
        // Длинная строка прокручивается внутри поля, и бледный хвост встал бы не там — тогда молчим.
        double need = typedGhost.getLayoutBounds().getWidth() + restGhost.getLayoutBounds().getWidth()
                + field.getInsets().getLeft() + field.getInsets().getRight();
        ghost.setVisible(need < field.getWidth());
    }

    /** Режим чата: строка — обычное сообщение, подсказываем только ники, без команд и истории. */
    private boolean playersOnly;

    public void setPlayersOnly(boolean on) {
        playersOnly = on;
        refresh();
    }

    /** Полный текст строки с подсказкой, либо null. Чистая функция — проверяется без UI. */
    String suggest(String text) {
        if (playersOnly) {
            String token = text.substring(text.lastIndexOf(' ') + 1);
            if (token.length() < 2) return null;
            for (String p : players.get()) {
                if (p != null && p.length() > token.length() && p.regionMatches(true, 0, token, 0, token.length())) {
                    return text.substring(0, text.length() - token.length()) + p;
                }
            }
            return null;
        }
        return suggest(text, history, players.get(), commands());
    }

    private List<String> commands() {
        LinkedHashSet<String> all = new LinkedHashSet<>(VANILLA);
        Collection<String> extra = extraCommands == null ? null : extraCommands.get();
        if (extra != null) all.addAll(extra);
        return new ArrayList<>(all);
    }

    static String suggest(String text, List<String> history, Collection<String> players, List<String> commands) {
        int lastSpace = text.lastIndexOf(' ');
        String token = text.substring(lastSpace + 1);
        if (token.length() < 2) return null;

        // 1. Вся строка — начало команды из истории: предлагаем её целиком (свежие важнее).
        for (int i = history.size() - 1; i >= 0; i--) {
            String h = history.get(i);
            if (h.length() > text.length() && h.regionMatches(true, 0, text, 0, text.length())) {
                return text + h.substring(text.length());
            }
        }

        boolean slash = text.startsWith("/");
        String body = slash ? text.substring(1) : text;
        String[] words = body.split(" ", -1);
        if (lastSpace < 0) {
            // 2. Первое слово — команда.
            String t = slash ? token.substring(1) : token;
            if (t.length() < 2) return null;
            String c = firstPrefix(commands, t);
            return c == null ? null : (slash ? "/" : "") + c;
        }

        // 3. Аргумент: известные значения этой команды, иначе ники.
        String cmd = words[0].toLowerCase(Locale.ROOT);
        int argIndex = words.length - 2;   // 0 — первый аргумент
        List<List<String>> spec = ARGS.get(cmd);
        List<String> options = new ArrayList<>();
        if (spec != null && argIndex < spec.size()) {
            for (String o : spec.get(argIndex)) {
                if (o.equals(P)) options.addAll(players);
                else options.add(o);
            }
        } else if (spec == null || argIndex >= spec.size()) {
            options.addAll(players);
        }
        // Ник подставляется в своём регистре: набрал «al» — получил «Alex».
        String o = firstPrefix(options, token);
        return o == null ? null : text.substring(0, text.length() - token.length()) + o;
    }

    /** Первый вариант, который начинается с {@code prefix} (без учёта регистра) и длиннее его. */
    private static String firstPrefix(Collection<String> options, String prefix) {
        for (String o : options) {
            if (o != null && o.length() > prefix.length() && o.regionMatches(true, 0, prefix, 0, prefix.length())) return o;
        }
        return null;
    }
}
