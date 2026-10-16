package com.example.launcher.ui;

import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Orientation;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Pane;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Цветная консоль сервера (11.10.2026): ошибки красным, предупреждения жёлтым, входы и выходы
 * игроков зелёным, ники кликабельны (меню: оператор, кик, бан…).
 *
 * <p>Сам текст по-прежнему живёт в TextArea консоли: в неё пишут три разных источника (локальный
 * сервер, VPS, старый агент), и переписывать их все незачем. TextArea прячется, а на её месте
 * показывается список строк, который перечитывает её текст через 100 мс после изменения.
 * Список виртуальный — даже 200 000 символов лога рисуются только видимыми строками.
 */
public final class ConsoleView {

    /** Что делать по щелчку на нике. */
    public interface NickHandler {
        void onNick(String nick, Node anchor, double screenX, double screenY);
    }

    private static final Pattern ERR = Pattern.compile(
            "(/ERROR\\]|\\[ERROR\\]|/FATAL\\]|SEVERE|Exception|Caused by:|^\\s+at |\\[ERROR\\]|Ошибка|ERROR:)");
    private static final Pattern WARN = Pattern.compile("(/WARN\\]|\\[WARN|WARNING|Warning:)");
    private static final Pattern JOIN = Pattern.compile("( joined the game| left the game| logged in with entity id|lost connection:)");
    private static final Pattern CHAT = Pattern.compile("(\\]: (<[A-Za-z0-9_]{2,16}>|\\[Server\\]|\\[Rcon\\])|\\[СЕРВЕР\\])");
    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final TextArea area;
    private final ListView<String> list = new ListView<>();
    private final ObservableList<String> lines = FXCollections.observableArrayList();
    private final Supplier<Collection<String>> players;
    private final NickHandler nickHandler;
    private volatile boolean dirty = true;
    /** Отложенная пересборка: 100 мс после первой новой строки — пачка строк собирается за раз. */
    private final PauseTransition pending = new PauseTransition(Duration.millis(100));
    /** Ники в нижнем регистре — для быстрого поиска при отрисовке строки. */
    private Set<String> nicksLower = Set.of();

    private ConsoleView(TextArea area, Supplier<Collection<String>> players, NickHandler nickHandler) {
        this.area = area;
        this.players = players;
        this.nickHandler = nickHandler;
    }

    public static ConsoleView attach(TextArea area, Supplier<Collection<String>> players, NickHandler nickHandler) {
        ConsoleView v = new ConsoleView(area, players, nickHandler);
        v.install();
        return v;
    }

    private void install() {
        if (!(area.getParent() instanceof Pane pane)) return;
        int idx = pane.getChildren().indexOf(area);
        list.setItems(lines);
        list.getStyleClass().add("console-view");
        list.setPrefHeight(Math.max(260, area.getPrefRowCount() * 21));
        list.setFocusTraversable(true);
        list.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        list.setCellFactory(v -> new LineCell());
        pane.getChildren().add(idx + 1, list);
        area.setVisible(false);
        area.setManaged(false);

        MenuItem copy = new MenuItem("Копировать выделенное");
        copy.setOnAction(e -> copySelection());
        MenuItem copyAll = new MenuItem("Копировать всю консоль");
        copyAll.setOnAction(e -> copy(area.getText()));
        list.setContextMenu(new ContextMenu(copy, copyAll));
        KeyCombination ctrlC = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
        list.setOnKeyPressed(e -> {
            if (ctrlC.match(e)) { copySelection(); e.consume(); }
        });

        // Раньше здесь был AnimationTimer: он тикал каждый кадр всё время работы лаунчера, даже
        // на чужом экране и со свёрнутым окном, и не давал JavaFX уснуть между кадрами (16.10.2026,
        // разбор, почему открытый лаунчер мешал Blender). Теперь пересборка заводится изменением
        // текста, а пока консоль спрятана — ждёт её показа.
        pending.setOnFinished(e -> {
            if (dirty && shown()) {
                dirty = false;
                rebuild();
            }
        });
        area.textProperty().addListener((o, a, b) -> {
            dirty = true;
            schedule();
        });
        for (Parent p = list.getParent(); p != null; p = p.getParent()) {
            p.visibleProperty().addListener((o, was, now) -> { if (now) schedule(); });
        }
        list.visibleProperty().addListener((o, was, now) -> { if (now) schedule(); });
    }

    private void schedule() {
        if (dirty && pending.getStatus() != Animation.Status.RUNNING && shown()) pending.playFromStart();
    }

    /** Видна ли консоль на самом деле: экраны переключаются setVisible у предков. */
    private boolean shown() {
        for (Node n = list; n != null; n = n.getParent()) if (!n.isVisible()) return false;
        return list.getScene() != null;
    }

    private void rebuild() {
        boolean follow = atBottom();
        Collection<String> p = players.get();
        Set<String> lower = new HashSet<>();
        if (p != null) for (String n : p) if (n != null) lower.add(n.toLowerCase(Locale.ROOT));
        nicksLower = lower;
        String text = area.getText();
        List<String> next = text.isEmpty() ? List.of() : new ArrayList<>(Arrays.asList(text.split("\n", -1)));
        if (!next.isEmpty() && next.get(next.size() - 1).isEmpty()) next.remove(next.size() - 1);
        lines.setAll(next);
        if (follow && !lines.isEmpty()) list.scrollTo(lines.size() - 1);
    }

    private boolean atBottom() {
        for (Node n : list.lookupAll(".scroll-bar")) {
            if (n instanceof ScrollBar sb && sb.getOrientation() == Orientation.VERTICAL && sb.isVisible()) {
                return sb.getValue() >= sb.getMax() - 0.001;
            }
        }
        return true;
    }

    private void copySelection() {
        List<String> sel = new ArrayList<>(list.getSelectionModel().getSelectedItems());
        if (!sel.isEmpty()) copy(String.join("\n", sel));
    }

    private static void copy(String s) {
        ClipboardContent c = new ClipboardContent();
        c.putString(s);
        Clipboard.getSystemClipboard().setContent(c);
    }

    /** Вид строки: err, warn, join, chat или обычная. Чистая функция — проверяется без окна. */
    public static String kind(String line) {
        if (JOIN.matcher(line).find()) return "join";
        if (CHAT.matcher(line).find()) return "chat";
        if (ERR.matcher(line).find()) return "err";
        if (WARN.matcher(line).find()) return "warn";
        return "";
    }

    private final class LineCell extends ListCell<String> {
        private final TextFlow flow = new TextFlow();

        LineCell() {
            flow.maxWidthProperty().bind(list.widthProperty().subtract(30));
            flow.prefWidthProperty().bind(list.widthProperty().subtract(30));
            setPrefWidth(0);
        }

        @Override
        protected void updateItem(String line, boolean empty) {
            super.updateItem(line, empty);
            if (empty || line == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            String kind = kind(line);
            flow.getChildren().clear();
            Matcher m = WORD.matcher(line);
            int pos = 0;
            while (m.find()) {
                String w = m.group();
                if (!nicksLower.contains(w.toLowerCase(Locale.ROOT))) continue;
                if (m.start() > pos) flow.getChildren().add(text(line.substring(pos, m.start()), kind));
                Text nick = text(w, kind);
                nick.getStyleClass().add("console-nick");
                nick.setCursor(Cursor.HAND);
                nick.setOnMouseClicked(e -> {
                    if (e.getButton() == MouseButton.PRIMARY && nickHandler != null) {
                        nickHandler.onNick(w, nick, e.getScreenX(), e.getScreenY());
                        e.consume();
                    }
                });
                flow.getChildren().add(nick);
                pos = m.end();
            }
            if (pos < line.length()) flow.getChildren().add(text(line.substring(pos), kind));
            setText(null);
            setGraphic(flow);
        }

        private Text text(String s, String kind) {
            Text t = new Text(s);
            t.getStyleClass().add("console-line");
            if (!kind.isEmpty()) t.getStyleClass().add("console-" + kind);
            return t;
        }
    }
}
