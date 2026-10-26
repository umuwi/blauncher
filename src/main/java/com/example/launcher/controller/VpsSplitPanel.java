package com.example.launcher.controller;

import com.example.launcher.model.ServerEntry;
import com.example.launcher.service.vps.SecretStore;
import com.example.launcher.service.vps.VpsCapacity;
import com.example.launcher.service.vps.VpsException;
import com.example.launcher.service.vps.VpsInstaller;
import com.example.launcher.service.vps.VpsSession;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * «Серверы на этом VPS» (26.10.2026): один купленный VPS делится на несколько Minecraft-серверов.
 *
 * <p>Каждый сервер — обычная запись {@link ServerEntry} со своей службой, папкой, портом и RCON, так
 * что экран сервера, консоль, игроки, файлы и бэкапы работают для него без изменений. Общая у них
 * только машина: адрес, отпечаток хоста и SSH-ключ (копия ключа лежит под именем каждой записи,
 * поэтому удаление одной записи не ломает остальные — см. VpsPanelController.deleteServer).
 *
 * <p>Здесь — блок внутри «Подключение по SSH»: полоса памяти VPS (кому сколько отдано и сколько
 * свободно), короткий список серверов машины и «+ Ещё сервер». Память и порты читаются с самого
 * VPS ({@link VpsCapacity}): серверы, поставленные с другого ПК, тоже видны и тоже учитываются.
 * Держится в стороне от MainController по образцу {@link PluginsPanelController}.
 */
public class VpsSplitPanel {

    /** Меньше этого второй сервер не предлагаем: Minecraft на 512 МБ не живёт. */
    static final int MIN_SERVER_MB = 1024;
    /** Снимок свежее этого не перечитываем при каждой перерисовке экрана. */
    private static final long FRESH_MS = 15_000;

    private final VpsPanelController.Host host;
    private final SecretStore secrets;
    private final VpsPanelController owner;
    /** Снимок прочитан — форма установки урезает выбор памяти и ядер. Вызывается в UI-потоке. */
    private final BiConsumer<ServerEntry, VpsCapacity.Snapshot> onSnapshot;

    private final Map<String, VpsCapacity.Snapshot> snapshots = new HashMap<>();
    private final Map<String, Long> readAt = new HashMap<>();
    private final Set<String> reading = new HashSet<>();
    private final Map<String, String> errors = new HashMap<>();

    private VBox root, rows, addBox;
    private HBox bar;
    private Label caption;
    private Button addBtn;
    private TextField nameField;
    private Button createBtn;
    private ServerEntry current;

    VpsSplitPanel(VpsPanelController.Host host, SecretStore secrets, VpsPanelController owner,
                  BiConsumer<ServerEntry, VpsCapacity.Snapshot> onSnapshot) {
        this.host = host;
        this.secrets = secrets;
        this.owner = owner;
        this.onSnapshot = onSnapshot;
    }

    private String tr(String key) {
        return host.tr(key);
    }

    // ================================================================ разметка

    VBox build() {
        Label title = new Label(tr("vsplit_title"));
        title.getStyleClass().add("field-label");
        addBtn = new Button(tr("vsplit_add"));
        addBtn.getStyleClass().add("server-action");
        addBtn.setOnAction(e -> openAddBox());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox head = new HBox(8, title, gap, addBtn);
        head.setAlignment(Pos.CENTER_LEFT);

        bar = new HBox();
        bar.getStyleClass().add("vps-mem-bar");
        // Своя ширина у полосы — маленькая: отрезки привязаны к её ширине, и без этого предпочтительная
        // ширина полосы считалась бы из них же и раздувала экран.
        bar.setMinWidth(0);
        bar.setPrefWidth(10);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setMinHeight(8);
        bar.setPrefHeight(8);
        bar.setMaxHeight(8);

        caption = new Label();
        caption.getStyleClass().add("toggle-sub");
        caption.setWrapText(true);

        rows = new VBox(2);

        nameField = new TextField();
        nameField.getStyleClass().add("text-input");
        nameField.setPromptText(tr("vsplit_name_prompt"));
        HBox.setHgrow(nameField, Priority.ALWAYS);
        nameField.setOnAction(e -> onCreate());
        createBtn = new Button(tr("vsplit_create"));
        createBtn.getStyleClass().add("server-action");
        createBtn.setOnAction(e -> onCreate());
        Button cancel = new Button(tr("vsplit_cancel"));
        cancel.getStyleClass().add("server-action");
        cancel.setOnAction(e -> show(addBox, false));
        HBox addRow = new HBox(8, nameField, createBtn, cancel);
        addRow.setAlignment(Pos.CENTER_LEFT);
        Label addNote = new Label(tr("vsplit_add_note"));
        addNote.getStyleClass().add("toggle-sub");
        addNote.setWrapText(true);
        addBox = new VBox(6, addRow, addNote);
        show(addBox, false);

        root = new VBox(8, new Separator(), head, bar, caption, rows, addBox);
        return root;
    }

    // ================================================================ снимок

    private static String vpsKey(ServerEntry s) {
        return (s.getHost() == null ? "" : s.getHost().toLowerCase()) + ":" + s.getSshPort() + "|" + s.getSshHostKey();
    }

    /** Последний прочитанный снимок машины этого сервера; null — ещё не читали. */
    VpsCapacity.Snapshot snapshot(ServerEntry s) {
        return s == null ? null : snapshots.get(vpsKey(s));
    }

    /** Забыть снимок (после установки или удаления сервера) — следующая перерисовка перечитает. */
    void invalidate(ServerEntry s) {
        if (s != null) readAt.remove(vpsKey(s));
    }

    /** Перечитать снимок, если он старше FRESH_MS (или {@code force}). */
    void refresh(ServerEntry s, boolean force) {
        String key = vpsKey(s);
        Long at = readAt.get(key);
        if (!force && at != null && System.currentTimeMillis() - at < FRESH_MS) return;
        if (!reading.add(key)) return;
        host.runInBackground(() -> {
            VpsCapacity.Snapshot snap = null;
            String err = null;
            try (VpsSession session = owner.openSession(s)) {
                snap = new VpsCapacity().read(session);
            } catch (VpsException | RuntimeException e) {
                err = e.getMessage();
            }
            VpsCapacity.Snapshot result = snap;
            String error = err;
            Platform.runLater(() -> {
                reading.remove(key);
                readAt.put(key, System.currentTimeMillis());
                if (result != null) {
                    snapshots.put(key, result);
                    errors.remove(key);
                } else {
                    errors.put(key, error);
                }
                if (current != null && vpsKey(current).equals(key)) {
                    draw();
                    if (result != null) onSnapshot.accept(current, result);
                }
            });
        });
    }

    // ================================================================ отрисовка

    void render(ServerEntry s) {
        boolean ssh = s != null && s.isSsh();
        if (current == null || s == null || !current.getId().equals(s.getId())) show(addBox, false);
        current = ssh ? s : null;
        show(root, ssh);
        if (!ssh) return;
        draw();
        refresh(s, false);
    }

    private void draw() {
        ServerEntry s = current;
        VpsCapacity.Snapshot snap = snapshot(s);
        rows.getChildren().clear();
        bar.getChildren().clear();
        if (snap == null) {
            String err = errors.get(vpsKey(s));
            caption.setText(err != null ? tr("vsplit_read_failed").replace("{error}", err) : tr("vsplit_reading"));
            addBtn.setDisable(true);
            return;
        }

        // Полоса: серверы по порядку (этот — акцентом), затем свободное, в конце — резерв системы.
        long total = Math.max(1, snap.memTotalMb());
        List<ServerEntry> mine = siblings(s);
        for (VpsCapacity.Server srv : snap.servers()) {
            long mb = VpsCapacity.footprintMb(srv.ramMb() > 0 ? srv.ramMb() : 2048);
            boolean me = srv.id().equalsIgnoreCase(s.getId());
            Region seg = segment(mb, total, me ? "vps-mem-seg-current" : "vps-mem-seg");
            Tooltip.install(seg, new Tooltip(nameOf(srv, mine) + " · " + VpsCapacity.mbText(mb)));
            bar.getChildren().add(seg);
        }
        long used = snap.usedMb(null);
        long free = Math.max(0, snap.budgetMb() - used);
        Region freeSeg = segment(free, total, "vps-mem-free");
        HBox.setHgrow(freeSeg, Priority.ALWAYS);
        bar.getChildren().add(freeSeg);
        bar.getChildren().add(segment(Math.min(total, VpsInstaller.SYSTEM_RESERVE_MB), total, "vps-mem-system"));

        int canGive = snap.maxRamFor(null);
        caption.setText(tr("vsplit_caption")
                .replace("{total}", VpsCapacity.mbText(snap.memTotalMb()))
                .replace("{used}", VpsCapacity.mbText(Math.min(used, snap.budgetMb())))
                .replace("{free}", VpsCapacity.mbText(free))
                .replace("{cpus}", String.valueOf(snap.cpus()))
                + (used > snap.budgetMb() ? "\n" + tr("vsplit_over") : ""));

        Set<String> shown = new HashSet<>();
        for (VpsCapacity.Server srv : snap.servers()) {
            ServerEntry e = entryFor(srv.id(), mine);
            if (e != null) shown.add(e.getId());
            rows.getChildren().add(row(srv, e, srv.id().equalsIgnoreCase(s.getId()), mine));
        }
        // Записи лаунчера на этой машине без службы — сервер ещё не установлен.
        for (ServerEntry e : mine) {
            if (shown.contains(e.getId())) continue;
            rows.getChildren().add(row(null, e, e.getId().equals(s.getId()), mine));
        }

        boolean room = canGive >= MIN_SERVER_MB;
        addBtn.setDisable(!room);
        addBtn.setTooltip(room ? null : new Tooltip(tr("vsplit_no_room")));
        if (!room) show(addBox, false);
    }

    private static Region segment(long mb, long total, String style) {
        Region r = new Region();
        r.getStyleClass().addAll("vps-mem-seg-base", style);
        double frac = Math.max(0, Math.min(1, (double) mb / total));
        r.setMinWidth(0);
        // Ширина — доля полосы; связывается, когда отрезок попадает в полосу.
        r.parentProperty().addListener((o, was, now) -> {
            if (now instanceof HBox hb) r.prefWidthProperty().bind(hb.widthProperty().multiply(frac));
            else r.prefWidthProperty().unbind();
        });
        return r;
    }

    private HBox row(VpsCapacity.Server srv, ServerEntry e, boolean me, List<ServerEntry> mine) {
        Label dot = new Label("●");
        boolean active = srv != null && srv.active();
        dot.getStyleClass().add(active ? "vps-dot-on" : "vps-dot-off");

        String name = srv != null ? nameOf(srv, mine) : e.getName();
        javafx.scene.Node title;
        if (e != null && !me) {
            Hyperlink link = new Hyperlink(name);
            link.getStyleClass().add("all-builds-link");
            String id = e.getId();
            link.setOnAction(ev -> host.openServerDetail(id));
            title = link;
        } else {
            Label l = new Label(me ? name + " · " + tr("vsplit_this") : name);
            l.getStyleClass().add(me ? "field-label" : "toggle-sub");
            title = l;
        }

        String meta;
        if (srv == null) {
            meta = tr("vsplit_not_installed");
        } else {
            meta = (srv.port() > 0 ? tr("vsplit_port").replace("{port}", String.valueOf(srv.port())) + " · " : "")
                    + (srv.ramMb() > 0 ? VpsCapacity.mbText(srv.ramMb()) : "?")
                    + " · " + (active
                    ? tr("vsplit_running").replace("{mem}", srv.memoryBytes() > 0
                            ? VpsCapacity.mbText(srv.memoryBytes() / (1024 * 1024)) : "?")
                    : tr("vsplit_stopped"))
                    + (e == null ? " · " + tr("vsplit_foreign") : "");
        }
        Label metaLabel = new Label(meta);
        metaLabel.getStyleClass().addAll("toggle-sub", "mono");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox box = new HBox(8, dot, title, gap, metaLabel);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** Записи лаунчера на той же машине, что и текущий сервер, включая его самого. */
    private List<ServerEntry> siblings(ServerEntry s) {
        List<ServerEntry> out = new ArrayList<>();
        for (ServerEntry e : host.allServers()) if (e == s || s.sameVps(e)) out.add(e);
        return out;
    }

    private static ServerEntry entryFor(String id, List<ServerEntry> mine) {
        for (ServerEntry e : mine) if (e.getId().equalsIgnoreCase(id)) return e;
        return null;
    }

    private String nameOf(VpsCapacity.Server srv, List<ServerEntry> mine) {
        ServerEntry e = entryFor(srv.id(), mine);
        return e != null ? e.getName() : tr("vsplit_unknown").replace("{id}", srv.id().substring(0, 8));
    }

    // ================================================================ «+ Ещё сервер»

    private void openAddBox() {
        if (current == null) return;
        nameField.setText(suggestName(current));
        show(addBox, true);
        nameField.requestFocus();
        nameField.selectAll();
    }

    private String suggestName(ServerEntry s) {
        Set<String> names = new HashSet<>();
        for (ServerEntry e : host.allServers()) if (e.getName() != null) names.add(e.getName().toLowerCase());
        String base = s.getName() == null ? "VPS" : s.getName().replaceAll("\\s+\\d+$", "");
        for (int i = 2; i < 100; i++) {
            String n = base + " " + i;
            if (!names.contains(n.toLowerCase())) return n;
        }
        return base;
    }

    /** Первый порт от 25565, который не занят ни сервером на VPS, ни записью лаунчера, ни другой программой. */
    static int suggestPort(VpsCapacity.Snapshot snap, List<ServerEntry> mine) {
        Set<Integer> taken = new HashSet<>(snap == null ? List.of() : snap.takenPorts(null));
        if (snap != null) taken.addAll(snap.listening());
        for (ServerEntry e : mine) taken.add(e.getPort());
        int p = 25565;
        while (taken.contains(p)) p++;
        return p;
    }

    private void onCreate() {
        ServerEntry src = current;
        if (src == null) return;
        String name = nameField.getText() == null ? "" : nameField.getText().strip();
        if (name.isEmpty() || name.length() > 40) {
            host.showToast(tr("vsplit_name_bad"), "error");
            return;
        }
        for (ServerEntry e : host.allServers()) {
            if (name.equalsIgnoreCase(e.getName())) {
                host.showToast(tr("vsplit_name_taken").replace("{name}", name), "error");
                return;
            }
        }
        ServerEntry n = new ServerEntry(name, null, null, false);
        n.setConnection("ssh");
        n.setHost(src.getHost());
        n.setSshPort(src.getSshPort());
        n.setSshUser(src.getSshUser());
        n.setSshHostKey(src.getSshHostKey());
        n.setSshHostKeyType(src.getSshHostKeyType());
        n.setSshPublicKey(src.getSshPublicKey());
        n.setSshPrivilege(src.getSshPrivilege());
        n.setSshOs(src.getSshOs());
        n.setPort(suggestPort(snapshot(src), siblings(src)));
        createBtn.setDisable(true);
        host.runInBackground(() -> {
            String err = null;
            try {
                // Тот же ключ под именем новой записи: удаление одной из них не оставит другие без ключа.
                String pem = secrets.get(src.secretKeyName());
                if (pem == null) throw new IOException(tr("vsplit_no_key"));
                secrets.put(n.secretKeyName(), pem);
                if ("SUDO_PASSWORD".equals(src.getSshPrivilege())) {
                    String sudo = secrets.get(src.secretSudoName());
                    if (sudo != null) secrets.put(n.secretSudoName(), sudo);
                }
            } catch (IOException e) {
                err = e.getMessage();
            }
            String error = err;
            Platform.runLater(() -> {
                createBtn.setDisable(false);
                if (error != null) {
                    host.showToast(tr("vsplit_create_failed").replace("{error}", error), "error");
                    return;
                }
                show(addBox, false);
                invalidate(src);
                host.addServer(n);
                host.saveServers();
                host.refreshServerViews();
                host.log("[VPS] На машине «" + src.getName() + "» добавлен ещё один сервер: «" + n.getName()
                        + "», порт " + n.getPort());
                host.showToast(tr("vsplit_created").replace("{name}", n.getName()), "success");
                host.openServerDetail(n.getId());
            });
        });
    }

    private static void show(javafx.scene.Node n, boolean visible) {
        n.setVisible(visible);
        n.setManaged(visible);
    }
}
