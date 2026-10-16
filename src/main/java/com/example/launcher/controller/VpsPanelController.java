package com.example.launcher.controller;

import com.example.launcher.model.Profile;
import com.example.launcher.model.ServerEntry;
import com.example.launcher.service.VersionDownloadService;
import com.example.launcher.service.vps.SecretStore;
import com.example.launcher.service.vps.ServerMigration;
import com.example.launcher.service.vps.VpsConnector;
import com.example.launcher.service.vps.VpsException;
import com.example.launcher.service.vps.VpsInstaller;
import com.example.launcher.service.vps.VpsSession;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;
import org.kordamp.ikonli.javafx.FontIcon;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Панель VPS по SSH: добавление сервера (вкладка «VPS / внешний» окна создания сервера) и блок
 * подключения на экране сервера.
 *
 * <p>Устроен как {@link PluginsPanelController}: не {@code fx:controller}, узлы FXML приходят
 * через {@link #wire}, обработчики из main.fxml остаются однострочными делегатами в
 * MainController, а обратно он ходит только через {@link Host}. Вся работа с VPS — в
 * {@code service.vps} (без JavaFX); здесь только UI и потоки.
 *
 * <p>Секреты (пароль из формы, приватный ключ, пароль sudo) не попадают ни в лог, ни в
 * ServerEntry: пароль живёт в поле формы до конца подключения, ключ и пароль sudo — в SecretStore.
 */
public class VpsPanelController {

    public interface Host {
        /** Добавить запись в список серверов (вызывается в UI-потоке). */
        void addServer(ServerEntry s);
        void saveServers();
        /** Перерисовать списки серверов и, если открыт, экран сервера. */
        void refreshServerViews();
        void removeServerFromList(ServerEntry s);
        void showVpsStep(int step);
        void closeCreateModal();
        void openServerDetail(String id);
        void showToast(String message, String type);
        void log(String line);
        void runInBackground(Runnable task);
        Window window();
        // ---- для установки Minecraft (этап 2)
        /** Релизы Minecraft, новые сверху — тот же список, что в остальных выборах версии. */
        List<String> minecraftVersions();
        /** Сборки лаунчера (вкладка «Сборки»). */
        List<Profile> builds();
        /** Папка модов сборки: launcher_root/mods/&lt;имя&gt;. */
        Path modsDirFor(Profile build);
        VersionDownloadService versionService();
        /** Открыть ссылку в браузере (EULA). */
        void openUrl(String url);
        // ---- для переноса локального сервера (этап 4)
        /** Локальные серверы из списка «Мои серверы». */
        List<ServerEntry> localServers();
        /** Папка локального сервера: launcher_root/servers/&lt;имя&gt;. */
        Path localServerDir(ServerEntry local);
        /** Моды, которые лаунчер кладёт этому серверу: launcher_root/mods/&lt;имя&gt;. */
        Path localModsDir(ServerEntry local);
        boolean isLocalServerRunning(ServerEntry local);
    }

    /** ButtonType.CANCEL подписан «Cancel» независимо от языка лаунчера. */
    private static final ButtonType CANCEL = new ButtonType("Отмена", ButtonBar.ButtonData.CANCEL_CLOSE);

    private final Host host;
    private final SecretStore secrets;
    private final VpsConnector connector = new VpsConnector();

    // Окно создания сервера, шаг 1
    private TextField nameField, hostField, sshPortField, userField, mcPortField, keyFileField;
    private PasswordField passwordField, keyPassphraseField, sudoPasswordField;
    private ToggleButton authPasswordTab, authKeyTab;
    private VBox passwordBox, keyBox;
    // шаг 2
    private VBox stepsList;
    private Label statusLabel;
    private Button retryBtn, backBtn;
    // экран сервера
    private VBox sshPanel;
    private Label infoLabel, hostKeyLabel, sshStatusLabel;
    private Button checkBtn;

    /** Идёт подключение — окно создания закрывать нельзя, см. MainController.onCloseCreateServer. */
    private volatile boolean busy;
    private final Map<VpsConnector.Step, StepRow> stepRows = new EnumMap<>(VpsConnector.Step.class);

    public VpsPanelController(Host host, SecretStore secrets) {
        this.host = host;
        this.secrets = secrets;
    }

    public void wireCreateForm(TextField nameField, TextField hostField, TextField sshPortField, TextField userField,
                               ToggleButton authPasswordTab, ToggleButton authKeyTab,
                               VBox passwordBox, PasswordField passwordField,
                               VBox keyBox, TextField keyFileField, PasswordField keyPassphraseField,
                               PasswordField sudoPasswordField, TextField mcPortField,
                               VBox stepsList, Label statusLabel, Button retryBtn, Button backBtn) {
        this.nameField = nameField;
        this.hostField = hostField;
        this.sshPortField = sshPortField;
        this.userField = userField;
        this.authPasswordTab = authPasswordTab;
        this.authKeyTab = authKeyTab;
        this.passwordBox = passwordBox;
        this.passwordField = passwordField;
        this.keyBox = keyBox;
        this.keyFileField = keyFileField;
        this.keyPassphraseField = keyPassphraseField;
        this.sudoPasswordField = sudoPasswordField;
        this.mcPortField = mcPortField;
        this.stepsList = stepsList;
        this.statusLabel = statusLabel;
        this.retryBtn = retryBtn;
        this.backBtn = backBtn;
    }

    public void wireDetail(VBox sshPanel, Label infoLabel, Label hostKeyLabel, Label sshStatusLabel, Button checkBtn) {
        this.sshPanel = sshPanel;
        this.infoLabel = infoLabel;
        this.hostKeyLabel = hostKeyLabel;
        this.sshStatusLabel = sshStatusLabel;
        this.checkBtn = checkBtn;
        buildInstallSection();
        sshPanel.getChildren().add(installSection);
    }

    public boolean isBusy() {
        return busy;
    }

    // ================================================================ окно создания

    /** Сброс формы при открытии окна «Новый сервер». */
    public void resetForm() {
        nameField.clear();
        hostField.clear();
        sshPortField.setText("22");
        userField.setText("root");
        mcPortField.setText("25565");
        clearSecrets();
        keyFileField.clear();
        onAuthPassword();
        statusLabel.setText("");
    }

    /** Пароли из полей формы — сразу, как только они больше не нужны. */
    private void clearSecrets() {
        passwordField.clear();
        keyPassphraseField.clear();
        sudoPasswordField.clear();
    }

    public void onAuthPassword() {
        authPasswordTab.setSelected(true);
        authKeyTab.setSelected(false);
        show(passwordBox, true);
        show(keyBox, false);
    }

    public void onAuthKey() {
        authPasswordTab.setSelected(false);
        authKeyTab.setSelected(true);
        show(passwordBox, false);
        show(keyBox, true);
    }

    public void onBrowseKey() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Приватный ключ SSH");
        File ssh = new File(System.getProperty("user.home"), ".ssh");
        if (ssh.isDirectory()) chooser.setInitialDirectory(ssh);
        File f = chooser.showOpenDialog(host.window());
        if (f != null) keyFileField.setText(f.getAbsolutePath());
    }

    public void onBackToForm() {
        if (busy) return;
        host.showVpsStep(1);
    }

    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9.:\\-]{1,253}$");
    private static final Pattern LOGIN = Pattern.compile("^[A-Za-z0-9._\\-]{1,32}$");

    /** Снимок формы — снимается в UI-потоке, дальше работа идёт только с ним. */
    private record Form(String name, VpsSession.Target target, VpsSession.Auth auth, String sudoPassword, int mcPort) {
        @Override public String toString() { return "Form[" + name + ", " + target + "]"; }
    }

    private Form pendingForm;
    private VpsSession.HostKeyInfo pendingHostKey;

    /** Кнопка «Подключиться» на шаге 1. */
    public void onConnect() {
        if (busy) return;
        Form form;
        try {
            form = readForm();
        } catch (IllegalArgumentException | IOException e) {
            host.showToast(e.getMessage(), "error");
            return;
        }
        pendingForm = form;
        pendingHostKey = null;
        startConnect();
    }

    /** «Повторить» на шаге 2 — с теми же данными. */
    public void onRetry() {
        if (busy || pendingForm == null) return;
        startConnect();
    }

    private Form readForm() throws IOException {
        String h = hostField.getText() == null ? "" : hostField.getText().trim();
        if (!HOST.matcher(h).matches()) throw new IllegalArgumentException("Укажите IP-адрес или имя сервера");
        int sshPort = parsePort(sshPortField.getText(), "Порт SSH");
        String user = userField.getText() == null ? "" : userField.getText().trim();
        if (!LOGIN.matcher(user).matches()) throw new IllegalArgumentException("Укажите пользователя (например root)");
        int mcPort = parsePort(mcPortField.getText(), "Порт Minecraft");
        String name = nameField.getText() == null || nameField.getText().isBlank() ? "VPS " + h : nameField.getText().trim();

        VpsSession.Auth auth;
        String sudo = null;
        if (authKeyTab.isSelected()) {
            String path = keyFileField.getText() == null ? "" : keyFileField.getText().trim();
            if (path.isEmpty()) throw new IllegalArgumentException("Выберите файл приватного ключа");
            File f = new File(path);
            if (!f.isFile()) throw new IllegalArgumentException("Файл ключа не найден: " + path);
            if (f.length() > 64 * 1024) throw new IllegalArgumentException("Это не похоже на файл ключа: слишком большой");
            String pem = Files.readString(f.toPath(), StandardCharsets.UTF_8);
            auth = new VpsSession.PrivateKey(pem, keyPassphraseField.getText());
            sudo = sudoPasswordField.getText();
        } else {
            String pw = passwordField.getText();
            if (pw == null || pw.isEmpty()) throw new IllegalArgumentException("Введите пароль от VPS");
            auth = new VpsSession.Password(pw);
        }
        return new Form(name, new VpsSession.Target(h, sshPort, user), auth, sudo, mcPort);
    }

    private static int parsePort(String text, String what) {
        try {
            int p = Integer.parseInt(text == null ? "" : text.trim());
            if (p >= 1 && p <= 65535) return p;
        } catch (NumberFormatException ignored) {}
        throw new IllegalArgumentException(what + ": число от 1 до 65535");
    }

    private void startConnect() {
        Form form = pendingForm;
        busy = true;
        host.showVpsStep(2);
        buildStepRows();
        statusLabel.setText("");
        retryBtn.setDisable(true);
        backBtn.setDisable(true);
        host.log("[VPS] Подключение к " + form.target().host() + ":" + form.target().port() + "…");

        host.runInBackground(() -> {
            try {
                // Хранилище секретов — до любых изменений на VPS (см. SecretStore.ensureReady).
                secrets.ensureReady();

                // 1. Ключ хоста — без пароля. Подтверждение — в UI-потоке, ждём ответа.
                if (pendingHostKey == null) {
                    stepUi(VpsConnector.Step.CONNECT, VpsConnector.State.RUNNING, "получаю ключ сервера");
                    VpsSession.HostKeyInfo hk = VpsSession.probeHostKey(form.target().host(), form.target().port());
                    if (!confirmHostKeyOnUiThread(form.target(), hk)) {
                        finishFailed("Подключение отменено: отпечаток сервера не подтверждён.");
                        return;
                    }
                    pendingHostKey = hk;
                }

                // 2–4. Вход, система, права, ключ. id записи создаётся заранее: он входит в подпись
                // ключа в authorized_keys, по которой человек на VPS поймёт, чей это ключ.
                String id = java.util.UUID.randomUUID().toString();
                VpsConnector.Result result = connector.connect(
                        new VpsConnector.Request(form.target(), form.auth(), form.sudoPassword(),
                                pendingHostKey.fingerprint(), VpsConnector.keyComment(id)),
                        this::stepUi);
                ServerEntry entry = createEntry(id, form, pendingHostKey, result);
                secrets.put(entry.secretKeyName(), result.privateKeyPem());
                if (result.sudoPasswordToStore() != null) {
                    secrets.put(entry.secretSudoName(), result.sudoPasswordToStore());
                }
                Platform.runLater(() -> {
                    busy = false;
                    clearSecrets();
                    pendingForm = null;
                    host.addServer(entry);
                    host.saveServers();
                    host.refreshServerViews();
                    host.closeCreateModal();
                    host.log("[VPS] «" + entry.getName() + "» подключён: " + result.os().prettyName()
                            + ", права: " + entry.getSshPrivilege());
                    host.showToast("Сервер «" + entry.getName() + "» подключён по SSH", "success");
                    host.openServerDetail(entry.getId());
                });
            } catch (VpsException e) {
                finishFailed(e.getMessage());
            } catch (IOException e) {
                finishFailed("Хранилище ключей недоступно: " + e.getMessage());
            } catch (RuntimeException e) {
                finishFailed("Непредвиденная ошибка: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            }
        });
    }

    private ServerEntry createEntry(String id, Form form, VpsSession.HostKeyInfo hk, VpsConnector.Result r) {
        ServerEntry s = new ServerEntry(form.name(), null, null, false);
        s.setId(id);
        s.setConnection("ssh");
        s.setHost(form.target().host());
        s.setSshPort(form.target().port());
        s.setSshUser(form.target().user());
        s.setPort(form.mcPort());
        s.setSshHostKey(hk.fingerprint());
        s.setSshHostKeyType(hk.type());
        s.setSshPublicKey(r.publicKeyLine());
        s.setSshPrivilege(r.privilege().name());
        s.setSshOs(r.os().prettyName());
        return s;
    }

    private void finishFailed(String message) {
        Platform.runLater(() -> {
            busy = false;
            retryBtn.setDisable(false);
            backBtn.setDisable(false);
            boolean shownInRow = false;
            for (StepRow row : stepRows.values()) {
                if (row.state == VpsConnector.State.RUNNING) row.set(VpsConnector.State.FAILED, null);
                if (row.state == VpsConnector.State.FAILED && message.equals(row.detail.getText())) shownInRow = true;
            }
            // Текст ошибки уже стоит в строке шага (VpsConnector пишет его туда сам) — второй раз
            // под списком он только мешает.
            statusLabel.setText(shownInRow ? "" : message);
            host.log("[VPS] Не удалось подключиться: " + message);
        });
    }

    /** Показ отпечатка и ожидание ответа человека из фонового потока. */
    private boolean confirmHostKeyOnUiThread(VpsSession.Target target, VpsSession.HostKeyInfo hk) {
        java.util.concurrent.CompletableFuture<Boolean> answer = new java.util.concurrent.CompletableFuture<>();
        Platform.runLater(() -> {
            Alert a = new Alert(Alert.AlertType.CONFIRMATION);
            a.setTitle("Новый сервер");
            a.setHeaderText("Первое подключение к " + target.host() + ":" + target.port());
            a.setContentText("Отпечаток ключа сервера:\n" + hk.type() + "  " + hk.fingerprint()
                    + "\n\nЛаунчер запомнит его и будет предупреждать, если он изменится. Проверить, что это "
                    + "ваш сервер, можно в веб-консоли хостера командой:\n"
                    + "ssh-keygen -lf " + hostKeyFile(hk.type())
                    + "\n\nДоверять этому серверу?");
            ButtonType trust = new ButtonType("Доверять", ButtonBar.ButtonData.OK_DONE);
            a.getButtonTypes().setAll(trust, CANCEL);
            a.getDialogPane().setMinWidth(560);
            Optional<ButtonType> r = a.showAndWait();
            answer.complete(r.isPresent() && r.get() == trust);
        });
        try {
            return answer.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            return false;
        }
    }

    private static String hostKeyFile(String type) {
        if (type.contains("ed25519")) return "/etc/ssh/ssh_host_ed25519_key.pub";
        if (type.startsWith("ecdsa")) return "/etc/ssh/ssh_host_ecdsa_key.pub";
        return "/etc/ssh/ssh_host_rsa_key.pub";
    }

    // ---------------------------------------------------------------- список шагов

    /** Строка шага — общая для подключения (VpsConnector) и установки (VpsInstaller). */
    private static final class StepRow {
        final HBox box = new HBox(10);
        final FontIcon icon = new FontIcon("fth-circle");
        final Label detail = new Label();
        VpsConnector.State state;

        StepRow(String label) {
            box.setAlignment(Pos.CENTER_LEFT);
            icon.setIconSize(14);
            icon.getStyleClass().add("mod-avatar-fallback");
            Label title = new Label(label);
            title.getStyleClass().add("field-label");
            title.setMinWidth(170);
            detail.getStyleClass().add("toggle-sub");
            detail.setWrapText(true);
            HBox.setHgrow(detail, Priority.ALWAYS);
            box.getChildren().addAll(icon, title, detail);
        }

        void set(VpsConnector.State s, String text) {
            state = s;
            icon.setIconLiteral(switch (s) {
                case RUNNING -> "fth-loader";
                case DONE -> "fth-check";
                case FAILED -> "fth-x";
            });
            if (text != null) detail.setText(text);
        }
    }

    private void buildStepRows() {
        stepsList.getChildren().clear();
        stepRows.clear();
        for (VpsConnector.Step step : VpsConnector.Step.values()) {
            StepRow row = new StepRow(step.label);
            stepRows.put(step, row);
            stepsList.getChildren().add(row.box);
        }
    }

    private void stepUi(VpsConnector.Step step, VpsConnector.State state, String detail) {
        Platform.runLater(() -> {
            StepRow row = stepRows.get(step);
            if (row != null) row.set(state, detail);
        });
    }

    // ================================================================ экран сервера

    /** Показывает блок SSH для сервера панели и прячет — для остальных. */
    public void renderDetail(ServerEntry s) {
        boolean ssh = s != null && s.isSsh();
        show(sshPanel, ssh);
        if (!ssh) return;
        infoLabel.setText(s.getSshUser() + "@" + s.getHost() + ":" + s.getSshPort()
                + " · " + (s.getSshOs() != null ? s.getSshOs() : "система не проверена")
                + " · права: " + privilegeText(s.getSshPrivilege()));
        hostKeyLabel.setText("Ключ сервера: " + s.getSshHostKeyType() + " " + s.getSshHostKey());
        sshStatusLabel.setText(installedSummary(s));
        checkBtn.setDisable(false);
        renderInstall(s);
    }

    /** Строка о том, что стоит на VPS. */
    private static String installedSummary(ServerEntry s) {
        if (!s.isSshInstalled()) return "Minecraft-сервер на этом VPS ещё не установлен.";
        return "Установлен: " + s.getLoader() + " " + s.getVersion()
                + (s.getLoaderVersion() != null ? " (" + s.getLoaderVersion() + ")" : "")
                + (s.getSshProfile() != null ? " · сборка «" + s.getSshProfile() + "»" : "")
                + " · " + ramText(s.getRamMb() == null ? 0 : s.getRamMb())
                + " · порт " + s.getPort() + " · Java " + s.getSshJavaMajor()
                + "\nСлужба " + s.getSshService() + " — запускается сама после перезагрузки VPS и перезапускается при падении.";
    }

    private static String privilegeText(String p) {
        if (p == null) return "?";
        return switch (p) {
            case "ROOT" -> "root";
            case "SUDO_NOPASSWD" -> "sudo";
            case "SUDO_PASSWORD" -> "sudo с паролем";
            default -> p;
        };
    }

    /** Открывает сессию по сохранённому ключу. Вызывать из фонового потока. */
    public VpsSession openSession(ServerEntry s) throws VpsException {
        String pem;
        String sudo;
        try {
            pem = secrets.get(s.secretKeyName());
            sudo = "SUDO_PASSWORD".equals(s.getSshPrivilege()) ? secrets.get(s.secretSudoName()) : null;
        } catch (IOException e) {
            throw new VpsException("Ключ этого сервера не читается: " + e.getMessage(), e);
        }
        if (pem == null) throw new VpsException("Ключ этого сервера не найден на этом ПК — удалите сервер из лаунчера и подключите заново");
        VpsSession session = VpsSession.open(new VpsSession.Target(s.getHost(), s.getSshPort(), s.getSshUser()),
                new VpsSession.PrivateKey(pem, null), s.getSshHostKey());
        session.setPrivilege(VpsSession.Privilege.valueOf(s.getSshPrivilege()), sudo);
        return session;
    }

    /** «Проверить подключение» на экране сервера. */
    public void onCheckConnection(ServerEntry s) {
        if (s == null || !s.isSsh()) return;
        checkBtn.setDisable(true);
        sshStatusLabel.setText("Проверяю…");
        host.runInBackground(() -> {
            long t0 = System.nanoTime();
            try (VpsSession session = openSession(s)) {
                String os = session.exec("sh -c '. /etc/os-release && echo \"$PRETTY_NAME\"'", Duration.ofSeconds(20)).stdout().trim();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                Platform.runLater(() -> {
                    if (!os.isEmpty()) s.setSshOs(os);
                    host.saveServers();
                    renderDetail(s);
                    sshStatusLabel.setText("Подключение работает (" + ms + " мс). " + installedSummary(s));
                });
            } catch (VpsException.HostKeyChanged e) {
                Platform.runLater(() -> {
                    checkBtn.setDisable(false);
                    sshStatusLabel.setText("Отпечаток сервера изменился — подключение остановлено.");
                    askTrustChangedKey(s, e);
                });
            } catch (VpsException e) {
                Platform.runLater(() -> {
                    checkBtn.setDisable(false);
                    sshStatusLabel.setText("Нет подключения: " + e.getMessage());
                });
            }
        });
    }

    /**
     * Ключ хоста сменился. По умолчанию — отказ: доверять новому ключу можно только явной кнопкой,
     * потому что то же самое видно и при подмене сервера.
     */
    private void askTrustChangedKey(ServerEntry s, VpsException.HostKeyChanged e) {
        Alert a = new Alert(Alert.AlertType.WARNING);
        a.setTitle("Отпечаток сервера изменился");
        a.setHeaderText("Сервер «" + s.getName() + "» предъявил другой ключ");
        a.setContentText("Было:  " + e.expected + "\nСейчас: " + e.actual.type() + " " + e.actual.fingerprint()
                + "\n\nТак бывает, если вы переустановили систему на VPS. Если вы ничего не меняли — не "
                + "подключайтесь: возможно, кто-то подменяет сервер.\n\nПроверить новый отпечаток можно в "
                + "веб-консоли хостера: ssh-keygen -lf " + hostKeyFile(e.actual.type()));
        ButtonType trust = new ButtonType("Доверять новому ключу", ButtonBar.ButtonData.OTHER);
        a.getButtonTypes().setAll(CANCEL, trust);
        a.getDialogPane().setMinWidth(560);
        Optional<ButtonType> r = a.showAndWait();
        if (r.isPresent() && r.get() == trust) {
            s.setSshHostKey(e.actual.fingerprint());
            s.setSshHostKeyType(e.actual.type());
            host.saveServers();
            host.log("[VPS] «" + s.getName() + "»: принят новый ключ сервера " + e.actual.fingerprint());
            onCheckConnection(s);
        }
    }

    // ================================================================ установка Minecraft (этап 2)
    //
    // Форма живёт внутри блока SSH на экране сервера и видна, пока Minecraft на VPS не установлен.
    // Вся работа — в VpsInstaller; здесь выбор (ядро или сборка, версия, память, EULA), список
    // шагов и «Повторить». Установка одна на всё окно: два параллельных apt на разных VPS — не
    // проблема, но два списка шагов на одном экране путали бы.

    private static final String EULA_URL = "https://aka.ms/MinecraftEULA";
    private static final int[] RAM_OPTIONS_MB = {1024, 1536, 2048, 3072, 4096, 6144, 8192, 12288, 16384, 24576, 32768};

    private VpsInstaller installer;
    private VBox installSection, installForm, coreBox, buildBox, moveBox, loaderVersionBox, installStepsList;
    private ToggleButton modeCoreTab, modeBuildTab, modeMoveTab;
    private ComboBox<VpsInstaller.Core> coreCombo;
    private ComboBox<String> versionCombo, loaderVersionCombo, buildCombo, moveCombo;
    private ComboBox<Integer> ramCombo;
    private Label buildInfoLabel, moveInfoLabel, installStatusLabel;
    private int moveInfoSeq;
    /** Локальный сервер, который просили перенести кнопкой «На VPS» до того, как форма построилась. */
    private String pendingMoveName;
    /** Порт можно поменять перед установкой: при «порт занят» иначе было бы некуда деться. */
    private TextField installPortField;
    private CheckBox eulaCheck;
    private Button installBtn, installRetryBtn;
    private final Map<VpsInstaller.Step, StepRow> installRows = new EnumMap<>(VpsInstaller.Step.class);
    /** id сервера, на который сейчас идёт установка; null — ничего не ставится. */
    private volatile String installingId;
    /** Последняя установка — для «Повторить» с теми же параметрами. */
    private VpsInstaller.Plan lastPlan;
    private String lastPlanServerId;
    private boolean lastInstallFailed;
    /** Память VPS (МБ) по id сервера: читается раз за сеанс, чтобы не предлагать больше, чем есть. */
    private final Map<String, Long> vpsMemoryMb = new HashMap<>();
    private final java.util.Set<String> memoryRequests = new java.util.HashSet<>();
    /** Поздний ответ о версиях загрузчика или модах сборки не должен перетереть более новый выбор. */
    private int loaderRequestSeq, buildInfoSeq;
    /** Сервер, для которого сейчас нарисована форма. */
    private ServerEntry renderedInstallFor;
    /** Для какого сервера заполнено поле порта. */
    private String installPortFor;

    private VpsInstaller installer() {
        if (installer == null) installer = new VpsInstaller(host.versionService(), secrets);
        return installer;
    }

    private void buildInstallSection() {
        Label title = new Label("Установка Minecraft-сервера");
        title.getStyleClass().add("panel-title");
        Label sub = new Label("Лаунчер поставит Java, ядро и службу systemd. Сервер будет работать от отдельного "
                + "пользователя minecraft (не от root) и сам запускаться после перезагрузки VPS.");
        sub.getStyleClass().add("toggle-sub");
        sub.setWrapText(true);

        ToggleGroup modeGroup = new ToggleGroup();
        modeCoreTab = new ToggleButton("Чистое ядро");
        modeBuildTab = new ToggleButton("Сборка из лаунчера");
        modeMoveTab = new ToggleButton("Перенести с ПК");
        for (ToggleButton t : List.of(modeCoreTab, modeBuildTab, modeMoveTab)) {
            t.getStyleClass().add("share-tab");
            t.setToggleGroup(modeGroup);
        }
        modeCoreTab.setSelected(true);
        // Повторный щелчок по выбранной вкладке снимает выбор в ToggleGroup — возвращаем его.
        modeGroup.selectedToggleProperty().addListener((o, was, now) -> {
            if (now == null) {
                if (was != null) was.setSelected(true);
                return;
            }
            updateMode();
        });
        HBox modeTabs = new HBox(4, modeCoreTab, modeBuildTab, modeMoveTab);
        modeTabs.getStyleClass().add("share-tabs");

        coreCombo = new ComboBox<>(FXCollections.observableArrayList(VpsInstaller.Core.values()));
        coreCombo.setConverter(new StringConverter<>() {
            @Override public String toString(VpsInstaller.Core c) { return c == null ? "" : c.label; }
            @Override public VpsInstaller.Core fromString(String s) { return null; }
        });
        coreCombo.getSelectionModel().select(VpsInstaller.Core.PAPER);
        versionCombo = new ComboBox<>();
        loaderVersionCombo = new ComboBox<>();
        loaderVersionBox = field("Версия загрузчика", loaderVersionCombo);
        coreCombo.setOnAction(e -> refreshLoaderVersions());
        versionCombo.setOnAction(e -> refreshLoaderVersions());
        loaderVersionCombo.setOnAction(e -> updateInstallButton());
        coreBox = new VBox(6, new HBox(10, field("Ядро", coreCombo), field("Версия Minecraft", versionCombo), loaderVersionBox));

        buildCombo = new ComboBox<>();
        buildCombo.setOnAction(e -> refreshBuildInfo());
        buildInfoLabel = new Label();
        buildInfoLabel.getStyleClass().add("toggle-sub");
        buildInfoLabel.setWrapText(true);
        buildBox = new VBox(6, field("Сборка", buildCombo), buildInfoLabel);

        moveCombo = new ComboBox<>();
        moveCombo.setOnAction(e -> refreshMoveInfo());
        moveInfoLabel = new Label();
        moveInfoLabel.getStyleClass().add("toggle-sub");
        moveInfoLabel.setWrapText(true);
        Label moveNote = new Label("Переедут миры, настройки, плагины и моды. Сервер на ПК останется как был — "
                + "его можно удалить, когда убедитесь, что на VPS всё на месте.");
        moveNote.getStyleClass().add("toggle-sub");
        moveNote.setWrapText(true);
        moveBox = new VBox(6, field("Локальный сервер", moveCombo), moveInfoLabel, moveNote);

        ramCombo = new ComboBox<>();
        ramCombo.setConverter(new StringConverter<>() {
            @Override public String toString(Integer mb) { return mb == null ? "" : ramText(mb); }
            @Override public Integer fromString(String s) { return null; }
        });
        ramCombo.setOnAction(e -> updateInstallButton());
        installPortField = new TextField();
        installPortField.getStyleClass().addAll("text-input", "mono");
        installPortField.setPrefColumnCount(6);
        VBox portBox = field("Порт Minecraft", installPortField);
        HBox.setHgrow(portBox, Priority.NEVER);
        HBox ramRow = new HBox(10, field("Память серверу", ramCombo), portBox);

        eulaCheck = new CheckBox("Я принимаю");
        eulaCheck.selectedProperty().addListener((o, a, b) -> updateInstallButton());
        Hyperlink eulaLink = new Hyperlink("лицензионное соглашение Minecraft (EULA)");
        eulaLink.getStyleClass().add("all-builds-link");
        eulaLink.setOnAction(e -> host.openUrl(EULA_URL));
        HBox eulaRow = new HBox(2, eulaCheck, eulaLink);
        eulaRow.setAlignment(Pos.CENTER_LEFT);
        Label eulaNote = new Label("Без принятия EULA сервер Minecraft не запускается. Лаунчер не отмечает это за вас.");
        eulaNote.getStyleClass().add("toggle-sub");
        eulaNote.setWrapText(true);

        installBtn = new Button("Установить");
        installBtn.getStyleClass().add("play-btn");
        installBtn.setMaxWidth(Double.MAX_VALUE);
        installBtn.setOnAction(e -> onInstall());

        installForm = new VBox(10, modeTabs, coreBox, buildBox, moveBox, ramRow, eulaRow, eulaNote, installBtn);

        installStepsList = new VBox(6);
        installStatusLabel = new Label();
        installStatusLabel.getStyleClass().add("toggle-sub");
        installStatusLabel.setWrapText(true);
        installRetryBtn = new Button("Повторить");
        installRetryBtn.getStyleClass().add("server-action");
        installRetryBtn.setOnAction(e -> onRetryInstall());

        installSection = new VBox(10, new Separator(), title, sub, installForm, installStepsList, installStatusLabel, installRetryBtn);
        show(installStepsList, false);
        show(installRetryBtn, false);
        updateMode();
    }

    private static VBox field(String label, javafx.scene.Node control) {
        Label l = new Label(label);
        l.getStyleClass().add("select-label");
        if (control instanceof ComboBox<?> c) c.setMaxWidth(Double.MAX_VALUE);
        VBox box = new VBox(6, l, control);
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    static String ramText(int mb) {
        if (mb <= 0) return "?";
        if (mb % 1024 == 0) return (mb / 1024) + " ГБ";
        if (mb % 512 == 0) return (mb / 1024) + ",5 ГБ";
        return mb + " МБ";
    }

    /** Форма установки для сервера {@code s}; вызывается из renderDetail. */
    private void renderInstall(ServerEntry s) {
        renderedInstallFor = s;
        boolean installed = s.isSshInstalled();
        show(installSection, !installed);
        if (installed) return;

        boolean mine = s.getId().equals(lastPlanServerId);
        show(installStepsList, mine);
        installStatusLabel.setText(mine ? installStatusLabel.getText() : "");
        show(installRetryBtn, mine && lastInstallFailed && installingId == null);
        installForm.setDisable(installingId != null);
        // Не перетираем порт, который человек только что поправил, при каждой перерисовке экрана.
        if (!s.getId().equals(installPortFor)) {
            installPortField.setText(String.valueOf(s.getPort()));
            installPortFor = s.getId();
        }

        if (versionCombo.getItems().isEmpty()) {
            List<String> versions = host.minecraftVersions();
            versionCombo.setItems(FXCollections.observableArrayList(versions));
            if (!versions.isEmpty()) versionCombo.getSelectionModel().selectFirst();
            refreshLoaderVersions();
        }
        String prevBuild = buildCombo.getValue();
        List<String> names = new ArrayList<>();
        for (Profile p : host.builds()) if (p.getName() != null && p.getVersionId() != null) names.add(p.getName());
        buildCombo.setItems(FXCollections.observableArrayList(names));
        if (prevBuild != null && names.contains(prevBuild)) buildCombo.getSelectionModel().select(prevBuild);
        else if (!names.isEmpty()) buildCombo.getSelectionModel().selectFirst();
        if (modeBuildTab.isSelected()) refreshBuildInfo();

        String prevMove = pendingMoveName != null ? pendingMoveName : moveCombo.getValue();
        List<String> locals = new ArrayList<>();
        for (ServerEntry l : host.localServers()) if (l.getName() != null) locals.add(l.getName());
        moveCombo.setItems(FXCollections.observableArrayList(locals));
        if (prevMove != null && locals.contains(prevMove)) moveCombo.getSelectionModel().select(prevMove);
        else if (!locals.isEmpty()) moveCombo.getSelectionModel().selectFirst();
        if (pendingMoveName != null) {
            pendingMoveName = null;
            modeMoveTab.setSelected(true);   // слушатель вкладок сам вызовет updateMode
        }
        if (modeMoveTab.isSelected()) refreshMoveInfo();

        refreshRamOptions(s);
        requestVpsMemory(s);
        updateInstallButton();
    }

    private void updateMode() {
        boolean build = modeBuildTab.isSelected();
        boolean move = modeMoveTab.isSelected();
        show(coreBox, !build && !move);
        show(buildBox, build);
        show(moveBox, move);
        if (build) refreshBuildInfo();
        if (move) refreshMoveInfo();
        installBtn.setText(move ? "Перенести на VPS" : "Установить");
        updateInstallButton();
    }

    /**
     * Кнопка «На VPS» с экрана локального сервера: открыть форму этого VPS сразу на вкладке переноса
     * с выбранным сервером. Форма может быть ещё не построена или нарисована для другого сервера —
     * поэтому только запоминаем, а применяет renderInstall.
     */
    public void preselectMigration(String localName) {
        pendingMoveName = localName;
    }

    private ServerEntry selectedLocal() {
        String name = moveCombo.getValue();
        if (name == null) return null;
        for (ServerEntry l : host.localServers()) if (name.equals(l.getName())) return l;
        return null;
    }

    private static VpsInstaller.Core coreOfLocal(ServerEntry l) {
        return l == null || l.getLoader() == null ? null : VpsInstaller.Core.fromProfileLoader(l.getLoader().toLowerCase());
    }

    private void refreshMoveInfo() {
        ServerEntry l = selectedLocal();
        int seq = ++moveInfoSeq;
        updateInstallButton();
        if (l == null) {
            moveInfoLabel.setText(host.localServers().isEmpty() ? "На этом ПК нет локальных серверов." : "");
            return;
        }
        VpsInstaller.Core core = coreOfLocal(l);
        if (core == null) {
            moveInfoLabel.setText("Серверы на " + l.getLoader() + " панель пока не переносит — только Vanilla, Paper, "
                    + "Fabric, Forge и NeoForge.");
            return;
        }
        String head = core.label + " " + l.getVersion();
        moveInfoLabel.setText(head + " · считаю, что переносить…");
        Path dir = host.localServerDir(l);
        Path modsDir = host.localModsDir(l);
        boolean running = host.isLocalServerRunning(l);
        host.runInBackground(() -> {
            String text;
            try {
                ServerMigration.Summary sum = Files.isDirectory(dir) ? ServerMigration.scan(dir) : null;
                StringBuilder sb = new StringBuilder(head);
                if (sum == null || sum.worlds().isEmpty()) sb.append(" · мир ещё не создан (сервер ни разу не запускался)");
                else sb.append(" · мир: ").append(String.join(", ", sum.worlds()));
                if (sum != null) sb.append(" · к переносу ").append(sizeText(sum.bytes()));
                if (core.takesMods()) sb.append(" · модов на сервер: ").append(collectLocalMods(dir, modsDir).size());
                if (running) sb.append("\nСервер сейчас запущен на ПК — остановите его перед переносом, иначе мир может перенестись не целиком.");
                text = sb.toString();
            } catch (IOException e) {
                text = head + " · папка сервера не читается: " + e.getMessage();
            }
            String t = text;
            Platform.runLater(() -> {
                if (seq == moveInfoSeq) moveInfoLabel.setText(t);
            });
        });
    }

    /**
     * Моды локального сервера: то, что лежит в его папке mods (туда лаунчер копирует сборку, а
     * человек мог добавить своё), плюс моды сборки, если сервер ещё ни разу не запускался и
     * папка пуста. Клиентские отсеиваются как при установке сборки.
     */
    static List<Path> collectLocalMods(Path serverDir, Path buildModsDir) throws IOException {
        Map<String, Path> byName = new java.util.LinkedHashMap<>();
        for (Path p : VpsInstaller.collectServerMods(serverDir.resolve("mods"))[0]) byName.put(p.getFileName().toString(), p);
        for (Path p : VpsInstaller.collectServerMods(buildModsDir)[0]) byName.putIfAbsent(p.getFileName().toString(), p);
        return new ArrayList<>(byName.values());
    }

    private static String sizeText(long bytes) {
        if (bytes < 1024L * 1024 * 1024) return Math.max(1, bytes / (1024 * 1024)) + " МБ";
        return String.format(java.util.Locale.ROOT, "%.1f ГБ", bytes / (1024.0 * 1024 * 1024)).replace('.', ',');
    }

    private void updateInstallButton() {
        if (installBtn == null) return;
        boolean ok = eulaCheck.isSelected() && installingId == null && ramCombo.getValue() != null;
        if (modeMoveTab.isSelected()) {
            ServerEntry l = selectedLocal();
            ok &= l != null && coreOfLocal(l) != null && l.getVersion() != null;
        } else if (modeBuildTab.isSelected()) {
            Profile p = selectedBuild();
            ok &= p != null && VpsInstaller.Core.fromProfileLoader(p.getModLoader()) != null;
        } else {
            VpsInstaller.Core core = coreCombo.getValue();
            ok &= core != null && versionCombo.getValue() != null
                    && (!core.needsLoaderVersion() || loaderVersionCombo.getValue() != null);
        }
        installBtn.setDisable(!ok);
    }

    private Profile selectedBuild() {
        String name = buildCombo.getValue();
        if (name == null) return null;
        for (Profile p : host.builds()) if (name.equals(p.getName())) return p;
        return null;
    }

    private void refreshLoaderVersions() {
        VpsInstaller.Core core = coreCombo.getValue();
        String mc = versionCombo.getValue();
        boolean need = core != null && core.needsLoaderVersion();
        show(loaderVersionBox, need);
        int seq = ++loaderRequestSeq;
        loaderVersionCombo.setItems(FXCollections.observableArrayList());
        updateInstallButton();
        if (!need || mc == null) return;
        loaderVersionCombo.setPromptText("загружаю…");
        VersionDownloadService vds = host.versionService();
        host.runInBackground(() -> {
            List<String> list;
            try {
                list = switch (core) {
                    case FABRIC -> vds.getFabricVersions(mc);
                    case FORGE -> vds.getForgeVersions(mc);
                    default -> vds.getNeoForgeVersions(mc);
                };
            } catch (Exception e) {
                list = List.of();
            }
            List<String> result = list;
            Platform.runLater(() -> {
                if (seq != loaderRequestSeq) return;
                loaderVersionCombo.setItems(FXCollections.observableArrayList(result));
                if (result.isEmpty()) {
                    loaderVersionCombo.setPromptText(core.label + " для " + mc + " нет");
                } else {
                    loaderVersionCombo.setPromptText(null);
                    loaderVersionCombo.getSelectionModel().selectFirst();
                }
                updateInstallButton();
            });
        });
    }

    private void refreshBuildInfo() {
        Profile p = selectedBuild();
        int seq = ++buildInfoSeq;
        updateInstallButton();
        if (p == null) {
            buildInfoLabel.setText(host.builds().isEmpty() ? "В лаунчере нет сборок." : "");
            return;
        }
        VpsInstaller.Core core = VpsInstaller.Core.fromProfileLoader(p.getModLoader());
        if (core == null) {
            buildInfoLabel.setText("Сборки на " + p.getModLoader() + " панель пока не ставит — только Vanilla, Fabric, "
                    + "Forge и NeoForge.");
            return;
        }
        String head = core.label + " " + p.getVersionId()
                + (core.needsLoaderVersion() && p.getModLoaderVersion() != null ? " (" + p.getModLoaderVersion() + ")" : "");
        if (!core.takesMods()) {
            buildInfoLabel.setText(head + " · моды серверу не нужны");
            return;
        }
        buildInfoLabel.setText(head + " · считаю моды…");
        Path dir = host.modsDirFor(p);
        host.runInBackground(() -> {
            String text;
            try {
                List<Path>[] mods = VpsInstaller.collectServerMods(dir);
                StringBuilder sb = new StringBuilder(head).append(" · модов на сервер: ").append(mods[0].size());
                if (!mods[1].isEmpty()) {
                    sb.append(". Только для клиента — их не повезём: ");
                    for (int i = 0; i < mods[1].size() && i < 4; i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(mods[1].get(i).getFileName());
                    }
                    if (mods[1].size() > 4) sb.append(" и ещё ").append(mods[1].size() - 4);
                }
                text = sb.toString();
            } catch (IOException e) {
                text = head + " · папка модов не читается: " + e.getMessage();
            }
            String t = text;
            Platform.runLater(() -> {
                if (seq == buildInfoSeq) buildInfoLabel.setText(t);
            });
        });
    }

    private void refreshRamOptions(ServerEntry s) {
        Long mem = vpsMemoryMb.get(s.getId());
        List<Integer> opts = new ArrayList<>();
        long limit = mem == null ? Long.MAX_VALUE : mem - VpsInstaller.SYSTEM_RESERVE_MB;
        for (int o : RAM_OPTIONS_MB) if (o <= limit) opts.add(o);
        if (opts.isEmpty() && mem != null) opts.add((int) Math.max(512, limit / 128 * 128));
        Integer prev = ramCombo.getValue();
        ramCombo.setItems(FXCollections.observableArrayList(opts));
        if (prev != null && opts.contains(prev)) {
            ramCombo.getSelectionModel().select(prev);
        } else {
            // По умолчанию — до 4 ГБ: больше ванильному серверу на пару друзей не нужно, а на
            // маленьком VPS важнее оставить память системе.
            Integer pick = null;
            for (int o : opts) if (o <= 4096) pick = o;
            if (pick == null && !opts.isEmpty()) pick = opts.get(0);
            ramCombo.getSelectionModel().select(pick);
        }
        updateInstallButton();
    }

    private void requestVpsMemory(ServerEntry s) {
        String id = s.getId();
        if (vpsMemoryMb.containsKey(id) || !memoryRequests.add(id)) return;
        host.runInBackground(() -> {
            try (VpsSession session = openSession(s)) {
                String kb = session.exec("awk '/^MemTotal:/ {print $2}' /proc/meminfo", Duration.ofSeconds(20)).stdout().trim();
                long mb = Long.parseLong(kb) / 1024;
                Platform.runLater(() -> {
                    vpsMemoryMb.put(id, mb);
                    if (renderedInstallFor != null && id.equals(renderedInstallFor.getId())) refreshRamOptions(renderedInstallFor);
                });
            } catch (VpsException | RuntimeException e) {
                // Не страшно: предложим полный список, а установка сама проверит память на шаге «Проверка».
            } finally {
                Platform.runLater(() -> memoryRequests.remove(id));
            }
        });
    }

    private void onInstall() {
        ServerEntry s = renderedInstallFor;
        if (s == null || installingId != null || !s.isSsh()) return;
        VpsInstaller.Plan plan;
        try {
            plan = readPlan(s);
        } catch (IllegalArgumentException | IOException e) {
            host.showToast(e.getMessage(), "error");
            return;
        }
        startInstall(s, plan);
    }

    /**
     * «Повторить» читает форму заново, а не берёт прошлый план: после «порт занят» человек правит
     * порт и жмёт именно эту кнопку — со старым планом повтор упал бы ровно так же.
     */
    private void onRetryInstall() {
        ServerEntry s = renderedInstallFor;
        if (s == null || installingId != null || !s.getId().equals(lastPlanServerId)) return;
        onInstall();
    }

    private VpsInstaller.Plan readPlan(ServerEntry s) throws IOException {
        if (!eulaCheck.isSelected()) throw new IllegalArgumentException("Отметьте, что принимаете EULA Minecraft");
        Integer ram = ramCombo.getValue();
        if (ram == null) throw new IllegalArgumentException("Выберите память для сервера");
        int port = parsePort(installPortField.getText(), "Порт Minecraft");
        // Ниже 1024 порты привилегированные: сервер от пользователя minecraft их не откроет.
        if (port < 1024) throw new IllegalArgumentException("Порт Minecraft: от 1024 до 65535");
        if (modeMoveTab.isSelected()) {
            ServerEntry l = selectedLocal();
            if (l == null) throw new IllegalArgumentException("Выберите локальный сервер");
            VpsInstaller.Core core = coreOfLocal(l);
            if (core == null) throw new IllegalArgumentException("Серверы на " + l.getLoader() + " панель пока не переносит");
            // Проверка в момент нажатия, а не только в подсказке: мир, который пишется прямо сейчас,
            // упаковался бы наполовину старым, наполовину новым.
            if (host.isLocalServerRunning(l)) {
                throw new IllegalArgumentException("Сервер «" + l.getName() + "» запущен на ПК — остановите его перед переносом");
            }
            Path dir = host.localServerDir(l);
            List<Path> mods = core.takesMods() ? collectLocalMods(dir, host.localModsDir(l)) : List.of();
            String loaderVersion = !core.needsLoaderVersion() ? null
                    : l.getLoaderVersion() != null && !l.getLoaderVersion().isBlank() ? l.getLoaderVersion()
                    : ServerMigration.detectLoaderVersion(dir, core, l.getVersion());
            return new VpsInstaller.Plan(s.getId(), l.getVersion(), core, loaderVersion, ram, port, true, mods, null,
                    Files.isDirectory(dir) ? dir : null);
        }
        if (modeBuildTab.isSelected()) {
            Profile p = selectedBuild();
            if (p == null) throw new IllegalArgumentException("Выберите сборку");
            VpsInstaller.Core core = VpsInstaller.Core.fromProfileLoader(p.getModLoader());
            if (core == null) throw new IllegalArgumentException("Сборки на " + p.getModLoader() + " панель пока не ставит");
            List<Path> mods = core.takesMods() ? VpsInstaller.collectServerMods(host.modsDirFor(p))[0] : List.of();
            return new VpsInstaller.Plan(s.getId(), p.getVersionId(), core,
                    core.needsLoaderVersion() ? p.getModLoaderVersion() : null, ram, port, true, mods, p.getName());
        }
        VpsInstaller.Core core = coreCombo.getValue();
        String mc = versionCombo.getValue();
        if (core == null || mc == null) throw new IllegalArgumentException("Выберите ядро и версию Minecraft");
        String loader = core.needsLoaderVersion() ? loaderVersionCombo.getValue() : null;
        if (core.needsLoaderVersion() && loader == null) throw new IllegalArgumentException("Нет версии " + core.label + " для " + mc);
        return new VpsInstaller.Plan(s.getId(), mc, core, loader, ram, port, true, List.of(), null);
    }

    private void startInstall(ServerEntry s, VpsInstaller.Plan plan) {
        installingId = s.getId();
        lastPlan = plan;
        lastPlanServerId = s.getId();
        lastInstallFailed = false;
        installForm.setDisable(true);
        show(installRetryBtn, false);
        installStatusLabel.setText("");
        installStepsList.getChildren().clear();
        installRows.clear();
        for (VpsInstaller.Step step : VpsInstaller.Step.values()) {
            StepRow row = new StepRow(step.label);
            installRows.put(step, row);
            installStepsList.getChildren().add(row.box);
        }
        show(installStepsList, true);
        updateInstallButton();
        host.log("[VPS] «" + s.getName() + "»: установка " + plan.core().label + " " + plan.mcVersion()
                + (plan.profileName() != null ? " (сборка «" + plan.profileName() + "»)" : "")
                + (plan.migrateDir() != null ? " с переносом сервера с ПК" : "") + "…");

        host.runInBackground(() -> {
            try (VpsSession session = openSession(s)) {
                VpsInstaller.Result r = installer().install(session, plan, this::installStepUi);
                Platform.runLater(() -> finishInstall(s, plan, r));
            } catch (VpsException e) {
                failInstall(s, e.getMessage());
            } catch (RuntimeException e) {
                failInstall(s, "Непредвиденная ошибка: " + e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : ": " + e.getMessage()));
            }
        });
    }

    private void finishInstall(ServerEntry s, VpsInstaller.Plan plan, VpsInstaller.Result r) {
        installingId = null;
        s.setVersion(plan.mcVersion());
        s.setLoader(plan.core().label);
        s.setLoaderVersion(r.loaderVersion());
        s.setRamMb(plan.ramMb());
        s.setPort(plan.port());
        s.setSshServerDir(r.serverDir());
        s.setSshService(r.service());
        s.setSshRconPort(r.rconPort());
        s.setSshJavaMajor(r.javaMajor());
        s.setSshProfile(plan.profileName());
        s.setSshInstalled(true);
        host.saveServers();
        installForm.setDisable(false);
        eulaCheck.setSelected(false);
        host.refreshServerViews();
        // Заново открыть экран: на установленном сервере появятся консоль и панели управления.
        if (renderedInstallFor != null && renderedInstallFor.getId().equals(s.getId())) host.openServerDetail(s.getId());
        host.log("[VPS] «" + s.getName() + "»: Minecraft-сервер установлен и запущен — " + plan.core().label + " "
                + plan.mcVersion() + ", служба " + r.service());
        host.showToast(plan.migrateDir() != null
                ? "Сервер перенесён на «" + s.getName() + "» и запущен"
                : "Minecraft-сервер на «" + s.getName() + "» установлен и запущен", "success");
    }

    private void failInstall(ServerEntry s, String message) {
        Platform.runLater(() -> {
            installingId = null;
            lastInstallFailed = true;
            installForm.setDisable(false);
            boolean shownInRow = false;
            for (StepRow row : installRows.values()) {
                if (row.state == VpsConnector.State.RUNNING) row.set(VpsConnector.State.FAILED, null);
                if (row.state == VpsConnector.State.FAILED && message.equals(row.detail.getText())) shownInRow = true;
            }
            boolean here = renderedInstallFor != null && renderedInstallFor.getId().equals(s.getId());
            installStatusLabel.setText(shownInRow ? "Готовые шаги при повторе пропустятся." : message);
            show(installRetryBtn, here);
            updateInstallButton();
            String first = message.lines().findFirst().orElse(message);
            host.log("[VPS] «" + s.getName() + "»: установка не удалась — " + first);
            if (!here) host.showToast("Установка на «" + s.getName() + "» не удалась: " + first, "error");
        });
    }

    private void installStepUi(VpsInstaller.Step step, VpsConnector.State state, String detail) {
        Platform.runLater(() -> {
            StepRow row = installRows.get(step);
            if (row != null) row.set(state, detail);
        });
    }

    // ================================================================ удаление

    /**
     * Удаляет сервер панели из лаунчера: убирает наш ключ из authorized_keys на VPS (если сервер
     * доступен) и все его секреты на ПК. Сам VPS и файлы на нём не трогаются.
     */
    public void deleteServer(ServerEntry s) {
        if (s.getId().equals(installingId)) {
            host.showToast("Дождитесь конца установки на «" + s.getName() + "»", "info");
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Убрать «" + s.getName() + "» из лаунчера? Лаунчер удалит свой SSH-ключ с VPS и забудет "
                        + "сохранённые пароли. Сам VPS и файлы на нём не трогаются."
                        + (s.isSshInstalled() ? "\n\nMinecraft-сервер на VPS продолжит работать (служба "
                        + s.getSshService() + ", папка " + s.getSshServerDir() + ")." : ""));
        confirm.setHeaderText(null);
        confirm.setTitle("Удаление сервера");
        ButtonType remove = new ButtonType("Удалить", ButtonBar.ButtonData.OK_DONE);
        confirm.getButtonTypes().setAll(remove, CANCEL);
        Optional<ButtonType> r = confirm.showAndWait();
        if (r.isEmpty() || r.get() != remove) return;

        host.runInBackground(() -> {
            String keyNote = null;
            try (VpsSession session = openSession(s)) {
                VpsConnector.removeAuthorizedKey(session, s.getSshPublicKey());
            } catch (VpsException e) {
                keyNote = e.getMessage();
            }
            try {
                secrets.removePrefix(s.secretPrefix());
            } catch (IOException e) {
                host.log("[VPS] Не удалось стереть секреты «" + s.getName() + "»: " + e.getMessage());
            }
            String note = keyNote;
            Platform.runLater(() -> {
                host.removeServerFromList(s);
                if (note == null) {
                    host.showToast("Сервер «" + s.getName() + "» удалён из лаунчера, ключ убран с VPS", "success");
                } else {
                    String comment = s.getSshPublicKey() == null ? "" : s.getSshPublicKey().replaceAll("^\\S+\\s+\\S+\\s*", "");
                    host.showToast("Сервер удалён из лаунчера, но ключ с VPS убрать не удалось (" + note
                            + "). Его можно удалить вручную из ~/.ssh/authorized_keys — строка с подписью " + comment, "info");
                }
            });
        });
    }

    private static void show(javafx.scene.Node n, boolean visible) {
        n.setVisible(visible);
        n.setManaged(visible);
    }
}
