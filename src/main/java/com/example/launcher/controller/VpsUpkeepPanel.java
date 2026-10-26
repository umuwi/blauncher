package com.example.launcher.controller;

import com.example.launcher.model.ServerEntry;
import com.example.launcher.service.vps.VpsCapacity;
import com.example.launcher.service.vps.VpsException;
import com.example.launcher.service.vps.VpsServerManager;
import com.example.launcher.service.vps.VpsSession;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * «Обслуживание» установленного сервера на VPS (26.10.2026), рядом с панелью «Сервер на VPS»:
 * <ul>
 *   <li>память и ядра — поменять без переустановки (например, перекинуть память между серверами
 *       одного VPS); остаток считается по всем серверам машины, как при установке;</li>
 *   <li>плановый перезапуск раз в сутки с предупреждением игроков в чате — systemd-таймер на VPS,
 *       работает без ПК;</li>
 *   <li>сторож VPS: мало места на диске или свободной памяти на всей машине — строка-предупреждение
 *       в «Сервер на VPS» и уведомление Windows (не чаще раза в 6 часов на машину).</li>
 * </ul>
 * Логика — в {@link VpsServerManager}; подключение берётся у открытого экрана (VpsManageController).
 */
public class VpsUpkeepPanel {

    /** Сессия открытого экрана сервера (VpsManageController.session). Вызывать из фонового потока. */
    interface SessionSource {
        VpsSession get() throws VpsException;
    }

    private static final long NOTIFY_EVERY_MS = 6 * 60 * 60 * 1000L;
    private static final int[] RAM_OPTIONS_MB = {1024, 1536, 2048, 3072, 4096, 6144, 8192, 12288, 16384, 24576, 32768};

    private final VpsManageController.Host host;
    private final VpsServerManager manager;
    /** Когда последний раз предупреждали о машине (host:port|вид) — у нескольких серверов одного VPS одна машина. */
    private static final Map<String, Long> notifiedAt = new ConcurrentHashMap<>();

    private VBox panel, editor;
    private Label limitsValue, limitsNote, status, warning;
    private Button editBtn, applyBtn;
    private ComboBox<Integer> ramCombo, cpuCombo, hourCombo;
    private CheckBox restartCheck;
    private boolean updating;

    private ServerEntry current;
    private SessionSource source;
    private BooleanSupplier alive;
    private int cpuLimit;
    private int maxRam = -1;
    private int cpus = 1;

    VpsUpkeepPanel(VpsManageController.Host host, VpsServerManager manager) {
        this.host = host;
        this.manager = manager;
        build();
    }

    private String tr(String key) {
        return host.tr(key);
    }

    Node panel() { return panel; }

    /** Строка сторожа VPS — VpsManageController ставит её в «Сервер на VPS». */
    Node warning() { return warning; }

    // ================================================================ разметка

    private void build() {
        Label title = new Label(tr("upkeep_title"));
        title.getStyleClass().add("panel-title");

        Label limitsCaption = new Label(tr("upkeep_limits"));
        limitsCaption.getStyleClass().add("select-label");
        limitsValue = new Label("—");
        limitsValue.getStyleClass().add("field-label");
        editBtn = button(tr("upkeep_change"), this::openEditor);
        editBtn.setDisable(true);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox limitsRow = new HBox(10, limitsCaption, limitsValue, gap, editBtn);
        limitsRow.setAlignment(Pos.CENTER_LEFT);

        ramCombo = new ComboBox<>();
        ramCombo.setConverter(converter(mb -> VpsPanelController.ramText(mb)));
        cpuCombo = new ComboBox<>();
        cpuCombo.setConverter(converter(this::cpuText));
        applyBtn = button(tr("upkeep_apply"), this::onApplyLimits);
        applyBtn.getStyleClass().setAll("button", "play-btn");
        Button cancel = button(tr("vsplit_cancel"), () -> show(editor, false));
        HBox editRow = new HBox(8, ramCombo, cpuCombo, applyBtn, cancel);
        editRow.setAlignment(Pos.CENTER_LEFT);
        limitsNote = sub("");
        editor = new VBox(6, editRow, limitsNote);
        show(editor, false);

        restartCheck = new CheckBox(tr("upkeep_restart"));
        hourCombo = new ComboBox<>();
        List<Integer> hours = new ArrayList<>();
        for (int h = 0; h < 24; h++) hours.add(h);
        hourCombo.setItems(FXCollections.observableArrayList(hours));
        hourCombo.setConverter(converter(h -> String.format("%02d:00", h)));
        hourCombo.getSelectionModel().select(Integer.valueOf(5));
        restartCheck.setDisable(true);
        hourCombo.setDisable(true);
        restartCheck.selectedProperty().addListener((o, was, now) -> {
            if (!updating) onRestartChanged();
        });
        hourCombo.valueProperty().addListener((o, was, now) -> {
            if (!updating && restartCheck.isSelected()) onRestartChanged();
        });
        HBox restartRow = new HBox(8, restartCheck, hourCombo);
        restartRow.setAlignment(Pos.CENTER_LEFT);
        Label restartNote = sub(tr("upkeep_restart_note"));

        status = sub("");
        panel = new VBox(10, title, limitsRow, editor, restartRow, restartNote, status);
        panel.getStyleClass().add("panel");

        warning = new Label();
        warning.getStyleClass().add("vps-watch-warning");
        warning.setWrapText(true);
        show(warning, false);
    }

    private static <T> StringConverter<T> converter(java.util.function.Function<T, String> f) {
        return new StringConverter<>() {
            @Override public String toString(T v) { return v == null ? "" : f.apply(v); }
            @Override public T fromString(String s) { return null; }
        };
    }

    private String cpuText(int n) {
        return n == 0 ? tr("vsplit_cpu_all") : tr("vsplit_cpu_n").replace("{n}", String.valueOf(n));
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

    // ================================================================ жизненный цикл

    void open(ServerEntry s, SessionSource source, BooleanSupplier alive) {
        current = s;
        this.source = source;
        this.alive = alive;
        maxRam = -1;
        show(editor, false);
        show(warning, false);
        status.setText("");
        editBtn.setDisable(true);
        updating = true;
        restartCheck.setSelected(false);
        updating = false;
        restartCheck.setDisable(true);
        hourCombo.setDisable(true);
        renderLimits();
        host.runInBackground(this::load);
    }

    void close() {
        current = null;
        source = null;
    }

    private boolean stillOn(ServerEntry s) {
        return s != null && s == current && alive != null && alive.getAsBoolean();
    }

    private void load() {
        ServerEntry s = current;
        SessionSource src = source;
        if (s == null || src == null) return;
        try {
            VpsSession ses = src.get();
            VpsServerManager.Srv srv = srv(s);
            VpsCapacity.Snapshot cap = new VpsCapacity().read(ses);
            int cpu = manager.cpuLimit(ses, srv);
            int hour = manager.restartHour(ses, srv);
            Platform.runLater(() -> {
                if (!stillOn(s)) return;
                maxRam = cap.maxRamFor(s.getId());
                cpus = cap.cpus();
                cpuLimit = cpu;
                renderLimits();
                editBtn.setDisable(false);
                updating = true;
                restartCheck.setSelected(hour >= 0);
                if (hour >= 0) hourCombo.getSelectionModel().select(Integer.valueOf(hour));
                updating = false;
                restartCheck.setDisable(false);
                hourCombo.setDisable(false);
            });
        } catch (VpsException | RuntimeException e) {
            Platform.runLater(() -> {
                if (stillOn(s)) status.setText(tr("upkeep_load_failed").replace("{error}", String.valueOf(e.getMessage())));
            });
        }
    }

    private void renderLimits() {
        ServerEntry s = current;
        if (s == null) return;
        limitsValue.setText(VpsPanelController.ramText(s.getRamMb() == null ? 0 : s.getRamMb()) + " · " + cpuText(cpuLimit));
    }

    private static VpsServerManager.Srv srv(ServerEntry s) {
        return new VpsServerManager.Srv(s.getId(), s.getSshServerDir(), s.getSshService(), s.getSshRconPort());
    }

    // ================================================================ память и ядра

    private void openEditor() {
        ServerEntry s = current;
        if (s == null || maxRam < 0) return;
        int now = s.getRamMb() == null ? 0 : s.getRamMb();
        List<Integer> opts = new ArrayList<>();
        for (int o : RAM_OPTIONS_MB) if (o <= maxRam) opts.add(o);
        if (now > 0 && now <= maxRam && !opts.contains(now)) opts.add(now);
        opts.sort(null);
        ramCombo.setItems(FXCollections.observableArrayList(opts));
        ramCombo.getSelectionModel().select(opts.contains(now) ? Integer.valueOf(now) : opts.isEmpty() ? null : opts.get(opts.size() - 1));
        List<Integer> cores = new ArrayList<>(List.of(0));
        for (int n = 1; n < cpus; n++) cores.add(n);
        cpuCombo.setItems(FXCollections.observableArrayList(cores));
        cpuCombo.getSelectionModel().select(cores.contains(cpuLimit) ? Integer.valueOf(cpuLimit) : Integer.valueOf(0));
        show(cpuCombo, cpus > 1);
        limitsNote.setText(tr("upkeep_limits_note").replace("{max}", VpsCapacity.mbText(maxRam)));
        show(editor, true);
    }

    private void onApplyLimits() {
        ServerEntry s = current;
        SessionSource src = source;
        Integer ram = ramCombo.getValue();
        int cpu = cpuCombo.getValue() == null ? 0 : cpuCombo.getValue();
        if (s == null || src == null || ram == null) return;
        applyBtn.setDisable(true);
        editBtn.setDisable(true);
        status.setText(tr("upkeep_applying"));
        host.runInBackground(() -> {
            try {
                manager.setLimits(src.get(), srv(s), ram, cpu, true);
                Platform.runLater(() -> {
                    s.setRamMb(ram);
                    host.saveServers();
                    host.refreshServerViews();
                    host.log("[VPS] «" + s.getName() + "»: память " + ram + " МБ, ядра: " + (cpu == 0 ? "все" : cpu));
                    if (!stillOn(s)) return;
                    cpuLimit = cpu;
                    renderLimits();
                    show(editor, false);
                    status.setText(tr("upkeep_applied"));
                });
            } catch (VpsException | RuntimeException e) {
                Platform.runLater(() -> {
                    if (stillOn(s)) status.setText(tr("upkeep_failed").replace("{error}", String.valueOf(e.getMessage())));
                });
            } finally {
                Platform.runLater(() -> {
                    applyBtn.setDisable(false);
                    editBtn.setDisable(false);
                });
                if (s == current) load();   // перечитать остаток памяти и ограничение ядер
            }
        });
    }

    // ================================================================ плановый перезапуск

    private void onRestartChanged() {
        ServerEntry s = current;
        SessionSource src = source;
        if (s == null || src == null) return;
        boolean on = restartCheck.isSelected();
        int hour = hourCombo.getValue() == null ? 5 : hourCombo.getValue();
        restartCheck.setDisable(true);
        hourCombo.setDisable(true);
        status.setText(on ? tr("upkeep_restart_enabling") : tr("upkeep_restart_disabling"));
        host.runInBackground(() -> {
            try {
                manager.setRestartSchedule(src.get(), srv(s), on ? hour : -1, ZoneId.systemDefault());
                Platform.runLater(() -> {
                    host.log("[VPS] «" + s.getName() + "»: плановый перезапуск " + (on ? String.format("в %02d:00", hour) : "выключен"));
                    if (stillOn(s)) status.setText(on ? tr("upkeep_restart_on").replace("{time}", String.format("%02d:00", hour))
                            : tr("upkeep_restart_off"));
                });
            } catch (VpsException | RuntimeException e) {
                Platform.runLater(() -> {
                    if (!stillOn(s)) return;
                    status.setText(tr("upkeep_failed").replace("{error}", String.valueOf(e.getMessage())));
                    updating = true;
                    restartCheck.setSelected(!on);
                    updating = false;
                });
            } finally {
                Platform.runLater(() -> {
                    restartCheck.setDisable(false);
                    hourCombo.setDisable(false);
                });
            }
        });
    }

    // ================================================================ сторож VPS

    /** Беда со всей машиной: kind — «disk» или «mem» (для частоты уведомлений), text — что показать. */
    record Problem(String kind, String text) {}

    /**
     * Что не так со всей машиной: null — всё в порядке. Пороги — 5 % от размера VPS, но не меньше
     * 1 ГБ диска и 256 МБ памяти (случай 04.10.2026: диск blauncher.ru на 9,8 ГБ заполнился до 100 %).
     */
    Problem vpsProblem(VpsServerManager.Status st) {
        if (st.diskTotalKb() > 0 && st.diskAvailKb() >= 0
                && st.diskAvailKb() < Math.max(1024L * 1024, st.diskTotalKb() / 20)) {
            return new Problem("disk", tr("upkeep_watch_disk").replace("{free}", VpsManageController.mb(st.diskAvailKb()))
                    .replace("{total}", VpsManageController.mb(st.diskTotalKb())));
        }
        if (st.memTotalKb() > 0 && st.memAvailKb() >= 0
                && st.memAvailKb() < Math.max(256L * 1024, st.memTotalKb() / 20)) {
            return new Problem("mem", tr("upkeep_watch_mem").replace("{free}", VpsManageController.mb(st.memAvailKb()))
                    .replace("{total}", VpsManageController.mb(st.memTotalKb())));
        }
        return null;
    }

    /**
     * Свежий статус: на открытом экране — строка-предупреждение; везде — уведомление Windows, если
     * окно лаунчера не в фокусе (это решает host.notifyDesktop), не чаще раза в 6 часов на машину.
     */
    void onStatus(ServerEntry s, VpsServerManager.Status st, boolean onScreen) {
        Problem problem = vpsProblem(st);
        if (onScreen && s == current) {
            warning.setText(problem == null ? "" : problem.text());
            show(warning, problem != null);
        }
        if (problem == null) return;
        String key = (s.getHost() == null ? "" : s.getHost().toLowerCase()) + ":" + s.getSshPort() + "|" + problem.kind();
        long now = System.currentTimeMillis();
        Long last = notifiedAt.get(key);
        if (last != null && now - last < NOTIFY_EVERY_MS) return;
        notifiedAt.put(key, now);
        host.notifyDesktop(tr("upkeep_watch_title").replace("{name}", s.getName()), problem.text(), true);
    }

    private static void show(Node n, boolean visible) {
        n.setVisible(visible);
        n.setManaged(visible);
    }
}
