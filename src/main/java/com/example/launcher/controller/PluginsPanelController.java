package com.example.launcher.controller;

import com.example.launcher.agent.AgentClient;
import com.example.launcher.agent.AgentException;
import com.example.launcher.agent.AgentModels;
import com.example.launcher.agent.AgentModels.PluginInstallStatus;
import com.example.launcher.model.BrandPlugin;
import com.example.launcher.model.ServerEntry;
import com.example.launcher.service.BrandPluginCatalogService;

import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.util.List;

/**
 * Owns the "Plugins" section of the Server Detail screen — the brand-plugins catalog
 * ({@code brandPluginsList}): license-key activation, then install onto the server.
 *
 * <p>Used to also own a generic Modrinth-style "add plugin by name" candidate list
 * (LuckPerms/Chunky/EssentialsX/...) — removed, it never did more than append a cosmetic string
 * to {@code ServerEntry.getPlugins()} with no actual install behind it.
 *
 * <p>Split out of MainController (which was ~3300 lines) to keep this self-contained chunk of
 * UI logic separately readable. This is NOT itself an {@code fx:controller} — FXMLLoader only
 * injects {@code @FXML} fields and wires {@code onAction="#method"} handlers into the single
 * class named in main.fxml's {@code fx:controller}, which stays MainController. So the actual
 * {@code @FXML}-annotated field and the handler method main.fxml references by name
 * (`#onActivateBrandPluginKey`) still has to live on MainController; that method is now a
 * one-line delegation to this class. This class holds the FXML node references (passed in via
 * {@link #wire}) and all the actual logic.
 *
 * <p>Talks back to MainController only through the small {@link Host} callback interface below
 * — rather than holding a reference to MainController itself, so this class's own dependencies
 * stay explicit and it doesn't need MainController's full 300+ field surface to compile.
 */
public class PluginsPanelController {

    /** Whatever MainController-side state/behavior this panel needs, kept minimal on purpose. */
    public interface Host {
        ServerEntry findFocusedServer();
        void saveServers();
        void showToast(String message, String type);
        void showOverlay(Node modal);
        void hideOverlay(Node modal);
        /** Same cached-per-server-id AgentClient MainController's own agent calls use. */
        AgentClient agentClientFor(ServerEntry server);
        /** Runs task off the JavaFX thread — same executor MainController's other agent calls use. */
        void runInBackground(Runnable task);
    }

    private final Host host;
    private final BrandPluginCatalogService brandPluginCatalogService;

    /** Latest catalog fetched via brandPluginCatalogService — null until the first fetch resolves. */
    private volatile List<BrandPlugin> brandPluginCatalog = null;

    // FXML nodes, injected via wire() — see class javadoc for why these can't be @FXML here directly.
    private VBox brandPluginsList;
    private TextField brandPluginKeyField;
    private Button brandPluginActivateBtn;
    private Label brandPluginKeyStatusLabel;
    private VBox catalogListBox;

    public PluginsPanelController(Host host, BrandPluginCatalogService brandPluginCatalogService) {
        this.host = host;
        this.brandPluginCatalogService = brandPluginCatalogService;
    }

    /** Call once during MainController.init(), right after FXMLLoader has populated its fields. */
    public void wire(VBox brandPluginsList, TextField brandPluginKeyField,
                      Button brandPluginActivateBtn, Label brandPluginKeyStatusLabel,
                      VBox catalogListBox) {
        this.brandPluginsList = brandPluginsList;
        this.brandPluginKeyField = brandPluginKeyField;
        this.brandPluginActivateBtn = brandPluginActivateBtn;
        this.brandPluginKeyStatusLabel = brandPluginKeyStatusLabel;
        this.catalogListBox = catalogListBox;
    }

    /**
     * Kicks off the catalog fetch once (call from MainController.init()). Always resolves
     * successfully — see BrandPluginCatalogService's javadoc — so no error path needed here.
     */
    public void loadBrandPluginCatalog() {
        brandPluginCatalogService.fetchCatalog().thenAccept(catalog -> Platform.runLater(() -> {
            brandPluginCatalog = catalog;
            renderBrandPluginsList();
            renderPluginCatalog();
        }));
    }

    /**
     * Opens the "Скачать плагины" browse modal — the full catalog (every visible plugin,
     * whether or not this server has activated it), as opposed to {@link #renderBrandPluginsList}
     * which only shows what's already activated. Purchase still happens off-platform on FunPay
     * (see BrandPlugin's javadoc); this screen is discovery, not activation.
     */
    public void onOpenPluginCatalog() {
        renderPluginCatalog();
    }

    private void renderPluginCatalog() {
        catalogListBox.getChildren().clear();
        List<BrandPlugin> catalog = brandPluginCatalog;

        if (catalog == null) {
            Label loading = new Label("Загружаем каталог…");
            loading.getStyleClass().add("server-sub");
            catalogListBox.getChildren().add(loading);
            return;
        }

        List<BrandPlugin> visible = catalog.stream().filter(BrandPlugin::isVisible).toList();
        if (visible.isEmpty()) {
            Label empty = new Label("Пока нет доступных плагинов.");
            empty.getStyleClass().add("server-sub");
            catalogListBox.getChildren().add(empty);
            return;
        }

        for (BrandPlugin plugin : visible) {
            VBox card = new VBox(6);
            card.getStyleClass().add("server-row");

            HBox header = new HBox(10);
            header.setAlignment(Pos.CENTER_LEFT);
            VBox titleBox = new VBox(2);
            Label nameLabel = new Label(plugin.getName());
            nameLabel.getStyleClass().add("server-name");
            titleBox.getChildren().add(nameLabel);
            if (plugin.getDescription() != null && !plugin.getDescription().isBlank()) {
                Label descLabel = new Label(plugin.getDescription());
                descLabel.getStyleClass().add("server-sub");
                descLabel.setWrapText(true);
                titleBox.getChildren().add(descLabel);
            }
            String mcVersions = plugin.getMcVersions();
            String loader = plugin.getLoader();
            if ((mcVersions != null && !mcVersions.isBlank()) || (loader != null && !loader.isBlank())) {
                String compat = java.util.stream.Stream.of(loader, mcVersions)
                        .filter(v -> v != null && !v.isBlank())
                        .collect(java.util.stream.Collectors.joining(" · "));
                Label compatLabel = new Label(compat);
                compatLabel.getStyleClass().add("server-sub");
                titleBox.getChildren().add(compatLabel);
            }
            HBox.setHgrow(titleBox, Priority.ALWAYS);
            header.getChildren().add(titleBox);
            if (plugin.getPrice() != null && !plugin.getPrice().isBlank()) {
                Label priceLabel = new Label(plugin.getPrice());
                priceLabel.getStyleClass().add("server-name");
                header.getChildren().add(priceLabel);
            }
            card.getChildren().add(header);

            String longDesc = plugin.getLongDescription();
            if (longDesc != null && !longDesc.isBlank()) {
                Label longDescLabel = new Label(longDesc);
                longDescLabel.getStyleClass().add("server-sub");
                longDescLabel.setWrapText(true);
                longDescLabel.setVisible(false);
                longDescLabel.setManaged(false);

                Button detailsBtn = new Button("Подробнее");
                detailsBtn.getStyleClass().add("server-action");
                detailsBtn.setOnAction(e -> {
                    boolean nowOpen = !longDescLabel.isVisible();
                    longDescLabel.setVisible(nowOpen);
                    longDescLabel.setManaged(nowOpen);
                    detailsBtn.setText(nowOpen ? "Скрыть" : "Подробнее");
                });

                HBox actions = new HBox(8, detailsBtn, funpayButton(plugin));
                card.getChildren().addAll(actions, longDescLabel);
            } else {
                card.getChildren().add(funpayButton(plugin));
            }

            catalogListBox.getChildren().add(card);
        }
    }

    private Button funpayButton(BrandPlugin plugin) {
        Button buyBtn = new Button("Купить на FunPay");
        buyBtn.getStyleClass().add("server-action");
        buyBtn.setOnAction(e -> {
            try {
                com.example.launcher.service.Os.browse(plugin.getFunpayUrl());
            } catch (Exception ex) {
                host.showToast("Не удалось открыть браузер: " + ex.getMessage(), "error");
            }
        });
        return buyBtn;
    }

    /** Call whenever the Server Detail screen opens/refreshes (plugin list + key field reset). */
    public void onServerDetailOpened() {
        brandPluginKeyField.clear();
        brandPluginKeyStatusLabel.setVisible(false);
        brandPluginKeyStatusLabel.setManaged(false);
        renderBrandPluginsList();
    }

    // ---------- Brand plugins (blauncher.ru catalog, sold on FunPay) ----------

    /**
     * Shows only plugins this server has actually activated a key for — NOT a browse-the-shop
     * catalog of everything purchasable. There's no live shop/discovery endpoint yet (see
     * BrandPluginCatalogService's javadoc), so a full "buy" listing would mostly show unrelated
     * fallback entries; showing just what's activated keeps the tab honest about what it can
     * currently do. Catalog data (name/description) is only used here to label an activated id
     * nicely — an activated id with no matching catalog entry still renders, just plainer.
     */
    public void renderBrandPluginsList() {
        ServerEntry s = host.findFocusedServer();
        List<String> activated = s != null ? s.getActivatedBrandPlugins() : List.of();
        brandPluginsList.getChildren().clear();

        if (activated.isEmpty()) {
            Label empty = new Label("Активированных плагинов нет — введите ключ ниже.");
            empty.getStyleClass().add("server-sub");
            brandPluginsList.getChildren().add(empty);
            return;
        }

        for (String pluginId : activated) {
            BrandPlugin plugin = findInCatalog(pluginId);
            // Admin disabled this plugin in the secret panel's "Плагины" tab (see BrandPlugin's
            // javadoc on isVisible) - treat it as if it didn't exist in the catalog at all, not
            // even the plain-label fallback row other unknown ids get below.
            if (plugin != null && !plugin.isVisible()) continue;

            HBox row = new HBox(10);
            row.getStyleClass().add("server-row");
            row.setAlignment(Pos.CENTER_LEFT);

            if (plugin != null && plugin.getIconUrl() != null && !plugin.getIconUrl().isBlank()) {
                // Loaded through IconLoader rather than `new Image(url, ...)`: JavaFX's own loader
                // cannot decode WebP, which the catalog's icons may well be, and it fails by
                // handing back an error image - leaving a blank square in the row with nothing in
                // any log to explain it. IconLoader returns null instead, and no icon is added.
                final String iconUrl = plugin.getIconUrl();
                host.runInBackground(() -> {
                    Image icon = com.example.launcher.ui.IconLoader.load(iconUrl);
                    if (icon == null) return;
                    ImageView view = new ImageView(icon);
                    view.setFitWidth(32);
                    view.setFitHeight(32);
                    view.setPreserveRatio(true);
                    Platform.runLater(() -> row.getChildren().add(0, view));
                });
            }

            VBox textBox = new VBox(2);
            Label name = new Label(plugin != null ? plugin.getName() : pluginId);
            name.getStyleClass().add("server-name");
            textBox.getChildren().add(name);
            if (plugin != null && plugin.getDescription() != null && !plugin.getDescription().isBlank()) {
                Label desc = new Label(plugin.getDescription());
                desc.getStyleClass().add("server-sub");
                desc.setWrapText(true);
                textBox.getChildren().add(desc);
            }
            HBox.setHgrow(textBox, Priority.ALWAYS);

            VBox statusBox = new VBox(4);
            statusBox.setAlignment(Pos.CENTER_RIGHT);
            Label status = new Label("Активирован");
            status.getStyleClass().add("server-name");
            statusBox.getChildren().add(status);

            if (plugin != null && plugin.getDownloadUrl() != null && !plugin.getDownloadUrl().isBlank()) {
                Label installStatus = new Label();
                installStatus.getStyleClass().add("server-sub");
                installStatus.setVisible(false);
                installStatus.setManaged(false);

                String installedVersion = s.getInstalledPluginVersions().get(pluginId);
                boolean updateAvailable = installedVersion != null && plugin.getLatestVersion() != null
                        && isNewerVersion(plugin.getLatestVersion(), installedVersion);

                boolean isUpdate = installedVersion != null;
                Button actionBtn;
                if (installedVersion == null || updateAvailable) {
                    actionBtn = new Button(installedVersion == null ? "Установить" : "Обновить");
                    actionBtn.setOnAction(e -> {
                        if (isUpdate) {
                            confirmThenInstall(s, plugin, actionBtn, installStatus);
                        } else {
                            installOnVps(s, plugin, actionBtn, installStatus);
                        }
                    });
                } else {
                    actionBtn = new Button("Удалить");
                    actionBtn.setOnAction(e -> uninstallOnVps(s, plugin, actionBtn, installStatus));
                }
                actionBtn.getStyleClass().add("server-action");

                if (s.isLocal()) {
                    // See installOnVps's javadoc — the local-server file layout this would need
                    // to write into isn't wired up yet, only VPS/agent is.
                    actionBtn.setDisable(true);
                    actionBtn.setTooltip(new javafx.scene.control.Tooltip(
                            "Пока не поддерживается для локального сервера — используйте VPS-сервер."));
                } else if (s.isSsh()) {
                    // Каталог плагинов для серверов по SSH ещё не подключён — только ручная загрузка.
                    actionBtn.setDisable(true);
                    actionBtn.setTooltip(new javafx.scene.control.Tooltip(s.isSshInstalled()
                            ? "Для сервера по SSH загрузите .jar плагина в папку plugins через панель «Файлы»."
                            : "Сначала установите Minecraft-сервер на этот VPS."));
                } else if (!s.isAgentConnected()) {
                    actionBtn.setDisable(true);
                    actionBtn.setTooltip(new javafx.scene.control.Tooltip("Сначала подключите агент этого сервера."));
                }
                statusBox.getChildren().addAll(actionBtn, installStatus);
            }

            row.getChildren().addAll(textBox, statusBox);
            brandPluginsList.getChildren().add(row);
        }
    }

    /**
     * Updating overwrites the jar of an already-configured plugin — unlike a fresh install,
     * there's real config on disk that could reset (plugin-specific, the launcher has no idea
     * what each brand plugin does with its own data file). Fresh installs skip this — nothing to
     * lose yet — see the isUpdate branch in renderBrandPluginsList.
     */
    private void confirmThenInstall(ServerEntry s, BrandPlugin plugin, Button installBtn, Label installStatus) {
        javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "Старые настройки плагина «" + plugin.getName() + "» могут не сохраниться после обновления. Обновить?",
                javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        confirm.setHeaderText("Обновление плагина");
        if (installBtn.getScene() != null) {
            confirm.getDialogPane().getStylesheets().addAll(installBtn.getScene().getStylesheets());
        }
        confirm.showAndWait().ifPresent(choice -> {
            if (choice == javafx.scene.control.ButtonType.OK) {
                installOnVps(s, plugin, installBtn, installStatus);
            }
        });
    }

    /**
     * Downloads plugin.getDownloadUrl() through the VPS agent straight into that server's
     * plugins/ folder (AgentClient.startPluginInstall — see AGENT_API.md's
     * "POST /server/plugins/install"). Local-server installs aren't implemented (see the
     * disabled-button branch above in renderBrandPluginsList).
     *
     * <p>On success, restarts the Minecraft server through the agent if it was running — the new
     * jar only actually loads on the next boot, and per the plugin update flow's warning, the
     * player already confirmed this. A server that wasn't running is left alone (nothing to
     * restart; the new jar just loads whenever it's next started).
     */
    private void installOnVps(ServerEntry s, BrandPlugin plugin, Button installBtn, Label installStatus) {
        AgentClient client = host.agentClientFor(s);
        installBtn.setDisable(true);
        installStatus.setText("Загрузка...");
        installStatus.setVisible(true);
        installStatus.setManaged(true);

        host.runInBackground(() -> {
            try {
                client.startPluginInstall(plugin.getDownloadUrl(), plugin.getFileName(), plugin.getSha256());
                client.awaitPluginInstall(Duration.ofSeconds(1), status -> Platform.runLater(() -> {
                    String stage = status.status == PluginInstallStatus.Status.VERIFYING ? "Проверка" : "Загрузка";
                    installStatus.setText(stage + "... " + status.pct + "%");
                }));
                // Never store null here - an absent map entry means "never installed" (see
                // renderBrandPluginsList's installedVersion == null check), so a plugin with
                // no known latestVersion must still record SOMETHING non-null, or the button
                // would wrongly revert to "Установить" on the very next render.
                String versionRecord = plugin.getLatestVersion() != null ? plugin.getLatestVersion() : "unknown";
                // The map write has to happen on the FX thread, not here on the background
                // executor - getInstalledPluginVersions() is a plain (non-thread-safe) HashMap
                // that renderBrandPluginsList() reads from the FX thread; writing to it from a
                // background thread while a render is reading it is a real race, not a
                // theoretical one, since installOnVps runs concurrently with normal UI use.
                Platform.runLater(() -> {
                    s.getInstalledPluginVersions().put(plugin.getId(), versionRecord);
                    host.saveServers();
                    installStatus.setText("Установлено, перезапускаем сервер…");
                });

                boolean wasRunning = client.getStatus().running;
                if (wasRunning) {
                    client.stopServer();
                    client.startServer();
                }

                Platform.runLater(() -> {
                    installBtn.setDisable(false);
                    installStatus.setText(wasRunning ? "Обновлено, сервер перезапущен" : "Установлено");
                    host.showToast(wasRunning
                            ? "Плагин «" + plugin.getName() + "» установлен, сервер перезапущен."
                            : "Плагин «" + plugin.getName() + "» установлен. Он загрузится при следующем запуске сервера.",
                            "success");
                    renderBrandPluginsList(); // переключит кнопку на "Удалить"/"Обновить"
                });
            } catch (AgentException ex) {
                Platform.runLater(() -> {
                    installBtn.setDisable(false);
                    // The jar itself may already be in place even if the restart step below it
                    // failed - re-render so the button reflects what's actually on disk rather
                    // than staying stuck on "Установить"/"Обновить" after a real success.
                    installStatus.setText("Ошибка");
                    host.showToast("Плагин «" + plugin.getName() + "»: " + ex.getMessage(), "error");
                    renderBrandPluginsList();
                });
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Removes a previously-installed jar via the agent (see AgentClient.uninstallPlugin /
     *  AGENT_API.md's "POST /server/plugins/uninstall") and forgets the local install record so
     *  the row goes back to showing "Установить". Requires plugin.getFileName() to still match
     *  what was actually installed — if the admin panel changed the catalog's fileName after
     *  install, this could miss the real file on disk; acceptable edge case, matches the same
     *  "launcher's record can drift from reality" caveat as ServerEntry's own javadoc. */
    private void uninstallOnVps(ServerEntry s, BrandPlugin plugin, Button uninstallBtn, Label installStatus) {
        AgentClient client = host.agentClientFor(s);
        uninstallBtn.setDisable(true);
        installStatus.setText("Удаление...");
        installStatus.setVisible(true);
        installStatus.setManaged(true);

        host.runInBackground(() -> {
            try {
                boolean removed = client.uninstallPlugin(plugin.getFileName());
                Platform.runLater(() -> {
                    s.getInstalledPluginVersions().remove(plugin.getId());
                    host.saveServers();
                    host.showToast(removed
                            ? "Плагин «" + plugin.getName() + "» удалён. Перезапустите сервер."
                            : "Плагин «" + plugin.getName() + "» уже отсутствовал на сервере.", "success");
                    renderBrandPluginsList(); // переключит кнопку обратно на "Установить"
                });
            } catch (AgentException ex) {
                Platform.runLater(() -> {
                    uninstallBtn.setDisable(false);
                    installStatus.setText("Ошибка удаления");
                    host.showToast("Не удалось удалить «" + plugin.getName() + "»: " + ex.getMessage(), "error");
                });
            }
        });
    }

    /** Same dotted-integer version compare convention as UpdateService.isNewer (launcher self-
     *  update) and the plugins' own UpdateChecker.isNewer — duplicated on purpose rather than
     *  shared, consistent with how this codebase already keeps these three independent (see
     *  ECOSYSTEM.md in the plugins repo for the general principle). */
    private static boolean isNewerVersion(String remoteVersion, String currentVersion) {
        try {
            int[] remote = parseVersionParts(remoteVersion);
            int[] current = parseVersionParts(currentVersion);
            int len = Math.max(remote.length, current.length);
            for (int i = 0; i < len; i++) {
                int r = i < remote.length ? remote[i] : 0;
                int c = i < current.length ? current[i] : 0;
                if (r != c) return r > c;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static int[] parseVersionParts(String version) {
        String stripped = version.startsWith("v") || version.startsWith("V") ? version.substring(1) : version;
        String[] parts = stripped.split("\\.");
        int[] result = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            result[i] = Integer.parseInt(parts[i].trim());
        }
        return result;
    }

    /**
     * Case-insensitive on purpose: the admin panel that generates keys (license-server/
     * sehsbatwvrhhfakyacjakxwkw/index.php) does NOT normalize whatever pluginId string was typed
     * in — a key generated for "BLAuction" activates with pluginId exactly "BLAuction", not
     * "blauction". Confirmed directly: this mismatch (catalog id "blauction" vs. real activated
     * "BLAuction") was why the Install button silently never appeared for an otherwise correctly
     * activated key — case-sensitive equals() found no match, findInCatalog returned null, and
     * renderBrandPluginsList's null-plugin branch just shows a plain label with no button.
     */
    private BrandPlugin findInCatalog(String pluginId) {
        if (brandPluginCatalog == null) return null;
        for (BrandPlugin plugin : brandPluginCatalog) {
            if (plugin.getId().equalsIgnoreCase(pluginId)) return plugin;
        }
        return null;
    }

    /**
     * Activation goes through THIS server's own VPS agent (AgentClient#validateLicense), not a
     * direct HTTP call from the launcher — see AgentModels.LicenseValidateResult's javadoc.
     * Calling blauncher.ru directly from here would bind the key to whichever network the
     * player's PC happens to be on (home IP, VPN exit node, whatever) instead of the Minecraft
     * server the plugin actually runs on, which defeats the point of IP-binding entirely.
     * Local servers and not-yet-connected agents can't do this — same restriction as install/
     * uninstall below, and for the same reason (no VPS to proxy through).
     */
    public void onActivateBrandPluginKey() {
        ServerEntry s = host.findFocusedServer();
        if (s == null) return;

        String key = brandPluginKeyField.getText() != null ? brandPluginKeyField.getText().trim() : "";
        if (key.isEmpty()) {
            showBrandPluginKeyStatus("Введите ключ активации.", true);
            return;
        }
        if (s.isLocal()) {
            showBrandPluginKeyStatus("Брендовые плагины пока поддерживаются только для VPS-сервера.", true);
            return;
        }
        if (s.isSsh()) {
            showBrandPluginKeyStatus(s.isSshInstalled()
                    ? "Активация фирменных плагинов для серверов по SSH пока не готова."
                    : "Сначала установите Minecraft-сервер на этот VPS.", true);
            return;
        }
        if (!s.isAgentConnected()) {
            showBrandPluginKeyStatus("Сначала подключите агент этого сервера.", true);
            return;
        }

        AgentClient client = host.agentClientFor(s);
        brandPluginActivateBtn.setDisable(true);
        showBrandPluginKeyStatus("Проверяем ключ…", false);

        host.runInBackground(() -> {
            try {
                AgentModels.LicenseValidateResult result = client.validateLicense(key);
                Platform.runLater(() -> {
                    brandPluginActivateBtn.setDisable(false);
                    if (result.valid && result.pluginId != null) {
                        if (!s.getActivatedBrandPlugins().contains(result.pluginId)) {
                            s.getActivatedBrandPlugins().add(result.pluginId);
                            host.saveServers();
                        }
                        brandPluginKeyField.clear();
                        showBrandPluginKeyStatus("Плагин активирован.", false);
                        renderBrandPluginsList();
                        host.showToast("Плагин активирован на этом сервере", "success");
                    } else {
                        String msg = result.message != null ? result.message : "Ключ не подошёл.";
                        showBrandPluginKeyStatus(msg, true);
                    }
                });
            } catch (AgentException ex) {
                Platform.runLater(() -> {
                    brandPluginActivateBtn.setDisable(false);
                    showBrandPluginKeyStatus("Не удалось проверить ключ: " + ex.getMessage(), true);
                });
            }
        });
    }

    private void showBrandPluginKeyStatus(String text, boolean isError) {
        brandPluginKeyStatusLabel.setText(text);
        brandPluginKeyStatusLabel.getStyleClass().removeAll("error-text");
        if (isError) brandPluginKeyStatusLabel.getStyleClass().add("error-text");
        brandPluginKeyStatusLabel.setVisible(true);
        brandPluginKeyStatusLabel.setManaged(true);
    }
}
