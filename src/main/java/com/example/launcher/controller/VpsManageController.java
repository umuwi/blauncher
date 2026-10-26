package com.example.launcher.controller;

import com.example.launcher.model.ServerEntry;
import com.example.launcher.service.vps.Rcon;
import com.example.launcher.service.vps.VpsException;
import com.example.launcher.service.vps.VpsServerManager;
import com.example.launcher.service.vps.VpsSession;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Управление установленным сервером панели VPS (этап 3): статус и нагрузка, старт/стоп, консоль,
 * игроки, файлы, бэкапы. Логика — в {@link VpsServerManager}; здесь UI и потоки.
 *
 * <p>Консоль и кнопка «Запустить/Остановить» — общие с локальным сервером (consolePanel из
 * main.fxml, MainController отдаёт их через {@link Host}); панели «Нагрузка», «Игроки», «Файлы» и
 * «Бэкапы» строятся здесь и вставляются в экран сервера после консоли.
 *
 * <p>На открытый экран держится одно SSH-подключение: опрос статуса раз в 3 с, поток журнала для
 * консоли и, пока сервер работает, RCON через туннель. Уход с экрана ({@link #close}) закрывает всё.
 */
public class VpsManageController {

    public interface Host {
        void runInBackground(Runnable task);
        void showToast(String message, String type);
        void log(String line);
        Window window();
        void saveServers();
        /** Перерисовать шапку экрана сервера, состояние консоли и списки серверов. */
        void refreshServerViews();
        boolean isFocused(String serverId);
        void consoleClear();
        void consoleAppend(String line);
        /** Подключение по сохранённому ключу (VpsPanelController.openSession). Из фонового потока. */
        VpsSession openSession(ServerEntry s) throws VpsException;
        String rconPassword(ServerEntry s) throws IOException;
        /** Уведомление Windows, если окно лаунчера не в фокусе (сервер упал, кто-то зашёл). */
        default void notifyDesktop(String title, String text, boolean error) {}
        /** Строка интерфейса на языке лаунчера (блок «Обслуживание», VpsUpkeepPanel). */
        default String tr(String key) { return key; }
    }

    private static final ButtonType CANCEL = new ButtonType("Отмена", ButtonBar.ButtonData.CANCEL_CLOSE);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final int CONSOLE_BACKLOG = 300;

    private final Host host;
    private final VpsServerManager manager = new VpsServerManager();

    // ---- открытый сервер
    private ServerEntry current;
    /** Растёт при каждом open/close: ответы фоновых задач от прошлого экрана выбрасываются. */
    private volatile int generation;
    private VpsSession session;
    private VpsSession.Stream console;
    private VpsServerManager.RconLink rcon;
    private VpsServerManager.Status lastStatus;
    private long dirSizeKb = -1;
    private long dirSizeAt;
    private long listsAt;
    private Timeline poller, consoleTicker;
    private final AtomicBoolean pollInFlight = new AtomicBoolean();
    private final ConcurrentLinkedQueue<String> consoleQueue = new ConcurrentLinkedQueue<>();
    /** Долгая операция (бэкап, откат, загрузка) — вторую параллельно не начинаем. */
    private final AtomicBoolean opRunning = new AtomicBoolean();

    // ---- узлы
    private VBox loadPanel, playersPanel, filesPanel, backupsPanel;
    private Label statusLine, cpuValue, ramValue, diskValue, playersValue, loadValue;
    private Label playersHint, onlineTitle, opStatus;
    private VBox onlineList, whitelistList, opsList, bansList;
    private CheckBox whitelistCheck;
    private TextField playerField;
    private Label pathLabel, filesStatus;
    private VBox filesList;
    private String filesDir = "";
    private Label backupsStatus;
    private VBox backupsList;
    private boolean updatingWhitelistCheck;

    public VpsManageController(Host host) {
        this.host = host;
    }

    // ================================================================ разметка

    /** Вставляет панели этапа 3 в экран сервера сразу после консоли. */
    public void wire(VBox screen, Node consolePanel) {
        upkeep = new VpsUpkeepPanel(host, manager);
        buildLoadPanel();
        buildPlayersPanel();
        buildFilesPanel();
        buildBackupsPanel();
        int at = screen.getChildren().indexOf(consolePanel) + 1;
        screen.getChildren().addAll(at, List.of(loadPanel, upkeep.panel(), playersPanel, filesPanel, backupsPanel));
        showPanels(false);
    }

    /** «Обслуживание»: память и ядра, плановый перезапуск, сторож VPS (26.10.2026). */
    private VpsUpkeepPanel upkeep;

    private void showPanels(boolean visible) {
        for (Node p : List.of(loadPanel, upkeep.panel(), playersPanel, filesPanel, backupsPanel)) show(p, visible);
    }

    private static VBox panel(String title, String sub) {
        Label t = new Label(title);
        t.getStyleClass().add("panel-title");
        VBox box = new VBox(10, t);
        if (sub != null) {
            Label s = new Label(sub);
            s.getStyleClass().add("toggle-sub");
            s.setWrapText(true);
            box.getChildren().add(s);
        }
        box.getStyleClass().add("panel");
        return box;
    }

    private static Label sub(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("toggle-sub");
        l.setWrapText(true);
        return l;
    }

    private static Button button(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("server-action");
        b.setOnAction(e -> action.run());
        return b;
    }

    /** Плитка показателя: подпись сверху, значение крупно. */
    private static VBox stat(String caption, Label value) {
        Label c = new Label(caption);
        c.getStyleClass().add("select-label");
        value.getStyleClass().add("panel-title");
        VBox box = new VBox(4, c, value);
        box.setMinWidth(150);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private void buildLoadPanel() {
        loadPanel = panel("Сервер на VPS", null);
        statusLine = sub("");
        cpuValue = new Label("—");
        ramValue = new Label("—");
        diskValue = new Label("—");
        playersValue = new Label("—");
        loadValue = new Label("—");
        HBox stats = new HBox(16, stat("Процессор", cpuValue), stat("Память сервера", ramValue),
                stat("Диск", diskValue), stat("Игроки", playersValue));
        Label loadCaption = sub("");
        loadCaption.textProperty().bind(loadValue.textProperty());
        Button restart = button("Перезапустить", this::onRestart);
        HBox actions = new HBox(8, restart);
        actions.setAlignment(Pos.CENTER_LEFT);
        loadPanel.getChildren().addAll(statusLine, stats, loadCaption, upkeep.warning(), actions);
    }

    private void buildPlayersPanel() {
        playersPanel = panel("Игроки", null);
        playersHint = sub("");
        onlineTitle = new Label("Онлайн");
        onlineTitle.getStyleClass().add("field-label");
        onlineList = new VBox(4);

        playerField = new TextField();
        playerField.setPromptText("Ник игрока");
        playerField.getStyleClass().addAll("text-input", "mono");
        HBox.setHgrow(playerField, Priority.ALWAYS);
        HBox addRow = new HBox(8, playerField,
                button("В вайтлист", () -> actionFromField(VpsServerManager.PlayerAction.WHITELIST_ADD)),
                button("Сделать оператором", () -> actionFromField(VpsServerManager.PlayerAction.OP)),
                button("Забанить", () -> actionFromField(VpsServerManager.PlayerAction.BAN)));
        addRow.setAlignment(Pos.CENTER_LEFT);

        whitelistCheck = new CheckBox("Вайтлист включён — зайти могут только игроки из списка");
        whitelistCheck.selectedProperty().addListener((o, was, now) -> {
            if (!updatingWhitelistCheck) onWhitelistToggle(now);
        });
        whitelistList = new VBox(4);
        opsList = new VBox(4);
        bansList = new VBox(4);
        opStatus = sub("");

        HBox columns = new HBox(16,
                column("Вайтлист", whitelistList), column("Операторы", opsList), column("Баны", bansList));
        playersPanel.getChildren().addAll(playersHint, onlineTitle, onlineList, addRow, whitelistCheck, columns, opStatus);
    }

    private static VBox column(String title, VBox list) {
        Label l = new Label(title);
        l.getStyleClass().add("field-label");
        VBox box = new VBox(6, l, list);
        box.setMinWidth(180);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private void buildFilesPanel() {
        filesPanel = panel("Файлы", "Папка сервера на VPS. Плагины — это .jar в папке plugins, моды — в mods; "
                + "после загрузки перезапустите сервер.");
        pathLabel = new Label("/");
        pathLabel.getStyleClass().addAll("toggle-sub", "mono");
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox bar = new HBox(8, pathLabel, grow,
                button("Вверх", this::onFilesUp),
                button("Обновить", this::refreshFiles),
                button("Новая папка", this::onNewFolder),
                button("Загрузить файлы…", this::onUpload));
        bar.setAlignment(Pos.CENTER_LEFT);
        filesList = new VBox(2);
        ScrollPane scroll = new ScrollPane(filesList);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(300);
        scroll.getStyleClass().add("screen-scroll");
        filesStatus = sub("");
        filesPanel.getChildren().addAll(bar, scroll, filesStatus);
    }

    private void buildBackupsPanel() {
        backupsPanel = panel("Бэкапы", "Копия всей папки сервера (мир, настройки, моды, плагины) хранится на VPS в "
                + "/opt/blauncher/backups. Пока сервер работает, бэкап делается с паузой автосохранения, без остановки.");
        backupsStatus = sub("");
        backupsList = new VBox(4);
        Button create = button("Создать бэкап", this::onCreateBackup);
        create.getStyleClass().setAll("button", "play-btn");
        autoBackupCheck = new CheckBox("Автобэкап каждую ночь (4:00 МСК), хранить последние "
                + VpsServerManager.AUTO_BACKUP_KEEP);
        autoBackupCheck.setDisable(true);
        autoBackupCheck.selectedProperty().addListener((o, was, now) -> {
            if (!updatingAutoBackup) onToggleAutoBackup(now);
        });
        Label autoNote = sub("Делает сам VPS — работает, даже когда ваш ПК выключен. Ручные бэкапы при этом не удаляются.");
        backupsPanel.getChildren().addAll(create, autoBackupCheck, autoNote, backupsStatus, backupsList);
    }

    private CheckBox autoBackupCheck;
    private boolean updatingAutoBackup;

    private void refreshAutoBackupNow(int gen) {
        ServerEntry s = current;
        if (s == null || gen != generation) return;
        try {
            boolean on = manager.autoBackupEnabled(session(gen), srv(s));
            Platform.runLater(() -> {
                if (gen != generation) return;
                updatingAutoBackup = true;
                autoBackupCheck.setSelected(on);
                updatingAutoBackup = false;
                autoBackupCheck.setDisable(false);
            });
        } catch (VpsException | RuntimeException e) {
            // не узнали — переключатель остаётся неактивным, список бэкапов скажет, что со связью
        }
    }

    private void onToggleAutoBackup(boolean on) {
        ServerEntry s = current;
        if (s == null) return;
        int gen = generation;
        autoBackupCheck.setDisable(true);
        backupsStatus.setText(on ? "Включаю автобэкап…" : "Выключаю автобэкап…");
        host.runInBackground(() -> {
            try {
                manager.setAutoBackup(session(gen), srv(s), on);
                Platform.runLater(() -> {
                    backupsStatus.setText(on ? "Автобэкап включён: первый — этой ночью." : "Автобэкап выключен.");
                    host.log("[VPS] «" + s.getName() + "»: автобэкап " + (on ? "включён" : "выключен"));
                });
            } catch (VpsException | RuntimeException e) {
                Platform.runLater(() -> {
                    backupsStatus.setText("Не получилось: " + e.getMessage());
                    updatingAutoBackup = true;
                    autoBackupCheck.setSelected(!on);
                    updatingAutoBackup = false;
                });
            } finally {
                Platform.runLater(() -> autoBackupCheck.setDisable(false));
            }
        });
    }

    // ================================================================ жизненный цикл экрана

    /** Экран сервера открыт на установленном SSH-сервере. */
    /**
     * Ники, которые сервер знает: сначала те, кто сейчас онлайн, потом операторы, вайтлист, баны.
     * Нужны подсказке в строке консоли (CommandAssist) — дописывать ник после двух букв.
     */
    private final java.util.LinkedHashSet<String> knownOnline = new java.util.LinkedHashSet<>();
    private final java.util.LinkedHashSet<String> knownListed = new java.util.LinkedHashSet<>();

    public List<String> knownPlayers() {
        java.util.LinkedHashSet<String> all = new java.util.LinkedHashSet<>(knownOnline);
        all.addAll(knownListed);
        return new java.util.ArrayList<>(all);
    }

    /** Первый ответ RCON со списком онлайна уже был — иначе при открытии экрана каждый игрок «заходил бы». */
    private boolean seenOnlineOnce;

    /** «Рестарт через минуту» из быстрых кнопок консоли — тот же перезапуск, что кнопкой «Перезапустить». */
    public void restartNow(ServerEntry s) {
        if (isOpenFor(s)) onRestart();
    }

    /**
     * Команда без эха «> команда» и без вывода ответа — для сообщений «[СЕРВЕР]» (в консоль идёт
     * {@code echo} вместо JSON tellraw) и предупреждений перед рестартом.
     */
    public void sendQuiet(ServerEntry s, String command, String echo) {
        if (!isOpenFor(s)) return;
        if (echo != null) consoleQueue.add(echo);
        int gen = generation;
        host.runInBackground(() -> {
            try {
                commandSync(gen, command);
            } catch (VpsException | RuntimeException e) {
                consoleQueue.add("[BLauncher] Не отправлено: " + e.getMessage());
            }
        });
    }

    public void open(ServerEntry s) {
        close();
        seenOnlineOnce = false;
        knownOnline.clear();
        knownListed.clear();
        current = s;
        int gen = ++generation;
        lastStatus = null;
        dirSizeKb = -1;
        dirSizeAt = 0;
        listsAt = 0;
        filesDir = "";
        consoleQueue.clear();
        showPanels(true);
        statusLine.setText("Подключаюсь…");
        for (Label l : List.of(cpuValue, ramValue, diskValue, playersValue)) l.setText("—");
        loadValue.setText("");
        onlineList.getChildren().clear();
        filesList.getChildren().clear();
        backupsList.getChildren().clear();
        if (autoBackupCheck != null) autoBackupCheck.setDisable(true);   // пока не узнали, что на этом VPS
        host.consoleClear();
        if (!s.isRunning()) s.setPending("Проверяю…");
        host.refreshServerViews();

        // Два таймера, а не два кадра одного: цикл Timeline равен её самому позднему кадру, и кадр на
        // 150 мс в одной Timeline с кадром на 3 с срабатывал раз в 3 с — ответы команд «застревали».
        poller = new Timeline(new KeyFrame(Duration.seconds(3), e -> poll(gen)));
        poller.setCycleCount(Timeline.INDEFINITE);
        poller.play();
        consoleTicker = new Timeline(new KeyFrame(Duration.millis(150), e -> drainConsole()));
        consoleTicker.setCycleCount(Timeline.INDEFINITE);
        consoleTicker.play();
        poll(gen);
        upkeep.open(s, () -> session(gen), () -> gen == generation);
        host.runInBackground(() -> {
            if (gen != generation) return;
            refreshFilesNow(gen);
            refreshBackupsNow(gen);
            refreshAutoBackupNow(gen);
        });
    }

    /** Ушли с экрана сервера (или на другой сервер) — закрыть подключение, поток журнала и RCON. */
    public void close() {
        generation++;
        if (poller != null) {
            poller.stop();
            poller = null;
        }
        if (consoleTicker != null) {
            consoleTicker.stop();
            consoleTicker = null;
        }
        ServerEntry was = current;
        current = null;
        if (upkeep != null) upkeep.close();
        if (was != null && "Проверяю…".equals(was.getPending())) was.setPending(null);
        VpsSession.Stream c = console;
        VpsServerManager.RconLink r = rcon;
        VpsSession ses = session;
        console = null;
        rcon = null;
        session = null;
        if (loadPanel != null) showPanels(false);
        if (c != null || r != null || ses != null) {
            host.runInBackground(() -> {
                if (c != null) c.close();
                if (r != null) r.close();
                if (ses != null) ses.close();
            });
        }
    }

    public boolean isOpenFor(ServerEntry s) {
        return s != null && current != null && current.getId().equals(s.getId());
    }

    private final java.util.Map<String, Long> statusCheckedAt = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Статус для списка серверов: одно короткое подключение, не чаще раза в 20 с на сервер (список
     * перерисовывается часто). Открытый экран сервера опрашивается своим poll — его не трогаем.
     */
    public void checkStatusInBackground(ServerEntry s, Runnable onChanged) {
        if (s == null || !s.isSsh() || !s.isSshInstalled() || isOpenFor(s)) return;
        long now = System.currentTimeMillis();
        Long last = statusCheckedAt.get(s.getId());
        if (last != null && now - last < 20_000) return;
        statusCheckedAt.put(s.getId(), now);
        host.runInBackground(() -> {
            try (VpsSession ses = host.openSession(s)) {
                VpsServerManager.Status st = manager.status(ses, srv(s));
                Platform.runLater(() -> {
                    boolean changed = !s.isSshStatusKnown() || s.isRunning() != st.running();
                    s.setRunning(st.running());
                    s.setSshStatusKnown(true);
                    // Сторож VPS и для серверов, чей экран не открыт: список проверяет их раз в 20 с.
                    upkeep.onStatus(s, st, false);
                    if (changed) onChanged.run();
                });
            } catch (VpsException | RuntimeException ignored) {
                // VPS недоступен — в списке останется «служба на VPS»; ошибку человек увидит на экране сервера
            }
        });
    }

    // ================================================================ подключение (фоновые потоки)

    private synchronized VpsSession session(int gen) throws VpsException {
        if (gen != generation) throw new VpsException("Экран сервера закрыт");
        if (session == null || !session.isConnected()) {
            if (session != null) session.close();
            session = host.openSession(current);
        }
        return session;
    }

    private static VpsServerManager.Srv srv(ServerEntry s) {
        return new VpsServerManager.Srv(s.getId(), s.getSshServerDir(), s.getSshService(), s.getSshRconPort());
    }

    /** RCON к работающему серверу; null — сервер не работает или RCON ещё не поднялся. */
    private synchronized Rcon rcon(int gen) throws VpsException {
        if (rcon != null && rcon.isOpen()) return rcon.rcon;
        if (rcon != null) {
            rcon.close();
            rcon = null;
        }
        if (lastStatus == null || !lastStatus.running()) return null;
        String pw;
        try {
            pw = host.rconPassword(current);
        } catch (IOException e) {
            throw new VpsException("Пароль RCON не читается: " + e.getMessage(), e);
        }
        rcon = manager.openRcon(session(gen), srv(current), pw);
        return rcon.rcon;
    }

    private synchronized void dropRcon() {
        if (rcon != null) {
            rcon.close();
            rcon = null;
        }
    }

    // ================================================================ опрос статуса и консоль

    private void poll(int gen) {
        ServerEntry s = current;
        if (s == null || gen != generation || !pollInFlight.compareAndSet(false, true)) return;
        host.runInBackground(() -> {
            try {
                VpsSession ses = session(gen);
                VpsServerManager.Srv srv = srv(s);
                VpsServerManager.Status st = manager.status(ses, srv);
                long now = System.currentTimeMillis();
                long dir = -1;
                if (now - dirSizeAt > 30_000) {
                    dir = manager.serverDirSizeKb(ses, srv);
                    dirSizeAt = now;
                }
                synchronized (this) {
                    if (console == null || !console.isOpen()) openConsole(gen, ses, srv);
                }
                VpsServerManager.Status prev = lastStatus;
                lastStatus = st;
                VpsServerManager.Online online = null;
                if (st.running()) {
                    try {
                        Rcon r = rcon(gen);
                        if (r != null) online = manager.online(r);
                    } catch (VpsException e) {
                        dropRcon();   // сервер ещё стартует — RCON поднимется позже
                    }
                } else {
                    dropRcon();
                }
                VpsServerManager.PlayerLists lists = null;
                if (now - listsAt > 15_000) {
                    lists = manager.playerLists(ses, srv);
                    listsAt = now;
                }
                long dirKb = dir;
                VpsServerManager.Online onl = online;
                VpsServerManager.PlayerLists pl = lists;
                Platform.runLater(() -> {
                    if (gen != generation) return;
                    if (dirKb >= 0) dirSizeKb = dirKb;
                    applyStatus(s, prev, st, onl);
                    if (pl != null) applyLists(pl);
                });
            } catch (VpsException | RuntimeException e) {
                Platform.runLater(() -> {
                    if (gen != generation) return;
                    statusLine.setText("Нет связи с VPS: " + e.getMessage() + " — повторю через 3 с");
                    if ("Проверяю…".equals(s.getPending())) {
                        s.setPending(null);
                        host.refreshServerViews();
                    }
                });
                synchronized (this) {
                    if (session != null && !session.isConnected()) session = null;
                }
            } finally {
                pollInFlight.set(false);
            }
        });
    }

    private void openConsole(int gen, VpsSession ses, VpsServerManager.Srv srv) throws VpsException {
        console = manager.console(ses, srv, CONSOLE_BACKLOG, consoleQueue::add, () -> {
            if (gen == generation) consoleQueue.add("[BLauncher] Поток консоли прервался — переподключаюсь…");
        });
    }

    /** Строки консоли копятся в очереди и уходят в TextArea пачкой раз в 150 мс: на старте Forge
     *  их сотни в секунду, и отдельный runLater на каждую заметно подвешивал окно. */
    private void drainConsole() {
        String line;
        int n = 0;
        while (n < 2000 && (line = consoleQueue.poll()) != null) {
            host.consoleAppend(line);
            n++;
        }
    }

    private void applyStatus(ServerEntry s, VpsServerManager.Status prev, VpsServerManager.Status st, VpsServerManager.Online online) {
        // Падение: счётчик перезапусков systemd вырос или служба ушла в failed. Сравниваем с
        // прошлым опросом этого же экрана — при открытии (prev == null) старые падения не шумят.
        if (prev != null && (st.restarts() > prev.restarts() || ("failed".equals(st.activeState()) && !"failed".equals(prev.activeState())))) {
            host.notifyDesktop("Сервер «" + s.getName() + "» упал",
                    st.running() ? "systemd уже поднял его заново." : "Откройте лаунчер — в консоли причина.", true);
        }
        if (online != null && st.running()) {
            for (String n : online.names()) {
                if (seenOnlineOnce && !knownOnline.contains(n)) {
                    host.notifyDesktop("«" + s.getName() + "»: зашёл " + n, "Сейчас онлайн: " + online.count(), false);
                }
            }
            seenOnlineOnce = true;
        }
        boolean changed = s.isRunning() != st.running() || !java.util.Objects.equals(s.getPending(), pendingFor(st));
        s.setRunning(st.running());
        s.setPending(pendingFor(st));
        s.setSshStatusKnown(true);
        statusLine.setText(st.label() + (st.restarts() > 0 ? " · перезапусков после падений: " + st.restarts() : "")
                + " · служба " + s.getSshService());
        double cpu = VpsServerManager.cpuPercent(prev, st);
        cpuValue.setText(!st.running() ? "—" : cpu < 0 ? (st.cpuNs() < 0 ? "нет данных" : "…") : String.format("%.0f %%", cpu));
        ramValue.setText(st.memoryBytes() < 0 || !st.running() ? "—"
                : mb(st.memoryBytes() / 1024) + " из " + VpsPanelController.ramText(s.getRamMb() == null ? 0 : s.getRamMb()));
        diskValue.setText((dirSizeKb >= 0 ? mb(dirSizeKb) + " · " : "") + "свободно " + mb(st.diskAvailKb()));
        playersValue.setText(online != null ? online.count() + " из " + online.max() : st.running() ? "…" : "—");
        loadValue.setText("Весь VPS: память занята " + mb(st.memTotalKb() - st.memAvailKb()) + " из " + mb(st.memTotalKb())
                + " · ядер " + st.cpus() + " · средняя нагрузка " + st.load());
        renderOnline(online, st.running());
        upkeep.onStatus(s, st, true);
        playersHint.setText(st.running() ? "" : "Менять списки можно, пока сервер работает (команды идут через RCON). "
                + "Списки ниже — из файлов сервера.");
        show(playersHint, !st.running());
        if (changed) host.refreshServerViews();
    }

    private static String pendingFor(VpsServerManager.Status st) {
        return st.transitioning() ? st.label() : null;
    }

    // ================================================================ старт / стоп / команды

    /** «Запустить/Остановить» из шапки экрана и из списка серверов. */
    public void toggle(ServerEntry s) {
        boolean stop = s.isRunning();
        s.setPending(stop ? "Останавливается…" : "Запускается…");
        host.refreshServerViews();
        int gen = generation;
        boolean focused = isOpenFor(s);
        host.runInBackground(() -> {
            VpsSession temp = null;
            try {
                VpsSession ses = focused ? session(gen) : (temp = host.openSession(s));
                if (stop) manager.stop(ses, srv(s));
                else manager.start(ses, srv(s));
                VpsServerManager.Status st = manager.status(ses, srv(s));
                Platform.runLater(() -> {
                    s.setRunning(st.running() || (!stop && st.transitioning()));
                    s.setPending(stop ? null : pendingFor(st));
                    s.setSshStatusKnown(true);
                    host.saveServers();
                    host.refreshServerViews();
                    host.log("[VPS] «" + s.getName() + "»: " + (stop ? "остановлен" : "запускается"));
                });
            } catch (VpsException | RuntimeException e) {
                Platform.runLater(() -> {
                    s.setPending(null);
                    host.refreshServerViews();
                    host.showToast((stop ? "Не удалось остановить: " : "Не удалось запустить: ") + e.getMessage(), "error");
                });
            } finally {
                if (temp != null) temp.close();
            }
        });
    }

    private void onRestart() {
        ServerEntry s = current;
        if (s == null) return;
        s.setPending("Перезапускается…");
        host.refreshServerViews();
        int gen = generation;
        host.runInBackground(() -> {
            try {
                dropRcon();
                manager.restart(session(gen), srv(s));
                Platform.runLater(() -> host.log("[VPS] «" + s.getName() + "»: перезапуск"));
            } catch (VpsException | RuntimeException e) {
                Platform.runLater(() -> {
                    s.setPending(null);
                    host.refreshServerViews();
                    host.showToast("Не удалось перезапустить: " + e.getMessage(), "error");
                });
            }
        });
    }

    /** Команда из поля консоли. Ответ сервера печатается в консоль сразу под ней. */
    public void sendCommand(ServerEntry s, String command) {
        if (!isOpenFor(s)) return;
        String cmd = command.trim();
        if (cmd.startsWith("/")) cmd = cmd.substring(1);
        if (cmd.isEmpty()) return;
        String c = cmd;
        consoleQueue.add("> " + c);
        int gen = generation;
        host.runInBackground(() -> {
            try {
                String reply = commandSync(gen, c);
                for (String line : reply.split("\n")) if (!line.isBlank()) consoleQueue.add(line);
            } catch (VpsException | RuntimeException e) {
                consoleQueue.add("[BLauncher] Команда не выполнена: " + e.getMessage());
            }
        });
    }

    /** Команда через RCON с ответом — для справки по командам (MainController.sendHelpAndCollect). */
    public String commandSync(ServerEntry s, String command) throws VpsException {
        if (!isOpenFor(s)) throw new VpsException("Экран сервера закрыт");
        return commandSync(generation, command);
    }

    private String commandSync(int gen, String command) throws VpsException {
        Rcon r = rcon(gen);
        if (r == null) throw new VpsException("сервер не запущен");
        try {
            return r.command(command);
        } catch (VpsException e) {
            dropRcon();
            throw e;
        }
    }

    // ================================================================ игроки

    private void renderOnline(VpsServerManager.Online online, boolean running) {
        onlineList.getChildren().clear();
        if (!running) {
            onlineTitle.setText("Онлайн — сервер не запущен");
            return;
        }
        if (online == null) {
            onlineTitle.setText("Онлайн — подключаюсь к RCON…");
            return;
        }
        onlineTitle.setText("Онлайн: " + online.count() + " из " + online.max());
        knownOnline.clear();
        knownOnline.addAll(online.names());
        if (online.names().isEmpty()) onlineList.getChildren().add(sub("Никого нет."));
        for (String name : online.names()) {
            onlineList.getChildren().add(playerRow(name,
                    button("Кикнуть", () -> playerAction(VpsServerManager.PlayerAction.KICK, name)),
                    button("Забанить", () -> playerAction(VpsServerManager.PlayerAction.BAN, name)),
                    button("Оператор", () -> playerAction(VpsServerManager.PlayerAction.OP, name))));
        }
    }

    private void applyLists(VpsServerManager.PlayerLists pl) {
        updatingWhitelistCheck = true;
        whitelistCheck.setSelected(pl.whitelistEnabled());
        updatingWhitelistCheck = false;
        knownListed.clear();
        knownListed.addAll(pl.ops());
        knownListed.addAll(pl.whitelist());
        knownListed.addAll(pl.banned());
        fill(whitelistList, pl.whitelist(), name -> button("Убрать", () -> playerAction(VpsServerManager.PlayerAction.WHITELIST_REMOVE, name)));
        fill(opsList, pl.ops(), name -> button("Снять", () -> playerAction(VpsServerManager.PlayerAction.DEOP, name)));
        fill(bansList, pl.banned(), name -> button("Разбанить", () -> playerAction(VpsServerManager.PlayerAction.PARDON, name)));
    }

    private void fill(VBox list, List<String> names, java.util.function.Function<String, Button> action) {
        list.getChildren().clear();
        if (names.isEmpty()) list.getChildren().add(sub("пусто"));
        for (String n : names) list.getChildren().add(playerRow(n, action.apply(n)));
    }

    private static HBox playerRow(String name, Button... actions) {
        Label l = new Label(name);
        l.getStyleClass().add("mono");
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox row = new HBox(6, l, grow);
        row.getChildren().addAll(actions);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void actionFromField(VpsServerManager.PlayerAction action) {
        String name = playerField.getText() == null ? "" : playerField.getText().trim();
        if (name.isEmpty()) {
            host.showToast("Введите ник игрока", "info");
            return;
        }
        playerAction(action, name);
    }

    private void playerAction(VpsServerManager.PlayerAction action, String name) {
        ServerEntry s = current;
        if (s == null) return;
        if (lastStatus == null || !lastStatus.running()) {
            host.showToast("Сервер не запущен — изменения идут через его консоль (RCON)", "info");
            return;
        }
        int gen = generation;
        opStatus.setText("Выполняю…");
        host.runInBackground(() -> {
            try {
                String reply = manager.playerAction(rcon(gen), action, name);
                VpsServerManager.PlayerLists pl = manager.playerLists(session(gen), srv(s));
                Platform.runLater(() -> {
                    if (gen != generation) return;
                    opStatus.setText(reply.isBlank() ? "Готово." : reply);
                    playerField.clear();
                    applyLists(pl);
                    listsAt = System.currentTimeMillis();
                    host.log("[VPS] «" + s.getName() + "»: " + action.name().toLowerCase() + " " + name);
                });
            } catch (IllegalArgumentException e) {
                Platform.runLater(() -> opStatus.setText(e.getMessage()));
            } catch (VpsException | RuntimeException e) {
                dropRcon();
                Platform.runLater(() -> opStatus.setText("Не получилось: " + e.getMessage()));
            }
        });
    }

    private void onWhitelistToggle(boolean on) {
        ServerEntry s = current;
        if (s == null) return;
        if (lastStatus == null || !lastStatus.running()) {
            updatingWhitelistCheck = true;
            whitelistCheck.setSelected(!on);
            updatingWhitelistCheck = false;
            host.showToast("Вайтлист включается у запущенного сервера", "info");
            return;
        }
        int gen = generation;
        host.runInBackground(() -> {
            try {
                String reply = manager.setWhitelist(rcon(gen), on);
                Platform.runLater(() -> opStatus.setText(reply));
            } catch (VpsException | RuntimeException e) {
                dropRcon();
                Platform.runLater(() -> {
                    opStatus.setText("Не получилось: " + e.getMessage());
                    updatingWhitelistCheck = true;
                    whitelistCheck.setSelected(!on);
                    updatingWhitelistCheck = false;
                });
            }
        });
    }

    // ================================================================ файлы

    private void refreshFiles() {
        int gen = generation;
        if (current == null) return;
        host.runInBackground(() -> refreshFilesNow(gen));
    }

    private void refreshFilesNow(int gen) {
        ServerEntry s = current;
        if (s == null || gen != generation) return;
        String dir = filesDir;
        try {
            List<VpsServerManager.FileItem> items = manager.listFiles(session(gen), srv(s), dir);
            Platform.runLater(() -> {
                if (gen != generation || !dir.equals(filesDir)) return;
                renderFiles(items);
            });
        } catch (VpsException | RuntimeException e) {
            Platform.runLater(() -> {
                if (gen == generation) filesStatus.setText("Не удалось открыть папку: " + e.getMessage());
            });
        }
    }

    private void renderFiles(List<VpsServerManager.FileItem> items) {
        pathLabel.setText("/" + filesDir);
        filesList.getChildren().clear();
        if (items.isEmpty()) filesList.getChildren().add(sub("Папка пуста."));
        for (VpsServerManager.FileItem f : items) {
            FontIcon icon = new FontIcon(f.dir() ? "fth-folder" : "fth-file");
            icon.setIconSize(14);
            icon.getStyleClass().add("mod-avatar-fallback");
            Node name;
            if (f.dir()) {
                Hyperlink link = new Hyperlink(f.name());
                link.getStyleClass().add("all-builds-link");
                link.setOnAction(e -> {
                    filesDir = filesDir.isEmpty() ? f.name() : filesDir + "/" + f.name();
                    refreshFiles();
                });
                name = link;
            } else {
                Label l = new Label(f.name());
                l.getStyleClass().add("mono");
                name = l;
            }
            Label meta = sub((f.dir() ? "" : mb(f.size() / 1024) + " · ") + date(f.mtimeSec()));
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            HBox row = new HBox(8, icon, name, grow, meta);
            row.setAlignment(Pos.CENTER_LEFT);
            String rel = filesDir.isEmpty() ? f.name() : filesDir + "/" + f.name();
            if (!f.dir()) row.getChildren().add(button("Скачать", () -> onDownloadFile(rel, f.name())));
            row.getChildren().add(button("Удалить", () -> onDeleteFile(rel, f.dir())));
            filesList.getChildren().add(row);
        }
    }

    private void onFilesUp() {
        if (filesDir.isEmpty()) return;
        int slash = filesDir.lastIndexOf('/');
        filesDir = slash < 0 ? "" : filesDir.substring(0, slash);
        refreshFiles();
    }

    private void onNewFolder() {
        ServerEntry s = current;
        if (s == null) return;
        TextInputDialog d = new TextInputDialog();
        d.setTitle("Новая папка");
        d.setHeaderText("Имя новой папки в /" + filesDir);
        d.getDialogPane().getButtonTypes().setAll(new ButtonType("Создать", ButtonBar.ButtonData.OK_DONE), CANCEL);
        Optional<String> name = d.showAndWait();
        if (name.isEmpty() || name.get().isBlank()) return;
        int gen = generation;
        String dir = filesDir;
        host.runInBackground(() -> {
            try {
                manager.mkdir(session(gen), srv(s), dir, name.get().trim());
                refreshFilesNow(gen);
            } catch (VpsException | RuntimeException e) {
                Platform.runLater(() -> filesStatus.setText("Не удалось создать папку: " + e.getMessage()));
            }
        });
    }

    private void onUpload() {
        ServerEntry s = current;
        if (s == null) return;
        FileChooser fc = new FileChooser();
        fc.setTitle("Загрузить на сервер в /" + filesDir);
        List<File> files = fc.showOpenMultipleDialog(host.window());
        if (files == null || files.isEmpty()) return;
        if (!opRunning.compareAndSet(false, true)) {
            host.showToast("Дождитесь окончания текущей операции", "info");
            return;
        }
        int gen = generation;
        String dir = filesDir;
        host.runInBackground(() -> {
            try {
                int i = 0;
                for (File f : files) {
                    i++;
                    String head = "Загружаю " + i + "/" + files.size() + ": " + f.getName();
                    long total = f.length();
                    Platform.runLater(() -> filesStatus.setText(head));
                    manager.upload(session(gen), srv(s), dir, f.toPath(),
                            n -> Platform.runLater(() -> filesStatus.setText(head + " — " + percent(n, total))));
                }
                Platform.runLater(() -> {
                    filesStatus.setText("Загружено файлов: " + files.size());
                    host.log("[VPS] «" + s.getName() + "»: загружено в /" + dir + " файлов: " + files.size());
                });
                refreshFilesNow(gen);
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> filesStatus.setText("Загрузка не удалась: " + e.getMessage()));
            } finally {
                opRunning.set(false);
            }
        });
    }

    private void onDownloadFile(String rel, String name) {
        ServerEntry s = current;
        if (s == null) return;
        FileChooser fc = new FileChooser();
        fc.setTitle("Сохранить " + name);
        fc.setInitialFileName(name);
        File to = fc.showSaveDialog(host.window());
        if (to == null) return;
        int gen = generation;
        host.runInBackground(() -> {
            try {
                manager.download(session(gen), srv(s), rel, to.toPath(),
                        n -> Platform.runLater(() -> filesStatus.setText("Скачиваю " + name + ": " + mb(n / 1024))));
                Platform.runLater(() -> filesStatus.setText("Сохранено: " + to.getAbsolutePath()));
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> filesStatus.setText("Не удалось скачать: " + e.getMessage()));
            }
        });
    }

    private void onDeleteFile(String rel, boolean dir) {
        ServerEntry s = current;
        if (s == null) return;
        if (!confirm("Удаление", "Удалить " + (dir ? "папку /" + rel + " со всем содержимым" : "/" + rel)
                + "? Вернуть можно только из бэкапа.", "Удалить")) return;
        int gen = generation;
        host.runInBackground(() -> {
            try {
                manager.delete(session(gen), srv(s), rel);
                Platform.runLater(() -> {
                    filesStatus.setText("Удалено: /" + rel);
                    host.log("[VPS] «" + s.getName() + "»: удалено /" + rel);
                });
                refreshFilesNow(gen);
            } catch (VpsException | RuntimeException e) {
                Platform.runLater(() -> filesStatus.setText("Не удалось удалить: " + e.getMessage()));
            }
        });
    }

    // ================================================================ бэкапы

    private void refreshBackupsNow(int gen) {
        ServerEntry s = current;
        if (s == null || gen != generation) return;
        try {
            List<VpsServerManager.Backup> list = manager.listBackups(session(gen), srv(s));
            Platform.runLater(() -> {
                if (gen == generation) renderBackups(list);
            });
        } catch (VpsException | RuntimeException e) {
            Platform.runLater(() -> {
                if (gen == generation) backupsStatus.setText("Список бэкапов недоступен: " + e.getMessage());
            });
        }
    }

    private void renderBackups(List<VpsServerManager.Backup> list) {
        backupsList.getChildren().clear();
        if (list.isEmpty()) backupsList.getChildren().add(sub("Бэкапов пока нет."));
        for (VpsServerManager.Backup b : list) {
            Label title = new Label(backupDate(b.name()));
            title.getStyleClass().add("field-label");
            Label size = sub(mb(b.size() / 1024));
            Region grow = new Region();
            HBox.setHgrow(grow, Priority.ALWAYS);
            HBox row = new HBox(8, title, size, grow,
                    button("Откатить", () -> onRestore(b)),
                    button("Скачать на ПК", () -> onDownloadBackup(b)),
                    button("Удалить", () -> onDeleteBackup(b)));
            row.setAlignment(Pos.CENTER_LEFT);
            backupsList.getChildren().add(row);
        }
    }

    private void onCreateBackup() {
        ServerEntry s = current;
        if (s == null) return;
        if (!opRunning.compareAndSet(false, true)) {
            host.showToast("Дождитесь окончания текущей операции", "info");
            return;
        }
        int gen = generation;
        backupsStatus.setText("Создаю бэкап…");
        host.runInBackground(() -> {
            try {
                Rcon r = null;
                try {
                    r = rcon(gen);
                } catch (VpsException e) {
                    // RCON недоступен — бэкап всё равно делаем, просто без паузы автосохранения
                }
                VpsServerManager.Backup b = manager.createBackup(session(gen), srv(s), r,
                        p -> Platform.runLater(() -> backupsStatus.setText("Бэкап: " + p)));
                Platform.runLater(() -> {
                    backupsStatus.setText("Готово: " + backupDate(b.name()) + ", " + mb(b.size() / 1024));
                    host.log("[VPS] «" + s.getName() + "»: бэкап " + b.name());
                });
                refreshBackupsNow(gen);
            } catch (VpsException | RuntimeException e) {
                Platform.runLater(() -> backupsStatus.setText("Бэкап не создан: " + e.getMessage()));
            } finally {
                opRunning.set(false);
            }
        });
    }

    private void onRestore(VpsServerManager.Backup b) {
        ServerEntry s = current;
        if (s == null) return;
        if (!confirm("Откат к бэкапу", "Сервер будет остановлен, а вся его папка (мир, настройки, моды) заменена копией от "
                + backupDate(b.name()) + ". Всё, что изменилось после этого, пропадёт. Перед откатом можно создать "
                + "свежий бэкап. Продолжить?", "Откатить")) return;
        if (!opRunning.compareAndSet(false, true)) {
            host.showToast("Дождитесь окончания текущей операции", "info");
            return;
        }
        int gen = generation;
        boolean wasRunning = s.isRunning();
        dropRcon();
        host.runInBackground(() -> {
            try {
                manager.restoreBackup(session(gen), srv(s), b.name(), wasRunning,
                        p -> Platform.runLater(() -> backupsStatus.setText("Откат: " + p)));
                Platform.runLater(() -> {
                    backupsStatus.setText("Откат к " + backupDate(b.name()) + " выполнен" + (wasRunning ? ", сервер запускается" : ""));
                    host.log("[VPS] «" + s.getName() + "»: откат к бэкапу " + b.name());
                    listsAt = 0;
                });
                refreshFilesNow(gen);
            } catch (VpsException | RuntimeException e) {
                Platform.runLater(() -> backupsStatus.setText("Откат не удался: " + e.getMessage()));
            } finally {
                opRunning.set(false);
            }
        });
    }

    private void onDownloadBackup(VpsServerManager.Backup b) {
        ServerEntry s = current;
        if (s == null) return;
        FileChooser fc = new FileChooser();
        fc.setTitle("Сохранить бэкап");
        fc.setInitialFileName(safeFileName(s.getName()) + "-" + b.name());
        File to = fc.showSaveDialog(host.window());
        if (to == null) return;
        if (!opRunning.compareAndSet(false, true)) {
            host.showToast("Дождитесь окончания текущей операции", "info");
            return;
        }
        int gen = generation;
        host.runInBackground(() -> {
            try {
                manager.downloadBackup(session(gen), srv(s), b.name(), to.toPath(),
                        n -> Platform.runLater(() -> backupsStatus.setText("Скачиваю бэкап: " + percent(n, b.size()))));
                Platform.runLater(() -> backupsStatus.setText("Бэкап сохранён: " + to.getAbsolutePath()));
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> backupsStatus.setText("Не удалось скачать бэкап: " + e.getMessage()));
            } finally {
                opRunning.set(false);
            }
        });
    }

    private void onDeleteBackup(VpsServerManager.Backup b) {
        ServerEntry s = current;
        if (s == null) return;
        if (!confirm("Удаление бэкапа", "Удалить бэкап от " + backupDate(b.name()) + "?", "Удалить")) return;
        int gen = generation;
        host.runInBackground(() -> {
            try {
                manager.deleteBackup(session(gen), srv(s), b.name());
                refreshBackupsNow(gen);
            } catch (VpsException | RuntimeException e) {
                Platform.runLater(() -> backupsStatus.setText("Не удалось удалить: " + e.getMessage()));
            }
        });
    }

    // ================================================================ мелочи

    /** Подтверждение без initOwner — с ним тёмная тема делает текст нечитаемым (см. HANDOFF_VPS.md). */
    private static boolean confirm(String title, String text, String okText) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, text);
        a.setTitle(title);
        a.setHeaderText(null);
        ButtonType ok = new ButtonType(okText, ButtonBar.ButtonData.OK_DONE);
        a.getButtonTypes().setAll(ok, CANCEL);
        a.getDialogPane().setMinWidth(480);
        Optional<ButtonType> r = a.showAndWait();
        return r.isPresent() && r.get() == ok;
    }

    static String mb(long kb) {
        if (kb < 0) return "—";
        if (kb < 1024) return kb + " КБ";
        if (kb < 1024 * 1024) return (kb / 1024) + " МБ";
        return String.format("%.1f ГБ", kb / 1048576.0);
    }

    private static String percent(long done, long total) {
        if (total <= 0) return mb(done / 1024);
        return Math.min(100, done * 100 / total) + " % (" + mb(done / 1024) + " из " + mb(total / 1024) + ")";
    }

    private static String date(long epochSec) {
        if (epochSec <= 0) return "";
        return DATE.format(LocalDateTime.ofInstant(Instant.ofEpochSecond(epochSec), ZoneId.systemDefault()));
    }

    /** 20261007-171512.tar.gz → 07.10.2026 17:15 (время VPS). */
    static String backupDate(String name) {
        if (name.length() < 15) return name;
        return name.substring(6, 8) + "." + name.substring(4, 6) + "." + name.substring(0, 4) + " "
                + name.substring(9, 11) + ":" + name.substring(11, 13) + ":" + name.substring(13, 15);
    }

    private static String safeFileName(String s) {
        String t = s == null ? "server" : s.replaceAll("[\\\\/:*?\"<>|\\s]+", "_");
        return t.isEmpty() ? "server" : t;
    }

    private static void show(Node n, boolean visible) {
        n.setVisible(visible);
        n.setManaged(visible);
    }
}
