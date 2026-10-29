package com.example.launcher.controller;

import javafx.animation.PauseTransition;
import javafx.util.Duration;
import org.kordamp.ikonli.javafx.FontIcon;
import com.example.launcher.agent.AgentClient;
import com.example.launcher.agent.AgentException;
import com.example.launcher.agent.AgentModels.ConsoleSnapshot;
import com.example.launcher.agent.AgentModels.ServerStatus;
import com.example.launcher.agent.AgentPoller;
import com.example.launcher.model.Friend;
import com.example.launcher.model.FriendInvite;
import com.example.launcher.model.LauncherConfig;
import com.example.launcher.model.ModInfo;
import com.example.launcher.model.Palette;
import com.example.launcher.model.Profile;
import com.example.launcher.model.ServerEntry;
import com.example.launcher.model.UserProfile;
import com.example.launcher.service.AuthService;
import com.example.launcher.service.BrandPluginCatalogService;
import com.example.launcher.service.ConfigService;
import com.example.launcher.service.RustcraftService;
import com.example.launcher.service.CpuWatchdog;
import com.example.launcher.service.CustomSkinApiServer;
import com.example.launcher.service.GameLaunchService;
import com.example.launcher.service.FriendsService;
import com.example.launcher.service.Http;
import com.example.launcher.service.JavaDownloadService;
import com.example.launcher.service.JavaVersionDetector;
import com.example.launcher.service.Os;
import com.example.launcher.service.LocalSkinServer;
import com.example.launcher.service.ModConflictAnalyzer;
import com.example.launcher.service.ModDoctor;
import com.example.launcher.service.MineskinService;
import com.example.launcher.service.ModLoaderService;
import com.example.launcher.service.ModpackService;
import com.example.launcher.service.ModrinthService;
import com.example.launcher.service.OnlineStatsService;
import com.example.launcher.service.PaletteService;
import com.example.launcher.service.ProfileService;
import com.example.launcher.service.ServerMonitorService;
import com.example.launcher.service.ServerService;
import com.example.launcher.service.ServerProps;
import com.example.launcher.service.SkinSourceCatalog;
import com.example.launcher.service.UpdateService;
import com.example.launcher.service.VersionDownloadService;
import com.example.launcher.service.ZeroTierService;
import com.example.launcher.service.ZtInfoServer;
import com.example.launcher.service.vps.SecretStore;
import com.example.launcher.service.vps.ServHostRent;
import com.example.launcher.ui.AppRestart;
import com.example.launcher.ui.RentPicker;
import com.example.launcher.ui.PaletteEditor;
import com.example.launcher.ui.BackgroundPicker;
import com.example.launcher.service.BlCoreService;
import com.example.launcher.ui.SkinEditor;
import com.example.launcher.ui.studio.DisplayStudio;
import com.example.launcher.ui.EndSky;
import com.example.launcher.ui.FxProgress;
import com.example.launcher.ui.Motion;
import com.example.launcher.ui.SkinPreview;
import com.example.launcher.ui.ServerPropsEditor;
import com.example.launcher.ui.SkinSourceTiles;

import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import javax.imageio.ImageIO;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Controller for the redesigned main.fxml — an "icon rail + screens" layout
 * (Home / Builds / Mods / Server / Server-Advanced / Server-Detail /
 * Settings) following blauncher-ui-concept-2-1.html, replacing the previous
 * sidebar+TabPane layout.
 *
 * This controller intentionally does NOT reimplement the launcher's core
 * business logic (auth, version/loader downloads, Modrinth search, actual
 * process launching) — all of that continues to live in the existing
 * services (AuthService, VersionDownloadService, ModLoaderService,
 * ModrinthService, GameLaunchService, JavaDownloadService), which are
 * called here exactly as MainApp already wires them up. What's new here is
 * the screen/navigation model, the build-card presentation of Profile, and
 * the VPS-agent-backed server list (ServerEntry + AgentClient/AgentPoller)
 * alongside the pre-existing local dedicated server flow.
 *
 * <p><b>File map</b> — this file is long (~3000 lines); the line numbers below are a rough
 * guide (they'll drift a bit as the file is edited, but stay close enough to jump to with
 * Ctrl+G / Cmd+L in most editors). Search for the "// ====" banner comment that matches a
 * section name if a line number has drifted too far to be useful. Two related classes are
 * split out into their own files rather than living here: {@link Strings} (the i18n
 * dictionary — pure static data, no controller dependency) and the person's own future
 * additions, if this file grows further.
 *
 * <pre>
 *   ~89   FXML fields              every @FXML field, grouped by screen/modal
 *   ~287  Services                 fields for the existing services MainApp wires up
 *   ~311  Runtime state            builds/servers lists, focused-server tracking, flags
 *   ~363  Init                     init(...), called once from MainApp after FXML loads
 *   ~466  Navigation               goToView(...) + the rail's onNavXxx handlers
 *   ~577  Builds                   build-card rendering, Home/Builds screens, sort/filter
 *   ~787  Play / Launch            onLaunch(), download-then-launch, Java version selection
 *   ~1215 Mods screen              Modrinth search + installing mods into a build
 *   ~1337 Servers                  local server + VPS/agent: list, detail, console, tunnel,
 *                                  network, plugins, create-server modal, delete
 *   ~2122 Modal overlays + toasts  showOverlay/hideOverlay/showToast helpers
 *   ~2173 Create/Edit build modal
 *   ~2265 Import/Export modal      ModShareCodec-backed share links
 *   ~2379 Create Server modal      local/VPS tabs, agent install command, install progress
 *   ~2676 Settings screen          RAM, launch animation, language, theme, account
 *   ~2978 Shutdown                 shutdownBackgroundProcesses(), called from MainApp on close
 * </pre>
 */
public class MainController {

    // ============================================================
    //  FXML fields — must match fx:id values in main.fxml exactly.
    // ============================================================

    // --- Rail navigation ---
    @FXML private ToggleGroup navToggleGroup;
    @FXML private ToggleButton navHomeButton;
    @FXML private ToggleButton navBuildsButton;
    @FXML private ToggleButton navModsButton;
    @FXML private ToggleButton navServerButton;
    @FXML private ToggleButton navFriendsButton;
    @FXML private ToggleButton navRentButton;
    @FXML private ToggleButton navSettingsButton;
    @FXML private ToggleButton navToolsButton;
    @FXML private ToggleButton navProfilesButton;
    @FXML private ScrollPane screenProfiles;
    @FXML private ToggleButton invertMouseToggle;
    @FXML private ToggleButton animationsToggle;
    @FXML private ToggleButton minimizeOnLaunchToggle;
    @FXML private Region railMarker;
    @FXML private javafx.scene.layout.Pane playRippleLayer;
    @FXML private Region backgroundView;
    @FXML private StackPane overlayLayer;

    // --- Screens (exactly one visible at a time — see goToView) ---
    @FXML private ScrollPane screenHome;
    @FXML private Label homeSubLabel;
    @FXML private ScrollPane screenBuilds;
    @FXML private ScrollPane screenFriends;
    @FXML private ScrollPane screenRent;
    @FXML private ScrollPane screenMods;
    @FXML private ScrollPane screenServer;
    @FXML private ScrollPane screenServerAdvanced;
    @FXML private ScrollPane screenServerDetail;
    @FXML private ScrollPane screenSettings;
    @FXML private ScrollPane screenTools;
    @FXML private ScrollPane screenToolHost;

    // --- Tools screen ---
    @FXML private FlowPane toolsGrid;
    @FXML private Label toolHostTitle;
    @FXML private StackPane toolHostContent;
    @FXML private Label toolsTitleLabel;
    @FXML private VBox backgroundPickerHost;
    @FXML private ToggleButton blBadgeToggle;
    @FXML private VBox gameConsoleBody;
    @FXML private Button gameConsoleToggleBtn;

    // --- Screen titles (i18n targets — see tr()/refreshLanguageTexts()) ---
    @FXML private Label homeTitleLabel;
    @FXML private Label buildsTitleLabel;
    @FXML private Label friendsTitleLabel;
    @FXML private Label modsTitleLabel;
    @FXML private Label serverTitleLabel;
    @FXML private Label settingsTitleLabel;

    // --- Home screen ---
    @FXML private StackPane homeVanillaCardHolder;
    @FXML private HBox homeBuildGrid;
    @FXML private ScrollPane homeBuildScroll;
    @FXML private VBox currentBuildPanel;
    @FXML private StackPane selectedIcon;
    @FXML private Label selectedIconLabel;
    @FXML private Label selectedName;
    @FXML private Label selectedSub;
    @FXML private Hyperlink allBuildsLink;
    @FXML private HBox lockedMetaRow;
    @FXML private Label selectedVersionChip;
    @FXML private Label selectedLoaderChip;
    @FXML private VBox editableMetaRow;
    @FXML private HBox homeEngineRow;
    @FXML private ComboBox<String> homeEngineCombo;
    @FXML private ComboBox<String> homeVanillaVersionCombo;
    @FXML private ComboBox<String> homeVanillaLoaderCombo;
    @FXML private Button playButton;
    @FXML private ProgressBar launchProgressBar;
    @FXML private Label playHint;
    @FXML private Button stopButton;
    @FXML private Label homeStatusLabel;
    @FXML private HBox homeAccountRow;
    @FXML private TextField homeUsernameField;
    @FXML private Button homeMicrosoftLoginButton;
    @FXML private org.kordamp.ikonli.javafx.FontIcon homeAccountIcon;
    @FXML private Label homeAccountLabel;

    // --- Builds screen ---
    @FXML private ComboBox<String> buildsSortCombo;
    @FXML private ComboBox<String> buildsLoaderFilterCombo;
    @FXML private FlowPane allBuildsGrid;
    @FXML private Label buildsEmptyStateLabel;

    // --- Server monitoring (Builds & Servers tab) ---
    @FXML private HBox monitorAddRow;
    @FXML private TextField monitorAddressField;
    @FXML private VBox monitorList;
    @FXML private Label monitorEmptyLabel;

    // --- Mods screen ---
    @FXML private TextField modsSearchField;
    @FXML private ComboBox<String> modsContentTypeCombo;
    @FXML private ComboBox<String> modsVersionFilterCombo;
    @FXML private ComboBox<String> modsLoaderFilterCombo;
    @FXML private Button modsSearchButton;
    /** "Импорт .mrpack" — installs a pack the player already has as a file, see onImportMrpack(). */
    /** Меню «Модпак .mrpack»: создать из сборки / импорт (17.10.2026). */
    @FXML private javafx.scene.control.MenuButton modsImportPackBtn;
    @FXML private VBox modsListPanel;

    // --- Server (quick) screen ---
    @FXML private ComboBox<Profile> serverBuildCombo;
    @FXML private Button serverStartBtn;
    @FXML private HBox serverLockedMetaRow;
    @FXML private Label serverVersionChip;
    @FXML private Label serverLoaderChip;
    @FXML private HBox serverEditableMetaRow;
    @FXML private ComboBox<String> serverVanillaVersionCombo;
    @FXML private ComboBox<String> serverVanillaLoaderCombo;
    @FXML private Label serverStatusLabel;
    @FXML private ProgressIndicator serverStartSpinner;
    @FXML private VBox myServersQuickList;

    // --- Play together (ZeroTier overlay network) ---
    @FXML private Button ztPowerBtn;
    @FXML private Button ztInstallBtn;
    @FXML private Button ztRefreshBtn;
    @FXML private Label ztStatusLabel;
    @FXML private HBox ztAddressRow;
    @FXML private TextField ztAddressField;
    @FXML private TextField ztJoinField;
    @FXML private Button ztJoinBtn;
    @FXML private TextField ztNameField;
    @FXML private ComboBox<String> ztTypeCombo;
    @FXML private Button ztCreateBtn;
    @FXML private VBox ztRoomsBox;

    // --- Friends by code (the panel under the rooms on the friends screen) ---
    @FXML private VBox friendsPanel;
    @FXML private Label friendsCountLabel;
    @FXML private Label friendsStatusLabel;
    @FXML private Button friendsRefreshBtn;
    @FXML private TextField friendCodeInput;
    @FXML private Button friendAddBtn;
    @FXML private TextField myFriendCodeField;
    @FXML private Button myFriendCodeCopyBtn;
    @FXML private TextArea myAboutArea;
    @FXML private Button myAboutSaveBtn;
    @FXML private Button myProfileBtn;
    @FXML private StackPane myProfileOverlay;
    @FXML private Label myProfileHeadFallback;
    @FXML private ImageView myProfileHeadImage;
    @FXML private Label myProfileNick;
    @FXML private VBox friendsListBox;
    @FXML private Label friendsEmptyLabel;
    @FXML private VBox friendInvitesBox;
    @FXML private VBox friendInvitesList;
    @FXML private HBox friendCard;
    @FXML private StackPane friendProfileOverlay;
    @FXML private Button friendProfileInviteBtn;
    @FXML private Label friendHeadFallback;
    @FXML private ImageView friendHeadImage;
    @FXML private Label friendCardNick;
    @FXML private HBox friendOnlineChip;
    @FXML private Label friendOnlineLabel;
    @FXML private Label friendCardCode;
    @FXML private Label friendCardAbout;
    @FXML private Button friendRemoveBtn;

    // --- Server Advanced screen ---
    @FXML private Label serverAdvancedSummaryLabel;
    @FXML private VBox serverAdvancedList;

    // --- Server Detail screen ---
    @FXML private Label serverDetailTitle;
    @FXML private Label serverDetailSub;
    @FXML private VBox agentPanel;
    @FXML private Label agentStatusLine;
    @FXML private TextField agentCommandField;
    @FXML private Button agentCheckBtn;
    @FXML private VBox vpsSshPanel;
    @FXML private Label vpsSshInfoLabel;
    @FXML private Label vpsSshHostKeyLabel;
    @FXML private Label vpsSshStatusLabel;
    @FXML private Button vpsSshCheckBtn;
    @FXML private VBox consolePanel;
    @FXML private Button serverDetailToggleBtn;
    @FXML private Button serverDetailHelpBtn;
    @FXML private Button serverMoveToVpsBtn;
    @FXML private ToggleButton consoleChatToggle;
    @FXML private HBox quickCmdRow;
    @FXML private Button quickRestartBtn;
    @FXML private Label vpsCardStatus;
    // Аренда на serv.host: вкладка «Купить сервер», плашка «Ещё нет VPS?» и подсказка в форме VPS.
    @FXML private Label rentTitleLabel;
    @FXML private Label rentOpenedLabel;
    @FXML private Hyperlink rentReopenLink;
    @FXML private HBox rentPromoBox;
    @FXML private Label rentConnectHint;
    @FXML private VBox rentPickerBox;
    @FXML private Label rentStep2Label;
    /** Выбор тарифа на вкладке; создаётся при первом заходе на неё, тогда же грузятся цены. */
    private RentPicker rentPicker;
    /** Что открыли в браузере последним — «Открыть serv.host ещё раз» ведёт туда же. */
    private String rentLastUrl;
    @FXML private TextArea consoleArea;
    @FXML private TextField consoleCommandField;
    @FXML private Button consoleSendBtn;
    @FXML private VBox localAddressBox;
    @FXML private TextField localAddressField;
    @FXML private VBox networkPanel;
    @FXML private VBox networkPanelBody;
    @FXML private VBox reinstallPanel;
    @FXML private ComboBox<String> reinstallVersionCombo;
    @FXML private ComboBox<String> reinstallLoaderCombo;
    @FXML private Button reinstallBtn;
    @FXML private VBox brandPluginsPanel;
    @FXML private VBox brandPluginsList;
    @FXML private TextField brandPluginKeyField;
    @FXML private Button brandPluginActivateBtn;
    @FXML private Label brandPluginKeyStatusLabel;

    // --- Settings screen ---
    @FXML private Slider ramSlider;
    @FXML private Label ramValueLabel;
    @FXML private Label recommendedRamLabel;
    @FXML private TextField jvmArgsField;
    @FXML private Label jvmArgsStatusLabel;
    @FXML private TextField gameArgsField;
    @FXML private Label gameArgsStatusLabel;
    @FXML private TextField autoJoinServerField;
    @FXML private TextField windowWidthField;
    @FXML private TextField windowHeightField;
    @FXML private ToggleButton fullscreenToggle;
    @FXML private Button useRecommendedRamButton;
    @FXML private ToggleButton closeOnLaunchToggle;
    @FXML private ToggleButton showSnapshotsToggle;
    @FXML private ToggleButton offlineModeToggle;
    @FXML private ToggleButton telemetryToggle;
    @FXML private ToggleButton langRuButton;
    @FXML private ToggleButton langEnButton;
    @FXML private VBox themeVioletSwatch;
    @FXML private VBox themeBlackSwatch;
    @FXML private VBox themeWhiteSwatch;
    @FXML private TextField usernameField;
    @FXML private Button microsoftLoginButton;
    @FXML private Label loginStatusLabel;
    @FXML private VBox loginCodeBox;
    @FXML private Label loginCodeLabel;
    @FXML private Button copyCodeButton;
    @FXML private TextArea gameConsoleOutput;
    @FXML private VBox adsContainer;
    @FXML private Label appVersionLabel;

    // --- Create/Edit build modal ---
    @FXML private StackPane createBuildOverlay;
    @FXML private Label createBuildModalTitle;
    @FXML private TextField buildNameField;
    @FXML private ComboBox<String> buildVersionCombo;
    @FXML private ComboBox<String> buildLoaderCombo;
    @FXML private VBox buildLoaderVersionBox;
    @FXML private ComboBox<String> buildLoaderVersionCombo;
    @FXML private Button createBuildConfirmBtn;
    @FXML private VBox editBuildModsSection;
    @FXML private HBox serverMetaRow;
    @FXML private ComboBox<String> homeServerBuildCombo;
    @FXML private Label homeServerHint;
    @FXML private Hyperlink buildLaunchToggleLink;
    @FXML private VBox buildLaunchBox;
    @FXML private TextField buildMemoryField;
    @FXML private TextField buildServerField;
    @FXML private TextField buildJvmArgsField;
    @FXML private TextField buildGameArgsField;
    @FXML private VBox editBuildModsList;
    @FXML private Hyperlink deleteBuildLink;

    // --- Import/Export modal ---
    @FXML private StackPane shareOverlay;
    @FXML private ToggleButton shareTabExport;
    @FXML private ToggleButton shareTabImport;
    @FXML private VBox exportPanel;
    @FXML private VBox exportBuildRow;
    @FXML private Label exportEmptyStateLabel;
    @FXML private TextField exportLinkField;
    @FXML private Button exportCopyBtn;
    @FXML private VBox importPanel;
    @FXML private TextField importLinkField;
    @FXML private Button importConfirmBtn;
    @FXML private Label importStatusLabel;
    @FXML private HBox importProgressRow;
    @FXML private ProgressBar importProgressBar;
    @FXML private Label importProgressLabel;
    /** Идёт скачивание модов импортированной сборки — не сбрасывать статус и не импортировать второй раз. */
    private boolean importBusy;

    // --- Create server modal ---
    @FXML private StackPane createServerOverlay;
    @FXML private ToggleButton serverTypeTabLocal;
    @FXML private ToggleButton serverTypeTabVps;
    @FXML private VBox createLocalPanel;
    /** Плитки server.properties в окне «Новый сервер» (17.10.2026). */
    @FXML private VBox newServerPropsBox;
    private ServerPropsEditor newServerPropsEditor;
    @FXML private TextField newServerNameField;
    @FXML private TextField newServerPortField;
    @FXML private ComboBox<String> newServerVersionCombo;
    @FXML private ComboBox<String> newServerLoaderCombo;
    @FXML private Slider newServerRamSlider;
    @FXML private Label newServerRamValueLabel;
    // VPS по SSH — логика в VpsPanelController, здесь только узлы для wire().
    @FXML private VBox createVpsStep1Panel;
    @FXML private TextField newVpsNameField;
    @FXML private TextField newVpsHostField;
    @FXML private TextField newVpsSshPortField;
    @FXML private TextField newVpsUserField;
    @FXML private ToggleButton newVpsAuthPasswordTab;
    @FXML private ToggleButton newVpsAuthKeyTab;
    @FXML private VBox newVpsPasswordBox;
    @FXML private PasswordField newVpsPasswordField;
    @FXML private VBox newVpsKeyBox;
    @FXML private TextField newVpsKeyFileField;
    @FXML private PasswordField newVpsKeyPassphraseField;
    @FXML private PasswordField newVpsSudoPasswordField;
    @FXML private TextField newVpsPortField;
    @FXML private VBox createVpsStep2Panel;
    @FXML private VBox newVpsStepsList;
    @FXML private Label newVpsConnectionStatusLabel;
    @FXML private Button newVpsRetryBtn;
    @FXML private Button newVpsBackBtn;
    @FXML private Button createServerConfirmBtn;
    @FXML private Button createServerCloseBtn;
    @FXML private VBox serverSetupProgressBox;
    @FXML private Label serverSetupProgressLabel;
    @FXML private ProgressBar serverSetupProgressBar;

    // --- Toasts ---
    @FXML private VBox toastStack;

    // --- Update modal ---
    @FXML private StackPane updateOverlay;
    @FXML private Label updateModalTitle;
    @FXML private Label updateVersionLabel;
    @FXML private Label updateChangelogLabel;
    @FXML private VBox updateProgressBox;
    @FXML private Label updateProgressLabel;
    @FXML private ProgressBar updateProgressBar;
    @FXML private Button updateLaterBtn;
    @FXML private Button updateNowBtn;

    // --- Server commands modal ---
    @FXML private StackPane commandsOverlay;
    @FXML private VBox commandsModalCard;
    @FXML private Label commandsModalTitle;
    @FXML private VBox commandsListBox;

    // --- Plugin catalog browse modal ("Скачать плагины") ---
    @FXML private StackPane catalogOverlay;
    @FXML private VBox catalogModalCard;
    @FXML private VBox catalogListBox;

    // --- Profiles modal (Home "Профили") ---
    @FXML private VBox profilesList;
    @FXML private StackPane paletteOverlay;
    @FXML private VBox paletteEditorRows;
    @FXML private Button addProfileButton;

    // --- Profile editor modal ---
    @FXML private StackPane profileEditorOverlay;
    @FXML private VBox profileEditorCard;
    @FXML private Label profileEditorTitle;
    @FXML private TextField profileEditorNameField;
    @FXML private ImageView profileSkinPreview;
    @FXML private ImageView profileCapePreview;
    @FXML private Button profileSkinUploadBtn;
    @FXML private Button profileCapeUploadBtn;
    @FXML private TextField profileSourceNickField;
    @FXML private Button profileDeleteBtn;
    // Source chips: one selectable chip per site a skin or a cape can be taken from.
    @FXML private FlowPane profileSkinSourcesPane;
    @FXML private FlowPane profileCapeSourcesPane;
    @FXML private TextField profileSourceUrlField;
    @FXML private Button profileSourceUrlSkinBtn;
    @FXML private Button profileSourceUrlCapeBtn;
    /** The chips are built once and only rebuilt when the language changes under them. */
    private boolean profileSourcesBuilt;
    /** The chosen site per row, remembered for the session so reopening the editor keeps it. */
    private String selectedSkinSourceId;
    private String selectedCapeSourceId;

    // ============================================================
    //  Services (unchanged — wired by MainApp exactly as before)
    // ============================================================

    private LauncherConfig config;
    private ConfigService configService;
    private VersionDownloadService versionDownloadService;
    private ProfileService profileService;
    private ModLoaderService modLoaderService;
    private GameLaunchService gameLaunchService;
    private final BrandPluginCatalogService brandPluginCatalogService = new BrandPluginCatalogService();
    private final MineskinService mineskinService = new MineskinService();
    /** Owns the Plugins section of Server Detail (generic + brand plugins) — see AGENTS.md. */
    private final PluginsPanelController pluginsPanelController =
            new PluginsPanelController(new PluginsPanelController.Host() {
                @Override public ServerEntry findFocusedServer() { return findServer(focusedServerId); }
                @Override public void saveServers() { MainController.this.saveServers(); }
                @Override public void showToast(String message, String type) { MainController.this.showToast(message, type); }
                @Override public void showOverlay(Node modal) { MainController.this.showOverlay(modal); }
                @Override public void hideOverlay(Node modal) { MainController.this.hideOverlay(modal); }
                @Override public AgentClient agentClientFor(ServerEntry server) { return MainController.this.agentClientFor(server); }
                @Override public void runInBackground(Runnable task) { executor.submit(task); }
            }, brandPluginCatalogService);
    /** Секреты панели VPS (SSH-ключи, пароль sudo) — только на этом ПК, под DPAPI. */
    private final SecretStore vpsSecrets = new SecretStore(Path.of("config", "vps_secrets.json"));
    /** Панель VPS по SSH: добавление сервера и блок подключения на экране сервера. */
    private final VpsPanelController vpsPanelController =
            new VpsPanelController(new VpsPanelController.Host() {
                @Override public void addServer(ServerEntry s) { servers.add(s); }
                @Override public void saveServers() { MainController.this.saveServers(); }
                @Override public void refreshServerViews() {
                    renderServerAdvancedList();
                    renderMyServersQuickList();
                    if (focusedServerId != null && screenServerDetail.isVisible()) renderServerDetailHeader();
                }
                @Override public void removeServerFromList(ServerEntry s) { MainController.this.removeServerFromList(s); }
                @Override public void showVpsStep(int step) { MainController.this.showVpsStep(step); }
                @Override public void closeCreateModal() { hideOverlay(createServerOverlay); }
                @Override public void openServerDetail(String id) { MainController.this.openServerDetail(id); }
                @Override public void showToast(String message, String type) { MainController.this.showToast(message, type); }
                @Override public void log(String line) { MainController.this.log(line); }
                @Override public void runInBackground(Runnable task) { executor.submit(task); }
                @Override public javafx.stage.Window window() { return createServerOverlay.getScene().getWindow(); }
                @Override public List<String> minecraftVersions() { return new ArrayList<>(versionListCache); }
                @Override public List<Profile> builds() { return new ArrayList<>(builds); }
                @Override public Path modsDirFor(Profile build) { return gameDir.resolve("mods").resolve(build.getName()); }
                @Override public VersionDownloadService versionService() { return versionDownloadService; }
                @Override public List<ServerEntry> localServers() {
                    List<ServerEntry> out = new ArrayList<>();
                    for (ServerEntry e : servers) if (e.isLocal()) out.add(e);
                    return out;
                }
                @Override public Path localServerDir(ServerEntry l) { return gameLaunchService.serverDirForName(l.getName()); }
                @Override public Path localModsDir(ServerEntry l) { return gameDir.resolve("mods").resolve(l.getName()); }
                @Override public boolean isLocalServerRunning(ServerEntry l) { return gameLaunchService.isServerRunning(l.getName()); }
                @Override public void stat(String event, String detail) { MainController.this.stat(event, detail); }
                @Override public String tr(String key) { return MainController.this.tr(key); }
                @Override public List<ServerEntry> allServers() { return new ArrayList<>(servers); }
                @Override public void openUrl(String url) {
                    // browse() может подвиснуть на пару секунд, пока стартует браузер — не в UI-потоке.
                    executor.submit(() -> {
                        try {
                            Os.browse(url);
                        } catch (Exception e) {
                            Platform.runLater(() -> showToast("Не удалось открыть браузер. Ссылка: " + url, "info"));
                        }
                    });
                }
            }, vpsSecrets);
    /** Управление установленным сервером панели VPS: статус, консоль, игроки, файлы, бэкапы. */
    private final VpsManageController vpsManageController =
            new VpsManageController(new VpsManageController.Host() {
                @Override public void runInBackground(Runnable task) { executor.submit(task); }
                @Override public void showToast(String message, String type) { MainController.this.showToast(message, type); }
                @Override public void log(String line) { MainController.this.log(line); }
                @Override public javafx.stage.Window window() { return screenServerDetail.getScene().getWindow(); }
                @Override public void saveServers() { MainController.this.saveServers(); }
                @Override public void refreshServerViews() {
                    if (focusedServerId != null && screenServerDetail.isVisible()) {
                        renderServerDetailHeader();
                        renderConsole();
                    }
                    renderServerAdvancedList();
                    renderMyServersQuickList();
                }
                @Override public boolean isFocused(String serverId) { return serverId.equals(focusedServerId); }
                @Override public void consoleClear() { consoleArea.clear(); }
                @Override public void consoleAppend(String line) {
                    appendCapped(consoleArea, line + "\n");
                }
                @Override public com.example.launcher.service.vps.VpsSession openSession(ServerEntry s)
                        throws com.example.launcher.service.vps.VpsException {
                    return vpsPanelController.openSession(s);
                }
                @Override public void notifyDesktop(String title, String text, boolean error) {
                    notifyIfAway(title, text, error);
                }
                @Override public String rconPassword(ServerEntry s) throws IOException {
                    return vpsSecrets.get(s.secretRconName());
                }
                @Override public String tr(String key) { return MainController.this.tr(key); }
            });
    private ModrinthService modrinthService;
    /** Reads/installs .mrpack archives - see ModpackService's javadoc for the format. */
    private ModpackService modpackService;
    private JavaVersionDetector javaVersionDetector;
    private JavaDownloadService javaDownloadService;
    private AuthService authService;
    /** Файл аккаунта, к которому сейчас привязан {@link #authService} — см.
     *  switchAccountToCurrentProfile(): у каждого профиля он свой. */
    private Path currentAccountFile;
    private OnlineStatsService onlineStatsService;
    private final UpdateService updateService = new UpdateService();
    private UpdateService.UpdateManifest pendingUpdateManifest;
    private ServerService serverService;
    private Path gameDir;
    private ZeroTierService zeroTierService;
    /** volatile: поле читают и UI-поток, и фоновый поллер ZeroTier. Жизненный цикл — под
     *  ztInfoServerLock, см. startZtInfoServerIfNeeded. */
    private volatile ZtInfoServer ztInfoServer;
    private final Object ztInfoServerLock = new Object();
    /** Пользователь явно включил ZeroTier кнопкой «Включить» — до этого никакой автоподключения
     *  и телефонного/Android обнаружения быть не должно. */
    private volatile boolean zeroTierPowerOn;
    /** Last known ZeroTier VPN IP, cached by refreshZeroTierPanel() so the server detail's join
     *  address can prefer it over the router-LAN IP without blocking the FX thread on a call. */
    private volatile String lastZeroTierIp;
    /** Live status poller for the play-together panel - rooms spend 10-30 s in
     *  "настраивается..." after a join, and the assigned IP should appear on its own. */
    private Motion.Every ztPoller;
    /** Отпечаток последнего отрисованного состава комнат: панель перестраивается только когда
     *  комнаты/статусы/IP реально изменились, а не каждый тик поллера (иначе строки мигали и
     *  «ядра»/задачи копились). Сбрасывается при ручном обновлении. */
    private String lastZtRoomsFingerprint = "\u0000";
    /** Уже показанные уведомления о запросах на вход (nwid:address), чтобы не спамить тостом
     *  каждые 5 секунд, пока владелец не одобрил/не отклонил запрос. */
    private final java.util.Set<String> notifiedZtPendingRequests = new java.util.HashSet<>();
    private Stage primaryStage;

    // --- Friends by code (FriendsService) ---
    /** Identity, friends list and «о себе» live in <gameDir>/friends.json — see FriendsService. */
    private FriendsService friendsService;
    /** 30 s heartbeat: keeps the friends list, the online dots and friends' «о себе» current. */
    private Motion.Every friendsPoller;
    /** Last rendered friends list - what the panel paints from, online or not. */
    private final List<Friend> friends = new ArrayList<>();
    /** Invitations into ZeroTier rooms that are waiting for our answer, as of the last sync. */
    private final List<FriendInvite> invites = new ArrayList<>();
    /** Invite ids already announced with a toast, so the 30-second poll cannot repeat itself. */
    private final java.util.Set<Long> announcedInviteIds = new java.util.HashSet<>();
    /** False until this session's first sync has been applied — see applyFriendsSnapshot. */
    private boolean firstFriendsSnapshotDone;

    // --- Аватарки модов в «карандашике» (правка сборки) ---
    // Установленный мод — это обычный .jar в папке сборки: ни icon_url, ни описания у него нет,
    // поэтому иконка ищется по цепочке: записанный projectId (мод ставили через лаунчер) → хэш
    // файла через Modrinth (мод скачали с Modrinth вручную) → заглушка (мод с CurseForge или
    // своей сборки). Найденное кэшируется: список перерисовывается после каждого «Удалить», и без
    // кэша это была бы повторная загрузка всех иконок на каждое нажатие.
    private final Map<String, String> modIconUrls = new HashMap<>();
    private final Map<String, Image> modIconImages = new HashMap<>();
    private final Map<String, String> modProjectIdByFile = new HashMap<>();
    /** sha1 по ключу «имя|размер|дата»: хэшировать десятки мегабайт на каждую перерисовку ни к чему. */
    private final Map<String, String> sha1Cache = new HashMap<>();

    // --- Плавная прокрутка полосы сборок на главной ---
    /** Текущее (анимируемое) значение прокрутки полосы; hvalue выставляется его слушателем. */
    private final javafx.beans.property.DoubleProperty stripScrollValue =
            new javafx.beans.property.SimpleDoubleProperty();
    private javafx.animation.Timeline stripScrollAnimation;
    /** Friend code of the card currently open under the list, or null. */
    private String selectedFriendCode;
    /** Row per friend code, so clicking one just moves the "selected" class instead of rebuilding. */
    private final Map<String, HBox> friendRows = new HashMap<>();
    /** Guards against a slow sync overlapping the next tick of the poller. */
    private volatile boolean friendsSyncInFlight;
    /** Set when an action asked for a refresh while one was already in flight — see refreshFriendsPanel. */
    private boolean friendsRefreshQueued;
    /** Seconds left on the "Обновить" cooldown; 0 means the button is live (see onRefreshFriends). */
    private int friendsRefreshCooldownLeft;
    private javafx.animation.Timeline friendsRefreshCooldown;
    /** Fingerprint of the last painted list; a poller tick with no changes leaves the rows alone. */
    private String lastFriendsFingerprint = "\u0000";
    /** «о себе» as last saved — an edit in progress is never overwritten by an incoming sync. */
    private String savedAbout = "";
    /** The skin file already handed to MineSkin this session, so a 30 s poll never re-uploads it. */
    private String publishedSkinPath;
    /** The texture URL that upload produced — reused for every later sync of this session. */
    private volatile String publishedSkinUrl;
    /** code|skinUrl|nick of the head currently in the card, so an unchanged card skips the disk. */
    private String loadedHeadKey = "";
    /** "Удалить из друзей" asks a second time before it does anything — see onRemoveSelectedFriend. */
    private boolean friendRemoveArmed;
    private javafx.animation.PauseTransition friendRemoveDisarm;

    /** Общий пул фоновых задач: запуск игры, поиск и установка модов, вызовы агента, иконки.
     *  Потоки daemon — иначе зависшее сетевое чтение не даёт JVM выйти (прерывание его не
     *  разрывает), и закрытое окно оставляло живой javaw.exe. */
    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "bl-worker");
        t.setDaemon(true);
        return t;
    });

    /**
     * Иконки результатов поиска модов — отдельно от {@link #executor}. Поиск разом ставит в очередь
     * 50 иконок, и 14.10.2026 «+» или импорт по коду, нажатые сразу после поиска, ждали за ними
     * 25 секунд (проверка через VPN): загрузка мода стояла в очереди за картинками.
     */
    private final ExecutorService iconExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "bl-icons");
        t.setDaemon(true);
        return t;
    });

    /**
     * Отдельный однопоточный пул только под опрос ZeroTier.
     *
     * <p>Опрос идёт каждые 5 секунд, а один его проход — это последовательность обращений к
     * локальному движку с таймаутом 15 секунд каждое, плюс запрос на каждого участника каждой
     * комнаты. Когда движок установлен, но не отвечает (штатное состояние, у панели есть для
     * него отдельная ветка), проход занимает десятки секунд, а новые приходят каждые 5 — и
     * раньше они ложились в ОБЩИЙ пул из четырёх потоков. Через полминуты пул был занят целиком,
     * и человек, нажав «Играть», видел «Подготовка…» и дальше ничего: задача запуска стояла в
     * очереди за опросами ZeroTier. Теперь таймауты ZeroTier никого больше не задевают.
     */
    private final ExecutorService ztExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "zt-poll");
        t.setDaemon(true);
        return t;
    });

    /** Опрос ZeroTier уже выполняется — новый тик пропускаем, как это делает поллер друзей
     *  через friendsSyncInFlight. Без этого медленные проходы накладывались друг на друга. */
    private final java.util.concurrent.atomic.AtomicBoolean ztRefreshInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Обход папок модов уже идёт. Он копирует файлы, поэтому два одновременных прохода
     *  мешали бы друг другу, а нажать «Сборки» несколько раз подряд — обычное дело. */
    private final java.util.concurrent.atomic.AtomicBoolean buildsScanInFlight =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Идёт включение или выключение ZeroTier. Нужен, чтобы поллер, который каждые 5 секунд
     *  перерисовывает панель, не включал кнопку обратно посреди установки. */
    private volatile boolean ztPowerBusy;

    /** Та же задержка для поля ника: иначе конфиг писался на каждую нажатую клавишу. */
    private final javafx.animation.PauseTransition usernameSaveDebounce =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(400));

    /** Откладывает запись настроек на 400 мс после последнего шевеления слайдера памяти:
     *  иначе одно перетаскивание означало десятки синхронных записей JSON в UI-потоке. */
    private final javafx.animation.PauseTransition ramSaveDebounce =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(400));

    /** Запись настроек без всплывающих сообщений: не получилось — пишем в лог и живём дальше.
     *  Настройки не та вещь, из-за которой стоит прерывать человеку работу диалогом. */
    /**
     * Панель «Запуск игры» в Настройках (11.10.2026): размер окна, полноэкранный режим, автовход
     * на сервер, свои аргументы Java (с готовыми наборами) и игры. Текстовые поля сохраняются,
     * когда теряют фокус или по Enter; под полями аргументов — что именно уйдёт в игру.
     */
    private void initLaunchFields() {
        if (jvmArgsField == null) return;
        bindConfigText(jvmArgsField, config::getJvmArgs, v -> { config.setJvmArgs(v); renderArgsStatus(); },
                "Аргументы Java");
        bindConfigText(gameArgsField, config::getGameArgs, v -> { config.setGameArgs(v); renderArgsStatus(); },
                "Аргументы игры");
        bindConfigText(autoJoinServerField, config::getAutoJoinServer, config::setAutoJoinServer, "Автовход на сервер");
        bindConfigText(windowWidthField, () -> config.getWindowWidth() > 0 ? String.valueOf(config.getWindowWidth()) : "",
                v -> config.setWindowWidth(parseWindowSide(v)), "Ширина окна");
        bindConfigText(windowHeightField, () -> config.getWindowHeight() > 0 ? String.valueOf(config.getWindowHeight()) : "",
                v -> config.setWindowHeight(parseWindowSide(v)), "Высота окна");
        fullscreenToggle.setSelected(config.isFullscreen());
        renderArgsStatus();
    }

    /** Поле ↔ строка конфига: сохранить, когда поле потеряло фокус или нажат Enter, и только если изменилось. */
    private void bindConfigText(TextField field, java.util.function.Supplier<String> get,
                                java.util.function.Consumer<String> set, String what) {
        field.setText(get.get());
        Runnable commit = () -> {
            String text = field.getText() == null ? "" : field.getText().trim();
            if (text.equals(get.get())) return;
            set.accept(text);
            saveConfigQuietly();
            field.setText(get.get());   // показать, как значение понято (например, «abc» в ширине → пусто)
            log("[SETTINGS] " + what + ": " + (get.get().isEmpty() ? "нет" : get.get()));
        };
        field.setOnAction(e -> commit.run());
        field.focusedProperty().addListener((o, was, now) -> { if (!now) commit.run(); });
    }

    /** Сторона окна из поля: число от 320 до 7680, иначе 0 (не задано). */
    private static int parseWindowSide(String v) {
        try {
            int n = Integer.parseInt(v.trim());
            return n >= 320 && n <= 7680 ? n : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void renderArgsStatus() {
        renderArgsStatus(jvmArgsStatusLabel, config.getJvmArgs(), GameLaunchService.parseJvmArgs(config.getJvmArgs()),
                "каждый должен начинаться с «-»");
        if (JVM_PRESET_G1.equals(config.getJvmArgs())) {
            jvmArgsStatusLabel.setText("Набор «Оптимизация (G1)»: "
                    + GameLaunchService.parseJvmArgs(JVM_PRESET_G1).size() + " флагов, работает на любой версии игры");
        }
        renderArgsStatus(gameArgsStatusLabel, config.getGameArgs(), GameLaunchService.parseGameArgs(config.getGameArgs()),
                "ник, токен и папки задаёт лаунчер");
    }

    /** Под полем аргументов: что уйдёт в игру и пропущено ли что-то. Поле пустое — подпись скрыта. */
    static void renderArgsStatus(Label label, String text, List<String> used, String whySkipped) {
        if (label == null) return;
        boolean show = text != null && !text.isBlank();
        label.setVisible(show);
        label.setManaged(show);
        if (!show) return;
        int total = GameLaunchService.splitJvmArgs(text).size();
        String msg = used.isEmpty() ? "Ничего не уйдёт в игру — " + whySkipped
                : "В игру уйдёт: " + String.join(" ", used);
        if (!used.isEmpty() && total > used.size()) msg += " · часть пропущена (" + whySkipped + ")";
        label.setText(msg);
    }

    /** Флаги Aikar для G1 без -XX:+AlwaysPreTouch (на клиенте он только затягивает запуск).
     *  Проверено 11.10.2026 на Java 8, 17, 21 и 25 — все принимают. */
    private static final String JVM_PRESET_G1 = "-XX:+UseG1GC -XX:+ParallelRefProcEnabled -XX:MaxGCPauseMillis=200"
            + " -XX:+UnlockExperimentalVMOptions -XX:+DisableExplicitGC -XX:G1NewSizePercent=30"
            + " -XX:G1MaxNewSizePercent=40 -XX:G1HeapRegionSize=8M -XX:G1ReservePercent=20"
            + " -XX:G1HeapWastePercent=5 -XX:G1MixedGCCountTarget=4 -XX:InitiatingHeapOccupancyPercent=15"
            + " -XX:G1MixedGCLiveThresholdPercent=90 -XX:G1RSetUpdatingPauseTimePercent=5 -XX:SurvivorRatio=32"
            + " -XX:+PerfDisableSharedMem -XX:MaxTenuringThreshold=1";
    /** Java 8 ZGC не знает — для старых версий игры GameLaunchService этот флаг сам убирает. */
    private static final String JVM_PRESET_ZGC = "-XX:+UseZGC";

    @FXML private void onJvmPresetG1() { applyJvmPreset(JVM_PRESET_G1, "Оптимизация (G1)"); }
    @FXML private void onJvmPresetZgc() { applyJvmPreset(JVM_PRESET_ZGC, "ZGC"); }
    @FXML private void onJvmPresetClear() { applyJvmPreset("", "нет"); }

    private void applyJvmPreset(String args, String name) {
        config.setJvmArgs(args);
        jvmArgsField.setText(args);
        saveConfigQuietly();
        renderArgsStatus();
        log("[SETTINGS] Аргументы Java: " + name);
    }

    @FXML private void onWindowPreset720() { applyWindowSize(1280, 720); }
    @FXML private void onWindowPreset1080() { applyWindowSize(1920, 1080); }

    private void applyWindowSize(int w, int h) {
        config.setWindowWidth(w);
        config.setWindowHeight(h);
        windowWidthField.setText(String.valueOf(w));
        windowHeightField.setText(String.valueOf(h));
        saveConfigQuietly();
    }

    @FXML
    private void onToggleFullscreen() {
        config.setFullscreen(fullscreenToggle.isSelected());
        saveConfigQuietly();
    }

    private void saveConfigQuietly() {
        try {
            configService.saveConfig(config);
        } catch (Exception e) {
            log("[SETTINGS] Не удалось сохранить настройки: " + e.getMessage());
        }
    }

    // ============================================================
    //  Runtime state
    // ============================================================

    /** All saved builds/profiles — same concept as the old "groups" list. */
    private final List<Profile> builds = new ArrayList<>();
    /** Для статистики «до кнопки Играть»: экраны, открытые за сессию, и было ли нажатие. */
    private final java.util.Set<String> screensSeen = new java.util.HashSet<>();
    private final long sessionStartedAt = System.currentTimeMillis();
    private volatile boolean playClicked;
    /**
     * Трасса сессии для статистики (22.10.2026). Раньше уходил только первый заход на каждый экран,
     * и по отметкам «человек просто ушёл» нельзя было понять ни порядка, ни где он провёл время, ни
     * какие ошибки ему показали. Теперь: каждый переход (откуда и сколько секунд там пробыл), каждое
     * всплывающее предупреждение (место в коде, без текста) и последний экран перед закрытием.
     * Потолки — чтобы одна долгая сессия не съела дневной лимит event.php.
     */
    private String currentScreen;
    private long currentScreenSince = sessionStartedAt;
    private volatile long lastStatAt = sessionStartedAt;
    private int screenStats, msgStats, searchStats;
    /**
     * Нажатия на текущем экране: «что трогал» по fx:id, в порядке первого нажатия (28.10.2026).
     * По переходам между экранами было видно, ГДЕ человек сидел, но не ЧТО он там делал: новички
     * 41baf131 и 2e2db5f5 по 2–5 минут ходили по меню и ушли, а что искали — неизвестно. Уходит
     * одним событием «ui» при смене экрана и при закрытии; текст полей и названия сборок не шлём.
     */
    private final java.util.LinkedHashMap<String, Integer> uiClicks = new java.util.LinkedHashMap<>();
    private int uiStats;
    private String lastMsgAt = "";
    private long lastMsgTime;
    /** Currently selected build on Home/Server ("no build / vanilla" = null). */
    private Profile selectedBuild;
    private static final String VANILLA_LABEL = "Без модов и сборок";

    /** Saved launcher profiles (nickname + optional skin/cape). */
    private final List<UserProfile> userProfiles = new ArrayList<>();
    /** Profile currently being edited in the profile editor modal, or null for "new profile". */
    private UserProfile currentEditingProfile;

    /** All known servers (local + VPS), persisted via ServerService. */
    private final List<ServerEntry> servers = new ArrayList<>();
    /** id of the server currently open in screenServerDetail, or null. */
    private String focusedServerId;
    /** id of whichever server's log is currently shown in consoleArea — see renderConsole(), which
     *  only wipes the text when this differs from focusedServerId (a genuine server switch), not
     *  just because the currently-focused local server happens to be stopped right now. */
    private String consoleAreaServerId;
    /** One AgentClient per VPS server that's had its agent connection confirmed at least once. */
    private final Map<String, AgentClient> agentClients = new HashMap<>();
    /** Active poller for whichever server is currently focused in screenServerDetail. */
    private AgentPoller focusedPoller;
    /** Public-server monitor for the "Сборки и сервера" tab — persisted via monitorFile. */
    private final ServerMonitorService serverMonitorService = new ServerMonitorService();
    private final Path monitorFile = Path.of("launcher_root", "monitor_servers.json");
    private final List<String> monitoredServers = new ArrayList<>();
    /** Addresses of monitored servers the player liked — these show as cards on Home, like builds. */
    private final java.util.Set<String> likedMonitoredServers = new java.util.LinkedHashSet<>();
    /** Режим сервера на главной: адрес выбранной плитки сервера, null — обычный выбор сборки. */
    private String selectedServer;
    /** Какую сборку выбирали для сервера (адрес → имя сборки или VANILLA_LABEL); в monitor_servers.json. */
    private final Map<String, String> serverBuildChoice = new HashMap<>();
    /** Последний ответ каждого сервера (только поток UI). */
    private final Map<String, ServerMonitorService.ServerStatus> serverStatusCache = new HashMap<>();
    /** Для какого сервера версия уже подставлена в выбор «Без модов» — чтобы не перебивать ручной выбор. */
    private String serverVersionAppliedFor;
    private boolean fillingServerCombo;
    /** Сервер, выбранный в момент нажатия «Играть» (скачивание может идти долго). */
    private volatile String pendingLaunchServer;

    private volatile boolean gameStarting = false;
    /** Запуск пошёл со щелчка по карточке в «Сборках» — для отметки play_click card=1. */
    private boolean launchFromCard;
    /**
     * Когда последний раз стартовал процесс игры (0 — игра закрылась). 19.10.2026 двое новичков
     * из девяти через 12-18 с после старта нажали «Играть» ещё раз: окно Minecraft после первой
     * загрузки появляется за 30-60 с, и казалось, что ничего не происходит. Вторая копия той же
     * сборки подралась за файлы с первой (15d359ca, NoSuchFileException). Поэтому минуту после
     * запуска повторный не даём — пока игра жива; вылетела раньше — можно сразу.
     */
    private volatile long gameLaunchedAt = 0;
    private static final long GAME_START_COOLDOWN_MS = 60_000;
    /** Идёт «Починка сборки» — запуск ждёт, чтобы игра не стартовала с наполовину починенными модами. */
    private volatile boolean modDoctorApplying = false;
    /**
     * Что сейчас делает запуск, пока игры ещё нет: "dl" — качается Minecraft, "java" — Java,
     * "loader" — ставится Forge/Fabric/NeoForge, "start" — игра стартует; "" — ничего.
     * Нужно для вопроса при закрытии окна и для статистики: 12-13.10.2026 трое новичков из
     * четырёх закрыли лаунчер посреди первой загрузки, а окно закрывалось молча.
     */
    private volatile String launchPhase = "";
    /** Общий процент первой загрузки (0-100), -1 — загрузки нет. */
    private volatile int downloadPercent = -1;
    /** Процент загрузки Java (-1 — размер неизвестен или Java не качается); для busy=java:NN. */
    private volatile int javaPercent = -1;
    /** Сколько процентов установки Forge/NeoForge позади (-1 — не идёт или не знаем). */
    private volatile int loaderPercent = -1;
    /** Когда пошли «ресурсы N/M» и сколько их было сделано в тот момент — для оценки времени. */
    private volatile long assetsTimerAt;
    private volatile int assetsTimerDone;
    private volatile boolean stoppedByUser = false;
    private static final int MAX_LAUNCH_BUFFER_CHARS = 300_000;
    /** Cap for gameConsoleOutput/consoleArea's own text content (see log() and
     *  appendFocusedConsoleLineIfLocal()) - JavaFX's TextArea gets progressively slower to
     *  append to the larger its total content grows, to the point that a long session (or
     *  anything logging quickly for a while, like streaming a dedicated server's own output)
     *  eventually makes every single appendText() call take noticeably longer than the last.
     *  Since those calls all run on the FX Application Thread via Platform.runLater(), once
     *  appends start taking longer than new log lines arrive, the queue backs up permanently and
     *  the entire UI stops responding to anything - clicks, redraws, all of it - looking exactly
     *  like a hard freeze (confirmed via a live jstack dump: the FX thread was stuck inside
     *  TextArea's own content-changed listener machinery, not blocked on anything from this
     *  launcher's own code). Trimming old text once it crosses this cap keeps every append O(1)
     *  regardless of how long the session runs. */
    private static final int MAX_CONSOLE_CHARS = 200_000;
    /** Shown at the bottom of Settings — bump on every release and re-run build.ps1, which also
     *  regenerates the manifests from the new jar's sha256 (a stale hash there makes every client
     *  reject the update). v1.12.1 was bumped past the then-published v1.12.0 so an auto-update
     *  could never replace this jar with a server build lacking built-in ZeroTier play-together. */
    /** Номер версии — из общего места: {@link com.example.launcher.service.LauncherVersion}.
     *  Своей копии здесь больше нет, потому что вторая такая копия жила в OnlineStatsService
     *  и разъехалась с этой (там годами стояло «1.2»). */
    private static final String LAUNCHER_VERSION =
            com.example.launcher.service.LauncherVersion.get();

    /** Minecraft Java Edition nicknames are limited to 16 characters. */
    private static final int MAX_NICKNAME_LENGTH = 16;

    private String currentLanguage = "ru";
    private String currentTheme = "violet";
    /** Live while the palette editor is open; null when it has never been opened. */
    private PaletteEditor paletteEditor;

    /** Looks up a UI string in the current language — see Strings.java for the full dictionary
     *  and MainController.refreshLanguageTexts() for where this gets re-applied on a live
     *  language switch. */
    private String tr(String key) {
        return Strings.tr(key, currentLanguage);
    }

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /**
     * Cached Minecraft version list shared by every version ComboBox in the app (Home's vanilla
     * picker, Server's vanilla picker, create-build/create-server modals). Starts as a small
     * static fallback so pickers are never empty before the network fetch below completes, or
     * when the launcher is started with no internet connection at all — a version that's
     * already downloaded still needs to be pickable and launchable fully offline (see
     * refreshVersionListCache()/onLaunch(), which never re-fetches this list once cached, and
     * GameLaunchService, which never needs network access for a version already on disk).
     */
    private static final List<String> FALLBACK_VERSIONS = List.of(
            "1.21.1", "1.20.1", "1.19.4", "1.16.5", "1.12.2", "1.8.9", "1.7.10");
    private volatile List<String> versionListCache = new ArrayList<>(FALLBACK_VERSIONS);
    /** Every ComboBox<String> currently showing versionListCache — refreshed in place when the list changes. */
    private final List<ComboBox<String>> versionCombosToRefresh = new ArrayList<>();

    // ============================================================
    //  Init
    // ============================================================

    public void init(LauncherConfig config, ConfigService configService,
                      VersionDownloadService versionDownloadService,
                      ProfileService profileService,
                      ModLoaderService modLoaderService,
                      ModrinthService modrinthService,
                      JavaVersionDetector javaVersionDetector,
                      JavaDownloadService javaDownloadService,
                      ServerService serverService,
                      Path gameDir,
                      Stage primaryStage) {

        this.primaryStage = primaryStage;
        this.config = config;
        this.configService = configService;
        this.versionDownloadService = versionDownloadService;
        this.profileService = profileService;
        this.modLoaderService = modLoaderService;
        this.modrinthService = modrinthService;
        this.javaVersionDetector = javaVersionDetector;
        this.javaDownloadService = javaDownloadService;
        this.serverService = serverService;
        this.gameDir = gameDir;
        this.modpackService = new ModpackService(gameDir);
        installUiClickTracker();

        // Linux-сборка (20.10.2026): ZeroTier там ставится только через sudo и вживую не проверен,
        // поэтому пока выключен целиком. Все обработчики уже проверяют сервис на null.
        this.zeroTierService = Os.isWindows() ? new ZeroTierService(gameDir) : null;
        if (zeroTierService == null) {
            if (ztStatusLabel != null) ztStatusLabel.setText(tr("zt_windows_only"));
            for (Button b : new Button[]{ztPowerBtn, ztInstallBtn, ztRefreshBtn, ztJoinBtn, ztCreateBtn}) {
                if (b != null) b.setDisable(true);
            }
        }
        // Сервер участников (порт 9994) НЕ запускаем при старте лаунчера: телефон/Android
        // должен видеть ПК и создавать/находить комнаты только после того, как пользователь
        // нажал «Включить» на вкладке Сервер. Запуск/остановка этого HTTP-сервера выполняются
        // в onZeroTierPowerToggle() через startZtInfoServerIfNeeded()/stopZtInfoServerIfNeeded().

        this.gameLaunchService = new GameLaunchService(gameDir, modLoaderService, versionDownloadService, config);
        // Папки natives от прошлых запусков: к моменту старта лаунчера игра уже не работает,
        // её DLL никем не замаплены, и всё удаляется. Своей чистки при старте не было, а чистка
        // при выходе прерывалась на первом занятом файле — на рабочей машине так накопилось
        // 11 папок и 325 МБ. Фоном, чтобы не задерживать показ окна.
        executor.submit(() -> {
            try {
                gameLaunchService.cleanupOrphanNatives();
            } catch (Exception ignored) {
                // Чистка диска — не повод мешать запуску.
            }
        });
        // Аккаунт Microsoft у каждого профиля свой (см. switchAccountToCurrentProfile), но
        // AuthService нужен уже сейчас — начинаем с общего файла и переключаемся, когда станет
        // известен текущий профиль (это происходит в initSettingsScreen, где читается ник).
        this.authService = new AuthService(gameDir.resolve("account.json"));
        authService.setLogger(this::log);
        gameLaunchService.setAuthService(authService);

        this.onlineStatsService = new OnlineStatsService(gameDir, config);
        onlineStatsService.start();
        reportSessionStart();
        // Партнёрская ссылка аренды может смениться на сайте — перечитываем её заранее, в фоне.
        executor.submit(ServHostRent::refresh);

        // Self-diagnosis for "the launcher eats 100% CPU" reports. Every such report so far has
        // arrived after the process was already killed in Task Manager, with nothing left to look
        // at - this leaves a thread dump naming the exact threads and stacks behind instead.
        CpuWatchdog.start(gameDir.resolve("logs"), this::log);

        currentLanguage = config.getLanguage() != null && !config.getLanguage().isBlank() ? config.getLanguage() : "ru";

        pluginsPanelController.wire(brandPluginsList, brandPluginKeyField,
                brandPluginActivateBtn, brandPluginKeyStatusLabel, catalogListBox);
        vpsPanelController.wireCreateForm(newVpsNameField, newVpsHostField, newVpsSshPortField, newVpsUserField,
                newVpsAuthPasswordTab, newVpsAuthKeyTab, newVpsPasswordBox, newVpsPasswordField,
                newVpsKeyBox, newVpsKeyFileField, newVpsKeyPassphraseField, newVpsSudoPasswordField,
                newVpsPortField, newVpsStepsList, newVpsConnectionStatusLabel, newVpsRetryBtn, newVpsBackBtn);
        vpsPanelController.wireDetail(vpsSshPanel, vpsSshInfoLabel, vpsSshHostKeyLabel, vpsSshStatusLabel, vpsSshCheckBtn);
        consoleAssist = com.example.launcher.ui.CommandAssist.attach(consoleCommandField,
                this::consoleKnownPlayers, this::consoleKnownCommands);
        com.example.launcher.ui.ConsoleView.attach(consoleArea, this::consoleKnownPlayers, this::showNickMenu);
        vpsManageController.wire((VBox) consolePanel.getParent(), consolePanel);

        loadBuilds();
        loadServers();
        loadUserProfiles();
        initNavigation();
        initHomeScreen();
        initBuildsScreen();
        initModsScreen();
        initServerScreens();
        initSettingsScreen();
        // After initSettingsScreen(): the friends API is keyed off the player's nickname, and
        // that is only set once the settings screen has restored/created it.
        initFriendsScreen();
        // Тоже только здесь: чтобы понять, чей аккаунт восстанавливать, нужен текущий профиль,
        // а его ник читается в initSettingsScreen. До этого момента работал общий account.json.
        switchAccountToCurrentProfile();
        initModals();
        // All 5 version combos are registered by this point (versionCombosToRefresh) — mark
        // already-downloaded versions with a checkmark so picking one doesn't quietly cost a
        // fresh download just because it looks identical to any other entry in the list.
        for (ComboBox<String> combo : versionCombosToRefresh) {
            styleVersionComboWithInstalledMarker(combo);
        }

        goToView("home");
        renderHomeBuildGrid();
        renderBuildsGrid();
        renderCurrentBuildPanel();
        renderHomeAccountRow();
        refreshServerBuildCombo();
        renderMyServersQuickList();
        // ZeroTier state check runs on the background executor - it touches the local API and
        // possibly the CLI, neither of which belongs on the FX Application Thread.
        refreshZeroTierPanel(true);
        // Rooms take 10-30 s to go from "настраивается..." to a configured IP after join, and
        // friends arriving at a private room need their approval noticed promptly - poll the
        // local engine every 5 s so the panel stays live without the user pressing "Обновить".
        // Same tick also catches a dedicated server process that died on its own (crash, OOM,
        // kill) while the launcher stayed open — otherwise its card would keep showing "running"
        // until the tab was reopened.
        // Motion.Every, а не Timeline: вечный Timeline не даёт JavaFX спать между кадрами.
        ztPoller = new Motion.Every(5, () -> {
                    // Опрос движка (два HTTP-запроса, по запросу на комнату, а при упавшей службе —
                    // запуск sc.exe) нужен, только когда ZeroTier включён или его видно; остальное
                    // время он молотил впустую весь сеанс (16.10.2026). «Друзья» при открытии
                    // обновляются сами (onNavFriends).
                    if (zeroTierPowerOn || screenFriends.isVisible() || screenServer.isVisible()
                            || screenServerDetail.isVisible()) {
                        refreshZeroTierPanel(false);
                    }
                    refreshLocalServerCardsIfChanged();
                });
        ztPoller.play();
        // ServerService.loadAll() (in loadServers() above) resets every VPS server's running
        // flag to false, since a previous session's in-memory state can't be trusted — without
        // this, a VPS server that's genuinely still running (its agent is a separate, always-on
        // process on the VPS, unaffected by this launcher closing) would show as stopped right
        // on Home until the person happened to open the Servers tab or its own detail screen,
        // which are the only other places this same correction already runs (see
        // refreshVpsStatusesInBackground()'s own javadoc).
        refreshVpsStatusesInBackground();
        // Pick up any local server process left running by a previously killed launcher session
        // so it shows as "running" and can be stopped from the UI.
        adoptRunningServersInBackground();

        // Restore whichever theme was saved last — must run after the scene already exists
        // (see MainApp, which now creates the Scene before calling init() for exactly this).
        // The text size is part of the same generated stylesheet and is read from the config
        // by applyPalette, which is why nothing has to be prepared before this call.
        String savedTheme = config.getTheme();
        applyTheme(savedTheme != null && !savedTheme.isBlank() && !"light".equals(savedTheme) ? savedTheme : "violet");

        // Best-effort background fetch — every version combo above already has FALLBACK_VERSIONS
        // showing, so the UI is fully usable immediately even with no network at all.
        refreshVersionListCache();
        loadBrandPluginCatalog();

        checkForUpdateInBackground();

        if (Boolean.getBoolean("blauncher.debug.styles")) {
            javafx.application.Platform.runLater(this::debugStyles);
        }
        // Photographing a dialog needs a way to open one without a mouse. The value is the id of
        // the overlay in main.fxml (profilesOverlay, createBuildOverlay, paletteOverlay, ...);
        // used together with capture-window.ps1 -Attach.
        String modal = System.getProperty("blauncher.debug.modal");
        if (modal != null && !modal.isBlank()) {
            javafx.application.Platform.runLater(() -> {
                javafx.scene.Node overlay = primaryStage.getScene() == null ? null
                        : primaryStage.getScene().lookup("#" + modal);
                System.out.println("[modal] " + modal + " -> " + overlay);
                if (overlay != null) {
                    showOverlay(overlay);
                    // What the dialog actually became, printed by the window that is on screen:
                    // a probe can measure a scene it built itself, but only this can say what the
                    // player's window did with the same markup. Delayed by a second and a half, so
                    // that several layout pulses have run - measuring in the next runLater
                    // reported an unlaid-out card of 420x300 at negative coordinates.
                    javafx.animation.PauseTransition pause =
                            new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.5));
                    pause.setOnFinished(event -> {
                        javafx.scene.Scene scene = primaryStage.getScene();
                        System.out.printf("[modal] окно сцены: %.0fx%.0f%n", scene.getWidth(), scene.getHeight());
                        javafx.scene.Node layer = scene.lookup("#overlayLayer");
                        if (layer instanceof javafx.scene.layout.Region region) {
                            System.out.printf("[modal] слой окон: %.0fx%.0f (виден %s, управляем %s)%n",
                                    region.getWidth(), region.getHeight(), region.isVisible(), region.isManaged());
                        }
                        if (overlay instanceof javafx.scene.layout.Region scrim) {
                            javafx.geometry.Bounds bounds = scrim.localToScene(scrim.getBoundsInLocal());
                            System.out.printf("[modal] оверлей: %.0fx%.0f в x=%.2f y=%.2f, отступы %s%n",
                                    bounds.getWidth(), bounds.getHeight(), bounds.getMinX(), bounds.getMinY(),
                                    scrim.getPadding());
                        }
                        javafx.scene.Node card = overlay.lookup(".modal-card");
                        if (card instanceof javafx.scene.layout.Region region) {
                            javafx.geometry.Bounds bounds = card.localToScene(card.getBoundsInLocal());
                            System.out.printf("[modal] карточка: %.0fx%.0f в x=%.2f y=%.2f,"
                                            + " maxWidth=%s maxHeight=%s%n",
                                    bounds.getWidth(), bounds.getHeight(), bounds.getMinX(), bounds.getMinY(),
                                    region.getMaxWidth(), region.getMaxHeight());
                        }
                    });
                    pause.play();
                }
            });
        }
    }

    /**
     * TEMPORARY diagnostic (run with -Dblauncher.debug.styles=true).
     *
     * <p>Probes could not agree on whether the launcher runs with JavaFX's own stylesheet
     * switched off, and the answer decides whether a control the theme does not style is
     * merely decorated by JavaFX or completely invisible. This asks the running window: an
     * unstyled node reports no background at all, a modena-styled one reports modena's grey.
     */
    private void debugStyles() {
        javafx.scene.Scene scene = primaryStage.getScene();
        System.out.println("[styles] сцена есть: " + (scene != null)
                + ", стилей: " + (scene == null ? "-" : scene.getStylesheets().size()));
        // The Settings screen holds the controls the theme says least about - the RAM slider,
        // a switch and a separator - so it is switched to, inspected and left again.
        goToView("settings");
        for (String id : new String[]{"homeVanillaVersionCombo", "launchProgressBar", "paletteEditButton",
                "ramSlider", "closeOnLaunchToggle"}) {
            javafx.scene.Node node = scene == null ? null : scene.lookup("#" + id);
            System.out.println("[styles] #" + id + " = " + node);
            if (node == null) continue;
            for (String selector : new String[]{".arrow", ".arrow-button", ".bar", ".track", ".thumb",
                    ".box", ".mark", ".slider"}) {
                javafx.scene.Node part = node.lookup(selector);
                if (part == null) continue;
                System.out.println("[styles]    " + selector + " -> " + describePaint(part));
            }
        }
        javafx.scene.Node rail = scene == null ? null : scene.lookup(".rail-btn");
        System.out.println("[styles] .rail-btn -> " + describePaint(rail));
        javafx.scene.Node separator = scene == null ? null : scene.lookup(".separator");
        System.out.println("[styles] .separator -> " + describePaint(separator));
    }

    /** The paint a node ended up with, or "не закрашен" when the theme gave it none. */
    private static String describePaint(javafx.scene.Node node) {
        if (node == null) return "узла нет";
        if (node instanceof javafx.scene.layout.Region region) {
            if (region.getBackground() == null || region.getBackground().getFills().isEmpty()) {
                return "не закрашен";
            }
            return String.valueOf(region.getBackground().getFills().get(0).getFill());
        }
        if (node instanceof javafx.scene.control.Labeled labeled) {
            return "текст " + labeled.getTextFill();
        }
        return node.getClass().getSimpleName();
    }

    /** Restores a previously-saved Microsoft session, if any, without forcing a fresh login. */
    /** Restores a previously-saved Microsoft session, if any, without forcing a fresh login.
     *  Runs the actual token-refresh network calls on the background executor — this used to
     *  run directly on the JavaFX Application Thread during init(), which meant the entire UI
     *  would freeze for however long the refresh-token → Xbox Live → XSTS → Minecraft auth
     *  chain took (several sequential HTTP round-trips) on every single launcher startup that
     *  had a saved account, even though every other network-bound thing in this controller
     *  already goes through the executor for exactly this reason. */
    private void restoreSavedAccount() {
        executor.submit(() -> {
            try {
                if (authService.tryRestoreSession()) {
                    applySignedInUiState();
                } else {
                    // Say *why* the account row fell back to "Войти через Microsoft" - a network
                    // failure and a genuinely expired session look identical to the user
                    // otherwise, and only one of the two actually needs signing in again.
                    log("[AUTH] " + authService.getLastStatus());
                    // И чей это аккаунт: у каждого профиля свой вход, и без подписи непонятно,
                    // к какому профилю относится «не выполнен вход».
                    Platform.runLater(() -> loginStatusLabel.setText(
                            "Профиль «" + currentProfileLabel() + "»: вход через Microsoft не выполнен"));
                }
            } catch (Exception e) {
                log("[AUTH] Could not restore saved session: " + e.getMessage());
            }
        });
    }

    // ---------- Аккаунт Microsoft на профиль ----------

    /**
     * Файл, в котором лежит refresh-токен аккаунта этого профиля.
     *
     * <p>Назван по стабильному {@link UserProfile#getId()}, а не по нику: профиль можно
     * переименовать, и вход при этом не должен потеряться. Пока профиля нет вовсе (совсем свежая
     * установка), используется прежний общий {@code account.json}.
     */
    private Path accountFileForProfile(UserProfile profile) {
        if (profile == null || profile.getId() == null || profile.getId().isBlank()) {
            return gameDir.resolve("account.json");
        }
        return gameDir.resolve("accounts").resolve(profile.getId() + ".json");
    }

    /**
     * Переключает авторизацию на аккаунт текущего профиля и восстанавливает сессию.
     *
     * <p>Вызывается при старте и при каждом выборе профиля в окне «Профили». Если файл не
     * изменился, только перерисовывает состояние входа — иначе каждое открытие профиля дёргало бы
     * сеть. Единственный {@code account.json}, оставшийся от версии с одним общим аккаунтом,
     * переносится в аккаунт текущего профиля: без этого вход, сделанный до обновления, «пропал»
     * бы, а два профиля делили бы один токен.
     */
    private void switchAccountToCurrentProfile() {
        UserProfile profile = currentUserProfile();
        Path target = accountFileForProfile(profile);

        if (profile != null && !Files.exists(target)) {
            Path legacy = gameDir.resolve("account.json");
            if (Files.exists(legacy)) {
                try {
                    Files.createDirectories(target.getParent());
                    Files.move(legacy, target);
                    log("[AUTH] Аккаунт перенесён в профиль «" + profile.getName() + "»");
                } catch (IOException e) {
                    log("[AUTH] Не удалось перенести аккаунт в профиль: " + e.getMessage());
                }
            }
        }

        if (target.equals(currentAccountFile)) {
            renderHomeAccountRow();
            return;
        }
        currentAccountFile = target;
        authService = new AuthService(target);
        authService.setLogger(this::log);
        gameLaunchService.setAuthService(authService);
        renderHomeAccountRow();
        loginStatusLabel.setText("Профиль «" + currentProfileLabel() + "»: проверяем вход…");
        restoreSavedAccount();
    }

    /** Имя текущего профиля — для надписей вида «аккаунт профиля «моды»». */
    private String currentProfileLabel() {
        UserProfile profile = currentUserProfile();
        if (profile != null && profile.getName() != null) return profile.getName();
        String nick = currentNickname();
        return nick.isBlank() ? "—" : nick;
    }

    private void applySignedInUiState() {
        Platform.runLater(() -> {
            // Ник профиля здесь НЕ переписывается именем аккаунта: у каждого профиля свой
            // аккаунт, и вход в него не должен переименовывать профиль (иначе выбор профиля,
            // к которому только что привязали аккаунт, тут же «уезжал» бы на другой).
            if (loginStatusLabel != null) {
                loginStatusLabel.setText(!authService.isLoggedIn() ? ""
                        : authService.hasLicense()
                                ? ("Выполнен вход: " + authService.getUsername()
                                        + " — лицензия есть · профиль «" + currentProfileLabel() + "»")
                                : "Выполнен вход через Microsoft, но лицензии Minecraft на аккаунте нет "
                                        + "— доступны только оффлайн-серверы");
            }
            renderHomeAccountRow();
        });
    }

    /**
     * Shows the Microsoft account state right on Home, next to Play — signing in only from a
     * small button buried at the bottom of Settings made it easy to miss that licensed/online
     * play needs it at all. Clicking either state (signed in or not) jumps to Settings' Account
     * panel, where the actual sign-in button and login-code flow live (see onMicrosoftLogin()).
     */
    private void renderHomeAccountRow() {
        boolean loggedIn = authService != null && authService.isLoggedIn();
        boolean licensed = authService != null && authService.hasLicense();
        updateHomeLicenseBadge(licensed);
        if (loggedIn) {
            // Deliberately NOT setting homeUsernameField's text here — it's bound
            // bidirectionally to usernameField (Settings), so writing the Microsoft account's
            // name into it would also overwrite the saved offline nickname in config (via
            // usernameField's own listener) the moment someone signs in. Left editable on
            // purpose: onLaunch() always reads the real Minecraft identity straight from
            // authService while signed in (see the isLoggedIn branch there), so this field is
            // purely a cosmetic local nickname and editing it can't desync the actual online
            // username/UUID used to launch.
            homeUsernameField.setDisable(false);
            homeMicrosoftLoginButton.setText("Аккаунт");
            if (licensed) {
                homeAccountIcon.setIconLiteral("fth-check-circle");
            } else {
                homeAccountIcon.setIconLiteral("fth-alert-circle");
            }
        } else {
            // homeUsernameField and usernameField (Settings) are bound bidirectionally (see
            // initSettingsScreen()) — no need to manually copy a value across here.
            homeUsernameField.setDisable(false);
            homeMicrosoftLoginButton.setText("Войти через Microsoft");
            homeAccountIcon.setIconLiteral("fth-log-in");
        }
    }

    private void updateHomeLicenseBadge(boolean licensed) {
        if (homeAccountLabel == null) {
            return;
        }
        homeAccountLabel.getStyleClass().removeAll("license-badge-ok", "license-badge-error");
        homeAccountLabel.getStyleClass().add(licensed ? "license-badge-ok" : "license-badge-error");
        homeAccountLabel.setText(licensed ? tr("license_yes") : tr("license_no"));
    }

    /** Appends a line to the game console (Settings screen) and stdout, timestamped. */
    /** Строки лога, ещё не дошедшие до gameConsoleOutput; под своим же замком. */
    private final StringBuilder pendingLog = new StringBuilder();
    private boolean logFlushQueued;

    /**
     * Можно звать с любого потока. Строки копятся и уходят в окно одной пачкой за кадр: раньше
     * каждая строка лога игры была отдельным Platform.runLater с appendText, а у полной консоли
     * ещё и с deleteText по 200 000 символов — сборка с модами сыплет тысячи строк, и всё это
     * время поток интерфейса был занят консолью (16.10.2026, разбор нагрузки от лаунчера).
     */
    private void log(String message) {
        String line = "[" + TIME_FMT.format(Instant.now()) + "] " + message;
        System.out.println(line);
        if (gameConsoleOutput == null) return;
        synchronized (pendingLog) {
            pendingLog.append(line).append('\n');
            if (pendingLog.length() > MAX_CONSOLE_CHARS) {
                pendingLog.delete(0, pendingLog.length() - MAX_CONSOLE_CHARS);
            }
            if (logFlushQueued) return;
            logFlushQueued = true;
        }
        Platform.runLater(() -> {
            String chunk;
            synchronized (pendingLog) {
                chunk = pendingLog.toString();
                pendingLog.setLength(0);
                logFlushQueued = false;
            }
            appendCapped(gameConsoleOutput, chunk);
        });
    }

    /**
     * Дописывает в консоль и держит её не длиннее MAX_CONSOLE_CHARS. Срезает с запасом в четверть,
     * чтобы дорогой deleteText случался раз на десятки тысяч символов, а не на каждой строке.
     */
    private static void appendCapped(TextArea area, String chunk) {
        area.appendText(chunk);
        int len = area.getLength();
        if (len > MAX_CONSOLE_CHARS) {
            area.deleteText(0, len - MAX_CONSOLE_CHARS + MAX_CONSOLE_CHARS / 4);
        }
    }

    // ============================================================
    //  Friends by code (FriendsService)
    //
    //  The panel that sits under the rooms on the friends screen: the player's
    //  own friend code on the right, the list of people added by code below it,
    //  and the card that opens when one of them is clicked — their head, their
    //  nickname beside it, and the "о себе" text they wrote under the nickname.
    //
    //  Everything network-shaped here runs on the background executor and comes
    //  back through Platform.runLater; the 30-second poller keeps the list, the
    //  online dots and friends' texts current without the player pressing
    //  anything (see FriendsService for the API contract).
    // ============================================================

    /** Server-side limit (see friends.php's MAX_ABOUT_LEN) — enforced here too, so a long text
     *  is refused with a message instead of being silently cut in half on the way back. */
    private static final int MAX_ABOUT_LENGTH = 300;

    /** How long "Обновить" stays on cooldown after a press, so a clicked-in-frustration button
     *  cannot turn into a stream of requests at the friends API. */
    private static final int FRIENDS_REFRESH_COOLDOWN_SECONDS = 10;

    private void initFriendsScreen() {
        friendsService = new FriendsService(gameDir);
        // BL Core rides on the friends identity: the mark is left under the same secret.
        gameLaunchService.setBlCore(new BlCoreService(gameDir, friendsService, config::isBlBadge,
                () -> {
                    UserProfile profile = currentUserProfile();
                    String cape = profile == null ? null : profile.getCapePath();
                    return cape == null || cape.isBlank() ? null : Path.of(cape);
                }, executor, this::log));

        savedAbout = friendsService.getMyAbout();
        myAboutArea.setText(savedAbout);
        myAboutArea.textProperty().addListener((obs, oldV, newV) -> updateAboutDirtyMarker());
        updateAboutDirtyMarker();

        // Whatever the last session knew is on screen immediately, so the panel is usable
        // (and readable) with no connection at all; the first sync replaces it a moment later.
        friends.clear();
        friends.addAll(friendsService.getCachedFriends());
        myFriendCodeField.setText(friendsService.getMyCode());
        renderFriendsList();

        friendsPoller = new Motion.Every(30, () -> refreshFriendsPanel(false));
        friendsPoller.play();

        // The first sync waits for the window to paint: it is a network round trip, and on a
        // slow start-up a blank-but-responsive window beats a frozen one.
        javafx.animation.PauseTransition firstSync = new javafx.animation.PauseTransition(Duration.seconds(2));
        firstSync.setOnFinished(e -> refreshFriendsPanel(true));
        firstSync.play();
    }

    /** The nickname everything friend-related is keyed off — the same one the game launches as. */
    private String currentNickname() {
        return usernameField == null || usernameField.getText() == null ? "" : usernameField.getText().trim();
    }

    /** The saved profile behind the current nickname, or null when the nick has no profile yet. */
    private UserProfile currentUserProfile() {
        // Сначала по сохранённому id: профиль мог быть переименован, и по нику его уже не найти,
        // а аккаунт Microsoft привязан именно к id.
        String savedId = config.getLastProfileId();
        if (!savedId.isBlank()) {
            for (UserProfile profile : userProfiles) {
                if (profile != null && savedId.equals(profile.getId())) return profile;
            }
        }
        String name = currentNickname();
        if (name.isBlank()) return null;
        for (UserProfile profile : userProfiles) {
            if (profile != null && name.equals(profile.getName())) return profile;
        }
        return null;
    }

    /**
     * Publishes our nickname, our skin and our «о себе», and pulls the friends list back.
     *
     * @param force true for a click or our own action — repaint even when nothing looks changed;
     *              false for a poller tick, which leaves the rows alone unless they really moved.
     */
    private void refreshFriendsPanel(boolean force) {
        if (friendsService == null) return;
        // A click that asks for a refresh while one is already in flight (the 30-second poll is
        // usually the one holding the flag) must not be dropped - it would leave a just-added
        // friend off the list for up to half a minute. Remember it and run it on the way out.
        if (friendsSyncInFlight) {
            if (force) friendsRefreshQueued = true;
            return;
        }

        String nick = currentNickname();
        if (nick.isBlank()) {
            friendsStatusLabel.setText(tr("friends_err_nick"));
            return;
        }

        friendsSyncInFlight = true;
        if (friends.isEmpty()) friendsStatusLabel.setText(tr("friends_loading"));

        // Read our own skin while still on the FX thread: it walks the profile list, and that
        // list is only ever mutated from here.
        final SkinSource skin = mySkinSource();
        executor.submit(() -> {
            FriendsService.Snapshot snapshot = null;
            String failure = null;
            try {
                snapshot = friendsService.sync(nick, publishMySkin(skin), friendsService.getMyAbout());
            } catch (FriendsService.FriendsException e) {
                failure = e.getCode();
            } catch (Exception e) {
                failure = "offline";
                log("[FRIENDS] Синхронизация не удалась: " + e.getMessage());
            }
            final FriendsService.Snapshot result = snapshot;
            final String error = failure;
            Platform.runLater(() -> {
                friendsSyncInFlight = false;
                applyFriendsSnapshot(result, error, force);
                if (friendsRefreshQueued) {
                    friendsRefreshQueued = false;
                    refreshFriendsPanel(true);
                }
            });
        });
    }

    private void applyFriendsSnapshot(FriendsService.Snapshot snapshot, String error, boolean force) {
        if (snapshot == null) {
            // The cached list deliberately stays on screen: a flaky connection should not
            // empty a panel whose whole point is showing who your friends are.
            friendsStatusLabel.setText(friendsErrorText(error));
            renderFriendsList();
            return;
        }

        myFriendCodeField.setText(snapshot.code() == null ? "" : snapshot.code());
        friendsStatusLabel.setText("");
        friends.clear();
        friends.addAll(snapshot.friends());
        if (force) lastFriendsFingerprint = "\u0000";
        applyServerAbout(snapshot.about());

        // Invitations: the panel always shows what is pending, but a toast is only worth it for
        // the ones that arrived while the player was watching — hence the id set, and the muted
        // first round (invitations left over from a previous session are already on screen).
        invites.clear();
        invites.addAll(snapshot.invites());
        renderFriendInvites();
        for (FriendInvite invite : invites) {
            if (announcedInviteIds.add(invite.getId()) && firstFriendsSnapshotDone) {
                showToast(tr("friends_invite_arrived") + " " + invite.getFromNick()
                        + " — «" + invite.roomLabel() + "»", "info");
            }
        }
        // Answers to invitations WE sent — the API hands each one over exactly once.
        for (FriendsService.InviteAnswer answer : snapshot.inviteAnswers()) {
            showToast(tr(answer.accepted() ? "friends_invite_accepted" : "friends_invite_declined")
                    + " " + answer.nick() + " — «" + answer.networkName() + "»",
                    answer.accepted() ? "success" : "info");
        }
        firstFriendsSnapshotDone = true;

        renderFriendsList();
        // The code only exists after the first sync, so an own-profile dialog opened before it
        // would otherwise keep showing "Получаем код…" forever.
        if (myProfileOverlay != null && myProfileOverlay.isVisible()) renderMyProfileCard();
    }

    /**
     * What we know about our own skin: a texture URL that is already hosted somewhere, and/or
     * the local file the profile editor saved. Captured on the FX thread — {@link #mySkinSource()}
     * walks the profile list, which only the FX thread mutates.
     */
    private record SkinSource(String url, String path) {}

    private SkinSource mySkinSource() {
        UserProfile profile = currentUserProfile();
        if (profile == null) return new SkinSource(null, null);
        return new SkinSource(profile.getSkinUrl(), profile.getSkinPath());
    }

    /**
     * The skin URL friends should see for us: the profile's already-hosted texture, or a fresh
     * MineSkin upload of its local file.
     *
     * <p>This step is what makes the feature work at all for offline-mode players: their nickname
     * does not exist on Mojang, so without a hosted texture a friend's card would have nothing to
     * draw. Background executor only; an upload is attempted once per file, never on every poll.
     */
    private String publishMySkin(SkinSource source) {
        if (publishedSkinUrl != null) return publishedSkinUrl;
        if (source == null) return null;
        if (source.url() != null && !source.url().isBlank()) {
            publishedSkinUrl = source.url();
            return publishedSkinUrl;
        }

        String path = source.path();
        if (path == null || path.isBlank() || path.equals(publishedSkinPath)) return null;
        Path file = Path.of(path);
        if (!Files.exists(file)) return null;

        publishedSkinPath = path;   // set first: a failing upload must not be retried every 30 s
        try {
            String url = mineskinService.uploadSkin(file);
            publishedSkinUrl = url;
            // Back onto the FX thread for the profile write - same rule as the lookup above.
            Platform.runLater(() -> {
                UserProfile profile = currentUserProfile();
                if (profile != null) {
                    profile.setSkinUrl(url);
                    saveUserProfiles();
                }
            });
            log("[FRIENDS] Скин профиля опубликован для друзей");
            return url;
        } catch (Exception e) {
            log("[FRIENDS] Не удалось опубликовать скин профиля: " + e.getMessage());
            return null;
        }
    }

    /**
     * Adopts the server's copy of «о себе» — never over text the player is in the middle of
     * typing, which the 30-second poll would otherwise wipe out from under them.
     */
    private void applyServerAbout(String about) {
        String incoming = about == null ? "" : about;
        if (incoming.equals(savedAbout)) return;

        boolean editing = isAboutDirty();
        savedAbout = incoming;
        if (!editing) myAboutArea.setText(incoming);
        updateAboutDirtyMarker();
    }

    private boolean isAboutDirty() {
        String text = myAboutArea.getText() == null ? "" : myAboutArea.getText();
        return !text.equals(savedAbout);
    }

    /** Highlights "Сохранить" while the text differs from what the friends are currently shown. */
    private void updateAboutDirtyMarker() {
        if (myAboutSaveBtn == null) return;
        myAboutSaveBtn.getStyleClass().remove("dirty");
        if (isAboutDirty()) myAboutSaveBtn.getStyleClass().add("dirty");
    }

    private Friend selectedFriend() {
        if (selectedFriendCode == null) return null;
        for (Friend friend : friends) {
            if (selectedFriendCode.equals(friend.getCode())) return friend;
        }
        return null;
    }

    /** A click on a friend's row opens their profile in its own window. */
    private void selectFriend(Friend friend) {
        if (friend == null) return;
        selectedFriendCode = friend.getCode();
        disarmFriendRemoval();
        renderFriendsList();
        showOverlay(friendProfileOverlay);
    }

    @FXML
    private void onCloseFriendProfile() {
        disarmFriendRemoval();
        selectedFriendCode = null;
        renderFriendsList();
        hideOverlay(friendProfileOverlay);
    }

    @FXML
    private void onInviteSelectedFriend() {
        Friend friend = selectedFriend();
        if (friend != null) onInviteFriend(friend);
    }

    /**
     * The "Профиль" button in the screen header: opens the player's own card in a dialog, in
     * exactly the shape a friend sees — head, nickname beside it, «о себе» under the nickname.
     */
    @FXML
    private void onShowMyProfile() {
        renderMyProfileCard();
        showOverlay(myProfileOverlay);
        // Refresh in the background so the code and the head in that dialog are the current ones.
        refreshFriendsPanel(true);
    }

    @FXML
    private void onCloseMyProfile() {
        hideOverlay(myProfileOverlay);
    }

    /**
     * The "Профиль" button on the Friends tab: opens the player's own card — head, nickname and
     * "это ты" badge, exactly as a friend sees them — together with the two things that can be
     * edited about oneself: the friend code (copy it and hand it out) and the «О себе» text.
     *
     * <p>The code and the text are NOT re-filled from here: they are live fields, kept in step by
     * {@link #applyFriendsSnapshot} and {@link #applyServerAbout}, and overwriting them on every
     * open would throw away an edit in progress.
     */
    private void renderMyProfileCard() {
        String nick = currentNickname();
        myProfileNick.setText(nick);
        myProfileHeadFallback.setText(nickInitial(nick));
        loadMyProfileHead();
        updateAboutDirtyMarker();
    }

    /**
     * The player's own head for the profile dialog. The local skin file is used straight from
     * disk when the profile editor saved one — the one head the launcher can always draw without
     * asking the network for anything.
     */
    private void loadMyProfileHead() {
        myProfileHeadImage.setImage(null);
        myProfileHeadFallback.setVisible(true);

        // Resolved on the FX thread: myLocalSkinPath() walks the profile list.
        final String localSkinPath = myLocalSkinPath();
        final String nick = currentNickname();
        final String skinUrl = publishedSkinUrl != null ? publishedSkinUrl
                : (currentUserProfile() != null ? currentUserProfile().getSkinUrl() : null);

        executor.submit(() -> {
            Image head = null;
            try {
                Path texture = localSkinPath != null
                        ? Path.of(localSkinPath)
                        : friendsService.headTextureForSelf(nick, skinUrl);
                if (texture != null) {
                    Image raw = new Image(texture.toUri().toString());
                    if (!raw.isError()) head = SkinPreview.skinHead(raw);
                }
            } catch (Exception e) {
                // A missing head is not an error: the placeholder letter stays.
            }
            final Image loaded = head;
            Platform.runLater(() -> {
                // The dialog may have been closed, or the nickname changed, while this loaded.
                if (!myProfileOverlay.isVisible() || !nick.equals(currentNickname())) return;
                myProfileHeadImage.setImage(loaded);
                myProfileHeadImage.setSmooth(false);
                myProfileHeadFallback.setVisible(loaded == null);
            });
        });
    }

    /** Renders the list, the selection and the open card from the current {@link #friends}. */
    private void renderFriendsList() {
        if (friendsListBox == null) return;

        // Rows are only rebuilt when something about them really changed - the poller ticks
        // every 30 s and rebuilding identical rows would cost the selection and the scroll.
        String fingerprint = friendsFingerprint();
        if (!fingerprint.equals(lastFriendsFingerprint)) {
            lastFriendsFingerprint = fingerprint;
            friendRows.clear();
            friendsListBox.getChildren().clear();
            for (Friend friend : friends) {
                if (friend == null || friend.getCode() == null) continue;
                HBox row = buildFriendRow(friend);
                friendRows.put(friend.getCode(), row);
                friendsListBox.getChildren().add(row);
            }
        }
        for (Map.Entry<String, HBox> entry : friendRows.entrySet()) {
            entry.getValue().getStyleClass().remove("selected");
            if (entry.getKey().equals(selectedFriendCode)) {
                entry.getValue().getStyleClass().add("selected");
            }
        }

        boolean empty = friends.isEmpty();
        friendsEmptyLabel.setVisible(empty);
        friendsEmptyLabel.setManaged(empty);
        friendsCountLabel.setText(empty ? "" : "(" + friends.size() + ")");

        showFriendCard(selectedFriend());
    }

    private String friendsFingerprint() {
        StringBuilder fingerprint = new StringBuilder();
        for (Friend friend : friends) {
            if (friend == null) continue;
            fingerprint.append(friend.getCode()).append('|')
                    .append(friend.getNick()).append('|')
                    .append(friend.isOnline()).append(';');
        }
        return fingerprint.toString();
    }

    private HBox buildFriendRow(Friend friend) {
        HBox row = new HBox(10);
        row.getStyleClass().add("friend-row");
        row.setAlignment(Pos.CENTER_LEFT);

        Region dot = new Region();
        dot.getStyleClass().add("server-dot");
        if (!friend.isOnline()) dot.getStyleClass().add("off");

        Label nick = new Label(friend.getNick() == null ? "" : friend.getNick());
        nick.getStyleClass().add("friend-name");
        Label code = new Label(friend.getCode() == null ? "" : friend.getCode());
        code.getStyleClass().add("friend-code");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label state = new Label(tr(friend.isOnline() ? "friends_online" : "friends_offline"));
        state.getStyleClass().add("toggle-sub");

        // Позвать друга в комнату, не открывая карточку: выбор комнаты — в отдельном окне.
        Button invite = new Button(tr("friends_invite_btn"));
        invite.getStyleClass().add("server-action");
        invite.setGraphic(new FontIcon("fth-user-plus"));
        invite.setContentDisplay(javafx.scene.control.ContentDisplay.LEFT);
        invite.setGraphicTextGap(6);
        invite.setOnAction(e -> onInviteFriend(friend));
        // A Button does not consume MOUSE_CLICKED, so without this the row underneath would also
        // treat the press as "open this friend's card" and the dialog would open behind it.
        invite.addEventFilter(MouseEvent.MOUSE_CLICKED, javafx.event.Event::consume);

        row.getChildren().addAll(dot, nick, code, spacer, state, invite);
        // Whole row is the target: the card is the point of clicking, and a 14 px nick is a
        // needlessly small hit area.
        row.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) selectFriend(friend);
        });
        return row;
    }

    // ---------- Приглашения в комнату ZeroTier ----------

    /** Renders the invitations waiting for an answer: who is calling, into which room. */
    private void renderFriendInvites() {
        if (friendInvitesBox == null) return;
        friendInvitesList.getChildren().clear();

        boolean any = !invites.isEmpty();
        friendInvitesBox.setVisible(any);
        friendInvitesBox.setManaged(any);
        if (!any) return;

        for (FriendInvite invite : invites) {
            HBox row = new HBox(8);
            row.getStyleClass().add("friend-invite-row");
            row.setAlignment(Pos.CENTER_LEFT);

            Label text = new Label(invite.getFromNick() + " " + tr("friends_invite_row_action")
                    + " «" + invite.roomLabel() + "»");
            text.getStyleClass().add("friend-name");
            text.setWrapText(true);

            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);

            Button join = new Button(tr("friends_invite_join"));
            join.getStyleClass().add("server-action");
            join.setOnAction(e -> onAcceptInvite(invite));

            Button decline = new Button(tr("friends_invite_decline"));
            decline.getStyleClass().add("copy-btn");
            decline.setOnAction(e -> onDeclineInvite(invite));

            row.getChildren().addAll(text, spacer, join, decline);
            friendInvitesList.getChildren().add(row);
        }
    }

    /** "Пригласить" on a friend row: pick one of the rooms we are in, then send the invitation. */
    private void onInviteFriend(Friend friend) {
        if (friend == null) return;
        if (zeroTierService == null) {
            showToast(tr("zt_windows_only"), "info");
            return;
        }

        executor.submit(() -> {
            List<ZeroTierService.NetworkInfo> rooms = new ArrayList<>();
            boolean online = false;
            try {
                online = zeroTierService.isOnline();
                // Только комнаты, в которых мы реально состоим: приглашать в сеть, куда не
                // подключён сам приглашающий, — заведомо нерабочее приглашение.
                if (online) rooms = zeroTierService.listNetworks();
            } catch (Exception e) {
                log("[FRIENDS] Не удалось получить список комнат: " + e.getMessage());
            }
            final List<ZeroTierService.NetworkInfo> found = rooms;
            final boolean ztOnline = online;
            Platform.runLater(() -> showInviteRoomPicker(friend, found, ztOnline));
        });
    }

    /** The room picker: one row per joined room, with its name and an "Пригласить" button. */
    private void showInviteRoomPicker(Friend friend, List<ZeroTierService.NetworkInfo> rooms, boolean ztOnline) {
        StackPane scrim = new StackPane();
        scrim.getStyleClass().add("modal-scrim");

        VBox card = new VBox(0);
        card.getStyleClass().add("modal-card");
        card.setMinWidth(420);
        card.setMinHeight(320);
        card.setPrefWidth(560);
        card.setPrefHeight(420);
        card.setMaxWidth(560);
        card.setMaxHeight(420);
        card.setPickOnBounds(true);

        HBox header = new HBox();
        header.getStyleClass().add("modal-header");
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label(tr("friends_invite_pick_title") + " " + friend.getNick());
        title.getStyleClass().add("modal-title");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        Button close = new Button();
        close.getStyleClass().add("modal-close");
        close.setGraphic(new FontIcon("fth-x"));
        header.getChildren().addAll(title, headerSpacer, close);

        VBox body = new VBox(8);
        body.getStyleClass().add("modal-body");

        if (rooms.isEmpty()) {
            Label empty = new Label(tr(ztOnline ? "friends_invite_no_rooms" : "friends_invite_zt_off"));
            empty.setWrapText(true);
            empty.getStyleClass().add("toggle-sub");
            body.getChildren().add(empty);
        } else {
            for (ZeroTierService.NetworkInfo room : rooms) {
                HBox row = new HBox(10);
                row.getStyleClass().add("friend-row");
                row.setAlignment(Pos.CENTER_LEFT);

                VBox text = new VBox(2);
                Label name = new Label(room.name == null || room.name.isBlank() ? room.id : room.name);
                name.getStyleClass().add("server-name");
                Label sub = new Label(room.id + " · " + (room.privateRoom
                        ? tr("friends_invite_room_private")
                        : tr("friends_invite_room_public")));
                sub.getStyleClass().addAll("server-sub", "mono");
                text.getChildren().addAll(name, sub);

                Region spacer = new Region();
                HBox.setHgrow(spacer, Priority.ALWAYS);

                Button send = new Button(tr("friends_invite_btn"));
                send.getStyleClass().add("server-action");
                send.setOnAction(e -> {
                    overlayLayer.getChildren().remove(scrim);
                    hideOverlay(scrim);
                    sendRoomInvite(friend, room);
                });

                row.getChildren().addAll(text, spacer, send);
                body.getChildren().add(row);
            }
        }

        card.getChildren().addAll(header, body);
        scrim.getChildren().add(card);
        overlayLayer.getChildren().add(scrim);
        close.setOnAction(e -> {
            overlayLayer.getChildren().remove(scrim);
            hideOverlay(scrim);
        });
        showOverlay(scrim);
    }

    private void sendRoomInvite(Friend friend, ZeroTierService.NetworkInfo room) {
        String roomName = room.name == null || room.name.isBlank() ? room.id : room.name;
        executor.submit(() -> {
            try {
                friendsService.sendInvite(friend.getCode(), room.id, roomName);
                Platform.runLater(() -> showToast(tr("friends_invite_sent") + " " + friend.getNick()
                        + " — «" + roomName + "»", "success"));
            } catch (FriendsService.FriendsException e) {
                Platform.runLater(() -> showToast(friendsErrorText(e.getCode()), "error"));
            }
        });
    }

    /**
     * "Войти" on an invitation. The room is joined FIRST and the invitation is only answered
     * once that worked: an invitation marked "принято" whose join silently failed would leave
     * the sender waiting for somebody who never arrives.
     */
    private void onAcceptInvite(FriendInvite invite) {
        if (zeroTierService == null) {
            showToast(tr("zt_windows_only"), "info");
            return;
        }
        invites.remove(invite);
        renderFriendInvites();

        executor.submit(() -> {
            try {
                if (!zeroTierService.isInstalled()) {
                    Platform.runLater(() -> {
                        showToast(tr("friends_invite_zt_off"), "warning");
                        refreshFriendsPanel(true);
                    });
                    return;
                }
                zeroTierService.join(invite.getNetworkId(), this::logZeroTierOutput);
                friendsService.answerInvite(invite.getId(), true);
                Platform.runLater(() -> {
                    showToast(tr("friends_invite_joined") + " «" + invite.roomLabel() + "»", "success");
                    refreshZeroTierPanel(true);
                    refreshFriendsPanel(true);
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    showToast(tr("friends_invite_join_failed") + " " + e.getMessage(), "error");
                    refreshFriendsPanel(true);   // приглашение вернётся в список
                });
            }
        });
    }

    private void onDeclineInvite(FriendInvite invite) {
        invites.remove(invite);
        renderFriendInvites();
        executor.submit(() -> {
            try {
                friendsService.answerInvite(invite.getId(), false);
            } catch (FriendsService.FriendsException e) {
                // Отказ не стоит того, чтобы ругаться: худшее — приглашение вернётся в списке.
                log("[FRIENDS] Не удалось отклонить приглашение: " + e.getCode());
            }
            Platform.runLater(() -> refreshFriendsPanel(true));
        });
    }

    /**
     * The card under the friends list: head on the left, nickname to its right, «о себе»
     * underneath. The player's own profile uses the same layout, but in its own dialog — see
     * {@link #renderMyProfileCard()}.
     */
    private void showFriendCard(Friend friend) {
        if (friend == null) {
            loadedHeadKey = "";
            // The friend was removed (here or from their side) while the window was open.
            if (friendProfileOverlay.isVisible()) hideOverlay(friendProfileOverlay);
            return;
        }

        friendCardNick.setText(friend.getNick() == null ? "" : friend.getNick());
        friendCardCode.setText(friend.getCode() == null ? "" : friend.getCode());

        friendOnlineLabel.setText(tr(friend.isOnline() ? "friends_online" : "friends_offline"));
        friendOnlineChip.getStyleClass().remove("offline");
        if (!friend.isOnline()) friendOnlineChip.getStyleClass().add("offline");

        String about = friend.getAbout();
        boolean hasAbout = about != null && !about.isBlank();
        friendCardAbout.setText(hasAbout ? about : tr("friends_no_about"));
        friendCardAbout.getStyleClass().remove("muted");
        if (!hasAbout) friendCardAbout.getStyleClass().add("muted");

        friendHeadFallback.setText(nickInitial(friend.getNick()));

        // Reloading a head is a file read (and, the first time, a download) - worth skipping
        // while the poller re-renders the same friend twice a minute.
        String headKey = friend.getCode() + "|" + friend.getSkinUrl() + "|" + friend.getNick();
        if (!headKey.equals(loadedHeadKey)) {
            loadedHeadKey = headKey;
            loadFriendHead(friend, headKey);
        }
    }

    /**
     * Loads a friend's head into the card: their published texture, else a lookup by nickname
     * (see FriendsService).
     *
     * @param headKey the key {@link #showFriendCard} stored for this render; the result is
     *                dropped if the card moved on to somebody else while it was loading.
     */
    private void loadFriendHead(Friend friend, String headKey) {
        friendHeadImage.setImage(null);
        friendHeadFallback.setVisible(true);

        executor.submit(() -> {
            Image head = null;
            try {
                Path texture = friendsService.headTextureFor(friend);
                if (texture != null) {
                    Image raw = new Image(texture.toUri().toString());
                    if (!raw.isError()) {
                        head = SkinPreview.fullBody(raw, 8);
                        if (head == null) head = SkinPreview.skinHead(raw);
                    }
                }
            } catch (Exception e) {
                // No head is not an error: the placeholder letter stays, which is exactly what
                // a friend with no skin anywhere should look like.
            }
            final Image loaded = head;
            Platform.runLater(() -> {
                // A click on somebody else while this was loading must not paint over them.
                if (headKey == null || !headKey.equals(loadedHeadKey)) return;
                friendHeadImage.setImage(loaded);
                friendHeadImage.setSmooth(false);
                friendHeadFallback.setVisible(loaded == null);
            });
        });
    }

    /** The player's own skin file on disk, if the profile editor saved one. */
    private String myLocalSkinPath() {
        UserProfile profile = currentUserProfile();
        if (profile == null || profile.getSkinPath() == null || profile.getSkinPath().isBlank()) return null;
        Path file = Path.of(profile.getSkinPath());
        return Files.exists(file) ? file.toString() : null;
    }

    private static String nickInitial(String nick) {
        if (nick == null || nick.isBlank()) return "?";
        return nick.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    /**
     * "Обновить" is a real round trip to the friends API, and the 30-second poller already keeps
     * the list current — so the button is put on a cooldown after each press instead of firing one
     * request per click. The countdown is shown on the button itself, so it reads as "working"
     * rather than as a button that stopped responding.
     */
    @FXML
    private void onRefreshFriends() {
        if (friendsService == null) return;
        if (friendsRefreshCooldownLeft > 0) {
            showToast(tr("friends_refresh_wait") + " " + friendsRefreshCooldownLeft + " "
                    + tr("friends_seconds_short"), "info");
            return;
        }
        startFriendsRefreshCooldown();
        refreshFriendsPanel(true);
    }

    private void startFriendsRefreshCooldown() {
        friendsRefreshCooldownLeft = FRIENDS_REFRESH_COOLDOWN_SECONDS;
        friendsRefreshBtn.setDisable(true);
        updateFriendsRefreshButtonText();

        if (friendsRefreshCooldown != null) friendsRefreshCooldown.stop();
        friendsRefreshCooldown = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(Duration.seconds(1), e -> {
                    friendsRefreshCooldownLeft--;
                    if (friendsRefreshCooldownLeft <= 0) {
                        friendsRefreshCooldownLeft = 0;
                        if (friendsRefreshCooldown != null) friendsRefreshCooldown.stop();
                        friendsRefreshBtn.setDisable(false);
                    }
                    updateFriendsRefreshButtonText();
                }));
        friendsRefreshCooldown.setCycleCount(javafx.animation.Animation.INDEFINITE);
        friendsRefreshCooldown.play();
    }

    private void updateFriendsRefreshButtonText() {
        friendsRefreshBtn.setText(friendsRefreshCooldownLeft > 0
                ? tr("friends_refresh_btn") + " (" + friendsRefreshCooldownLeft + ")"
                : tr("friends_refresh_btn"));
    }

    @FXML
    private void onAddFriendByCode() {
        if (friendsService == null) return;
        String raw = friendCodeInput.getText() == null ? "" : friendCodeInput.getText().trim();
        if (raw.isEmpty()) return;
        String nick = currentNickname();
        if (nick.isBlank()) {
            showToast(tr("friends_err_nick"), "warning");
            return;
        }

        friendAddBtn.setDisable(true);
        final SkinSource skin = mySkinSource();
        executor.submit(() -> {
            try {
                // On a fresh install the code a friend is about to be given only exists after
                // the first sync, so make sure one happened before adding anybody.
                if (friendsService.getMyCode().isBlank()) {
                    friendsService.sync(nick, publishMySkin(skin), friendsService.getMyAbout());
                }
                FriendsService.AddResult result = friendsService.addFriend(raw);
                Platform.runLater(() -> {
                    friendCodeInput.clear();
                    showToast(tr(result.already() ? "friends_already_friend" : "friends_added")
                            + result.friend().getNick(), result.already() ? "info" : "success");
                    // Show them on the spot, from the server's own answer, instead of waiting for
                    // the refresh below (which the poller may already be holding).
                    Friend added = result.friend();
                    friends.removeIf(f -> added.getCode() != null && added.getCode().equals(f.getCode()));
                    friends.add(added);
                    // Opening the card right away is the point: you add people to see who they are.
                    selectedFriendCode = added.getCode();
                    lastFriendsFingerprint = "\u0000";
                    renderFriendsList();
                    refreshFriendsPanel(true);
                });
            } catch (FriendsService.FriendsException e) {
                Platform.runLater(() -> showToast(friendsErrorText(e.getCode()), "error"));
            } catch (Exception e) {
                Platform.runLater(() -> showToast(friendsErrorText("offline"), "error"));
            } finally {
                Platform.runLater(() -> friendAddBtn.setDisable(false));
            }
        });
    }

    @FXML
    private void onCopyMyFriendCode() {
        String code = myFriendCodeField.getText();
        if (code == null || code.isBlank()) {
            showToast(tr("friends_loading"), "info");
            return;
        }
        copyToClipboard(code);
        showToast(tr("friends_code_copied"), "success");
    }

    @FXML
    private void onSaveMyAbout() {
        if (friendsService == null) return;
        String text = myAboutArea.getText() == null ? "" : myAboutArea.getText().trim();
        if (text.length() > MAX_ABOUT_LENGTH) {
            showToast(tr("friends_about_too_long"), "warning");
            return;
        }
        savedAbout = text;
        // Local first: the text is kept even if the server cannot be reached right now, and the
        // next successful sync publishes it.
        friendsService.setLocalAbout(text);
        updateAboutDirtyMarker();
        // The own-profile dialog may be open behind this one - keep it in step with the save.
        if (myProfileOverlay != null && myProfileOverlay.isVisible()) renderMyProfileCard();
        showToast(tr("friends_about_saved"), "success");
        refreshFriendsPanel(true);
    }

    /**
     * Removing is confirmed by a second click: the button asks once and forgets the question a
     * few seconds later, which beats a modal for something this small — and beats no question at
     * all for something that drops a person off the list on a single stray click.
     */
    @FXML
    private void onRemoveSelectedFriend() {
        Friend friend = selectedFriend();
        if (friend == null || friendsService == null) return;

        if (!friendRemoveArmed) {
            friendRemoveArmed = true;
            friendRemoveBtn.setText(tr("friends_remove_confirm"));
            if (friendRemoveDisarm != null) friendRemoveDisarm.stop();
            friendRemoveDisarm = new javafx.animation.PauseTransition(Duration.seconds(4));
            friendRemoveDisarm.setOnFinished(e -> disarmFriendRemoval());
            friendRemoveDisarm.play();
            return;
        }

        disarmFriendRemoval();
        selectedFriendCode = null;
        loadedHeadKey = "";
        executor.submit(() -> {
            try {
                friendsService.removeFriend(friend.getCode());
                Platform.runLater(() -> {
                    friends.removeIf(f -> friend.getCode() != null && friend.getCode().equals(f.getCode()));
                    lastFriendsFingerprint = "\u0000";
                    renderFriendsList();
                    showToast(tr("friends_removed") + friend.getNick(), "info");
                });
            } catch (FriendsService.FriendsException e) {
                Platform.runLater(() -> showToast(friendsErrorText(e.getCode()), "error"));
            }
        });
    }

    private void disarmFriendRemoval() {
        friendRemoveArmed = false;
        friendRemoveBtn.setText(tr("friends_remove_btn"));
    }

    /** Turns a short code from friends.php into a sentence the player can act on. */
    private String friendsErrorText(String code) {
        String key = switch (code == null ? "" : code) {
            case "not_found" -> "friends_err_not_found";
            case "self" -> "friends_err_self";
            case "bad_code" -> "friends_err_bad_code";
            case "unknown_player", "bad_secret" -> "friends_err_unknown_player";
            case "rate_limited" -> "friends_err_rate_limited";
            case "too_many_friends" -> "friends_err_too_many_friends";
            case "not_friend" -> "friends_err_not_friend";
            case "bad_network" -> "friends_err_bad_network";
            case "too_many_invites" -> "friends_err_too_many_invites";
            case "nick_required", "bad_nick" -> "friends_err_nick";
            case "offline" -> "friends_err_offline";
            default -> "friends_err_unknown";
        };
        return tr(key);
    }

    // ============================================================
    //  Navigation (rail <-> screens)
    // ============================================================

    private void initNavigation() {
        // Nothing extra to wire beyond the onAction handlers below — each
        // rail button's ToggleGroup membership is already set in main.fxml.
    }

    /** Shows exactly one top-level screen, hiding the rest — mirrors the concept's .view/.view.active. */
    private void goToView(String name) {
        // Уходим с экрана сервера — гасим его опрос. Останавливал его только onServerDetailBack,
        // а кнопки рейла (Главная, Сборки, Моды, Друзья, Настройки) просто переключали экран:
        // опрос консоли раз в секунду и статуса раз в 4 секунды продолжал бить по VPS до конца
        // сессии, а колбэк консоли ещё и писал в невидимое поле, потому что сверялся с
        // focusedServerId, который оставался выставленным.
        if (!"serverDetail".equals(name) && focusedPoller != null) {
            focusedPoller.stop();
            focusedPoller = null;
            focusedServerId = null;
        }
        // То же для сервера панели VPS: SSH-подключение, поток журнала и RCON живут, пока открыт его экран.
        if (!"serverDetail".equals(name)) vpsManageController.close();
        if ("rent".equals(name)) ensureRentPicker().loadIfNeeded();

        // Каждый переход на другой экран: куда, откуда и сколько секунд человек пробыл на прошлом
        // (11.10.2026 уходило только первое посещение — двое новых сидели 7 и 14 минут без единого
        // нажатия, а порядок и время по экранам так и остались неизвестны; 22.10.2026 — все переходы).
        if (name != null && !name.equals(currentScreen)) {
            long now = System.currentTimeMillis();
            flushUiClicks(false);
            screensSeen.add(name);
            if (screenStats++ < 80) {
                stat("screen", name + (currentScreen == null ? ""
                        : " from=" + currentScreen + ":" + (now - currentScreenSince) / 1000));
            }
            currentScreen = name;
            currentScreenSince = now;
        }

        ScrollPane[] all = { screenHome, screenBuilds, screenFriends, screenRent, screenMods, screenServer, screenServerAdvanced, screenServerDetail, screenSettings, screenTools, screenToolHost, screenProfiles };
        String[] names = { "home", "builds", "friends", "rent", "mods", "server", "serverAdvanced", "serverDetail", "settings", "tools", "toolHost", "profiles" };
        for (int i = 0; i < all.length; i++) {
            boolean match = names[i].equals(name);
            all[i].setVisible(match);
            all[i].setManaged(match);
        }
        ToggleButton[] navButtons = { navHomeButton, navBuildsButton, navFriendsButton, navRentButton, navModsButton, navServerButton, navSettingsButton, navToolsButton, navProfilesButton };
        String[] navNames = { "home", "builds", "friends", "rent", "mods", "server", "settings", "tools", "profiles" };
        for (int i = 0; i < navButtons.length; i++) {
            // serverAdvanced/serverDetail are reached FROM the Server rail tab, so the
            // rail itself should still show "server" as active while either is open.
            boolean isServerFamily = "server".equals(navNames[i]) && ("serverAdvanced".equals(name) || "serverDetail".equals(name));
            boolean isToolFamily = "tools".equals(navNames[i]) && "toolHost".equals(name);
            navButtons[i].setSelected(navNames[i].equals(name) || isServerFamily || isToolFamily);
        }
        // Leaving an open tool closes it: the 3D studio keeps an animation running.
        if (!"toolHost".equals(name) && openTool != null) {
            closeOpenTool();
            toolHostContent.getChildren().clear();
        }
        animateViewChange(name, all, names, navButtons);
    }

    /** Rail order, for the direction a screen arrives from. */
    private static final java.util.List<String> RAIL_ORDER = java.util.List.of(
            "home", "profiles", "builds", "mods", "friends", "rent", "server", "tools", "settings");
    private String lastView = "home";

    private void animateViewChange(String name, ScrollPane[] all, String[] names, ToggleButton[] navButtons) {
        if (name.equals(lastView)) return;
        int from = RAIL_ORDER.indexOf(railFamily(lastView));
        int to = RAIL_ORDER.indexOf(railFamily(name));
        lastView = name;
        for (int i = 0; i < all.length; i++) {
            if (names[i].equals(name)) Motion.enterScreen(all[i].getContent(), to >= from ? 1 : -1);
        }
        for (ToggleButton b : navButtons) {
            if (b.isSelected()) Motion.moveMarker(railMarker, b);
        }
        // the nav handlers render their lists after this returns; stagger what they built
        Platform.runLater(() -> {
            Node list = switch (name) {
                case "home" -> homeBuildGrid;
                case "builds" -> allBuildsGrid;
                case "tools" -> toolsGrid;
                case "profiles" -> profilesList;
                case "friends" -> friendsListBox;
                case "mods" -> modsListPanel;
                default -> null;
            };
            if (list instanceof javafx.scene.Parent parent) Motion.stagger(parent.getChildrenUnmodifiable());
        });
    }

    private static String railFamily(String view) {
        return switch (view) {
            case "serverAdvanced", "serverDetail" -> "server";
            case "toolHost" -> "tools";
            default -> view;
        };
    }

    /** Icon rail's bottom button — opens BLauncher's Telegram channel in the default browser. */
    @FXML
    private void onOpenTelegram() {
        try {
            Os.browse("https://t.me/theBLauncher");
        } catch (Exception e) {
            log("[TELEGRAM] Не удалось открыть браузер: " + e.getMessage());
        }
    }

    /** «Как это работает» в блоке «Сервер на VPS» — инструкция на сайте (что арендовать, что вводить). */
    @FXML
    private void onOpenVpsGuide() {
        try {
            Os.browse("https://blauncher.ru/vps.html");
        } catch (Exception e) {
            showToast("Не удалось открыть браузер. Инструкция: blauncher.ru/vps.html", "info");
        }
    }

    /** Clicking the rail logo opens the official BLauncher site. */
    @FXML
    private void onOpenSite() {
        try {
            Os.browse("https://blauncher.ru");
        } catch (Exception e) {
            log("[SITE] Не удалось открыть сайт: " + e.getMessage());
        }
    }

    @FXML
    private void onNavHome() {
        goToView("home");
        renderHomeBuildGrid();
        renderCurrentBuildPanel();
        renderHomeAccountRow();
    }

    @FXML
    private void onNavBuilds() {
        goToView("builds");
        // Показываем, что уже знаем, сразу — экран должен открыться мгновенно.
        renderBuildsGrid();

        // Rescan mods/ here, not only at startup: dropping a folder of mods in while the launcher
        // is open used to do nothing at all until the next restart, with no hint that a restart is
        // what was missing - the single most common "my mods don't show up" report.
        //
        // В ФОНЕ, а не здесь. Этот обход заходит в flattenNestedModsFolderIfPresent, который
        // копирует КАЖДЫЙ jar из вложенной папки mods (так переносятся инстансы MultiMC и Prism —
        // ровно тот случай, для которого фича и сделана), и ещё сохраняет профиль на каждую
        // найденную папку. В UI-потоке это означало, что окно перестаёт перерисовываться и
        // принимать ввод на всё время копирования сотен мегабайт, причём без индикатора: нарисовать
        // его было нечем, UI-поток стоял внутри Files.copy.
        if (buildsScanInFlight.compareAndSet(false, true)) {
            // Всё, что нужно от интерфейса, снимаем ЗДЕСЬ, в UI-потоке: ник из поля, версию по
            // умолчанию и уже известные имена. Читать их из фона значило бы трогать контролы и
            // список builds не из того потока.
            final String username = usernameField.getText();
            final String defaultVersion = versionListCache.isEmpty() ? "1.20.4" : versionListCache.get(0);
            final java.util.Set<String> known = new java.util.HashSet<>();
            for (Profile p : builds) known.add(p.getName());

            executor.submit(() -> {
                List<Profile> found = List.of();
                try {
                    found = scanModFolders(username, defaultVersion, known);
                } catch (Exception ex) {
                    System.out.println("[BUILDS] обход папок модов не удался: " + ex.getMessage());
                } finally {
                    buildsScanInFlight.set(false);
                }
                final List<Profile> discovered = found;
                Platform.runLater(() -> {
                    if (discovered.isEmpty()) return;
                    // В builds добавляем только отсюда — из UI-потока.
                    builds.addAll(discovered);
                    renderBuildsGrid();
                    renderHomeBuildGrid();
                    refreshServerBuildCombo();
                    showToast(discovered.size() == 1
                            ? "Найдена папка с модами — создана сборка. Проверьте версию и загрузчик в «Изменить»."
                            : "Найдено папок с модами: " + discovered.size()
                              + " — созданы сборки. Проверьте версию и загрузчик.",
                            "success");
                });
            });
        }
    }

    @FXML
    private void onNavMods() {
        goToView("mods");
        if (modsListPanel.getChildren().isEmpty()) {
            onSearch();
        }
    }

    @FXML
    private void onNavServer() {
        goToView("server");
        refreshServerBuildCombo();
        renderMyServersQuickList();
        // Monitoring moved here from the builds screen: it is about servers, has its own
        // data file, and nothing to do with builds.
        renderMonitorList();
        adoptRunningServersInBackground();
    }

    /**
     * Play together: the overlay-network room, the internet tunnel and the address a
     * friend needs. All of it used to be a panel on the server screen, with the address
     * itself on a different screen again.
     */
    @FXML
    private void onNavFriends() {
        goToView("friends");
        // Re-check the overlay network whenever the tab is opened - the engine may have come
        // online (or gone offline) since the last render.
        refreshZeroTierPanel(true);
        // The join address is the other half of "play with friends": it belongs on this
        // screen, next to the room, instead of three screens away on the server detail.
        renderFriendAddress();
        // Opening the tab is the natural moment to look for new friends, new nicknames and
        // fresh "о себе" texts, rather than waiting up to 30 s for the poller's next tick.
        refreshFriendsPanel(true);
    }

    @FXML
    private void onNavSettings() {
        goToView("settings");
        ensureBackgroundPicker();
    }

    // ---------- Фон ----------

    private boolean backgroundPickerBuilt;

    private Path backgroundsDir() {
        return gameDir.resolve("backgrounds");
    }

    /** Built on the first visit to Settings: its sixteen previews are not worth decoding at start. */
    private void ensureBackgroundPicker() {
        if (backgroundPickerBuilt || backgroundPickerHost == null) return;
        backgroundPickerBuilt = true;
        BackgroundPicker picker = new BackgroundPicker(backgroundsDir(), config.getBackgroundImage(),
                config.getBackgroundDim(), this::tr,
                id -> {
                    config.setBackgroundImage(id);
                    saveConfigQuietly();
                    applyBackground();
                },
                dim -> {
                    config.setBackgroundDim(dim);
                    applyBackground();
                    backgroundDimSave.playFromStart();
                },
                message -> showToast(tr("bg_add_failed") + " " + message, "error"));
        backgroundPickerHost.getChildren().setAll(picker.node());
    }

    /** The slider fires on every pixel of a drag; the config is written once it rests. */
    private final javafx.animation.PauseTransition backgroundDimSave = createBackgroundDimSave();

    private javafx.animation.PauseTransition createBackgroundDimSave() {
        javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(Duration.millis(400));
        pause.setOnFinished(e -> saveConfigQuietly());
        return pause;
    }

    /**
     * Draws the chosen picture behind every screen, veiled by the palette's own background colour
     * so text stays readable on any picture and in every theme. Runs again after each palette
     * change, because the veil is that palette's colour.
     */
    private EndSky endSky;

    private boolean paletteIsLight() {
        try {
            return javafx.scene.paint.Color.web((paletteOnScreen != null ? paletteOnScreen : currentPalette()).value("bg"))
                    .getBrightness() > 0.6;
        } catch (Exception e) {
            return false;
        }
    }

    /** The palette's background colour at the given opacity, as a background rule. */
    private String veilStyle(double alpha) {
        javafx.scene.paint.Color c;
        try {
            c = javafx.scene.paint.Color.web((paletteOnScreen != null ? paletteOnScreen : currentPalette()).value("bg"));
        } catch (Exception e) {
            c = javafx.scene.paint.Color.BLACK;
        }
        return String.format(java.util.Locale.ROOT, "-fx-background-color: rgba(%d,%d,%d,%.3f);",
                (int) Math.round(c.getRed() * 255), (int) Math.round(c.getGreen() * 255),
                (int) Math.round(c.getBlue() * 255), alpha);
    }

    /** Puts the living End sky behind every screen, or takes it away. */
    private void showEndSky(boolean on) {
        if (backgroundView == null || !(backgroundView.getParent() instanceof javafx.scene.layout.Pane root)) return;
        if (on) {
            if (endSky == null) {
                endSky = new EndSky();
                root.getChildren().add(root.getChildren().indexOf(backgroundView) + 1, endSky);
            }
            endSky.setVisible(true);
            endSky.setLight(paletteIsLight());
            endSky.start();
        } else if (endSky != null) {
            endSky.stop();
            endSky.setVisible(false);
        }
    }

    /** The palette last put on screen, including the palette editor's live preview. */
    private Palette paletteOnScreen;

    private void applyBackground() {
        if (backgroundView == null || config == null) return;
        Node content = backgroundView.getParent() == null ? null : backgroundView.getParent().lookup(".content-area");
        boolean end = BackgroundPicker.END.equals(config.getBackgroundImage());
        showEndSky(end);
        Node rail = backgroundView.getParent() == null ? null : backgroundView.getParent().lookup(".rail");
        if (end) {
            backgroundView.setStyle("");
            if (content != null) content.setStyle("-fx-background-color: transparent;");
            // the rail is a veil too, so the flashes reach the left edge of the window
            if (rail != null) rail.setStyle(veilStyle(0.45));
            return;
        }
        String url = BackgroundPicker.resolveUrl(config.getBackgroundImage());
        if (url == null) {
            backgroundView.setStyle("");
            if (content != null) content.setStyle("");
            if (rail != null) rail.setStyle("");
            return;
        }
        if (rail != null) rail.setStyle(veilStyle(Math.min(0.85, config.getBackgroundDim() + 0.2)));
        backgroundView.setStyle("-fx-background-image: url(\"" + url + "\");"
                + "-fx-background-size: cover; -fx-background-position: center center;"
                + "-fx-background-repeat: no-repeat;");
        if (content != null) {
            javafx.scene.paint.Color veil;
            try {
                veil = javafx.scene.paint.Color.web((paletteOnScreen != null ? paletteOnScreen : currentPalette()).value("bg"));
            } catch (Exception e) {
                veil = javafx.scene.paint.Color.BLACK;
            }
            content.setStyle(String.format(java.util.Locale.ROOT, "-fx-background-color: rgba(%d,%d,%d,%.3f);",
                    (int) Math.round(veil.getRed() * 255), (int) Math.round(veil.getGreen() * 255),
                    (int) Math.round(veil.getBlue() * 255), config.getBackgroundDim()));
        }
    }

    // ---------- Инструменты ----------

    /** The tool currently open in screenToolHost, if it needs closing when left. */
    private AutoCloseable openTool;

    @FXML
    private void onNavTools() {
        renderToolsGrid();
        goToView("tools");
    }

    /**
     * One card per tool. The "soon" ones have no action on purpose: they become real once the
     * launcher's online count passes a hundred, and until then a card that opens an empty screen
     * would only disappoint.
     */
    private void renderToolsGrid() {
        if (toolsGrid == null) return;
        toolsGrid.getChildren().setAll(
                toolCard("fth-edit-3", tr("tool_skin_title"), tr("tool_skin_sub"), () -> openSkinEditor(false)),
                toolCard("fth-flag", tr("tool_cape_title"), tr("tool_cape_sub"), () -> openSkinEditor(true)),
                toolCard("fth-box", tr("tool_studio_title"), tr("tool_studio_sub"), this::openDisplayStudio),
                toolCard("fth-globe", tr("tool_public_ip_title"), tr("tool_public_ip_sub"), null),
                // Был «Скоро» — с 17.10.2026 это аренда VPS у serv.host, вкладка «Купить сервер».
                toolCard("fth-server", tr("tool_own_server_title"), tr("tool_own_server_sub"), this::onNavRent));
    }

    private Node toolCard(String icon, String title, String sub, Runnable action) {
        VBox card = new VBox(8);
        card.getStyleClass().add("tool-card");
        card.setPrefWidth(250);
        card.setMinHeight(150);

        HBox top = new HBox(8);
        top.setAlignment(Pos.CENTER_LEFT);
        FontIcon glyph = new FontIcon(icon);
        glyph.setIconSize(22);
        glyph.getStyleClass().add("tool-card-icon");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        top.getChildren().addAll(glyph, spacer);
        if (action == null) {
            Label soon = new Label(tr("tool_soon"));
            soon.getStyleClass().add("tool-soon-chip");
            top.getChildren().add(soon);
            card.getStyleClass().add("soon");
        }

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("tool-card-title");
        titleLabel.setWrapText(true);
        Label subLabel = new Label(sub);
        subLabel.getStyleClass().add("toggle-sub");
        subLabel.setWrapText(true);
        card.getChildren().addAll(top, titleLabel, subLabel);

        if (action != null) {
            card.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY) action.run();
            });
        }
        return card;
    }

    /** Puts a tool on the full-area host screen. */
    private void showTool(String title, Node content, AutoCloseable handle) {
        if (openTool != handle) closeOpenTool();
        openTool = handle;
        toolHostTitle.setText(title);
        toolHostContent.getChildren().setAll(content);
        goToView("toolHost");
    }

    private void closeOpenTool() {
        if (openTool == null) return;
        try {
            openTool.close();
        } catch (Exception e) {
            log("[TOOLS] " + e.getMessage());
        }
        openTool = null;
    }

    private void openSkinEditor(boolean cape) {
        SkinEditor editor = new SkinEditor(cape, new SkinEditor.Host() {
            @Override public String tr(String key) { return MainController.this.tr(key); }
            @Override public void toast(String message, String type) { showToast(message, type); }
            @Override public void saveToProfile(byte[] png, boolean isCape) { saveEditedTexture(png, isCape); }
            @Override public void loadProfileTexture(boolean isCape, java.util.function.Consumer<Image> onLoaded) {
                loadOwnTexture(isCape, onLoaded);
            }
            @Override public boolean invertMouse() { return config.isInvertMouse(); }
        });
        showTool(tr(cape ? "tool_cape_title" : "tool_skin_title"), editor.node(), editor);
    }

    /**
     * The painted texture becomes the current profile's skin or cape - the same file, the same
     * MineSkin upload and the same launch path as a skin picked in the profile editor.
     */
    private void saveEditedTexture(byte[] png, boolean cape) {
        UserProfile profile = currentUserProfile();
        if (profile == null) {
            showToast(tr("skin_need_profile"), "warning");
            onOpenProfiles();
            return;
        }
        executor.submit(() -> {
            Path saved = saveProfileTexture(profile, png, cape);
            Platform.runLater(() -> {
                if (saved == null) {
                    showToast(tr("profile_source_bad_image"), "error");
                    return;
                }
                if (cape) {
                    profile.setCapePath(saved.toString());
                } else {
                    profile.setSkinPath(saved.toString());
                    // the hosted copy of the old skin must not outlive it
                    profile.setSkinUrl(null);
                    uploadProfileSkinToMineskin(profile, saved, false);
                }
                saveUserProfiles();
                        showToast(tr(cape ? "cape_saved" : "skin_saved") + " «" + profile.getName() + "»", "success");
            });
        });
    }

    /** The current profile's own texture: its local file, or for a skin the one found by nickname. */
    private void loadOwnTexture(boolean cape, java.util.function.Consumer<Image> onLoaded) {
        UserProfile profile = currentUserProfile();
        final String local = profile == null ? null : (cape ? profile.getCapePath() : profile.getSkinPath());
        final String nick = currentNickname();
        final String skinUrl = profile != null ? profile.getSkinUrl() : null;
        executor.submit(() -> {
            Image image = null;
            try {
                Path texture = null;
                if (local != null && !local.isBlank() && Files.isRegularFile(Path.of(local))) {
                    texture = Path.of(local);
                } else if (!cape && nick != null && !nick.isBlank() && friendsService != null) {
                    texture = friendsService.headTextureForSelf(nick, skinUrl);
                }
                if (texture != null) {
                    Image raw = new Image(texture.toUri().toString());
                    if (!raw.isError()) image = raw;
                }
            } catch (Exception e) {
                log("[SKIN] " + e.getMessage());
            }
            final Image loaded = image;
            Platform.runLater(() -> onLoaded.accept(loaded));
        });
    }

    private void openDisplayStudio() {
        DisplayStudio studio = new DisplayStudio(new DisplayStudio.Host() {
            @Override public String tr(String key) { return MainController.this.tr(key); }
            @Override public void toast(String message, String type) { showToast(message, type); }
            @Override public void copy(String text) { copyToClipboard(text); }
            @Override public Path versionsDir() { return gameDir.resolve("versions"); }
            @Override public Path studioDir() { return gameDir.resolve("studio"); }
            @Override public java.util.concurrent.ExecutorService executor() { return executor; }
            @Override public boolean invertMouse() { return config.isInvertMouse(); }
            @Override public String language() { return currentLanguage == null ? "ru" : currentLanguage; }
        });
        showTool(tr("tool_studio_title"), studio.node(), studio);
    }


    /**
     * Fetches the real Mojang version list in the background and swaps it into every registered
     * version ComboBox (versionCombosToRefresh), preserving each combo's current selection where
     * possible. Safe to call with no network at all — on failure this just logs and leaves
     * versionListCache at its FALLBACK_VERSIONS default, so pickers stay usable (a version
     * that's already downloaded is still selectable and launchable fully offline either way;
     * this only affects which NEW versions show up to pick from).
     */
    private void refreshVersionListCache() {
        boolean includeSnapshots = config.isShowSnapshots();
        executor.submit(() -> {
            try {
                List<String> fetched = versionDownloadService.getAvailableVersions(includeSnapshots);
                if (fetched == null || fetched.isEmpty()) return;
                versionListCache = fetched;
                Platform.runLater(this::applyVersionListToCombos);
            } catch (Exception ex) {
                stat("versions_fail", ex.getClass().getSimpleName());
                log("[VERSIONS] Не удалось обновить список версий (офлайн?) — используется сохранённый список: " + ex.getMessage());
            }
        });
    }

    /** Marks already-downloaded versions with a check icon, in both the dropdown list and the
     *  closed combo's own display. An icon rather than a "✓" character: the bundled faces have
     *  no U+2713, so JavaFX used to substitute a system font for it. */
    private void styleVersionComboWithInstalledMarker(ComboBox<String> combo) {
        javafx.util.Callback<javafx.scene.control.ListView<String>, javafx.scene.control.ListCell<String>> factory = lv ->
                new javafx.scene.control.ListCell<>() {
                    @Override
                    protected void updateItem(String item, boolean empty) {
                        super.updateItem(item, empty);
                        if (empty || item == null) {
                            setText(null);
                            setGraphic(null);
                            return;
                        }
                        boolean installed = versionDownloadService.isVersionInstalled(item);
                        setText(item);
                        if (installed) {
                            FontIcon check = new FontIcon("fth-check");
                            check.getStyleClass().add("installed-check");
                            setGraphic(check);
                        } else {
                            setGraphic(null);
                        }
                    }
                };
        combo.setCellFactory(factory);
        combo.setButtonCell(factory.call(null));
    }

    /** Makes a small ComboBox dropdown fit its current item count instead of reserving the default
     *  visible row count (which leaves unused blank rows / a scrollbar for short lists). */
    private void fitComboToItemCount(ComboBox<?> combo) {
        int count = combo.getItems() == null ? 0 : combo.getItems().size();
        combo.setVisibleRowCount(Math.max(1, count));
    }

    /** Guards every PROGRAMMATIC selection made on homeVanillaVersionCombo/homeVanillaLoaderCombo/
     *  serverVanillaVersionCombo/serverVanillaLoaderCombo (restoring a saved value, or this class
     *  correcting one once the real version list arrives) — JavaFX's ComboBoxBase fires onAction
     *  on ANY value change, not just user clicks, so without this guard restoring/correcting a
     *  selection immediately re-triggers onHomeVanillaVersionSelected() et al. and saves right
     *  back over whatever was actually saved. See applyVersionListToCombos()'s javadoc for the
     *  concrete bug this caused. */
    private boolean restoringVanillaCombos = false;
    /** Guards async refreshes of the Home loader combo; stale slower fetches don't overwrite newer ones. */
    private long homeLoaderRefreshSeq = 0;
    /** Loader options that arrived while the Home loader dropdown was open. Applied on close so the
     *  visible list doesn't resize/flicker while the user is interacting with it. */
    private List<String> pendingHomeLoaderOptions;

    /**
     * Re-populates every version combo once the real Mojang manifest replaces
     * FALLBACK_VERSIONS. For homeVanillaVersionCombo/serverVanillaVersionCombo specifically, this
     * re-asserts the SAVED config value (lastSelectedVersion/lastSelectedServerVersion) rather
     * than just "whatever happens to be selected right now" — necessary because the very first
     * restore attempt (renderCurrentBuildPanel()/initServerScreens(), run at startup before this
     * ever fires) can only check against FALLBACK_VERSIONS' 7 hardcoded entries; a saved
     * preference outside that small set (in practice: any current, non-ancient MC release) fails
     * that initial contains() check, falls back to selectFirst(), and — without restoringVanillaCombos
     * suppressing the resulting onAction — used to overwrite the saved value with that wrong
     * fallback right there, before the network fetch even had a chance to load the real list and
     * correct it. This method is what actually restores the true saved version once the full list
     * is available; other combos in versionCombosToRefresh keep the old "whatever was selected"
     * behavior since they aren't backed by a persisted preference.
     */
    private void applyVersionListToCombos() {
        restoringVanillaCombos = true;
        try {
            for (ComboBox<String> combo : versionCombosToRefresh) {
                String preferred = combo.getSelectionModel().getSelectedItem();
                if (combo == homeVanillaVersionCombo) {
                    preferred = config.getLastSelectedVersion();
                } else if (combo == serverVanillaVersionCombo) {
                    preferred = config.getLastSelectedServerVersion();
                }
                combo.setItems(FXCollections.observableArrayList(
                        combo == homeVanillaVersionCombo ? homeVersionItems() : versionListCache));
                if (preferred != null && !preferred.isBlank() && combo.getItems().contains(preferred)) {
                    combo.getSelectionModel().select(preferred);
                } else if (!combo.getItems().isEmpty()) {
                    combo.getSelectionModel().selectFirst();
                }
            }
        } finally {
            restoringVanillaCombos = false;
        }
        refreshHomeLoaderOptionsForVersion(homeVanillaVersionCombo.getSelectionModel().getSelectedItem());
    }

    /** Home quick-launch loader choices come from the same public loader APIs as installation:
     *  a loader is offered only when the selected Minecraft version actually has downloadable
     *  builds. No combined pseudo-loaders are offered — only real server-published loaders. */
    private void refreshHomeLoaderOptionsForVersion(String mcVersion) {
        if (mcVersion == null || mcVersion.isBlank()) return;
        long requestToken = ++homeLoaderRefreshSeq;
        if (homeRustSelected()) return;   // у Rustcraft загрузчиков нет, список скрыт
        List<String> candidates = List.of("Forge", "Fabric", "NeoForge", "Quilt");
        executor.submit(() -> {
            List<String> available = new ArrayList<>();
            available.add("Vanilla");
            for (String display : candidates) {
                String internal = mapDisplayLoaderToInternal(display);
                if (!fetchLoaderVersions(mcVersion, internal).isEmpty()) {
                    available.add(display);
                }
            }
            List<String> result = available;
            Platform.runLater(() -> {
                if (requestToken != homeLoaderRefreshSeq) return;
                if (homeVanillaLoaderCombo.isShowing()) {
                    // Don't resize the open dropdown; apply when the user closes it.
                    pendingHomeLoaderOptions = result;
                } else {
                    applyHomeLoaderOptions(result);
                }
            });
        });
    }

    /**
     * Which loader the quick-play combo lands on: what was picked, else the saved choice, else
     * Fabric (BL Core only loads there), else whatever the version has - Vanilla for versions
     * Fabric does not exist for. Falling back does not overwrite the saved choice: the combo is
     * filled with restoringVanillaCombos set.
     */
    private String preferredHomeLoader(List<String> options, String previous) {
        if (options == null || options.isEmpty()) return null;
        if (previous != null && options.contains(previous)) return previous;
        String saved = config.getLastSelectedLoader();
        if (saved != null && options.contains(saved)) return saved;
        if (options.contains("Fabric")) return "Fabric";
        return options.get(0);
    }

    private static final String ENGINE_JAVA = "Java";
    private static final String ENGINE_RUST = "Rust";

    /** На карточке «Без модов и сборок» выбран язык игры Rust — запуск через Rustcraft. */
    private boolean homeRustSelected() {
        return homeEngineCombo != null && ENGINE_RUST.equals(homeEngineCombo.getSelectionModel().getSelectedItem())
                && RustcraftService.platformSupported();
    }

    /**
     * Версии на карточке «Без модов и сборок»: у Java — все, у Rust — только те, что умеет
     * Rustcraft (см. RustcraftService). Серверы и сборки этого выбора не касаются.
     */
    private List<String> homeVersionItems() {
        return homeRustSelected() ? new ArrayList<>(RustcraftService.SUPPORTED_VERSIONS) : new ArrayList<>(versionListCache);
    }

    /** Выбранная на главной версия Minecraft. */
    private String homeVanillaVersion() {
        return homeVanillaVersionCombo.getSelectionModel().getSelectedItem();
    }

    /** У Rust выбора загрузчика нет совсем — список прячется. */
    private void applyEngineToLoaderCombo() {
        boolean java = !homeRustSelected();
        homeVanillaLoaderCombo.setVisible(java);
        homeVanillaLoaderCombo.setManaged(java);
    }

    @FXML
    private void onHomeEngineSelected() {
        String engine = homeEngineCombo.getSelectionModel().getSelectedItem();
        if (engine == null || restoringVanillaCombos) return;
        if (!engine.equals(config.getLastSelectedEngine())) {
            stat("engine", engine.toLowerCase());
            config.setLastSelectedEngine(engine);
            try { configService.saveConfig(config); } catch (Exception ignored) { }
        }
        applyEngineToLoaderCombo();
        restoringVanillaCombos = true;
        try {
            homeVanillaVersionCombo.setItems(FXCollections.observableArrayList(homeVersionItems()));
            String saved = homeRustSelected() ? null : config.getLastSelectedVersion();
            if (saved != null && homeVanillaVersionCombo.getItems().contains(saved)) {
                homeVanillaVersionCombo.getSelectionModel().select(saved);
            } else {
                homeVanillaVersionCombo.getSelectionModel().selectFirst();
            }
        } finally {
            restoringVanillaCombos = false;
        }
        updatePlayButtonTint(homeVanillaVersion());
        refreshHomeLoaderOptionsForVersion(homeVanillaVersion());
        // Язык выбирают на карточке «Без модов и сборок» — значит, играть хотят именно без сборки.
        // Режим сервера не трогаем: там этот выбор действует, когда для сервера взято «Без модов».
        if (selectedServer != null) {
            renderCurrentBuildPanel();
        } else if (selectedBuild != null && !selectedBuild.isVanillaEntry()) {
            Platform.runLater(() -> {
                selectedBuild = null;
                renderHomeBuildGrid();
                renderBuildsGrid();
                renderCurrentBuildPanel();
            });
        }
    }

    private void applyHomeLoaderOptions(List<String> result) {
        var current = homeVanillaLoaderCombo.getItems();
        boolean unchanged = current != null && current.size() == result.size();
        if (unchanged) {
            for (int i = 0; i < result.size(); i++) {
                if (!java.util.Objects.equals(current.get(i), result.get(i))) {
                    unchanged = false;
                    break;
                }
            }
        }
        if (unchanged) return;

        String previous = homeVanillaLoaderCombo.getSelectionModel().getSelectedItem();
        restoringVanillaCombos = true;
        try {
            homeVanillaLoaderCombo.setItems(FXCollections.observableArrayList(result));
            fitComboToItemCount(homeVanillaLoaderCombo);
            homeVanillaLoaderCombo.getSelectionModel().select(preferredHomeLoader(result, previous));
        } finally {
            restoringVanillaCombos = false;
        }
    }

    @FXML
    private void onToggleShowSnapshots() {
        config.setShowSnapshots(showSnapshotsToggle.isSelected());
        try { configService.saveConfig(config); } catch (Exception ignored) { }
        refreshVersionListCache();
    }

    @FXML
    private void onToggleOfflineMode() {
        config.setOfflineMode(offlineModeToggle.isSelected());
        try { configService.saveConfig(config); } catch (Exception ignored) { }
        log(config.isOfflineMode()
                ? "[SETTINGS] Офлайн-режим включён — запуск всегда без сети, даже при выполненном входе."
                : "[SETTINGS] Офлайн-режим выключен.");
    }

    /**
     * Отключение анонимной статистики.
     *
     * <p>Флаг {@code telemetryEnabled} в конфиге был с самого начала, и javadoc
     * {@link OnlineStatsService} обещал переключатель в настройках — но {@code setTelemetryEnabled}
     * не вызывался нигде, ни один элемент интерфейса к нему не был привязан, и выключить
     * статистику можно было только правкой файла руками.
     *
     * <p>Переключатель действует СРАЗУ: сердцебиение останавливается или запускается, а не ждёт
     * перезапуска лаунчера. Это вторая половина той же находки — {@code ping()} читал флаг
     * только при старте.
     */
    @FXML
    private void onToggleMinimizeOnLaunch() {
        config.setMinimizeOnLaunch(minimizeOnLaunchToggle.isSelected());
        saveConfigQuietly();
    }

    @FXML
    private void onToggleAnimations() {
        config.setAnimations(animationsToggle.isSelected());
        Motion.setEnabled(config.isAnimations());
        saveConfigQuietly();
    }

    @FXML
    private void onToggleInvertMouse() {
        config.setInvertMouse(invertMouseToggle.isSelected());
        saveConfigQuietly();
    }

    @FXML
    private void onToggleBlBadge() {
        config.setBlBadge(blBadgeToggle.isSelected());
        saveConfigQuietly();
    }

    @FXML
    private void onToggleTelemetry() {
        boolean on = telemetryToggle.isSelected();
        config.setTelemetryEnabled(on);
        try { configService.saveConfig(config); } catch (Exception ignored) { }
        if (onlineStatsService != null) {
            if (on) onlineStatsService.start();
            else onlineStatsService.stop();
        }
        log(on
                ? "[SETTINGS] Анонимная статистика включена."
                : "[SETTINGS] Анонимная статистика выключена — лаунчер больше не сообщает о себе.");
    }

    // ============================================================
    //  Builds (Profile-backed "build cards") — Home + Builds screens
    // ============================================================

    private void loadBuilds() {
        builds.clear();
        try {
            builds.addAll(profileService.loadAllProfiles());
        } catch (IOException e) {
            log("[BUILDS] Could not load saved builds: " + e.getMessage());
        }
        if (builds.isEmpty()) {
            Profile def = new Profile();
            def.setName("Vanilla");
            builds.add(def);
            try {
                profileService.saveProfile(def);
            } catch (IOException e) {
                log("[BUILDS] Could not save default build: " + e.getMessage());
            }
        }
        discoverModFolderBuilds();
        // Home always opens on "Без модов и сборок" rather than restoring the last pick —
        // requested behavior, not a bug: selectedBuild == null means the vanilla quick-play card.
        selectedBuild = null;
    }

    /** Mods placed straight into gameDir/mods/<FolderName>/ - by hand, or copied over from
     *  another launcher entirely - used to just sit there unused: nothing ever created a build
     *  for that folder name, so nothing pointed a version/loader at it and syncMods() never had
     *  a reason to pull those jars into a live game. Any such folder that doesn't already match
     *  a known build's name gets a build auto-created here, named after the folder, so its mods
     *  become reachable the same way any other build's are. The guessed version/loader (newest
     *  known release, vanilla) is very likely wrong for an unknown modpack - this is meant to get
     *  the build to *exist* and show up, not to be correct out of the box; fix version/loader
     *  from the Edit Build modal once mod compatibility surfaces the real requirement. */
    /**
     * Синхронный вариант — для запуска лаунчера, где это выполняется ещё до показа окна.
     * С экрана «Сборки» вызывается не он, а {@link #scanModFolders} в фоне: см. onNavBuilds.
     */
    private int discoverModFolderBuilds() {
        java.util.Set<String> known = new java.util.HashSet<>();
        for (Profile p : builds) known.add(p.getName());
        List<Profile> found = scanModFolders(usernameField.getText(),
                versionListCache.isEmpty() ? "1.20.4" : versionListCache.get(0), known);
        builds.addAll(found);
        return found.size();
    }

    /**
     * Обходит {@code gameDir/mods/*} и возвращает сборки, которые стоит добавить. НИЧЕГО не
     * меняет в состоянии интерфейса — ни списка {@code builds}, ни контролов.
     *
     * <p>Так разделено не ради красоты. Метод копирует файлы (см.
     * {@link #flattenNestedModsFolderIfPresent}), поэтому обязан выполняться в фоне, а список
     * {@code builds} рисует UI-поток, и менять его из фона нельзя — это дало бы гонку вместо
     * прежнего зависания. Поэтому всё, что нужно от интерфейса (ник, версия по умолчанию,
     * уже известные имена), передаётся аргументами, снятыми заранее в UI-потоке.
     *
     * <p>Файлы профилей сохраняются здесь же: это тоже ввод-вывод, и ему в UI-потоке не место.
     */
    private List<Profile> scanModFolders(String username, String defaultVersion,
                                         java.util.Set<String> knownNames) {
        List<Profile> created = new ArrayList<>();
        Path modsRoot = gameDir.resolve("mods");
        if (!Files.isDirectory(modsRoot)) return created;
        try (var stream = Files.list(modsRoot)) {
            for (Path dir : stream.filter(Files::isDirectory).collect(java.util.stream.Collectors.toList())) {
                String folderName = dir.getFileName().toString();
                if (knownNames.contains(folderName)) continue;
                // Deleting a build leaves its mods on disk on purpose (they're the player's files),
                // so without this the very next scan would recreate the build they just deleted.
                if (Files.exists(dir.resolve(DISMISSED_MARKER))) continue;

                flattenNestedModsFolderIfPresent(dir);

                boolean hasJar;
                try (var files = Files.list(dir)) {
                    hasJar = files.anyMatch(f -> f.getFileName().toString().endsWith(".jar"));
                }
                // An empty staging folder isn't worth resurrecting as a build (e.g. left behind
                // by a build that was deleted from the launcher but whose mods/ subfolder never
                // got cleaned up).
                if (!hasJar) continue;

                Profile discovered = new Profile(folderName, username, "",
                        defaultVersion, guessLoaderFromJarNames(dir));
                discovered.setIconText(folderName.substring(0, Math.min(2, folderName.length())).toUpperCase());
                discovered.setIconGradient(randomCardGradient());
                created.add(discovered);
                knownNames.add(folderName);
                try {
                    profileService.saveProfile(discovered);
                } catch (IOException e) {
                    log("[BUILDS] Не удалось сохранить автоматически найденную сборку \"" + folderName + "\": " + e.getMessage());
                }
                log("[BUILDS] Найдена папка с модами \"" + folderName + "\" - создана сборка.");
            }
        } catch (IOException ignored) {}
        return created;
    }

    /** Dropped into mods/&lt;name&gt;/ when the matching build is deleted, so
     *  {@link #discoverModFolderBuilds()} stops re-creating it. Delete this file by hand to have
     *  the folder picked up as a build again. Starts with a dot and isn't a .jar, so syncMods()
     *  never copies it into the live mods folder. */
    private static final String DISMISSED_MARKER = ".blauncher-not-a-build";

    /** Instances copied in wholesale from another launcher (MultiMC/Prism/CurseForge-style
     *  exports) keep their jars one level deeper, in their own "mods" subfolder, rather than
     *  directly in the profile-named folder the way BLauncher's own staging convention expects
     *  (see ModrinthService/syncMods()). If {@code dir} has no jars directly but {@code dir/mods}
     *  does, copy (never move - never destroy the original layout) those jars up a level so every
     *  other piece of code that expects BLauncher's flat convention just works without having to
     *  special-case this. Existing same-named files at the destination are left untouched. */
    private void flattenNestedModsFolderIfPresent(Path dir) {
        boolean rootHasJar;
        try (var files = Files.list(dir)) {
            rootHasJar = files.anyMatch(f -> f.getFileName().toString().endsWith(".jar"));
        } catch (IOException e) {
            return;
        }
        if (rootHasJar) return;

        Path nested = dir.resolve("mods");
        if (!Files.isDirectory(nested)) return;
        try (var files = Files.list(nested)) {
            for (Path jar : files.filter(f -> f.getFileName().toString().endsWith(".jar"))
                    .collect(java.util.stream.Collectors.toList())) {
                Path dest = dir.resolve(jar.getFileName());
                if (!Files.exists(dest)) {
                    Files.copy(jar, dest, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        } catch (IOException e) {
            log("[BUILDS] Не удалось перенести моды из вложенной папки \"" + dir.getFileName() + "/mods\": " + e.getMessage());
        }
    }

    /** Best-effort loader guess for an auto-discovered build from its jar filenames alone (no
     *  manifest/fabric.mod.json parsing) - e.g. a "fabric-api.jar"/"fabric-language-kotlin.jar"
     *  pair is about as unambiguous a signal as filenames get. Defaults to "vanilla" (the same
     *  fallback discoverModFolderBuilds() always used before this existed) when nothing matches;
     *  wrong either way is always fixable from the Edit Build modal. */
    private String guessLoaderFromJarNames(Path dir) {
        try (var files = Files.list(dir)) {
            List<String> names = files.map(f -> f.getFileName().toString().toLowerCase())
                    .filter(n -> n.endsWith(".jar"))
                    .collect(java.util.stream.Collectors.toList());
            if (names.stream().anyMatch(n -> n.contains("neoforge"))) return "neoforge";
            if (names.stream().anyMatch(n -> n.contains("fabric"))) return "fabric";
            if (names.stream().anyMatch(n -> n.contains("forge"))) return "forge";
        } catch (IOException ignored) {}
        return "vanilla";
    }

    private void initHomeScreen() {
        // The home screen is never shorter than the window, so the spacer in main.fxml pushes the
        // Play block to the bottom. A min height, not fitToHeight: fitToHeight with wrapping text
        // made the layout rock between two heights after a window resize (the page "scrolled
        // up and down" on its own when going full screen).
        if (screenHome.getContent() instanceof Region homeContent) {
            homeContent.minHeightProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                    () -> Math.floor(screenHome.getViewportBounds().getHeight()), screenHome.viewportBoundsProperty()));
            // Home does not scroll. The scene-wide smooth wheel ran before the page's own filter
            // and scrolled it anyway, because at the 1050x700 floor the page was ~90px taller
            // than the window. Now the wheel is off it, and a short window gets a tighter page
            // (measured: 747px at full spacing, about 630 tight, in a 659px viewport). The switch
            // depends on the window only, never on the page's own height, so it cannot rock.
            screenHome.getProperties().put(Motion.NO_SCROLL, true);
            homeStatusLabel.managedProperty().bind(homeStatusLabel.textProperty().isNotEmpty());
            Runnable fit = () -> {
                boolean tight = screenHome.getViewportBounds().getHeight() < 770;
                if (homeContent instanceof VBox box) box.setSpacing(tight ? 10 : 18);
                if (tight) {
                    if (!homeContent.getStyleClass().contains("home-tight")) homeContent.getStyleClass().add("home-tight");
                } else {
                    homeContent.getStyleClass().remove("home-tight");
                }
                homeSubLabel.setVisible(!tight);
                homeSubLabel.setManaged(!tight);
            };
            screenHome.viewportBoundsProperty().addListener((o, was, now) -> fit.run());
            fit.run();
        }
        // One-time move to the living End sky for a config that never picked a background.
        if (config.getBackgroundRevision() < 1) {
            if (config.getBackgroundImage().isEmpty()) config.setBackgroundImage(BackgroundPicker.END);
            config.setBackgroundRevision(1);
            saveConfigQuietly();
            applyBackground();
        }
        // One-time move of the quick-play loader to Fabric (see LauncherConfig.loaderDefaultRevision).
        if (config.getLoaderDefaultRevision() < 1) {
            if ("Vanilla".equals(config.getLastSelectedLoader())) config.setLastSelectedLoader("Fabric");
            config.setLoaderDefaultRevision(1);
            saveConfigQuietly();
        }
        // The main menu is meant to be a fixed, non-paging screen — swallow scroll input on its
        // outer pane so it can't be nudged up/down. The build strip is the one exception: as soon
        // as a few builds are starred it is wider than the window, carries no scrollbar on purpose
        // (hbarPolicy=NEVER) and is meant to be wheeled left/right — so events over it are left
        // alone here and handled by installStripWheelScroll() instead. Consuming them here would
        // make the wheel do nothing at all over the strip, which is exactly what it used to do.
        screenHome.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (isInsideNode(event.getTarget(), homeBuildScroll)) return;
            event.consume();
        });
        installStripWheelScroll(homeBuildScroll);
        // Полосу двигает анимация (см. animateStripScroll): hvalue идёт за stripScrollValue, а не
        // выставляется напрямую — иначе плавной докрутки не получилось бы. Слушатель, а не bind:
        // bind запретил бы ScrollPane менять hvalue самому (перетаскивание полосы, изменение
        // размера окна), и первое же такое изменение упало бы с «bound value cannot be set».
        stripScrollValue.addListener((obs, oldValue, newValue) ->
                homeBuildScroll.setHvalue(newValue.doubleValue()));

        homeEngineCombo.setItems(FXCollections.observableArrayList(ENGINE_JAVA, ENGINE_RUST));
        boolean rustPossible = RustcraftService.platformSupported();
        homeEngineRow.setVisible(rustPossible);
        homeEngineRow.setManaged(rustPossible);
        // Ряд стоит внутри карточки «Без модов»: щелчок по списку не должен считаться щелчком по карточке
        // (та перерисовывает полосу и переносила бы список, пока он открыт).
        homeEngineRow.addEventHandler(javafx.scene.input.MouseEvent.MOUSE_CLICKED, javafx.event.Event::consume);
        restoringVanillaCombos = true;
        try {
            homeEngineCombo.getSelectionModel().select(
                    rustPossible && ENGINE_RUST.equals(config.getLastSelectedEngine()) ? ENGINE_RUST : ENGINE_JAVA);
        } finally {
            restoringVanillaCombos = false;
        }
        applyEngineToLoaderCombo();
        homeVanillaVersionCombo.setItems(FXCollections.observableArrayList(homeVersionItems()));
        versionCombosToRefresh.add(homeVanillaVersionCombo);
        homeVanillaLoaderCombo.setItems(FXCollections.observableArrayList(
                "Vanilla", "Forge", "Fabric", "NeoForge", "Quilt"));
        fitComboToItemCount(homeVanillaLoaderCombo);
        homeVanillaLoaderCombo.showingProperty().addListener((obs, wasShowing, isShowing) -> {
            if (!isShowing && pendingHomeLoaderOptions != null) {
                List<String> options = pendingHomeLoaderOptions;
                pendingHomeLoaderOptions = null;
                applyHomeLoaderOptions(options);
            }
        });
    }

    /** True when {@code target} is {@code node} itself or one of its descendants. */
    private static boolean isInsideNode(Object target, Node node) {
        if (!(target instanceof Node start) || node == null) return false;
        for (Node current = start; current != null; current = current.getParent()) {
            if (current == node) return true;
        }
        return false;
    }

    /** Пиксели прокрутки на единицу дельты колеса (одно деление — delta ≈ 40, то есть ~80 px). */
    private static final double STRIP_WHEEL_PIXELS_PER_UNIT = 2.0;
    /** Длительность плавной докрутки полосы после деления колеса. */
    private static final double STRIP_SCROLL_MS = 200;

    /**
     * Колесо мыши листает полосу сборок влево-вправо — плавно, а не рывком на фиксированный шаг.
     *
     * <p>Ползунка у полосы нет намеренно (hbarPolicy=NEVER), а сборок с сердечком может оказаться
     * больше, чем помещается в окно — раньше до них можно было добраться только перетаскиванием
     * (pannable). Событие гасится здесь же: без этого оно уходило бы во внешний ScrollPane
     * главного экрана и экран «подрагивал» бы по вертикали.
     *
     * <p>Шаг — фиксированные пиксели на одно деление колеса, а не доля ширины окна: hvalue
     * считается из реальной ширины содержимого, поэтому скорость прокрутки одна и та же и в
     * широком, и в узком окне. Колесо «вниз» листает вправо (к следующим сборкам), «вверх» —
     * влево, как в обычном вертикальном списке. Несколько быстрых делений подряд не сбрасывают
     * анимацию, а складываются в её цель, поэтому прокрутка не «заикается».
     */
    private void installStripWheelScroll(ScrollPane strip) {
        strip.addEventFilter(ScrollEvent.SCROLL, event -> {
            event.consume();
            Node content = strip.getContent();
            if (content == null) return;
            double viewportWidth = strip.getViewportBounds().getWidth();
            double scrollable = content.getBoundsInLocal().getWidth() - viewportWidth;
            if (scrollable <= 0 || viewportWidth <= 0) return;   // всё помещается — листать нечего
            // Обычное колесо даёт deltaY, горизонтальное (или shift+колесо) — deltaX.
            double delta = Math.abs(event.getDeltaX()) > Math.abs(event.getDeltaY())
                    ? event.getDeltaX() : event.getDeltaY();
            if (delta == 0) return;

            // Откуда считать: если докрутка ещё идёт, следующее деление добавляется к её цели,
            // иначе — к тому, что реально видно сейчас (например, после перетаскивания мышью).
            boolean animating = stripScrollAnimation != null
                    && stripScrollAnimation.getStatus() == javafx.animation.Animation.Status.RUNNING;
            double fromPx = (animating ? stripScrollValue.get() : strip.getHvalue()) * scrollable;
            double toPx = Math.max(0, Math.min(scrollable, fromPx + delta * STRIP_WHEEL_PIXELS_PER_UNIT));
            animateStripScroll(strip, toPx / scrollable);
        });
    }

    private void animateStripScroll(ScrollPane strip, double targetHvalue) {
        if (stripScrollAnimation != null) stripScrollAnimation.stop();
        stripScrollValue.set(strip.getHvalue());
        stripScrollAnimation = new javafx.animation.Timeline(
                new javafx.animation.KeyFrame(javafx.util.Duration.millis(STRIP_SCROLL_MS),
                        new javafx.animation.KeyValue(stripScrollValue, targetHvalue,
                                javafx.animation.Interpolator.EASE_BOTH)));
        stripScrollAnimation.play();
    }

    private void initBuildsScreen() {
        buildsSortCombo.setItems(FXCollections.observableArrayList("Недавние", "По алфавиту", "Больше всего времени", "Меньше всего времени"));
        buildsSortCombo.getSelectionModel().selectFirst();
        buildsLoaderFilterCombo.setItems(FXCollections.observableArrayList("Все загрузчики", "Forge", "Fabric", "Vanilla", "NeoForge", "Quilt"));
        fitComboToItemCount(buildsLoaderFilterCombo);
        buildsLoaderFilterCombo.getSelectionModel().selectFirst();
        initServerMonitor();
    }

    /** Loads the persisted public-server monitor list and renders it (init time — no network yet). */
    private void initServerMonitor() {
        likedMonitoredServers.clear();
        try {
            if (Files.exists(monitorFile)) {
                String json = Files.readString(monitorFile).trim();
                if (json.startsWith("[")) {
                    // Old format: a bare JSON array of address strings.
                    com.google.gson.reflect.TypeToken<List<String>> token =
                            new com.google.gson.reflect.TypeToken<List<String>>() {};
                    List<String> loaded = new com.google.gson.Gson().fromJson(json, token.getType());
                    if (loaded != null) {
                        monitoredServers.clear();
                        monitoredServers.addAll(loaded);
                    }
                } else {
                    com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                    if (obj.has("servers")) {
                        monitoredServers.clear();
                        for (var el : obj.getAsJsonArray("servers")) monitoredServers.add(el.getAsString());
                    }
                    if (obj.has("liked")) {
                        likedMonitoredServers.clear();
                        for (var el : obj.getAsJsonArray("liked")) likedMonitoredServers.add(el.getAsString());
                    }
                    if (obj.has("builds")) {
                        serverBuildChoice.clear();
                        for (var e : obj.getAsJsonObject("builds").entrySet()) serverBuildChoice.put(e.getKey(), e.getValue().getAsString());
                    }
                }
            }
        } catch (Exception ignored) {
            // corrupt/missing file — start with an empty list
        }
        renderMonitorList();
    }

    private void saveMonitorServers() {
        try {
            Files.createDirectories(monitorFile.getParent());
            com.google.gson.JsonObject obj = new com.google.gson.JsonObject();
            com.google.gson.JsonArray serversArr = new com.google.gson.JsonArray();
            for (String s : monitoredServers) serversArr.add(s);
            obj.add("servers", serversArr);
            com.google.gson.JsonArray likedArr = new com.google.gson.JsonArray();
            for (String s : likedMonitoredServers) likedArr.add(s);
            obj.add("liked", likedArr);
            com.google.gson.JsonObject buildsObj = new com.google.gson.JsonObject();
            for (var e : serverBuildChoice.entrySet()) buildsObj.addProperty(e.getKey(), e.getValue());
            obj.add("builds", buildsObj);
            Files.writeString(monitorFile, new com.google.gson.GsonBuilder().setPrettyPrinting()
                    .create().toJson(obj));
        } catch (IOException ignored) {
        }
    }

    @FXML
    private void onAddMonitoredServer() {
        String address = monitorAddressField.getText();
        if (address == null || address.isBlank()) {
            // Toggle the add-input row open on first click; add on second (or Enter).
            boolean wasHidden = !monitorAddRow.isVisible();
            monitorAddRow.setVisible(wasHidden);
            monitorAddRow.setManaged(wasHidden);
            if (wasHidden) monitorAddressField.requestFocus();
            return;
        }
        String[] parts = ServerMonitorService.parseAddress(address.trim());
        if (parts == null) return;
        String normalized = parts[0] + ":" + parts[1];
        if (monitoredServers.contains(normalized)) {
            showToast("Сервер уже в мониторинге", "info");
            return;
        }
        monitoredServers.add(normalized);
        saveMonitorServers();
        monitorAddressField.clear();
        monitorAddRow.setVisible(false);
        monitorAddRow.setManaged(false);
        renderMonitorList();
        showToast("Сервер добавлен в мониторинг", "success");
    }

    /** Renders the in-memory monitored server list (addresses shown immediately, statuses pinged async). */
    private void renderMonitorList() {
        if (monitorList == null) return;
        monitorList.getChildren().clear();
        monitorEmptyLabel.setVisible(monitoredServers.isEmpty());
        monitorEmptyLabel.setManaged(monitoredServers.isEmpty());
        for (String address : monitoredServers) {
            monitorList.getChildren().add(buildMonitorRow(address));
        }
        refreshMonitoredServers();
    }

    private Node buildMonitorRow(String address) {
        HBox row = new HBox(10);
        row.getStyleClass().add("server-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new javafx.geometry.Insets(10, 12, 10, 12));

        VBox textBox = new VBox(4);
        textBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        HBox head = new HBox(8);
        head.setAlignment(Pos.CENTER_LEFT);
        Label nameLabel = new Label(address);
        nameLabel.getStyleClass().add("server-name");
        head.getChildren().add(nameLabel);
        Label pingLabel = new Label("—");
        pingLabel.getStyleClass().add("mono");
        head.getChildren().add(pingLabel);
        HBox.setHgrow(head, Priority.ALWAYS);

        Label statusLabel = new Label(tr("server_monitor_offline"));
        statusLabel.getStyleClass().add("toggle-sub");
        statusLabel.setWrapText(true);

        textBox.getChildren().addAll(head, statusLabel);
        row.getChildren().add(textBox);

        // Like/heart toggle — liked monitored servers also appear as cards on Home, like builds.
        Button likeBtn = new Button();
        likeBtn.getStyleClass().add("build-like-btn");
        FontIcon heart = new FontIcon("fth-heart");
        heart.getStyleClass().add(likedMonitoredServers.contains(address)
                ? "build-like-icon-active" : "build-like-icon");
        likeBtn.setGraphic(heart);
        likeBtn.setOnMouseClicked(e -> {
            e.consume();
            if (!likedMonitoredServers.add(address)) {
                likedMonitoredServers.remove(address);
                if (address.equals(selectedServer)) {   // плитка ушла с главной — и режим сервера тоже
                    selectedServer = null;
                    renderCurrentBuildPanel();
                }
            }
            saveMonitorServers();
            renderMonitorList();
            renderHomeBuildGrid();
        });
        row.getChildren().add(likeBtn);

        // Re-test this server immediately (same ping path as the background refresh).
        Button refreshBtn = new Button();
        refreshBtn.getStyleClass().add("build-like-btn");
        FontIcon refreshIcon = new FontIcon("fth-refresh-cw");
        refreshIcon.getStyleClass().add("build-like-icon");
        refreshBtn.setGraphic(refreshIcon);
        refreshBtn.setTooltip(new Tooltip("Перезапустить проверку"));
        refreshBtn.setOnAction(e -> {
            pingLabel.setText("—");
            statusLabel.setText("Проверка…");
            setOnline(statusLabel, false);
            executor.submit(() -> {
                ServerMonitorService.ServerStatus status = serverMonitorService.ping(address);
                Platform.runLater(() -> updateMonitorRow(address, status));
            });
        });
        row.getChildren().add(refreshBtn);

        Button removeBtn = new Button();
        removeBtn.getStyleClass().add("danger-button");
        removeBtn.setGraphic(new FontIcon("fth-x"));   // no ✕ character: see installed-check note
        removeBtn.setOnAction(e -> {
            monitoredServers.remove(address);
            likedMonitoredServers.remove(address);
            serverBuildChoice.remove(address);
            if (address.equals(selectedServer)) {
                selectedServer = null;
                renderCurrentBuildPanel();
            }
            saveMonitorServers();
            renderMonitorList();
            renderHomeBuildGrid();
        });
        row.getChildren().add(removeBtn);

        // ServerStatus is attached to the row's userData by refreshMonitoredServers (async), so
        // here we just need to keep the reference.
        row.setUserData(address);
        return row;
    }

    /**
     * Builds a Home-grid card for a liked monitored server — visually the same square "build card"
     * as builds, so liked public servers show up on Home just like liked build packs. The status
     * line is filled in asynchronously by refreshHomeMonitorCards() after ping.
     */
    private Node buildMonitorCard(String address) {
        VBox card = new VBox(4);
        card.getStyleClass().add("build-card");
        card.setPrefWidth(180);

        StackPane icon = new StackPane();
        icon.getStyleClass().add("build-icon-sm");
        // "SR" for server, or derive from the host's first letters ("mc" -> "MC").
        String hostPart = address;
        int colon = address.lastIndexOf(':');
        if (colon > 0) hostPart = address.substring(0, colon);
        String shortName = hostPart.length() >= 2 ? hostPart.substring(0, 2).toUpperCase() : hostPart.toUpperCase();
        Label iconLabel = new Label(shortName);
        iconLabel.getStyleClass().add("build-icon-label");
        icon.getChildren().add(iconLabel);

        Label title = new Label(address);
        title.getStyleClass().add("build-title");
        HBox.setHgrow(title, Priority.ALWAYS);

        HBox titleRow = new HBox(4, title);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        titleRow.setMaxWidth(Double.MAX_VALUE);

        // Lightweight status line — filled by refreshHomeMonitorCards().
        Label status = new Label(tr("server_monitor_ping") + "…");
        status.getStyleClass().addAll("build-meta", "mono");
        HBox statusRow = new HBox(4, status);
        statusRow.setAlignment(Pos.CENTER_LEFT);
        statusRow.setMaxWidth(Double.MAX_VALUE);

        card.getChildren().addAll(icon, titleRow, statusRow);
        card.setUserData(address);
        if (address.equals(selectedServer)) card.getStyleClass().add("selected");
        card.setOnMouseClicked(e -> selectServer(address));
        return card;
    }

    // ============================================================
    //  Режим сервера на главной (11.10.2026)
    //  Плитка сервера из мониторинга → в «Текущей сборке» вместо версии выбор сборки, с которой
    //  на него заходить, а «Играть» запускает её и сразу подключается (GameLaunchService
    //  .setNextLaunchServer). Выбор сборки запоминается для каждого сервера.
    // ============================================================

    /** Выбрать сервер: сборка — та, что выбиралась для него раньше, иначе подходящая по версии. */
    private void selectServer(String address) {
        selectedServer = address;
        serverVersionAppliedFor = null;
        String choice = serverBuildChoice.get(address);
        Profile pick = null;
        boolean known = false;
        if (VANILLA_LABEL.equals(choice)) {
            known = true;
        } else if (choice != null) {
            pick = findBuildByName(choice);
            known = pick != null;
        }
        if (!known) {
            String serverVer = serverVersionId(serverStatusCache.get(address));
            if (serverVer != null) {
                pick = builds.stream().filter(b -> !b.isVanillaEntry() && serverVer.equals(b.getVersionId()))
                        .min(Comparator.comparingLong(Profile::getDaysSinceLastLaunch)).orElse(null);
            }
            // Точной нет — любая сборка, которую сервер примет (Hypixel пускает 1.8–1.21).
            String[] range = serverVersionRange(serverStatusCache.get(address));
            if (pick == null && range != null) {
                pick = builds.stream().filter(b -> !b.isVanillaEntry() && b.getVersionId() != null
                                && versionInRange(b.getVersionId(), range[0], range[1]))
                        .min(Comparator.comparingLong(Profile::getDaysSinceLastLaunch)).orElse(null);
            }
        }
        selectedBuild = pick;
        renderHomeBuildGrid();
        renderCurrentBuildPanel();
        goToView("home");
    }

    private Profile findBuildByName(String name) {
        for (Profile b : builds) if (!b.isVanillaEntry() && b.getName().equals(name)) return b;
        return null;
    }

    /** Панель «Текущая сборка» в режиме сервера. */
    private void renderServerPanel() {
        ServerMonitorService.ServerStatus st = serverStatusCache.get(selectedServer);
        selectedName.setText(selectedServer);
        if (st == null) {
            selectedSub.setText("Проверяем сервер…");
        } else if (st.online) {
            selectedSub.setText("Онлайн " + st.onlinePlayers + "/" + st.maxPlayers + " · " + st.pingMs + " мс"
                    + (st.version != null && !st.version.isBlank() ? " · " + st.version : ""));
        } else {
            selectedSub.setText("Сервер сейчас не отвечает");
        }
        String host = selectedServer.contains(":") ? selectedServer.substring(0, selectedServer.lastIndexOf(':')) : selectedServer;
        selectedIconLabel.setText(host.length() >= 2 ? host.substring(0, 2).toUpperCase() : host.toUpperCase());

        lockedMetaRow.setVisible(false);
        lockedMetaRow.setManaged(false);
        serverMetaRow.setVisible(true);
        serverMetaRow.setManaged(true);
        homeServerHint.setVisible(true);
        homeServerHint.setManaged(true);

        boolean vanilla = selectedBuild == null || selectedBuild.isVanillaEntry();
        fillingServerCombo = true;
        try {
            List<String> names = new ArrayList<>();
            names.add(VANILLA_LABEL);
            builds.stream().filter(b -> !b.isVanillaEntry())
                    .sorted(Comparator.comparingLong(Profile::getDaysSinceLastLaunch))
                    .forEach(b -> names.add(b.getName()));
            homeServerBuildCombo.setItems(FXCollections.observableArrayList(names));
            homeServerBuildCombo.getSelectionModel().select(vanilla ? VANILLA_LABEL : selectedBuild.getName());
        } finally {
            fillingServerCombo = false;
        }

        // «Без модов и сборок» — версию выбирают ниже; подставляем версию сервера, один раз на выбор.
        editableMetaRow.setVisible(vanilla);
        editableMetaRow.setManaged(vanilla);
        String serverVer = serverVersionId(st);
        if (vanilla && serverVer != null && !selectedServer.equals(serverVersionAppliedFor)) {
            serverVersionAppliedFor = selectedServer;
            restoringVanillaCombos = true;
            try {
                homeVanillaVersionCombo.getSelectionModel().select(serverVer);
            } finally {
                restoringVanillaCombos = false;
            }
            refreshHomeLoaderOptionsForVersion(serverVer);
        }

        String version = vanilla ? homeVanillaVersion() : selectedBuild.getVersionId();
        String[] range = serverVersionRange(st);
        if (range != null && version != null && !versionInRange(version, range[0], range[1])) {
            String server = range[0].equals(range[1]) ? range[0] : range[0] + "–" + range[1];
            homeServerHint.setText("Внимание: сервер на " + server + ", а запуск будет на " + version
                    + " — зайти может не получиться.");
            setOnline(homeServerHint, false);
        } else if (st != null && !st.online) {
            homeServerHint.setText("Сервер сейчас не отвечает — игра всё равно попробует подключиться.");
            setOnline(homeServerHint, false);
        } else {
            homeServerHint.setText("«Играть» запустит игру и сразу подключит к серверу.");
            setOnline(homeServerHint, true);
        }
        playHint.setText("Готово · зайдём на " + selectedServer);
        updatePlayButtonTint(version);
    }

    @FXML
    private void onHomeServerBuildSelected() {
        if (fillingServerCombo || selectedServer == null) return;
        String name = homeServerBuildCombo.getSelectionModel().getSelectedItem();
        if (name == null) return;
        serverBuildChoice.put(selectedServer, name);
        saveMonitorServers();
        selectedBuild = VANILLA_LABEL.equals(name) ? null : findBuildByName(name);
        serverVersionAppliedFor = null;   // вернулись к «Без модов» — снова предложить версию сервера
        renderCurrentBuildPanel();
    }

    /**
     * Версия Minecraft из ответа сервера, если такая есть в списке версий: «Paper 1.21.4» → 1.21.4,
     * «Velocity 3.3.0 (1.7.2-1.21.4)» → 1.21.4 (берётся самая правая подходящая). Иначе null.
     */
    private String serverVersionId(ServerMonitorService.ServerStatus st) {
        if (st == null || !st.online || st.version == null) return null;
        java.util.regex.Matcher m = SERVER_VERSION_TOKEN.matcher(st.version);
        List<String> found = new ArrayList<>();
        while (m.find()) found.add(m.group());
        for (int i = found.size() - 1; i >= 0; i--) {
            if (homeVanillaVersionCombo.getItems().contains(found.get(i))) return found.get(i);
        }
        return null;
    }

    /**
     * Какие версии принимает сервер, по его ответу: «1.21.4» → 1.21.4–1.21.4, «Requires MC 1.8 / 1.21»
     * (Hypixel) или «1.7.2-1.21.4» (Velocity) → от меньшей до большей. null — версии в ответе нет.
     */
    private static String[] serverVersionRange(ServerMonitorService.ServerStatus st) {
        if (st == null || !st.online || st.version == null) return null;
        java.util.regex.Matcher m = SERVER_VERSION_TOKEN.matcher(st.version);
        String min = null, max = null;
        while (m.find()) {
            String v = m.group();
            if (min == null || compareVersionParts(parseVersionParts(v), parseVersionParts(min)) < 0) min = v;
            if (max == null || compareVersionParts(parseVersionParts(v), parseVersionParts(max)) > 0) max = v;
        }
        return min == null ? null : new String[] {min, max};
    }

    /**
     * Загрузчик для этой версии Minecraft не выпускался — что сказать игроку, или null.
     * 14.10.2026 новый игрок первым делом создал «Fabric 1.12.2»: окно сборки это позволило, а
     * «Играть» отвечало «не удалось подобрать сборку… проверьте подключение к интернету», хотя
     * дело не в сети — Fabric для 1.12.2 нет вовсе. Снапшоты (25w14a) не проверяем.
     */
    static String loaderUnavailableReason(String loader, String mc) {
        if (loader == null || mc == null) return null;
        int[] v = parseVersionParts(mc);
        if (v.length == 0) return null;
        if ("fabric".equals(loader) && compareVersionParts(v, new int[] {1, 14}) < 0) {
            return "Fabric выходит только для Minecraft 1.14 и новее. Для " + mc + " выберите Forge.";
        }
        if ("neoforge".equals(loader) && compareVersionParts(v, new int[] {1, 20, 1}) < 0) {
            return "NeoForge выходит только для Minecraft 1.20.1 и новее. Для " + mc + " выберите Forge.";
        }
        return null;
    }

    /** Версия в диапазоне сервера. Граница «1.21» покрывает все 1.21.x — сравниваем по её длине. */
    static boolean versionInRange(String version, String min, String max) {
        int[] v = parseVersionParts(version);
        if (v.length == 0) return true;   // снапшот вида 25w14a — не судим
        int[] hi = parseVersionParts(max);
        int[] vCut = java.util.Arrays.copyOf(v, Math.min(v.length, hi.length));
        return compareVersionParts(v, parseVersionParts(min)) >= 0 && compareVersionParts(vCut, hi) <= 0;
    }

    private static final java.util.regex.Pattern SERVER_VERSION_TOKEN = java.util.regex.Pattern.compile("\\d+\\.\\d+(?:\\.\\d+)?");

    /** Pings every liked monitored server and updates the matching Home card's status line. */
    private void refreshHomeMonitorCards() {
        if (likedMonitoredServers.isEmpty()) return;
        List<String> snapshot = new ArrayList<>(likedMonitoredServers);
        executor.submit(() -> {
            for (String address : snapshot) {
                ServerMonitorService.ServerStatus st = serverMonitorService.ping(address);
                Platform.runLater(() -> {
                    serverStatusCache.put(address, st);
                    if (address.equals(selectedServer)) renderCurrentBuildPanel();
                    for (Node node : homeBuildGrid.getChildren()) {
                        if (node.getUserData() == null) continue;
                        if (!address.equals(node.getUserData())) continue;
                        if (!(node instanceof VBox card) || card.getChildren().size() < 3) continue;
                        Node statusNode = card.getChildren().get(2);
                        if (!(statusNode instanceof HBox row) || row.getChildren().isEmpty()) continue;
                        Label statusLabel = (Label) row.getChildren().get(0);
                        if (st.online) {
                            statusLabel.setText(st.onlinePlayers + "/" + st.maxPlayers + " · " + st.pingMs + " мс");
                            setOnline(statusLabel, true);
                        } else {
                            statusLabel.setText(tr("server_monitor_offline"));
                            setOnline(statusLabel, false);
                        }
                    }
                });
            }
        });
    }

    /** Pings every monitored server on a background thread and updates the visible rows on the FX thread. */
    private void refreshMonitoredServers() {
        if (monitoredServers.isEmpty()) return;
        List<String> snapshot = new ArrayList<>(monitoredServers);
        executor.submit(() -> {
            for (String address : snapshot) {
                ServerMonitorService.ServerStatus status = serverMonitorService.ping(address);
                Platform.runLater(() -> updateMonitorRow(address, status));
            }
        });
    }

    private void updateMonitorRow(String address, ServerMonitorService.ServerStatus status) {
        for (Node node : monitorList.getChildren()) {
            if (!(node instanceof HBox row)) continue;
            if (!address.equals(row.getUserData())) continue;
            // Раскладка строки: [textBox, likeBtn, refreshBtn, removeBtn], где
            // textBox = [head, statusLabel]. Прежний комментарий описывал устаревшую раскладку
            // «[textBox, removeBtn]» — buildMonitorRow давно собирает четыре элемента.
            //
            // continue, а НЕ return: раньше строка неожидаемой формы обрывала весь цикл, и у
            // всех последующих отслеживаемых серверов молча перестают обновляться пинг и статус.
            if (row.getChildren().isEmpty()) continue;
            Node first = row.getChildren().get(0);
            if (!(first instanceof VBox textBox)) continue;
            if (textBox.getChildren().size() < 2) continue;
            Node headNode = textBox.getChildren().get(0);
            Node statusNode = textBox.getChildren().get(1);
            if (headNode instanceof HBox head && head.getChildren().size() == 2) {
                Label pingLabel = (Label) head.getChildren().get(1);
                pingLabel.setText(status.online ? (tr("server_monitor_ping") + ": " + status.pingMs + " мс") : "—");
            }
            if (statusNode instanceof Label statusLabel) {
                if (status.online) {
                    StringBuilder sb = new StringBuilder();
                    if (status.motd != null && !status.motd.isEmpty()) sb.append(status.motd);
                    if (status.version != null && !status.version.isEmpty()) {
                        if (sb.length() > 0) sb.append(" · ");
                        sb.append(status.version);
                    }
                    sb.append(" · ").append(tr("server_monitor_players")).append(": ")
                            .append(status.onlinePlayers).append('/').append(status.maxPlayers);
                    statusLabel.setText(sb.toString());
                    setOnline(statusLabel, true);
                } else {
                    statusLabel.setText(tr("server_monitor_offline"));
                    setOnline(statusLabel, false);
                }
            }
        }
    }

    @FXML
    private void onBuildsSortChanged() {
        renderBuildsGrid();
    }

    @FXML
    private void onBuildsFilterChanged() {
        renderBuildsGrid();
    }

    /** Builds a single clickable build-card Node for either grid (Home's short list or Builds' full grid). */
    private Node buildCardNode(Profile profile, boolean selected) {
        return buildCardNode(profile, selected, false);
    }

    /** @param showLikeToggle only the Builds screen's full grid gets the like star — Home's
     *  strip is a read-only display of whichever builds were already liked there. */
    private Node buildCardNode(Profile profile, boolean selected, boolean showLikeToggle) {
        VBox card = new VBox(4);
        card.getStyleClass().add("build-card");
        if (selected) {
            card.getStyleClass().add("selected");
        }
        card.setPrefWidth(180);

        StackPane icon = new StackPane();
        icon.getStyleClass().add("build-icon-sm");
        String iconText = profile.getIconText() != null ? profile.getIconText()
                : profile.getName().length() >= 2 ? profile.getName().substring(0, 2).toUpperCase()
                : profile.getName().toUpperCase();
        Label iconLabel = new Label(iconText);
        iconLabel.getStyleClass().add("build-icon-label");
        icon.getChildren().add(iconLabel);
        String gradient = profile.getIconGradient() != null ? profile.getIconGradient() : "#4b5563,#1f2937";
        String[] colors = gradient.split(",");
        if (colors.length == 2) {
        // Per-build tile colours from the table above: item identity, like an avatar
        // colour, deliberately not a theme token.
            icon.setStyle("-fx-background-color: linear-gradient(to bottom right, " + colors[0].trim() + ", " + colors[1].trim() + ");");
        }

        Label title = new Label(profile.getName());
        title.getStyleClass().add("build-title");
        HBox.setHgrow(title, Priority.ALWAYS);

        HBox titleRow = new HBox(4, title);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        if (showLikeToggle && !profile.isVanillaEntry()) {
            Button likeBtn = new Button();
            likeBtn.getStyleClass().add("build-like-btn");
            FontIcon heart = new FontIcon("fth-heart");
            heart.getStyleClass().add(profile.isLiked() ? "build-like-icon-active" : "build-like-icon");
            likeBtn.setGraphic(heart);
            likeBtn.setOnMouseClicked(ev -> {
                ev.consume();
                profile.setLiked(!profile.isLiked());
                try {
                    profileService.saveProfile(profile);
                } catch (IOException ex) {
                    log("[BUILDS] Не удалось сохранить лайк: " + ex.getMessage());
                }
                renderBuildsGrid();
                renderHomeBuildGrid();
            });
            titleRow.getChildren().add(likeBtn);

            Button editBtn = new Button();
            editBtn.getStyleClass().add("build-like-btn");
            FontIcon pencil = new FontIcon("fth-edit-2");
            pencil.getStyleClass().add("build-like-icon");
            editBtn.setGraphic(pencil);
            editBtn.setOnMouseClicked(ev -> {
                ev.consume();
                onOpenEditBuild(profile);
            });
            titleRow.getChildren().add(editBtn);

            Button folderBtn = new Button();
            folderBtn.getStyleClass().add("build-like-btn");
            FontIcon folder = new FontIcon("fth-folder");
            folder.getStyleClass().add("build-like-icon");
            folderBtn.setGraphic(folder);
            folderBtn.setTooltip(new Tooltip(tr("build_folder_tip")));
            folderBtn.setOnMouseClicked(ev -> {
                ev.consume();
                confirmOpenBuildFolder(profile);
            });
            titleRow.getChildren().add(folderBtn);
        }

        String loaderDisplay = (profile.getModLoader() == null || profile.getModLoader().isBlank() || "vanilla".equalsIgnoreCase(profile.getModLoader()))
                ? "Vanilla" : profile.getModLoader();
        // The vanilla stub Profile() below always defaults versionId to "1.20.4" regardless of
        // what's actually picked in homeVanillaVersionCombo (this card is built fresh from a
        // brand-new Profile() every render, it never reads the combo) - showing that fixed,
        // unrelated number here would just be wrong, not "no build has no version yet". Same
        // wording as renderCurrentBuildPanel()'s selectedSub for this exact state.
        Label meta = new Label(profile.isVanillaEntry() ? "Без загрузки модов" : (profile.getVersionId() + " · " + loaderDisplay));
        meta.getStyleClass().addAll("build-meta", "mono");

        HBox playtime = new HBox(4);
        playtime.setAlignment(Pos.CENTER_RIGHT);
        // Without this, an HBox's max width defaults to its own pref width (it hugs its single
        // child tightly) - the VBox card's fillWidth wouldn't actually stretch this row out to
        // the card's full width, so CENTER_RIGHT above would have nothing to push against.
        playtime.setMaxWidth(Double.MAX_VALUE);
        Label playtimeLabel = new Label(profile.getTotalPlaytimeHours() + " ч");
        playtimeLabel.getStyleClass().add("build-playtime");
        playtime.getChildren().add(playtimeLabel);

        card.getChildren().addAll(icon, titleRow, meta, playtime);
        card.setOnMouseClicked(e -> {
            selectedBuild = profile;
            selectedServer = null;   // сборка или «Без модов» — выход из режима сервера
            renderHomeBuildGrid();
            renderBuildsGrid();
            renderCurrentBuildPanel();
            goToView("home");
            // Щелчок по карточке на экране «Сборки» — сразу и запуск (26.10.2026). Новички
            // e4be5752 и 507f1c5f создавали сборку, щёлкали по ней, попадали на главную, ничего не
            // происходило — возвращались в «Сборки» и через несколько кругов уходили, так и не нажав
            // «Играть». На главной щелчок по-прежнему только выбирает: там выбирают, что запускать.
            if (showLikeToggle && !gameStarting && !modDoctorApplying && !gameLaunchService.isGameRunning()) {
                launchFromCard = true;
                onLaunch();
                launchFromCard = false;
            }
        });
        return card;
    }

    /**
     * Кнопка-папка на карточке сборки (25.10.2026, по просьбе владельца): сначала предупреждение,
     * что моды, положенные или удалённые руками, лаунчер не отслеживает и «Импорт» с ними работать
     * корректно не будет, — и только по «Открыть папку» открывается mods/<сборка>.
     */
    private void confirmOpenBuildFolder(Profile profile) {
        StackPane scrim = new StackPane();
        scrim.getStyleClass().add("modal-scrim");

        VBox card = new VBox(0);
        card.getStyleClass().addAll("modal-card", "modal-compact");
        card.setStyle("-fx-min-height: -1;");   // в теме у .modal-card min-height 300; стиль в коде сильнее таблицы
        card.setMinWidth(380);
        card.setPrefWidth(520);
        card.setMaxWidth(520);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setPickOnBounds(true);

        HBox header = new HBox();
        header.getStyleClass().add("modal-header");
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label(tr("build_folder_title"));
        title.getStyleClass().add("modal-title");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        Button close = new Button();
        close.getStyleClass().add("modal-close");
        close.setGraphic(new FontIcon("fth-x"));
        header.getChildren().addAll(title, headerSpacer, close);

        Label text = new Label(tr("build_folder_text").replace("{name}", profile.getName()));
        text.getStyleClass().add("toggle-sub");
        text.setWrapText(true);
        VBox body = new VBox(10, text);
        body.setPadding(new javafx.geometry.Insets(16));

        Button cancel = new Button(tr("cancel_btn"));
        cancel.getStyleClass().add("copy-btn");
        Button open = new Button(tr("build_folder_open"));
        open.getStyleClass().add("server-action");
        HBox footer = new HBox(10, cancel, open);
        footer.setPadding(new javafx.geometry.Insets(12, 16, 16, 16));
        footer.setAlignment(Pos.CENTER_RIGHT);

        Runnable dismiss = () -> {
            overlayLayer.getChildren().remove(scrim);
            hideOverlay(scrim);
        };
        close.setOnAction(e -> dismiss.run());
        cancel.setOnAction(e -> dismiss.run());
        open.setOnAction(e -> {
            dismiss.run();
            Path dir = gameDir.resolve("mods").resolve(profile.getName());
            executor.submit(() -> {
                try {
                    Files.createDirectories(dir);
                    Os.openFolder(dir);
                } catch (Exception ex) {
                    log("[BUILDS] Не удалось открыть " + dir + ": " + ex.getMessage());
                    Platform.runLater(() -> showToast(tr("build_folder_fail").replace("{path}", dir.toAbsolutePath().toString()), "error"));
                }
            });
        });

        card.getChildren().addAll(header, body, footer);
        scrim.getChildren().add(card);
        overlayLayer.getChildren().add(scrim);
        showOverlay(scrim);
    }

    private Node addBuildCardNode() {
        Button add = new Button("+ Новая сборка");
        add.getStyleClass().add("add-build-card");
        add.setMaxWidth(Double.MAX_VALUE);
        add.setPrefWidth(180);
        add.setOnAction(e -> onOpenCreateBuild());
        return add;
    }

    private void renderHomeBuildGrid() {
        // "No build / Vanilla" quick-pick, matching the concept's noBuildCard — pinned outside
        // the scroll area so it never moves regardless of how many builds are in the strip.
        homeVanillaCardHolder.getChildren().clear();
        Profile vanillaStub = new Profile();
        vanillaStub.setName(VANILLA_LABEL);
        vanillaStub.setVanillaEntry(true);
        Node vanillaCard = buildCardNode(vanillaStub, selectedServer == null && (selectedBuild == null || selectedBuild.isVanillaEntry()));
        // Язык игры — на самой карточке (28.10.2026, просьба владельца): он относится только к ней,
        // а в панели «Текущая сборка» его не было видно, пока карточку не выбрали.
        if (vanillaCard instanceof VBox card && homeEngineRow != null) card.getChildren().add(homeEngineRow);
        homeVanillaCardHolder.getChildren().add(vanillaCard);

        // Only builds starred (liked) from the Builds screen show up in this strip — an
        // unliked build stays reachable via "Все сборки" without cluttering Home.
        homeBuildGrid.getChildren().clear();
        List<Profile> liked = builds.stream()
                .filter(Profile::isLiked)
                .sorted(Comparator.comparingLong(Profile::getDaysSinceLastLaunch))
                .toList();
        for (Profile p : liked) {
            homeBuildGrid.getChildren().add(buildCardNode(p, selectedServer == null && p == selectedBuild));
        }
        // Liked monitored public servers appear as the same square cards right after builds.
        for (String address : likedMonitoredServers) {
            if (monitoredServers.contains(address)) {
                homeBuildGrid.getChildren().add(buildMonitorCard(address));
            }
        }
        refreshHomeMonitorCards();
    }

    private void renderBuildsGrid() {
        allBuildsGrid.getChildren().clear();
        List<Profile> filtered = new ArrayList<>(builds);

        String loaderFilter = buildsLoaderFilterCombo.getSelectionModel().getSelectedItem();
        if (loaderFilter != null && !"Все загрузчики".equals(loaderFilter)) {
            filtered.removeIf(p -> !loaderFilter.equalsIgnoreCase(p.getModLoader()));
        }

        String sort = buildsSortCombo.getSelectionModel().getSelectedItem();
        if ("По алфавиту".equals(sort)) {
            filtered.sort(Comparator.comparing(Profile::getName, String.CASE_INSENSITIVE_ORDER));
        } else if ("Больше всего времени".equals(sort)) {
            filtered.sort((a, b) -> Long.compare(b.getTotalPlaytimeMinutes(), a.getTotalPlaytimeMinutes()));
        } else if ("Меньше всего времени".equals(sort)) {
            filtered.sort(Comparator.comparingLong(Profile::getTotalPlaytimeMinutes));
        } else {
            filtered.sort(Comparator.comparingLong(Profile::getDaysSinceLastLaunch));
        }

        for (Profile p : filtered) {
            // Deliberately not highlighting the applied build here (see Home's
            // renderHomeBuildGrid()/renderCurrentBuildPanel() for that) — this grid is a catalog
            // of all builds, and re-opening it after applying one used to leave that card stuck
            // looking "selected" every time, which read as a stale/broken state.
            allBuildsGrid.getChildren().add(buildCardNode(p, false, true));
        }
        allBuildsGrid.getChildren().add(addBuildCardNode());
        buildsEmptyStateLabel.setVisible(filtered.isEmpty());
        buildsEmptyStateLabel.setManaged(filtered.isEmpty());
    }

    /** Updates Home's "currently selected" summary panel + locked/editable meta rows. */
    private void renderCurrentBuildPanel() {
        if (selectedServer != null) {
            renderServerPanel();
            return;
        }
        serverMetaRow.setVisible(false);
        serverMetaRow.setManaged(false);
        homeServerHint.setVisible(false);
        homeServerHint.setManaged(false);
        boolean vanilla = selectedBuild == null || selectedBuild.isVanillaEntry();
        selectedName.setText(vanilla ? VANILLA_LABEL : selectedBuild.getName());
        selectedSub.setText(vanilla ? "Без загрузки модов" : (selectedBuild.getTotalPlaytimeHours() + " ч наиграно"));

        String iconText = vanilla ? "MC" : (selectedBuild.getIconText() != null ? selectedBuild.getIconText()
                : selectedBuild.getName().substring(0, Math.min(2, selectedBuild.getName().length())).toUpperCase());
        selectedIconLabel.setText(iconText);

        lockedMetaRow.setVisible(!vanilla);
        lockedMetaRow.setManaged(!vanilla);
        editableMetaRow.setVisible(vanilla);
        editableMetaRow.setManaged(vanilla);

        if (!vanilla) {
            selectedVersionChip.setText(selectedBuild.getVersionId());
            String loader = selectedBuild.getModLoader() == null || selectedBuild.getModLoader().isBlank()
                    ? "Vanilla" : selectedBuild.getModLoader();
            selectedLoaderChip.setText(loader);
        } else {
            restoringVanillaCombos = true;
            try {
                if (homeVanillaVersionCombo.getSelectionModel().isEmpty()) {
                    String savedVersion = config.getLastSelectedVersion();
                    if (savedVersion != null && !savedVersion.isBlank() && homeVanillaVersionCombo.getItems().contains(savedVersion)) {
                        homeVanillaVersionCombo.getSelectionModel().select(savedVersion);
                    } else {
                        homeVanillaVersionCombo.getSelectionModel().selectFirst();
                    }
                }
                if (homeVanillaLoaderCombo.getSelectionModel().isEmpty()) {
                    homeVanillaLoaderCombo.getSelectionModel().select(
                            preferredHomeLoader(homeVanillaLoaderCombo.getItems(), null));
                }
            } finally {
                restoringVanillaCombos = false;
            }
        }

        playHint.setText(vanilla ? "Готово к запуску" : "Готово к запуску · " + selectedBuild.getVersionId());
        updatePlayButtonTint(vanilla ? homeVanillaVersion() : selectedBuild.getVersionId());
    }

    /** Minecraft's own loading screen is red starting with 1.16 (1.16 and newer), while older
     *  versions use the white splash — retints the Play button to match so it doesn't flash from
     *  one color to the other the instant the game window replaces it. */
    private static final int[] WHITE_SPLASH_CUTOFF = {1, 15, 2};

    private void updatePlayButtonTint(String versionId) {
        playButton.getStyleClass().remove("play-btn-year");
        if (versionId != null && compareVersionParts(parseVersionParts(versionId), WHITE_SPLASH_CUTOFF) > 0) {
            playButton.getStyleClass().add("play-btn-year");
        }
    }

    private static int[] parseVersionParts(String versionId) {
        String[] tokens = versionId.split("[.\\-]");
        List<Integer> nums = new ArrayList<>();
        for (String token : tokens) {
            try {
                nums.add(Integer.parseInt(token));
            } catch (NumberFormatException e) {
                break;
            }
        }
        int[] parts = new int[nums.size()];
        for (int i = 0; i < parts.length; i++) parts[i] = nums.get(i);
        return parts;
    }

    private static int compareVersionParts(int[] a, int[] b) {
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    @FXML
    private void onHomeVanillaVersionSelected() {
        // Persisted so the vanilla/no-build picker reopens on the same version after a
        // restart instead of resetting to the list's first entry — see renderCurrentBuildPanel().
        // restoringVanillaCombos guards against THIS controller's own programmatic restore/
        // correction selects (which also fire this same onAction) saving over the real value —
        // see its javadoc on applyVersionListToCombos() for the bug this caused.
        String selected = homeVanillaVersionCombo.getSelectionModel().getSelectedItem();
        // У Rust список из одной-двух версий — сохранённый выбор Java им не затираем.
        if (!restoringVanillaCombos && !homeRustSelected() && selected != null
                && !selected.equals(config.getLastSelectedVersion())) {
            config.setLastSelectedVersion(selected);
            try { configService.saveConfig(config); } catch (Exception ignored) { }
        }
        updatePlayButtonTint(homeVanillaVersion());
        refreshHomeLoaderOptionsForVersion(selected);
        if (selectedServer != null && !restoringVanillaCombos) renderCurrentBuildPanel();
    }

    @FXML
    private void onHomeVanillaLoaderSelected() {
        // See onHomeVanillaVersionSelected — same idea.
        String selected = homeVanillaLoaderCombo.getSelectionModel().getSelectedItem();
        if (!restoringVanillaCombos && selected != null && !selected.equals(config.getLastSelectedLoader())) {
            config.setLastSelectedLoader(selected);
            try { configService.saveConfig(config); } catch (Exception ignored) { }
        }
    }

    // ============================================================
    //  Play / Launch — reuses GameLaunchService, ModLoaderService,
    //  VersionDownloadService, JavaDownloadService/JavaVersionDetector
    //  exactly as the previous controller did, adapted to read
    //  version/loader from selectedBuild (or the vanilla combos)
    //  instead of a dedicated versionCombo/modLoaderCombo pair.
    // ============================================================

    @FXML
    private void onLaunch() {
        if (gameStarting) {
            log("[LAUNCH] Запуск уже готовится - одну секунду.");
            return;
        }
        long cooldownLeft = GAME_START_COOLDOWN_MS - (System.currentTimeMillis() - gameLaunchedAt);
        if (gameLaunchedAt > 0 && cooldownLeft > 0 && gameLaunchService.isGameRunning()) {
            long sec = (cooldownLeft + 999) / 1000;
            log("[LAUNCH] Minecraft уже запускается — повторный запуск через " + sec + " с.");
            showToast("Minecraft уже запускается — окно появится через несколько секунд. "
                    + "Ещё одну копию можно будет запустить через " + sec + " с.", "info");
            stat("play_blocked", "cooldown");
            return;
        }
        // Игра уже идёт, минута защиты прошла — переспрашиваем, а не запускаем молча вторую копию.
        // 26.10.2026 новичок 667c33a9 не дождался окна Minecraft 26.2 (оно было за лаунчером или
        // ещё грузилось), жал «Играть» и в итоге держал три копии игры сразу. Нарочно вторую
        // копию запустить можно — ответом «Запустить ещё одну».
        boolean extraCopy = gameLaunchService.isGameRunning();
        if (extraCopy && !confirmExtraGameCopy()) {
            stat("play_blocked", "running");
            return;
        }
        if (modDoctorApplying) {
            showToast("Сейчас идёт починка сборки — подождите несколько секунд", "info");
            stat("play_blocked", "doctor");
            return;
        }
        boolean vanilla = selectedBuild == null || selectedBuild.isVanillaEntry();
        String versionId = vanilla ? homeVanillaVersion() : selectedBuild.getVersionId();
        String modLoader = vanilla ? mapDisplayLoaderToInternal(homeVanillaLoaderCombo.getSelectionModel().getSelectedItem())
                : mapDisplayLoaderToInternal(selectedBuild.getModLoader());
        // Язык игры Rust: версия ставится как обычная, а запускается движком на Rust.
        if (vanilla && homeRustSelected()) {
            modLoader = "rustcraft";
        }
        if (versionId == null || versionId.isBlank()) {
            log("[ERROR] Выберите версию перед запуском.");
            stat("play_blocked", "no_version");
            return;
        }

        gameStarting = true;
        stoppedByUser = false;
        playButton.setDisable(true);
        // Locked for the whole download+launch — otherwise picking a different version while
        // 26.2 is still downloading left playHint/playHint's "Готово к запуску" summary
        // re-reading the (now different) combo selection on the next Home refresh, claiming
        // the wrong version was ready while the original one was still mid-download.
        homeVanillaVersionCombo.setDisable(true);
        homeVanillaLoaderCombo.setDisable(true);
        homeEngineCombo.setDisable(true);
        // Hidden for the duration — otherwise this kept showing its stale "Готово к запуску · X"
        // right next to homeStatusLabel's real "Downloading..."/"Installing..." text, two
        // contradictory statuses on screen at once.
        playHint.setVisible(false);
        playHint.setManaged(false);
        setHomeProgress(-1, "Подготовка...");

        boolean installed = versionDownloadService.isVersionFullyInstalled(versionId);
        launchPhase = installed ? "start" : "dl";
        downloadPercent = installed ? -1 : 0;
        setPlayButtonBusy(installed ? "Запуск…" : "Скачивание · 0%");
        playClicked = true;
        pendingLaunchServer = selectedServer;
        stat("play_click", modLoader + " " + versionId + " dl=" + (installed ? 0 : 1) + (launchFromCard ? " card=1" : "")
                + (extraCopy ? " extra=1" : ""));
        launchFromCard = false;
        if (!installed) {
            downloadAndLaunch(versionId, modLoader);
        } else {
            launchGame(versionId, modLoader);
        }
    }

    /** Maps the Cyrillic/friendly loader labels used in the UI to ModLoaderService's internal keys. */
    private String mapDisplayLoaderToInternal(String display) {
        if (display == null) return "vanilla";
        return switch (display.toLowerCase()) {
            case "forge" -> "forge";
            case "fabric" -> "fabric";
            case "neoforge" -> "neoforge";
            case "quilt" -> "fabric"; // no dedicated Quilt installer yet — closest supported loader
            case "paper" -> "paper";
            case "purpur" -> "purpur";
            case "pufferfish" -> "pufferfish";
            default -> "vanilla";
        };
    }

    /** Every downloadable build of {@code modLoader} available for {@code versionId}, newest
     *  first (all four VersionDownloadService.get*Versions() calls already return that order).
     *  Network failures / an unsupported MC version both surface as an empty list, never an
     *  exception - callers just treat that as "nothing to offer" and handle it themselves. */
    private List<String> fetchLoaderVersions(String versionId, String modLoader) {
        try {
            List<String> versions = switch (modLoader) {
                case "forge" -> versionDownloadService.getForgeVersions(versionId);
                case "fabric" -> versionDownloadService.getFabricVersions(versionId);
                case "neoforge" -> versionDownloadService.getNeoForgeVersions(versionId);
                case "optifine" -> versionDownloadService.getOptiFineVersions(versionId);
                default -> java.util.Collections.<String>emptyList();
            };
            // An empty list is ambiguous (no builds for this MC version vs. the list couldn't be
            // fetched at all) - the launcher used to act on it either way without a word, which is
            // how a loader request quietly turned into a vanilla launch. Say which one it was.
            if (versions.isEmpty() && "neoforge".equals(modLoader)) {
                String reason = versionDownloadService.getLastNeoForgeListError();
                if (reason != null && !reason.isBlank()) {
                    log("[LOADER] Не удалось получить список сборок NeoForge для MC " + versionId
                            + ": " + reason);
                } else {
                    log("[LOADER] Для MC " + versionId + " у NeoForge нет доступных сборок.");
                }
            }
            return versions;
        } catch (Exception e) {
            log("[LOADER] Не удалось получить список версий " + modLoader + " для MC " + versionId + ": " + e.getMessage());
            return java.util.Collections.emptyList();
        }
    }

    /** Picks the newest available build of {@code modLoader} for {@code versionId} - called
     *  whenever a launch/server-start needs a concrete loader version and none was pinned yet
     *  (e.g. the Create Build modal's own picker - see refreshBuildLoaderVersions() - never
     *  resolved one, or this is the vanilla quick-launch combo which has no picker at all). */
    private String resolveLatestLoaderVersion(String versionId, String modLoader) {
        List<String> versions = fetchLoaderVersions(versionId, modLoader);
        return versions.isEmpty() ? null : versions.get(0);
    }

    private void downloadAndLaunch(String versionId, String modLoader) {
        log("[DOWNLOAD] Загрузка Minecraft " + versionId);
        assetsTimerAt = 0;
        setHomeProgress(0, "Скачиваю Minecraft " + versionId + " · 0% · первый раз это 1–4 минуты");

        executor.submit(() -> {
            try {
                String pre = stopPrefetch();
                // Сколько секунд и мегабайт заняли игра и Java (22.10.2026: f3a53d5b ждал 12 минут и
                // закрыл лаунчер на 94 % — медленный интернет или просто большая загрузка, было не понять).
                long t0 = System.currentTimeMillis(), b0 = Http.DOWNLOADED.get();
                versionDownloadService.downloadVersion(versionId, msg -> {
                    Platform.runLater(() -> log("[DOWNLOAD] " + msg));
                    updateDownloadProgress(versionId, msg);
                });
                long t1 = System.currentTimeMillis(), b1 = Http.DOWNLOADED.get();
                String javaPath = selectJavaForVersion(versionId);
                long t2 = System.currentTimeMillis(), b2 = Http.DOWNLOADED.get();
                stat("dl_done", "game=" + (t1 - t0) / 1000 + "s:" + (b1 - b0) / 1_048_576 + "mb"
                        + " java=" + (t2 - t1) / 1000 + "s:" + (b2 - b1) / 1_048_576 + "mb" + pre);
                Platform.runLater(() -> {
                    log("[DOWNLOAD] Загрузка завершена!");
                    if (javaPath != null) {
                        gameLaunchService.overrideJavaPath(javaPath);
                    }
                    launchGame(versionId, modLoader);
                });
            } catch (Exception ex) {
                stat("dl_fail", failDetail(ex));
                String net = networkHint(ex, "dl");
                String hint = net != null ? net : "java".equals(launchPhase) ? JAVA_FAIL_HINT : null;
                Platform.runLater(() -> {
                    log("[ERROR] Загрузка не удалась: " + ex.getMessage());
                    homeStatusLabel.setText("Загрузка не удалась");
                    if (hint != null) {
                        log("[LAUNCH] " + hint);
                        showToast(hint, "error");
                    }
                });
                finishLaunchAttempt();
            }
        });
    }

    private void launchGame(String versionId, String modLoader) {
        log("[LAUNCH] Запуск Minecraft " + versionId);
        homeStatusLabel.setText("Запуск игры...");
        launchPhase = "start";
        downloadPercent = -1;
        setPlayButtonBusy("Запуск…");

        executor.submit(() -> {
            final LocalSkinServer[] skinServerForExit = new LocalSkinServer[1];
            final CustomSkinApiServer[] customSkinServerForExit = new CustomSkinApiServer[1];
            try {
                stopPrefetch();   // загрузчик может докачивать базовую версию — не параллельно с фоновой
                String javaPath = selectJavaForVersion(versionId);
                if (javaPath != null) {
                    log("[LAUNCH] Используется Java: " + javaPath);
                    gameLaunchService.overrideJavaPath(javaPath);
                    modLoaderService.overrideJavaPath(javaPath);
                }

                String modLoaderVersion = null;
                Path rustcraftExe = null;
                String rustcraftJava = null;
                if ("rustcraft".equals(modLoader)) {
                    launchPhase = "loader";
                    setPlayButtonBusy("Установка Rustcraft…");
                    Platform.runLater(() -> homeStatusLabel.setText("Проверяю Rustcraft на GitHub…"));
                    long rcT0 = System.currentTimeMillis(), rcB0 = Http.DOWNLOADED.get();
                    try {
                        rustcraftExe = new RustcraftService(gameDir).ensureInstalled(msg -> Platform.runLater(() -> log(msg)));
                    } catch (Exception rcEx) {
                        stat("rustcraft", "fail exc=" + rcEx.getClass().getSimpleName());
                        throw rcEx;
                    }
                    long rcMb = (Http.DOWNLOADED.get() - rcB0) / 1_048_576;
                    stat("rustcraft", "ok tag=" + rustcraftExe.getParent().getFileName()
                            + " dl=" + (Http.DOWNLOADED.get() > rcB0 ? 1 : 0) + " mb=" + rcMb
                            + " sec=" + (System.currentTimeMillis() - rcT0) / 1000);
                    log("[RUSTCRAFT] Движок: " + rustcraftExe);
                    // Урезанный кэш прошлых версий лаунчера (трава без цвета, нет неба и мобов) —
                    // удалить до проверки dataReady, чтобы движку дали Java пересобрать его.
                    try {
                        String partial = new RustcraftService(gameDir).dropPartialCache(versionId);
                        if (partial != null) {
                            stat("rustcraft_cache", "partial why=" + partial);
                            log("[RUSTCRAFT] Данные игры были собраны не полностью — соберу заново");
                        }
                    } catch (IOException cacheEx) {
                        stat("rustcraft_cache", "drop_fail exc=" + cacheEx.getClass().getSimpleName());
                    }
                    if (!new RustcraftService(gameDir).dataReady(versionId)) {
                        rustcraftJava = rustcraftSourceJava(javaPath);
                        log("[RUSTCRAFT] Первый запуск дольше: Rustcraft один раз готовит данные игры из 26.3 (несколько минут)");
                    }
                } else if (modLoader != null && !"vanilla".equals(modLoader)) {
                    ModLoaderService.Loader loaderEnum = switch (modLoader) {
                        case "forge" -> ModLoaderService.Loader.FORGE;
                        case "fabric" -> ModLoaderService.Loader.FABRIC;
                        case "neoforge" -> ModLoaderService.Loader.NEOFORGE;
                        case "optifine" -> ModLoaderService.Loader.OPTIFINE;
                        default -> ModLoaderService.Loader.VANILLA;
                    };

                    // A saved build's own pinned loader version always wins over re-resolving
                    // "latest" here - otherwise a build pinned to e.g. Forge 47.2.0 would
                    // silently drift to whatever's newest on every single launch. There is
                    // currently no UI to pick a specific version, so a fresh/never-pinned build
                    // just resolves+persists the latest one on its first launch.
                    boolean vanillaLaunch = selectedBuild == null || selectedBuild.isVanillaEntry();
                    modLoaderVersion = !vanillaLaunch ? selectedBuild.getModLoaderVersion() : null;
                    if (modLoaderVersion == null || modLoaderVersion.isBlank()) {
                        log("[LOADER] Определяем последнюю версию " + modLoader + " для MC " + versionId + "...");
                        modLoaderVersion = resolveLatestLoaderVersion(versionId, modLoader);
                        if (modLoaderVersion != null) {
                            String src = switch (modLoader) {
                                case "fabric" -> versionDownloadService.getLastFabricListSource();
                                case "forge" -> versionDownloadService.getLastForgeListSource();
                                case "neoforge" -> versionDownloadService.getLastNeoForgeListSource();
                                default -> null;
                            };
                            if ("cache".equals(src) || "installed".equals(src)) {
                                String err = switch (modLoader) {
                                    case "fabric" -> versionDownloadService.getLastFabricListError();
                                    case "forge" -> versionDownloadService.getLastForgeListError();
                                    default -> versionDownloadService.getLastNeoForgeListError();
                                };
                                log("[LOADER] Список версий " + modLoader + " не скачался (" + err + ") — беру "
                                        + ("installed".equals(src) ? "уже скачанный" : "сохранённый") + " "
                                        + modLoader + " " + modLoaderVersion);
                            }
                        }
                        // Закрепляем найденную версию за сборкой сразу, а не после удачного запуска.
                        // 16.10.2026 (e94f57dd): установка Fabric оборвалась, версия так и не
                        // записалась, и каждое следующее «Играть» снова шло за списком к
                        // недоступному meta.fabricmc.net — «no_build» вместо повторной установки.
                        if (modLoaderVersion != null && !vanillaLaunch) {
                            selectedBuild.setModLoaderVersion(modLoaderVersion);
                            try {
                                profileService.saveProfile(selectedBuild);
                            } catch (IOException ignored) {
                                // не записалось — версия всё равно останется в памяти до выхода
                            }
                        }
                    }

                    if (modLoaderVersion == null) {
                        // A loader was explicitly requested, so launching vanilla instead is not a
                        // safe fallback: the build's mods would silently not load (or the game would
                        // crash on a missing loader) and the player would be left thinking the loader
                        // "didn't download" with nothing on screen explaining why. Abort loudly with
                        // the reason fetchLoaderVersions() just logged.
                        String unavailable = loaderUnavailableReason(modLoader, versionId);
                        if (unavailable != null) throw new IOException(unavailable);
                        String reason = switch (modLoader) {
                            case "neoforge" -> versionDownloadService.getLastNeoForgeListError();
                            case "fabric" -> versionDownloadService.getLastFabricListError();
                            case "forge" -> versionDownloadService.getLastForgeListError();
                            default -> null;
                        };
                        throw new IOException("Не удалось подобрать сборку " + modLoader
                                + " для Minecraft " + versionId
                                + (reason != null && !reason.isBlank()
                                        ? " (" + reason + ")"
                                        : " - для этой версии Minecraft её нет")
                                + ". Проверьте подключение к интернету и попробуйте снова.");
                    }

                    boolean alreadyInstalled = modLoaderService.isLoaderReady(versionId, modLoader, modLoaderVersion);
                    if (!alreadyInstalled) {
                        String loaderVersionForStatus = modLoaderVersion;
                        launchPhase = "loader";
                        setPlayButtonBusy("Установка " + modLoader + "…");
                        Platform.runLater(() -> homeStatusLabel.setText("Установка " + modLoader + " " + loaderVersionForStatus
                                + "… обычно до пары минут, не закрывайте лаунчер"));
                        log("[LOADER] Установка " + modLoader + " " + modLoaderVersion + " для MC " + versionId + "...");
                        String loaderTitle = switch (modLoader) {
                            case "forge" -> "Forge";
                            case "neoforge" -> "NeoForge";
                            case "fabric" -> "Fabric";
                            default -> modLoader;
                        };
                        loaderPercent = -1;
                        modLoaderService.setInstallProgress((f, stage) -> {
                            loaderPercent = f < 0 ? -1 : (int) Math.round(f * 100);
                            setHomeProgress(f < 0 ? -1 : f, "Установка " + loaderTitle + " · "
                                    + (loaderPercent >= 0 ? loaderPercent + "% · " : "") + stage
                                    + " · не закрывайте лаунчер");
                        });
                        try {
                            modLoaderService.installLoader(versionId, loaderEnum, modLoaderVersion,
                                    msg -> Platform.runLater(() -> log(msg)));
                        } finally {
                            modLoaderService.setInstallProgress(null);
                            loaderPercent = -1;
                        }
                        log("[LOADER] " + modLoader + " установлен.");
                    }
                }

                Profile launchProfile = vanillaOrRealProfile(versionId,
                        rustcraftExe != null ? "vanilla" : modLoader, modLoaderVersion);

                // Версия уже стоит — докачать библиотеки, которые движку нужны, а Java-игре нет
                // (natives-windows-arm64). Без них Rustcraft собирает урезанный кэш.
                if (rustcraftExe != null) {
                    try {
                        int got = new RustcraftService(gameDir).ensureEngineLibraries(versionId,
                                msg -> Platform.runLater(() -> log(msg)));
                        if (got > 0) stat("rustcraft_cache", "libs=" + got);
                    } catch (Exception libEx) {
                        // Не роняем запуск: игра пойдёт, но кэш будет урезанным — видно по отметке.
                        stat("rustcraft_cache", "libs_fail exc=" + libEx.getClass().getSimpleName());
                        log("[RUSTCRAFT] Не удалось докачать библиотеки: " + libEx.getMessage());
                    }
                }

                // Прошлый запуск этой версии упал на «не хватает файлов» — сперва сверяем библиотеки
                // по sha1 (обычная проверка смотрит только размер). 27.10.2026 3aec706a.
                String launchKey = modLoader + " " + versionId;
                if (launchKey.equals(repairLibsFor)) {
                    repairLibsFor = null;
                    launchPhase = "verify";
                    setPlayButtonBusy("Проверка файлов…");
                    Platform.runLater(() -> homeStatusLabel.setText("Проверяю файлы игры после вылета…"));
                    int fixed = gameLaunchService.repairLibraries(launchProfile, versionId);
                    stat("lib_repair", launchKey + " fixed=" + fixed);
                    log("[LAUNCH] Проверка файлов после вылета: " + (fixed == 0 ? "всё цело" : "перекачано " + fixed));
                    launchPhase = "start";
                }

                // Куда писать наигранное время — решаем СЕЙЧАС, при запуске, а не при выходе из
                // игры. Прежний код сравнивал launchProfile с selectedBuild уже в колбэке выхода:
                // щёлкнул во время игры карточку другой сборки — и два часа не записывались,
                // setLastLaunchedAt не вызывался, а сборка ещё и оставалась внизу при сортировке
                // «Недавние». null означает ваниль: vanillaOrRealProfile отдаёт для неё
                // одноразовую заглушку, которой нет в списке сборок, и записывать туда нечего
                // (сохранить её значило бы создать на диске фиктивный профиль «Ваниль»).
                final Profile playtimeTarget =
                        (selectedBuild != null && !selectedBuild.isVanillaEntry()) ? selectedBuild : null;

                // Offline mode (Settings) always wins, regardless of a signed-in session — this
                // is the explicit "I have no internet right now, just let me play" override.
                // Without it, a previously-restored Microsoft session (see restoreSavedAccount())
                // would force onlineMode=true even with no network at all, which makes the game
                // itself try to reach Mojang's session servers on world join and fail — even
                // though the version is fully downloaded and would otherwise launch fine offline.
                boolean forceOffline = config.isOfflineMode();
                boolean isLoggedIn = !forceOffline && authService != null && authService.isLoggedIn();
                if (isLoggedIn && authService.getUsername() != null) {
                    launchProfile.setOnlineMode(true);
                    launchProfile.setUsername(authService.getUsername());
                    launchProfile.setUuid(authService.getUuid());
                    log("[LAUNCH] Онлайн-режим: вход выполнен как " + authService.getUsername());
                } else {
                    launchProfile.setOnlineMode(false);
                    launchProfile.setUsername(usernameField.getText());
                    log(forceOffline
                            ? "[LAUNCH] Офлайн-режим включён в настройках — запуск без сети."
                            : "[LAUNCH] Не выполнен вход - запуск в оффлайн-режиме.");
                }

                // Captured so a non-zero exit can be scanned for a mod-loader dependency
                // conflict (see ModConflictAnalyzer) — reset per launch, capped so a runaway
                // log can't grow this unboundedly over a long play session.
                StringBuilder launchOutputBuffer = new StringBuilder();
                long launchStartMillis = System.currentTimeMillis();

                // Serve this profile's local skin/cape over localhost so Minecraft can load them
                // through the standard texture path in offline mode.
                UserProfile activeUserProfile = null;
                String activeName = usernameField.getText();
                for (UserProfile p : userProfiles) {
                    if (activeName != null && activeName.equals(p.getName())) {
                        activeUserProfile = p;
                        break;
                    }
                }
                LocalSkinServer skinServer = null;
                if (activeUserProfile != null) {
                    try {
                        Path skinPath = activeUserProfile.getSkinPath() != null && !activeUserProfile.getSkinPath().isBlank()
                                ? Path.of(activeUserProfile.getSkinPath()) : null;
                        Path capePath = activeUserProfile.getCapePath() != null && !activeUserProfile.getCapePath().isBlank()
                                ? Path.of(activeUserProfile.getCapePath()) : null;

                          // If the profile has no local skin, try to download it by nickname
                          // (Mojang first, Ely.by as fallback) and store it locally. Local files
                          // are far more reliable for CustomSkinLoader than remote texture URLs.
                          if ((skinPath == null || !Files.exists(skinPath)) && activeUserProfile.getName() != null
                                  && !activeUserProfile.getName().isBlank()) {
                              Path downloadedSkin = downloadSkinByNickname(activeUserProfile);
                              if (downloadedSkin != null) {
                                  activeUserProfile.setSkinPath(downloadedSkin.toString());
                                  skinPath = downloadedSkin;
                              }
                          }
                        skinServer = LocalSkinServer.start(skinPath, capePath);

                        // Also serve the same files as a local CustomSkinAPI for CustomSkinLoader.
                        if (skinPath != null || capePath != null) {
                              syncLocalSkinLoaderFiles(activeUserProfile.getName(), skinPath, capePath);
                            CustomSkinApiServer customServer = CustomSkinApiServer.start(
                                    activeUserProfile.getName(), skinPath, capePath);
                            customSkinServerForExit[0] = customServer;
                            updateCustomSkinLoaderConfig(customServer.getRootUrl());
                        }
                    } catch (Exception e) {
                        log("[SKIN] Не удалось запустить локальный сервер скинов: " + e.getMessage());
                    }
                }
                skinServerForExit[0] = skinServer;

                String launchSkinUrl = activeUserProfile != null
                        && activeUserProfile.getSkinUrl() != null
                        && !activeUserProfile.getSkinUrl().isBlank()
                        ? activeUserProfile.getSkinUrl()
                        : (skinServer != null ? skinServer.getSkinUrl() : null);
                String launchCapeUrl = skinServer != null ? skinServer.getCapeUrl() : null;

                if (pendingLaunchServer != null) log("[LAUNCH] Сразу заходим на сервер " + pendingLaunchServer);
                gameLaunchService.setNextLaunchServer(pendingLaunchServer);
                final boolean viaRustcraft = rustcraftExe != null;
                final String rustcraftBackend = viaRustcraft && Os.isWindows() ? config.getRustcraftBackend() : "";
                if (!rustcraftBackend.isEmpty()) log("[RUSTCRAFT] Графика: " + rustcraftBackend + " (после вылета на Vulkan)");
                gameLaunchService.setNextEngine(rustcraftExe, rustcraftJava, rustcraftBackend);
                // Строки запуска графики — отдельно: за долгую игру буфер вывода (300 000 символов)
                // вытесняет начало, а по ним решается, переходить ли на DirectX 12.
                final StringBuilder rcStartup = new StringBuilder();
                final boolean[] rcGpuSent = {false};
                gameLaunchService.launchGame(launchProfile, versionId,
                        output -> {
                            log(output);   // log() сам переносит строки на поток интерфейса пачкой
                            if (viaRustcraft && (output.startsWith("[startup] instance ") || output.startsWith("adapter: "))) {
                                synchronized (rcStartup) {
                                    if (rcStartup.length() < 2000) rcStartup.append(output).append('\n');
                                }
                                if (output.startsWith("adapter: ") && isSoftwareAdapter(output)) {
                                    Platform.runLater(() -> showToast(NO_GPU_DRIVER_TIP, "warning"));
                                }
                                // Видеокарта на каждом запуске, не только при вылете: 28.10.2026 игроки
                                // прислали фото с тёмным небом и рябью, а на RX 6700 XT это не повторилось —
                                // сравнивать было не с чем. Одна отметка на запуск.
                                if (output.startsWith("adapter: ") && !rcGpuSent[0]) {
                                    rcGpuSent[0] = true;
                                    lastRustcraftGpu = rustcraftGpuInfo(output);
                                    stat("rustcraft_gpu", lastRustcraftGpu);
                                }
                                // Движок всё же пошёл запасным путём — значит, кэш снова будет урезанным
                                // (см. RustcraftService.ensureEngineLibraries). Должно быть ноль.
                                if (output.contains("downloading the official server jar")) {
                                    stat("rustcraft_cache", "fallback");
                                }
                            }
                            if (output.startsWith("[LAUNCH]") || output.startsWith("[NATIVES]")) {
                                updateLaunchStage(output);
                            }
                            if (output.startsWith("[MEMORY] ")) {
                                String note = output.substring("[MEMORY] ".length());
                                Platform.runLater(() -> showToast(note, "warning"));
                            }
                            synchronized (launchOutputBuffer) {
                                launchOutputBuffer.append(output).append('\n');
                                if (launchOutputBuffer.length() > MAX_LAUNCH_BUFFER_CHARS) {
                                    launchOutputBuffer.delete(0, launchOutputBuffer.length() - MAX_LAUNCH_BUFFER_CHARS);
                                }
                            }
                        },
                        exitCode -> {
                            gameLaunchedAt = 0; // игра закрылась — запускать снова можно сразу
                            // До сброса stoppedByUser ниже: user=1 отделяет «остановили кнопкой»
                            // от падения. Минуты — чтобы видеть вылеты в первую же минуту.
                            boolean crashed = exitCode != 0 && !stoppedByUser;
                            String capturedLog;
                            synchronized (launchOutputBuffer) {
                                capturedLog = launchOutputBuffer.toString();
                            }
                            String why = crashed ? crashReason(capturedLog, exitCode,
                                    (System.currentTimeMillis() - launchStartMillis) / 60000L) : "";
                            String need = "mods".equals(why) ? missingModIds(capturedLog) : "";
                            String exc = "unknown".equals(why) || "crash_report".equals(why) || "game_crash".equals(why)
                                    ? rootException(capturedLog) : "";
                            String cls = "files".equals(why) ? missingClassPackage(capturedLog) : "";
                            String desc = crashed ? crashDescription(capturedLog) : "";
                            String top = "StackOverflowError".equals(exc) ? stackOverflowPackage(capturedLog) : "";
                            String http = crashed ? failedHttpEndpoint(capturedLog) : "";
                            // Сколько ещё копий игры шло в момент выхода (28.10.2026): у ab54a7b2 и
                            // d9326fdd сбои начались сразу после второй копии, а проверить связь было не по чему.
                            int others = gameLaunchService.runningGameCount();
                            stat("game_exit", "code=" + exitCode
                                    + " min=" + (System.currentTimeMillis() - launchStartMillis) / 60000L
                                    + " user=" + (stoppedByUser ? 1 : 0)
                                    + (why.isEmpty() ? "" : " why=" + why)
                                    + (need.isEmpty() ? "" : " need=" + need)
                                    + (exc.isEmpty() ? "" : " exc=" + exc)
                                    + (cls.isEmpty() ? "" : " cls=" + cls)
                                    + (top.isEmpty() ? "" : " top=" + top)
                                    + (desc.isEmpty() ? "" : " desc=" + desc)
                                    + (http.isEmpty() ? "" : " http=" + http)
                                    + (others > 0 ? " others=" + others : "")
                                    + (!crashed && ModDoctor.loaderRefused(capturedLog) ? " refused=1" : ""));
                            // У вылетов Rustcraft нет Java-исключения (exc= пустой) — 29.10.2026 после
                            // 1.14.41 было 15 вылетов 0xC0000005/0xC0000409/0xCFFFFFFF/«1» у 10 человек, и
                            // понять было не по чему. Отдельной отметкой: графика, видеокарта, этап, хвост.
                            String rcLog = "";
                            if (viaRustcraft) {
                                synchronized (rcStartup) {
                                    rcLog = rcStartup + capturedLog;
                                }
                            }
                            final String rcBe = viaRustcraft ? rustcraftApi(rcLog) : "";
                            if (viaRustcraft && crashed) stat("rustcraft_crash", rustcraftCrashInfo(rcLog));
                            finishLaunchAttempt();
                            if (skinServerForExit[0] != null) {
                                skinServerForExit[0].close();
                            }
                            if (customSkinServerForExit[0] != null) {
                                customSkinServerForExit[0].close();
                            }
                            // Bring the window back if it was hidden for "close launcher on
                            // launch" (Settings) — otherwise the person would have no way to
                            // get back to BLauncher after quitting the game short of killing
                            // and restarting the whole process.
                            if (config.isCloseLauncherOnLaunch()) {
                                Platform.runLater(() -> primaryStage.show());
                            } else if (config.isMinimizeOnLaunch()) {
                                Platform.runLater(() -> primaryStage.setIconified(false));
                            }
                            // Mixin, не нашедший свой класс в первую минуту, — тоже признак битых файлов:
                            // 28.10.2026 у be206519 после обрыва загрузки (SocketTimeout) NeoForge 1.21.1
                            // без модов дважды падал с InjectionError, «Починка» ничего не нашла, а помогла
                            // только полная перекачка. Сверка sha1 стоит секунды, лишний раз не страшно.
                            boolean mixinEarly = crashed && (System.currentTimeMillis() - launchStartMillis) < 60_000L
                                    && exc.matches("InjectionError|MixinApplyError|MixinTransformerError|InvalidInjectionException");
                            // no_main_class — туда же: 29.10.2026 у 31827eab Forge 1.12.2 четыре раза подряд
                            // закрывался через секунду — не докачался launchwrapper, а лаунчер считал Forge
                            // установленным и ничего не чинил. Воспроизведено на чистой папке.
                            if (crashed && ("files".equals(why) || "corrupt".equals(why) || "no_main_class".equals(why))
                                    || mixinEarly) repairLibsFor = launchKey;
                            if (crashed) {
                                log("[LAUNCH] Игра завершилась с кодом " + exitCode);
                                long crashMinutes = (System.currentTimeMillis() - launchStartMillis) / 60000L;
                                boolean rcNoDriver = viaRustcraft && isSoftwareAdapter(rcLog);
                                boolean rcNet = viaRustcraft && crashMinutes < 2 && rustcraftNetworkFail(rcLog);
                                if (!rcNet) rustcraftNetFails = 0;
                                if (rcNoDriver) {
                                    // 29.10.2026 у a7712b5f «adapter: Microsoft Basic Render Driver» — драйвера
                                    // видеокарты нет, Windows рисует процессором. DirectX 12 тут не поможет.
                                    // Сперва просили поставить драйвер — за сутки пятеро, никто не поставил, а
                                    // Rust у них падал через 3–21 мин. Java у двоих из них шла без вылетов
                                    // (f3a73d4c, b514efef), поэтому переводим на Java. Ссылок на сайты драйверов
                                    // не даём — решение владельца: скачает не тот, а виноваты будем мы.
                                    stat("engine_fallback", "exc=no_driver" + (rcBe.isEmpty() ? "" : " be=" + rcBe)
                                            + " min=" + crashMinutes);
                                    Platform.runLater(() -> {
                                        if (homeEngineCombo != null) homeEngineCombo.getSelectionModel().select(ENGINE_JAVA);
                                        onHomeEngineSelected();
                                        showToast("Rust вылетел: на компьютере не установлен драйвер видеокарты, и игру рисует "
                                                + "процессор. Переключил на обычную Java — нажмите «Играть»", "warning");
                                    });
                                } else if (rcNet && ++rustcraftNetFails <= 1) {
                                    // Rustcraft сам качает данные версии с Mojang; 29.10.2026 у 533009a0 связь
                                    // оборвалась («version_data: download: … Peer disconnected»), и его сразу
                                    // перевели на Java. Обрыв сети — не поломка движка: даём ещё попытку.
                                    Platform.runLater(() -> showToast("Rustcraft не докачал данные игры — оборвалась связь. "
                                            + "Нажмите «Играть» ещё раз", "warning"));
                                } else if (viaRustcraft && Os.isWindows() && rustcraftBackend.isEmpty() && "vk".equals(rcBe)
                                        && crashMinutes < 15 && !config.isRustcraftDx12Failed()) {
                                    // Нативные вылеты Rustcraft — почти всегда драйвер Vulkan. Прежде чем
                                    // возвращать Java, один раз пробуем DirectX 12: тот же движок, другой
                                    // путь в драйвер (RUSTCRAFT_BACKEND=dx12, проверено 29.10.2026). Вылеты
                                    // 29.10 были на 0–12 мин; после долгой игры это скорее не графика.
                                    config.setRustcraftBackend("dx12");
                                    try { configService.saveConfig(config); } catch (Exception ignored) { }
                                    stat("engine_backend", "dx12 from=vk min=" + crashMinutes + " code=" + exitCode);
                                    Platform.runLater(() -> showToast("Rustcraft упал на графике Vulkan. Следующий запуск "
                                            + "попробует DirectX 12 — нажмите «Играть» ещё раз", "warning"));
                                } else if (viaRustcraft && crashMinutes < 2) {
                                    // Rustcraft — чужой экспериментальный движок. 28.10.2026 за час четверо
                                    // новичков выбрали «язык игры: Rust», и у всех он падал через 5–60 с
                                    // («InternalError») по многу раз подряд; двое так и ушли. Возвращаем Java
                                    // сами: обычная 26.3 у них запускалась.
                                    stat("engine_fallback", "exc=" + (exc.isEmpty() ? why : exc)
                                            + (rcBe.isEmpty() ? "" : " be=" + rcBe));
                                    // DirectX 12 тоже не пошёл сразу — путь назад: при следующем выборе Rust
                                    // снова Vulkan (на нём движок хотя бы запускался, раз сюда перешли с него).
                                    // Запоминаем отказ: иначе следующий вылет на Vulkan снова переключал на
                                    // DirectX 12 (99217110, GTX 950M, 29.10.2026 — паника на первом кадре).
                                    if (!rustcraftBackend.isEmpty()) {
                                        config.setRustcraftBackend("");
                                        config.setRustcraftDx12Failed(true);
                                        try { configService.saveConfig(config); } catch (Exception ignored) { }
                                    }
                                    Platform.runLater(() -> {
                                        if (homeEngineCombo != null) homeEngineCombo.getSelectionModel().select(ENGINE_JAVA);
                                        onHomeEngineSelected();
                                        showToast("Rustcraft (язык игры Rust) не запустился на этом компьютере — это пробный движок. "
                                                + "Переключил обратно на обычную Java: нажмите «Играть»", "warning");
                                    });
                                } else {
                                    if (!reportModConflictIfAny(capturedLog)) reportCrash(exitCode, why);
                                }
                                offerModDoctor(playtimeTarget, capturedLog, why);
                            } else if (ModDoctor.loaderRefused(capturedLog)) {
                                // Загрузчик показал свой экран ошибки, игрок его закрыл — код 0, но игры не было.
                                log("[LAUNCH] Загрузчик отказался запускать моды сборки");
                                reportModConflictIfAny(capturedLog);
                                offerModDoctor(playtimeTarget, capturedLog, "mods");
                            }
                            // Первая нормальная игра на Rust (без вылета, хотя бы минута) — один раз
                            // спрашиваем, как она выглядела: сломанную картинку по журналу не поймать.
                            if (viaRustcraft && !crashed && System.currentTimeMillis() - launchStartMillis >= 60_000L
                                    && !config.isRustcraftLookAsked() && config.isTelemetryEnabled()) {
                                Platform.runLater(this::askRustcraftLook);
                            }
                            stoppedByUser = false;
                            if (playtimeTarget == null) {
                                return; // ваниль-заглушка — записывать время некуда
                            }
                            long sessionMinutes = (System.currentTimeMillis() - launchStartMillis) / 60000L;
                            playtimeTarget.addPlaytimeMinutes(sessionMinutes);
                            playtimeTarget.setLastLaunchedAt(System.currentTimeMillis());
                            // Игра с этими модами работала: закрылась сама, без отказа загрузчика, или
                            // шла хотя бы 5 минут. Время начала, а не конца: мод, добавленный посреди
                            // игры, в ней не участвовал.
                            if (!crashed && !ModDoctor.loaderRefused(capturedLog) || sessionMinutes >= 5) {
                                playtimeTarget.setLastGoodLaunchAt(launchStartMillis);
                            }
                            try {
                                profileService.saveProfile(playtimeTarget);
                            } catch (IOException ignored) {
                                // Playtime bookkeeping is best-effort — never block on it.
                            }
                        },
                        launchSkinUrl,
                        launchCapeUrl);

                gameStarting = false;
                gameLaunchedAt = System.currentTimeMillis();
                launchPhase = "";
                setPlayButtonBusy(null);
                stat("game_started", modLoader + " " + versionId);
                Platform.runLater(() -> {
                    homeStatusLabel.setText("Игра запущена");
                    log("[LAUNCH] Игра успешно запущена");
                    playButton.setDisable(false);
                    homeVanillaVersionCombo.setDisable(false);
                    homeVanillaLoaderCombo.setDisable(false);
                    homeEngineCombo.setDisable(false);
                    playHint.setVisible(true);
                    playHint.setManaged(true);
                    stopButton.setDisable(false);
                    setHomeProgress(0, "");
                    // "Закрывать лаунчер при запуске игры" (Settings) — this used to only save
                    // the toggle's value without ever actually closing anything on a successful
                    // launch. Hides the window rather than fully exiting the JVM: background
                    // work tied to this controller (the game's own output-reading thread inside
                    // GameLaunchService, VPS agent polling if a serverDetail screen was left
                    // open, etc) needs the process to keep running exactly as before — only the
                    // window itself should disappear.
                    if (config.isCloseLauncherOnLaunch()) {
                        primaryStage.hide();
                    } else if (config.isMinimizeOnLaunch()) {
                        primaryStage.setIconified(true);
                    }
                });
            } catch (Exception ex) {
                if (skinServerForExit[0] != null) {
                    skinServerForExit[0].close();
                }
                if (customSkinServerForExit[0] != null) {
                    customSkinServerForExit[0].close();
                }
                stat("launch_fail", failDetail(ex));
                String msg = ex.getMessage() == null ? "" : ex.getMessage();
                String hint = networkHint(ex, launchPhase);
                if (hint == null && msg.startsWith("Не удалось подобрать сборку")) hint = msg;
                if (hint == null && "java".equals(launchPhase)) hint = JAVA_FAIL_HINT;
                // Нераспознанная ошибка — тоже словами, а не одной строкой «Запуск не удался» под
                // кнопкой: 27.10.2026 ab54a7b2 нажал «Играть» около 60 раз за два часа, не видя ни
                // одного сообщения (InvalidPathException).
                if (hint == null) {
                    String shortMsg = msg.length() > 220 ? msg.substring(0, 220) + "…" : msg;
                    hint = "Не удалось запустить игру" + (shortMsg.isBlank() ? "" : ": " + shortMsg)
                            + ". Подробности — в журнале ниже";
                }
                String toast = hint;
                Platform.runLater(() -> {
                    log("[ERROR] Запуск не удался: " + ex.getMessage());
                    homeStatusLabel.setText("Запуск не удался");
                    if (toast != null) {
                        log("[LAUNCH] " + toast);
                        showToast(toast, "error");
                        if (!gameConsoleBody.isVisible()) onToggleGameConsole();
                    }
                });
                finishLaunchAttempt();
            }
        });
    }

    /** For a real build, returns selectedBuild itself (so playtime/lastLaunchedAt persist onto it).
     *  For the vanilla stub, returns a fresh throwaway Profile that's never saved. */
    private Profile vanillaOrRealProfile(String versionId, String modLoader, String modLoaderVersion) {
        boolean vanilla = selectedBuild == null || selectedBuild.isVanillaEntry();
        if (!vanilla) {
            selectedBuild.setVersionId(versionId);
            selectedBuild.setModLoader(modLoader);
            selectedBuild.setModLoaderVersion(modLoaderVersion);
            return selectedBuild;
        }
        Profile stub = new Profile();
        stub.setName(VANILLA_LABEL);
        stub.setVersionId(versionId);
        stub.setModLoader(modLoader);
        stub.setModLoaderVersion(modLoaderVersion);
        stub.setMinMemoryMb(config.getMinMemoryMb());
        stub.setMaxMemoryMb(config.getMaxMemoryMb());
        return stub;
    }

    private void finishLaunchAttempt() {
        gameStarting = false;
        launchPhase = "";
        downloadPercent = -1;
        setPlayButtonBusy(null);
        Platform.runLater(() -> {
            playButton.setDisable(false);
            homeVanillaVersionCombo.setDisable(false);
            homeVanillaLoaderCombo.setDisable(false);
            homeEngineCombo.setDisable(false);
            playHint.setVisible(true);
            playHint.setManaged(true);
            stopButton.setDisable(!gameLaunchService.isGameRunning());
            setHomeProgress(0, "");
        });
    }

    /**
     * Scans a crashed launch's captured output for a mod-loader dependency conflict
     * (ModConflictAnalyzer — unchanged from the previous controller) and, if found, logs each
     * suggested fix as a readable line. This surfaces the same information the old drawer UI
     * showed, without the one-click auto-apply button that drawer had — the player can still
     * act on it manually (e.g. search + remove/replace the named mod on the Mods screen).
     */
    /** @return true, если нашёлся конфликт модов и человеку уже всё показано. */
    private boolean reportModConflictIfAny(String capturedLog) {
        if (!ModConflictAnalyzer.looksLikeModConflict(capturedLog)) {
            return false;
        }
        List<ModConflictAnalyzer.Suggestion> suggestions = ModConflictAnalyzer.analyze(capturedLog);
        if (suggestions.isEmpty()) {
            return false;
        }
        Platform.runLater(() -> {
            log("[CONFLICT] Обнаружен конфликт модов. Возможные решения:");
            for (ModConflictAnalyzer.Suggestion s : suggestions) {
                String actionLabel = switch (s.action) {
                    case REPLACE -> "Заменить";
                    case INSTALL -> "Установить";
                    case REMOVE -> "Удалить";
                };
                log("[CONFLICT]   " + actionLabel + " \"" + s.modTitle + "\""
                        + (s.versionPrefix != null ? " (версия " + s.versionPrefix + ".x)" : "") + " — " + s.detail);
            }
            showToast("Обнаружен конфликт модов — подробности в «Журнале игры» (Настройки)", "error");
            // the journal is folded by default; this is exactly the moment it is needed
            if (!gameConsoleBody.isVisible()) onToggleGameConsole();
        });
        return true;
    }

    // ============================================================
    //  ПОЧИНКА СБОРКИ (ModDoctor, 18.10.2026)
    // ============================================================

    private ModDoctor modDoctor;

    /** После этих причин вылета виноваты, скорее всего, моды — есть смысл их проверить. */
    private static final java.util.Set<String> MOD_CRASH_REASONS =
            java.util.Set.of("mods", "mods_dup", "files", "crash_report", "game_crash", "unknown", "corrupt", "config");

    private static boolean isModdedLoader(String loader) {
        if (loader == null) return false;
        String l = loader.toLowerCase(java.util.Locale.ROOT);
        return l.equals("fabric") || l.equals("quilt") || l.equals("forge") || l.equals("neoforge");
    }

    /**
     * Вылет модовой сборки: в фоне ищем, что починить, и если нашли — показываем план.
     * Ничего не меняем сами: игрок видит каждое действие и может снять галочку.
     */
    private void offerModDoctor(Profile build, String crashLog, String why) {
        if (build == null || build.isVanillaEntry() || !isModdedLoader(build.getModLoader())) return;
        if (!MOD_CRASH_REASONS.contains(why)) return;
        runModDoctor(build, crashLog, true);
    }

    @FXML
    private void onFixEditingBuild() {
        if (editingBuild == null) return;
        if (!isModdedLoader(editingBuild.getModLoader())) {
            showToast("В этой сборке нет загрузчика модов — проверять нечего", "info");
            return;
        }
        showToast("Проверяю моды «" + editingBuild.getName() + "»…", "info");
        runModDoctor(editingBuild, null, false);
    }

    private void runModDoctor(Profile build, String crashLog, boolean afterCrash) {
        if (modDoctor == null) modDoctor = new ModDoctor(modrinthService, gameDir);
        String loader = build.getModLoader().toLowerCase(java.util.Locale.ROOT);
        executor.submit(() -> {
            List<ModDoctor.Fix> plan;
            try {
                plan = modDoctor.diagnose(build.getName(), build.getVersionId(), loader, crashLog,
                        afterCrash ? build.getLastGoodLaunchAt() : 0, msg -> Platform.runLater(() -> log(msg)));
            } catch (Exception e) {
                Platform.runLater(() -> log("[FIX] Проверка модов не удалась: " + e.getMessage()));
                return;
            }
            stat("mod_doctor", "found=" + plan.size() + " crash=" + (afterCrash ? 1 : 0));
            Platform.runLater(() -> {
                if (plan.isEmpty()) {
                    if (!afterCrash) showToast("Проблем в модах не нашлось", "success");
                    return;
                }
                showModDoctorDialog(build, plan, afterCrash);
            });
        });
    }

    private void showModDoctorDialog(Profile build, List<ModDoctor.Fix> plan, boolean afterCrash) {
        StackPane scrim = new StackPane();
        scrim.getStyleClass().add("modal-scrim");

        VBox card = new VBox(0);
        card.getStyleClass().add("modal-card");
        card.setMinWidth(420);
        card.setPrefWidth(640);
        card.setMaxWidth(640);
        card.setMaxHeight(520);
        card.setPickOnBounds(true);

        HBox header = new HBox();
        header.getStyleClass().add("modal-header");
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("Починка сборки «" + build.getName() + "»");
        title.getStyleClass().add("modal-title");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        Button close = new Button();
        close.getStyleClass().add("modal-close");
        close.setGraphic(new FontIcon("fth-x"));
        header.getChildren().addAll(title, headerSpacer, close);

        VBox body = new VBox(10);
        body.setPadding(new javafx.geometry.Insets(16));
        Label intro = new Label(afterCrash
                ? "Игра вылетела. Лаунчер прочитал журнал и моды сборки — вот что, похоже, мешает. "
                  + "Выключенные моды не удаляются: им добавляется .disabled, вернуть можно в окне сборки."
                : "Что нашлось в модах сборки. Выключенные моды не удаляются: им добавляется .disabled.");
        intro.getStyleClass().add("page-sub");
        intro.setWrapText(true);
        body.getChildren().add(intro);

        for (ModDoctor.Fix fix : plan) {
            String action = switch (fix.kind) {
                case INSTALL -> "Установить";
                case REPLACE -> "Заменить";
                case DISABLE -> "Выключить";
                case ENABLE -> "Включить";
                case RESET_CONFIG -> "Пересоздать настройки";
            };
            Label reason = new Label(fix.reason);
            reason.getStyleClass().add("toggle-sub");
            reason.setWrapText(true);
            VBox row = new VBox(2);
            boolean doable = fix.kind == ModDoctor.Kind.DISABLE || fix.kind == ModDoctor.Kind.ENABLE
                    || fix.kind == ModDoctor.Kind.RESET_CONFIG || fix.versionId != null;
            if (doable) {
                CheckBox box = new CheckBox(action + ": " + fix.title);
                box.setSelected(fix.selected);
                box.selectedProperty().addListener((o, was, now) -> fix.selected = now);
                reason.setPadding(new javafx.geometry.Insets(0, 0, 0, 28));
                row.getChildren().addAll(box, reason);
            } else {
                fix.selected = false;
                Label name = new Label("Вручную: " + fix.title);
                name.getStyleClass().add("server-name");
                row.getChildren().addAll(name, reason);
            }
            body.getChildren().add(row);
        }

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Label progress = new Label("");
        progress.getStyleClass().add("toggle-sub");
        progress.setWrapText(true);
        HBox.setHgrow(progress, Priority.ALWAYS);
        progress.setMaxWidth(Double.MAX_VALUE);
        Button cancel = new Button("Отмена");
        cancel.getStyleClass().add("copy-btn");
        Button apply = new Button(afterCrash ? "Починить и запустить" : "Починить");
        apply.getStyleClass().add("server-action");
        HBox footer = new HBox(10, progress, cancel, apply);
        footer.setPadding(new javafx.geometry.Insets(12, 16, 16, 16));
        footer.setAlignment(Pos.CENTER_RIGHT);

        Runnable dismiss = () -> {
            overlayLayer.getChildren().remove(scrim);
            hideOverlay(scrim);
        };
        // Закрыл «Починку сборки», ничего не починив, — раньше в статистике выглядело так же, как
        // будто окна вообще не было (22.10.2026).
        Runnable skip = () -> {
            stat("mod_doctor_skip", "found=" + plan.size() + " crash=" + (afterCrash ? 1 : 0));
            dismiss.run();
        };
        close.setOnAction(e -> skip.run());
        cancel.setOnAction(e -> skip.run());
        apply.setOnAction(e -> {
            if (plan.stream().noneMatch(f -> f.selected)) {
                dismiss.run();
                return;
            }
            // Моды не трогаем, пока игра запускается или идёт: 19.10.2026 c2e80bea нажал «Играть»
            // через секунду после вылета, ещё до этого окна, и «Починить и запустить» пошла
            // поверх идущего запуска — все 10 исправлений не удались, а игра стартовала со
            // старыми модами и снова упала.
            if (gameStarting || gameLaunchService.isGameRunning()) {
                progress.setText("Игра сейчас запускается или открыта — моды сборки заняты. "
                        + "Закройте игру и нажмите ещё раз.");
                return;
            }
            apply.setDisable(true);
            cancel.setDisable(true);
            body.setDisable(true);
            progress.setText("Чиню…");
            modDoctorApplying = true;
            executor.submit(() -> {
              try {
                ModDoctor.Log toUi = msg -> Platform.runLater(() -> { log(msg); progress.setText(msg); });
                List<String> failed = new ArrayList<>(modDoctor.apply(plan, build.getName(), toUi));
                List<String> failKinds = new ArrayList<>(modDoctor.lastFailKinds());
                // Поставленные моды сами могут требовать библиотеки — один повторный проход,
                // в нём только уверенные установки (Cloth Config тянет Fabric API и т.п.).
                try {
                    // Снятая галочка — решение игрока: второй проход эти моды заново не ставит
                    // (раньше находил их «недостающими» и ставил всё равно; найдено ревью
                    // Android-копии 18.10.2026).
                    java.util.Set<String> declined = new java.util.HashSet<>();
                    for (ModDoctor.Fix f : plan) {
                        if (!f.selected && f.projectId != null) declined.add(f.projectId);
                    }
                    List<ModDoctor.Fix> second = new ArrayList<>();
                    for (ModDoctor.Fix f : modDoctor.diagnose(build.getName(), build.getVersionId(),
                            build.getModLoader().toLowerCase(java.util.Locale.ROOT), null, toUi)) {
                        if (f.kind == ModDoctor.Kind.INSTALL && f.selected && f.versionId != null
                                && !declined.contains(f.projectId)) second.add(f);
                    }
                    failed.addAll(modDoctor.apply(second, build.getName(), toUi));
                    failKinds.addAll(modDoctor.lastFailKinds());
                } catch (Exception ignored) { }
                int done = (int) plan.stream().filter(f -> f.selected).count();
                // Через «-»: event.php оставляет в detail только [A-Za-z0-9_ .:=-].
                String err = failKinds.stream().distinct().limit(3).collect(java.util.stream.Collectors.joining("-"));
                stat("mod_doctor_apply", "done=" + done + " failed=" + failed.size()
                        + (err.isEmpty() ? "" : " err=" + err));
                // Снимаем до onLaunch() ниже — иначе «Починить и запустить» упёрлась бы в свою же блокировку.
                modDoctorApplying = false;
                Platform.runLater(() -> {
                    dismiss.run();
                    if (editingBuild == build) renderEditBuildModsList();
                    if (failed.isEmpty()) {
                        showToast("Сборка «" + build.getName() + "» починена", "success");
                        if (afterCrash && selectedBuild == build && !gameStarting) onLaunch();
                    } else {
                        showToast("Не всё получилось: " + String.join("; ", failed), "warning");
                    }
                });
              } finally {
                modDoctorApplying = false;
              }
            });
        });

        card.getChildren().addAll(header, scroll, footer);
        scrim.getChildren().add(card);
        overlayLayer.getChildren().add(scrim);
        makeModalDraggable(card);
        makeModalResizable(card);
        showOverlay(scrim);
    }

    /**
     * Игра закрылась с ошибкой. До 11.10.2026 об этом знал только свёрнутый журнал, а на главной
     * так и оставалось «Игра запущена» — игрок abb67a0d нажал «Играть» 19 раз за две минуты,
     * не понимая, что происходит. Теперь — понятная причина, если узнали, и раскрытый журнал.
     */
    private void reportCrash(int exitCode, String why) {
        String hint = switch (why) {
            case "no_main_class" -> "Java не нашла файлы игры. Нажмите «Играть» ещё раз — лаунчер проверит "
                    + "и докачает их. Не помогло и в пути к лаунчеру есть необычные символы — "
                    + "перенесите его в папку с латинским именем, например C:\\BLauncher";
            case "heap" -> "Java не смогла выделить память — уменьшите «Память» в настройках";
            case "java_version" -> "Неподходящая версия Java для этой версии игры — "
                    + "уберите свой путь к Java в настройках, лаунчер подберёт сам";
            case "jvm_option" -> "Java не приняла параметры запуска — проверьте «Свои аргументы Java» в настройках и в настройках сборки";
            case "jvm_init" -> "Java не запустилась — возможно, повреждена. Удалите папку java-runtime в папке лаунчера, она скачается заново";
            case "video" -> "Не удалось создать окно игры — обновите драйвер видеокарты";
            case "oom" -> "Игре не хватило памяти — увеличьте «Память» в настройках или уберите часть модов";
            case "mods" -> "Моды несовместимы с этой версией игры или друг с другом — подробности в журнале";
            case "mods_dup" -> "Два мода конфликтуют: один и тот же мод лежит дважды или две библиотеки "
                    + "повторяют друг друга. Уберите дубли и недавно добавленные моды — имена есть в журнале";
            case "native_oom" -> "Компьютеру не хватило оперативной памяти — закройте браузер и другие тяжёлые программы. "
                    + "Если «Память» в настройках больше половины ОЗУ, уменьшите её";
            case "video_driver" -> "Игру уронил драйвер видеокарты — обновите его с сайта AMD, NVIDIA или Intel";
            case "native" -> "Java аварийно завершилась (код " + exitCode + "). Чаще всего виноваты драйвер видеокарты "
                    + "или оверлеи (Discord, MSI Afterburner, запись экрана) — обновите драйвер или отключите оверлеи";
            case "corrupt" -> "Часть файлов игры или мод скачались повреждёнными — удалите недавно добавленный мод "
                    + "или нажмите «Играть» ещё раз, лаунчер перепроверит файлы";
            case "files" -> "Игре не хватает файлов — проверьте моды на совместимость с версией и нажмите «Играть» ещё раз";
            case "config" -> "Испорчен файл настроек мода (.toml) — так бывает после нехватки места на диске "
                    + "или выключения компьютера во время игры. «Починка сборки» уберёт его, мод создаст новый";
            case "access" -> "Нет доступа к файлам игры — возможно, их держит антивирус или вторая копия игры. "
                    + "Закройте лишнее и попробуйте снова";
            case "crash_report" -> "Игра упала с отчётом об ошибке — чаще всего из-за мода. Подробности в журнале игры";
            case "game_crash" -> "Игра остановилась из-за своей ошибки — обычно это мод, который не подходит к версии. "
                    + "На самых новых версиях Minecraft многие моды ещё не готовы: попробуйте «Починку сборки» "
                    + "или версию постарше";
            default -> "Игра закрылась с ошибкой (код " + exitCode + ") — подробности в журнале игры";
        };
        Platform.runLater(() -> {
            homeStatusLabel.setText("Игра закрылась с ошибкой (код " + exitCode + ")");
            log("[LAUNCH] " + hint);
            showToast(hint, "error");
            if (!gameConsoleBody.isVisible()) onToggleGameConsole();
        });
    }

    /**
     * Короткая причина падения по выводу игры — в журнал, подсказку и статистику (detail
     * «why=…»: только слово из этого списка, без путей и текста ошибки). Чистая функция.
     */
    /**
     * id модов, которых не хватило загрузчику, — через двоеточие, не больше трёх («need=fabric-api:cloth-config»;
     * запятую api/event.php вырезает из detail).
     * 12.10.2026 два вылета новичков пришли как просто «why=mods», и понять, чего не хватало, было
     * нельзя. id мода — имя из каталога, о человеке ничего не говорит; путей и текста ошибки не шлём.
     * Fabric: «requires any version of fabric-api, which is missing!»; Forge/NeoForge: «Mod ID: 'x'».
     */
    static String missingModIds(String log) {
        if (log == null) return "";
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = MISSING_MOD_ID.matcher(log);
        while (m.find() && ids.size() < 3) {
            String id = (m.group(1) != null ? m.group(1) : m.group(2)).toLowerCase(java.util.Locale.ROOT);
            if (id.matches("[a-z0-9_\\-]{1,40}") && !id.equals("minecraft") && !id.equals("java")) ids.add(id);
        }
        return String.join(":", ids);
    }

    /**
     * Пакет класса, которого не нашла игра (до трёх частей: «org.spongepowered.asm»), — для вылета
     * «files». 25.10.2026 двое на Forge 1.12.2 вылетали так по 4 раза подряд, «Починка» ничего не
     * исправила, а голый Forge 1.12.2 у нас запускается: виноват какой-то мод, но какой — по
     * отметке не понять. По пакету видно, чьего кода не хватает. Имя пакета — код, не данные человека.
     */
    static String missingClassPackage(String log) {
        if (log == null) return "";
        java.util.regex.Matcher m = MISSING_CLASS.matcher(log);
        String last = "";
        while (m.find()) last = m.group(1);
        if (last.isEmpty()) return "";
        String[] parts = last.replace('/', '.').split("\\.");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < parts.length - 1 && i < 3; i++) {
            if (!parts[i].matches("[a-z][a-z0-9_]{0,30}")) break;
            if (out.length() > 0) out.append('.');
            out.append(parts[i]);
        }
        return out.toString();
    }

    /**
     * Строка «Description:» из отчёта о вылете Minecraft («Exception in server tick loop», «Ticking
     * entity», «Rendering overlay»…) — короткая английская фраза из кода игры, без путей и имён.
     * Пробелы → «_» (отметка делится на поля по пробелам), не длиннее 40 знаков.
     *
     * 25.10.2026: у троих 26.x (даже ванильная) вылетала через 3–18 минут игры с одним лишь
     * «game_crash RuntimeException», «Починка сборки» ничего не находила, и понять, что падает —
     * автосохранение мира, сущность или отрисовка, — по отметке было нельзя.
     */
    static String crashDescription(String log) {
        if (log == null) return "";
        int from = log.lastIndexOf("---- Minecraft Crash Report ----");
        if (from < 0) return "";
        java.util.regex.Matcher m = CRASH_DESCRIPTION.matcher(log.substring(from));
        if (!m.find()) return "";
        // Только сама фраза: всё после скобки, двоеточия, кавычки или слеша отрезаем — там бывают
        // имена файлов и папок (а в пути — имя пользователя Windows).
        String d = m.group(1).split("[(:'\"/\\\\]", 2)[0]
                .replaceAll("[^A-Za-z0-9 ]", "").trim().replaceAll(" +", "_");
        return d.length() > 40 ? d.substring(0, 40) : d;
    }

    private static final java.util.regex.Pattern CRASH_DESCRIPTION = java.util.regex.Pattern.compile("Description: ([^\r\n]+)");

    /**
     * Какой запрос игры к сервисам Mojang не прошёл — «api.minecraftservices.com/player/attributes:401».
     * 28.10.2026 у d9326fdd ванильная 26.2 дважды упала с MinecraftClientHttpException, и по одному
     * имени ошибки не понять, что игра запрашивала. Хост и путь — адрес сервиса, не данные человека;
     * в отметку попадают только буквы, цифры, точки, «/» → «_» и код ответа.
     */
    static String failedHttpEndpoint(String log) {
        if (log == null) return "";
        int at = log.lastIndexOf("MinecraftClientHttpException");
        if (at < 0) return "";
        String around = log.substring(at, Math.min(log.length(), at + 1500));
        java.util.regex.Matcher url = HTTP_URL.matcher(around);
        String where = url.find() ? (url.group(1) + url.group(2)).replace('/', '_') : "";
        java.util.regex.Matcher code = HTTP_CODE.matcher(around);
        String status = code.find() ? (code.group(1) != null ? code.group(1) : code.group(2)) : "";
        String out = where + (status.isEmpty() ? "" : ":" + status);
        out = out.replaceAll("[^A-Za-z0-9_.:\\-]", "");
        return out.length() > 60 ? out.substring(0, 60) : out;
    }

    private static final java.util.regex.Pattern HTTP_URL = java.util.regex.Pattern.compile(
            "https?://([a-z0-9.\\-]+)((?:/[A-Za-z_\\-]+){0,3})");
    private static final java.util.regex.Pattern HTTP_CODE = java.util.regex.Pattern.compile(
            "(?i)(?:status(?: code)?|code|HTTP)[^0-9\\n]{0,6}([1-5][0-9]{2})\\b|\\b(?:returned|due to) ([1-5][0-9]{2})\\b");

    /**
     * Чей код зациклился при StackOverflowError — пакет (до трёх частей) самого частого кадра стека.
     * 23.10.2026 ванильная 1.20.4 у 8ca4049b шесть раз подряд падала так при запуске, а по одному
     * имени ошибки не понять, игра это, Java или чья-то программа-оверлей.
     */
    static String stackOverflowPackage(String log) {
        if (log == null) return "";
        int from = log.lastIndexOf("StackOverflowError");
        if (from < 0) return "";
        java.util.regex.Matcher m = STACK_FRAME.matcher(log.substring(from, Math.min(log.length(), from + 20000)));
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        while (m.find()) {
            String[] parts = m.group(1).split("\\.");
            StringBuilder pkg = new StringBuilder();
            for (int i = 0; i < parts.length - 2 && i < 3; i++) {
                if (!parts[i].matches("[a-z][a-z0-9_]{0,30}")) break;
                if (pkg.length() > 0) pkg.append('.');
                pkg.append(parts[i]);
            }
            if (pkg.length() > 0) counts.merge(pkg.toString(), 1, Integer::sum);
        }
        return counts.entrySet().stream().max(java.util.Map.Entry.comparingByValue())
                .map(java.util.Map.Entry::getKey).orElse("");
    }

    private static final java.util.regex.Pattern STACK_FRAME = java.util.regex.Pattern.compile("\\sat ([A-Za-z0-9_$.]+)\\(");

    private static final java.util.regex.Pattern MISSING_CLASS = java.util.regex.Pattern.compile(
            "(?:NoClassDefFoundError|ClassNotFoundException):? ([A-Za-z0-9_$/.]+)");

    private static final java.util.regex.Pattern MISSING_MOD_ID = java.util.regex.Pattern.compile(
            "requires (?:any )?versions? ?[^\\n]*? of (?:mod )?'?([A-Za-z0-9_\\-]+)'?, which is missing"
                    + "|Mod ID: '([A-Za-z0-9_\\-]+)'");

    /**
     * Простое имя корневого исключения из вывода игры («exc=ResolutionException») — для причин,
     * которые crashReason не распознал. Без пакета и без текста ошибки: только имя класса, как
     * и для launch_fail (политика: «название класса системной ошибки»). 18.10.2026 два вылета
     * пришли голым «why=unknown», и понять по ним нельзя было ничего.
     */
    static String rootException(String log) {
        if (log == null || log.isEmpty()) return "";
        // Ищем от последнего «смертельного» места: раньше в выводе бывают безобидные трассы
        // из WARN-строк, и их «Caused by» выдал бы не ту причину. Нет такого места — хвост вывода.
        int from = Math.max(log.lastIndexOf("Exception in thread"), log.lastIndexOf("---- Minecraft Crash Report ----"));
        if (from < 0) from = Math.max(0, log.length() - 8000);
        log = log.substring(from);
        String last = "";
        java.util.regex.Matcher m = CAUSED_BY.matcher(log);
        while (m.find()) last = m.group(1);
        if (last.isEmpty()) {
            m = THROWN.matcher(log);
            if (m.find()) last = m.group(1);
        }
        return last.matches("[A-Za-z0-9_]{1,60}") ? last : "";
    }

    /** Сколько запусков Rustcraft подряд оборвались на скачивании данных — после второго уже не сеть. */
    private int rustcraftNetFails;

    static final String NO_GPU_DRIVER_TIP = "На компьютере не установлен драйвер видеокарты — игру рисует процессор, "
            + "поэтому Rust может тормозить и вылетать. Если вылетит, лаунчер сам переключит на обычную Java";

    /** «adapter: Microsoft Basic Render Driver (…)» — Windows без драйвера видеокарты рисует процессором. */
    static boolean isSoftwareAdapter(String log) {
        return log != null && java.util.regex.Pattern.compile("(?m)^adapter: (Microsoft Basic Render|llvmpipe|SwiftShader)")
                .matcher(log).find();
    }

    /** Последняя строка самого движка обрывается на скачивании: «version_data: download: … Peer disconnected». */
    static boolean rustcraftNetworkFail(String log) {
        String last = rustcraftLastLine(log).toLowerCase(java.util.Locale.ROOT);
        return last.matches(".*\\b(download|http|https|request)\\b.*")
                && last.matches(".*(disconnect|timed out|timeout|connection|reset|refused|resolve|dns|unexpectedeof|tls|io error).*");
    }

    /**
     * Отметка видеокарты Rustcraft по строке «adapter: AMD Radeon RX 6700 XT (Vulkan); pool: 16 threads»:
     * «be=vk gpu=AMD_Radeon_RX_6700_XT» (+ « sw=1», если Windows рисует без драйвера).
     */
    static String rustcraftGpuInfo(String adapterLine) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^adapter: (.+?) \\((Vulkan|DirectX 12|Dx12|Gl|OpenGL)[^)]*\\)").matcher(adapterLine.trim());
        if (!m.find()) return "gpu=" + rcToken(adapterLine.replaceFirst("^adapter:\\s*", ""), 40);
        String be = m.group(2).startsWith("Vulkan") ? "vk" : m.group(2).startsWith("D") ? "dx12" : "gl";
        return "be=" + be + " gpu=" + rcToken(m.group(1), 60) + (isSoftwareAdapter(adapterLine.trim()) ? " sw=1" : "");
    }

    /** Последняя строка вывода самого Rustcraft (строки лаунчера «[LAUNCH] …» пропускаются). */
    static String rustcraftLastLine(String log) {
        String last = "";
        if (log == null) return last;
        for (String line : log.split("\\R")) {
            String t = line.trim();
            if (t.isEmpty() || t.matches("^\\[[A-Z][A-Z_ -]*\\].*")) continue;
            last = t;
        }
        return last;
    }

    /** Графика, на которой шёл Rustcraft: «vk», «dx12» (по строке «[startup] instance …»); "" — до неё не дошёл. */
    static String rustcraftApi(String log) {
        if (log == null) return "";
        int at = log.lastIndexOf("[startup] instance ");
        if (at < 0) return "";
        String rest = log.substring(at + "[startup] instance ".length());
        if (rest.startsWith("Vulkan")) return "vk";
        if (rest.startsWith("DirectX 12")) return "dx12";
        return rcToken(rest.split(" at ", 2)[0], 8).toLowerCase();
    }

    /**
     * Отметка вылета Rustcraft: «be=vk gpu=AMD_Radeon_RX_6700_XT step=event_loop last=…». step — последний
     * пройденный этап запуска («[startup] … at N ms»), last — последняя строка вывода самого движка
     * (без путей: в них бывает имя пользователя Windows). В 120 символов detail укладывается.
     */
    static String rustcraftCrashInfo(String log) {
        if (log == null) log = "";
        StringBuilder sb = new StringBuilder();
        String be = rustcraftApi(log);
        if (!be.isEmpty()) sb.append("be=").append(be);
        java.util.regex.Matcher m = RC_ADAPTER.matcher(log);
        String gpu = "";
        while (m.find()) gpu = m.group(1);
        if (!gpu.isEmpty()) sb.append(sb.length() > 0 ? " " : "").append("gpu=").append(rcToken(gpu, 26));
        m = RC_STEP.matcher(log);
        String step = "";
        while (m.find()) step = m.group(1);
        if (!step.isEmpty()) sb.append(sb.length() > 0 ? " " : "").append("step=").append(rcToken(step, 18));
        String last = rustcraftLastLine(log);
        if (!last.isEmpty()) {
            // «PATH», а не «<path>»: event.php оставляет в detail только [A-Za-z0-9_ .:=-].
            last = last.replaceAll("[A-Za-z]:[\\\\/]\\S*", "PATH").replaceAll("/(home|Users)/\\S*", "PATH");
            sb.append(sb.length() > 0 ? " " : "").append("last=").append(rcToken(last, 50));
        }
        return sb.length() == 0 ? "no_output" : sb.toString();
    }

    private static String rcToken(String s, int max) {
        String t = s.replaceAll("[^A-Za-z0-9.:_-]+", "_").replaceAll("_+", "_").replaceAll("^_|_$", "");
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static final java.util.regex.Pattern RC_ADAPTER = java.util.regex.Pattern.compile(
            "(?m)^adapter: (.+?) \\((?:Vulkan|DirectX 12|Dx12|Gl|OpenGL)[^)]*\\)");
    private static final java.util.regex.Pattern RC_STEP = java.util.regex.Pattern.compile(
            "(?m)^\\[startup\\] (.+?) at \\d+ ms");

    private static final java.util.regex.Pattern CAUSED_BY = java.util.regex.Pattern.compile(
            "Caused by: (?:[a-z]\\w*\\.)*(?:[A-Z]\\w*\\$)*([A-Z]\\w*(?:Exception|Error))");
    private static final java.util.regex.Pattern THROWN = java.util.regex.Pattern.compile(
            "(?:Exception in thread \"[^\"]*\" |^|\\s)(?:[a-z]\\w*\\.)+(?:[A-Z]\\w*\\$)*([A-Z]\\w*(?:Exception|Error))\\b",
            java.util.regex.Pattern.MULTILINE);

    /**
     * @param exitCode код выхода игры — по нему узнаётся падение самой Java (0xC0000005 и т.п.)
     * @param minutes  сколько минут шла игра: «не хватило памяти» на старте и через час — разное
     */
    static String crashReason(String log, int exitCode, long minutes) {
        if (log == null) log = "";
        // ClassNotFoundException по классам игры — «не нашлась главная» только пока игра ещё не
        // писала свой лог. 18.10.2026: NeoForge 1.21.1 (a149bfff) и Forge 1.20.1 через 9 минут игры
        // (629f3d8b) пришли как no_main_class — это мод под другую версию игры, а игроку
        // посоветовали перенести лаунчер в папку с латинским именем и не предложили починку модов.
        if (log.contains("Could not find or load main class")
                || minutes < 1 && !GAME_LOG_LINE.matcher(log).find()
                        && (log.contains("ClassNotFoundException: net.minecraft")
                                || log.contains("ClassNotFoundException: net.fabricmc")
                                || log.contains("ClassNotFoundException: cpw."))) {
            return "no_main_class";
        }
        if (log.contains("insufficient memory for the Java Runtime") || log.contains("Native memory allocation")) {
            // 15.10.2026 (4aa6d096): через 6 минут игры процесс упал с 0xC0000409, мы записали
            // «heap» и посоветовали уменьшить «Память» — совет для старта. Посреди игры это значит,
            // что кончилась оперативка всего компьютера: Java просила у Windows ещё и не получила.
            return minutes >= 1 ? "native_oom" : "heap";
        }
        if (log.contains("Could not reserve enough space") || log.contains("Invalid maximum heap size")
                || log.contains("Invalid initial heap size") || log.contains("larger value than the maximum heap")) {
            return "heap";
        }
        if (log.contains("UnsupportedClassVersionError") || log.contains("compiled by a more recent version")) return "java_version";
        if (log.contains("Unrecognized VM option") || log.contains("Unrecognized option")) return "jvm_option";
        if (log.contains("OutOfMemoryError")) return "oom";
        // 18.10.2026: двое на Forge 1.20.1 падали с кодом 1 через 3–20 с, а в статистику шло
        // «unknown». Так рано на Forge падает модлаунчер, когда два мода несут один и тот же пакет
        // или мод лежит дважды разными версиями: ResolutionException ещё до окна игры.
        if (log.contains("ResolutionException") || log.contains("LayerInstantiationException")
                || log.contains("DuplicateModsFoundException") || log.contains("Found duplicate mods")
                || log.contains("reads package") && log.contains("from both")) {
            return "mods_dup";
        }
        if (log.contains("Incompatible mods found") || log.contains("Mod resolution failed")
                || log.contains("ModResolutionException") || log.contains("Missing or unsupported mandatory dependencies")) {
            return "mods";
        }
        if (log.contains("GLFW error") || log.contains("Pixel format not accelerated") || log.contains("WGL_ARB")
                || log.contains("Failed to create window") || log.contains("OpenGL") && log.contains("not supported")) {
            return "video";
        }
        if (log.contains("Error occurred during initialization of VM") || log.contains("Could not create the Java Virtual Machine")
                || log.contains("Error occurred during initialization of boot layer")) {
            return "jvm_init";
        }
        if (log.contains("ZipException") || log.contains("invalid LOC header") || log.contains("zip END header not found")
                || log.contains("error in opening zip file") || log.contains("Unexpected end of ZLIB")) {
            return "corrupt";
        }
        if (log.contains("AccessDeniedException") || log.contains("Access is denied") || log.contains("Отказано в доступе")) {
            return "access";
        }
        // 27.10.2026: Forge 1.20.1 у 32177f1e падал через 2 с с «why=unknown exc=ParsingException» —
        // это night-config не прочитал .toml с настройками (пустой после нехватки места на диске).
        if (log.contains("nightconfig") && log.contains("ParsingException") || log.contains("Failed loading config file")
                || log.contains("Failed to load FML config")) {
            return "config";
        }
        // Падение самой Java: hs_err печатает «A fatal error has been detected» и проблемный модуль.
        // Модуль драйвера видеокарты в нём — почти всегда драйвер, а не игра и не моды.
        boolean fatal = log.contains("A fatal error has been detected by the Java Runtime Environment");
        if (fatal && VIDEO_DRIVER_MODULE.matcher(log).find()) return "video_driver";
        if (log.contains("---- Minecraft Crash Report ----")) return "crash_report";
        // 0xCFFFFFFF: так заканчивается процесс, когда игра сама сдаётся после своей ошибки.
        // 19.10.2026 (c2e80bea, Fabric 26.3): шесть вылетов подряд с этим кодом и
        // IllegalStateException, половина пришла как «unknown» — отчёт об ошибке уходил в файл,
        // а не в вывод, и строки «Crash Report» мы не видели.
        if (exitCode == 0xCFFFFFFF) return "game_crash";
        if (log.contains("NoClassDefFoundError") || log.contains("ClassNotFoundException")) return "files";
        if (fatal || isNativeCrashCode(exitCode)) return "native";
        return "unknown";
    }

    /** Строка лога самой игры или загрузчика: «[main/INFO]», «[Render thread/WARN]» и т.п. Пока таких
     *  нет, Java ещё не дошла до кода игры. */
    private static final java.util.regex.Pattern GAME_LOG_LINE = java.util.regex.Pattern.compile(
            "\\[[^\\]\\n]{1,60}/(?:INFO|WARN|ERROR|DEBUG|FATAL)\\]");

    /** Модули драйверов OpenGL: AMD (atio6axx, atioglxx), NVIDIA (nvoglv64), Intel (ig*icd*). */
    private static final java.util.regex.Pattern VIDEO_DRIVER_MODULE = java.util.regex.Pattern.compile(
            "(?i)\\b(atio6axx|atioglxx|amdxc64|nvoglv(32|64)|ig\\d+icd(32|64)|igxelpicd64|igdumdim64)\\.dll");

    /** NTSTATUS-коды аварийного завершения процесса Windows: access violation, stack overflow,
     *  heap corruption, fast-fail (0xC0000409 — им же падает JVM на повреждённой памяти драйвера). */
    static boolean isNativeCrashCode(int code) {
        return code == 0xC0000005 || code == 0xC00000FD || code == 0xC0000374 || code == 0xC0000409
                || code == 0xC000001D;
    }

    @FXML
    private void onStopGame() {
        if (!gameLaunchService.isGameRunning()) {
            finishLaunchAttempt();
            return;
        }
        log("[LAUNCH] Остановка игры...");
        homeStatusLabel.setText("Остановка игры...");
        stoppedByUser = true;
        stopButton.setDisable(true);
        executor.submit(gameLaunchService::stopGame);
    }

    private void setHomeProgress(double progress, String status) {
        Platform.runLater(() -> {
            homeStatusLabel.setText(status);
            if (progress < 0) {
                launchProgressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                launchProgressBar.setVisible(true);
                launchProgressBar.setManaged(true);
            } else if (progress == 0 && (status == null || status.isBlank())) {
                launchProgressBar.setVisible(false);
                launchProgressBar.setManaged(false);
            } else {
                launchProgressBar.setProgress(progress);
                launchProgressBar.setVisible(true);
                launchProgressBar.setManaged(true);
            }
        });
    }

    /**
     * Прогресс первой загрузки одной строкой: «Скачиваю Minecraft 1.20.1 · 45% · звуки и текстуры ·
     * осталось ~1 мин». Раньше на главной был сырой текст этапа («Скачивается: 1.20.1.jar»), а полоса
     * заполнялась только на последнем этапе — первые полминуты она крутилась без цифр, и 13.10.2026
     * один новичок закрыл лаунчер через 17 секунд после «Играть», другой через 52.
     *
     * Доли этапов — по тому, сколько они реально длятся на свежей установке: json и jar — секунды,
     * библиотеки — около десятой части, ресурсы (тысячи мелких файлов) — почти всё остальное.
     */
    private void updateDownloadProgress(String versionId, String raw) {
        String msg = raw == null ? "" : raw.trim();
        int pct = downloadPercent;
        String stage = null;
        String eta = "";
        var m = java.util.regex.Pattern.compile("ресурсы\\s+(\\d+)\\s*/\\s*(\\d+)").matcher(msg);
        var lib = java.util.regex.Pattern.compile("библиотеки\\s+(\\d+)\\s*/\\s*(\\d+)").matcher(msg);
        if (lib.find()) {
            // Библиотеки — от 8 до 20 %, по штукам (см. VersionDownloadService.downloadLibraries).
            int done = Integer.parseInt(lib.group(1));
            int total = Integer.parseInt(lib.group(2));
            pct = 8 + (total > 0 ? (int) (12L * done / total) : 0);
            stage = "библиотеки " + done + " из " + total;
        } else if (m.find()) {
            int done = Integer.parseInt(m.group(1));
            int total = Integer.parseInt(m.group(2));
            if (total > 0) {
                pct = 20 + (int) (75L * done / total);
                long now = System.currentTimeMillis();
                if (assetsTimerAt == 0) {
                    assetsTimerAt = now;
                    assetsTimerDone = done;
                } else if (now - assetsTimerAt > 4000 && done > assetsTimerDone) {
                    double perMs = (double) (done - assetsTimerDone) / (now - assetsTimerAt);
                    long leftSec = (long) ((total - done) / perMs / 1000);
                    eta = leftSec < 60 ? " · меньше минуты" : " · осталось ~" + ((leftSec + 30) / 60) + " мин";
                }
            }
            stage = "звуки и текстуры";
        } else if (msg.endsWith(".json")) {
            pct = Math.max(pct, 1);
            stage = "подготовка";
        } else if (msg.endsWith(".jar")) {
            pct = Math.max(pct, 3);
            stage = "файлы игры";
        } else if (msg.equals("Скачивается: библиотеки")) {
            pct = Math.max(pct, 8);
            stage = "библиотеки";
        } else if (msg.equals("Библиотеки скачаны") || msg.equals("Скачивается: ресурсы")) {
            pct = Math.max(pct, 20);
            stage = "звуки и текстуры";
        } else if (msg.startsWith("Добираю недокачанные ресурсы: ")) {
            // Второй проход по ассетам (25.10.2026): раньше шёл молча, и полоса стояла на 94 %.
            pct = Math.max(pct, 95);
            stage = "добираю недокачанное " + msg.substring("Добираю недокачанные ресурсы: ".length());
        } else if (msg.equals("Ресурсы скачаны")) {
            pct = Math.max(pct, 95);
            stage = "почти готово";
        }
        if (stage == null) {
            updateLaunchStage(msg);   // прочие сообщения (старые версии, предупреждения) — как раньше
            return;
        }
        pct = Math.max(downloadPercent, Math.min(pct, 99));   // назад не откатываем
        downloadPercent = pct;
        setHomeProgress(pct / 100.0, "Скачиваю Minecraft " + versionId + " · " + pct + "% · " + stage + eta);
        setPlayButtonBusy("Скачивание · " + pct + "%");
    }

    /**
     * Надпись на кнопке «Играть», пока идёт запуск; null — вернуть «Играть». Строка статуса под
     * кнопкой мелкая и серая, а на самой кнопке всё это время оставалось «Играть» (только бледное) —
     * со стороны казалось, что нажатие не сработало. 13.10.2026.
     */
    private void setPlayButtonBusy(String text) {
        Platform.runLater(() -> playButton.setText(text != null ? text : tr("play_btn")));
    }

    /** Что сказать и что отправить в статистику про незаконченный запуск: «dl:45», «java», «loader», «start». */
    private String busyTag() {
        String phase = launchPhase;
        if (phase.isEmpty()) return "";
        if ("java".equals(phase) && javaPercent >= 0) return "java:" + javaPercent;
        if ("loader".equals(phase) && loaderPercent >= 0) return "loader:" + loaderPercent;
        return "dl".equals(phase) ? "dl:" + Math.max(0, downloadPercent) : phase;
    }

    /**
     * Окно закрывают, пока игра ещё скачивается или ставится: спрашиваем, а не обрываем молча.
     * true — можно закрывать. «Подождать» — кнопка по умолчанию, и крестик диалога тоже значит
     * «подождать»: случайный Enter или Esc не должны выбрасывать человека из первой загрузки.
     */
    public boolean confirmCloseDuringLaunch() {
        // «start» — секунды до появления окна игры; переспрашивать там только раздражать.
        String phase = launchPhase;
        if (!gameStarting || gameLaunchService.isGameRunning()
                || !("dl".equals(phase) || "java".equals(phase) || "loader".equals(phase))) return true;
        String busy = busyTag();
        String what = switch (phase) {
            case "dl" -> "Игра ещё скачивается" + (downloadPercent > 0 ? " — " + downloadPercent + "%" : "");
            case "java" -> "Скачивается Java для игры";
            default -> "Устанавливается загрузчик модов";
        };
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION);
        ask.initOwner(primaryStage);
        ask.setTitle("BLauncher");
        ask.setHeaderText(what);
        ask.setContentText("Если закрыть лаунчер сейчас, загрузка прервётся. Уже скачанное не пропадёт — "
                + "при следующем «Играть» докачается только остальное.\n\n"
                + "Первый запуск обычно занимает 1–4 минуты, дальше игра стартует сразу.");
        ButtonType wait = new ButtonType("Подождать", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType close = new ButtonType("Всё равно закрыть", ButtonBar.ButtonData.OTHER);
        ask.getButtonTypes().setAll(close, wait);
        Button waitBtn = (Button) ask.getDialogPane().lookupButton(wait);
        Button closeBtn = (Button) ask.getDialogPane().lookupButton(close);
        if (closeBtn != null) closeBtn.setDefaultButton(false);
        if (waitBtn != null) waitBtn.setDefaultButton(true);
        boolean closeIt = ask.showAndWait().orElse(wait) == close;
        if (!closeIt) stat("close_kept", "busy=" + busy);
        return closeIt;
    }

    /** «Minecraft уже запущен — запустить ещё одну копию?» (см. onLaunch). true — запускать. */
    private boolean confirmExtraGameCopy() {
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION);
        ask.initOwner(primaryStage);
        ask.setTitle("BLauncher");
        ask.setHeaderText("Minecraft уже запущен");
        ask.setContentText("Окно игры может быть за лаунчером или свёрнуто — посмотрите на панели задач.\n\n"
                + "Вторая копия игры займёт ещё столько же памяти — запускайте её, только если она правда нужна.");
        ButtonType no = new ButtonType("Не запускать", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType yes = new ButtonType("Запустить ещё одну", ButtonBar.ButtonData.OTHER);
        ask.getButtonTypes().setAll(yes, no);
        Button noBtn = (Button) ask.getDialogPane().lookupButton(no);
        Button yesBtn = (Button) ask.getDialogPane().lookupButton(yes);
        if (yesBtn != null) yesBtn.setDefaultButton(false);
        if (noBtn != null) noBtn.setDefaultButton(true);
        return ask.showAndWait().orElse(no) == yes;
    }

    /** Видеокарта последнего запуска Rustcraft («be=vk gpu=…») — к отзыву о картинке. */
    private volatile String lastRustcraftGpu = "";

    /**
     * «Как выглядела игра на Rust?» — один раз после первой игры без вылета. 28.10.2026 игроки прислали
     * фото с тёмным небом и рябью вместо снега, а на нашей RX 6700 XT это не повторилось: сломанная
     * картинка не роняет игру и в журнал не пишется. Ответ «нормально/проблемы» — отметкой
     * rustcraft_look рядом с rustcraft_gpu; при проблемах описание обязательно, иначе не понять что чинить.
     */
    private void askRustcraftLook() {
        if (config.isRustcraftLookAsked()) return;
        config.setRustcraftLookAsked(true);
        try { configService.saveConfig(config); } catch (Exception ignored) { }
        String gpu = lastRustcraftGpu;
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION);
        ask.initOwner(primaryStage);
        ask.setTitle("BLauncher");
        ask.setHeaderText("Как выглядела игра на Rust?");
        ask.setContentText("Rust — пробный движок, и на некоторых видеокартах он рисует мир неправильно: "
                + "тёмное небо, рябь вместо блоков, не те текстуры. Ответ поможет понять, где он ломается.");
        ButtonType ok = new ButtonType("Всё нормально", ButtonBar.ButtonData.YES);
        ButtonType bad = new ButtonType("Были проблемы с картинкой", ButtonBar.ButtonData.NO);
        ButtonType later = new ButtonType("Не отвечать", ButtonBar.ButtonData.CANCEL_CLOSE);
        ask.getButtonTypes().setAll(ok, bad, later);
        ButtonType answer = ask.showAndWait().orElse(later);
        if (answer == ok) {
            stat("rustcraft_look", "ok " + gpu);
        } else if (answer == bad) {
            String text = askRustcraftProblemText();
            stat("rustcraft_look", (text == null ? "bad_notext " : "bad ") + gpu);
            if (text != null && onlineStatsService != null) {
                onlineStatsService.feedback("rustcraft_look", gpu, text);
                showToast("Спасибо! Описание отправлено — посмотрим, что можно исправить", "info");
            }
        } else {
            stat("rustcraft_look", "skip " + gpu);
        }
    }

    /** Окно «Что было не так?» — «Отправить» включается, только когда что-то написано. null — закрыли. */
    private String askRustcraftProblemText() {
        javafx.scene.control.Dialog<String> d = new javafx.scene.control.Dialog<>();
        d.initOwner(primaryStage);
        d.setTitle("BLauncher");
        d.setHeaderText("Что было не так с картинкой?");
        javafx.scene.control.TextArea area = new javafx.scene.control.TextArea();
        area.setPromptText("Например: небо тёмно-синее без солнца, вблизи снег превращается в белую рябь, "
                + "у травы коричневые бока. Можно указать, где это было и какая была погода.");
        area.setWrapText(true);
        area.setPrefRowCount(5);
        area.setPrefColumnCount(40);
        area.setTextFormatter(new javafx.scene.control.TextFormatter<String>(c ->
                c.getControlNewText().length() <= 500 ? c : null));
        Label hint = new Label("Не пишите ник, почту и другие личные данные. Вместе с текстом уйдёт только "
                + "модель видеокарты и версия лаунчера.");
        hint.setWrapText(true);
        hint.setMaxWidth(420);
        javafx.scene.layout.VBox box = new javafx.scene.layout.VBox(8, area, hint);
        d.getDialogPane().setContent(box);
        ButtonType send = new ButtonType("Отправить", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancel = new ButtonType("Отмена", ButtonBar.ButtonData.CANCEL_CLOSE);
        d.getDialogPane().getButtonTypes().setAll(send, cancel);
        Node sendBtn = d.getDialogPane().lookupButton(send);
        sendBtn.setDisable(true);
        area.textProperty().addListener((o, a, b) -> sendBtn.setDisable(b == null || b.strip().length() < 5));
        d.setResultConverter(bt -> bt == send ? area.getText().strip() : null);
        Platform.runLater(area::requestFocus);
        return d.showAndWait().orElse(null);
    }

    private void updateLaunchStage(String raw) {
        String msg = raw == null ? "" : raw.trim();
        String label = msg.replaceFirst("^\\[[A-Z]+\\]\\s*", "");
        double frac = -1;
        // Scoped to "ресурсы N/M" specifically (VersionDownloadService's asset-progress message)
        // rather than a bare "(\d+)/(\d+)" - this same method also runs over raw game-process
        // output lines tagged [LAUNCH]/[NATIVES] (see launchGame()'s output callback), and a
        // generic digit/digit pattern risks misreading an unrelated number pair there (e.g. a
        // classpath entry count or a version string) as a download-progress fraction.
        var m = java.util.regex.Pattern.compile("ресурсы\\s+(\\d+)\\s*/\\s*(\\d+)").matcher(msg);
        if (m.find()) {
            double tot = Double.parseDouble(m.group(2));
            if (tot > 0) frac = Double.parseDouble(m.group(1)) / tot;
        }
        setHomeProgress(frac, label);
    }

    /**
     * Selects the best Java runtime for a Minecraft version — unchanged logic from the
     * previous controller: already-downloaded Java, exact system match, compatible system
     * match, auto-download, then a fallback LTS chain (25 -> 21 -> 17 -> 11 -> 8).
     */
    private String selectJavaForVersion(String versionId) throws Exception {
        int[] compatRange = javaDownloadService.getJavaVersionRange(versionId);
        int jsonRequired = versionDownloadService.getRequiredJavaMajorVersion(versionId);
        // Номер вне схемы «1.x» (старые снапшоты вида 17w43a и т.п.) правило по номеру не знает и
        // даёт «17+» по умолчанию, а Mojang в json версии прямо пишет, какая Java нужна. Тогда
        // верим json: иначе max() ниже выбирал 17 для игры, которой нужна 8 (14.10.2026, b1.7.3).
        if (jsonRequired > 0 && jsonRequired < compatRange[0] && !versionId.startsWith("1.")) {
            compatRange = new int[] {jsonRequired, jsonRequired <= 8 ? 8 : Integer.MAX_VALUE};
        }
        int requiredJava = Math.max(compatRange[0], jsonRequired);
        int maxJava = compatRange[1];

        if (requiredJava <= 0) return null;

        String rangeStr = maxJava >= Integer.MAX_VALUE ? requiredJava + "+" : requiredJava + "-" + maxJava;
        log("[JAVA] " + versionId + " требует Java " + rangeStr);

        int[] fallbackVersions = buildFallbackChain(requiredJava, maxJava);
        String installErr = "";
        Exception installCause = null;

        for (int tryVer : fallbackVersions) {
            log("[JAVA] Пробуем версию " + tryVer + "...");

            Path autoJavaExe = javaDownloadService.getJavaExecutable(tryVer);
            if (autoJavaExe != null) {
                log("[JAVA] Найдена автоматически загруженная Java " + tryVer);
                return autoJavaExe.toString();
            }

            String bestJava = javaVersionDetector.getJavaForExactVersion(tryVer);
            if (bestJava != null) {
                log("[JAVA] Найдена системная Java " + tryVer + ": " + bestJava);
                return bestJava;
            }

            bestJava = javaVersionDetector.findBestJava(tryVer, maxJava);
            if (bestJava != null) {
                int bestVer = javaVersionDetector.getMajorVersion(bestJava);
                log("[JAVA] Используется совместимая Java " + bestVer + ": " + bestJava);
                return bestJava;
            }

            try {
                launchPhase = "java";
                javaPercent = -1;
                setPlayButtonBusy("Скачивание Java…");
                setHomeProgress(-1, "Скачиваю Java " + tryVer + " для игры… это один раз, не закрывайте лаунчер");
                final int ver = tryVer;
                // Полоса и мегабайты вместо вечной крутилки — 13–20.10.2026 человек ждал Java
                // 18 минут без единой цифры и ушёл (69130832). Обновляем не чаще раза в процент.
                String dlJava = javaDownloadService.downloadAndInstallJava(tryVer,
                        msg -> Platform.runLater(() -> log(msg)),
                        new Http.Progress() {
                            @Override
                            public void update(long done, long total) {
                                if (total <= 0) return;
                                int pct = (int) (100L * done / total);
                                if (pct == javaPercent) return;
                                javaPercent = pct;
                                setHomeProgress(done / (double) total, "Скачиваю Java " + ver + " · "
                                        + (done >> 20) + " из " + (total >> 20) + " МБ · это один раз, не закрывайте лаунчер");
                                setPlayButtonBusy("Java · " + pct + "%");
                            }

                            @Override
                            public void reconnecting(long waitMs) {
                                javaPercent = -1;   // следующий update перерисует полосу
                                setHomeProgress(-1, "Связь прервалась — переподключаюсь, скачанное не пропадёт…");
                            }
                        });
                log("[JAVA] Установлена Java " + tryVer + ": " + dlJava);
                return dlJava;
            } catch (Exception e) {
                installErr = e.getMessage();
                installCause = e;
                log("[JAVA] Не удалось загрузить Java " + tryVer + ": " + installErr);
            }
        }

        // С первопричиной: 18.10.2026 (d523e597) в статистику пришло голое «Exception phase=java»
        // у игрока, которому за 20 минут до этого установщик сказал «нет места на диске», — по
        // нему не понять, сеть это, диск или антивирус, а игрок читал совет ставить Java вручную.
        throw new Exception(
                "Нет доступной Java для " + versionId + " (требуется " + rangeStr + ").\n"
                        + "Пробовали: " + java.util.Arrays.toString(fallbackVersions) + "\n"
                        + "Последняя ошибка: " + installErr + "\n"
                        + "Установите вручную: https://adoptium.net/ или https://bell-sw.com/",
                installCause
        );
    }

    /**
     * Java для первого запуска Rustcraft — с jdk.compiler (см. JavaDownloadService.sourceLaunchJava).
     * Без неё Rustcraft падает с «InternalError» на подготовке данных при каждом запуске.
     */
    private String rustcraftSourceJava(String gameJava) throws Exception {
        long t0 = System.currentTimeMillis(), b0 = Http.DOWNLOADED.get();
        launchPhase = "java";
        javaPercent = -1;
        try {
            String java = javaDownloadService.sourceLaunchJava(gameJava,
                    msg -> Platform.runLater(() -> log(msg)),
                    new Http.Progress() {
                        @Override
                        public void update(long done, long total) {
                            if (total <= 0) return;
                            int pct = (int) (100L * done / total);
                            if (pct == javaPercent) return;
                            javaPercent = pct;
                            setHomeProgress(done / (double) total, "Скачиваю Java для Rustcraft · "
                                    + (done >> 20) + " из " + (total >> 20) + " МБ · это один раз, не закрывайте лаунчер");
                            setPlayButtonBusy("Java · " + pct + "%");
                        }

                        @Override
                        public void reconnecting(long waitMs) {
                            javaPercent = -1;
                            setHomeProgress(-1, "Связь прервалась — переподключаюсь, скачанное не пропадёт…");
                        }
                    });
            boolean dl = Http.DOWNLOADED.get() > b0;
            String src = !dl ? "have" : javaDownloadService.lastFailTag().contains("m:") ? "jdk" : "m";
            stat("rustcraft_java", "ok src=" + src + " sec=" + (System.currentTimeMillis() - t0) / 1000
                    + " mb=" + (Http.DOWNLOADED.get() - b0) / 1_048_576);
            log("[RUSTCRAFT] Java для подготовки данных: " + java);
            launchPhase = "loader";   // при ошибке остаётся «java» — launch_fail покажет jv=…
            return java;
        } catch (Exception e) {
            stat("rustcraft_java", "fail tags=" + javaDownloadService.lastFailTag());
            throw e;
        }
    }

    private int[] buildFallbackChain(int required, int max) {
        int[] lts = {25, 21, 17, 11, 8};
        List<Integer> chain = new ArrayList<>();
        // Не-LTS (1.17.x просит Java 16): сперва ближайшая LTS из допустимых — она есть на зеркале
        // blauncher.ru, а 16 пришлось бы тянуть с GitHub (20.10.2026). 1.17 на Java 17 работает.
        for (int i = lts.length - 1; i >= 0; i--) {
            if (lts[i] > required && lts[i] <= max && java.util.Arrays.stream(lts).noneMatch(v -> v == required)) {
                chain.add(lts[i]);
                break;
            }
        }
        chain.add(required);
        for (int v : lts) {
            if (v != required && v >= required && v <= max && !chain.contains(v)) {
                chain.add(v);
            }
        }
        if (chain.size() == 1 && max < Integer.MAX_VALUE && max != required) {
            chain.add(max);
        }
        int[] result = new int[chain.size()];
        for (int i = 0; i < chain.size(); i++) result[i] = chain.get(i);
        return result;
    }

    // ============================================================
    //  Mods screen — reuses ModrinthService.search()/downloadContent()
    //  as-is; installs into whichever build is currently selected.
    // ============================================================

    private final List<ModInfo> currentModResults = new ArrayList<>();
    /** Эксклюзивы BLauncher над результатами Modrinth (27.10.2026: порт Create на 26.3). */
    private final com.example.launcher.service.ExclusiveModsService exclusiveModsService =
            new com.example.launcher.service.ExclusiveModsService();
    private final List<com.example.launcher.service.ExclusiveModsService.ExclusiveMod> currentExclusives = new ArrayList<>();
    private String activeContentType = "mod";
    /** Fires onSearch() a beat after the user stops typing, so a search fires per letter without
     *  flooding Modrinth with one request per keystroke while typing fast. */
    private final PauseTransition modsSearchDebounce = new PauseTransition(Duration.millis(300));
    { modsSearchDebounce.setOnFinished(e -> onSearch()); }

    private void initModsScreen() {
        modsContentTypeCombo.setItems(FXCollections.observableArrayList("Моды", "Шейдеры", "Ресурспаки", "Датапаки", "Модпаки"));
        modsContentTypeCombo.getSelectionModel().selectFirst();
        modsContentTypeCombo.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            activeContentType = switch (newV == null ? "" : newV) {
                case "Шейдеры" -> "shader";
                case "Ресурспаки" -> "resourcepack";
                case "Датапаки" -> "datapack";
                case "Модпаки" -> "modpack";
                default -> "mod";
            };
        });

        modsVersionFilterCombo.setItems(FXCollections.observableArrayList(
                "Любая версия", "1.21.1", "1.20.1", "1.19.4", "1.16.5", "1.12.2", "1.8.9", "1.7.10"));
        fitComboToItemCount(modsVersionFilterCombo);
        modsVersionFilterCombo.getSelectionModel().selectFirst();

        modsLoaderFilterCombo.setItems(FXCollections.observableArrayList("Любой загрузчик", "forge", "fabric", "neoforge", "quilt"));
        fitComboToItemCount(modsLoaderFilterCombo);
        modsLoaderFilterCombo.getSelectionModel().selectFirst();

        modsSearchField.setOnAction(e -> onSearch());
        modsSearchField.textProperty().addListener((obs, old, val) -> {
            modsSearchDebounce.playFromStart();
        });
    }

    @FXML
    private void onSearch() {
        String query = modsSearchField.getText() == null ? "" : modsSearchField.getText().trim();
        String mcVersionSel = modsVersionFilterCombo.getSelectionModel().getSelectedItem();
        String mcVersion = (mcVersionSel == null || "Любая версия".equals(mcVersionSel)) ? null : mcVersionSel;
        String loaderSel = modsLoaderFilterCombo.getSelectionModel().getSelectedItem();
        String loaderFilter = (loaderSel == null || "Любой загрузчик".equals(loaderSel)) ? null : loaderSel;
        String type = activeContentType;

        log("[SEARCH] Поиск " + type + ": " + query);
        executor.submit(() -> {
            try {
                List<ModInfo> results = modrinthService.search(query, mcVersion, loaderFilter, type, 50);
                List<com.example.launcher.service.ExclusiveModsService.ExclusiveMod> exclusives = new ArrayList<>();
                if ("mod".equals(type)) {
                    for (var ex : exclusiveModsService.list()) {
                        if (ex.matches(query, mcVersion, loaderFilter)) exclusives.add(ex);
                    }
                }
                Platform.runLater(() -> {
                    currentExclusives.clear();
                    currentExclusives.addAll(exclusives);
                    currentModResults.clear();
                    currentModResults.addAll(results);
                    renderModResults();
                    log("[SEARCH] Найдено " + results.size());
                    // Что искали — не шлём (это текст человека); только был ли запрос, фильтры и сколько нашлось.
                    // 21.10.2026 новички часами сидели на «Моды» и уходили ни с чем — было не понять, искали ли вообще.
                    // Modrinth ищет только по английским названиям: 23–25.10.2026 восемь человек сделали
                    // 33 поиска без единого результата — по всей видимости, по-русски. cyr=1 — в запросе
                    // была кириллица (сам запрос не шлём).
                    boolean cyr = query.chars().anyMatch(c -> Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC);
                    if (searchStats++ < 40) {
                        stat("mod_search", "type=" + type + " q=" + (query.isEmpty() ? 0 : 1) + " found=" + results.size()
                                + (mcVersion != null ? " ver=1" : "") + (loaderFilter != null ? " loader=1" : "")
                                + (cyr ? " cyr=1" : ""));
                    }
                    if (results.isEmpty() && cyr) {
                        showToast("Каталог модов понимает только английские названия — попробуйте, например, "
                                + "«sodium», «jei», «journeymap» или «shaders»", "info");
                    }
                });
            } catch (Exception ex) {
                log("[ERROR] " + ex.getMessage());
                // Раньше ошибка поиска только писалась в журнал: человек видел пустой список и думал,
                // что модов нет.
                Platform.runLater(() -> {
                    if (searchStats++ < 40) stat("mod_search", "fail type=" + type + " exc=" + ex.getClass().getSimpleName());
                    showToast("Не удалось загрузить список с Modrinth — проверьте интернет и попробуйте ещё раз", "error");
                });
            }
        });
    }

    private void renderModResults() {
        modsListPanel.getChildren().clear();
        if ("mod".equals(activeContentType)) {
            for (var ex : currentExclusives) {
                modsListPanel.getChildren().add(buildExclusiveRow(ex));
            }
        }
        modDescLabels.clear();
        for (ModInfo mod : currentModResults) {
            modsListPanel.getChildren().add(buildModRow(mod));
        }
        translateModDescriptions();
    }

    /** Описания текущей выдачи: подпись → английский оригинал (для перевода и подсказки с оригиналом). */
    private final Map<Label, String> modDescLabels = new LinkedHashMap<>();
    private final com.example.launcher.service.ModDescriptionTranslator modDescTranslator =
            new com.example.launcher.service.ModDescriptionTranslator();
    private int modDescGeneration;

    /**
     * Описания с Modrinth — по-английски; при русском интерфейсе подменяем их переводом (27.10.2026, просьба
     * владельца). Строки рисуются сразу, перевод приходит следом; ответ на старую выдачу отбрасывается.
     */
    private void translateModDescriptions() {
        int generation = ++modDescGeneration;
        if (!"ru".equals(currentLanguage) || modDescLabels.isEmpty()) return;
        Map<Label, String> snapshot = new LinkedHashMap<>(modDescLabels);
        List<String> texts = new ArrayList<>(new java.util.LinkedHashSet<>(snapshot.values()));
        executor.submit(() -> {
            Map<String, String> ru = modDescTranslator.translate(texts);
            if (ru.isEmpty()) return;
            Platform.runLater(() -> {
                if (generation != modDescGeneration) return;
                snapshot.forEach((label, original) -> {
                    String t = ru.get(original);
                    if (t == null || t.equals(original)) return;
                    label.setText(t);
                    Tooltip.install(label, new Tooltip(original));
                });
            });
        });
    }

    private Node buildModRow(ModInfo mod) {
        HBox row = new HBox(12);
        row.getStyleClass().add("server-row");
        row.setAlignment(Pos.CENTER_LEFT);

        Node icon = buildModIconNode(mod);

        VBox textBox = new VBox(2);
        Label name = new Label(mod.getTitle());
        name.getStyleClass().add("server-name");
        Label sub = new Label(mod.getDescription() != null ? mod.getDescription() : "");
        sub.getStyleClass().add("server-sub");
        sub.setWrapText(true);
        textBox.getChildren().addAll(name, sub);
        if (mod.getDescription() != null && !mod.getDescription().isBlank()) {
            modDescLabels.put(sub, mod.getDescription());
        }

        String versionRange = mod.getVersionRangeLabel();
        StringBuilder loaderList = new StringBuilder();
        if (mod.getLoaders() != null) {
            for (String loader : mod.getLoaders()) {
                if (loader == null || loader.isBlank()) continue;
                if (loaderList.length() > 0) loaderList.append(", ");
                loaderList.append(Character.toUpperCase(loader.charAt(0))).append(loader.substring(1));
            }
        }
        if (!versionRange.isBlank() || loaderList.length() > 0) {
            String metaText = versionRange.isBlank() ? loaderList.toString()
                    : loaderList.length() == 0 ? versionRange
                    : versionRange + " · " + loaderList;
            // role-badge (accent-tinted pill) instead of build-meta's plain gray text - build-meta
            // is the exact same size/color as "sub" right above it (the mod's description), so
            // the two just blended into one another with nothing to tell them apart at a glance.
            Label meta = new Label(metaText);
            meta.getStyleClass().addAll("role-badge", "mono");
            HBox metaRow = new HBox(meta);
            metaRow.setAlignment(Pos.CENTER_LEFT);
            textBox.getChildren().add(metaRow);
        }
        HBox.setHgrow(textBox, Priority.ALWAYS);

        Button addBtn = new Button("+");
        addBtn.getStyleClass().add("secondary-button");
        if ("modpack".equals(activeContentType)) {
            // A modpack is not a file you add to a build - it IS the build (it brings its own
            // Minecraft version and loader), so its "+" skips the "which build?" menu entirely.
            addBtn.setOnAction(e -> startModpackInstallFromModrinth(mod, addBtn));
        } else {
            addBtn.setOnAction(e -> showAddModTargetMenu(mod, addBtn));
        }

        row.getChildren().addAll(icon, textBox, addBtn);
        return row;
    }

    /**
     * Карточка эксклюзива BLauncher: та же строка, что у мода с Modrinth, но в жёлтой рамке и с жёлтой
     * пометкой «ЭКСКЛЮЗИВ» (27.10.2026, просьба владельца — порт Create на 26.3 должен быть заметен в поиске).
     */
    private Node buildExclusiveRow(com.example.launcher.service.ExclusiveModsService.ExclusiveMod ex) {
        HBox row = new HBox(12);
        row.getStyleClass().addAll("server-row", "exclusive-row");
        row.setAlignment(Pos.CENTER_LEFT);

        Region placeholder = new Region();
        placeholder.getStyleClass().add("build-icon-sm");
        StackPane icon = new StackPane(placeholder);
        if (ex.icon != null && !ex.icon.isBlank()) {
            iconExecutor.submit(() -> {
                Image img = com.example.launcher.ui.IconLoader.load(ex.icon);
                if (img != null) {
                    Platform.runLater(() -> {
                        ImageView iv = new ImageView(img);
                        iv.setFitWidth(32);
                        iv.setFitHeight(32);
                        iv.setPreserveRatio(true);
                        icon.getChildren().setAll(iv);
                    });
                }
            });
        }

        Label name = new Label(ex.title);
        name.getStyleClass().add("server-name");
        Label badge = new Label(ex.badge == null || ex.badge.isBlank() ? "ЭКСКЛЮЗИВ" : ex.badge);
        badge.getStyleClass().add("exclusive-badge");
        HBox titleRow = new HBox(8, name, badge);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        VBox textBox = new VBox(2, titleRow);
        if (ex.description != null && !ex.description.isBlank()) {
            Label sub = new Label(ex.description);
            sub.getStyleClass().add("server-sub");
            sub.setWrapText(true);
            textBox.getChildren().add(sub);
        }
        String targets = ex.targetsLabel();
        if (!targets.isBlank()) {
            Label meta = new Label(targets);
            meta.getStyleClass().addAll("role-badge", "mono");
            HBox metaRow = new HBox(meta);
            metaRow.setAlignment(Pos.CENTER_LEFT);
            textBox.getChildren().add(metaRow);
        }
        HBox.setHgrow(textBox, Priority.ALWAYS);

        Button addBtn = new Button("+");
        addBtn.getStyleClass().add("secondary-button");
        addBtn.setOnAction(e -> showExclusiveTargetMenu(ex, addBtn));

        row.getChildren().addAll(icon, textBox, addBtn);
        return row;
    }

    /** Выбор сборки для эксклюзива: ставится только туда, где совпадают версия игры и загрузчик. */
    private void showExclusiveTargetMenu(com.example.launcher.service.ExclusiveModsService.ExclusiveMod ex, Button sourceButton) {
        ContextMenu menu = new ContextMenu();
        for (Profile build : builds) {
            if (build.isVanillaEntry()) continue;
            var file = ex.fileFor(build.getModLoader(), build.getVersionId());
            MenuItem item = new MenuItem(build.getName() + (file == null ? "  (не подходит)" : ""));
            if (file == null) {
                item.setDisable(true);
            } else {
                item.setOnAction(e -> installExclusiveIntoBuild(ex, file, build, sourceButton));
            }
            menu.getItems().add(item);
        }
        if (!menu.getItems().isEmpty()) {
            menu.getItems().add(new SeparatorMenuItem());
        }
        MenuItem createNew = new MenuItem("+ Новая сборка...");
        createNew.setOnAction(e -> {
            showToast("Для «" + ex.title + "» нужна сборка " + ex.targetsLabel() + " — создайте её, затем нажмите «+» ещё раз", "info");
            onOpenCreateBuild();
        });
        menu.getItems().add(createNew);
        menu.show(sourceButton, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    private void installExclusiveIntoBuild(com.example.launcher.service.ExclusiveModsService.ExclusiveMod ex,
                                           com.example.launcher.service.ExclusiveModsService.ExclusiveFile file,
                                           Profile build, Button sourceButton) {
        if (sourceButton != null) sourceButton.setDisable(true);
        FxProgress progress = showRowProgress(sourceButton);
        executor.submit(() -> {
            try {
                modrinthService.downloadDirect(file.url, file.fileName, file.sha1, build.getName(),
                        "bl:" + ex.id, ex.title, progress == null ? null : progress.part(0, 1));
                stat("exclusive_add", "ok id=" + ex.id + " loader=" + file.loader + " mc=" + file.mcVersion);
                Platform.runLater(() -> {
                    hideRowProgress(sourceButton, false);
                    log("[MODS] Установлено в «" + build.getName() + "»: " + ex.title);
                    showToast("«" + ex.title + "» установлен в «" + build.getName() + "»", "success");
                    if (sourceButton != null) {
                        FontIcon done = new FontIcon("fth-check");
                        done.getStyleClass().add("installed-check");
                        sourceButton.setText("");
                        sourceButton.setGraphic(done);
                    }
                });
            } catch (Exception e) {
                stat("exclusive_add", "fail id=" + ex.id + " exc=" + e.getClass().getSimpleName());
                Platform.runLater(() -> {
                    log("[ERROR] Не удалось установить " + ex.title + ": " + e.getMessage());
                    hideRowProgress(sourceButton, true);
                    showToast("Не удалось скачать «" + ex.title + "» — проверьте интернет и попробуйте ещё раз", "error");
                });
            }
        });
    }

    /** Search-result row icon: Modrinth returns an {@code icon_url} for basically every project
     *  (mod/shader/resourcepack/datapack alike, see ModrinthService.search()/ModInfo.getIconUrl())
     *  but this row used to always render a bare, blank "build-icon-sm" Region - the icon was
     *  fetched and available, it just was never actually shown. Loads via IconLoader (not plain
     *  JavaFX Image - see its javadoc, the short version is Modrinth mostly serves WebP and
     *  JavaFX's own image loader can't decode that) on the background executor, swapping the
     *  placeholder for the real icon once it's ready; stays on the blank placeholder on a
     *  missing/bad URL or decode failure. */
    private Node buildModIconNode(ModInfo mod) {
        Region placeholder = new Region();
        placeholder.getStyleClass().add("build-icon-sm");
        String iconUrl = mod.getIconUrl();
        if (iconUrl == null || iconUrl.isBlank()) return placeholder;

        StackPane holder = new StackPane(placeholder);
        iconExecutor.submit(() -> {
            Image img = com.example.launcher.ui.IconLoader.load(iconUrl);
            if (img != null) {
                Platform.runLater(() -> {
                    ImageView iv = new ImageView(img);
                    iv.setFitWidth(32);
                    iv.setFitHeight(32);
                    iv.setPreserveRatio(true);
                    holder.getChildren().setAll(iv);
                });
            }
        });
        return holder;
    }

    /** A mod queued via showAddModTargetMenu()'s "+ Новая сборка" choice - installed once
     *  onConfirmCreateBuild() actually finishes creating that build, then cleared. */
    private ModInfo pendingModForNewBuild;

    /** Lets the player pick which build a search-result mod goes into (or create a new one for
     *  it) right from the "+" button, instead of silently targeting whatever selectedBuild
     *  happens to be left over from the Home/Builds screens - which used to just log an error
     *  and do nothing if that happened to be the vanilla "no build" entry, with no way to tell
     *  from the Mods screen itself which build was even about to receive the mod. */
    private void showAddModTargetMenu(ModInfo mod, Button sourceButton) {
        ContextMenu menu = new ContextMenu();
        // Сборки, под версию игры которых у проекта файла нет, — сразу видно и не нажать.
        // 29.10.2026 b514efef десять раз подряд добавлял ресурспаки в сборку Fabric 1.16.5 и десять
        // раз получал «нет подходящей версии»: поиск идёт по «Любой версии», а сборку выбирают уже
        // здесь. Список версий из поиска Modrinth полный (см. ModrinthService.search), пустой — не знаем.
        java.util.List<String> known = mod.getGameVersions();
        for (Profile build : builds) {
            if (build.isVanillaEntry()) continue;
            String v = build.getVersionId();
            boolean noFile = known != null && !known.isEmpty() && v != null && !known.contains(v);
            MenuItem item = new MenuItem(build.getName() + (noFile ? "  ·  нет версии для " + v : ""));
            item.setDisable(noFile);
            item.setOnAction(e -> installModIntoBuild(mod, build, sourceButton));
            menu.getItems().add(item);
        }
        if (!menu.getItems().isEmpty()) {
            menu.getItems().add(new SeparatorMenuItem());
        }
        MenuItem createNew = new MenuItem("+ Новая сборка...");
        createNew.setOnAction(e -> {
            pendingModForNewBuild = mod;
            onOpenCreateBuild();
        });
        menu.getItems().add(createNew);
        menu.show(sourceButton, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    private static final String ROW_PROGRESS = "bl.rowProgress";
    private static final String ROW_PROGRESS_NODE = "bl.rowProgressNode";

    /**
     * Шкала с процентами на месте «+» в строке результата поиска, пока файл качается.
     * До 14.10.2026 кнопка просто серела, а ход был виден только в консоли: шейдер или ресурспак
     * на сотни мегабайт выглядел как «нажал — ничего не происходит». Вызывать в UI-потоке.
     */
    private FxProgress showRowProgress(Button btn) {
        if (btn == null) return null;
        if (btn.getProperties().get(ROW_PROGRESS) instanceof FxProgress existing) return existing;
        if (!(btn.getParent() instanceof javafx.scene.layout.Pane parent)) return null;
        ProgressBar bar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        bar.setMinWidth(84);
        bar.setPrefWidth(84);
        bar.setMaxWidth(84);
        Label pct = new Label("");
        pct.getStyleClass().addAll("toggle-sub", "mono");
        // Не меньше своего текста: при заданных 38 и 48 пикселях JavaFX сворачивал «42 %» в «…»
        // (у строки результата свой, более крупный шрифт). Число пикселей тут не угадать.
        pct.setMinWidth(Region.USE_PREF_SIZE);
        HBox box = new HBox(6, bar, pct);
        box.setAlignment(Pos.CENTER_RIGHT);
        parent.getChildren().add(parent.getChildren().indexOf(btn), box);
        btn.setVisible(false);
        btn.setManaged(false);
        FxProgress progress = new FxProgress(bar, pct, FxProgress.Style.PERCENT);
        btn.getProperties().put(ROW_PROGRESS, progress);
        btn.getProperties().put(ROW_PROGRESS_NODE, box);
        return progress;
    }

    /** Шкала, которую {@link #showRowProgress} поставила на место этой кнопки, или null. */
    private FxProgress rowProgressOf(Button btn) {
        return btn != null && btn.getProperties().get(ROW_PROGRESS) instanceof FxProgress p ? p : null;
    }

    /** Убирает шкалу и возвращает кнопку; {@code enable} — снова можно нажать (при ошибке/отмене). */
    private void hideRowProgress(Button btn, boolean enable) {
        if (btn == null) return;
        Object node = btn.getProperties().remove(ROW_PROGRESS_NODE);
        btn.getProperties().remove(ROW_PROGRESS);
        if (node instanceof Node n && n.getParent() instanceof javafx.scene.layout.Pane parent) parent.getChildren().remove(n);
        btn.setVisible(true);
        btn.setManaged(true);
        if (enable) btn.setDisable(false);
    }

    private void installModIntoBuild(ModInfo mod, Profile build, Button sourceButton) {
        if (sourceButton != null) sourceButton.setDisable(true);
        FxProgress rowProgress = showRowProgress(sourceButton);
        if (rowProgress != null) rowProgress.indeterminate();
        String mcVersion = build.getVersionId();
        // Modrinth's "loaders" filter only means "mod loader" (fabric/forge/neoforge/quilt) for
        // actual mods - shaders use a completely different loader vocabulary (iris/optifine/canvas),
        // resourcepacks use "minecraft", datapacks use "datapack" (see ModrinthService.search()'s
        // javadoc, which already treats this correctly for search but resolveBestVersion() below
        // was still being handed the build's mod loader regardless of activeContentType). Filtering
        // shader/resourcepack/datapack versions by e.g. "fabric" matches nothing, so every install
        // of those used to fail with "no version supports fabric" even for perfectly compatible
        // files - only real mods should be loader-filtered here.
        //
        // Датапаки — исключение: многие (Terralith, Incendium, …) публикуются в одном проекте и как
        // датапак, и как мод под Fabric/NeoForge. Без фильтра первой приходила версия-мод, и в
        // datapacks/<сборка>/ ложился Terralith_…_Neoforge.jar, который игра как датапак не берёт.
        // Фильтр "datapack" оставляет только настоящий zip-датапак.
        String loader = "mod".equals(activeContentType) ? build.getModLoader()
                : "datapack".equals(activeContentType) ? "datapack" : null;
        // Тип контента снимаем ЗДЕСЬ, в UI-потоке, и передаём в задачу снимком. Раньше в
        // downloadContent передавалось само поле activeContentType, которое меняет слушатель
        // комбобокса, а оно определяет папку назначения: переключил «Шейдеры» на «Ресурспаки»
        // пока идёт загрузка — и архив шейдера уезжал в resourcepacks/<сборка>/ и записывался
        // в .installed.json ТОЙ папки. Соседний loader снимался правильно, а тип — нет.
        final String contentType = activeContentType;
        final List<String> gameVersions = new ArrayList<>(buildVersionCombo.getItems());
        final List<String> buildLoaders = new ArrayList<>(buildLoaderCombo.getItems());
        executor.submit(() -> {
            try {
                String versionId = modrinthService.resolveBestVersion(mod.getProjectId(), mcVersion, loader);
                if (versionId == null) {
                    stat("mod_add", "no_version type=" + contentType + " mc=" + mcVersion
                            + (loader == null ? "" : " loader=" + loader));
                    String[] fit = null;
                    if ("mod".equals(contentType)) {
                        try {
                            fit = bestBuildForMod(mod.getProjectId(), gameVersions, buildLoaders);
                        } catch (Exception ignored) {
                            // не узнали — остаётся подсказка по данным поиска
                        }
                    }
                    String[] fits = fit;
                    Platform.runLater(() -> {
                        log("[MODS] Нет подходящей версии \"" + mod.getTitle() + "\" для " + mcVersion + " " + loader);
                        hideRowProgress(sourceButton, true);
                        // Та же пара, что у сборки, — значит, файл есть, но не подошёл по другой причине:
                        // предлагать её же было бы кругом.
                        if (fits != null && !(fits[0].equals(mcVersion) && fits[1].equals(loader))) {
                            offerBuildWhereModFits(mod, build, loader, fits);
                            return;
                        }
                        // Раньше только в журнал, который свёрнут: «+» снова становился активным, и со
                        // стороны это выглядело как «нажал — ничего». 21.10.2026 новички уходили как
                        // раз с экрана «Моды», не поставив ни одного мода.
                        showToast(noModVersionHint(mod, build, loader), "error");
                    });
                    return;
                }
                List<ModrinthService.MissingDependency> deps = List.of();
                if ("mod".equals(contentType)) {
                    try {
                        deps = modrinthService.missingRequiredDependencies(versionId, mcVersion, loader, build.getName());
                    } catch (Exception depEx) {
                        // Не узнали — ставим как раньше, без зависимостей; установку из-за этого не валим.
                        Platform.runLater(() -> log("[MODS] Не удалось проверить обязательные моды для "
                                + mod.getTitle() + ": " + depEx.getMessage()));
                    }
                }
                if (deps.isEmpty()) {
                    installModFiles(mod, build, contentType, versionId, List.of(), sourceButton);
                    return;
                }
                List<ModrinthService.MissingDependency> found = deps;
                Platform.runLater(() -> askRequiredDependencies(mod, build, contentType, versionId, found, sourceButton));
            } catch (Exception ex) {
                stat("mod_add", "fail type=" + contentType + " exc=" + ex.getClass().getSimpleName());
                Platform.runLater(() -> {
                    log("[ERROR] Не удалось установить " + mod.getTitle() + ": " + ex.getMessage());
                    hideRowProgress(sourceButton, true);
                    showToast(modInstallFailHint(mod, ex), "error");
                });
            }
        });
    }

    /**
     * Мод требует другие моды, которых в сборке нет. Спрашиваем, а не ставим молча: игрок должен
     * видеть, что в сборку попадает что-то кроме выбранного. Без них Fabric/Forge не запустится —
     * так 11-12.10.2026 вылетали сборки новичков («why=mods» в событиях).
     */
    private void askRequiredDependencies(ModInfo mod, Profile build, String contentType, String versionId,
                                         List<ModrinthService.MissingDependency> deps, Button sourceButton) {
        List<ModrinthService.MissingDependency> installable = new ArrayList<>();
        StringBuilder list = new StringBuilder();
        StringBuilder unavailable = new StringBuilder();
        for (ModrinthService.MissingDependency d : deps) {
            if (d.versionId != null) {
                installable.add(d);
                list.append("•  ").append(d.title).append('\n');
            } else {
                unavailable.append("•  ").append(d.title).append('\n');
            }
        }
        String where = build.getModLoader() + " " + build.getVersionId();
        StringBuilder text = new StringBuilder();
        if (!installable.isEmpty()) {
            text.append("Без них игра не запустится:\n").append(list);
        }
        if (unavailable.length() > 0) {
            if (text.length() > 0) text.append('\n');
            text.append("Этих модов нет для ").append(where).append(" — с ними «").append(mod.getTitle())
                    .append("» скорее всего не запустится:\n").append(unavailable);
        }

        Alert confirm = new Alert(Alert.AlertType.WARNING);
        confirm.initOwner(primaryStage);
        confirm.setTitle("Нужны дополнительные моды");
        confirm.setHeaderText("«" + mod.getTitle() + "» требует " + (deps.size() == 1 ? "ещё один мод"
                : "ещё " + deps.size() + (deps.size() < 5 ? " мода" : " модов")));
        confirm.setContentText(text.toString().trim());
        ButtonType all = new ButtonType(installable.isEmpty() ? "Всё равно установить" : "Установить вместе с ними",
                ButtonBar.ButtonData.OK_DONE);
        ButtonType onlyThis = new ButtonType("Только этот мод", ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType("Отмена", ButtonBar.ButtonData.CANCEL_CLOSE);
        if (installable.isEmpty()) {
            confirm.getButtonTypes().setAll(all, cancel);
        } else {
            confirm.getButtonTypes().setAll(all, onlyThis, cancel);
        }
        ButtonType choice = confirm.showAndWait().orElse(cancel);
        if (choice == cancel) {
            hideRowProgress(sourceButton, true);
            return;
        }
        List<ModrinthService.MissingDependency> withMod = choice == all ? installable : List.of();
        if (choice == onlyThis) {
            log("[MODS] «" + mod.getTitle() + "» ставится без обязательных модов — игра может не запуститься");
        }
        executor.submit(() -> installModFiles(mod, build, contentType, versionId, withMod, sourceButton));
    }

    /** Качает зависимости, затем сам мод. Вызывать в фоне. */
    private void installModFiles(ModInfo mod, Profile build, String contentType, String versionId,
                                 List<ModrinthService.MissingDependency> deps, Button sourceButton) {
        // Шкала общая на все файлы: сначала зависимости, последним — сам мод.
        FxProgress progress = rowProgressOf(sourceButton);
        int files = deps.size() + 1;
        try {
            List<String> failedDeps = new ArrayList<>();
            for (int i = 0; i < deps.size(); i++) {
                ModrinthService.MissingDependency d = deps.get(i);
                if (progress != null) progress.startPart(i, files);
                try {
                    modrinthService.downloadContent(d.versionId, build.getName(), "mod", d.projectId, d.title,
                            msg -> Platform.runLater(() -> log("[MODS] " + msg)),
                            progress == null ? null : progress.part(i, files));
                    Platform.runLater(() -> log("[MODS] Установлено в «" + build.getName() + "»: " + d.title + " (нужен для " + mod.getTitle() + ")"));
                } catch (Exception depEx) {
                    failedDeps.add(d.title);
                    Platform.runLater(() -> log("[ERROR] Не удалось установить " + d.title + ": " + depEx.getMessage()));
                }
            }
            if (progress != null) progress.startPart(files - 1, files);
            modrinthService.downloadContent(versionId, build.getName(), contentType,
                    mod.getProjectId(), mod.getTitle(),
                    msg -> Platform.runLater(() -> log("[MODS] " + msg)),
                    progress == null ? null : progress.part(files - 1, files));
            stat("mod_add", "ok type=" + contentType + " deps=" + deps.size());
            boolean addedFabricApi = "mod".equals(contentType) && ensureFabricApi(build);
            Platform.runLater(() -> {
                hideRowProgress(sourceButton, false);
                log("[MODS] Установлено в «" + build.getName() + "»: " + mod.getTitle());
                if (addedFabricApi) {
                    showToast("Вместе с «" + mod.getTitle() + "» поставлен Fabric API — без него мод не запустится", "info");
                }
                if (!deps.isEmpty()) {
                    if (failedDeps.isEmpty()) {
                        showToast("«" + mod.getTitle() + "» и нужные ему моды (" + deps.size() + ") установлены", "success");
                    } else {
                        showToast("Не удалось скачать: " + String.join(", ", failedDeps) + " — без них игра может не запуститься", "error");
                    }
                }
                if ("mod".equals(contentType)) hintPlayAfterMods(build);
                if (sourceButton != null) {
                    // An icon, not a "✓" character: the bundled faces have no U+2713.
                    FontIcon done = new FontIcon("fth-check");
                    done.getStyleClass().add("installed-check");
                    sourceButton.setText("");
                    sourceButton.setGraphic(done);
                }
            });
        } catch (Exception ex) {
            stat("mod_add", "fail type=" + contentType + " exc=" + ex.getClass().getSimpleName());
            Platform.runLater(() -> {
                log("[ERROR] Не удалось установить " + mod.getTitle() + ": " + ex.getMessage());
                hideRowProgress(sourceButton, true);
                showToast(modInstallFailHint(mod, ex), "error");
            });
        }
    }

    /** Fabric API на Modrinth. */
    private static final String FABRIC_API_PROJECT = "P7dR8mSH";

    /**
     * Ставит Fabric API в Fabric/Quilt-сборку, если моду в ней он нужен (по fabric.mod.json), а его
     * нет — до первого вылета, а не после (ModDoctor.needsFabricApi). Из фонового потока. true — поставлен.
     */
    private boolean ensureFabricApi(Profile build) {
        String loader = build.getModLoader() == null ? "" : build.getModLoader().toLowerCase(java.util.Locale.ROOT);
        if (!loader.equals("fabric") && !loader.equals("quilt")) return false;
        try {
            if (!ModDoctor.needsFabricApi(modrinthService.contentDir("mod", build.getName()))) return false;
            String v = modrinthService.resolveBestVersion(FABRIC_API_PROJECT, build.getVersionId(), "fabric");
            if (v == null) return false;
            modrinthService.downloadContent(v, build.getName(), "mod", FABRIC_API_PROJECT, "Fabric API",
                    msg -> Platform.runLater(() -> log("[MODS] " + msg)));
            stat("mod_add", "auto_fabric_api mc=" + build.getVersionId());
            return true;
        } catch (Exception e) {
            Platform.runLater(() -> log("[MODS] Не удалось поставить Fabric API: " + e.getMessage()));
            return false;
        }
    }

    /**
     * Почему мод не встал в сборку — для всплывающего сообщения: для какой версии и загрузчика
     * его нет и для каких есть (из результата поиска Modrinth, снапшоты отброшены).
     */
    private static String noModVersionHint(ModInfo mod, Profile build, String loader) {
        String where = build.getVersionId() + (loader == null || "vanilla".equals(loader) ? "" : " " + loader);
        List<String> releases = new ArrayList<>();
        for (String v : mod.getGameVersions()) if (v != null && v.matches("\\d+\\.\\d+(\\.\\d+)?")) releases.add(v);
        String have = "";
        if (!releases.isEmpty()) {
            List<String> sorted = ModInfo.sortedVersions(releases);
            String min = sorted.get(0), max = sorted.get(sorted.size() - 1);
            have = " Есть для " + (min.equals(max) ? min : min + "–" + max);
            if (loader != null && !mod.getLoaders().isEmpty()) have += " (" + String.join(", ", mod.getLoaders()) + ")";
            have += " — создайте сборку под эту версию.";
        }
        return "«" + mod.getTitle() + "» нет для " + where + "." + have;
    }

    private static String modInstallFailHint(ModInfo mod, Exception ex) {
        boolean net = networkHint(ex, "dl") != null;
        return "Не удалось поставить «" + mod.getTitle() + "»"
                + (net ? ": нет связи с Modrinth — проверьте интернет и нажмите «+» ещё раз" : " — подробности в журнале");
    }

    // ============================================================
    //  Modpacks (.mrpack) — reading a pack archive and installing it
    //  as a new build. The format itself and every file that lands
    //  on disk are ModpackService's business; what follows is only
    //  the dialog around it.
    // ============================================================

    /** Blocks both entry points into this flow while an archive is being read or fetched, so an
     *  impatient second click cannot start the same 200 MB download twice. */
    private void setModpackBusy(boolean busy) {
        if (modsImportPackBtn != null) modsImportPackBtn.setDisable(busy);
    }

    /**
     * "Импорт .mrpack": reads a pack the player already has as a file and offers to install it as
     * a new build. The archive is parsed on the background executor and the summary is only shown
     * once it parsed successfully - a corrupt file, or a ZIP that simply isn't a modpack, must
     * never leave a half-created build behind.
     */
    @FXML
    private void onImportMrpack() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Выберите сборку (.mrpack)");
        chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Сборка Modrinth (*.mrpack)", "*.mrpack"));
        java.io.File file = chooser.showOpenDialog(primaryStage);
        if (file == null) return;

        setModpackBusy(true);
        log("[MODPACK] Чтение " + file.getName() + "...");
        executor.submit(() -> {
            try {
                ModpackService.PackInfo info = modpackService.read(file.toPath());
                Platform.runLater(() -> {
                    setModpackBusy(false);
                    openModpackSummary(info, file.toPath(), false);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    setModpackBusy(false);
                    log("[MODPACK] Не удалось прочитать сборку: " + ex.getMessage());
                    showToast("Не удалось прочитать .mrpack: " + ex.getMessage(), "warning");
                });
            }
        });
    }

    /**
     * «Создать .mrpack из сборки» (17.10.2026): выбор сборки, название и версия пака, сохранение
     * файла. Сам формат — ModpackService.export; здесь только окно вокруг него.
     */
    @FXML
    private void onExportMrpack() {
        List<Profile> choices = new ArrayList<>(builds);
        if (choices.isEmpty()) {
            showToast("Сначала создайте сборку — пак собирается из неё", "info");
            return;
        }
        StackPane scrim = new StackPane();
        scrim.getStyleClass().add("modal-scrim");

        VBox card = new VBox(0);
        card.getStyleClass().add("modal-card");
        card.setMinWidth(420);
        card.setMinHeight(300);
        card.setPrefWidth(600);
        card.setMaxWidth(600);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setPickOnBounds(true);

        HBox header = new HBox();
        header.getStyleClass().add("modal-header");
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label(tr("mrpack_export_title"));
        title.getStyleClass().add("modal-title");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        Button close = new Button();
        close.getStyleClass().add("modal-close");
        close.setGraphic(new FontIcon("fth-x"));
        header.getChildren().addAll(title, headerSpacer, close);

        VBox body = new VBox(10);
        body.setPadding(new javafx.geometry.Insets(16));
        Label intro = new Label(tr("mrpack_export_sub"));
        intro.getStyleClass().add("toggle-sub");
        intro.setWrapText(true);

        Label buildLabel = new Label(tr("label_build"));
        buildLabel.getStyleClass().add("select-label");
        ComboBox<Profile> buildCombo = new ComboBox<>(FXCollections.observableArrayList(choices));
        buildCombo.getStyleClass().add("combo-box");
        buildCombo.setMaxWidth(Double.MAX_VALUE);
        buildCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Profile p) { return p == null ? "" : p.getName(); }
            @Override public Profile fromString(String s) { return null; }
        });
        Profile preselect = selectedBuild != null && choices.contains(selectedBuild) ? selectedBuild : choices.get(0);
        buildCombo.getSelectionModel().select(preselect);

        VBox facts = new VBox(4);
        Label nameLabel = new Label(tr("mrpack_export_name"));
        nameLabel.getStyleClass().add("select-label");
        TextField nameField = new TextField(preselect.getName());
        nameField.getStyleClass().add("text-input");
        Label versionLabel = new Label(tr("mrpack_export_version"));
        versionLabel.getStyleClass().add("select-label");
        TextField versionField = new TextField("1.0.0");
        versionField.getStyleClass().addAll("text-input", "mono");
        VBox nameBox = new VBox(6, nameLabel, nameField);
        VBox versionBox = new VBox(6, versionLabel, versionField);
        HBox.setHgrow(nameBox, Priority.ALWAYS);
        versionBox.setPrefWidth(140);
        HBox nameRow = new HBox(10, nameBox, versionBox);

        Runnable refreshFacts = () -> {
            Profile p = buildCombo.getValue();
            facts.getChildren().clear();
            if (p == null) return;
            String loader = p.getModLoader() == null || p.getModLoader().isBlank() ? "vanilla" : p.getModLoader();
            facts.getChildren().addAll(
                    modpackFact("Minecraft", p.getVersionId()),
                    modpackFact(tr("label_loader"), "vanilla".equalsIgnoreCase(loader) ? "Vanilla"
                            : loader + (p.getModLoaderVersion() == null || p.getModLoaderVersion().isBlank() ? "" : " " + p.getModLoaderVersion())),
                    modpackFact(tr("mrpack_export_files"), String.valueOf(countBuildContentFiles(p.getName()))));
        };
        buildCombo.setOnAction(e -> {
            refreshFacts.run();
            if (buildCombo.getValue() != null) nameField.setText(buildCombo.getValue().getName());
        });
        refreshFacts.run();

        body.getChildren().addAll(intro, buildLabel, buildCombo, facts, nameRow);

        Label progress = new Label("");
        progress.getStyleClass().add("toggle-sub");
        progress.setWrapText(true);
        HBox.setHgrow(progress, Priority.ALWAYS);
        progress.setMaxWidth(Double.MAX_VALUE);
        Button cancel = new Button(tr("cancel_btn"));
        cancel.getStyleClass().add("copy-btn");
        Button save = new Button(tr("mrpack_export_save"));
        save.getStyleClass().add("server-action");
        HBox footer = new HBox(10, progress, cancel, save);
        footer.setPadding(new javafx.geometry.Insets(12, 16, 16, 16));
        footer.setAlignment(Pos.CENTER_RIGHT);

        Runnable dismiss = () -> {
            overlayLayer.getChildren().remove(scrim);
            hideOverlay(scrim);
        };
        close.setOnAction(e -> dismiss.run());
        cancel.setOnAction(e -> dismiss.run());

        save.setOnAction(e -> {
            Profile p = buildCombo.getValue();
            if (p == null) return;
            String packName = nameField.getText() == null || nameField.getText().isBlank() ? p.getName() : nameField.getText().trim();
            String packVersion = versionField.getText() == null || versionField.getText().isBlank() ? "1.0.0" : versionField.getText().trim();
            FileChooser chooser = new FileChooser();
            chooser.setTitle(tr("mrpack_export_title"));
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Modrinth (*.mrpack)", "*.mrpack"));
            // Имя файла — без символов, которые Windows в имени не допускает.
            chooser.setInitialFileName((packName + "-" + packVersion).replaceAll("[\\\\/:*?\"<>|]", "_") + ".mrpack");
            java.io.File file = chooser.showSaveDialog(primaryStage);
            if (file == null) return;
            Path out = file.toPath().getFileName().toString().toLowerCase().endsWith(".mrpack")
                    ? file.toPath() : file.toPath().resolveSibling(file.getName() + ".mrpack");

            save.setDisable(true);
            buildCombo.setDisable(true);
            nameField.setDisable(true);
            versionField.setDisable(true);
            progress.setText(tr("mrpack_export_working"));
            setModpackBusy(true);
            log("[MODPACK] Экспорт сборки «" + p.getName() + "» в " + out);
            executor.submit(() -> {
                try {
                    ModpackService.ExportReport report = modpackService.export(p.getName(), p.getVersionId(),
                            p.getModLoader(), p.getModLoaderVersion(), packName, packVersion, out, modrinthService,
                            msg -> Platform.runLater(() -> progress.setText(msg)));
                    Platform.runLater(() -> {
                        setModpackBusy(false);
                        dismiss.run();
                        for (String w : report.warnings) log("[MODPACK] " + w);
                        log("[MODPACK] Готово: " + out + " — с Modrinth " + report.hosted + ", внутри архива "
                                + report.bundled + ", " + (report.bytes / 1024) + " КБ");
                        showToast(tr("mrpack_export_done").replace("{file}", out.getFileName().toString())
                                .replace("{hosted}", String.valueOf(report.hosted))
                                .replace("{bundled}", String.valueOf(report.bundled)), "success");
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        setModpackBusy(false);
                        save.setDisable(false);
                        buildCombo.setDisable(false);
                        nameField.setDisable(false);
                        versionField.setDisable(false);
                        progress.setText(tr("mrpack_export_error") + " " + ex.getMessage());
                        log("[MODPACK] Не удалось создать .mrpack: " + ex.getMessage());
                    });
                }
            });
        });

        card.getChildren().addAll(header, body, footer);
        scrim.getChildren().add(card);
        overlayLayer.getChildren().add(scrim);
        makeModalDraggable(card);
        makeModalResizable(card);
        showOverlay(scrim);
    }

    /** Сколько файлов попадёт в пак: моды, наборы ресурсов, датапаки и шейдеры сборки. */
    private int countBuildContentFiles(String buildName) {
        int n = 0;
        for (String dir : new String[]{"mods", "resourcepacks", "datapacks", ModrinthService.SHADER_STORAGE_DIR}) {
            Path d = gameDir.resolve(dir).resolve(buildName);
            if (!Files.isDirectory(d)) continue;
            try (var list = Files.list(d)) {
                n += (int) list.filter(f -> Files.isRegularFile(f) && !f.getFileName().toString().startsWith(".")).count();
            } catch (IOException ignored) {
            }
        }
        return n;
    }

    /**
     * The "+" of a "Модпаки" search result: downloads that pack's archive from Modrinth and shows
     * the same summary the local import shows, so both entry points behave identically.
     */
    private void startModpackInstallFromModrinth(ModInfo mod, Button sourceButton) {
        if (sourceButton != null) sourceButton.setDisable(true);
        log("[MODPACK] Загрузка архива «" + mod.getTitle() + "»...");
        // Отметки модпака (25.10.2026): 38c28a8f сделал 15 поисков модпаков и ушёл, а нажимал ли он
        // «+», открывал ли окно установки и что там случилось — статистика не видела вообще.
        stat("modpack", "click src=modrinth");
        long clickAt = System.currentTimeMillis();
        // Окно сразу, а не после загрузки архива (28.10.2026): у 3aec706a 206-мегабайтный архив
        // шёл 400 секунд, и всё это время о модпаке была только полоска на кнопке «+».
        java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        ModpackFetchWindow fetch = showModpackFetchWindow(mod, () -> {
            cancelled.set(true);
            stat("modpack", "fetch_cancel after=" + (System.currentTimeMillis() - clickAt) / 1000 + "s");
            if (sourceButton != null) sourceButton.setDisable(false);
        });
        Http.Progress shown = fetch.progress.bytes();
        Http.Progress bytes = (done, total) -> {
            if (cancelled.get()) throw new java.util.concurrent.CancellationException("отменено");
            shown.update(done, total);
        };
        executor.submit(() -> {
            Path archive = null;
            try {
                // No version/loader filter: the pack decides both, and this only needs "the newest
                // published version of this project".
                String versionId = modrinthService.resolveBestVersion(mod.getProjectId(), null, null);
                if (versionId == null) throw new IOException("У этой сборки на Modrinth нет ни одной версии");
                archive = modrinthService.downloadModpackArchive(versionId,
                        msg -> Platform.runLater(() -> log("[MODPACK] " + msg)), bytes);
                if (cancelled.get()) {
                    deleteTempArchive(archive);
                    return;
                }
                ModpackService.PackInfo info = modpackService.read(archive);
                // The archive lives in %TEMP% under a generated name, so the catalogue entry the
                // player actually clicked is a far better description than that file name.
                info.sourceLabel = "Modrinth: " + mod.getTitle();
                info.fromModrinth = true;
                info.fetchSeconds = (System.currentTimeMillis() - clickAt) / 1000;
                if (info.iconUrl.isBlank()) info.iconUrl = mod.getIconUrl() == null ? "" : mod.getIconUrl();
                if (info.summary.isBlank() && mod.getDescription() != null) info.summary = mod.getDescription();
                Path ready = archive;
                Platform.runLater(() -> {
                    fetch.close.run();
                    if (sourceButton != null) sourceButton.setDisable(false);
                    if (cancelled.get()) {
                        deleteTempArchive(ready);
                        return;
                    }
                    openModpackSummary(info, ready, true);
                });
            } catch (Exception ex) {
                deleteTempArchive(archive);
                if (cancelled.get()) return;   // сам нажал «Отмена» — окно уже закрыто
                stat("modpack", "dl_fail exc=" + ex.getClass().getSimpleName());
                Platform.runLater(() -> {
                    fetch.close.run();
                    if (sourceButton != null) sourceButton.setDisable(false);
                    log("[MODPACK] Не удалось получить сборку: " + ex.getMessage());
                    showToast("Не удалось скачать сборку: " + ex.getMessage(), "warning");
                });
            }
        });
    }

    private record ModpackFetchWindow(FxProgress progress, Runnable close) {}

    /** Окно «Скачиваю модпак»: название, иконка, шкала в байтах и «Отмена» (onCancel вызывается один раз). */
    private ModpackFetchWindow showModpackFetchWindow(ModInfo mod, Runnable onCancel) {
        StackPane scrim = new StackPane();
        scrim.getStyleClass().add("modal-scrim");
        VBox card = new VBox(0);
        card.getStyleClass().add("modal-card");
        card.setPrefWidth(520);
        card.setMaxWidth(520);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setPickOnBounds(true);

        HBox header = new HBox();
        header.getStyleClass().add("modal-header");
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("Модпак с Modrinth");
        title.getStyleClass().add("modal-title");
        header.getChildren().add(title);

        Label packName = new Label(mod.getTitle());
        packName.getStyleClass().add("panel-title");
        packName.setWrapText(true);
        VBox nameBox = new VBox(4, packName);
        if (mod.getDescription() != null && !mod.getDescription().isBlank()) {
            Label summary = new Label(mod.getDescription());
            summary.getStyleClass().add("page-sub");
            summary.setWrapText(true);
            nameBox.getChildren().add(summary);
        }
        HBox.setHgrow(nameBox, Priority.ALWAYS);
        HBox titleRow = new HBox(12, buildModpackIcon(mod.getIconUrl() == null ? "" : mod.getIconUrl()), nameBox);
        titleRow.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label("Скачиваю архив сборки — после этого покажу, что в ней, и спрошу, ставить ли. "
                + "Большие модпаки на медленном интернете качаются несколько минут.");
        hint.getStyleClass().add("toggle-sub");
        hint.setWrapText(true);
        ProgressBar bar = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
        bar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(bar, Priority.ALWAYS);
        Label numbers = new Label("");
        numbers.getStyleClass().addAll("toggle-sub", "mono");
        numbers.setMinWidth(Region.USE_PREF_SIZE);
        HBox barRow = new HBox(8, bar, numbers);
        barRow.setAlignment(Pos.CENTER_LEFT);
        VBox body = new VBox(12, titleRow, hint, barRow);
        body.setPadding(new javafx.geometry.Insets(16));

        Button cancel = new Button("Отмена");
        cancel.getStyleClass().add("copy-btn");
        HBox footer = new HBox(cancel);
        footer.setPadding(new javafx.geometry.Insets(0, 16, 16, 16));
        footer.setAlignment(Pos.CENTER_RIGHT);

        card.getChildren().addAll(header, body, footer);
        scrim.getChildren().add(card);
        overlayLayer.getChildren().add(scrim);
        showOverlay(scrim);
        Runnable close = () -> {
            if (!overlayLayer.getChildren().remove(scrim)) return;
            hideOverlay(scrim);
        };
        cancel.setOnAction(e -> {
            close.run();
            onCancel.run();
        });
        return new ModpackFetchWindow(new FxProgress(bar, numbers, FxProgress.Style.BYTES), close);
    }

    /**
     * Shows what the pack contains and asks for the new build's name before anything is written.
     *
     * @param temporary true when {@code archive} is a copy fetched into the temp folder, which is
     *                  deleted once this dialog is done with it; false for a file the player picked
     *                  themselves, which is never touched.
     */
    private void openModpackSummary(ModpackService.PackInfo info, Path archive, boolean temporary) {
        stat("modpack", "open src=" + (info.fromModrinth ? "modrinth" : "file") + " mc=" + info.mcVersion
                + " loader=" + info.loader + " mods=" + info.modCount + " mb=" + (info.downloadBytes >> 20)
                + (info.loaderSupported ? "" : " unsupported=1") + (info.fromModrinth ? " fetch=" + info.fetchSeconds + "s" : ""));
        long openedAt = System.currentTimeMillis();
        StackPane scrim = new StackPane();
        scrim.getStyleClass().add("modal-scrim");

        VBox card = new VBox(0);
        card.getStyleClass().add("modal-card");
        card.setMinWidth(420);
        card.setMinHeight(320);
        card.setPrefWidth(640);
        card.setPrefHeight(480);
        card.setMaxWidth(640);
        card.setMaxHeight(480);
        card.setPickOnBounds(true);

        HBox header = new HBox();
        header.getStyleClass().add("modal-header");
        header.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("Сборка (.mrpack)");
        title.getStyleClass().add("modal-title");
        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        Button close = new Button();
        close.getStyleClass().add("modal-close");
        close.setGraphic(new FontIcon("fth-x"));
        header.getChildren().addAll(title, headerSpacer, close);

        VBox body = new VBox(10);
        body.setPadding(new javafx.geometry.Insets(16));

        Label packName = new Label(info.name);
        packName.getStyleClass().add("panel-title");
        packName.setWrapText(true);
        VBox nameBox = new VBox(4, packName);
        if (info.summary != null && !info.summary.isBlank()) {
            Label summary = new Label(info.summary);
            summary.getStyleClass().add("page-sub");
            summary.setWrapText(true);
            nameBox.getChildren().add(summary);
        }
        HBox.setHgrow(nameBox, Priority.ALWAYS);
        HBox titleRow = new HBox(12, buildModpackIcon(info.iconUrl), nameBox);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        body.getChildren().add(titleRow);

        VBox facts = new VBox(4);
        facts.getChildren().addAll(
                modpackFact("Minecraft", info.mcVersion),
                modpackFact("Загрузчик", info.loaderLabel()));
        if (info.packVersion != null && !info.packVersion.isBlank()) {
            facts.getChildren().add(modpackFact("Версия сборки", info.packVersion));
        }
        facts.getChildren().add(modpackFact("Модов", String.valueOf(info.modCount)));
        String size = info.sizeLabel();
        facts.getChildren().add(modpackFact("Файлов к загрузке",
                info.fileCount + (size.isBlank() ? "" : " (" + size + ")")));
        if (info.overrideCount > 0) {
            facts.getChildren().add(modpackFact("Настроек внутри", String.valueOf(info.overrideCount)));
        }
        if (info.serverOnlyCount > 0) {
            facts.getChildren().add(modpackFact("Только для сервера",
                    info.serverOnlyCount + " — пропустятся"));
        }
        if (info.sourceLabel != null && !info.sourceLabel.isBlank()) {
            facts.getChildren().add(modpackFact("Источник", info.sourceLabel));
        }
        body.getChildren().add(facts);

        // No "⚠" here on purpose: the bundled font faces carry Latin, Cyrillic and box drawing
        // only, so a symbol would render as a blank box (the same reason the checkmarks elsewhere
        // are FontIcons rather than characters).
        for (String warning : info.warnings) {
            Label warn = new Label("Внимание: " + warning);
            warn.getStyleClass().add("server-sub");
            warn.setWrapText(true);
            body.getChildren().add(warn);
        }

        Label nameLabel = new Label("Название сборки в лаунчере");
        nameLabel.getStyleClass().add("select-label");
        TextField nameField = new TextField(uniqueBuildName(info.name));
        nameField.getStyleClass().add("text-input");
        VBox.setMargin(nameLabel, new javafx.geometry.Insets(8, 0, 0, 0));
        body.getChildren().addAll(nameLabel, nameField);

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        Label progress = new Label("");
        progress.getStyleClass().add("toggle-sub");
        progress.setWrapText(true);
        // Шкала на всю сборку (14.10.2026): у пака на сотни модов одна строка «Скачивание
        // (37/412)» не говорила, сколько ещё ждать — файлы бывают и по 20 КБ, и по 50 МБ.
        ProgressBar packBar = new ProgressBar(0);
        packBar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(packBar, Priority.ALWAYS);
        Label packNumbers = new Label("");
        packNumbers.getStyleClass().addAll("toggle-sub", "mono");
        packNumbers.setMinWidth(Region.USE_PREF_SIZE);
        HBox packBarRow = new HBox(8, packBar, packNumbers);
        packBarRow.setAlignment(Pos.CENTER_LEFT);
        packBarRow.setVisible(false);
        packBarRow.setManaged(false);
        FxProgress packProgress = new FxProgress(packBar, packNumbers, FxProgress.Style.BYTES);
        VBox progressBox = new VBox(6, progress, packBarRow);
        progressBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(progressBox, Priority.ALWAYS);
        Button cancel = new Button("Отмена");
        cancel.getStyleClass().add("copy-btn");
        Button install = new Button("Установить");
        install.getStyleClass().add("server-action");
        HBox footer = new HBox(10, progressBox, cancel, install);
        footer.setPadding(new javafx.geometry.Insets(12, 16, 16, 16));
        footer.setAlignment(Pos.CENTER_RIGHT);

        Runnable dismiss = () -> {
            overlayLayer.getChildren().remove(scrim);
            hideOverlay(scrim);
            if (temporary) deleteTempArchive(archive);
        };
        Runnable declined = () -> {
            stat("modpack", "cancel after=" + (System.currentTimeMillis() - openedAt) / 1000 + "s");
            dismiss.run();
        };
        close.setOnAction(e -> declined.run());
        cancel.setOnAction(e -> declined.run());

        install.setOnAction(e -> {
            long installAt = System.currentTimeMillis();
            String buildName = uniqueBuildName(nameField.getText());
            final Profile createdProfile = createBuildFromPack(info, buildName);
            install.setDisable(true);
            cancel.setDisable(true);
            nameField.setDisable(true);
            progress.setText("Установка...");
            packBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);   // пока читается архив
            packNumbers.setText("");
            packBarRow.setVisible(true);
            packBarRow.setManaged(true);
            log("[MODPACK] Установка «" + info.name + "» в сборку «" + buildName + "»...");
            executor.submit(() -> {
                try {
                    ModpackService.InstallReport report = modpackService.install(archive, buildName,
                            msg -> Platform.runLater(() -> progress.setText(msg)),
                            packProgress.bytes());
                    recordInstalledPackMods(buildName, report);
                    stat("modpack", "ok got=" + report.downloaded + " had=" + report.skippedExisting
                            + " failed=" + report.failures.size() + " sec=" + (System.currentTimeMillis() - installAt) / 1000);
                    Platform.runLater(() -> {
                        dismiss.run();
                        log("[MODPACK] Скачано: " + report.downloaded + ", уже было: "
                                + report.skippedExisting + ", настроек: " + report.overridesApplied);
                        reportPackFailures(report);
                        showToast("Сборка «" + buildName + "» установлена", "success");
                    });
                } catch (Exception ex) {
                    stat("modpack", "fail exc=" + ex.getClass().getSimpleName()
                            + " sec=" + (System.currentTimeMillis() - installAt) / 1000);
                    Platform.runLater(() -> {
                        // Откатываем созданную сборку. Раньше она оставалась — пустая, но
                        // занимающая имя, — и повторное нажатие «Установить» получало от
                        // uniqueBuildName уже «SkyFactory (2)», ставило файлы в
                        // mods/SkyFactory (2)/, а первая так и висела на диске и в списке.
                        // Каждый повтор добавлял ещё одну.
                        removeFailedPackBuild(createdProfile);
                        packBarRow.setVisible(false);
                        packBarRow.setManaged(false);
                        install.setDisable(false);
                        cancel.setDisable(false);
                        nameField.setDisable(false);
                        progress.setText("Ошибка: " + ex.getMessage());
                        log("[MODPACK] Не удалось установить сборку: " + ex.getMessage());
                    });
                }
            });
        });

        card.getChildren().addAll(header, scroll, footer);
        scrim.getChildren().add(card);
        overlayLayer.getChildren().add(scrim);
        makeModalDraggable(card);
        makeModalResizable(card);
        showOverlay(scrim);
    }

    /**
     * 48px pack icon for the summary card. Loaded through IconLoader on the background executor
     * for the same reason search-result icons are (Modrinth mostly serves WebP, which JavaFX's own
     * image loader cannot decode); a missing URL or a failed download just leaves the blank
     * placeholder that is already in the layout.
     */
    private Node buildModpackIcon(String iconUrl) {
        Region placeholder = new Region();
        placeholder.getStyleClass().add("build-icon-sm");
        placeholder.setMinSize(48, 48);
        placeholder.setPrefSize(48, 48);
        placeholder.setMaxSize(48, 48);
        if (iconUrl == null || iconUrl.isBlank()) return placeholder;

        StackPane holder = new StackPane(placeholder);
        holder.setMinSize(48, 48);
        holder.setMaxSize(48, 48);
        executor.submit(() -> {
            Image img = com.example.launcher.ui.IconLoader.load(iconUrl);
            if (img != null) {
                Platform.runLater(() -> {
                    ImageView iv = new ImageView(img);
                    iv.setFitWidth(48);
                    iv.setFitHeight(48);
                    iv.setPreserveRatio(true);
                    holder.getChildren().setAll(iv);
                });
            }
        });
        return holder;
    }

    /** One "label — value" line of the pack summary above. */
    private HBox modpackFact(String label, String value) {
        Label name = new Label(label);
        name.getStyleClass().add("toggle-sub");
        name.setMinWidth(150);
        Label val = new Label(value == null || value.isBlank() ? "—" : value);
        val.getStyleClass().add("mono");
        val.setWrapText(true);
        HBox row = new HBox(8, name, val);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /**
     * Creates and saves the build a pack is installed into, taking the pack's own Minecraft
     * version and loader. A loader BLauncher cannot install (Quilt/LiteLoader) leaves the build
     * vanilla: the files still get installed, and the summary the player just confirmed said so -
     * claiming "fabric" for a Quilt pack would only produce a crash the launcher can't explain.
     */
    /**
     * Убирает сборку, созданную под установку модпака, когда та не удалась: из списка, с диска
     * и из выбора. Файлы модов не трогает — то, что успело скачаться, остаётся в
     * {@code mods/<имя>}, и повторная установка это переиспользует.
     */
    private void removeFailedPackBuild(Profile profile) {
        if (profile == null) return;
        builds.remove(profile);
        if (selectedBuild == profile) selectedBuild = null;
        try {
            profileService.deleteProfile(profile.getName());
        } catch (IOException e) {
            log("[MODPACK] Не удалось убрать неудавшуюся сборку: " + e.getMessage());
        }
        renderHomeBuildGrid();
        renderBuildsGrid();
        renderCurrentBuildPanel();
        refreshServerBuildCombo();
    }

    private Profile createBuildFromPack(ModpackService.PackInfo info, String buildName) {
        boolean supported = info.loaderSupported;
        Profile profile = new Profile(buildName, usernameField.getText(), "", info.mcVersion,
                supported ? info.loader : "vanilla");
        profile.setModLoaderVersion(supported ? info.loaderVersion : null);
        profile.setIconText(buildName.substring(0, Math.min(2, buildName.length())).toUpperCase());
        profile.setIconGradient(randomCardGradient());
        profile.setLiked(true);   // видна на главной, см. onConfirmCreateBuild
        builds.add(profile);
        try {
            profileService.saveProfile(profile);
        } catch (IOException e) {
            log("[BUILDS] Не удалось сохранить сборку: " + e.getMessage());
        }
        selectedBuild = profile;
        selectedServer = null;
        renderHomeBuildGrid();
        renderBuildsGrid();
        renderCurrentBuildPanel();
        refreshServerBuildCombo();
        return profile;
    }

    /**
     * A build's mods folder is keyed by the build's name (mods/&lt;name&gt;, see ModrinthService),
     * so two builds may never share one - and a pack's name is arbitrary text from the internet,
     * which on Windows may contain characters a folder name cannot ("/" in "SkyFactory 4 / Lite").
     */
    private String uniqueBuildName(String requested) {
        String base = requested == null ? "" : requested.trim().replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (base.isEmpty()) base = "Сборка Modrinth";
        if (base.length() > 60) base = base.substring(0, 60).trim();

        LinkedHashSet<String> taken = new LinkedHashSet<>();
        for (Profile existing : builds) {
            if (existing.getName() != null) taken.add(existing.getName().toLowerCase(Locale.ROOT));
        }
        if (!taken.contains(base.toLowerCase(Locale.ROOT))) return base;
        for (int i = 2; ; i++) {
            String candidate = base + " (" + i + ")";
            if (!taken.contains(candidate.toLowerCase(Locale.ROOT))) return candidate;
        }
    }

    /**
     * Carries the Modrinth project ids a pack's manifest revealed (they are embedded in each
     * file's download URL) into the installed-mods metadata, so the Mod Manager keeps knowing what
     * the pack's jars actually are - the manifest carries no titles, so those stay unset and the
     * list falls back to the file names.
     */
    private void recordInstalledPackMods(String buildName, ModpackService.InstallReport report) {
        if (report.modProjectIds.isEmpty()) return;
        Map<String, ModrinthService.InstalledMeta> meta = new HashMap<>();
        for (Map.Entry<String, String> entry : report.modProjectIds.entrySet()) {
            ModrinthService.InstalledMeta value = new ModrinthService.InstalledMeta();
            value.projectId = entry.getValue();
            meta.put(entry.getKey(), value);
        }
        modrinthService.recordInstalledMetaBatch("mod", buildName, meta);
    }

    /** A pack that installed "mostly fine" is still worth reporting precisely: the player needs to
     *  know which mods are missing (and why) before the game fails to find them. */
    private void reportPackFailures(ModpackService.InstallReport report) {
        if (report.failures.isEmpty()) return;
        int shown = 0;
        for (Map.Entry<String, String> failure : report.failures.entrySet()) {
            if (shown++ >= 5) break;
            log("[MODPACK] Не установлено: " + failure.getKey() + " — " + failure.getValue());
        }
        if (report.failures.size() > shown) {
            log("[MODPACK] ...и ещё " + (report.failures.size() - shown) + " файл(ов), см. лог.");
        }
        showToast("Не удалось скачать " + report.failures.size() + " файл(ов) сборки — см. лог", "warning");
    }

    private void deleteTempArchive(Path archive) {
        if (archive == null) return;
        try {
            Files.deleteIfExists(archive);
        } catch (IOException ignored) {
            // %TEMP% is cleaned by the OS; a leftover pack there is not worth an error message.
        }
    }

    // ============================================================
    //  Servers — local (GameLaunchService, same dedicated-server flow
    //  as before) + VPS/agent-managed (AgentClient/AgentPoller, new).
    //  See ServerEntry for the shared data model and AGENT_API.md for
    //  the VPS agent's HTTP contract.
    // ============================================================

    private void loadServers() {
        servers.clear();
        try {
            servers.addAll(serverService.loadAll());
        } catch (IOException e) {
            log("[SERVERS] Не удалось загрузить список серверов: " + e.getMessage());
        }
    }

    private void saveServers() {
        try {
            serverService.saveAll(servers);
        } catch (IOException e) {
            log("[SERVERS] Не удалось сохранить список серверов: " + e.getMessage());
        }
    }

    private ServerEntry findServer(String id) {
        if (id == null) return null;
        for (ServerEntry s : servers) {
            if (s.getId().equals(id)) return s;
        }
        return null;
    }

    private List<ServerEntry> hubs() {
        List<ServerEntry> result = new ArrayList<>();
        for (ServerEntry s : servers) {
            if (s.getNetwork() != null && s.getNetwork().isHub()) result.add(s);
        }
        return result;
    }

    private List<ServerEntry> satellitesOf(String hubId) {
        List<ServerEntry> result = new ArrayList<>();
        for (ServerEntry s : servers) {
            if (s.getNetwork() != null && s.getNetwork().isSatellite() && hubId.equals(s.getNetwork().getHubId())) {
                result.add(s);
            }
        }
        return result;
    }

    private AgentClient agentClientFor(ServerEntry s) {
        return agentClients.computeIfAbsent(s.getId(),
                id -> new AgentClient(s.getHost(), s.getAgentPort(), s.getAgentKey(), s.getAgentFingerprint()));
    }

    // ---------- "Server" quick screen ----------

    private void initServerScreens() {
        serverVanillaVersionCombo.setItems(FXCollections.observableArrayList(versionListCache));
        versionCombosToRefresh.add(serverVanillaVersionCombo);
        serverVanillaLoaderCombo.setItems(FXCollections.observableArrayList(
                "Vanilla", "Forge", "Fabric", "NeoForge", "Quilt", "Paper", "Purpur", "Pufferfish"));
        fitComboToItemCount(serverVanillaLoaderCombo);

        // Restores whatever version/loader was last picked here specifically — see
        // LauncherConfig.lastSelectedServerVersion's javadoc for why this is a separate saved
        // value from Home's own vanilla picker rather than sharing it. Falls back to the first
        // item only when nothing was ever saved (or the saved version got removed upstream).
        restoringVanillaCombos = true;
        try {
            String savedServerVersion = config.getLastSelectedServerVersion();
            if (savedServerVersion != null && !savedServerVersion.isBlank()
                    && serverVanillaVersionCombo.getItems().contains(savedServerVersion)) {
                serverVanillaVersionCombo.getSelectionModel().select(savedServerVersion);
            } else if (!serverVanillaVersionCombo.getItems().isEmpty()) {
                serverVanillaVersionCombo.getSelectionModel().selectFirst();
            }
            String savedServerLoader = config.getLastSelectedServerLoader();
            if (savedServerLoader != null && !savedServerLoader.isBlank()
                    && serverVanillaLoaderCombo.getItems().contains(savedServerLoader)) {
                serverVanillaLoaderCombo.getSelectionModel().select(savedServerLoader);
            } else {
                serverVanillaLoaderCombo.getSelectionModel().selectFirst();
            }
        } finally {
            restoringVanillaCombos = false;
        }

        serverBuildCombo.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Profile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName());
            }
        });
        serverBuildCombo.setButtonCell(serverBuildCombo.getCellFactory().call(null));
    }

    private void refreshServerBuildCombo() {
        List<Profile> options = new ArrayList<>();
        Profile vanillaStub = new Profile();
        vanillaStub.setName(VANILLA_LABEL);
        vanillaStub.setVanillaEntry(true);
        options.add(vanillaStub);
        options.addAll(builds);
        serverBuildCombo.setItems(FXCollections.observableArrayList(options));
        if (serverBuildCombo.getSelectionModel().isEmpty() && !options.isEmpty()) {
            serverBuildCombo.getSelectionModel().selectFirst();
        }
        onServerBuildSelected();
    }

    /** Key for whatever's currently picked in the quick "Локальный сервер" panel's own build
     *  combo - matches the Profile/ServerEntry name GameLaunchService keys its per-server
     *  process map by (see its "DEDICATED SERVER" section). */
    private String quickPanelServerKey() {
        Profile group = serverBuildCombo.getSelectionModel().getSelectedItem();
        return group == null || VANILLA_LABEL.equals(group.getName()) ? VANILLA_LABEL : group.getName();
    }

    @FXML
    private void onServerBuildSelected() {
        Profile group = serverBuildCombo.getSelectionModel().getSelectedItem();
        boolean vanilla = group == null || VANILLA_LABEL.equals(group.getName());
        serverLockedMetaRow.setVisible(!vanilla);
        serverLockedMetaRow.setManaged(!vanilla);
        serverEditableMetaRow.setVisible(vanilla);
        serverEditableMetaRow.setManaged(vanilla);
        if (group != null && !vanilla) {
            serverVersionChip.setText(group.getVersionId());
            String loader = group.getModLoader() == null || group.getModLoader().isBlank() ? "Vanilla" : group.getModLoader();
            serverLoaderChip.setText(loader);
        }
        String key = quickPanelServerKey();
        boolean running = gameLaunchService.isServerRunning(key);
        serverStartBtn.setText(running ? tr("stop_btn") : tr("start_btn"));
        serverStatusLabel.setText(key + ": " + (running ? tr("status_running") : tr("status_not_running")));
    }

    @FXML
    private void onServerVanillaVersionSelected() {
        String selected = serverVanillaVersionCombo.getSelectionModel().getSelectedItem();
        if (!restoringVanillaCombos && selected != null && !selected.equals(config.getLastSelectedServerVersion())) {
            config.setLastSelectedServerVersion(selected);
            try { configService.saveConfig(config); } catch (Exception ignored) { }
        }
    }

    @FXML
    private void onServerVanillaLoaderSelected() {
        String selected = serverVanillaLoaderCombo.getSelectionModel().getSelectedItem();
        if (!restoringVanillaCombos && selected != null && !selected.equals(config.getLastSelectedServerLoader())) {
            config.setLastSelectedServerLoader(selected);
            try { configService.saveConfig(config); } catch (Exception ignored) { }
        }
    }

    @FXML
    private void onToggleLocalServer() {
        if (gameLaunchService.isServerRunning(quickPanelServerKey())) {
            // null = quick panel launch/stop, not tied to any particular saved ServerEntry - see
            // startLocalServer()/stopLocalServer()'s own "quickPanel" handling.
            stopLocalServer(null);
        } else {
            startLocalServer(null);
        }
    }

    /** @param entry the specific server to start, or null for the quick Server-screen panel's own
     *  build selector. Starting a saved ServerEntry MUST use its own version/loader/port — it used
     *  to always defer to the quick panel's combo instead, so starting e.g. "Survival" (26.2,
     *  Fabric, port 25566) actually launched whatever the quick panel happened to have selected
     *  (often a stale default), silently ignoring the server that was actually clicked. */
    private void startLocalServer(ServerEntry entry) {
        String name;
        String version;
        String loader;
        String username;
        String uuid;
        int minMemoryMb;
        int maxMemoryMb;
        int port;

        if (entry != null) {
            name = entry.getName();
            version = entry.getVersion();
            loader = mapDisplayLoaderToInternal(entry.getLoader());
            username = usernameField.getText();
            uuid = "";
            minMemoryMb = config.getMinMemoryMb();
            maxMemoryMb = entry.getRamMb() != null ? entry.getRamMb() : config.getMaxMemoryMb();
            port = entry.getPort();
        } else {
            Profile group = serverBuildCombo.getSelectionModel().getSelectedItem();
            if (group == null) {
                log("[SERVER] Выберите сборку перед запуском сервера.");
                return;
            }
            boolean vanilla = VANILLA_LABEL.equals(group.getName());
            version = vanilla && serverVanillaVersionCombo.getSelectionModel().getSelectedItem() != null
                    ? serverVanillaVersionCombo.getSelectionModel().getSelectedItem() : group.getVersionId();
            loader = vanilla && serverVanillaLoaderCombo.getSelectionModel().getSelectedItem() != null
                    ? mapDisplayLoaderToInternal(serverVanillaLoaderCombo.getSelectionModel().getSelectedItem())
                    : mapDisplayLoaderToInternal(group.getModLoader());
            name = group.getName();
            username = group.getUsername();
            uuid = group.getUuid();
            minMemoryMb = group.getMinMemoryMb();
            maxMemoryMb = group.getMaxMemoryMb();
            port = nextLocalPort();
        }

        String finalName = name;
        // The quick "Локальный сервер" panel's own status label/spinner is a SINGLE widget on
        // the Server screen - it must only reflect a launch started from that same panel
        // (entry == null). Touching it for a specific named ServerEntry too used to make the
        // loading indicator appear to show up "globally" (next to the quick panel) in addition
        // to the actual server's own row - the row's spinner (via entry.setPending() below) is
        // the only indicator a named server's launch should ever drive.
        boolean quickPanel = entry == null;
        if (quickPanel) {
            serverStatusLabel.setText(name + ": " + tr("status_starting"));
            serverStartSpinner.setVisible(true);
            serverStartSpinner.setManaged(true);
        }
        // Marks this row as "starting" immediately (same field the VPS/agent path already uses
        // for its own pending state) so the Мои серверы list shows a spinner next to it right
        // away instead of only after install+launch finish, which for a fresh install (loader
        // download, world gen) can take a while with no feedback in that list at all.
        if (entry != null) {
            entry.setPending("Запускается...");
            renderMyServersQuickList();
            renderServerAdvancedList();
        }
        log("[SERVER] Запуск сервера для \"" + name + "\" (" + version + " " + loader + ", порт " + port + ")...");

        executor.submit(() -> {
            try {
                // launchServer() itself no-ops (just logs "Already running") if this name is
                // already up rather than calling onReady/onExit - checked here too so a race
                // (e.g. the quick panel and a saved entry sharing a name, or a stray double
                // trigger) can't leave serverStartSpinner/entry.getPending() stuck forever with
                // no callback left to ever clear them.
                if (gameLaunchService.isServerRunning(name)) {
                    log("[SERVER] \"" + name + "\" уже запущен.");
                    Platform.runLater(() -> {
                        if (quickPanel) {
                            serverStartSpinner.setVisible(false);
                            serverStartSpinner.setManaged(false);
                        }
                        if (entry != null) entry.setPending(null);
                        renderMyServersQuickList();
                        renderServerAdvancedList();
                    });
                    return;
                }

                Profile serverProfile = new Profile(name, username, uuid, version, loader);
                serverProfile.setMinMemoryMb(minMemoryMb);
                serverProfile.setMaxMemoryMb(maxMemoryMb);

                // A server-only setup never triggers a client download, so unlike launchGame()'s
                // path this version's manifest json may not exist on disk yet - without it,
                // selectJavaForVersion() can't read the real "javaVersion.majorVersion" Mojang
                // requires and falls back to a hardcoded range table that doesn't know about
                // newer/year-versioned MC releases, silently picking too-old a Java (e.g. Java 17
                // when the version actually needs 25) and crashing with
                // UnsupportedClassVersionError the moment the server jar loads. getServerJarUrl()
                // fetches+caches that manifest as a side effect if it's missing - cheap/no-op if
                // it's already there - so this just needs calling before selectJavaForVersion.
                try {
                    versionDownloadService.getServerJarUrl(version);
                } catch (Exception ignored) {
                    // Best-effort - if this fails, selectJavaForVersion() below just falls back
                    // to its existing hardcoded-range behavior exactly as before this change.
                }
                String serverJavaPath = selectJavaForVersion(version);
                if (serverJavaPath != null) {
                    serverProfile.setJavaPath(serverJavaPath);
                    // installServerLoader() below (Forge/NeoForge/Fabric) runs the loader's own
                    // installer via ModLoaderService.resolveJavaExe(), which - same as
                    // GameLaunchService - falls back to this process's own bundled java.home if
                    // nothing overrides it. In the packaged app-image build that's a jlink-trimmed
                    // runtime with no bin/java.exe at all, so without this the installer can fail
                    // to even launch. The client path already sets this same override before its
                    // own installLoader() call; the server path needs it too.
                    modLoaderService.overrideJavaPath(serverJavaPath);
                }

                // Same gap as the client path (see resolveLatestLoaderVersion's own javadoc):
                // there's nowhere in the UI yet to pin a specific loader build for a server, so
                // serverProfile.getModLoaderVersion() is always blank here - without resolving a
                // real version first, installServerLoader() below builds a download URL with an
                // empty version segment (e.g. ".../forge-1.20.1--installer.jar") and 404s.
                // Custom jar cores (Paper/Purpur/Pufferfish) download their latest build
                // directly and don't need a separate loader-version lookup.
                boolean needsLoaderVersion = !"vanilla".equals(loader)
                        && !"paper".equals(loader) && !"purpur".equals(loader) && !"pufferfish".equals(loader);
                if (needsLoaderVersion) {
                    log("[SERVER] Определяем последнюю версию " + loader + " для MC " + version + "...");
                    String resolvedLoaderVersion = resolveLatestLoaderVersion(version, loader);
                    if (resolvedLoaderVersion == null) {
                        String reason = "neoforge".equals(loader)
                                ? versionDownloadService.getLastNeoForgeListError() : null;
                        log("[ERROR] Не удалось найти сборку " + loader + " для MC " + version
                                + " - сервер не будет запущен."
                                + (reason != null && !reason.isBlank() ? " Причина: " + reason : ""));
                        throw new Exception("Нет доступной версии " + loader + " для MC " + version
                                + (reason != null && !reason.isBlank() ? " (" + reason + ")" : ""));
                    }
                    serverProfile.setModLoaderVersion(resolvedLoaderVersion);
                }

                gameLaunchService.launchServer(serverProfile, port,
                        output -> Platform.runLater(() -> {
                            log("[SERVER] " + output);
                            appendFocusedConsoleLineIfLocal(finalName, "server", output);
                            java.util.regex.Matcher joined = SERVER_JOINED.matcher(output);
                            if (joined.find()) notifyIfAway("«" + finalName + "»: зашёл " + joined.group(1), "Игрок на вашем сервере", false);
                        }),
                        () -> Platform.runLater(() -> {
                            // The dedicated server is reachable from ZeroTier-room friends on its
                            // VPN IP only if Windows Firewall lets the port in - the Minecraft
                            // java process gets no automatic rule on a virtual adapter. Best-effort
                            // (elevated, one UAC), scoped to ZeroTier's address ranges only.
                            openServerPortForNetwork(port);
                            if (quickPanel) {
                                serverStatusLabel.setText(finalName + ": " + tr("status_running"));
                                serverStartSpinner.setVisible(false);
                                serverStartSpinner.setManaged(false);
                                serverStartBtn.setText(tr("stop_btn"));
                            }
                            if (entry != null) entry.setPending(null);
                            renderMyServersQuickList();
                            renderServerAdvancedList();
                            if (entry != null && entry.getId().equals(focusedServerId)) {
                                renderServerDetailHeader();
                                renderFriendAddress();
                            }
                        }),
                        exitCode -> Platform.runLater(() -> {
                            // Остановили сами — у записи стоит «Останавливается...»; иначе ненулевой
                            // код значит, что сервер упал.
                            boolean stopping = entry != null && entry.getPending() != null && entry.getPending().startsWith("Останавлива");
                            if (exitCode != 0 && !stopping) {
                                notifyIfAway("Сервер «" + finalName + "» упал", "Код выхода " + exitCode + ". Причина — в консоли сервера.", true);
                            }
                            if (quickPanel) {
                                serverStatusLabel.setText(finalName + ": " + tr("status_not_running"));
                                serverStartSpinner.setVisible(false);
                                serverStartSpinner.setManaged(false);
                                serverStartBtn.setText(tr("start_btn"));
                            }
                            if (entry != null) entry.setPending(null);
                            renderMyServersQuickList();
                            renderServerAdvancedList();
                            if (entry != null && entry.getId().equals(focusedServerId)) {
                                renderServerDetailHeader();
                                renderFriendAddress();
                            }
                        }));
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    log("[ERROR] Не удалось запустить сервер: " + ex.getMessage());
                    if (quickPanel) {
                        serverStatusLabel.setText(finalName + ": ошибка");
                        serverStartSpinner.setVisible(false);
                        serverStartSpinner.setManaged(false);
                    }
                    if (entry != null) entry.setPending(null);
                    renderMyServersQuickList();
                    renderServerAdvancedList();
                });
            }
        });
    }

    /** @param entry the specific server to stop, or null for the quick "Локальный сервер"
     *  panel's own build selector - mirrors startLocalServer()'s same parameter. Each
     *  differently-named local server is its own independent process (GameLaunchService's
     *  "DEDICATED SERVER" section), so this stops only the one identified by entry/the panel's
     *  current selection, not "the" local server. */
    private void stopLocalServer(ServerEntry entry) {
        String serverKey = entry != null ? entry.getName() : quickPanelServerKey();
        boolean quickPanel = entry == null;
        log("[SERVER] Остановка сервера \"" + serverKey + "\"...");
        if (quickPanel) {
            serverStatusLabel.setText(serverKey + ": " + tr("status_stopping"));
        }
        if (entry != null) {
            entry.setPending("Останавливается...");
            renderMyServersQuickList();
            renderServerAdvancedList();
        }
        executor.submit(() -> {
            gameLaunchService.stopServer(serverKey);
            Platform.runLater(() -> {
                if (quickPanel) {
                    serverStatusLabel.setText(serverKey + ": " + tr("status_not_running"));
                    serverStartBtn.setText(tr("start_btn"));
                }
                if (entry != null) entry.setPending(null);
                renderMyServersQuickList();
                renderServerAdvancedList();
                ServerEntry focused = findServer(focusedServerId);
                if (focused != null && focused.isLocal() && serverKey.equals(focused.getName())) {
                    renderServerDetailHeader();
                    renderFriendAddress();
                }
            });
        });
    }

    /** If screenServerDetail is currently open on a LOCAL server, mirrors a log line into its console too. */
    private void appendFocusedConsoleLineIfLocal(String serverName, String level, String text) {
        ServerEntry focused = findServer(focusedServerId);
        if (focused != null && focused.isLocal() && focused.getName().equals(serverName)) {
            // Same unbounded-TextArea freeze risk as log()/gameConsoleOutput - a dedicated
            // server can easily stream output for hours while its detail screen sits open.
            appendCapped(consoleArea, "[" + TIME_FMT.format(Instant.now()) + "] " + text + "\n");
        }
    }

    private void renderMyServersQuickList() {
        myServersQuickList.getChildren().clear();
        for (ServerEntry s : servers) {
            myServersQuickList.getChildren().add(buildServerRowNode(s, false));
        }
        renderVpsCardStatus();
    }

    /** Подсказка под блоком «Сервер на VPS»: где уже подключённые VPS и как перенести локальный. */
    private void renderVpsCardStatus() {
        if (vpsCardStatus == null) return;
        int vps = 0;
        int local = 0;
        for (ServerEntry s : servers) {
            if (s.isSsh()) vps++;
            else if (s.isLocal()) local++;
        }
        String text = null;
        if (vps > 0) {
            text = tr("vps_card_has").replace("{n}", String.valueOf(vps));
        } else if (local > 0) {
            text = tr("vps_card_move_hint");
        }
        vpsCardStatus.setText(text == null ? "" : text);
        vpsCardStatus.setVisible(text != null);
        vpsCardStatus.setManaged(text != null);
    }

    /** «Подключить VPS» с главного экрана «Сервер»: окно создания сразу на вкладке VPS. */
    @FXML
    private void onOpenVpsCreate() {
        onOpenCreateServer();
        onServerTypeTabVps();
    }

    // ---------- Аренда VPS у serv.host (17.10.2026) ----------
    // Покупка — только на сайте хостинга в системном браузере (не во встроенном WebView: там
    // человек вводил бы данные карты в окне лаунчера). Лаунчер ничего не знает об аккаунте
    // serv.host; после покупки — обычная форма панели VPS. Тексты — rent_* в strings_*.properties.
    // С 26.10.2026 тариф выбирают здесь же (ui/RentPicker), а «Оплатить» открывает кабинет.

    /** Вкладка «Купить сервер» в рейле, между «Друзья» и «Сервер». */
    @FXML
    private void onNavRent() {
        goToView("rent");
    }

    private RentPicker ensureRentPicker() {
        if (rentPicker == null) {
            rentPicker = new RentPicker(new RentPicker.Host() {
                @Override public String tr(String key) { return MainController.this.tr(key); }
                @Override public void runInBackground(Runnable task) { executor.submit(task); }
                @Override public void pay(ServHostRent.Plan plan, ServHostRent.Location location, ServHostRent.Cpu cpu) {
                    onRentPay(plan, location, cpu);
                }
            });
            rentPickerBox.getChildren().setAll(rentPicker.node());
        }
        return rentPicker;
    }

    /**
     * «Оплатить»: кабинет serv.host с партнёрской меткой. Выбранный тариф кабинет по ссылке пока
     * не открывает (см. ServHostRent.orderUrl), поэтому его название — в буфер и в шаг 2.
     * plan == null — цены не загрузились, человек просто идёт на сайт.
     */
    private void onRentPay(ServHostRent.Plan plan, ServHostRent.Location location, ServHostRent.Cpu cpu) {
        stat("rent_open", plan == null ? "" : "plan=" + plan.id() + " dc=" + cpu.dc());
        rentLastUrl = plan == null ? ServHostRent.url() : ServHostRent.orderUrl(plan, cpu);
        browseRentSite(rentLastUrl);
        if (plan != null) {
            copyToClipboard(plan.name());
            rentStep2Label.setText(tr("rent_step2_plan")
                    .replace("{plan}", plan.name())
                    .replace("{loc}", rentPicker.dcLabel(location, cpu)));
            showToast(tr("rent_copied").replace("{plan}", plan.name()), "info");
        }
        show(rentOpenedLabel, true);
        show(rentReopenLink, true);
    }

    /** «Открыть serv.host ещё раз» — в счётчик не идёт, это то же намерение. */
    @FXML
    private void onRentReopen() {
        browseRentSite(rentLastUrl != null ? rentLastUrl : ServHostRent.url());
    }

    /** «Ещё нет VPS? Арендовать» из формы VPS: закрыть окно и перейти на вкладку «Купить сервер». */
    @FXML
    private void onRentPromo() {
        if (vpsPanelController.isBusy()) return;
        hideOverlay(createServerOverlay);
        onNavRent();
    }

    /** «Я купил — подключить сервер»: существующая форма VPS + подсказка, где взять данные. */
    @FXML
    private void onRentBought() {
        onOpenVpsCreate();
        vpsPanelController.setRentSource(ServHostRent.SOURCE);
        show(rentPromoBox, false);
        show(rentConnectHint, true);
    }

    private static void show(Node node, boolean on) {
        node.setVisible(on);
        node.setManaged(on);
    }

    private void browseRentSite(String url) {
        // browse() может подвиснуть на пару секунд, пока стартует браузер — не в UI-потоке.
        executor.submit(() -> {
            try {
                Os.browse(url);
            } catch (Exception e) {
                Platform.runLater(() -> showToast(tr("rent_browser_fail").replace("{url}", url), "info"));
            }
        });
    }

    // ---------- Play together (ZeroTier overlay network) ----------

    @FXML
    private void onZeroTierRefresh() {
        refreshZeroTierPanel(true);
    }

    /** Включает/выключает ZeroTier вручную. До нажатия «Включить» не запускаем движок, не
     *  поднимаем телефонное/Android обнаружение и не даём входить/создавать комнаты. */
    @FXML
    private void onZeroTierPowerToggle() {
        if (zeroTierService == null || ztPowerBtn == null) return;
        if (zeroTierPowerOn) {
            zeroTierPowerOn = false;
            ztPowerBtn.setText(tr("zt_enable_btn"));
            stopZtInfoServerIfNeeded();
            ztStatusLabel.setText("ZeroTier выключен");
            ztJoinBtn.setDisable(true);
            ztCreateBtn.setDisable(true);
            // Выход из комнат — тоже сетевые вызовы к движку; пока они идут, повторное нажатие
            // запустило бы включение параллельно выключению.
            ztPowerBtn.setDisable(true);
            ztPowerBusy = true;
            executor.submit(() -> {
                try {
                    zeroTierService.leaveAllRooms(this::logZeroTierOutput);
                } catch (Exception ignored) {
                } finally {
                    ztPowerBusy = false;
                    Platform.runLater(() -> ztPowerBtn.setDisable(false));
                }
                Platform.runLater(() -> refreshZeroTierPanel(true));
            });
            return;
        }

        zeroTierPowerOn = true;
        ztPowerBtn.setText(tr("zt_disable_btn"));
        ztPowerBtn.setDisable(true);
        // Пока идёт включение, поллер не должен оживлять кнопку (см. refreshZeroTierPanel).
        ztPowerBusy = true;
        executor.submit(() -> {
            try {
                // Уже установлен/запущен — просто включаем управление. Если нет — поднимаем так же,
                // как это делает кнопка установки/запуска службы (с UAC при необходимости).
                if (!zeroTierService.isInstalled()) {
                    zeroTierService.install(this::logZeroTierOutput);
                    zeroTierService.startService(this::logZeroTierOutput);
                } else if (!zeroTierService.isServiceRunning()) {
                    zeroTierService.startService(this::logZeroTierOutput);
                }
                Platform.runLater(() -> {
                    startZtInfoServerIfNeeded();
                    refreshZeroTierPanel(true);
                    showToast("ZeroTier включен", "success");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    zeroTierPowerOn = false;
                    ztPowerBtn.setText(tr("zt_enable_btn"));
                    stopZtInfoServerIfNeeded();
                    ztStatusLabel.setText("Ошибка включения: " + ex.getMessage());
                    log("[ZT] Ошибка включения: " + ex.getMessage());
                    refreshZeroTierPanel(true);
                });
            } finally {
                // Снимаем флаг и включаем кнопку в одном месте — при любом исходе.
                ztPowerBusy = false;
                Platform.runLater(() -> ztPowerBtn.setDisable(false));
            }
        });
    }

    @FXML
    private void onZeroTierInstall() {
        if (zeroTierService == null) return;
        ztInstallBtn.setDisable(true);
        // Already installed but the service stopped → this button now starts the service only
        // (no UAC install prompt, still one UAC for net start). New install → full flow.
        boolean needServiceOnly = zeroTierService.isInstalled();
        ztStatusLabel.setText(needServiceOnly
                ? "Запуск службы ZeroTier — подтвердите запрос администратора (UAC)..."
                : "Установка ZeroTier One — подтвердите запрос администратора (UAC)...");
        executor.submit(() -> {
            try {
                if (needServiceOnly) {
                    zeroTierService.startService(this::logZeroTierOutput);
                } else {
                    zeroTierService.install(this::logZeroTierOutput);
                    zeroTierService.startService(this::logZeroTierOutput);
                }
                Platform.runLater(() -> {
                    ztInstallBtn.setDisable(false);
                    refreshZeroTierPanel(true);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    ztInstallBtn.setDisable(false);
                    ztStatusLabel.setText("Ошибка: " + ex.getMessage());
                    log("[ZT] Ошибка: " + ex.getMessage());
                });
            }
        });
    }

    /** Ссылка-приглашение в комнату, например: blauncher/connect/3d9ec58252ec283b */
    private static String zeroTierConnectLink(String nwid) {
        return "blauncher/connect/" + nwid;
    }

    /** Извлекает 16-символьный ID комнаты из сырого ввода: можно вставить как ID, так и ссылку
     *  вида blauncher/connect/ID или blauncher://connect/ID. */
    private static String parseZeroTierRoomInput(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int idx = s.indexOf("/connect/");
        if (idx >= 0) s = s.substring(idx + "/connect/".length());
        else if (s.startsWith("blauncher:connect/")) s = s.substring("blauncher:connect/".length());
        return s.trim();
    }

    @FXML
    private void onZeroTierJoin() {
        if (zeroTierService == null) return;
        String id = parseZeroTierRoomInput(ztJoinField.getText() == null ? "" : ztJoinField.getText());
        if (id.isEmpty()) {
            showToast("Введите ID комнаты или ссылку blauncher/connect/ID", "info");
            return;
        }
        ztJoinBtn.setDisable(true);
        executor.submit(() -> {
            try {
                zeroTierService.join(id, this::logZeroTierOutput);
                Platform.runLater(() -> {
                    ztJoinBtn.setDisable(false);
                    showToast("Вход в комнату " + id + " выполнен", "success");
                    refreshZeroTierPanel(true);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    ztJoinBtn.setDisable(false);
                    showToast("Не удалось войти: " + ex.getMessage(), "error");
                });
            }
        });
    }

    @FXML
    private void onZeroTierCreateRoom() {
        if (zeroTierService == null) return;
        String name = ztNameField.getText() == null ? "" : ztNameField.getText().trim();
        // Room type: public ("без пароля" - anyone with the ID joins instantly) or private
        // ("с доступом" - the host approves each member, see the Одобрить buttons below).
        boolean privateRoom = ztTypeCombo.getSelectionModel().getSelectedIndex() == 1;
        ztCreateBtn.setDisable(true);
        executor.submit(() -> {
            try {
                String id = zeroTierService.createRoomLocal(name, privateRoom, this::logZeroTierOutput);
                final String newId = id;
                Platform.runLater(() -> {
                    ztCreateBtn.setDisable(false);
                    showToast("Комната создана: " + newId + " — отправь ссылку друзьям", "success");
                    refreshZeroTierPanel(true);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    ztCreateBtn.setDisable(false);
                    showToast("Не удалось создать комнату: " + ex.getMessage(), "error");
                });
            }
        });
    }

    private void onZeroTierApprove(String networkId, String memberAddress) {
        if (zeroTierService == null) return;
        executor.submit(() -> {
            try {
                zeroTierService.authorizeMember(networkId, memberAddress);
            } catch (Exception ex) {
                Platform.runLater(() -> showToast("Не удалось одобрить: " + ex.getMessage(), "error"));
            }
            Platform.runLater(() -> refreshZeroTierPanel(true));
        });
    }

    private void onZeroTierLeave(String networkId) {
        if (zeroTierService == null) return;
        executor.submit(() -> {
            try {
                zeroTierService.leave(networkId);
            } catch (Exception ignored) {
            }
            // Если комната создана на этом ПК — удаляем её с контроллера, чтобы она
            // оборвалась у всех участников, а не только у хоста.
            try {
                zeroTierService.deleteRoomLocal(networkId);
            } catch (Exception ignored) {
            }
            // Комната закрыта — её виртуальный адаптер тоже должен исчезнуть, иначе они
            // копятся в системе («ZeroTier Virtual Port #N» десятками).
            try {
                zeroTierService.removeAdapterForRoom(networkId, this::logZeroTierOutput);
            } catch (Exception ignored) {
            }
            Platform.runLater(() -> refreshZeroTierPanel(true));
        });
    }

    @FXML
    private void onCopyZeroTierAddress() {
        copyToClipboard(ztAddressField.getText());
    }

    private void logZeroTierOutput(String msg) {
        Platform.runLater(() -> log(msg));
    }

    /** Starts the phone/Android discovery HTTP endpoint, but only when the user actually enabled
     *  the local server (started it from the Server tab). The phone must not see or create rooms
     *  before that button is pressed. */
    private void startZtInfoServerIfNeeded() {
        // synchronized + volatile поле. Метод зовут и UI-поток (кнопка включения), и фоновый
        // поток опроса ZeroTier, а проверка с присваиванием раньше шла без синхронизации: оба
        // видели null, оба вызывали ZeroTier.start на порту 9994, один получал BindException,
        // съедаемый в строку лога, а победитель оставался без ссылки — порт держался занятым до
        // конца сессии, и openServerPortForNetwork давал ещё один запрос UAC. Встречное
        // выключение могло обнулить поле, пока живой сервер продолжал слушать.
        synchronized (ztInfoServerLock) {
            if (ztInfoServer != null) return;
            try {
                ztInfoServer = ZtInfoServer.start(gameDir);
                // Отказы сервер пишет в общий лог: иначе «телефон не видит ПК» было бы нечем
                // объяснить — а теперь в логе прямо сказано, адрес вне оверлея или браузер.
                ztInfoServer.setLogger(this::log);
                log("[ZT-INFO] Сервер участников комнат запущен на порту 9994");
                // Без правила брандмауэра телефон не сможет достучаться до порта 9994 —
                // именно это видно в логах Android как «host not found».
                openServerPortForNetwork(ZtInfoServer.PORT);
            } catch (Exception e) {
                log("[ZT-INFO] Не удалось запустить сервер участников: " + e.getMessage());
            }
        }
    }

    /** Stops the phone/Android discovery HTTP endpoint when no local server is running any more. */
    private void stopZtInfoServerIfNeeded() {
        synchronized (ztInfoServerLock) {
            if (ztInfoServer == null) return;
            try {
                ztInfoServer.close();
            } catch (Exception ignored) {
            }
            ztInfoServer = null;
            log("[ZT-INFO] Сервер участников комнат остановлен");
        }
    }


    /** Re-reads engine state + joined rooms on the background executor, then repaints the panel.
     *
     *  @param force true — ручное «Обновить»/после действия: перестроить список комнат даже без
     *               изменения состава; false — фоновый поллер: строки перестраиваются только при
     *               реальном изменении, чтобы не мигать и не плодить лишние задачи. */
    private void refreshZeroTierPanel(boolean force) {
        if (zeroTierService == null) return;
        // Room-type combo for the "Создать комнату" row: index 0 = public, 1 = private.
        if (ztTypeCombo.getItems().isEmpty()) {
            ztTypeCombo.getItems().addAll("Публичная — вход сразу", "Приватная — я одобряю вход");
            ztTypeCombo.getSelectionModel().select(0);
        }
        // Фоновый тик пропускаем, если предыдущий проход ещё идёт: раньше медленные
        // проходы накладывались друг на друга и копили очередь. Нажатие «Обновить»
        // (force) пропускать нельзя — человек ждёт реакции.
        if (!force && !ztRefreshInFlight.compareAndSet(false, true)) return;
        ztRefreshInFlight.set(true);
        ztExecutor.submit(() -> {
            try {
                final boolean installed = zeroTierService.isInstalled();
                final String statusText = installed ? zeroTierService.getStatusText() : null;
                final boolean online = installed && zeroTierService.isOnline();
                // sc query spawns a process — belongs on this background thread, never the FX thread.
                final boolean serviceDown = installed && statusText == null
                        && !zeroTierService.isServiceRunning();
                String ip = null;
                List<ZeroTierService.NetworkInfo> rooms = new ArrayList<>();
                Map<String, List<ZeroTierService.MemberInfo>> pending = new HashMap<>();
                if (online) {
                    try {
                        ip = zeroTierService.getMyVpnIp();
                    } catch (Exception ignored) {
                    }
                    try {
                        rooms = zeroTierService.listNetworks();
                        // For rooms hosted on THIS machine's controller (the ones we created), fetch
                        // who's waiting for approval - only our own controller answers these routes,
                        // so a room created elsewhere simply contributes nothing here.
                        for (ZeroTierService.NetworkInfo n : rooms) {
                            try {
                                List<ZeroTierService.MemberInfo> waiters = zeroTierService.listPendingMembers(n.id);
                                // Подстраховка: если listPendingMembers по какой-то причине пуст/недоступен,
                                // берём полный список участников и отбираем неодобренных не-владельцев.
                                if (waiters == null || waiters.isEmpty()) {
                                    List<ZeroTierService.RoomMember> all = zeroTierService.listMembers(n.id);
                                    if (all != null) {
                                        waiters = new ArrayList<>();
                                        for (ZeroTierService.RoomMember m : all) {
                                            if (!m.owner && !m.authorized) {
                                                ZeroTierService.MemberInfo mi = new ZeroTierService.MemberInfo();
                                                mi.address = m.address;
                                                mi.name = m.name;
                                                waiters.add(mi);
                                            }
                                        }
                                    }
                                }
                                if (waiters != null && !waiters.isEmpty()) pending.put(n.id, waiters);
                            } catch (Exception ignored) {
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }
                // Сервер участников поднимаем, только если человек САМ включил ZeroTier в этом
                // сеансе. Прежнее условие («есть активные комнаты») обходило защиту, которую
                // обещает javadoc startZtInfoServerIfNeeded: «телефон не должен видеть и
                // создавать комнаты до нажатия кнопки». А комнаты в ZeroTier сохраняются между
                // запусками, поэтому после перезапуска лаунчера условие было истинным сразу — и
                // незащищённый сервер на порту 9994 вместе с правилом брандмауэра на всю локалку
                // (находка C1) появлялся без единого действия человека.
                //
                // Следствие: после перезапуска телефон увидит ПК не сразу, а после нажатия
                // «Включить». Это и есть задуманное поведение.
                if (zeroTierPowerOn && online && rooms != null && !rooms.isEmpty()) {
                    startZtInfoServerIfNeeded();
                }
                final String fIp = ip;
                final List<ZeroTierService.NetworkInfo> fRooms = rooms;
                final Map<String, List<ZeroTierService.MemberInfo>> fPending = pending;
                final boolean fForce = force;
                Platform.runLater(() -> {
                    lastZeroTierIp = fIp;
                    // Кнопка включения/выключения — главный рубильник ZeroTier. Подключение и
                    // телефонное/Android обнаружение работают только после её нажатия.
                    if (ztPowerBtn != null) {
                        ztPowerBtn.setText(zeroTierPowerOn ? tr("zt_disable_btn") : tr("zt_enable_btn"));
                        // Но НЕ включаем кнопку, если прямо сейчас идёт включение или выключение.
                        // Раньше включение было безусловным, и поллер раз в 5 секунд оживлял
                        // кнопку посреди установки — второе нажатие уходило в ветку выключения и
                        // запускало leaveAllRooms параллельно ещё идущей установке, после чего
                        // отображаемое состояние расходилось с фактическим.
                        if (!ztPowerBusy) ztPowerBtn.setDisable(false);
                    }
                    // Кнопка установки контекстная: движок не установлен — «Установить ZeroTier»,
                    // установлен, но служба остановлена — «Запустить службу ZeroTier».
                    ztInstallBtn.setVisible(!installed || serviceDown);
                    ztInstallBtn.setManaged(!installed || serviceDown);
                    ztInstallBtn.setText(installed ? tr("zt_start_service_btn") : tr("zt_install_btn"));
                    ztJoinBtn.setDisable(!installed || !zeroTierPowerOn);
                    ztCreateBtn.setDisable(!installed || !zeroTierPowerOn);
                    if (!installed) {
                        ztStatusLabel.setText(tr("zt_status_not_installed"));
                    } else if (serviceDown) {
                        ztStatusLabel.setText(tr("zt_status_service_down"));
                    } else if (statusText == null) {
                        ztStatusLabel.setText("Движок ZeroTier установлен, но не отвечает — нажмите «Обновить»");
                    } else {
                        // Player-facing wording only: the engine's own status string and the VPN address
                    // were noise here - the address a friend needs is shown once, below, when there
                    // is a server to join.
                    ztStatusLabel.setText(fIp != null
                            ? "Готово — можно звать друзей"
                            : "Подключение…");
                    }
                    ztAddressRow.setVisible(fIp != null);
                    ztAddressRow.setManaged(fIp != null);
                    if (fIp != null) ztAddressField.setText(fIp);
                    renderZeroTierRooms(fRooms, fPending, fForce);
                    // Если в приватную комнату появился новый запрос на вход — показываем тост,
                    // чтобы владелец ПК не пропустил его (сам список комнат обновляется каждые 5 с).
                    java.util.Set<String> currentPending = new java.util.HashSet<>();
                    if (fPending != null) {
                        for (Map.Entry<String, List<ZeroTierService.MemberInfo>> pe : fPending.entrySet()) {
                            for (ZeroTierService.MemberInfo w : pe.getValue()) {
                                currentPending.add(pe.getKey() + ":" + w.address);
                            }
                        }
                    }
                    for (String key : currentPending) {
                        if (!notifiedZtPendingRequests.contains(key)) {
                            String[] parts = key.split(":", 2);
                            String roomId = parts.length > 0 ? parts[0] : "";
                            String address = parts.length > 1 ? parts[1] : "";
                            String who = "";
                            if (fPending != null) {
                                List<ZeroTierService.MemberInfo> waiters = fPending.get(roomId);
                                if (waiters != null) {
                                    for (ZeroTierService.MemberInfo w : waiters) {
                                        if (address.equals(w.address)) {
                                            who = w.name == null || w.name.isBlank() ? address : w.name;
                                            break;
                                        }
                                    }
                                }
                            }
                            showToast("Новый запрос на вход в комнату " + roomId + " от " + who, "info");
                        }
                    }
                    notifiedZtPendingRequests.clear();
                    notifiedZtPendingRequests.addAll(currentPending);
                    // The join address the friends screen shows prefers the VPN IP over the router-LAN IP.
                    if (focusedServerId != null) renderFriendAddress();
                });
            } finally {
                ztRefreshInFlight.set(false);
            }
        });
    }

    private void renderZeroTierRooms(List<ZeroTierService.NetworkInfo> rooms,
                                     Map<String, List<ZeroTierService.MemberInfo>> pending,
                                     boolean force) {
        // Отпечаток состава: если он совпадает с уже нарисованным и это не ручное обновление,
        // строки не трогаем — старые остаются, а поллер не пересоздаёт вью каждые 5 секунд.
        StringBuilder fp = new StringBuilder();
        if (rooms != null) {
            for (ZeroTierService.NetworkInfo n : rooms) {
                fp.append(n.id).append('|').append(n.status).append('|')
                        .append(String.join(",", n.ips)).append(';');
                List<ZeroTierService.MemberInfo> waiters = pending == null ? null : pending.get(n.id);
                if (waiters != null) {
                    for (ZeroTierService.MemberInfo w : waiters) {
                        fp.append("~").append(w.address).append('=').append(w.name);
                    }
                }
            }
        }
        String want = fp.toString();
        if (!force && want.equals(lastZtRoomsFingerprint)) return;
        lastZtRoomsFingerprint = want;

        ztRoomsBox.getChildren().clear();
        if (rooms == null || rooms.isEmpty()) {
            Label empty = new Label("Нет активных комнат — войди в комнату по ID выше");
            empty.getStyleClass().add("toggle-sub");
            ztRoomsBox.getChildren().add(empty);
            return;
        }
        for (ZeroTierService.NetworkInfo n : rooms) {
            HBox row = new HBox(8);
            row.getStyleClass().add("server-row");
            row.setAlignment(Pos.CENTER_LEFT);
            Region dot = new Region();
            dot.getStyleClass().add("server-dot");
            if ("ACCESS_DENIED".equals(n.status)) {
                dot.getStyleClass().add("denied");
            } else if (!"OK".equals(n.status)) {
                dot.getStyleClass().add("pending");
            }
            VBox text = new VBox(2);
            String title = n.id + (n.name != null && !n.name.isBlank() ? " · " + n.name : "");
            Label idLabel = new Label(title);
            idLabel.getStyleClass().add("server-name");
            String sub = (n.privateRoom ? "закрытая" : "открытая") + " · " + roomStatusText(n.status);
            // Room IPs used to be appended here: meaningless to a player, and the address that
            // actually matters is the one the friends screen shows for the running server.
            Label subLabel = new Label(sub);
            subLabel.getStyleClass().addAll("server-sub", "mono");
            text.getChildren().addAll(idLabel, subLabel);
            Region spacer = new Region();
            HBox.setHgrow(spacer, javafx.scene.layout.Priority.ALWAYS);
            List<ZeroTierService.MemberInfo> roomWaiters =
                    pending == null ? null : pending.get(n.id);
            int waitingCount = roomWaiters == null ? 0 : roomWaiters.size();
            Button details = new Button(waitingCount > 0
                    ? "Подробнее (" + waitingCount + ")"
                    : "Подробнее");
            details.getStyleClass().add("server-action");
            // Явно задаём светлый цвет текста, чтобы кнопка не сливалась с тёмным фоном.
            details.setOnAction(e -> showZeroTierRoomDetails(n));
            Button share = new Button("Поделиться");
            share.getStyleClass().add("server-action");
            share.setOnAction(e -> {
                String link = zeroTierConnectLink(n.id);
                copyToClipboard(link);
                showToast("Ссылка скопирована: " + link, "success");
            });
            Button leave = new Button("Покинуть");
            leave.getStyleClass().add("copy-btn");
            leave.setOnAction(e -> {
                // Optimistic: drop the row immediately - leaving runs in the background via the
                // fast local API, and the 5-second poller re-adds the room if it failed.
                ztRoomsBox.getChildren().remove(row);
                onZeroTierLeave(n.id);
            });
            row.getChildren().addAll(dot, text, spacer, details, share, leave);
            ztRoomsBox.getChildren().add(row);

            // Friends waiting for the host's approval in a private room we created.
            List<ZeroTierService.MemberInfo> waiters = pending == null ? null : pending.get(n.id);
            if (waiters != null && !waiters.isEmpty()) {
                for (ZeroTierService.MemberInfo w : waiters) {
                    HBox waiter = new HBox(8);
                    waiter.setAlignment(Pos.CENTER_LEFT);
                    waiter.setPadding(new javafx.geometry.Insets(2, 4, 2, 14));
                    Label who = new Label("Ждёт одобрения: " + w.address);
                    who.getStyleClass().add("toggle-sub");
                    Region wSpacer = new Region();
                    HBox.setHgrow(wSpacer, javafx.scene.layout.Priority.ALWAYS);
                    Button approve = new Button("Одобрить");
                    approve.getStyleClass().add("copy-btn");
                    approve.setOnAction(e -> onZeroTierApprove(n.id, w.address));
                    waiter.getChildren().addAll(who, wSpacer, approve);
                    ztRoomsBox.getChildren().add(waiter);
                }
            }
        }
    }

    /** Открывает карточку комнаты как часть лаунчера (оверлей), а не системное окно: тип
     *  (открытая/закрытая), статус, участники, одобрение входа и выход из комнаты. */
    private void showZeroTierRoomDetails(ZeroTierService.NetworkInfo n) {
        executor.submit(() -> {
            try {
                final List<ZeroTierService.RoomMember> members = zeroTierService.listMembers(n.id);
                final List<ZeroTierService.MemberInfo> pending = zeroTierService.listPendingMembers(n.id);
                Platform.runLater(() -> {
                    // Оверлей встроен в лаунчер и использует те же стили, что остальные модалки.
                    StackPane scrim = new StackPane();
                    scrim.getStyleClass().add("modal-scrim");

                    VBox card = new VBox(0);
                    card.getStyleClass().add("modal-card");
                    // Это внутреннее окно можно двигать за заголовок и растягивать за края,
                    // как обычные модалки лаунчера.
                    card.setMinWidth(420);
                    card.setMinHeight(320);
                    card.setPrefWidth(640);
                    card.setPrefHeight(480);
                    card.setMaxWidth(640);
                    card.setMaxHeight(480);
                    card.setPickOnBounds(true);

                    HBox header = new HBox();
                    header.getStyleClass().add("modal-header");
                    header.setAlignment(Pos.CENTER_LEFT);
                    Label title = new Label("Конфигурация комнаты");
                    title.getStyleClass().add("modal-title");
                    Region headerSpacer = new Region();
                    HBox.setHgrow(headerSpacer, javafx.scene.layout.Priority.ALWAYS);
                    Button close = new Button();
                    close.getStyleClass().add("modal-close");
                    close.setGraphic(new FontIcon("fth-x"));
                    close.setOnAction(e -> {
                        overlayLayer.getChildren().remove(scrim);
                        hideOverlay(scrim);
                    });
                    header.getChildren().addAll(title, headerSpacer, close);

                    VBox body = new VBox(8);
                    body.getStyleClass().add("modal-body");

                    Label typeLine = new Label("Тип: " + (n.privateRoom
                            ? "закрытая — вход по одобрению владельца"
                            : "открытая — вход сразу по ID"));
                    typeLine.setWrapText(true);
                    body.getChildren().add(typeLine);

                    String nameInfo = n.name != null && !n.name.isBlank() ? n.name : "—";
                    Label nameLabel = new Label("Название: " + nameInfo);
                    nameLabel.setWrapText(true);
                    body.getChildren().add(nameLabel);
                    Label idLabel = new Label("ID комнаты: " + n.id);
                    idLabel.setWrapText(true);
                    body.getChildren().add(idLabel);
                    Label statusLabel = new Label("Статус: " + roomStatusText(n.status));
                    statusLabel.setWrapText(true);
                    body.getChildren().add(statusLabel);
                    // No "IP в комнате" line: a raw VPN address tells a player nothing they can use.

                    body.getChildren().add(new javafx.scene.control.Separator());
                    Label membersTitle = new Label("Участники:");
                    membersTitle.getStyleClass().add("toggle-sub");
                    body.getChildren().add(membersTitle);

                    if ((members == null || members.isEmpty()) && (pending == null || pending.isEmpty())) {
                        Label emptyMembers = new Label("Пока никого нет");
                        body.getChildren().add(emptyMembers);
                    }
                    if (members != null) {
                        for (ZeroTierService.RoomMember m : members) {
                            HBox line = new HBox(8);
                            line.setAlignment(Pos.CENTER_LEFT);
                            String role = m.owner ? "владелец"
                                    : m.authorized ? "в игре/одобрен" : "ожидает одобрения";
                            String label = (m.name == null || m.name.isEmpty() ? m.address : m.name)
                                    + " — " + role;
                            Label info = new Label(label);
                            info.setWrapText(true);
                            HBox.setHgrow(info, javafx.scene.layout.Priority.ALWAYS);
                            line.getChildren().add(info);
                            if (!m.owner && !m.authorized) {
                                Button approve = new Button("Одобрить");
                                approve.getStyleClass().add("copy-btn");
                                approve.setOnAction(e -> {
                                    onZeroTierApprove(n.id, m.address);
                                    approve.setDisable(true);
                                    approve.setText("Одобрено");
                                    info.setText(info.getText().replace("ожидает одобрения", "в игре/одобрен"));
                                });
                                line.getChildren().add(approve);
                            }
                            body.getChildren().add(line);
                        }
                    } else if (pending != null) {
                        for (ZeroTierService.MemberInfo w : pending) {
                            HBox line = new HBox(8);
                            line.setAlignment(Pos.CENTER_LEFT);
                            String label = (w.name == null || w.name.isEmpty() ? w.address : w.name)
                                    + " (" + w.address + ") — ожидает одобрения";
                            Label info = new Label(label);
                            info.setWrapText(true);
                            HBox.setHgrow(info, javafx.scene.layout.Priority.ALWAYS);
                            line.getChildren().add(info);
                            Button approve = new Button("Одобрить");
                            approve.getStyleClass().add("copy-btn");
                            approve.setOnAction(e -> {
                                onZeroTierApprove(n.id, w.address);
                                approve.setDisable(true);
                                approve.setText("Одобрено");
                            });
                            line.getChildren().add(approve);
                            body.getChildren().add(line);
                        }
                    }

                    ScrollPane scroll = new ScrollPane(body);
                    scroll.setFitToWidth(true);
                    VBox.setVgrow(scroll, javafx.scene.layout.Priority.ALWAYS);

                    // В нижней части карточки — функции комнаты: обновить, выйти.
                    HBox footer = new HBox(10);
                    footer.setPadding(new javafx.geometry.Insets(12, 16, 16, 16));
                    footer.setAlignment(Pos.CENTER_RIGHT);
                    Button share = new Button("Поделиться");
                    share.getStyleClass().add("server-action");
                    share.setOnAction(e -> {
                        String link = zeroTierConnectLink(n.id);
                        copyToClipboard(link);
                        showToast("Ссылка скопирована: " + link, "success");
                    });
                    Button refresh = new Button("Обновить");
                    refresh.getStyleClass().add("copy-btn");
                    refresh.setOnAction(e -> {
                        overlayLayer.getChildren().remove(scrim);
                        hideOverlay(scrim);
                        refreshZeroTierPanel(true);
                        showZeroTierRoomDetails(n);
                    });
                    Button leave = new Button("Покинуть комнату");
                    leave.getStyleClass().add("server-action");
                    leave.setOnAction(e -> {
                        overlayLayer.getChildren().remove(scrim);
                        hideOverlay(scrim);
                        onZeroTierLeave(n.id);
                    });
                    footer.getChildren().addAll(share, refresh, leave);

                    card.getChildren().addAll(header, scroll, footer);
                    scrim.getChildren().add(card);
                    overlayLayer.getChildren().add(scrim);
                    makeModalDraggable(card);
                    makeModalResizable(card);
                    showOverlay(scrim);
                });
            } catch (Exception ex) {
                log("[ZT] Не удалось открыть карточку комнаты: " + ex.getMessage());
            }
        });
    }

    private static String roomStatusText(String status) {
        if (status == null) return "—";
        switch (status) {
            case "OK": return "подключена";
            case "ACCESS_DENIED": return "нет доступа — владелец должен одобрить вход";
            case "REQUESTING_CONFIGURATION": return "настраивается... (обычно 10–30 сек)";
            default: return status;
        }
    }

    /** Adds (idempotently) a Windows Firewall rule letting room friends reach the dedicated
     *  server's port over ZeroTier's address ranges (10.0.0.0/8, 25.0.0.0/8) AND over ordinary
     *  private LAN ranges (192.168.0.0/16, 172.16.0.0/12). Minecraft's java process gets no
     *  automatic inbound rule on a virtual adapter, and on a Private/Domain firewall profile even
     *  plain LAN play is silently blocked — measured on this machine: the phone could reach the
     *  server only after the private ranges were added. Best-effort: runs through one UAC prompt;
     *  if the user declines, a warning is logged. The rule is deliberately never removed (an
     *  open-but-unlistening port is harmless), and only allows private subnets — the public
     *  internet stays blocked. */
    private void openServerPortForNetwork(int port) {
        // netsh есть только в Windows. На Linux входящие по умолчанию не режутся (ufw выключен
        // почти везде), а включённый ufw без sudo не поправить — молча пропускаем.
        if (!Os.isWindows()) return;
        executor.submit(() -> {
            try {
                String ruleName = "BLauncher-ZT-" + port;
                // Если правило уже есть — не дёргаем UAC повторно.
                Process check = new ProcessBuilder("netsh", "advfirewall", "firewall",
                        "show", "rule", "name=" + ruleName)
                        .redirectErrorStream(true).start();
                String checkOut = new String(check.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                check.waitFor();
                if (checkOut.contains(ruleName)) return;
                // A .ps1 file avoids every level of the nested-quoting hell that inline
                // Start-Process -ArgumentList strings cause (same trick as the ZeroTier MSI
                // install) - netsh runs inside ONE elevated PowerShell, delete-then-add makes
                // the rule idempotent, and remoteip scoping limits exposure to private peers.
                Path script = Files.createTempFile("blauncher-fw-", ".ps1");
                String content = "netsh advfirewall firewall delete rule name='" + ruleName + "' 2>$null; "
                        + "netsh advfirewall firewall add rule name='" + ruleName
                        + "' dir=in action=allow protocol=TCP localport=" + port
                        + " remoteip=10.0.0.0/8,25.0.0.0/8,192.168.0.0/16,172.16.0.0/12; exit $LASTEXITCODE";
                Files.writeString(script, content, StandardCharsets.UTF_8);
                String psCommand = "$p = Start-Process powershell -ArgumentList '-NoProfile','-NonInteractive',"
                        + "'-ExecutionPolicy','Bypass','-File','" + script.toAbsolutePath() + "' "
                        + "-Verb RunAs -WindowStyle Hidden -Wait -PassThru; exit $p.ExitCode";
                Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", psCommand)
                        .redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                int exit = p.waitFor();
                try {
                    Files.deleteIfExists(script);
                } catch (IOException ignored) {
                }
                if (exit != 0) {
                    log("[FW] Не удалось открыть порт " + port + " в брандмауэре (код " + exit
                            + ") — друзья не смогут подключиться, пока правило не добавлено.");
                }
            } catch (Exception ex) {
                log("[FW] Не удалось открыть порт " + port + ": " + ex.getMessage());
            }
        });
    }

    @FXML
    private void onOpenServerAdvanced() {
        renderServerAdvancedList();
        goToView("serverAdvanced");
    }

    @FXML
    private void onServerAdvancedBack() {
        goToView("server");
    }

    // ---------- "Server Advanced" screen (full list) ----------

    private void renderServerAdvancedList() {
        serverAdvancedList.getChildren().clear();
        List<ServerEntry> hubList = hubs();
        for (ServerEntry hub : hubList) {
            serverAdvancedList.getChildren().add(buildServerRowNode(hub, false));
            for (ServerEntry sat : satellitesOf(hub.getId())) {
                serverAdvancedList.getChildren().add(buildServerRowNode(sat, true));
            }
        }
        for (ServerEntry s : servers) {
            if (s.getNetwork() == null) {
                serverAdvancedList.getChildren().add(buildServerRowNode(s, false));
            }
        }
        // Сервер панели VPS, чей статус ещё не пришёл, не считаем ни запущенным, ни остановленным —
        // иначе он попадал в «Остановлено», даже когда служба на VPS работала.
        long unknown = servers.stream().filter(s -> s.isSsh() && !s.isSshStatusKnown()).count();
        long running = servers.stream()
                .filter(s -> !s.isSsh() || s.isSshStatusKnown())
                .filter(s -> s.isLocal() ? gameLaunchService.isServerRunning(s.getName()) : s.isRunning())
                .count();
        long stopped = servers.size() - unknown - running;
        serverAdvancedSummaryLabel.setText("Запущено: " + running + " · Остановлено: " + stopped
                + (unknown > 0 ? " · проверяю: " + unknown : ""));
        refreshVpsStatusesInBackground();
    }

    /**
     * One-shot background refresh of every connected VPS server's actual running status —
     * ServerService.loadAll() resets running/pending to false on launcher startup (since a
     * previous session's in-memory state can't be trusted), so without this a VPS server that's
     * genuinely still running on its host would show as "stopped" here until the person happens
     * to open its serverDetail screen (which starts its own continuous poller — see
     * startPollingFocusedVpsServer). This is deliberately NOT a repeating poller of its own: a
     * list screen the person may not be looking at for long doesn't need continuous polling
     * overhead across every VPS server at once — just a one-time correction when the list is
     * first shown.
     */
    private void refreshVpsStatusesInBackground() {
        for (ServerEntry s : servers) {
            if (s.isSsh()) {
                vpsManageController.checkStatusInBackground(s, () -> {
                    renderServerAdvancedList();
                    renderMyServersQuickList();
                });
                continue;
            }
            if (s.isLocal() || !s.isAgentConnected()) continue;
            AgentClient client = agentClientFor(s);
            executor.submit(() -> {
                try {
                    ServerStatus status = client.getStatus();
                    if (s.isRunning() != status.running) {
                        s.setRunning(status.running);
                        Platform.runLater(() -> {
                            saveServers();
                            renderServerAdvancedList();
                            renderMyServersQuickList();
                        });
                    }
                } catch (AgentException ignored) {
                    // Best-effort — a VPS that's temporarily unreachable just keeps showing
                    // whatever status it last had; the person will see the real error if they
                    // try to act on it (start/stop) or open its detail screen.
                }
            });
        }
    }

    /** One-shot background scan that registers any still-running local server process (leftover
     *  from a killed launcher session, or started by hand) so the UI shows it as running and the
     *  Stop button can kill it - see GameLaunchService.adoptRunningServers(). */
    private void adoptRunningServersInBackground() {
        executor.submit(() -> {
            int adopted = 0;
            try {
                List<String> names = new ArrayList<>();
                for (ServerEntry s : servers) {
                    if (s.isLocal()) names.add(s.getName());
                }
                adopted = gameLaunchService.adoptRunningServers(names);
            } catch (Exception ignored) {
            }
            final int fAdopted = adopted;
            Platform.runLater(() -> {
                if (fAdopted > 0) {
                    log("[SERVER] Подхвачено работающих серверов: " + fAdopted);
                }
                renderMyServersQuickList();
                renderServerAdvancedList();
                if (focusedServerId != null) {
                    renderServerDetailHeader();
                    renderFriendAddress();
                }
            });
        });
    }

    /** Last known running state per local server, so the 5-second tick re-renders only on change. */
    private final java.util.Map<String, Boolean> lastLocalServerRunning = new java.util.HashMap<>();

    /** Catches a dedicated server process that died on its own while the launcher stayed open —
     *  without this the card keeps showing "running" until the tab is reopened. Runs off the FX
     *  thread; re-renders (on the FX thread) only when some server's state actually flipped. */
    private void refreshLocalServerCardsIfChanged() {
        final boolean[] changed = new boolean[]{false};
        synchronized (lastLocalServerRunning) {
            for (ServerEntry s : servers) {
                if (!s.isLocal()) continue;
                boolean running;
                try {
                    running = gameLaunchService.isServerRunning(s.getName());
                } catch (Exception e) {
                    continue;
                }
                Boolean prev = lastLocalServerRunning.get(s.getName());
                if (prev == null || prev.booleanValue() != running) {
                    lastLocalServerRunning.put(s.getName(), running);
                    changed[0] = true;
                }
            }
        }
        if (changed[0]) {
            Platform.runLater(() -> {
                renderMyServersQuickList();
                renderServerAdvancedList();
                if (focusedServerId != null) {
                    renderServerDetailHeader();
                    renderFriendAddress();
                }
            });
        }
    }

    private Node buildServerRowNode(ServerEntry s, boolean nested) {
        HBox row = new HBox(14);
        row.getStyleClass().add("server-row");
        if (nested) {
            row.setPadding(new javafx.geometry.Insets(12, 4, 12, 30));
        }
        row.setAlignment(Pos.CENTER_LEFT);

        // s.isRunning() is only ever written from the VPS/agent branch of toggleServerRunning()
        // — for a local ServerEntry it stays false forever, so this reads GameLaunchService's own
        // per-name running state instead, which is the real state for local servers (each
        // differently-named local server now runs as its own independent process).
        boolean running = s.isLocal() ? gameLaunchService.isServerRunning(s.getName()) : s.isRunning();

        Region dot = new Region();
        dot.getStyleClass().add("server-dot");
        if (s.getPending() != null) {
            dot.getStyleClass().add("pending");
        } else if (!running) {
            dot.getStyleClass().add("off");
        }

        ProgressIndicator rowSpinner = new ProgressIndicator();
        rowSpinner.setPrefSize(16, 16);
        rowSpinner.setMaxSize(16, 16);
        rowSpinner.setMinSize(16, 16);
        boolean pending = s.getPending() != null;
        rowSpinner.setVisible(pending);
        rowSpinner.setManaged(pending);

        VBox textBox = new VBox(2);
        Label name = new Label(s.getName());
        name.getStyleClass().add("server-name");
        String location = s.isLocal() ? "этот компьютер" : (s.getHost() + ":" + s.getPort());
        String statusText = s.getPending() != null ? s.getPending()
                : running ? "Работает" : "Остановлен";
        Label sub = new Label(serverSubtitle(s, location, statusText));
        sub.getStyleClass().addAll("server-sub", "mono");
        textBox.getChildren().addAll(name, sub);
        HBox.setHgrow(textBox, Priority.ALWAYS);

        Button actionBtn = new Button();
        actionBtn.getStyleClass().add("server-action");
        // Local servers now run as independent per-name processes (see GameLaunchService's
        // "DEDICATED SERVER" section), so a different local entry already running no longer
        // blocks this one from starting - "controllable" only needs to rule out an in-flight
        // pending start/stop for THIS entry.
        boolean controllable = s.canControl() && s.getPending() == null;
        actionBtn.setDisable(!controllable);
        if (s.isSsh() && s.isSshInstalled() && !s.isSshStatusKnown()) {
            // Пока не знаем, работает ли служба, — не предлагаем ни «Запустить», ни «Остановить».
            actionBtn.setDisable(true);
        }
        actionBtn.setText(s.isSsh() && !s.isSshInstalled() ? "Не установлен"
                : s.isSsh() && !s.isSshStatusKnown() && s.getPending() == null ? "Проверяю…"
                : !s.isLocal() && !s.isSsh() && !s.isAgentConnected() ? "Агент не подключен"
                : controllable ? (running ? tr("stop_btn") : tr("start_btn"))
                : statusText);
        actionBtn.setOnAction(e -> {
            e.consume();
            toggleServerRunning(s);
        });

        Button deleteBtn = new Button();
        deleteBtn.getStyleClass().add("danger-button");
        deleteBtn.setGraphic(new FontIcon("fth-x"));   // no ✕ character: see installed-check note
        deleteBtn.setDisable(running || s.getPending() != null);
        deleteBtn.setOnAction(e -> {
            e.consume();
            confirmAndDeleteServer(s);
        });

        row.getChildren().addAll(dot, rowSpinner, textBox, actionBtn, deleteBtn);
        row.setOnMouseClicked(e -> openServerDetail(s.getId()));
        return row;
    }

    /** Removes a server from servers[] after an explicit confirmation dialog — never silent,
     *  since this can't be undone. For a VPS entry this also has the agent uninstall itself from
     *  the VPS first (see AgentClient#uninstall / AGENT_API.md's POST /agent/uninstall) — the
     *  entry is removed from the list either way, even if that call fails (agent unreachable,
     *  key already wrong, etc.), since at that point there's nothing left on the launcher side to
     *  retry and the person has already confirmed they want this record gone. */
    private void confirmAndDeleteServer(ServerEntry s) {
        if (s.isSsh()) {
            // Своё подтверждение и своя уборка: ключ с VPS, секреты на ПК — см. VpsPanelController.
            vpsPanelController.deleteServer(s);
            return;
        }
        javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                s.isLocal()
                        ? "Удалить сервер «" + s.getName() + "»? Это уберёт его только из списка в лаунчере."
                        : "Удалить сервер «" + s.getName() + "»? Лаунчер остановит сервер и удалит агента с VPS "
                                + "(необратимо), затем уберёт запись из списка.");
        confirm.setHeaderText(null);
        confirm.setTitle("Удаление сервера");
        var result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != javafx.scene.control.ButtonType.OK) {
            return;
        }

        if (s.isLocal()) {
            removeServerFromList(s);
            showToast("Сервер «" + s.getName() + "» удалён из лаунчера", "success");
            return;
        }

        AgentClient client = agentClientFor(s);
        log("[SERVER] Удаляю агента с VPS \"" + s.getName() + "\"...");
        executor.submit(() -> {
            String failureReason = null;
            try {
                client.uninstall();
            } catch (AgentException ex) {
                failureReason = ex.getMessage();
            }
            String reason = failureReason;
            Platform.runLater(() -> {
                removeServerFromList(s);
                if (reason == null) {
                    showToast("Сервер «" + s.getName() + "» и агент на VPS удалены", "success");
                } else {
                    showToast("Не удалось достучаться до агента (" + reason
                            + ") — запись всё равно убрана из лаунчера", "info");
                }
            });
        });
    }

    /** Shared tail of {@link #confirmAndDeleteServer}: removes the entry from servers[] and
     *  refreshes every view that shows it. Split out because the VPS path needs to run this only
     *  after its background agent-uninstall call finishes (success or failure), while the local
     *  path can run it immediately - see call sites. */
    private void removeServerFromList(ServerEntry s) {
        if (vpsManageController.isOpenFor(s)) vpsManageController.close();
        if (s.getId().equals(focusedServerId) && focusedPoller != null) {
            focusedPoller.stop();
            focusedPoller = null;
            focusedServerId = null;
        }
        agentClients.remove(s.getId());
        // Satellites pointing at a deleted hub would otherwise reference a hubId that no longer
        // resolves to anything — detach them back to "no network" rather than leaving a dangling
        // reference that renderNetworkPanel()/satellitesOf() would just silently ignore anyway.
        if (s.getNetwork() != null && s.getNetwork().isHub()) {
            for (ServerEntry sat : satellitesOf(s.getId())) {
                sat.setNetwork(null);
            }
        }
        servers.remove(s);
        saveServers();
        renderServerAdvancedList();
        renderMyServersQuickList();
        if (focusedServerId == null && screenServerDetail.isVisible()) {
            goToView("serverAdvanced");
        }
    }

    private void toggleServerRunning(ServerEntry s) {
        if (s.isLocal()) {
            // Each differently-named local server runs as its own independent process (see
            // GameLaunchService's "DEDICATED SERVER" section) — check/stop THIS entry specifically,
            // not whichever local server happens to be running globally.
            if (gameLaunchService.isServerRunning(s.getName())) {
                stopLocalServer(s);
            } else {
                startLocalServer(s);
            }
            return;
        }
        if (s.isSsh()) {
            if (s.isSshInstalled()) {
                vpsManageController.toggle(s);
            } else {
                showToast("На «" + s.getName() + "» ещё не установлен Minecraft-сервер — установите его на экране сервера", "info");
            }
            return;
        }
        if (!s.isAgentConnected()) {
            log("[SERVER] Агент для \"" + s.getName() + "\" ещё не подключен.");
            return;
        }
        AgentClient client = agentClientFor(s);
        s.setPending(s.isRunning() ? "Останавливается..." : "Запускается...");
        renderServerAdvancedList();
        if (s.getId().equals(focusedServerId)) renderServerDetailHeader();
        executor.submit(() -> {
            try {
                if (s.isRunning()) {
                    client.stopServer();
                    s.setRunning(false);
                } else {
                    // ServerEntry.getRamMb() is already stored in megabytes (see its javadoc
                    // and how onConfirmCreateServer() populates it from the RAM slider) — do
                    // NOT multiply again here, AgentClient.startServer(int ramMb) expects MB too.
                    client.startServer(s.getRamMb() != null ? s.getRamMb() : 2048);
                    s.setRunning(true);
                }
            } catch (AgentException ex) {
                Platform.runLater(() -> log("[AGENT] Ошибка при управлении \"" + s.getName() + "\": " + ex.getMessage()));
            } finally {
                s.setPending(null);
                saveServers();
                Platform.runLater(() -> {
                    renderServerAdvancedList();
                    renderMyServersQuickList();
                    if (s.getId().equals(focusedServerId)) {
                        renderServerDetailHeader();
                    }
                });
            }
        });
    }

    // ---------- "Server Detail" screen (console / agent / tunnel / network / plugins) ----------

    private void openServerDetail(String id) {
        // Stop any poller from a previously-focused server before switching — otherwise
        // polling threads for every server ever opened would keep accumulating.
        if (focusedPoller != null) {
            focusedPoller.stop();
            focusedPoller = null;
        }
        focusedServerId = id;
        config.setLastSelectedServerId(id);
        try { configService.saveConfig(config); } catch (Exception ignored) { }

        renderServerDetailHeader();
        renderAgentGate();
        renderConsole();
        renderFriendAddress();
        renderNetworkPanel();
        pluginsPanelController.onServerDetailOpened();
        goToView("serverDetail");

        ServerEntry s = findServer(id);
        if (s != null && s.isSsh() && s.isSshInstalled()) {
            vpsManageController.open(s);
        } else {
            vpsManageController.close();
        }
        if (s != null && !s.isLocal()) {
            // Preselect the reinstall combos to what's actually installed right now, rather than
            // leaving them at whatever the last-opened server happened to leave selected — done
            // once here rather than in renderServerDetailHeader(), which reruns on every status
            // poll tick and would keep overwriting an in-progress pick.
            reinstallVersionCombo.getSelectionModel().select(s.getVersion());
            reinstallLoaderCombo.getSelectionModel().select(s.getLoader());
            if (s.isAgentConnected()) {
                startPollingFocusedVpsServer(s);
            }
        }
    }

    /**
     * «На VPS» на экране локального сервера. Сам перенос — это установка на VPS с вкладкой
     * «Перенести с ПК» (VpsPanelController), поэтому здесь только переход туда с уже выбранным
     * сервером. Нужен VPS, на котором Minecraft ещё не ставили: поверх установленного сервера мир
     * не переносим, чтобы ненароком не затереть тот, что уже на VPS.
     */
    @FXML
    private void onMoveServerToVps() {
        ServerEntry local = findServer(focusedServerId);
        if (local == null || !local.isLocal()) return;
        ServerEntry target = null;
        for (ServerEntry e : servers) {
            if (e.isSsh() && !e.isSshInstalled()) { target = e; break; }
        }
        if (target == null) {
            showToast("Сначала добавьте VPS: «Новый сервер» → «VPS». Потом откройте его — там будет вкладка «Перенести с ПК».", "info");
            return;
        }
        vpsPanelController.preselectMigration(local.getName());
        openServerDetail(target.getId());
    }

    @FXML
    private void onServerDetailBack() {
        if (focusedPoller != null) {
            focusedPoller.stop();
            focusedPoller = null;
        }
        renderServerAdvancedList();
        goToView("serverAdvanced");
    }

    private void startPollingFocusedVpsServer(ServerEntry s) {
        AgentClient client = agentClientFor(s);
        focusedPoller = new AgentPoller(client);
        focusedPoller.startStatusPolling(java.time.Duration.ofSeconds(4),
                (ServerStatus status) -> Platform.runLater(() -> {
                    if (!s.getId().equals(focusedServerId)) return;
                    boolean changed = s.isRunning() != status.running;
                    s.setRunning(status.running);
                    // Sync from what the agent actually has installed (see AGENT_API.md's
                    // "GET /server/status" loader/version fields) rather than only ever trusting
                    // the locally-cached copy — catches the server having been reinstalled some
                    // other way (or, before this feature existed, simply never having been
                    // recorded at all). null means the agent has no record (nothing installed
                    // through it yet) - leave the local value alone rather than blanking it.
                    String realLoader = mapAgentKeyToVpsLoader(status.loader);
                    if (realLoader != null && !realLoader.equals(s.getLoader())) {
                        s.setLoader(realLoader);
                        changed = true;
                    }
                    if (status.version != null && !status.version.equals(s.getVersion())) {
                        s.setVersion(status.version);
                        changed = true;
                    }
                    if (changed) {
                        renderServerDetailHeader();
                        // renderConsole() enables/disables consoleCommandField+consoleSendBtn
                        // based on running state - missing here meant that field stayed
                        // permanently disabled whenever the entry's initial running value (at
                        // the moment the screen opened) didn't match reality, e.g. a VPS server
                        // that was already running when its entry got (re-)added, since
                        // createPendingVpsServer() never syncs that - only this poll finding out
                        // later ever corrects it, and previously nothing here told the console
                        // field to re-check.
                        renderConsole();
                        renderServerAdvancedList();
                        renderMyServersQuickList();
                        saveServers();
                    }
                }),
                (AgentException err) -> Platform.runLater(() -> log("[AGENT] Статус недоступен: " + err.getMessage())));
        focusedPoller.startConsolePolling(java.time.Duration.ofSeconds(1),
                (ConsoleSnapshot snapshot) -> Platform.runLater(() -> {
                    if (!s.getId().equals(focusedServerId)) return;
                    StringBuilder sb = new StringBuilder();
                    for (var line : snapshot.lines) {
                        sb.append("[").append(line.time).append("] ").append(line.text).append("\n");
                    }
                    String rendered = sb.toString();
                    if (!rendered.equals(consoleArea.getText())) {
                        consoleArea.setText(rendered);
                        consoleArea.positionCaret(rendered.length());
                    }
                }),
                (AgentException err) -> { /* transient console poll failures are not worth logging every second */ });
    }

    private void renderServerDetailHeader() {
        ServerEntry s = findServer(focusedServerId);
        if (s == null) return;
        // Same gameLaunchService-is-the-source-of-truth reasoning as renderFriendAddress(): a
        // local ServerEntry's own isRunning() field is only ever written by the VPS/agent branch
        // of toggleServerRunning(), so it stays false forever for local servers regardless of
        // whether one is actually running — this used to leave the header permanently claiming
        // "Остановлен"/"Запустить" even while the server was live.
        boolean running = s.isLocal() ? gameLaunchService.isServerRunning(s.getName()) : s.isRunning();
        String location = s.isLocal() ? ("localhost:" + s.getPort()) : (s.getHost() + ":" + s.getPort());
        String statusText = s.getPending() != null ? s.getPending() : (running ? "Работает" : "Остановлен");
        serverDetailTitle.setText(s.getName());
        serverDetailSub.setText(serverSubtitle(s, location, statusText));
        boolean controllable = s.canControl() && s.getPending() == null;
        serverDetailToggleBtn.setDisable(!controllable);
        serverDetailToggleBtn.setText(controllable ? (running ? "Остановить" : "Запустить") : statusText);

        // Help/command reference — same "send help, scrape the reply" trick works whether the
        // console is local (gameLaunchService's own process) or remote (agent's console poll),
        // see onOpenServerHelp(). Always shown now; only needs the server to be running.
        serverDetailHelpBtn.setVisible(true);
        serverDetailHelpBtn.setManaged(true);
        serverDetailHelpBtn.setDisable(!running);

        // Перенос на VPS (этап 4 панели VPS) — только у локального сервера.
        serverMoveToVpsBtn.setVisible(s.isLocal());
        serverMoveToVpsBtn.setManaged(s.isLocal());

        // Reinstall (change version/loader) only makes sense for an agent-managed VPS server —
        // combo preselection to the entry's current version/loader happens once, in
        // openServerDetail(), not here (this runs on every status-poll tick too - re-preselecting
        // here would keep stomping on whatever the person is mid-way through picking).
        boolean agentServer = !s.isLocal() && !s.isSsh();
        reinstallPanel.setVisible(agentServer);
        reinstallPanel.setManaged(agentServer);
        reinstallBtn.setDisable(!s.canControl());
    }

    /** «версия · ядро · адрес · статус»; у сервера панели VPS до установки версии и ядра ещё нет. */
    private static String serverSubtitle(ServerEntry s, String location, String statusText) {
        if (s.isSsh() && s.getVersion() == null) {
            return "VPS · " + location + " · Minecraft не установлен";
        }
        if (s.isSsh() && !s.isSshStatusKnown() && s.getPending() == null) {
            // Статус службы ещё не пришёл с VPS — не пишем «Остановлен» наугад.
            return s.getVersion() + " · " + s.getLoader() + " · " + location + " · служба на VPS";
        }
        return s.getVersion() + " · " + s.getLoader() + " · " + location + " · " + statusText;
    }

    @FXML
    private void onToggleFocusedServer() {
        ServerEntry s = findServer(focusedServerId);
        if (s != null) toggleServerRunning(s);
    }

    /** Changes the version/loader on an already-connected VPS server without deleting and
     *  re-adding the whole entry — previously the only way to switch versions was through
     *  startInstall() inside onCheckNewVpsConnection(), which only ever ran once, at server
     *  creation. Stops the server first if it's running (the agent 409s a reinstall over a live
     *  one — see AGENT_API.md), then installs over it exactly like the create-server flow does. */
    @FXML
    private void onReinstallVpsServer() {
        ServerEntry s = findServer(focusedServerId);
        if (s == null || s.isLocal() || s.isSsh() || !s.canControl()) return;
        String version = reinstallVersionCombo.getSelectionModel().getSelectedItem();
        String loaderLabel = reinstallLoaderCombo.getSelectionModel().getSelectedItem();
        if (version == null || loaderLabel == null) {
            showToast("Выберите версию и загрузчик", "error");
            return;
        }

        javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "Переустановить «" + s.getName() + "» на " + loaderLabel + " " + version
                        + "? Текущий мир и настройки сервера будут потеряны.");
        confirm.setHeaderText(null);
        confirm.setTitle("Переустановка сервера");
        var result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != javafx.scene.control.ButtonType.OK) {
            return;
        }

        AgentClient client = agentClientFor(s);
        reinstallBtn.setDisable(true);
        String agentLoaderKey = mapVpsLoaderToAgentKey(loaderLabel);
        log("[SERVER] Переустанавливаю \"" + s.getName() + "\" на " + loaderLabel + " " + version + "...");

        executor.submit(() -> {
            try {
                if (s.isRunning()) {
                    log("[SERVER] Останавливаю перед переустановкой...");
                    client.stopServer();
                }
                client.startInstall(agentLoaderKey, version);
                client.awaitInstall(java.time.Duration.ofSeconds(2), status ->
                        log("[SERVER] " + (status.stage != null ? status.stage : "installing") + " " + status.pct + "%"));
                Platform.runLater(() -> {
                    s.setVersion(version);
                    s.setLoader(loaderLabel);
                    s.setRunning(false);
                    reinstallBtn.setDisable(false);
                    saveServers();
                    renderServerDetailHeader();
                    renderServerAdvancedList();
                    renderMyServersQuickList();
                    showToast("Сервер «" + s.getName() + "» переустановлен на " + loaderLabel + " " + version, "success");
                });
            } catch (AgentException ex) {
                Platform.runLater(() -> {
                    reinstallBtn.setDisable(false);
                    showToast("Не удалось переустановить: " + ex.getMessage(), "error");
                });
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Agent connection gate — a bare VPS entry (host:port only, agent not yet confirmed
     * reachable) can't be controlled at all: start/stop, console, tunnel status and plugins
     * all need the agent's HTTP API, and a bare IP isn't one yet. This panel replaces the
     * normal management panels entirely until agentCheckBtn confirms a ping() succeeds.
     */
    private void renderAgentGate() {
        ServerEntry s = findServer(focusedServerId);
        if (s == null) return;
        boolean needsAgent = !s.isLocal() && !s.isSsh() && !s.isAgentConnected();
        agentPanel.setVisible(needsAgent);
        agentPanel.setManaged(needsAgent);
        // Сервер панели VPS: свой блок подключения; консоль — когда Minecraft на нём установлен
        // (её ведёт VpsManageController: журнал службы + команды через RCON).
        vpsPanelController.renderDetail(s);
        boolean showConsole = !needsAgent && (!s.isSsh() || s.isSshInstalled());
        consolePanel.setVisible(showConsole);
        consolePanel.setManaged(showConsole);
        if (needsAgent) {
            agentStatusLine.setText("Агент не подключен");
            agentCommandField.setText(s.buildAgentInstallCommand());
        }
    }

    @FXML
    private void onCopyAgentCommand() {
        copyToClipboard(agentCommandField.getText());
    }

    @FXML
    private void onCheckAgentConnection() {
        ServerEntry s = findServer(focusedServerId);
        if (s == null) return;
        agentCheckBtn.setDisable(true);
        agentStatusLine.setText("Проверка подключения...");
        AgentClient client = agentClientFor(s);
        executor.submit(() -> {
            try {
                client.ping();
                s.setAgentConnected(true);
                // Persist whatever fingerprint this connection actually saw — on first contact
                // (s.getAgentFingerprint() was null going in, so agentClientFor() built this
                // client in unpinned/TOFU mode) this is what pins every future connection to
                // exactly this certificate; on a later re-check it's just re-confirming the
                // same value that was already pinned.
                String seenFingerprint = client.getObservedFingerprint();
                if (seenFingerprint != null) {
                    s.setAgentFingerprint(seenFingerprint);
                }
                saveServers();
                Platform.runLater(() -> {
                    agentCheckBtn.setDisable(false);
                    renderAgentGate();
                    renderServerDetailHeader();
                    renderConsole();
                    renderFriendAddress();
                    renderNetworkPanel();
                    renderServerAdvancedList();
                    renderMyServersQuickList();
                    startPollingFocusedVpsServer(s);
                    if (seenFingerprint != null) {
                        showToast("Агент подключен. Отпечаток сертификата: " + seenFingerprint, "success");
                    } else {
                        showToast("Агент подключен", "success");
                    }
                });
            } catch (AgentException ex) {
                Platform.runLater(() -> {
                    agentCheckBtn.setDisable(false);
                    String reason = ex.isUnauthorized() ? "неверный ключ"
                            : ex.isTransportFailure() ? "нет связи с сервером" : ex.getMessage();
                    agentStatusLine.setText("Не удалось подключиться: " + reason);
                    showToast("Не удалось подключиться к агенту", "error");
                });
            }
        });
    }

    private void renderConsole() {
        ServerEntry s = findServer(focusedServerId);
        boolean actuallyRunning = s != null && (s.isLocal() ? gameLaunchService.isServerRunning(s.getName()) : s.isRunning());
        boolean active = s != null && (actuallyRunning || s.getPending() != null);
        consoleCommandField.setDisable(!active);
        consoleSendBtn.setDisable(!active);
        consoleChatToggle.setDisable(!actuallyRunning);
        quickCmdRow.setDisable(!actuallyRunning && restartCountdown == null);
        if (!actuallyRunning && consoleChatToggle.isSelected()) {
            consoleChatToggle.setSelected(false);
            applyChatMode(false);
        }
        if (s != null && s.isLocal()) {
            // Local server output already streams into consoleArea via appendFocusedConsoleLineIfLocal
            // as it happens — only wipe it when switching to a DIFFERENT server than whatever is
            // currently shown, so a stopped server's own log survives leaving and reopening its
            // detail screen instead of being thrown away just because it isn't running right now.
            if (!s.getId().equals(consoleAreaServerId)) {
                consoleArea.clear();
            }
        }
        if (s != null) {
            consoleAreaServerId = s.getId();
        }
        // For VPS servers, console content is driven by startPollingFocusedVpsServer's
        // getConsole() polling — nothing to do here beyond the enabled/disabled state above.
    }

    /** Подсказка в строке консоли: команда, аргумент или ник после двух букв (ui/CommandAssist). */
    private com.example.launcher.ui.CommandAssist consoleAssist;
    /** usercache.json локального сервера читается не чаще раза в 15 с — подсказка спрашивает на каждый символ. */
    private String usercacheFor;
    private long usercacheAt;
    private List<String> usercacheNames = List.of();

    private static final java.util.regex.Pattern SERVER_JOINED = java.util.regex.Pattern.compile(
            "\\]: ([A-Za-z0-9_]{3,16}) joined the game");

    private static final java.util.regex.Pattern CONSOLE_PLAYER = java.util.regex.Pattern.compile(
            "(?:UUID of player |\\]: )([A-Za-z0-9_]{3,16})(?: is | joined the game| left the game|\\[/)");

    /**
     * Ники для подсказки: у сервера на VPS — онлайн, операторы, вайтлист, баны (их уже читает
     * панель управления); у локального — кто заходил по выводу консоли и все, кто есть в его
     * usercache.json (сервер сам ведёт этот список всех заходивших).
     */
    private java.util.Collection<String> consoleKnownPlayers() {
        ServerEntry s = findServer(focusedServerId);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (s == null) return out;
        if (s.isSsh()) out.addAll(vpsManageController.knownPlayers());
        java.util.regex.Matcher m = CONSOLE_PLAYER.matcher(consoleArea.getText());
        List<String> seen = new ArrayList<>();
        while (m.find()) seen.add(m.group(1));
        for (int i = seen.size() - 1; i >= 0; i--) out.add(seen.get(i));   // свежие — первыми
        if (s.isLocal()) {
            long now = System.currentTimeMillis();
            if (!s.getName().equals(usercacheFor) || now - usercacheAt > 15_000) {
                usercacheFor = s.getName();
                usercacheAt = now;
                usercacheNames = readUsercacheNames(gameLaunchService.serverDirForName(s.getName()).resolve("usercache.json"));
            }
            out.addAll(usercacheNames);
        }
        return out;
    }

    private static List<String> readUsercacheNames(Path file) {
        List<String> names = new ArrayList<>();
        try {
            if (!Files.isRegularFile(file)) return names;
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseString(Files.readString(file));
            if (!root.isJsonArray()) return names;
            for (com.google.gson.JsonElement e : root.getAsJsonArray()) {
                if (e.isJsonObject() && e.getAsJsonObject().has("name")) names.add(e.getAsJsonObject().get("name").getAsString());
            }
        } catch (Exception ignored) {
            // битый или чужой файл — подсказки просто без этих ников
        }
        return names;
    }

    /** Плагинные команды — из ответа сервера на «help», если он есть в консоли (кнопка «Команды»). */
    private java.util.Collection<String> consoleKnownCommands() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String line : consoleArea.getText().split("\n")) {
            java.util.regex.Matcher m = HELP_COMMAND_LINE.matcher(line);
            if (!m.find()) continue;
            String cmd = m.group(1).substring(1).split("[\\s:]", 2)[0];
            if (cmd.matches("[A-Za-z0-9_:-]{2,40}")) out.add(cmd);
        }
        return out;
    }

    @FXML
    private void onSendConsoleCommand() {
        ServerEntry s = findServer(focusedServerId);
        String cmd = consoleCommandField.getText();
        boolean running = s != null && (s.isLocal() ? gameLaunchService.isServerRunning(s.getName()) : s.isRunning());
        if (s == null || cmd == null || cmd.isBlank() || !running) return;
        consoleCommandField.clear();
        if (consoleChatToggle.isSelected()) {
            sendServerChat(s, cmd.strip());
            return;
        }
        // Сообщения чата в историю команд не кладём: иначе подсказка предложила бы их как команду.
        if (consoleAssist != null) consoleAssist.remember(cmd);
        sendToServer(s, cmd, null);
    }

    /**
     * Команда серверу — локальному, на VPS или через старого агента. {@code echo} — строка, которую
     * показать в консоли вместо самой команды (для «[СЕРВЕР] …»); null — как обычно.
     */
    private void sendToServer(ServerEntry s, String cmd, String echo) {
        if (s.isSsh()) {
            if (echo != null) vpsManageController.sendQuiet(s, cmd, echo);
            else vpsManageController.sendCommand(s, cmd);
            return;
        }
        if (s.isLocal()) {
            if (echo != null) appendFocusedConsoleLineIfLocal(s.getName(), "server", echo);
            else log("[SERVER] > " + cmd.trim());
            String serverKey = s.getName();
            executor.submit(() -> gameLaunchService.sendServerCommand(serverKey, cmd));
        } else {
            AgentClient client = agentClientFor(s);
            executor.submit(() -> {
                try {
                    client.sendCommand(cmd);
                } catch (AgentException ex) {
                    Platform.runLater(() -> log("[AGENT] Команда не выполнена: " + ex.getMessage()));
                }
            });
        }
    }

    // ---------- Чат от имени сервера, быстрые команды, меню ника (11.10.2026) ----------

    /**
     * Тумблер «Чат»: пока включён, всё, что набрано в строке, уходит игрокам сообщением
     * «[СЕРВЕР] …», а не командой. Нарочно тумблер, а не префикс: владелец просил, чтобы
     * случайно ничего не написать в чат, — поэтому строка в этом режиме ещё и подсвечена.
     */
    @FXML
    private void onToggleConsoleChat() {
        applyChatMode(consoleChatToggle.isSelected());
        consoleCommandField.requestFocus();
    }

    private void applyChatMode(boolean on) {
        consoleCommandField.getStyleClass().remove("console-chat-mode");
        consoleChatToggle.getStyleClass().remove("console-chat-on");
        if (on) {
            consoleCommandField.getStyleClass().add("console-chat-mode");
            consoleChatToggle.getStyleClass().add("console-chat-on");
        }
        consoleChatToggle.setText(on ? "Чат: вкл" : "Чат");
        consoleCommandField.setPromptText(on
                ? "Сообщение всем игрокам от [СЕРВЕР]… (Enter — отправить)"
                : "Введите команду… (Tab — дописать, ↑ — прошлые команды)");
        if (consoleAssist != null) consoleAssist.setPlayersOnly(on);
    }

    /** Сообщение игрокам: золотое «[СЕРВЕР]» и белый текст, через tellraw (есть во всех версиях с 1.7). */
    private void sendServerChat(ServerEntry s, String msg) {
        if (msg.isEmpty()) return;
        if (msg.length() > 256) msg = msg.substring(0, 256);
        sendToServer(s, serverChatCommand(msg), "[" + TIME_FMT.format(Instant.now()) + "] [СЕРВЕР] " + msg);
    }

    static String serverChatCommand(String msg) {
        return "tellraw @a [\"\",{\"text\":\"[СЕРВЕР] \",\"color\":\"gold\",\"bold\":true},{\"text\":\""
                + jsonEscape(msg) + "\",\"color\":\"white\"}]";
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    private void quickCommand(String cmd) {
        ServerEntry s = findServer(focusedServerId);
        if (s == null) return;
        sendToServer(s, cmd, null);
    }

    @FXML private void onQuickDay() { quickCommand("time set day"); }
    @FXML private void onQuickClear() { quickCommand("weather clear"); }
    @FXML private void onQuickSave() { quickCommand("save-all"); }

    /** Идущий отсчёт «рестарт через минуту»; null — нет. */
    private javafx.animation.Timeline restartCountdown;

    /**
     * Рестарт с предупреждением игроков: «[СЕРВЕР] Перезапуск через 60 / 30 / 10 / 5 с», затем
     * save-all и перезапуск. Повторное нажатие отменяет. Ушли на другой сервер — тоже отмена: иначе
     * перезапустился бы не тот, о котором думаешь.
     */
    @FXML
    private void onQuickRestart() {
        if (restartCountdown != null) {
            cancelRestartCountdown("Рестарт отменён");
            ServerEntry s = findServer(focusedServerId);
            if (s != null) sendServerChat(s, "Перезапуск отменён.");
            return;
        }
        ServerEntry s = findServer(focusedServerId);
        if (s == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Рестарт сервера");
        confirm.setHeaderText("Перезапустить «" + s.getName() + "» через минуту?");
        confirm.setContentText("Игроки увидят предупреждение в чате за 60, 30, 10 и 5 секунд. Отменить можно этой же кнопкой.");
        ButtonType go = new ButtonType("Перезапустить", ButtonBar.ButtonData.OK_DONE);
        confirm.getButtonTypes().setAll(go, new ButtonType("Отмена", ButtonBar.ButtonData.CANCEL_CLOSE));
        if (confirm.showAndWait().orElse(null) != go) return;

        String id = s.getId();
        int[] left = {60};
        sendServerChat(s, "Перезапуск сервера через 60 секунд.");
        restartCountdown = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), e -> {
            ServerEntry cur = findServer(focusedServerId);
            if (cur == null || !cur.getId().equals(id)) {
                cancelRestartCountdown("Рестарт отменён: открыт другой сервер");
                return;
            }
            left[0]--;
            quickRestartBtn.setText("Отменить рестарт (" + left[0] + " с)");
            if (left[0] == 30 || left[0] == 10 || left[0] == 5) sendServerChat(cur, "Перезапуск сервера через " + left[0] + " секунд.");
            if (left[0] <= 0) {
                cancelRestartCountdown(null);
                sendToServer(cur, "save-all", null);
                restartServer(cur);
            }
        }));
        restartCountdown.setCycleCount(60);
        restartCountdown.play();
        quickRestartBtn.setText("Отменить рестарт (60 с)");
    }

    private void cancelRestartCountdown(String toast) {
        if (restartCountdown != null) restartCountdown.stop();
        restartCountdown = null;
        quickRestartBtn.setText("Рестарт через минуту");
        if (toast != null) showToast(toast, "info");
        renderConsole();
    }

    /** Перезапуск: VPS — через systemd; локальный — остановить, дождаться выхода и запустить снова. */
    private void restartServer(ServerEntry s) {
        if (s.isSsh()) {
            vpsManageController.restartNow(s);
            return;
        }
        if (!s.isLocal()) {
            sendToServer(s, "stop", null);   // старый агент сам поднимает сервер по своей настройке
            return;
        }
        // Дать save-all дописать мир, потом обычная остановка.
        executor.submit(() -> {
            try { Thread.sleep(3000); } catch (InterruptedException ignored) { return; }
            Platform.runLater(() -> stopLocalServer(s));
            for (int i = 0; i < 120 && gameLaunchService.isServerRunning(s.getName()); i++) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) { return; }
            }
            Platform.runLater(() -> startLocalServer(s));
        });
    }

    /** Щелчок по нику в консоли: что сделать с игроком. Бан — только после подтверждения. */
    private void showNickMenu(String nick, Node anchor, double x, double y) {
        ServerEntry s = findServer(focusedServerId);
        if (s == null) return;
        boolean running = s.isLocal() ? gameLaunchService.isServerRunning(s.getName()) : s.isRunning();
        ContextMenu menu = new ContextMenu();
        MenuItem insert = new MenuItem("Вставить «" + nick + "» в строку");
        insert.setOnAction(e -> {
            String t = consoleCommandField.getText();
            String add = (t.isEmpty() || t.endsWith(" ") ? "" : " ") + nick + " ";
            consoleCommandField.setText(t + add);
            consoleCommandField.requestFocus();
            consoleCommandField.positionCaret(consoleCommandField.getLength());
        });
        menu.getItems().add(insert);
        if (running) {
            menu.getItems().add(new SeparatorMenuItem());
            String[][] acts = {
                    {"Сделать оператором", "op " + nick}, {"Снять оператора", "deop " + nick},
                    {"Творческий режим", "gamemode creative " + nick}, {"Режим выживания", "gamemode survival " + nick},
                    {"Добавить в вайтлист", "whitelist add " + nick}, {"Кикнуть", "kick " + nick}};
            for (String[] a : acts) {
                MenuItem mi = new MenuItem(a[0]);
                mi.setOnAction(e -> sendToServer(s, a[1], null));
                menu.getItems().add(mi);
            }
            MenuItem ban = new MenuItem("Забанить…");
            ban.setOnAction(e -> {
                Alert c = new Alert(Alert.AlertType.CONFIRMATION);
                c.setTitle("Бан игрока");
                c.setHeaderText("Забанить " + nick + "?");
                c.setContentText("Снять бан можно командой pardon " + nick + " или в списке игроков.");
                ButtonType yes = new ButtonType("Забанить", ButtonBar.ButtonData.OK_DONE);
                c.getButtonTypes().setAll(yes, new ButtonType("Отмена", ButtonBar.ButtonData.CANCEL_CLOSE));
                if (c.showAndWait().orElse(null) == yes) sendToServer(s, "ban " + nick, null);
            });
            menu.getItems().add(ban);
        }
        menu.show(anchor, x, y);
    }

    /** Уведомление Windows — только если окно лаунчера не в фокусе или свёрнуто. */
    private void notifyIfAway(String title, String text, boolean error) {
        Platform.runLater(() -> {
            if (primaryStage != null && primaryStage.isShowing() && primaryStage.isFocused() && !primaryStage.isIconified()) return;
            executor.submit(() -> com.example.launcher.ui.DesktopNotifier.show(title, text, error));
        });
    }

    /** A real Bukkit/Paper console "help"/"?" reply logs one line per command as
     *  {@code [12:00:00 INFO]: /kit: Забрать кит своей привилегии} — this matches everything
     *  from the trailing {@code "]: "} of the log-level bracket up to end of line, which is
     *  exactly the command name plus its (often already-present) description. Anchoring on
     *  "]: " rather than a raw leading "/" is what tells an actual command line apart from
     *  index headers like "BLAuction: All commands for BLAuction" (no leading slash at all) or
     *  a player just saying something starting with a slash in normal chat. */
    private static final java.util.regex.Pattern HELP_COMMAND_LINE =
            java.util.regex.Pattern.compile("\\]:\\s*(/\\S.*)$");

    /** Sends "help" to the focused server's console — local (gameLaunchService's own process) or
     *  VPS (agent) — and, a couple seconds later (enough for the reply to actually land in
     *  consoleArea: either the agent's 1s console poll, see startPollingFocusedVpsServer(), or the
     *  local process's own stdout callback), shows every "/command ..." line currently in the
     *  console buffer as a copyable, described menu — see showServerHelpMenu().
     *
     *  <p>Falls back to "?" if "help" comes back empty: EssentialsX (extremely common on
     *  Bukkit/Paper servers, and installed on BLauncher's own brand-plugin test server) hijacks
     *  the bare "help" console command entirely and just prints "To view help from the console,
     *  type '?'." instead of Bukkit's real HelpMap output — "?" is Essentials' own alias back to
     *  the original command list. Servers without Essentials (vanilla/Forge/Fabric/NeoForge, or
     *  Bukkit-family without it) already return real commands from "help" itself, so this only
     *  ever fires as a fallback, never replacing the first attempt. */
    @FXML
    private void onOpenServerHelp() {
        ServerEntry s = findServer(focusedServerId);
        if (s == null) return;
        boolean running = s.isLocal() ? gameLaunchService.isServerRunning(s.getName()) : s.isRunning();
        if (!running) {
            showToast(tr("server_must_run_for_commands"), "error");
            return;
        }
        serverDetailHelpBtn.setDisable(true);
        log("[SERVER] " + tr("commands_fetching_log"));
        executor.submit(() -> {
            List<String> commands = sendHelpAndCollect(s, "help");
            if (commands.isEmpty()) {
                log("[SERVER] " + tr("commands_retry_log"));
                commands = sendHelpAndCollect(s, "?");
            }
            List<String> finalCommands = commands;
            Platform.runLater(() -> {
                if (s.getId().equals(focusedServerId)) {
                    boolean stillRunning = s.isLocal() ? gameLaunchService.isServerRunning(s.getName()) : s.isRunning();
                    serverDetailHelpBtn.setDisable(!stillRunning);
                    showServerHelpMenu(finalCommands);
                }
            });
        });
    }

    /** Sends {@code command} to the focused server's console and returns whichever
     *  "/command ..." lines are in the console buffer afterward. Blocks the calling (background)
     *  thread for the round-trip — callers must already be off the FX thread (see
     *  onOpenServerHelp(), which runs this inside executor.submit). */
    private List<String> sendHelpAndCollect(ServerEntry s, String command) {
        if (s.isSsh()) {
            // RCON отвечает прямо на запрос — ждать, пока ответ доедет до консоли, не нужно.
            try {
                LinkedHashSet<String> commands = new LinkedHashSet<>();
                for (String line : vpsManageController.commandSync(s, command).split("\n")) {
                    String t = line.trim();
                    if (t.startsWith("/")) commands.add(t);
                }
                return new ArrayList<>(commands);
            } catch (com.example.launcher.service.vps.VpsException e) {
                Platform.runLater(() -> log("[VPS] Команда не выполнена: " + e.getMessage()));
                return List.of();
            }
        }
        if (s.isLocal()) {
            gameLaunchService.sendServerCommand(s.getName(), command);
        } else {
            try {
                agentClientFor(s).sendCommand(command);
            } catch (AgentException ex) {
                Platform.runLater(() -> log("[AGENT] Команда не выполнена: " + ex.getMessage()));
            }
        }
        // Give the reply a moment to actually land in consoleArea before reading it back —
        // the agent's console poll runs every second (see startPollingFocusedVpsServer), and
        // a local process's stdout callback is effectively async too.
        try { Thread.sleep(2500); } catch (InterruptedException ignored) { }
        return extractHelpCommands(readConsoleTextSync());
    }

    /** consoleArea is a JavaFX control — reading its text must happen on the FX thread. Callers
     *  on a background thread (sendHelpAndCollect) block on this future instead of racing the
     *  FX thread directly for the console's current contents. */
    private String readConsoleTextSync() {
        CompletableFuture<String> future = new CompletableFuture<>();
        Platform.runLater(() -> future.complete(consoleArea.getText()));
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "";
        }
    }

    private List<String> extractHelpCommands(String consoleText) {
        LinkedHashSet<String> commands = new LinkedHashSet<>();
        for (String line : consoleText.split("\n")) {
            var m = HELP_COMMAND_LINE.matcher(line.trim());
            if (m.find()) {
                commands.add(m.group(1).trim());
            }
        }
        return new ArrayList<>(commands);
    }

    /** RU/EN descriptions for the vanilla console commands admins actually reach for — the
     *  server's own "help" reply only echoes usage syntax for some of them (e.g. Mojang commands
     *  come back as "A Mojang provided command." with no real description at all), so this fills
     *  in "what does this even do" in the menu when the server's own text is empty or unhelpful
     *  (see {@link #describeCommand}). Keyed by command name without the leading slash, each
     *  value a {ru, en} pair matching {@link #currentLanguage} the same way {@link Strings#tr}
     *  does; unknown commands (a plugin's, or one this dictionary hasn't caught up to) just show
     *  their bare name with no dash, same as before this existed. */
    private static final Map<String, Map<String, String>> VANILLA_COMMAND_DESCRIPTIONS = Map.ofEntries(
            Map.entry("advancement", Map.of("ru", "выдать/отозвать достижения игроку", "en", "grant/revoke advancements for a player")),
            Map.entry("ban", Map.of("ru", "забанить игрока по имени", "en", "ban a player by name")),
            Map.entry("ban-ip", Map.of("ru", "забанить игрока по IP", "en", "ban a player by IP")),
            Map.entry("banlist", Map.of("ru", "показать список забаненных", "en", "show the ban list")),
            Map.entry("clear", Map.of("ru", "очистить инвентарь игрока", "en", "clear a player's inventory")),
            Map.entry("clone", Map.of("ru", "скопировать область блоков в другое место", "en", "copy a region of blocks elsewhere")),
            Map.entry("data", Map.of("ru", "посмотреть/изменить NBT-данные блока, сущности или хранилища", "en", "view/edit NBT data of a block, entity, or storage")),
            Map.entry("datapack", Map.of("ru", "включить/выключить датапак", "en", "enable/disable a data pack")),
            Map.entry("deop", Map.of("ru", "убрать права оператора у игрока", "en", "remove operator status from a player")),
            Map.entry("difficulty", Map.of("ru", "сложность игры (peaceful/easy/normal/hard)", "en", "game difficulty (peaceful/easy/normal/hard)")),
            Map.entry("effect", Map.of("ru", "выдать/снять эффект зелья игроку или мобу", "en", "give/remove a potion effect from a player or mob")),
            Map.entry("enchant", Map.of("ru", "зачаровать предмет в руке игрока", "en", "enchant the item in a player's hand")),
            Map.entry("execute", Map.of("ru", "выполнить команду с условием (позиция, сущность и т.п.)", "en", "run a command conditionally (position, entity, etc.)")),
            Map.entry("experience", Map.of("ru", "выдать/установить опыт игроку", "en", "give/set a player's experience")),
            Map.entry("xp", Map.of("ru", "выдать/установить опыт игроку", "en", "give/set a player's experience")),
            Map.entry("fill", Map.of("ru", "заполнить область блоками", "en", "fill a region with blocks")),
            Map.entry("forceload", Map.of("ru", "принудительно держать чанки загруженными", "en", "force-keep chunks loaded")),
            Map.entry("function", Map.of("ru", "выполнить функцию датапака", "en", "run a data pack function")),
            Map.entry("gamemode", Map.of("ru", "сменить режим игры игроку", "en", "change a player's game mode")),
            Map.entry("gamerule", Map.of("ru", "изменить игровое правило (мобы, дневной цикл и т.п.)", "en", "change a game rule (mobs, day cycle, etc.)")),
            Map.entry("give", Map.of("ru", "выдать предмет игроку", "en", "give an item to a player")),
            Map.entry("help", Map.of("ru", "список всех команд", "en", "list all commands")),
            Map.entry("kick", Map.of("ru", "выгнать игрока с сервера", "en", "kick a player from the server")),
            Map.entry("kill", Map.of("ru", "убить игрока/сущность", "en", "kill a player/entity")),
            Map.entry("list", Map.of("ru", "показать игроков онлайн", "en", "show online players")),
            Map.entry("locate", Map.of("ru", "найти ближайшую структуру/биом", "en", "find the nearest structure/biome")),
            Map.entry("loot", Map.of("ru", "заспавнить добычу из таблицы лута", "en", "spawn loot from a loot table")),
            Map.entry("op", Map.of("ru", "выдать права оператора игроку", "en", "grant operator status to a player")),
            Map.entry("pardon", Map.of("ru", "разбанить игрока по имени", "en", "unban a player by name")),
            Map.entry("pardon-ip", Map.of("ru", "разбанить игрока по IP", "en", "unban a player by IP")),
            Map.entry("particle", Map.of("ru", "заспавнить частицы", "en", "spawn particles")),
            Map.entry("playsound", Map.of("ru", "проиграть звук игроку", "en", "play a sound to a player")),
            Map.entry("publish", Map.of("ru", "открыть локальную игру для сети (LAN)", "en", "open the game to LAN")),
            Map.entry("reload", Map.of("ru", "перезагрузить датапаки", "en", "reload data packs")),
            Map.entry("save-all", Map.of("ru", "сохранить мир на диск прямо сейчас", "en", "save the world to disk right now")),
            Map.entry("save-off", Map.of("ru", "отключить автосохранение", "en", "disable autosave")),
            Map.entry("save-on", Map.of("ru", "включить автосохранение", "en", "enable autosave")),
            Map.entry("say", Map.of("ru", "написать сообщение от имени сервера всем игрокам", "en", "broadcast a message as the server")),
            Map.entry("schedule", Map.of("ru", "запланировать выполнение функции", "en", "schedule a function to run")),
            Map.entry("scoreboard", Map.of("ru", "управление таблицами очков", "en", "manage scoreboard objectives")),
            Map.entry("seed", Map.of("ru", "показать сид мира", "en", "show the world seed")),
            Map.entry("setblock", Map.of("ru", "поставить блок в указанной точке", "en", "place a block at a given position")),
            Map.entry("setworldspawn", Map.of("ru", "задать точку спавна мира", "en", "set the world spawn point")),
            Map.entry("spawnpoint", Map.of("ru", "задать точку возрождения игрока", "en", "set a player's respawn point")),
            Map.entry("spreadplayers", Map.of("ru", "разбросать игроков по карте", "en", "scatter players across the map")),
            Map.entry("stop", Map.of("ru", "остановить сервер", "en", "stop the server")),
            Map.entry("stopsound", Map.of("ru", "остановить проигрывание звука", "en", "stop sound playback")),
            Map.entry("summon", Map.of("ru", "заспавнить сущность", "en", "summon an entity")),
            Map.entry("team", Map.of("ru", "управление командами игроков", "en", "manage player teams")),
            Map.entry("teleport", Map.of("ru", "телепортировать игрока/сущность", "en", "teleport a player/entity")),
            Map.entry("tp", Map.of("ru", "телепортировать игрока/сущность", "en", "teleport a player/entity")),
            Map.entry("tellraw", Map.of("ru", "отправить форматированное сообщение игроку", "en", "send a formatted message to a player")),
            Map.entry("time", Map.of("ru", "изменить/показать игровое время", "en", "change/show the game time")),
            Map.entry("title", Map.of("ru", "показать заголовок на экране игрока", "en", "show a title on a player's screen")),
            Map.entry("trigger", Map.of("ru", "активировать пользовательский триггер очков", "en", "activate a custom scoreboard trigger")),
            Map.entry("weather", Map.of("ru", "изменить погоду", "en", "change the weather")),
            Map.entry("whitelist", Map.of("ru", "управление белым списком игроков", "en", "manage the player whitelist")),
            Map.entry("worldborder", Map.of("ru", "изменить границу мира", "en", "change the world border")));

    /** Looks up {@link #VANILLA_COMMAND_DESCRIPTIONS} in the current UI language, falling back
     *  to Russian if a language-specific entry is somehow missing (mirrors {@link Strings#tr}). */
    private String vanillaCommandDescription(String bareKey) {
        Map<String, String> entry = VANILLA_COMMAND_DESCRIPTIONS.get(bareKey);
        if (entry == null) return null;
        String value = entry.get(currentLanguage);
        return value != null ? value : entry.get("ru");
    }

    /** One parsed "/name: description" entry from the console reply. {@code fullName} keeps a
     *  namespace prefix if the server printed one (e.g. "minecraft:advancement" for a command
     *  that collides with another plugin's own registration); {@code bareKey} is always just
     *  the command word after the last colon, lowercased, no leading slash — used both to look
     *  up {@link #VANILLA_COMMAND_DESCRIPTIONS} and to spot the "namespaced echo of a command
     *  already listed under its plain name" duplicates filtered out below. */
    private record HelpEntry(String fullName, String bareKey, String description) {}

    /** Splits a raw line like "/blauction:balance: Посмотреть баланс" on the first ": " (colon
     *  + space) — the namespace separator "plugin:command" never has a space after its colon,
     *  only the real name/description boundary does, which is what makes this split unambiguous
     *  even though both are literally colons in the same string. */
    private HelpEntry parseHelpEntry(String raw) {
        int sep = raw.indexOf(": ");
        String name = sep >= 0 ? raw.substring(0, sep) : raw.replaceAll(":$", "");
        String description = sep >= 0 ? raw.substring(sep + 2).trim() : "";
        String afterSlash = name.startsWith("/") ? name.substring(1) : name;
        String bareKey = (afterSlash.contains(":") ? afterSlash.substring(afterSlash.lastIndexOf(':') + 1) : afterSlash).toLowerCase();
        return new HelpEntry(name, bareKey, description);
    }

    /** True for "/plugin:command" style entries (a namespace prefix before the actual command),
     *  as opposed to a plain "/command". */
    private boolean isNamespaced(String fullName) {
        return fullName.startsWith("/") && fullName.substring(1).contains(":");
    }

    /** Builds the copyable command menu from raw console lines — see {@link #parseHelpEntry}
     *  for the per-line split and {@link #HELP_COMMAND_LINE} for what counts as a command line
     *  at all. Two cleanups happen here rather than in extraction, since both need the full set
     *  of entries at once to decide anything:
     *  <ol>
     *  <li>A "namespace:command" echo (Bukkit's disambiguation when two plugins register the
     *  same bare name) is dropped whenever a plain "/command" entry with the exact same
     *  description is already in the list — real duplication, not two different commands. This
     *  alone cuts roughly half the entries on a typical Paper+Essentials server, almost all
     *  "minecraft:xxx" mirrors of vanilla commands.</li>
     *  <li>Unknown commands (no description came back from the server, and none of the ones this
     *  hardcoded RU dictionary covers either) are shown bare, with no dash and no placeholder
     *  text — there's nothing honest to say about them, and a fake "no description" label would
     *  read as if the server sent that.</li>
     *  </ol> */
    /** Shown instead of a plain {@code ContextMenu}: JavaFX popups aren't clipped to the owning
     *  Stage, only to the screen — on a server with this many registered commands (~150+ even
     *  after dedup) a ContextMenu would routinely render past the launcher's own window edges.
     *  This overlay lives inside {@code overlayLayer}, which IS sized to the Stage, so the list
     *  can never extend past the launcher no matter how many rows it holds — see the
     *  {@code commandsModalCard.maxHeightProperty()} binding in {@code initModals()}, which caps
     *  it at the window's own height rather than a fixed pixel guess. */
    private void showServerHelpMenu(List<String> commands) {
        commandsModalTitle.setText(tr("commands_modal_title"));
        if (commands.isEmpty()) {
            showToast(tr("commands_no_results"), "error");
            return;
        }

        List<HelpEntry> entries = commands.stream().map(this::parseHelpEntry).toList();

        Map<String, String> plainDescriptionByKey = new HashMap<>();
        for (HelpEntry e : entries) {
            if (!isNamespaced(e.fullName())) {
                plainDescriptionByKey.put(e.bareKey(), e.description());
            }
        }

        commandsListBox.getChildren().clear();
        for (HelpEntry e : entries) {
            if (isNamespaced(e.fullName())) {
                String plain = plainDescriptionByKey.get(e.bareKey());
                if (plain != null && plain.equals(e.description())) continue; // pure duplicate of the bare command
            }

            // Vanilla/Mojang commands come back from the server with a real but useless
            // description ("A Mojang provided command." — always in English, the server's own
            // text, not something the language toggle can affect) — treated the same as no
            // description at all so the RU/EN dictionary entry (if this command has one) wins
            // instead. A plugin's own description (blauction's, Essentials', ...) is always used
            // as-is: that's real server-side content this launcher has no business overriding.
            String raw = e.description();
            boolean generic = raw.isBlank() || raw.equalsIgnoreCase("A Mojang provided command.");
            String dictDescription = generic ? vanillaCommandDescription(e.bareKey()) : null;
            String description = dictDescription != null ? dictDescription : raw;

            Label nameLabel = new Label(e.fullName());
            nameLabel.getStyleClass().addAll("build-meta", "mono");
            Label descLabel = new Label(description);
            descLabel.setWrapText(true);
            descLabel.getStyleClass().add("toggle-sub");
            descLabel.setVisible(!description.isBlank());
            descLabel.setManaged(!description.isBlank());
            VBox textBox = new VBox(2, nameLabel, descLabel);
            HBox.setHgrow(textBox, Priority.ALWAYS);

            Button copyBtn = new Button();
            copyBtn.setGraphic(new FontIcon("fth-copy"));
            copyBtn.getStyleClass().add("copy-btn");
            String copyValue = e.fullName();
            copyBtn.setOnAction(ev -> {
                copyToClipboard(copyValue);
                showToast(tr("commands_copied_prefix") + copyValue, "success");
            });

            HBox row = new HBox(10, textBox, copyBtn);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("command-row");
            commandsListBox.getChildren().add(row);
        }
        showOverlay(commandsOverlay);
    }

    @FXML
    private void onCloseCommandsMenu() {
        hideOverlay(commandsOverlay);
    }

    @FXML
    private void onOpenPluginCatalog() {
        pluginsPanelController.onOpenPluginCatalog();
        showOverlay(catalogOverlay);
    }

    @FXML
    private void onClosePluginCatalog() {
        hideOverlay(catalogOverlay);
    }

    // ---------- Profiles (Home "Профили") ----------

    @FXML
    private void onOpenProfiles() {
        renderProfilesList();
        goToView("profiles");
    }

    @FXML
    private void onShowAddProfile() {
        openProfileEditor(null);
    }

    private void openProfileEditor(UserProfile profile) {
        boolean isNew = profile == null || !userProfiles.contains(profile);
        if (profile == null) {
            profile = new UserProfile(UUID.randomUUID().toString(), "");
        }
        currentEditingProfile = profile;

        profileEditorTitle.setText(isNew ? tr("profile_new_title") : tr("profile_edit_title"));
        profileDeleteBtn.setVisible(!isNew);
        profileDeleteBtn.setManaged(!isNew);
        profileEditorNameField.setText(profile.getName() == null ? "" : profile.getName());

        if (isNew) {
            clearProfilePreviews();
        } else {
            loadProfilePreview(profileSkinPreview, profile.getSkinPath());
            loadProfilePreview(profileCapePreview, profile.getCapePath());
        }

        ensureProfileSources();

        showOverlay(profileEditorOverlay);
        profileEditorNameField.requestFocus();
        // Do not leave the whole nickname selected when the editor opens.
        Platform.runLater(() -> {
            profileEditorNameField.deselect();
            profileEditorNameField.positionCaret(profileEditorNameField.getLength());
        });
    }

    @FXML
    private void onCloseProfileEditor() {
        // A brand-new profile that uploaded files but wasn't saved should not leave orphans.
        if (currentEditingProfile != null && !userProfiles.contains(currentEditingProfile)) {
            deleteProfileFiles(currentEditingProfile);
        }
        currentEditingProfile = null;
        hideOverlay(profileEditorOverlay);
    }

    @FXML
    private void onSaveProfile() {
        if (currentEditingProfile == null) return;
        String name = profileEditorNameField.getText() == null ? "" : profileEditorNameField.getText().trim();
        if (name.isEmpty()) return;
        if (name.length() > MAX_NICKNAME_LENGTH) {
            showToast(tr("profile_name_max_warning"), "warning");
            return;
        }

        currentEditingProfile.setName(name);
        boolean isNew = !userProfiles.contains(currentEditingProfile);
        if (isNew) {
            userProfiles.add(currentEditingProfile);
        }
        saveUserProfiles();

        usernameField.setText(name);
        // Сохранённый профиль становится текущим: запоминаем его id, чтобы к нему привязался
        // (или нашёлся) свой аккаунт Microsoft.
        config.setLastProfileId(currentEditingProfile.getId());
        try { configService.saveConfig(config); } catch (Exception ignored) { }
        currentEditingProfile = null;
        hideOverlay(profileEditorOverlay);
        renderProfilesList();
        switchAccountToCurrentProfile();
    }

    @FXML
    private void onDeleteProfile() {
        if (currentEditingProfile == null) return;
        userProfiles.remove(currentEditingProfile);
        deleteProfileFiles(currentEditingProfile);
        saveUserProfiles();
        // Если удалили профиль, к которому был привязан вход, — снимаем привязку: иначе лаунчер
        // искал бы аккаунт уже несуществующего профиля.
        if (currentEditingProfile.getId() != null
                && currentEditingProfile.getId().equals(config.getLastProfileId())) {
            config.setLastProfileId("");
            try { configService.saveConfig(config); } catch (Exception ignored) { }
        }
        currentEditingProfile = null;
        hideOverlay(profileEditorOverlay);
        renderProfilesList();
        switchAccountToCurrentProfile();
    }

    @FXML
    private void onUploadProfileSkin() {
        if (currentEditingProfile == null) return;
        Path source = chooseProfileImage();
        if (source == null) return;
        if (currentEditingProfile.getSkinPath() != null && !currentEditingProfile.getSkinPath().isBlank()) {
            try { Files.deleteIfExists(Path.of(currentEditingProfile.getSkinPath())); } catch (IOException ignored) { }
        }
        Path dest = copyProfileImage(source, currentEditingProfile.getId(), "skin");
        if (dest != null) {
            currentEditingProfile.setSkinPath(dest.toString());
            loadProfilePreview(profileSkinPreview, dest.toString());

            // Automatically upload to MineSkin so the game receives a real hosted texture URL.
            uploadProfileSkinToMineskin(currentEditingProfile, dest, true);
        }
    }

    @FXML
    private void onUploadProfileCape() {
        if (currentEditingProfile == null) return;
        Path source = chooseProfileImage();
        if (source == null) return;
        if (currentEditingProfile.getCapePath() != null && !currentEditingProfile.getCapePath().isBlank()) {
            try { Files.deleteIfExists(Path.of(currentEditingProfile.getCapePath())); } catch (IOException ignored) { }
        }
        Path dest = copyProfileImage(source, currentEditingProfile.getId(), "cape");
        if (dest != null) {
            currentEditingProfile.setCapePath(dest.toString());
            loadProfilePreview(profileCapePreview, dest.toString());
        }
    }

    // ---------- Profile editor: the sites a skin or a cape is taken from ----------

    /**
     * Builds the selectable chips with the site logos, once per editor session start (and again on
     * a language switch, since the line under each site name is translated).
     *
     * <p>The chosen site of each row is remembered for the session, so reopening the editor shows
     * the same selection highlighted - and the chip the player just picked stays highlighted even
     * when the download fails, because "nothing came from OptiFine" is the answer to that click.
     */
    private void ensureProfileSources() {
        Path cacheDir = gameDir == null
                ? Path.of(System.getProperty("java.io.tmpdir"), "blauncher-site-logos")
                : gameDir.resolve("cache").resolve("site-logos");
        selectedSkinSourceId = SkinSourceTiles.render(profileSkinSourcesPane, SkinSourceCatalog.skins(),
                cacheDir, selectedSkinSourceId, this::onProfileSourceChosen, this::sourceNote, executor::submit);
        selectedCapeSourceId = SkinSourceTiles.render(profileCapeSourcesPane, SkinSourceCatalog.capes(),
                cacheDir, selectedCapeSourceId, this::onProfileSourceChosen, this::sourceNote, executor::submit);
        profileSourcesBuilt = true;
    }

    /** What this site gives, in one line: "официальный скин аккаунта", "плащ OptiFine", ... */
    private String sourceNote(SkinSourceCatalog.Source source) {
        return tr(source.noteKey());
    }

    /**
     * A source chip was picked: that site is now the chosen source for its row, and the texture is
     * pulled from it. No site is ever opened in a browser - a site that cannot hand over a file is
     * not in the list at all.
     */
    private void onProfileSourceChosen(SkinSourceCatalog.Source source) {
        if (currentEditingProfile == null) return;
        if (source.kind() == SkinSourceCatalog.Kind.SKIN) {
            selectedSkinSourceId = source.id();
        } else {
            selectedCapeSourceId = source.id();
        }

        String nickname = profileSourceNickname();
        if (nickname.isEmpty()) {
            showToast(tr("profile_source_need_nick"), "warning");
            return;
        }
        boolean cape = source.kind() == SkinSourceCatalog.Kind.CAPE;
        UserProfile target = currentEditingProfile;
        downloadProfileTexture(target, cape, source.name(),
                () -> SkinSourceCatalog.fetch(source, nickname));
    }

    /**
     * The nickname the source chips work with: what was typed into the field, or - since the
     * usual case is "my own skin" - the nickname of the profile being edited.
     */
    private String profileSourceNickname() {
        String typed = sanitizeMinecraftName(profileSourceNickField.getText());
        if (!typed.isEmpty()) return typed;
        if (currentEditingProfile == null) return "";
        String own = currentEditingProfile.getName();
        return own == null ? "" : sanitizeMinecraftName(own);
    }

    /**
     * Enter in the nickname field takes the skin from the chosen skin site: the common case is
     * "give me the skin of this nickname", and the chip for it is already selected.
     */
    @FXML
    private void onProfileNickEnter() {
        if (currentEditingProfile == null) return;
        SkinSourceCatalog.Source source = SkinSourceCatalog.find(selectedSkinSourceId);
        if (source == null) source = SkinSourceCatalog.first(SkinSourceCatalog.Kind.SKIN);
        if (source == null) return;
        onProfileSourceChosen(source);
    }

    /** "В скин" / "В плащ" from a pasted direct image link. */
    @FXML
    private void onPullSkinFromUrl() {
        pullProfileTextureFromUrl(false);
    }

    @FXML
    private void onPullCapeFromUrl() {
        pullProfileTextureFromUrl(true);
    }

    private void pullProfileTextureFromUrl(boolean cape) {
        if (currentEditingProfile == null) return;
        String url = profileSourceUrlField.getText() == null ? "" : profileSourceUrlField.getText().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            showToast(tr("profile_source_bad_url"), "warning");
            return;
        }
        UserProfile target = currentEditingProfile;
        downloadProfileTexture(target, cape, tr("profile_source_url_label"), () -> {
            Http.Response resp = Http.get(url, Map.of("User-Agent", "BLauncher"),
                    java.time.Duration.ofSeconds(30));
            if (!resp.ok() || resp.body().length == 0) {
                throw new IOException("HTTP " + resp.status());
            }
            return resp.body();
        });
    }

    /**
     * Fetches a texture in the background and puts it into the profile on the FX thread. Both
     * routes - a source chip and a pasted link - go through here, so they end in the same file,
     * the same preview and the same toast.
     */
    private void downloadProfileTexture(UserProfile target, boolean cape, String label,
                                        java.util.concurrent.Callable<byte[]> download) {
        setProfileSourcesBusy(true);
        showToast(tr("profile_source_pulling") + " " + label, "info");
        executor.submit(() -> {
            try {
                byte[] bytes = download.call();
                Path saved = saveProfileTexture(target, bytes, cape);
                if (saved == null) throw new IOException(tr("profile_source_bad_image"));
                Platform.runLater(() -> applyProfileTexture(target, saved, cape, label));
            } catch (SkinSourceCatalog.Unavailable e) {
                // The site answered fine and simply has nothing for this player: a hint, not an error.
                Platform.runLater(() -> showToast(e.getMessage(), "info"));
            } catch (Exception e) {
                // 28.10.2026 у 1e7d2840 и cdda98cc скин не скачался четыре раза подряд, а в статистике
                // была только строка кода тоста. Теперь — сайт, класс ошибки и HTTP-код; ник не шлём.
                java.util.regex.Matcher code = java.util.regex.Pattern.compile("\\b([1-5]\\d\\d)\\b")
                        .matcher(String.valueOf(e.getMessage()));
                // event.php оставляет в detail только A-Za-z0-9_ .:=- — подпись «по ссылке» по-русски станет «url».
                String src = label.replaceAll("[^A-Za-z0-9._-]", "");
                stat("skin_fail", "src=" + (src.isEmpty() ? "url" : src) + " cape=" + (cape ? 1 : 0)
                        + " exc=" + e.getClass().getSimpleName() + (code.find() ? " http=" + code.group(1) : ""));
                Platform.runLater(() -> showToast(tr("profile_source_failed") + ": " + e.getMessage(), "error"));
            } finally {
                Platform.runLater(() -> setProfileSourcesBusy(false));
            }
        });
    }

    private void applyProfileTexture(UserProfile target, Path file, boolean cape, String label) {
        if (currentEditingProfile != target) return;
        if (cape) {
            target.setCapePath(file.toString());
            loadProfilePreview(profileCapePreview, file.toString());
        } else {
            target.setSkinPath(file.toString());
            loadProfilePreview(profileSkinPreview, file.toString());
            uploadProfileSkinToMineskin(target, file, false);
        }
        if (userProfiles.contains(target)) saveUserProfiles();
        showToast(label + " → " + tr(cape ? "profile_cape_label" : "profile_skin_label"), "success");
    }

    /** Writes downloaded bytes into the profile's PNG - the same file the upload button writes. */
    private Path saveProfileTexture(UserProfile profile, byte[] bytes, boolean cape) {
        try {
            BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (image == null) return null;
            Path dir = gameDir.resolve("profiles").resolve("images");
            Files.createDirectories(dir);
            Path dest = dir.resolve(profile.getId() + (cape ? "_cape.png" : "_skin.png"));
            Files.deleteIfExists(dest);
            return ImageIO.write(image, "png", dest.toFile()) ? dest : null;
        } catch (IOException e) {
            log("[SKIN] Не удалось сохранить текстуру из источника: " + e.getMessage());
            return null;
        }
    }

    /**
     * Hands a skin file to MineSkin, the way a manual upload does: an offline nickname has no
     * texture on Mojang, so the profile's hosted URL is what friends' cards and the launch
     * arguments read. Failures are logged rather than shown - the local file already works.
     */
    private void uploadProfileSkinToMineskin(UserProfile profile, Path file, boolean announce) {
        executor.submit(() -> {
            try {
                String url = mineskinService.uploadSkin(file);
                Platform.runLater(() -> {
                    profile.setSkinUrl(url);
                    if (userProfiles.contains(profile)) saveUserProfiles();
                    if (announce) showToast("Скин загружен в MineSkin", "success");
                });
            } catch (Exception e) {
                log("[SKIN] MineSkin не принял скин: " + e.getMessage());
                if (announce) {
                    Platform.runLater(() -> showToast("Не удалось загрузить скин в MineSkin: "
                            + e.getMessage(), "error"));
                }
            }
        });
    }

    /** While a texture is on its way, the chips and the link buttons stay out of the way. */
    private void setProfileSourcesBusy(boolean busy) {
        if (profileSkinSourcesPane != null) profileSkinSourcesPane.setDisable(busy);
        if (profileCapeSourcesPane != null) profileCapeSourcesPane.setDisable(busy);
        if (profileSourceUrlSkinBtn != null) profileSourceUrlSkinBtn.setDisable(busy);
        if (profileSourceUrlCapeBtn != null) profileSourceUrlCapeBtn.setDisable(busy);
    }

    private Path chooseProfileImage() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Выберите изображение");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Изображения", "*.png", "*.jpg", "*.jpeg"),
                new FileChooser.ExtensionFilter("PNG", "*.png"),
                new FileChooser.ExtensionFilter("JPG", "*.jpg", "*.jpeg"));
        java.io.File file = chooser.showOpenDialog(primaryStage);
        return file != null ? file.toPath() : null;
    }

    private Path copyProfileImage(Path source, String profileId, String kind) {
        try {
            Path dir = gameDir.resolve("profiles").resolve("images");
            Files.createDirectories(dir);
            Path dest = dir.resolve(profileId + "_" + kind + ".png");

            BufferedImage image = ImageIO.read(source.toFile());
            if (image == null) {
                log("Не удалось прочитать изображение: " + source);
                return null;
            }

            Files.deleteIfExists(dest);
            boolean written = ImageIO.write(image, "png", dest.toFile());
            if (!written) {
                throw new IOException("PNG writer unavailable for " + source);
            }
            return dest;
        } catch (IOException e) {
            log("Не удалось сохранить " + kind + ": " + e.getMessage());
            return null;
        }
    }


    /**
     * Downloads a player's skin by nickname and saves it as a local PNG. Ely.by is tried first
     * because it can serve custom/slim skins; Mojang is used as a fallback.
     */
    private Path downloadSkinByNickname(UserProfile profile) {
        String nick = profile.getName();
        if (nick == null || nick.isBlank()) return null;
        try {
            String skinUrl = null;

            // 1) Try Ely.by first — it can host custom/slim skins that Mojang does not have.
            Http.Response elyResp = Http.get(
                    "http://skinsystem.ely.by/textures/"
                            + java.net.URLEncoder.encode(nick, java.nio.charset.StandardCharsets.UTF_8),
                    Map.of("User-Agent", "BLauncher"), java.time.Duration.ofSeconds(10));
            if (elyResp.ok()) {
                var root = com.google.gson.JsonParser.parseString(elyResp.text()).getAsJsonObject();
                if (root.has("SKIN") && root.getAsJsonObject("SKIN").has("url")) {
                    skinUrl = root.getAsJsonObject("SKIN").get("url").getAsString();
                } else if (root.has("textures") && root.getAsJsonObject("textures").has("SKIN")) {
                    var skin = root.getAsJsonObject("textures").getAsJsonObject("SKIN");
                    if (skin.has("url")) skinUrl = skin.get("url").getAsString();
                }
            }

            // 2) If Ely.by has no skin, fall back to Mojang's official skin.
            if (skinUrl == null) {
                Http.Response userResp = Http.get(
                        "https://api.mojang.com/users/profiles/minecraft/"
                                + java.net.URLEncoder.encode(nick, java.nio.charset.StandardCharsets.UTF_8),
                        Map.of(), java.time.Duration.ofSeconds(10));
                if (userResp.ok()) {
                    var user = com.google.gson.JsonParser.parseString(userResp.text()).getAsJsonObject();
                    if (user.has("id")) {
                        String uuid = user.get("id").getAsString();
                        Http.Response profileResp = Http.get(
                                "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid,
                                Map.of(), java.time.Duration.ofSeconds(10));
                        if (profileResp.ok()) {
                            var root = com.google.gson.JsonParser.parseString(profileResp.text()).getAsJsonObject();
                            if (root.has("properties")) {
                                var properties = root.getAsJsonArray("properties");
                                for (int i = 0; i < properties.size(); i++) {
                                    var prop = properties.get(i).getAsJsonObject();
                                    if (prop.has("name") && "textures".equals(prop.get("name").getAsString())
                                            && prop.has("value")) {
                                        byte[] decoded = java.util.Base64.getDecoder().decode(prop.get("value").getAsString());
                                        var textures = com.google.gson.JsonParser.parseString(
                                                        new String(decoded, java.nio.charset.StandardCharsets.UTF_8))
                                                .getAsJsonObject().getAsJsonObject("textures");
                                        if (textures != null && textures.has("SKIN")) {
                                            var skin = textures.getAsJsonObject("SKIN");
                                            if (skin.has("url")) skinUrl = skin.get("url").getAsString();
                                        }
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (skinUrl == null) return null;

            Path dir = gameDir.resolve("profiles").resolve("images");
            Files.createDirectories(dir);
            Path dest = dir.resolve(profile.getId() + "_skin_from_nick.png");
            long written = Http.download(skinUrl, dest,
                    Map.of("User-Agent", "BLauncher"), java.time.Duration.ofSeconds(30));
            if (written < 0) return null;
            return dest;
        } catch (Exception e) {
            log("[SKIN] Не удалось загрузить скин по нику " + nick + ": " + e.getMessage());
            return null;
        }
    }
    private void deleteProfileFiles(UserProfile profile) {
        if (profile.getSkinPath() != null && !profile.getSkinPath().isBlank()) {
            try { Files.deleteIfExists(Path.of(profile.getSkinPath())); } catch (IOException ignored) { }
        }
        if (profile.getCapePath() != null && !profile.getCapePath().isBlank()) {
            try { Files.deleteIfExists(Path.of(profile.getCapePath())); } catch (IOException ignored) { }
        }
    }

    /**
     * Copies the selected skin/cape into CustomSkinLoader's LocalSkin folder. The Legacy loader can
     * read those files straight from disk, which is more reliable than fetching from localhost on
     * newer Minecraft versions.
     */
    private void syncLocalSkinLoaderFiles(String username, Path skinPath, Path capePath) {
        try {
            Path localRoot = gameDir.resolve("CustomSkinLoader").resolve("LocalSkin");
            if (skinPath != null && Files.exists(skinPath)) {
                Path skinsDir = localRoot.resolve("skins");
                Files.createDirectories(skinsDir);
                Files.copy(skinPath, skinsDir.resolve(username + ".png"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            if (capePath != null && Files.exists(capePath)) {
                Path capesDir = localRoot.resolve("capes");
                Files.createDirectories(capesDir);
                Files.copy(capePath, capesDir.resolve(username + ".png"),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log("[SKIN] Не удалось обновить LocalSkin файлы: " + e.getMessage());
        }
    }

    /** Adds the running local CustomSkinAPI server to CustomSkinLoader's load list. */
    private void updateCustomSkinLoaderConfig(String rootUrl) {
        try {
            Path configFile = gameDir.resolve("CustomSkinLoader").resolve("CustomSkinLoader.json");
            if (!Files.exists(configFile)) return;

            var root = com.google.gson.JsonParser.parseString(Files.readString(configFile)).getAsJsonObject();
            var loadList = root.has("loadlist") ? root.getAsJsonArray("loadlist") : new com.google.gson.JsonArray();
            // Remove stale local entries; LocalSkin is re-added first so the direct on-disk
            // file is preferred, and BLauncher Local is added as the HTTP fallback.
            com.google.gson.JsonObject localDiskEntry = null;
            for (int i = loadList.size() - 1; i >= 0; i--) {
                var item = loadList.get(i).getAsJsonObject();
                if (item.has("name") && "BLauncher Local".equals(item.get("name").getAsString())) {
                    loadList.remove(i);
                } else if (item.has("name") && "LocalSkin".equals(item.get("name").getAsString())) {
                    localDiskEntry = item;
                    loadList.remove(i);
                }
            }
            var httpEntry = new com.google.gson.JsonObject();
            httpEntry.addProperty("name", "BLauncher Local");
            httpEntry.addProperty("type", "CustomSkinAPI");
            httpEntry.addProperty("root", rootUrl);
            // Put the direct on-disk LocalSkin entry first so it wins over Mojang/other sources.
            var newLoadList = new com.google.gson.JsonArray();
            if (localDiskEntry != null) {
                newLoadList.add(localDiskEntry);
            }
            newLoadList.add(httpEntry);
            for (var item : loadList) newLoadList.add(item);
            root.add("loadlist", newLoadList);

            // Don't let CustomSkinLoader keep showing the old cape/skin after the user updates files.
            root.addProperty("forceDisableCache", true);
            root.addProperty("cacheExpiry", 0);

            Files.writeString(configFile,
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(root));

            // Clear any already-downloaded texture cache so the new cape/skin is fetched immediately.
            deleteRecursively(gameDir.resolve("CustomSkinLoader").resolve("caches"));
            deleteRecursively(gameDir.resolve("CustomSkinLoader").resolve("ProfileCache"));
        } catch (Exception e) {
            log("[SKIN] Не удалось обновить CustomSkinLoader.json: " + e.getMessage());
        }
    }

    private void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    /** Minecraft usernames only allow A-Z, a-z, 0-9 and underscore (max 16 chars). */
    private String sanitizeMinecraftName(String raw) {
        if (raw == null) return "";
        String cleaned = raw.replaceAll("[^A-Za-z0-9_]", "");
        return cleaned.length() > MAX_NICKNAME_LENGTH ? cleaned.substring(0, MAX_NICKNAME_LENGTH) : cleaned;
    }

    private void loadProfilePreview(ImageView view, String path) {
        if (path == null || path.isBlank()) {
            view.setImage(null);
            return;
        }
        try {
            Image raw = new Image(Path.of(path).toUri().toString());
            // Skin and cape textures are pixel art: show the part that means something
            // at a whole-number zoom instead of the whole sheet scaled by 1.5 with
            // smoothing, which is what turned the preview into a blurry rectangle.
            // Non-standard images come back unchanged and are shown as they are.
            boolean isCape = view == profileCapePreview;
            view.setImage(isCape ? SkinPreview.cape(raw) : SkinPreview.skinHead(raw));
            view.setSmooth(false);
        } catch (Exception e) {
            view.setImage(null);
        }
    }

    private void clearProfilePreviews() {
        profileSkinPreview.setImage(null);
        profileCapePreview.setImage(null);
    }

    private void renderProfilesList() {
        profilesList.getChildren().clear();
        List<UserProfile> profiles = new ArrayList<>(userProfiles);
        String current = usernameField.getText();
        boolean currentSaved = current != null && profiles.stream().anyMatch(p -> current.equals(p.getName()));
        // Ник, набранный в поле и ещё не сохранённый как профиль, показывается нулевой строкой.
        // Это не профиль: его нет в userProfiles, у него нет своего файла аккаунта, и после
        // перезапуска его id был бы другим. Поэтому он живёт только до следующей перерисовки, а
        // аккаунт у него — общий account.json, тот же, что вернёт accountFileForProfile(null).
        UserProfile unsaved = null;
        if (current != null && !current.isBlank() && !currentSaved) {
            unsaved = new UserProfile(UUID.randomUUID().toString(), current);
            profiles.add(0, unsaved);
        }
        final UserProfile unsavedRow = unsaved;

        if (profiles.isEmpty()) {
            Label empty = new Label(tr("profiles_empty"));
            empty.getStyleClass().add("toggle-sub");
            profilesList.getChildren().add(empty);
            return;
        }

        UserProfile currentProfile = currentUserProfile();
        for (UserProfile profile : profiles) {
            HBox row = new HBox(8);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setMaxWidth(Double.MAX_VALUE);

            // Stretched outer oval: nickname on top, license oval below it.
            VBox profileBox = new VBox(6);
            profileBox.getStyleClass().add("profile-license-oval");
            profileBox.setMaxWidth(Double.MAX_VALUE);
            profileBox.setAlignment(Pos.CENTER_LEFT);
            profileBox.setFillWidth(false);
            HBox.setHgrow(profileBox, Priority.ALWAYS);
            profileBox.setOnMouseClicked(e -> selectProfile(profile.getName()));

            Label nameOval = new Label(profile.getName());
            nameOval.getStyleClass().add("profile-name-oval");

            // Аккаунт Microsoft у каждого профиля свой, поэтому и подпись под ником — про него,
            // а не одна на всех: у текущего профиля это живой статус (вход уже проверен), у
            // остальных — то, что записано в его файле аккаунта (без сетевых запросов).
            // Несохранённый ник показывает состояние общего account.json: именно по нему и пойдёт
            // вход, пока профиль не создан. Брать accounts/<случайный id>.json было нельзя — такого
            // файла не бывает никогда, и строка всегда врала «нет аккаунта».
            String savedUser = AuthService.peekSavedUsername(
                    profile == unsavedRow ? gameDir.resolve("account.json") : accountFileForProfile(profile));
            boolean isCurrent = profile == unsavedRow
                    ? currentProfile == null
                    : profile == currentProfile;
            boolean loggedIn = isCurrent && authService != null && authService.isLoggedIn();

            Label licenseBadge = new Label();
            licenseBadge.getStyleClass().add("license-badge");
            if (loggedIn) {
                boolean licensed = authService.hasLicense();
                licenseBadge.getStyleClass().add(licensed ? "license-badge-ok" : "license-badge-error");
                licenseBadge.setText(licensed ? tr("license_yes") : tr("license_no"));
            } else if (savedUser != null) {
                licenseBadge.getStyleClass().add("license-badge-ok");
                licenseBadge.setText(savedUser.isBlank()
                        ? tr("account_saved")
                        : tr("account_prefix") + " " + savedUser);
            } else {
                licenseBadge.getStyleClass().add("license-badge-error");
                licenseBadge.setText(tr("account_none"));
            }

            profileBox.getChildren().addAll(nameOval, licenseBadge);

            Button editBtn = new Button();
            editBtn.getStyleClass().add("profile-edit-btn");
            editBtn.setGraphic(new FontIcon("fth-edit-2"));
            editBtn.setOnAction(e -> openProfileEditor(profile));

            row.getChildren().addAll(profileHead(profile), profileBox, editBtn);
            if (profile == currentProfile || (profile == unsavedRow && currentProfile == null)) {
                row.getStyleClass().add("profile-row-current");
            }
            row.getStyleClass().add("profile-row");
            profilesList.getChildren().add(row);
        }
    }

    /** The profile's own skin head from its local file; the first letter when it has none. */
    private Node profileHead(UserProfile profile) {
        StackPane box = new StackPane();
        box.getStyleClass().add("friend-head");
        box.setMinSize(48, 48);
        box.setMaxSize(48, 48);
        Label letter = new Label(nickInitial(profile.getName()));
        letter.getStyleClass().add("rail-head-fallback");
        box.getChildren().add(letter);
        String path = profile.getSkinPath();
        if (path != null && !path.isBlank() && Files.isRegularFile(Path.of(path))) {
            Image raw = new Image(Path.of(path).toUri().toString());
            if (!raw.isError()) {
                ImageView head = new ImageView(SkinPreview.skinHead(raw));
                head.setFitWidth(48);
                head.setFitHeight(48);
                head.setSmooth(false);
                box.getChildren().add(head);
                letter.setVisible(false);
            }
        }
        return box;
    }

    private void selectProfile(String name) {
        usernameField.setText(name);
        // Запоминаем id выбранного профиля: именно по нему находится его аккаунт Microsoft,
        // и это переживает переименование профиля.
        String pickedId = null;
        for (UserProfile profile : userProfiles) {
            if (profile != null && name != null && name.equals(profile.getName())) {
                pickedId = profile.getId();
                break;
            }
        }
        // Никакого профиля с таким именем нет — значит выбрана строка несохранённого ника. Привязку
        // нужно снять, а не оставить на прежнем профиле: иначе в поле ника один, а живой аккаунт,
        // подпись «Профиль «…»» и личность в друзьях — от другого.
        String newId = pickedId == null ? "" : pickedId;
        if (!newId.equals(config.getLastProfileId())) {
            config.setLastProfileId(newId);
            try { configService.saveConfig(config); } catch (Exception ignored) { }
        }
        renderHomeAccountRow();
        renderProfilesList();
        // У каждого профиля свой вход — переключаемся на его аккаунт и восстанавливаем сессию.
        switchAccountToCurrentProfile();
    }

    private void loadUserProfiles() {
        userProfiles.clear();
        Path file = gameDir.resolve("profiles.json");
        if (!Files.exists(file)) {
            // Migrate old savedProfiles (plain nicknames) into the new JSON storage.
            List<String> old = config.getSavedProfiles();
            if (old != null) {
                for (String name : old) {
                    if (name != null && !name.isBlank()) {
                        userProfiles.add(new UserProfile(UUID.randomUUID().toString(), name));
                    }
                }
                if (!userProfiles.isEmpty()) {
                    saveUserProfiles();
                }
            }
            return;
        }
        try {
            UserProfile[] loaded = new com.google.gson.Gson().fromJson(Files.readString(file), UserProfile[].class);
            if (loaded != null) {
                for (UserProfile p : loaded) {
                    if (p != null && p.getName() != null && !p.getName().isBlank()) {
                        if (p.getId() == null || p.getId().isBlank()) {
                            p.setId(UUID.randomUUID().toString());
                        }
                        userProfiles.add(p);
                    }
                }
            }
        } catch (Exception e) {
            log("[PROFILES] Не удалось загрузить profiles.json: " + e.getMessage());
        }
    }

    private void saveUserProfiles() {
        try {
            Files.createDirectories(gameDir);
            Files.writeString(gameDir.resolve("profiles.json"),
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(userProfiles));
        } catch (IOException e) {
            log("[PROFILES] Не удалось сохранить profiles.json: " + e.getMessage());
        }
    }

    /**
     * The server whose join address the friends screen shows.
     *
     * The address panel used to live on the server detail screen and describe whichever
     * server was focused there. On the friends screen the subject is the server a friend can
     * actually join - a running local one - with the focused server as a fallback so the
     * panel is never empty while a server is still starting.
     */
    private ServerEntry addressServerForFriends() {
        for (ServerEntry candidate : servers) {
            if (candidate.isLocal() && gameLaunchService.isServerRunning(candidate.getName())) {
                return candidate;
            }
        }
        return findServer(focusedServerId);
    }

    /** The address, on the friends screen, that a friend joins the running local server at.
     *
     *  <p>This used to be the bottom half of a "Доступ из интернета" panel, whose toggle and
     *  address field only ever stored an address the player had pasted in from playit.gg by hand:
     *  BLauncher never started that agent, never created a tunnel and never read one back, so the
     *  panel promised ("Заведёт публичный адрес") more than it did. The one genuinely useful part —
     *  the address itself — now sits in the play-together panel, next to the room it belongs to.
     */
    private void renderFriendAddress() {
        ServerEntry s = addressServerForFriends();
        boolean running = s != null && s.isLocal() && gameLaunchService.isServerRunning(s.getName());
        localAddressBox.setVisible(running);
        localAddressBox.setManaged(running);
        if (!running) return;

        // Запускаем фоновое обновление адреса локальной сети. Вызов дешёвый: чаще раза в
        // 30 секунд он ничего не делает и возвращается сразу, а сам перебор адаптеров идёт
        // в executor. Поставлен здесь, потому что renderFriendAddress вызывается из десятка мест.
        refreshLanIpAsync();

        // Friends over the ZeroTier network reach this machine on its VPN IP (10.x.x.x);
        // the router-LAN IP is only visible to people on the same physical network. Prefer
        // the VPN IP whenever a room is connected (lastZeroTierIp is a cached field updated
        // by refreshZeroTierPanel(), so this stays free of blocking network calls).
        String vpn = lastZeroTierIp;
        String lan = cachedLanIp;   // из кеша; обновляется в фоне, см. refreshLanIpAsync()
        localAddressField.setText((vpn != null ? vpn : (lan != null ? lan : "127.0.0.1")) + ":" + s.getPort());
    }

    @FXML
    private void onCopyLocalAddress() {
        copyToClipboard(localAddressField.getText());
    }

    /** Best-effort LAN IPv4 address for "join me at this address" on a local network — prefers
     *  common private-range adapters over VPN/virtual ones (a system-wide proxy/VPN adapter, e.g.
     *  v2ray/tun, otherwise tends to sort first and would show an address only reachable from
     *  this same machine, not from another PC on the router). Returns null if none found. */
    /** Последний известный адрес в локальной сети. Обновляется в фоне — см. refreshLanIpAsync. */
    private volatile String cachedLanIp;
    /** Когда обновляли, чтобы не дёргать перебор адаптеров чаще, чем раз в 30 секунд. */
    private volatile long cachedLanIpAt;

    /**
     * Обновляет {@link #cachedLanIp} в фоне, не чаще раза в 30 секунд.
     *
     * <p>{@link #getLocalLanIp()} перебирает все сетевые адаптеры и спрашивает у каждого
     * {@code isUp()}, {@code isLoopback()}, {@code isVirtual()} — это нативные вызовы. Он
     * вызывался из renderFriendAddress, то есть из 5-секундного поллера, из onNavFriends и из
     * колбэков готовности локального сервера — всё это UI-поток. Javadoc рядом утверждал, что
     * путь «свободен от блокирующих сетевых вызовов»: для VPN-адреса это так (он кеширован в
     * lastZeroTierIp), а для адреса локальной сети не было. Критично именно здесь, потому что
     * лаунчер сам создаёт по виртуальному адаптеру на каждую комнату, и их число растёт.
     */
    private void refreshLanIpAsync() {
        long now = System.currentTimeMillis();
        if (now - cachedLanIpAt < 30_000L) return;
        cachedLanIpAt = now;
        executor.submit(() -> {
            String ip = getLocalLanIp();
            if (!java.util.Objects.equals(ip, cachedLanIp)) {
                cachedLanIp = ip;
                Platform.runLater(this::renderFriendAddress);   // показать, когда изменился
            }
        });
    }

    private static String getLocalLanIp() {
        try {
            List<String> candidates = new ArrayList<>();
            var ifaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                java.net.NetworkInterface iface = ifaces.nextElement();
                if (iface.isLoopback() || !iface.isUp() || iface.isVirtual()) continue;
                var addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        candidates.add(addr.getHostAddress());
                    }
                }
            }
            return candidates.stream()
                    .sorted(Comparator.comparingInt(ip -> ip.startsWith("192.168.") ? 0 : ip.startsWith("10.") ? 1 : ip.startsWith("172.") ? 2 : 3))
                    .findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Hub/satellite proxy network — only shown for servers that belong to one. */
    private void renderNetworkPanel() {
        ServerEntry s = findServer(focusedServerId);
        if (s == null || s.getNetwork() == null) {
            networkPanel.setVisible(false);
            networkPanel.setManaged(false);
            return;
        }
        networkPanel.setVisible(true);
        networkPanel.setManaged(true);
        networkPanelBody.getChildren().clear();
        if (s.getNetwork().isHub()) {
            List<ServerEntry> sats = satellitesOf(s.getId());
            Label heading = new Label("Хаб для:");
            heading.getStyleClass().add("toggle-sub");
            networkPanelBody.getChildren().add(heading);
            if (sats.isEmpty()) {
                Label none = new Label("Нет подключённых сателлитов");
                none.getStyleClass().add("toggle-sub");
                networkPanelBody.getChildren().add(none);
            } else {
                for (ServerEntry sat : sats) {
                    networkPanelBody.getChildren().add(buildNetworkLinkRow(sat));
                }
            }
        } else {
            ServerEntry hub = findServer(s.getNetwork().getHubId());
            Label heading = new Label("Часть сети:");
            heading.getStyleClass().add("toggle-sub");
            networkPanelBody.getChildren().add(heading);
            if (hub != null) {
                networkPanelBody.getChildren().add(buildNetworkLinkRow(hub));
            }
        }
    }

    private Node buildNetworkLinkRow(ServerEntry other) {
        HBox row = new HBox(10);
        row.getStyleClass().add("server-row");
        row.setAlignment(Pos.CENTER_LEFT);
        Region dot = new Region();
        dot.getStyleClass().add("server-dot");
        if (!other.isRunning()) dot.getStyleClass().add("off");
        VBox textBox = new VBox(2);
        Label name = new Label(other.getName());
        name.getStyleClass().add("server-name");
        Label sub = new Label(other.getHost() + ":" + other.getPort());
        sub.getStyleClass().addAll("server-sub", "mono");
        textBox.getChildren().addAll(name, sub);
        row.getChildren().addAll(dot, textBox);
        row.setOnMouseClicked(e -> openServerDetail(other.getId()));
        return row;
    }

    // ---------- Brand plugins (blauncher.ru catalog, sold on FunPay) — delegated to PluginsPanelController ----------
    // Logic lives in PluginsPanelController (see AGENTS.md); these @FXML methods stay here
    // only because main.fxml's onAction="#..." references must resolve on fx:controller itself.

    private void loadBrandPluginCatalog() {
        pluginsPanelController.loadBrandPluginCatalog();
    }

    private void renderBrandPluginsList() {
        pluginsPanelController.renderBrandPluginsList();
    }

    @FXML
    private void onActivateBrandPluginKey() {
        pluginsPanelController.onActivateBrandPluginKey();
    }


    // ============================================================
    //  Modal overlay helpers + toasts
    // ============================================================


    private static final int MODAL_RESIZE_NONE = 0;
    private static final int MODAL_RESIZE_N = 1;
    private static final int MODAL_RESIZE_S = 2;
    private static final int MODAL_RESIZE_W = 4;
    private static final int MODAL_RESIZE_E = 8;
    private static final double MODAL_RESIZE_MARGIN = 8;

    private void showOverlay(Node modal) {
        for (Node child : overlayLayer.getChildren()) {
            if (child == toastStack) continue;
            child.setVisible(child == modal);
            child.setManaged(child == modal);
        }
        overlayLayer.setVisible(true);
        overlayLayer.setManaged(true);
        if (modal instanceof javafx.scene.layout.Pane scrim && !scrim.getChildren().isEmpty()) {
            Motion.openDialog(scrim, scrim.getChildren().get(0));
        }
        // The scrim only knows how big it is after a layout pass, so the size is applied twice: at
        // once, for a dialog that was already laid out (switching from one to another), and again
        // on the next pulse, which is when a dialog opened for the first time has its area. The
        // second call only happens if the dialog is still the one on screen, so it cannot resize a
        // dialog the player has already closed.
        sizeModalToWindow(modal);
        javafx.application.Platform.runLater(() -> {
            if (modal.isVisible()) {
                sizeModalToWindow(modal);
            }
        });
    }

    /**
     * The margin a dialog leaves around itself, matching the padding the theme gives
     * {@code .modal-scrim}.
     *
     * <p>Public so the check can compare the two: the number exists in two places - here, because
     * the opening size is set in code, and in the theme, because the scrim is what creates the
     * area - and a change to one without the other would move the text of every dialog by half a
     * pixel, which is the fault this margin is arranged to avoid. ModalSizeCheck reads both.
     */
    public static final double MODAL_MARGIN = 22;

    /**
     * Opens a dialog at almost the whole launcher instead of a 640x360 box in the middle of it.
     *
     * <p>The dialogs are real windows here - draggable by the header, resizable by any edge - and
     * that is why their size lives in code rather than in the markup: initModals() used to stamp
     * 640x360 onto every card, so editing the FXML alone changed nothing on screen. This is the
     * other half, and it sizes the card to the scrim's own padded area.
     *
     * <p>Filling that area is also what keeps the text inside crisp. A card smaller than the
     * window is centred at (parent - card) / 2, and with the window at 1050x700 including its
     * decorations the scene is 1034x661 - an even width and an odd height - so a card of 640x360
     * lands on y.5 and the whole subtree is resampled. A filled card sits at the padding: a whole
     * number of pixels, whatever the window size, which is why this is measured rather than
     * assumed (ModalSizeCheck runs an even and an odd window for exactly this).
     */
    private void sizeModalToWindow(Node overlay) {
        if (!(overlay instanceof javafx.scene.layout.Region scrim)) return;
        javafx.scene.Node card = scrim.lookup(".modal-card");
        if (!(card instanceof javafx.scene.layout.Region region)) return;
        // Короткое подтверждение («Открыть папку сборки») растянутым на всё окно выглядит пустым
        // листом с двумя строками сверху — такие карточки остаются своего размера (25.10.2026).
        if (region.getStyleClass().contains("modal-compact")) {
            region.setTranslateX(0);
            region.setTranslateY(0);
            return;
        }

        double areaWidth = scrim.getWidth() - 2 * MODAL_MARGIN;
        double areaHeight = scrim.getHeight() - 2 * MODAL_MARGIN;
        if (areaWidth <= 0 || areaHeight <= 0) return;

        // whole pixels, and at least the card's own minimum
        double width = Math.max(region.getMinWidth(), Math.rint(areaWidth));
        double height = Math.max(region.getMinHeight(), Math.rint(areaHeight));
        region.setPrefWidth(width);
        region.setPrefHeight(height);
        region.setMaxWidth(width);
        region.setMaxHeight(height);
        // a freshly opened dialog starts centred, not wherever it was dragged to last time
        region.setTranslateX(0);
        region.setTranslateY(0);
    }

    private void hideOverlay(Node modal) {
        modal.setVisible(false);
        modal.setManaged(false);
        boolean anyOpen = overlayLayer.getChildren().stream()
                .anyMatch(c -> c != toastStack && c.isVisible());
        // Видимый тост тоже держит слой. Раньше проверялись только модалки, поэтому закрытие
        // любого диалога гасило слой вместе с тостом: показалось сообщение на 3 секунды, человек
        // за это время открыл и закрыл окно — и тост пропадал на полуслове. Обработчик истечения
        // самого тоста эту проверку делает, а здесь её не было.
        boolean toastVisible = !toastStack.getChildren().isEmpty();
        if (!anyOpen && !toastVisible) {
            overlayLayer.setVisible(false);
            overlayLayer.setManaged(false);
        }
    }

    /**
     * Marks a monitored server's status line as online/offline through a style class
     * rather than an inline colour, so the palette owns that colour: the inline version
     * kept the dark theme's green in the white theme and ignored a custom palette.
     * Removing the class first stops repeated refreshes from stacking duplicates.
     */
    private static void setOnline(Label label, boolean online) {
        label.getStyleClass().remove("status-online");
        if (online) {
            label.getStyleClass().add("status-online");
        }
    }

    /** «loader версия», чей запуск упал на нехватке/порче файлов: следующий запуск сверит библиотеки. */
    private volatile String repairLibsFor;

    /** Больше — самые старые уходят: сообщения об ошибках теперь висят, пока их не закроют. */
    private static final int MAX_TOASTS = 4;

    /**
     * Сообщение в правом нижнем углу окна с крестиком. Ошибки и предупреждения висят, пока их не
     * закроют, остальное уходит само через 8 секунд (пока над ним мышь — ждёт). 28.10.2026 владелец:
     * за прежние 3 секунды по центру внизу длинную ошибку было не дочитать.
     */
    private void showToast(String message, String type) {
        showToast(message, type, null, null);
    }

    /** То же, с кнопкой действия перед крестиком (actionLabel/onAction — null, если не нужна). */
    private void showToast(String message, String type, String actionLabel, Runnable onAction) {
        if ("error".equals(type) || "warning".equals(type)) statShownMessage(type);
        Label text = new Label(message);
        text.getStyleClass().add("toast-text");
        text.setWrapText(true);
        text.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button close = new Button();
        close.getStyleClass().add("toast-close");
        close.setGraphic(new FontIcon("fth-x"));
        close.setFocusTraversable(false);
        HBox toast = new HBox(10, text, close);
        Button action = null;
        if (actionLabel != null && onAction != null) {
            action = new Button(actionLabel);
            action.getStyleClass().add("toast-action");
            action.setMinWidth(Region.USE_PREF_SIZE);
            action.setFocusTraversable(false);
            toast.getChildren().add(1, action);
        }
        toast.setAlignment(Pos.TOP_LEFT);
        toast.getStyleClass().addAll("toast", "toast-" + type);
        Runnable remove = () -> {
            toastStack.getChildren().remove(toast);
            boolean anyOpen = overlayLayer.getChildren().stream().anyMatch(c -> c != toastStack && c.isVisible());
            if (!anyOpen && toastStack.getChildren().isEmpty()) {
                overlayLayer.setVisible(false);
                overlayLayer.setManaged(false);
            }
        };
        close.setOnAction(e -> remove.run());
        if (action != null) action.setOnAction(e -> { remove.run(); onAction.run(); });
        while (toastStack.getChildren().size() >= MAX_TOASTS) toastStack.getChildren().remove(0);
        toastStack.getChildren().add(toast);
        Motion.toastIn(toast);
        overlayLayer.setVisible(true);
        overlayLayer.setManaged(true);
        if (!"error".equals(type) && !"warning".equals(type)) {
            javafx.animation.PauseTransition hide = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(8));
            hide.setOnFinished(e -> remove.run());
            toast.setOnMouseEntered(e -> hide.pause());
            toast.setOnMouseExited(e -> hide.playFromStart());
            hide.play();
        }
    }

    /**
     * Отметка «человеку показали ошибку/предупреждение»: вместо текста (в нём бывают пути с именем
     * пользователя Windows) — метод и строка, откуда её показали; по версии лаунчера это однозначно
     * находится в исходниках. 21.10.2026 игроки уходили сразу после всплывающей ошибки, а в
     * статистике от неё не оставалось следа. Одинаковые подряд в пределах 10 с не дублируем.
     */
    private void statShownMessage(String type) {
        String at = "";
        for (StackTraceElement f : new Throwable().getStackTrace()) {
            String m = f.getMethodName();
            if (m.equals("showToast") || m.equals("statShownMessage")) continue;
            // lambda$onPlay$12 → onPlay; вызов из другого контроллера — с именем его класса.
            if (m.startsWith("lambda$")) {
                String[] p = m.split("\\$");
                m = p.length > 1 ? p[1] : m;
            }
            String cls = f.getClassName();
            cls = cls.substring(cls.lastIndexOf('.') + 1);
            int dollar = cls.indexOf('$');
            if (dollar > 0) cls = cls.substring(0, dollar);
            at = (cls.equals("MainController") ? "" : cls + ".") + m + ":" + f.getLineNumber();
            break;
        }
        long now = System.currentTimeMillis();
        if (at.equals(lastMsgAt) && now - lastMsgTime < 10_000) return;
        lastMsgAt = at;
        lastMsgTime = now;
        if (msgStats++ < 30) stat("ui_msg", type + " at=" + at + (currentScreen != null ? " on=" + currentScreen : ""));
    }

    private void copyToClipboard(String text) {
        if (text == null) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    // ============================================================
    //  Create / Edit build modal
    // ============================================================

    private void initModals() {
        newServerPropsEditor = new ServerPropsEditor(this::tr, 3);
        newServerPropsBox.getChildren().setAll(newServerPropsEditor.node());
        // Every modal card is draggable by its header and resizable by grabbing any edge or corner
        // like a normal window. Its OPENING size is not set here any more: this used to stamp
        // 640x360 onto every card, which is why making the dialogs bigger in the markup changed
        // nothing - the numbers were overwritten at start-up. sizeModalToWindow() gives each one
        // the scrim's padded area when it is opened, so it opens at almost the whole launcher and
        // on whole pixels (see that method for why the second part matters for sharp text).
        commandsModalCard.maxHeightProperty().unbind();
        catalogModalCard.maxHeightProperty().unbind();
        profileEditorCard.maxHeightProperty().unbind();

        double minWidth = 420;
        double minHeight = 320;

        for (Node overlay : overlayLayer.getChildren()) {
            if (overlay == toastStack || !(overlay instanceof StackPane scrim)) continue;
            if (scrim.getChildren().isEmpty()) continue;
            Node card = scrim.getChildren().get(0);
            if (!card.getStyleClass().contains("modal-card")) continue;
            if (!(card instanceof javafx.scene.layout.Region region)) continue;

            region.setMinWidth(minWidth);
            region.setMinHeight(minHeight);
            region.setPickOnBounds(true);
            makeModalDraggable(region);
            makeModalResizable(region);
        }

        // Minecraft nicknames are max 16 chars; prevent typing/pasting more and warn.
        profileEditorNameField.textProperty().addListener((obs, oldV, newV) -> {
            if (newV != null && newV.length() > MAX_NICKNAME_LENGTH) {
                profileEditorNameField.setText(oldV);
                showToast(tr("profile_name_max_warning"), "warning");
            }
        });


        buildVersionCombo.setItems(FXCollections.observableArrayList(versionListCache));
        versionCombosToRefresh.add(buildVersionCombo);
        buildLoaderCombo.setItems(FXCollections.observableArrayList("vanilla", "forge", "fabric", "neoforge"));
        fitComboToItemCount(buildLoaderCombo);
        buildVersionCombo.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> refreshBuildLoaderVersions());
        buildLoaderCombo.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> refreshBuildLoaderVersions());

        newServerVersionCombo.setItems(FXCollections.observableArrayList(versionListCache));
        versionCombosToRefresh.add(newServerVersionCombo);
        newServerLoaderCombo.setItems(FXCollections.observableArrayList(
                "vanilla", "forge", "fabric", "neoforge", "paper", "purpur", "pufferfish"));
        fitComboToItemCount(newServerLoaderCombo);
        // Same ceiling as the game's own RAM slider, and for the same reason — see
        // maxAllowedHeapGb(). A local server is if anything the harsher case: it runs alongside
        // the game on this very machine, so "all of the RAM" is even less available to it.
        newServerRamSlider.setMax(maxAllowedHeapGb());
        newServerRamSlider.valueProperty().addListener((obs, oldV, newV) ->
                newServerRamValueLabel.setText(formatRamGbValue(newV.doubleValue())));

        // Новые VPS добавляются по SSH (VpsPanelController): версия, ядро и память выбираются
        // при установке сервера, а не в окне подключения. Хаб/сателлит — функция старого агента,
        // для новых серверов её нет.

        // Для старых серверов на агенте: окно «переустановить с другой версией/ядром» на экране
        // сервера — см. onReinstallVpsServer(). Список ядер — ровно то, что умеет installer.js.
        reinstallVersionCombo.setItems(FXCollections.observableArrayList(versionListCache));
        versionCombosToRefresh.add(reinstallVersionCombo);
        reinstallLoaderCombo.setItems(FXCollections.observableArrayList(
                "Paper", "Purpur", "Pufferfish", "Velocity", "BungeeCord", "Waterfall", "Vanilla", "Fabric", "Pumpkin"));
        fitComboToItemCount(reinstallLoaderCombo);
        reinstallLoaderCombo.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            boolean isPumpkin = "Pumpkin".equals(newV);
            reinstallVersionCombo.setDisable(isPumpkin);
            reinstallVersionCombo.setPromptText(isPumpkin ? "Не требуется для Pumpkin" : null);
        });
    }

    private void makeModalDraggable(Node card) {
        Node header = card.lookup(".modal-header");
        if (header == null) return;

        final double[] press = new double[2];
        header.setOnMousePressed(e -> {
            press[0] = e.getSceneX() - card.getTranslateX();
            press[1] = e.getSceneY() - card.getTranslateY();
        });
        header.setOnMouseDragged(e -> {
            // Horizontal: at most half of the modal may leave the window.
            double maxX = overlayLayer.getWidth() / 2.0;
            // Vertical: the header must never leave the window, otherwise there is nothing to grab.
            double minY = -(overlayLayer.getHeight() - card.getLayoutBounds().getHeight()) / 2.0;
            double maxY = overlayLayer.getHeight() / 2.0;
            double newX = e.getSceneX() - press[0];
            double newY = e.getSceneY() - press[1];
            card.setTranslateX(Math.max(-maxX, Math.min(maxX, newX)));
            card.setTranslateY(Math.max(minY, Math.min(maxY, newY)));
        });
    }

    private void makeModalResizable(Region card) {
        final double[] start = new double[6];
        final int[] resizeEdge = new int[1];

        card.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            int hit = modalResizeEdge(card, e);
            if (hit == MODAL_RESIZE_NONE) return;
            resizeEdge[0] = hit;
            start[0] = e.getSceneX();
            start[1] = e.getSceneY();
            start[2] = card.getWidth();
            start[3] = card.getHeight();
            start[4] = card.getTranslateX();
            start[5] = card.getTranslateY();
            e.consume();
        });

        card.addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            int hit = resizeEdge[0];
            if (hit == MODAL_RESIZE_NONE) return;

            Region scrim = card.getParent() instanceof Region parent ? parent : null;
            double overlayW = scrim != null && scrim.getWidth() > 0
                    ? scrim.getWidth() : overlayLayer.getWidth();
            double overlayH = scrim != null && scrim.getHeight() > 0
                    ? scrim.getHeight() : overlayLayer.getHeight();

            double dx = e.getSceneX() - start[0];
            double dy = e.getSceneY() - start[1];
            double startW = start[2];
            double startH = start[3];
            double startTX = start[4];
            double startTY = start[5];

            // Current visual edges inside the overlay/scrim.
            double startLeft = (overlayW - startW) / 2.0 + startTX;
            double startTop = (overlayH - startH) / 2.0 + startTY;
            double startRight = startLeft + startW;
            double startBottom = startTop + startH;

            double newW = startW;
            double newH = startH;
            if ((hit & MODAL_RESIZE_E) != 0) newW += dx;
            if ((hit & MODAL_RESIZE_W) != 0) newW -= dx;
            if ((hit & MODAL_RESIZE_S) != 0) newH += dy;
            if ((hit & MODAL_RESIZE_N) != 0) newH -= dy;

            // Wall limit: the grabbed edge cannot be dragged past the launcher window border.
            // If the cursor is already against the wall, the size simply stops growing.
            double maxW = startW;
            double maxH = startH;
            if ((hit & MODAL_RESIZE_E) != 0) maxW = Math.max(maxW, overlayW - startLeft);
            if ((hit & MODAL_RESIZE_W) != 0) maxW = Math.max(maxW, startRight);
            if ((hit & MODAL_RESIZE_S) != 0) maxH = Math.max(maxH, overlayH - startTop);
            if ((hit & MODAL_RESIZE_N) != 0) maxH = Math.max(maxH, startBottom);

            newW = Math.max(card.getMinWidth(), Math.min(newW, maxW));
            newH = Math.max(card.getMinHeight(), Math.min(newH, maxH));

            // StackPane keeps resizable children centered, so when width/height change we must
            // nudge the translate values to keep the grabbed edge/corner attached to the mouse.
            double dW = newW - startW;
            double dH = newH - startH;
            double tx = start[4];
            double ty = start[5];
            if ((hit & MODAL_RESIZE_W) != 0) tx -= dW / 2.0;
            if ((hit & MODAL_RESIZE_E) != 0) tx += dW / 2.0;
            if ((hit & MODAL_RESIZE_N) != 0) ty -= dH / 2.0;
            if ((hit & MODAL_RESIZE_S) != 0) ty += dH / 2.0;

            // Keep the same limits used while dragging by the header: the card may go partly
            // outside horizontally, but its header should stay reachable vertically.
            if (overlayW > 0) {
                double maxAbsX = overlayW / 2.0;
                tx = Math.max(-maxAbsX, Math.min(maxAbsX, tx));
            }
            if (overlayH > 0) {
                double maxY = overlayH / 2.0;
                double minY = -(overlayH - newH) / 2.0;
                ty = Math.max(minY, Math.min(maxY, ty));
            }

            card.setTranslateX(tx);
            card.setTranslateY(ty);
            card.setPrefWidth(newW);
            card.setPrefHeight(newH);
            card.setMaxWidth(newW);
            card.setMaxHeight(newH);
            e.consume();
        });

        card.addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (resizeEdge[0] == MODAL_RESIZE_NONE) return;
            resizeEdge[0] = MODAL_RESIZE_NONE;
            card.setCursor(Cursor.DEFAULT);
            Node header = card.lookup(".modal-header");
            if (header != null) header.setCursor(Cursor.MOVE);
            e.consume();
        });

        card.addEventFilter(MouseEvent.MOUSE_MOVED, e -> {
            int edge = modalResizeEdge(card, e);
            Cursor cursor = cursorForModalResizeEdge(edge);
            card.setCursor(cursor);
            // The header has a "move" cursor from CSS; when an edge is hovered make the header
            // show the appropriate resize cursor too.
            Node header = card.lookup(".modal-header");
            if (header != null) {
                header.setCursor(edge != MODAL_RESIZE_NONE ? cursor : Cursor.MOVE);
            }
        });
        card.addEventFilter(MouseEvent.MOUSE_EXITED, e -> {
            if (resizeEdge[0] == MODAL_RESIZE_NONE) {
                card.setCursor(Cursor.DEFAULT);
                Node header = card.lookup(".modal-header");
                if (header != null) header.setCursor(Cursor.MOVE);
            }
        });
    }

    private int modalResizeEdge(Region card, MouseEvent e) {
        Point2D local = card.sceneToLocal(e.getSceneX(), e.getSceneY());
        double width = card.getWidth();
        double height = card.getHeight();
        if (width <= 0 || height <= 0) return MODAL_RESIZE_NONE;

        double x = local.getX();
        double y = local.getY();
        boolean nearLeft = x >= 0 && x <= MODAL_RESIZE_MARGIN;
        boolean nearRight = x <= width && x >= width - MODAL_RESIZE_MARGIN;
        boolean nearTop = y >= 0 && y <= MODAL_RESIZE_MARGIN;
        boolean nearBottom = y <= height && y >= height - MODAL_RESIZE_MARGIN;

        int edge = MODAL_RESIZE_NONE;
        if (nearTop) edge |= MODAL_RESIZE_N;
        if (nearBottom) edge |= MODAL_RESIZE_S;
        if (nearLeft) edge |= MODAL_RESIZE_W;
        if (nearRight) edge |= MODAL_RESIZE_E;
        return edge;
    }

    private Cursor cursorForModalResizeEdge(int edge) {
        if (edge == (MODAL_RESIZE_N | MODAL_RESIZE_W)) return Cursor.NW_RESIZE;
        if (edge == (MODAL_RESIZE_N | MODAL_RESIZE_E)) return Cursor.NE_RESIZE;
        if (edge == (MODAL_RESIZE_S | MODAL_RESIZE_W)) return Cursor.SW_RESIZE;
        if (edge == (MODAL_RESIZE_S | MODAL_RESIZE_E)) return Cursor.SE_RESIZE;
        if (edge == MODAL_RESIZE_N) return Cursor.N_RESIZE;
        if (edge == MODAL_RESIZE_S) return Cursor.S_RESIZE;
        if (edge == MODAL_RESIZE_W) return Cursor.W_RESIZE;
        if (edge == MODAL_RESIZE_E) return Cursor.E_RESIZE;
        return Cursor.DEFAULT;
    }


    /** Non-null while the create/edit modal is open in edit mode — see onOpenEditBuild(). */
    private Profile editingBuild;

    /** Bumped on every refreshBuildLoaderVersions() call so a slow/late-arriving fetch from a
     *  loader/version combo the user has since changed away from can't clobber the combo with
     *  stale results after a newer request already repopulated it. Read/written only on the FX
     *  thread (the field itself, not the background fetch), so no synchronization needed. */
    private long buildLoaderVersionRequestSeq = 0;

    /** Shows/hides+(re)populates buildLoaderVersionCombo for whatever version+loader is currently
     *  picked in the Create/Edit Build modal - hidden entirely for vanilla (there's nothing to
     *  pick), otherwise fetches that loader's actual downloadable builds in the background so the
     *  build can be pinned to a specific one instead of always resolving to "whatever's latest"
     *  at launch time (see resolveLatestLoaderVersion()). When editing an existing build, its
     *  already-pinned modLoaderVersion is preselected if it's still in the fetched list. */
    private void refreshBuildLoaderVersions() {
        String version = buildVersionCombo.getSelectionModel().getSelectedItem();
        String loader = buildLoaderCombo.getSelectionModel().getSelectedItem();
        boolean vanilla = loader == null || "vanilla".equals(loader);
        buildLoaderVersionBox.setVisible(!vanilla);
        buildLoaderVersionBox.setManaged(!vanilla);
        buildLoaderVersionCombo.getItems().clear();
        buildLoaderVersionCombo.setPromptText(null);
        if (vanilla || version == null) {
            return;
        }

        String preserveSelection = editingBuild != null
                && loader.equals(editingBuild.getModLoader() == null || editingBuild.getModLoader().isBlank()
                        ? "vanilla" : editingBuild.getModLoader())
                && version.equals(editingBuild.getVersionId())
                ? editingBuild.getModLoaderVersion() : null;

        buildLoaderVersionCombo.setDisable(true);
        buildLoaderVersionCombo.setPromptText("Загрузка версий...");
        long requestToken = ++buildLoaderVersionRequestSeq;
        executor.submit(() -> {
            List<String> versions = fetchLoaderVersions(version, loader);
            Platform.runLater(() -> {
                if (requestToken != buildLoaderVersionRequestSeq) return; // superseded
                buildLoaderVersionCombo.setDisable(false);
                buildLoaderVersionCombo.setPromptText(versions.isEmpty() ? "Нет доступных версий" : null);
                buildLoaderVersionCombo.setItems(FXCollections.observableArrayList(versions));
                if (preserveSelection != null && versions.contains(preserveSelection)) {
                    buildLoaderVersionCombo.getSelectionModel().select(preserveSelection);
                } else if (!versions.isEmpty()) {
                    buildLoaderVersionCombo.getSelectionModel().selectFirst();
                }
            });
        });
    }

    // ============================================================
    //  Свои настройки запуска сборки (11.10.2026)
    // ============================================================

    @FXML
    private void onToggleBuildLaunch() {
        setBuildLaunchExpanded(!buildLaunchBox.isVisible());
    }

    private void setBuildLaunchExpanded(boolean on) {
        buildLaunchBox.setVisible(on);
        buildLaunchBox.setManaged(on);
        buildLaunchToggleLink.setText((on ? "▾ " : "▸ ") + tr("build_launch_toggle"));
    }

    /** Заполняет блок из сборки (null — новая сборка); подсказки в пустых полях — общие значения. */
    private void fillBuildLaunchFields(Profile p) {
        buildMemoryField.setText(p != null && p.getBuildMemoryMb() > 0 ? formatGb(p.getBuildMemoryMb()) : "");
        buildServerField.setText(p != null ? p.getBuildServer() : "");
        buildJvmArgsField.setText(p != null ? p.getBuildJvmArgs() : "");
        buildGameArgsField.setText(p != null ? p.getBuildGameArgs() : "");
        buildMemoryField.setPromptText("как в Настройках: " + formatGb(config.getMaxMemoryMb()));
        buildServerField.setPromptText(generalHint(config.getAutoJoinServer(), "mc.example.ru:25565"));
        buildJvmArgsField.setPromptText(generalHint(config.getJvmArgs(), "-XX:+UseZGC"));
        buildGameArgsField.setPromptText(generalHint(config.getGameArgs(), "--demo"));
        boolean any = p != null && (p.getBuildMemoryMb() > 0 || !p.getBuildServer().isBlank()
                || !p.getBuildJvmArgs().isBlank() || !p.getBuildGameArgs().isBlank());
        setBuildLaunchExpanded(any);
    }

    private static String generalHint(String general, String example) {
        if (general == null || general.isBlank()) return "пусто — общее не задано (например, " + example + ")";
        String g = general.length() > 60 ? general.substring(0, 57) + "…" : general;
        return "пусто — общее: " + g;
    }

    private static String formatGb(int mb) {
        double gb = mb / 1024.0;
        return gb == Math.rint(gb) ? String.valueOf((long) gb) : String.format(Locale.ROOT, "%.1f", gb);
    }

    /** Переносит блок в сборку. Память — в ГБ («6», «6,5»), не больше безопасного максимума ПК. */
    private void applyBuildLaunchFields(Profile p) {
        String mem = buildMemoryField.getText() == null ? "" : buildMemoryField.getText().trim().replace(',', '.');
        int mb = 0;
        if (!mem.isEmpty()) {
            try {
                double gb = Math.max(0.5, Math.min(maxAllowedHeapGb(), Double.parseDouble(mem)));
                mb = (int) Math.round(gb * 1024);
            } catch (NumberFormatException e) {
                showToast("Память сборки: «" + mem + "» — не число, оставлена общая", "error");
            }
        }
        p.setBuildMemoryMb(mb);
        p.setBuildServer(buildServerField.getText());
        p.setBuildJvmArgs(buildJvmArgsField.getText());
        p.setBuildGameArgs(buildGameArgsField.getText());
    }

    @FXML
    private void onOpenCreateBuild() {
        editingBuild = null;
        modRefineToken = null;
        createBuildConfirmBtn.setDisable(false);
        createBuildModalTitle.setText("Новая сборка");
        buildNameField.clear();
        buildNameField.setDisable(false);
        buildVersionCombo.getSelectionModel().selectFirst();
        // Fabric by default: BL Core (the badge and capes) only loads there
        buildLoaderCombo.getSelectionModel().select("fabric");
        // Сборка «под мод» (кнопка «+» на экране «Моды»): сразу версия и загрузчик, для которых
        // этот мод есть. Раньше окно всегда ставило самую новую версию — 21.10.2026 это 26.3, для
        // которой большинства модов ещё нет, — мод в созданную сборку не вставал, и новичок уходил.
        if (pendingModForNewBuild != null) preselectBuildForMod(pendingModForNewBuild);
        refreshBuildLoaderVersions();
        stat("build_dialog", "open from=" + (pendingModForNewBuild != null ? "mod" : "builds"));
        createBuildConfirmBtn.setText("Создать");
        editBuildModsSection.setVisible(false);
        editBuildModsSection.setManaged(false);
        deleteBuildLink.setVisible(false);
        deleteBuildLink.setManaged(false);
        fillBuildLaunchFields(null);
        showOverlay(createBuildOverlay);
    }

    /** Opens the same modal in edit mode: name is locked (renaming would orphan the profile's
     *  on-disk mods folder, which is keyed by name — see ModrinthService), but version, loader,
     *  and the installed mods list are all editable. */
    private void onOpenEditBuild(Profile profile) {
        editingBuild = profile;
        modRefineToken = null;
        createBuildConfirmBtn.setDisable(false);
        createBuildModalTitle.setText("Редактировать сборку");
        buildNameField.setText(profile.getName());
        buildNameField.setDisable(true);
        buildVersionCombo.getSelectionModel().select(profile.getVersionId());
        String loader = profile.getModLoader() == null || profile.getModLoader().isBlank()
                ? "vanilla" : profile.getModLoader();
        buildLoaderCombo.getSelectionModel().select(loader);
        refreshBuildLoaderVersions();
        createBuildConfirmBtn.setText("Сохранить");
        editBuildModsSection.setVisible(true);
        editBuildModsSection.setManaged(true);
        deleteBuildLink.setVisible(true);
        deleteBuildLink.setManaged(true);
        renderEditBuildModsList();
        fillBuildLaunchFields(profile);
        showOverlay(createBuildOverlay);
    }

    /** Разделы окна сборки: тип контента Modrinth → заголовок, текст пустого раздела, значок-заглушка.
     *  Раньше здесь были только моды — поставленные через лаунчер ресурспаки, датапаки и шейдеры
     *  лежали в папке сборки, но увидеть или убрать их было негде. */
    private static final String[][] EDIT_BUILD_SECTIONS = {
            {"mod",          "Моды",       "Моды не установлены",       "fth-box"},
            {"resourcepack", "Ресурспаки", "Ресурспаки не установлены", "fth-image"},
            {"datapack",     "Датапаки",   "Датапаки не установлены",   "fth-database"},
            {"shader",       "Шейдеры",    "Шейдеры не установлены",    "fth-sun"},
    };

    private void renderEditBuildModsList() {
        editBuildModsList.getChildren().clear();
        if (editingBuild == null) return;

        for (String[] section : EDIT_BUILD_SECTIONS) {
            String type = section[0];
            List<ModInfo> items = modrinthService.listInstalledContent(type, editingBuild.getName());

            Label header = new Label(section[1] + (items.isEmpty() ? "" : " · " + items.size()));
            header.getStyleClass().add("field-label");
            header.setPadding(new Insets(editBuildModsList.getChildren().isEmpty() ? 0 : 10, 0, 4, 0));
            editBuildModsList.getChildren().add(header);

            if (items.isEmpty()) {
                Label empty = new Label(section[2]);
                empty.getStyleClass().add("toggle-sub");
                editBuildModsList.getChildren().add(empty);
                continue;
            }

            // Аватарка на строку: сразу ставим ту, что уже в кэше, остальные добираем одним заходом
            // в фоне — список перерисовывается после каждого «Удалить», и без кэша это была бы
            // повторная загрузка иконок всего списка на каждое нажатие.
            Map<String, StackPane> avatarsByFile = new LinkedHashMap<>();
            for (ModInfo item : items) {
                StackPane avatar = buildModAvatar(item, section[3]);
                avatarsByFile.put(item.getFileName(), avatar);
                editBuildModsList.getChildren().add(buildEditBuildModRow(type, item, avatar));
            }
            loadModAvatars(items, avatarsByFile, modrinthService.contentDir(type, editingBuild.getName()));
        }
    }

    /** Квадратная аватарка мода: заглушка сразу, картинка с Modrinth — как только найдётся. */
    private StackPane buildModAvatar(ModInfo mod) {
        return buildModAvatar(mod, "fth-box");
    }

    private StackPane buildModAvatar(ModInfo mod, String fallbackIcon) {
        StackPane avatar = new StackPane();
        avatar.getStyleClass().add("mod-avatar");
        avatar.setMinSize(34, 34);
        avatar.setPrefSize(34, 34);
        avatar.setMaxSize(34, 34);

        FontIcon fallback = new FontIcon(fallbackIcon);
        fallback.setIconSize(16);
        fallback.getStyleClass().add("mod-avatar-fallback");
        avatar.getChildren().add(fallback);

        String projectId = mod.getProjectId();
        if (projectId == null || projectId.isBlank()) projectId = modProjectIdByFile.get(mod.getFileName());
        String iconUrl = projectId == null ? null : modIconUrls.get(projectId);
        Image cached = iconUrl == null ? null : modIconImages.get(iconUrl);
        if (cached != null) setModAvatarImage(avatar, cached);
        return avatar;
    }

    private void setModAvatarImage(StackPane avatar, Image image) {
        ImageView view = new ImageView(image);
        view.setFitWidth(34);
        view.setFitHeight(34);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        avatar.getChildren().setAll(view);
    }

    private HBox buildEditBuildModRow(String type, ModInfo mod, StackPane avatar) {
        HBox row = new HBox(12);
        row.getStyleClass().add("server-row");
        row.setAlignment(Pos.CENTER_LEFT);

        VBox text = new VBox(2);
        Label name = new Label(mod.getTitle() != null && !mod.getTitle().isBlank()
                ? mod.getTitle() : mod.getFileName());
        name.getStyleClass().add("server-name");
        // Имя файла — то, что реально лежит в папке сборки: по нему мод и опознаётся, если
        // название проекта не записано (мод положили вручную).
        Label file = new Label(mod.getFileName());
        file.getStyleClass().addAll("server-sub", "mono");
        text.getChildren().addAll(name, file);
        HBox.setHgrow(text, Priority.ALWAYS);

        // «Удалить» — последний элемент строки, то есть в самом правом углу.
        Button remove = new Button("Удалить");
        remove.getStyleClass().add("server-action");
        remove.setOnAction(e -> {
            try {
                modrinthService.deleteContent(type, editingBuild.getName(), mod);
                renderEditBuildModsList();
            } catch (IOException ex) {
                log("[BUILDS] Не удалось удалить " + mod.getFileName() + ": " + ex.getMessage());
            }
        });

        row.getChildren().addAll(avatar, text);
        if (!mod.isEnabled()) {
            Label off = new Label("выключен");
            off.getStyleClass().add("role-badge");
            row.getChildren().add(off);
        }
        row.getChildren().add(remove);
        return row;
    }

    /**
     * Ищет аватарки для уже нарисованных строк.
     *
     * <p>У мода в папке сборки нет ни своего icon_url, ни описания — это обычный {@code .jar}.
     * Поэтому иконка ищется по цепочке: записанный {@code projectId} (мод ставили через лаунчер)
     * → хэш файла через Modrinth (мод скачали с Modrinth вручную, метаданных нет) → заглушка
     * (мод с CurseForge или собственной сборки — иконки взять неоткуда). Хэшируются только те
     * файлы, для которых projectId неизвестен.
     */
    private void loadModAvatars(List<ModInfo> mods, Map<String, StackPane> avatarsByFile, Path modsDir) {
        if (mods.isEmpty()) return;

        executor.submit(() -> {
            try {
                // projectId на файл: у поставленных лаунчером он записан в .installed.json, у
                // положенных руками — добывается по хэшу файла (Modrinth опознаёт по нему версию).
                Map<String, String> projectIdByFile = new HashMap<>();
                Map<String, String> fileByHash = new HashMap<>();
                for (ModInfo mod : mods) {
                    String id = mod.getProjectId();
                    if (id == null || id.isBlank()) id = modProjectIdByFile.get(mod.getFileName());
                    if (id != null && !id.isBlank()) {
                        projectIdByFile.put(mod.getFileName(), id);
                        continue;
                    }
                    String hash = sha1For(modsDir.resolve(mod.getFileName()));
                    if (hash != null) fileByHash.put(hash, mod.getFileName());
                }

                if (!fileByHash.isEmpty()) {
                    for (var entry : modrinthService.projectIdsByHash(fileByHash.keySet()).entrySet()) {
                        String fileName = fileByHash.get(entry.getKey());
                        if (fileName != null) projectIdByFile.put(fileName, entry.getValue());
                    }
                }
                if (projectIdByFile.isEmpty()) return;

                // Один запрос иконок на весь список (и на projectId, и на slug — Modrinth отдаёт оба).
                Map<String, String> icons = modrinthService.projectIcons(projectIdByFile.values());
                for (var entry : projectIdByFile.entrySet()) {
                    String url = icons.get(entry.getValue());
                    if (url == null) continue;
                    synchronized (modIconUrls) { modIconUrls.put(entry.getValue(), url); }
                    modProjectIdByFile.put(entry.getKey(), entry.getValue());

                    StackPane avatar = avatarsByFile.get(entry.getKey());
                    if (avatar == null) continue;
                    Image image = loadModIconCached(url);
                    if (image != null) Platform.runLater(() -> setModAvatarImage(avatar, image));
                }
            } catch (Exception e) {
                log("[BUILDS] Не удалось получить аватарки модов: " + e.getMessage());
            }
        });
    }

    /** Декодирует иконку один раз на url: список модов перерисовывается после каждого удаления. */
    private Image loadModIconCached(String url) {
        synchronized (modIconImages) {
            if (modIconImages.containsKey(url)) return modIconImages.get(url);
        }
        Image image = com.example.launcher.ui.IconLoader.load(url);
        synchronized (modIconImages) {
            modIconImages.put(url, image);
        }
        return image;
    }

    /** sha1 файла, посчитанный не больше одного раза: ключ включает размер и дату изменения. */
    private String sha1For(Path file) {
        try {
            String key = file.getFileName() + "|" + Files.size(file) + "|" + Files.getLastModifiedTime(file).toMillis();
            synchronized (sha1Cache) {
                String cached = sha1Cache.get(key);
                if (cached != null) return cached;
            }
            String hash = sha1Of(file);
            if (hash != null) {
                synchronized (sha1Cache) { sha1Cache.put(key, hash); }
            }
            return hash;
        } catch (IOException e) {
            return null;
        }
    }

    /** sha1 файла (Modrinth опознаёт по нему версию проекта), или null, если файл не прочитался. */
    private static String sha1Of(Path file) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
            try (var in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception e) {
            return null;
        }
    }

    @FXML
    private void onDeleteEditingBuild() {
        if (editingBuild == null) return;
        Profile toDelete = editingBuild;
        javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                "Удалить сборку «" + toDelete.getName() + "»? Настройки сборки будут потеряны. "
                        + "Сами моды останутся на диске, в папке mods\\" + toDelete.getName() + ".");
        confirm.setHeaderText(null);
        confirm.setTitle("Удаление сборки");
        var result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != javafx.scene.control.ButtonType.OK) {
            return;
        }
        builds.remove(toDelete);
        try {
            profileService.deleteProfile(toDelete.getName());
        } catch (IOException e) {
            log("[BUILDS] Не удалось удалить сборку: " + e.getMessage());
        }
        // The player's mod jars are left alone, but the folder must stop looking like a build to
        // discoverModFolderBuilds() - otherwise opening the Builds tab would instantly bring the
        // deleted build back.
        try {
            Path staging = gameDir.resolve("mods").resolve(toDelete.getName());
            if (Files.isDirectory(staging)) {
                Files.writeString(staging.resolve(DISMISSED_MARKER),
                        "Эта папка больше не считается сборкой BLauncher.\n"
                                + "Удалите этот файл, чтобы лаунчер снова создал сборку из неё.\n");
            }
        } catch (IOException e) {
            log("[BUILDS] Не удалось пометить папку модов удалённой сборки: " + e.getMessage());
        }
        if (selectedBuild == toDelete) {
            selectedBuild = null;
        }
        if (exportSelectedBuild == toDelete) {
            exportSelectedBuild = null;
        }
        editingBuild = null;
        hideOverlay(createBuildOverlay);
        renderHomeBuildGrid();
        renderBuildsGrid();
        renderCurrentBuildPanel();
        refreshServerBuildCombo();
        showToast("Сборка «" + toDelete.getName() + "» удалена", "success");
    }

    @FXML
    private void onCloseCreateBuild() {
        if (editingBuild == null) {
            stat("build_dialog", "cancel from=" + (pendingModForNewBuild != null ? "mod" : "builds"));
        }
        editingBuild = null;
        pendingModForNewBuild = null;
        hideOverlay(createBuildOverlay);
    }

    /**
     * Версия и загрузчик окна новой сборки под мод: самая новая версия из списка окна, для
     * которой у мода есть файл (по результату поиска Modrinth), и загрузчик мода — Fabric, если
     * мод под него есть (на нём работает BL Core), иначе NeoForge, затем Forge. Нет данных о
     * версиях — оставляем как было. Для шейдеров и паков загрузчик не трогаем.
     */
    private void preselectBuildForMod(ModInfo mod) {
        java.util.Set<String> supported = new java.util.HashSet<>(mod.getGameVersions());
        for (String v : buildVersionCombo.getItems()) {
            if (supported.contains(v)) {
                buildVersionCombo.getSelectionModel().select(v);
                break;
            }
        }
        if (!"mod".equals(activeContentType)) return;
        List<String> loaders = mod.getLoaders();
        for (String l : List.of("fabric", "neoforge", "forge")) {
            if (loaders.contains(l) && buildLoaderCombo.getItems().contains(l)) {
                buildLoaderCombo.getSelectionModel().select(l);
                break;
            }
        }
        // Мод только под Quilt: ближе всего Fabric, Quilt-моды обычно запускаются и на нём.
        refinePreselectFromVersions(mod);
    }

    /**
     * Уточняет выбор preselectBuildForMod по настоящему списку файлов мода. Из поиска приходят
     * версии игры и загрузчики мода ВПЕРЕМЕШКУ: «есть 1.21.4» и «есть NeoForge» не значит, что есть
     * NeoForge под 1.21.4. 26.10.2026 новичок 9527a74f создал сборку под мод с NeoForge 1.21.4, и мод
     * в неё не встал ни с первой попытки, ни с двух следующих. Здесь ищем самую новую версию игры
     * из списка окна, для которой файл под Fabric / NeoForge / Forge действительно есть. Если
     * человек за это время уже сам поменял выбор — не трогаем.
     */
    private void refinePreselectFromVersions(ModInfo mod) {
        String shownVersion = buildVersionCombo.getSelectionModel().getSelectedItem();
        String shownLoader = buildLoaderCombo.getSelectionModel().getSelectedItem();
        List<String> gameVersions = new ArrayList<>(buildVersionCombo.getItems());
        List<String> loaders = new ArrayList<>(buildLoaderCombo.getItems());
        // «Создать» ждёт ответа Modrinth: 27.10.2026 120bf4ff нажал её через 8 секунд, раньше, чем
        // пришёл список файлов мода, — сборка создалась по неточным данным поиска (Forge 1.21.1),
        // мод в неё не встал, и новичок ушёл. runLater — чтобы встать после onOpenCreateBuild,
        // который сам выставляет текст кнопки.
        Object token = new Object();
        modRefineToken = token;
        Platform.runLater(() -> setCreateBuildWaiting(token, true));
        // Modrinth не ответил за 15 с — не держим окно запертым.
        javafx.animation.PauseTransition limit = new javafx.animation.PauseTransition(Duration.seconds(15));
        limit.setOnFinished(e -> setCreateBuildWaiting(token, false));
        limit.play();
        executor.submit(() -> {
            String[] best;
            try {
                best = bestBuildForMod(mod.getProjectId(), gameVersions, loaders);
            } catch (Exception e) {
                best = null;   // нет сети — остаётся выбор по данным поиска
            }
            if (best == null) {
                Platform.runLater(() -> setCreateBuildWaiting(token, false));
                return;
            }
            String v = best[0], l = best[1];
            Platform.runLater(() -> {
                setCreateBuildWaiting(token, false);
                if (pendingModForNewBuild != mod || editingBuild != null || !createBuildOverlay.isVisible()) return;
                if (!java.util.Objects.equals(buildVersionCombo.getSelectionModel().getSelectedItem(), shownVersion)
                        || !java.util.Objects.equals(buildLoaderCombo.getSelectionModel().getSelectedItem(), shownLoader)) return;
                if (v.equals(shownVersion) && l.equals(shownLoader)) return;
                buildVersionCombo.getSelectionModel().select(v);
                buildLoaderCombo.getSelectionModel().select(l);
                log("[BUILDS] Под «" + mod.getTitle() + "» выбрано " + l + " " + v + " — для неё у мода есть файл");
            });
        });
    }

    /** Подбор версии под мод, которого ждёт кнопка «Создать»; null — никто не ждёт. */
    private Object modRefineToken;

    private void setCreateBuildWaiting(Object token, boolean waiting) {
        if (token != modRefineToken) return;
        if (!waiting) modRefineToken = null;
        createBuildConfirmBtn.setDisable(waiting);
        createBuildConfirmBtn.setText(waiting ? "Подбираю версию…" : "Создать");
    }

    /**
     * Самая новая версия игры из {@code gameVersions} (по порядку списка) и загрузчик Fabric /
     * NeoForge / Forge, для которых у проекта на Modrinth действительно есть файл; Quilt считается
     * за Fabric. Нет такой пары — null. Сеть: исключение наружу.
     */
    private String[] bestBuildForMod(String projectId, List<String> gameVersions, List<String> loaders) throws Exception {
        Map<String, java.util.Set<String>> loadersByGame = new HashMap<>();
        for (var el : modrinthService.listVersions(projectId, null, null)) {
            var v = el.getAsJsonObject();
            if (!v.has("game_versions") || !v.has("loaders")) continue;
            for (var gv : v.getAsJsonArray("game_versions")) {
                var set = loadersByGame.computeIfAbsent(gv.getAsString(), k -> new java.util.HashSet<>());
                for (var l : v.getAsJsonArray("loaders")) set.add(l.getAsString());
            }
        }
        for (String gv : gameVersions) {
            java.util.Set<String> have = loadersByGame.get(gv);
            if (have == null) continue;
            if (have.contains("quilt")) have.add("fabric");
            for (String l : List.of("fabric", "neoforge", "forge")) {
                if (have.contains(l) && loaders.contains(l)) return new String[] {gv, l};
            }
        }
        return null;
    }

    /**
     * Мода нет под эту сборку — предлагаем сразу сборку, где он есть, вместо одного «нет версии».
     * 27.10.2026 120bf4ff трижды получил «нет версии» в двух сборках подряд и ушёл, так и не
     * узнав, под что мод вообще есть.
     */
    private void offerBuildWhereModFits(ModInfo mod, Profile build, String loader, String[] fit) {
        String text = "«" + mod.getTitle() + "» нет для " + build.getVersionId()
                + (loader == null || "vanilla".equals(loader) ? "" : " " + loader)
                + ". Он есть для " + fit[1] + " " + fit[0] + " — создать сборку под эту версию и поставить мод в неё?";
        Alert ask = new Alert(Alert.AlertType.CONFIRMATION);
        ask.initOwner(primaryStage);
        ask.setTitle("BLauncher");
        ask.setHeaderText("Мод не подходит к сборке «" + build.getName() + "»");
        ask.setContentText(text);
        ButtonType create = new ButtonType("Создать сборку", ButtonBar.ButtonData.OK_DONE);
        ButtonType no = new ButtonType("Не надо", ButtonBar.ButtonData.CANCEL_CLOSE);
        ask.getButtonTypes().setAll(create, no);
        stat("mod_fit_offer", fit[1] + " " + fit[0]);
        if (ask.showAndWait().orElse(no) != create) return;
        stat("mod_fit_accept", fit[1] + " " + fit[0]);
        pendingModForNewBuild = mod;
        onOpenCreateBuild();
        // Пара уже точная — подбор по поиску и ожидание кнопки не нужны.
        modRefineToken = null;
        createBuildConfirmBtn.setDisable(false);
        createBuildConfirmBtn.setText("Создать");
        buildVersionCombo.getSelectionModel().select(fit[0]);
        buildLoaderCombo.getSelectionModel().select(fit[1]);
    }

    @FXML
    private void onConfirmCreateBuild() {
        String version = buildVersionCombo.getSelectionModel().getSelectedItem();
        String loader = buildLoaderCombo.getSelectionModel().getSelectedItem();
        // null for vanilla (the picker is hidden) or if nothing's loaded/selected yet in it -
        // launchGame() already treats a blank modLoaderVersion as "resolve+pin the latest build
        // at launch time" (see resolveLatestLoaderVersion()), so leaving it unset here is a safe
        // fallback, not a dead end.
        String loaderVersion = "vanilla".equals(loader) ? null : buildLoaderVersionCombo.getSelectionModel().getSelectedItem();

        String unavailable = loaderUnavailableReason(loader, version);
        if (unavailable != null) {
            showToast(unavailable, "warning");
            return;
        }

        if (editingBuild != null) {
            editingBuild.setVersionId(version);
            editingBuild.setModLoader(loader);
            editingBuild.setModLoaderVersion(loaderVersion);
            applyBuildLaunchFields(editingBuild);
            try {
                profileService.saveProfile(editingBuild);
            } catch (IOException e) {
                log("[BUILDS] Не удалось сохранить сборку: " + e.getMessage());
            }
            hideOverlay(createBuildOverlay);
            renderHomeBuildGrid();
            renderBuildsGrid();
            renderCurrentBuildPanel();
            refreshServerBuildCombo();
            showToast("Сборка «" + editingBuild.getName() + "» обновлена", "success");
            editingBuild = null;
            return;
        }

        String name = buildNameField.getText() != null ? buildNameField.getText().trim() : "";
        if (name.isEmpty()) {
            name = "Новая сборка " + (builds.size() + 1);
        }
        Profile profile = new Profile(name, usernameField.getText(), "", version, loader);
        profile.setModLoaderVersion(loaderVersion);
        applyBuildLaunchFields(profile);
        stat("build_created", loader + " " + version + (pendingModForNewBuild != null ? " from=mod" : ""));
        profile.setIconText(name.substring(0, Math.min(2, name.length())).toUpperCase());
        profile.setIconGradient(randomCardGradient());
        // Сразу с сердечком (26.10.2026): на главной полоса показывает только отмеченные сборки, и
        // новичок, которого после создания переносит на главную, своей сборки там не видел — только
        // «Без модов и сборок». Так же для модпаков и сборок, импортированных по коду.
        profile.setLiked(true);
        builds.add(profile);
        try {
            profileService.saveProfile(profile);
        } catch (IOException e) {
            log("[BUILDS] Не удалось сохранить сборку: " + e.getMessage());
        }
        selectedBuild = profile;
        selectedServer = null;   // новая сборка выбирается как обычно, без режима сервера
        hideOverlay(createBuildOverlay);
        renderHomeBuildGrid();
        renderBuildsGrid();
        renderCurrentBuildPanel();
        refreshServerBuildCombo();

        // Созданная не «под мод» сборка — сразу на главную к «Играть» (25.10.2026). За неделю пятеро
        // новичков создали сборку и ушли, так и не нажав «Играть»: после окна их оставляло на экране
        // «Сборки», и что делать дальше, было непонятно (38c28a8f пошёл искать модпаки и закрыл лаунчер).
        if (pendingModForNewBuild == null) {
            goToView("home");
            showToast("Сборка «" + profile.getName() + "» создана — нажмите «Играть». Первый запуск скачает игру, "
                    + "это пара минут; моды можно добавить в разделе «Моды»", "success");
            pulsePlayButton();
        } else {
            showToast("Сборка «" + profile.getName() + "» создана", "success");
        }

        // "+ Новая сборка..." from the Mods screen's add-mod menu (showAddModTargetMenu) routes
        // through here rather than installing directly, since the build has to exist first -
        // finish that queued install now that it does.
        if (pendingModForNewBuild != null) {
            ModInfo queuedMod = pendingModForNewBuild;
            pendingModForNewBuild = null;
            installModIntoBuild(queuedMod, profile, null);
        }
    }

    private void pulsePlayButton() {
        javafx.animation.ScaleTransition pulse = new javafx.animation.ScaleTransition(Duration.millis(380), playButton);
        pulse.setFromX(1); pulse.setFromY(1);
        pulse.setToX(1.06); pulse.setToY(1.06);
        pulse.setAutoReverse(true);
        pulse.setCycleCount(4);
        pulse.play();
    }

    /** Подсказку «моды добавлены — теперь Играть» показываем один раз за сессию. */
    private boolean playHintAfterModsShown;

    /**
     * После первого добавленного мода, если «Играть» ещё не нажимали. 28.10.2026 новичок 41baf131
     * создал Forge 1.20.1, положил семь модов, пять минут ходил по меню и ушёл, так и не запустив
     * игру: тост «нажмите Играть» после создания сборки он видел, но ушёл в «Моды», а оттуда к
     * кнопке не вернулся. Здесь — кнопка, которая сама ведёт к «Играть».
     */
    private void hintPlayAfterMods(Profile build) {
        if (playClicked || playHintAfterModsShown) return;
        playHintAfterModsShown = true;
        showToast("Мод добавлен в «" + build.getName() + "». Добавите всё, что нужно, — возвращайтесь на главную "
                + "и нажимайте «Играть»: моды подхватятся сами", "success", "К «Играть»", () -> {
            stat("play_hint", "mods");
            selectedBuild = build;
            selectedServer = null;
            goToView("home");
            renderCurrentBuildPanel();
            pulsePlayButton();
        });
    }

    /** Card icon background gradients — matches the small fixed palette used throughout
     *  blauncher-ui-concept-2-1.html's own sample build cards (AT/VS/PP/CA/SE). */
    private static final String[] CARD_GRADIENTS = {
            "#4b5563,#1f2937", "#0f766e,#022c22", "#9a3412,#450a0a", "#a16207,#422006", "#1e40af,#0f172a"
    };

    private String randomCardGradient() {
        return CARD_GRADIENTS[(int) (Math.random() * CARD_GRADIENTS.length)];
    }

    // ============================================================
    //  Import / Export modal — reuses ModShareCodec exactly as before.
    // ============================================================

    @FXML
    private void onOpenExport() {
        onShareTabExport();
        showOverlay(shareOverlay);
    }

    @FXML
    private void onOpenImport() {
        onShareTabImport();
        showOverlay(shareOverlay);
    }

    @FXML
    private void onCloseShare() {
        hideOverlay(shareOverlay);
    }

    /** Which build the Export tab currently targets — deliberately separate from selectedBuild
     *  (Home/Builds' own "currently selected" concept) so picking a build to export doesn't
     *  silently reassign what Home shows as selected. Seeded from selectedBuild once, the first
     *  time the tab is opened, purely as a convenient default. */
    private Profile exportSelectedBuild;

    @FXML
    private void onShareTabExport() {
        shareTabExport.setSelected(true);
        shareTabImport.setSelected(false);
        exportPanel.setVisible(true);
        exportPanel.setManaged(true);
        importPanel.setVisible(false);
        importPanel.setManaged(false);

        if (exportSelectedBuild == null && selectedBuild != null && !selectedBuild.isVanillaEntry()) {
            exportSelectedBuild = selectedBuild;
        }
        renderExportBuildRow();
        renderExportLink();
    }

    /** The vertical list of build rows (avatar/name/like/version) the Export tab picks a target
     *  from, stacked top-to-bottom — its own click handler only updates exportSelectedBuild, it
     *  must NOT reassign selectedBuild or navigate anywhere (this is a modal sitting on top of
     *  whatever screen was already open). */
    private void renderExportBuildRow() {
        exportBuildRow.getChildren().clear();
        List<Profile> exportable = builds.stream().filter(p -> !p.isVanillaEntry()).toList();
        exportEmptyStateLabel.setVisible(exportable.isEmpty());
        exportEmptyStateLabel.setManaged(exportable.isEmpty());
        for (Profile p : exportable) {
            exportBuildRow.getChildren().add(exportBuildRowNode(p));
        }
    }

    private Node exportBuildRowNode(Profile profile) {
        HBox row = new HBox(10);
        row.getStyleClass().add("server-row");
        if (profile == exportSelectedBuild) {
            row.getStyleClass().add("selected");
        }
        row.setAlignment(Pos.CENTER_LEFT);

        StackPane icon = new StackPane();
        icon.getStyleClass().add("build-icon-sm");
        String iconText = profile.getIconText() != null ? profile.getIconText()
                : profile.getName().length() >= 2 ? profile.getName().substring(0, 2).toUpperCase()
                : profile.getName().toUpperCase();
        Label iconLabel = new Label(iconText);
        iconLabel.getStyleClass().add("build-icon-label");
        icon.getChildren().add(iconLabel);
        String gradient = profile.getIconGradient() != null ? profile.getIconGradient() : "#4b5563,#1f2937";
        String[] colors = gradient.split(",");
        if (colors.length == 2) {
        // Per-build tile colours from the table above: item identity, like an avatar
        // colour, deliberately not a theme token.
            icon.setStyle("-fx-background-color: linear-gradient(to bottom right, " + colors[0].trim() + ", " + colors[1].trim() + ");");
        }

        HBox titleRow = new HBox(4);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label(profile.getName());
        title.getStyleClass().add("build-title");
        titleRow.getChildren().add(title);
        if (profile.isLiked()) {
            FontIcon heart = new FontIcon("fth-heart");
            heart.getStyleClass().add("build-like-icon-active");
            titleRow.getChildren().add(heart);
        }
        HBox.setHgrow(titleRow, Priority.ALWAYS);

        String loaderDisplay = (profile.getModLoader() == null || profile.getModLoader().isBlank()
                || "vanilla".equalsIgnoreCase(profile.getModLoader())) ? "Vanilla" : profile.getModLoader();
        Label meta = new Label(profile.getVersionId() + " · " + loaderDisplay);
        meta.getStyleClass().addAll("build-meta", "mono");

        row.getChildren().addAll(icon, titleRow, meta);
        row.setOnMouseClicked(e -> {
            exportSelectedBuild = profile;
            renderExportBuildRow();
            renderExportLink();
        });
        return row;
    }

    /** Regenerates exportLinkField's content for whatever exportSelectedBuild currently is - or
     *  clears the field entirely (not a placeholder message, an actually empty field) and
     *  disables Copy when nothing's picked yet, so there's never text sitting in a "copy this"
     *  field that isn't actually a real share code. */
    private void renderExportLink() {
        if (exportSelectedBuild == null) {
            exportLinkField.clear();
            exportCopyBtn.setDisable(true);
            return;
        }
        Map<String, ModrinthService.InstalledMeta> meta = modrinthService.loadInstalledMeta(exportSelectedBuild.getName());
        List<com.example.launcher.service.ModShareCodec.Mod> mods = new ArrayList<>();
        for (ModrinthService.InstalledMeta m : meta.values()) {
            if (m.projectId != null && !m.projectId.isBlank()) {
                mods.add(new com.example.launcher.service.ModShareCodec.Mod(m.projectId, m.title));
            }
        }
        String code = com.example.launcher.service.ModShareCodec.encode(
                exportSelectedBuild.getName(), exportSelectedBuild.getVersionId(), exportSelectedBuild.getModLoader(),
                exportSelectedBuild.getModLoaderVersion(), mods);
        exportLinkField.setText(code);
        exportCopyBtn.setDisable(false);
    }

    @FXML
    private void onShareTabImport() {
        shareTabExport.setSelected(false);
        shareTabImport.setSelected(true);
        exportPanel.setVisible(false);
        exportPanel.setManaged(false);
        importPanel.setVisible(true);
        importPanel.setManaged(true);
        if (!importBusy) importStatusLabel.setText("");
    }

    @FXML
    private void onCopyExportLink() {
        copyToClipboard(exportLinkField.getText());
        showToast("Скопировано", "success");
    }

    @FXML
    private void onConfirmImport() {
        if (importBusy) return;
        String code = importLinkField.getText();
        if (code == null || code.isBlank()) {
            importStatusLabel.setText("Вставь код, который прислали");
            return;
        }
        com.example.launcher.service.ModShareCodec.Payload payload;
        try {
            payload = com.example.launcher.service.ModShareCodec.decode(code.trim());
        } catch (IllegalArgumentException ex) {
            importStatusLabel.setText("Некорректный код: " + ex.getMessage());
            return;
        }
        String name = payload.n != null && !payload.n.isBlank()
                ? payload.n : "Импортированная сборка " + (builds.size() + 1);
        Profile profile = new Profile(name, usernameField.getText(), "", payload.v, payload.l);
        profile.setOrigin("player");
        // Math.min: имя приходит из чужого кода обмена и может быть короче двух символов.
        // Голый substring(0, 2) бросал StringIndexOutOfBoundsException из обработчика FXML
        // за две строки до builds.add — статус не обновлялся, тоста не было, сборка не
        // создавалась, в лог не писалось ничего. Со стороны человека кнопка просто не работала.
        profile.setIconText(name.substring(0, Math.min(2, name.length())).toUpperCase());
        profile.setIconGradient(randomCardGradient());
        profile.setLiked(true);   // видна на главной, см. onConfirmCreateBuild
        builds.add(profile);
        try {
            profileService.saveProfile(profile);
        } catch (IOException e) {
            log("[IMPORT] Не удалось сохранить сборку: " + e.getMessage());
        }
        importStatusLabel.setText("Сборка «" + name + "» импортирована, скачиваем моды...");
        renderBuildsGrid();
        refreshServerBuildCombo();

        // Шкала по всем модам кода (14.10.2026): раньше окно молча висело до конца загрузки,
        // а ход был только в консоли. Размеров заранее нет, поэтому каждый мод — равная доля.
        List<com.example.launcher.service.ModShareCodec.Mod> mods = payload.m == null ? List.of() : payload.m;
        importBusy = true;
        importConfirmBtn.setDisable(true);
        importProgressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);   // до старта задачи
        importProgressLabel.setText("");
        importProgressRow.setVisible(true);
        importProgressRow.setManaged(true);
        FxProgress importProgress = new FxProgress(importProgressBar, importProgressLabel, FxProgress.Style.PERCENT);

        executor.submit(() -> {
            int ok = 0, fail = 0;
            for (int i = 0; i < mods.size(); i++) {
                com.example.launcher.service.ModShareCodec.Mod m = mods.get(i);
                String title = m.t != null ? m.t : m.id;
                int number = i + 1;
                Platform.runLater(() -> importStatusLabel.setText(
                        "Скачиваю моды: " + number + " из " + mods.size() + " — " + title));
                importProgress.startPart(i, mods.size());
                try {
                    String versionId = modrinthService.resolveBestVersion(m.id, payload.v, payload.l);
                    if (versionId == null) throw new IOException("нет версии под " + payload.v + " " + payload.l);
                    modrinthService.downloadContent(versionId, profile.getName(), "mod", m.id, m.t,
                            progress -> log("[IMPORT] " + progress), importProgress.part(i, mods.size()));
                    ok++;
                } catch (Exception ex) {
                    fail++;
                    log("[IMPORT] Не удалось установить " + title + ": " + ex.getMessage());
                }
            }
            importProgress.post(1, 1);
            int okF = ok, failF = fail;
            Platform.runLater(() -> {
                importBusy = false;
                importConfirmBtn.setDisable(false);
                importProgressRow.setVisible(false);
                importProgressRow.setManaged(false);
                importStatusLabel.setText("");
                log("[IMPORT] Готово: " + okF + " установлено, " + failF + " не удалось.");
                showToast("Сборка «" + name + "» импортирована"
                        + (failF > 0 ? ", но " + failF + " мод(ов) скачать не удалось — см. консоль" : ""),
                        failF > 0 ? "warning" : "success");
                hideOverlay(shareOverlay);
            });
        });
    }

    // ============================================================
    //  Create Server modal — local (adds to servers[] + can be started
    //  via GameLaunchService like before) or VPS (данные SSH-доступа →
    //  VpsPanelController подключается, проверяет систему и ставит ключ).
    // ============================================================

    private String newServerType = "local";
    private int vpsStep = 1;

    @FXML
    private void onOpenCreateServer() {
        newServerType = "local";
        serverTypeTabLocal.setSelected(true);
        serverTypeTabVps.setSelected(false);
        createLocalPanel.setVisible(true);
        createLocalPanel.setManaged(true);
        createVpsStep1Panel.setVisible(false);
        createVpsStep1Panel.setManaged(false);
        createVpsStep2Panel.setVisible(false);
        createVpsStep2Panel.setManaged(false);

        newServerNameField.clear();
        newServerPortField.setText(String.valueOf(nextLocalPort()));
        newServerVersionCombo.getSelectionModel().selectFirst();
        newServerLoaderCombo.getSelectionModel().selectFirst();
        newServerRamSlider.setValue(Math.min(4, newServerRamSlider.getMax()));
        newServerPropsEditor.reset();

        vpsPanelController.resetForm();
        vpsStep = 1;
        // Плашка аренды — пока нет ни одного VPS; подсказку про serv.host включает только onRentBought.
        boolean hasVps = servers.stream().anyMatch(ServerEntry::isSsh);
        show(rentPromoBox, !hasVps);
        show(rentConnectHint, false);

        serverSetupProgressBox.setVisible(false);
        serverSetupProgressBox.setManaged(false);
        createServerConfirmBtn.setDisable(false);
        createServerConfirmBtn.setVisible(true);
        createServerConfirmBtn.setManaged(true);
        createServerConfirmBtn.setText("Создать");

        showOverlay(createServerOverlay);
    }

    @FXML
    private void onCloseCreateServer() {
        if (vpsPanelController.isBusy()) {
            showToast("Дождитесь окончания подключения к серверу", "info");
            return;
        }
        hideOverlay(createServerOverlay);
    }

    private int nextLocalPort() {
        int max = 25564;
        for (ServerEntry s : servers) {
            if (s.isLocal()) max = Math.max(max, s.getPort());
        }
        return max + 1;
    }

    @FXML
    private void onServerTypeTabLocal() {
        newServerType = "local";
        serverTypeTabLocal.setSelected(true);
        serverTypeTabVps.setSelected(false);
        createLocalPanel.setVisible(true);
        createLocalPanel.setManaged(true);
        createVpsStep1Panel.setVisible(false);
        createVpsStep1Panel.setManaged(false);
        createVpsStep2Panel.setVisible(false);
        createVpsStep2Panel.setManaged(false);
        createServerConfirmBtn.setVisible(true);
        createServerConfirmBtn.setManaged(true);
        createServerConfirmBtn.setText("Создать");
    }

    @FXML
    private void onServerTypeTabVps() {
        newServerType = "vps";
        serverTypeTabLocal.setSelected(false);
        serverTypeTabVps.setSelected(true);
        createLocalPanel.setVisible(false);
        createLocalPanel.setManaged(false);
        showVpsStep(1);
    }

    private void showVpsStep(int step) {
        vpsStep = step;
        createVpsStep1Panel.setVisible(step == 1);
        createVpsStep1Panel.setManaged(step == 1);
        createVpsStep2Panel.setVisible(step == 2);
        createVpsStep2Panel.setManaged(step == 2);
        createServerConfirmBtn.setVisible(step == 1);
        createServerConfirmBtn.setManaged(step == 1);
        createServerConfirmBtn.setText("Подключиться");
    }

    // Делегаты разметки панели VPS — логика в VpsPanelController.
    @FXML private void onVpsAuthPassword() { vpsPanelController.onAuthPassword(); }
    @FXML private void onVpsAuthKey() { vpsPanelController.onAuthKey(); }
    @FXML private void onVpsBrowseKey() { vpsPanelController.onBrowseKey(); }
    @FXML private void onVpsBackToForm() { vpsPanelController.onBackToForm(); }
    @FXML private void onVpsRetryConnect() { vpsPanelController.onRetry(); }
    @FXML private void onVpsCheckConnection() { vpsPanelController.onCheckConnection(findServer(focusedServerId)); }

    /** Maps the UI's display labels for VPS loaders (as shown in reinstallLoaderCombo) to the
     *  lowercase keys installer.js's SUPPORTED_LOADERS actually expects. */
    private String mapVpsLoaderToAgentKey(String displayLabel) {
        if (displayLabel == null) return "vanilla";
        return switch (displayLabel) {
            case "Paper" -> "paper";
            case "Purpur" -> "purpur";
            case "Pufferfish" -> "pufferfish";
            case "Velocity" -> "velocity";
            case "BungeeCord" -> "bungeecord";
            case "Waterfall" -> "waterfall";
            case "Fabric" -> "fabric";
            case "Pumpkin" -> "pumpkin";
            default -> "vanilla";
        };
    }

    /** Inverse of {@link #mapVpsLoaderToAgentKey} — turns GET /server/status's lowercase
     *  loader key back into the display label ServerEntry.loader/the UI expect. */
    private String mapAgentKeyToVpsLoader(String agentKey) {
        if (agentKey == null) return null;
        return switch (agentKey) {
            case "paper" -> "Paper";
            case "purpur" -> "Purpur";
            case "pufferfish" -> "Pufferfish";
            case "velocity" -> "Velocity";
            case "bungeecord" -> "BungeeCord";
            case "waterfall" -> "Waterfall";
            case "fabric" -> "Fabric";
            case "pumpkin" -> "Pumpkin";
            case "vanilla" -> "Vanilla";
            default -> agentKey;
        };
    }

    @FXML
    private void onConfirmCreateServer() {
        if ("local".equals(newServerType)) {
            String name = newServerNameField.getText() != null && !newServerNameField.getText().isBlank()
                    ? newServerNameField.getText().trim() : "Новый сервер";
            // Сначала проверка плиток: с неверным значением сервер не создаётся вовсе.
            Map<String, String> props;
            try {
                props = ServerProps.cleanAll(newServerPropsEditor.values());
            } catch (IllegalArgumentException e) {
                showToast(e.getMessage(), "error");
                return;
            }
            ServerEntry s = new ServerEntry(name, newServerVersionCombo.getSelectionModel().getSelectedItem(),
                    newServerLoaderCombo.getSelectionModel().getSelectedItem(), true);
            try {
                s.setPort(Integer.parseInt(newServerPortField.getText().trim()));
            } catch (NumberFormatException e) {
                s.setPort(nextLocalPort());
            }
            s.setRamMb((int) Math.round(newServerRamSlider.getValue() * 1024));
            s.setAgentConnected(true);
            // server.properties — сразу в папку сервера. При первом запуске GameLaunchService
            // увидит готовый файл и свой минимальный не запишет (writeServerDefaultsIfMissing).
            Map<String, String> fileValues = new java.util.LinkedHashMap<>();
            fileValues.put("server-port", String.valueOf(s.getPort()));
            fileValues.putAll(props);
            try {
                ServerProps.writeInto(gameLaunchService.serverDirForName(name).resolve("server.properties"), fileValues);
            } catch (IOException e) {
                log("[SERVER] Не удалось записать server.properties: " + e.getMessage());
                showToast("Сервер создан, но настройки не записались: " + e.getMessage(), "warning");
            }
            servers.add(s);
            saveServers();
            renderServerAdvancedList();
            renderMyServersQuickList();
            hideOverlay(createServerOverlay);
            showToast("Сервер «" + s.getName() + "» создан", "success");
            return;
        }

        // Вкладка VPS, шаг 1 → подключение по SSH (шаг 2 показывает его ход по пунктам).
        vpsPanelController.onConnect();
    }

    // ============================================================
    //  Settings screen — RAM, launch animation, language,
    //  theme (violet-only for now), account (Microsoft login), console.
    // ============================================================

    private void initSettingsScreen() {
        appVersionLabel.setText("BLauncher " + LAUNCHER_VERSION);

        double maxHeapGb = maxAllowedHeapGb();
        ramSlider.setMax(maxHeapGb);

        long totalSystemRamMb = getTotalSystemRamMb();
        long recommendedGb = recommendedRamGb(totalSystemRamMb);
        recommendedRamLabel.setText(totalSystemRamMb > 0
                ? "Рекомендуется: " + recommendedGb + " ГБ (из " + (totalSystemRamMb / 1024) + " ГБ)"
                : "Рекомендуется: " + recommendedGb + " ГБ");

        int initialMax = config.getMaxMemoryMb() > 0 ? config.getMaxMemoryMb() : (int) (recommendedGb * 1024);
        // Clamp a value that was ALREADY saved above the ceiling, and persist the clamp. The
        // ceiling below is new; configs written before it exist in the wild holding things like
        // 7168 MB on a 7 GB machine, which is what this whole guard is here to stop. Slider max
        // alone wouldn't fix them: the listener that saves the config only fires on a real change
        // made by a person, and it isn't even registered yet at this point in init.
        double clampedGb = Math.max(0.5, Math.min(maxHeapGb, initialMax / 1024.0));
        ramSlider.setValue(clampedGb);
        int clampedMb = (int) Math.round(clampedGb * 1024);
        if (config.getMaxMemoryMb() <= 0) {
            log("[SETTINGS] Установлена рекомендуемая память: " + formatRamGbValue(clampedGb));
            config.setMaxMemoryMb(clampedMb);
            try { configService.saveConfig(config); } catch (Exception ignored) { }
        } else if (config.getMaxMemoryMb() != clampedMb) {
            log("[SETTINGS] Выделено памяти было " + config.getMaxMemoryMb() + " МБ — это больше безопасного "
                    + "максимума для этого ПК, снижено до " + clampedMb + " МБ");
            config.setMaxMemoryMb(clampedMb);
            try { configService.saveConfig(config); } catch (Exception ignored) { }
        }
        updateRamLabel();
        ramSaveDebounce.setOnFinished(e -> saveConfigQuietly());
        usernameSaveDebounce.setOnFinished(e -> saveConfigQuietly());
        initLaunchFields();

        // Шаг 0.5 ГБ: в FXML snapToTicks не задан, поэтому перетаскивание давало непрерывные
        // дробные значения вроде 6,327 — а подпись показывает целые гигабайты. Отсюда и
        // расхождение: на экране «6 ГБ», а в конфиг уходило 6478 МБ.
        ramSlider.setSnapToTicks(true);
        ramSlider.setMajorTickUnit(0.5);
        ramSlider.setMinorTickCount(0);

        ramSlider.valueProperty().addListener((obs, oldV, newV) -> {
            updateRamLabel();
            // Math.round, а не (int): отбрасывание давало не то число, что в подписи.
            config.setMaxMemoryMb((int) Math.round(newV.doubleValue() * 1024));
            config.setMinMemoryMb(512);
            // НЕ сохраняем на каждое изменение. Слушатель срабатывает десятки раз за одно
            // перетаскивание, и каждый раз это была синхронная запись JSON в UI-потоке — плюс
            // столько же шансов поймать обрыв записи на незащищённом файле (см. ConfigService).
            // Сохраняем один раз, когда человек отпустил ползунок.
            ramSaveDebounce.playFromStart();
        });
        // Отпустили ползунок — сохраняем сразу, не дожидаясь задержки.
        ramSlider.valueChangingProperty().addListener((obs, wasChanging, changing) -> {
            if (wasChanging && !changing) {
                ramSaveDebounce.stop();
                saveConfigQuietly();
            }
        });

        closeOnLaunchToggle.setSelected(config.isCloseLauncherOnLaunch());
        closeOnLaunchToggle.selectedProperty().addListener((obs, oldV, newV) -> {
            config.setCloseLauncherOnLaunch(newV);
            try { configService.saveConfig(config); } catch (Exception ignored) { }
        });

        showSnapshotsToggle.setSelected(config.isShowSnapshots());
        offlineModeToggle.setSelected(config.isOfflineMode());
        telemetryToggle.setSelected(config.isTelemetryEnabled());
        blBadgeToggle.setSelected(config.isBlBadge());
        invertMouseToggle.setSelected(config.isInvertMouse());
        animationsToggle.setSelected(config.isAnimations());
        minimizeOnLaunchToggle.setSelected(config.isMinimizeOnLaunch());
        Motion.setEnabled(config.isAnimations());

        langRuButton.setSelected("ru".equals(currentLanguage));
        langEnButton.setSelected("en".equals(currentLanguage));

        // Only fall back to the saved/generated username if a Microsoft session wasn't already
        // restored above (see restoreSavedAccount/applySignedInUiState, called earlier in
        // init()) — otherwise this would silently overwrite the signed-in account's name.
        if (authService == null || !authService.isLoggedIn()) {
            String savedUsername = config.getLastUsername();
            if (savedUsername == null || savedUsername.isBlank()) {
                // First launch (or a config predating this field) — a random BL##### handle
                // beats leaking the player's Windows account name into an offline-mode nickname.
                savedUsername = generateDefaultUsername();
                config.setLastUsername(savedUsername);
                try { configService.saveConfig(config); } catch (Exception ignored) { }
            }
            usernameField.setText(savedUsername);
        }
        // Ник ограничиваем прямо при вводе: Minecraft принимает только латиницу, цифры и
        // подчёркивание, максимум 16 символов. Поле редактора профиля это уже проверяло, а
        // usernameField (и связанное с ним homeUsernameField) — нет вообще, и launchGame
        // передавал текст в игру как есть: 30 символов или кириллица уходили как невалидное
        // имя. Неиспользуемый ключ словаря profile_name_invalid_chars показывает, что проверка
        // здесь и задумывалась.
        usernameField.textProperty().addListener((obs, oldV, newV) -> {
            String safe = sanitizeMinecraftName(newV);
            if (!safe.equals(newV)) {
                // Правим поле и выходим: повторный вызов слушателя сохранит уже исправленное.
                int caret = Math.min(usernameField.getCaretPosition(), safe.length());
                usernameField.setText(safe);
                usernameField.positionCaret(caret);
                return;
            }
            config.setLastUsername(newV);
            // Та же причина, что у слайдера памяти: сохранять на каждую нажатую клавишу —
            // это десятки записей JSON в UI-потоке за одно слово.
            usernameSaveDebounce.playFromStart();
        });

        // Home's username field (next to the Microsoft login button) is the same value as
        // Settings' — bound both ways so editing either one keeps them in sync, rather than
        // having two independent fields that could silently drift apart.
        homeUsernameField.textProperty().bindBidirectional(usernameField.textProperty());
    }

    private void updateRamLabel() {
        ramValueLabel.setText(formatRamGbValue(ramSlider.getValue()));
    }

    /** Formats a slider value in GB (fractional below 1, e.g. 0.5) the way a person reading it
     *  would expect: "512 МБ" under a gigabyte, otherwise whole gigabytes ("6 ГБ") — a bare
     *  {@code (int)} cast on 0.5 truncates to "0 ГБ", which reads as "no memory" rather than
     *  the 512 MB it actually means. */
    private static String formatRamGbValue(double gb) {
        return gb < 1 ? Math.round(gb * 1024) + " МБ" : Math.round(gb) + " ГБ";
    }

    /** "BL" + 5 random digits (e.g. "BL04213") — the offline-mode default nickname on a fresh
     *  install, before the person picks their own or signs in with Microsoft. */
    private static String generateDefaultUsername() {
        return "BL" + String.format("%05d", new java.util.Random().nextInt(100_000));
    }

    // Половина ОЗУ, но не больше 6 ГБ (18.10.2026): на 32 ГБ советовали 16, а JVM занимает кучу
    // почти до -Xmx — сборка на NeoForge держала ~10 ГБ, хотя ей хватает 4–6.
    private static long recommendedRamGb(long totalSystemRamMb) {
        return totalSystemRamMb > 0 ? Math.max(2, Math.min(6, totalSystemRamMb / 1024 / 2)) : 6;
    }

    @FXML
    private void onUseRecommendedRam() {
        long recommendedGb = recommendedRamGb(getTotalSystemRamMb());
        ramSlider.setValue(recommendedGb);
        log("[SETTINGS] Применена рекомендуемая память: " + recommendedGb + " ГБ");
    }

    /**
     * The largest heap the RAM sliders will let anyone hand to the game (or a local server), in GB.
     *
     * <p>The sliders used to top out at this machine's <b>entire</b> physical RAM, which reads like
     * a reasonable ceiling and is not: a player with 7 GB set the slider to 7 GB, the game launched
     * with {@code -Xmx7168M}, and it died partway through every session. {@code -Xmx} is only the
     * Java heap — the same process also needs metaspace, thread stacks, GC structures and, for
     * Minecraft especially, a large native allocation for LWJGL/OpenGL and the graphics driver's own
     * buffers, none of which come out of the heap. Add Windows itself on top and a heap sized at
     * 100% of RAM cannot be backed once the game actually grows into it; the game writes a crash
     * report and exits with -1, which looks exactly like "it just crashes after a while".
     *
     * <p>So: reserve 2 GB for the OS, and never hand over more than 80% of the machine either
     * (on a big machine "total minus 2" would still be far too greedy). Rounded down to the
     * slider's own 0.5 GB step. The floor of 2 GB is kept from the previous code — under that the
     * game won't start at all, so on a very small machine there is nothing useful to clamp to.
     */
    private double maxAllowedHeapGb() {
        long totalMb = getTotalSystemRamMb();
        if (totalMb <= 0) return 8; // unknown machine — a safe fixed ceiling, as elsewhere here
        double totalGb = totalMb / 1024.0;
        double capped = Math.min(totalGb - 2, totalGb * 0.8);
        return Math.max(2, Math.floor(capped * 2) / 2);
    }

    private long getTotalSystemRamMb() {
        try {
            var osBean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                return sunBean.getTotalMemorySize() / (1024 * 1024);
            }
        } catch (Exception ignored) { }
        return -1;
    }

    @FXML
    private void onLanguageRu() {
        switchLanguage("ru", langRuButton, langEnButton,
                "Полный перевод интерфейса применится после перезапуска BLauncher");
    }

    @FXML
    private void onLanguageEn() {
        switchLanguage("en", langEnButton, langRuButton,
                "Full interface translation will apply after restarting BLauncher");
    }

    /**
     * Applies a language change.
     *
     * Most of the interface is translated through FXML's own {@code %key} mechanism, which
     * JavaFX resolves once when the window is built, so a live switch can only ever update
     * the handful of labels tr() owns. Rather than leave the player with a half-translated
     * window and an instruction to restart by hand, the launcher restarts itself: the config
     * is saved first, then a successor is started with a short hand-over pause (AppRestart
     * explains why the pause is needed) and this process exits.
     *
     * If no successor can be started - an unusual run, or a platform where the launcher is
     * not packaged - the old behaviour stays: apply what can be applied and say so, so a
     * failed restart never leaves the player with nothing.
     */
    private void switchLanguage(String language, ToggleButton selected, ToggleButton other, String restartNotice) {
        boolean changed = !language.equals(currentLanguage);
        currentLanguage = language;
        selected.setSelected(true);
        other.setSelected(false);
        config.setLanguage(language);
        try {
            configService.saveConfig(config);
        } catch (Exception e) {
            log("[I18N] Не удалось сохранить язык: " + e.getMessage());
        }
        refreshLanguageTexts();
        if (!changed) return;

        if (AppRestart.spawnSuccessor()) {
            shutdownBackgroundProcesses();
            Platform.exit();
            // System.exit обязателен, как в пути обновления и при закрытии окна.
            // Platform.exit() только закрывает JavaFX; JVM живёт, пока жив хоть один поток не
            // daemon, а прерывание из shutdownNow() не разрывает блокирующее чтение сокета.
            // Поэтому при любой сетевой задаче в полёте старый процесс оставался жив и держал
            // blauncher.lock — преемник, запускаемый через 2 секунды, видел лок занятым, писал
            // «BLauncher уже запущен» и выходил. Со стороны человека: сменил язык и остался
            // вообще без лаунчера.
            System.exit(0);
            return;
        }
        showToast(restartNotice, "info");
    }

    /**
     * Re-applies tr()-backed strings to already-built UI after a language switch — screen
     * titles plus whatever's on the currently focused server/build screen, updated immediately
     * with no restart needed.
     *
     * Every other static label in main.fxml (~89 strings — see i18n/strings_ru.properties and
     * strings_en.properties, and MainApp's ResourceBundle wiring) is translated via FXML's own
     * %key mechanism, which JavaFX resolves once when the FXML is loaded. Those pick up the
     * CURRENT language correctly on every app start, but do NOT re-render live when the
     * language toggle is clicked mid-session — only on the next launch. Wiring true no-restart
     * updates for all ~89 of them would mean giving every one of those labels its own fx:id and
     * controller field purely for this, which is a large mechanical change for a cosmetic gap
     * (the language you're switching TO is still fully correct after restarting once).
     */
    private void refreshLanguageTexts() {
        homeTitleLabel.setText(tr("play_btn"));
        buildsTitleLabel.setText(tr("builds_title"));
        modsTitleLabel.setText(tr("mods_title"));
        friendsTitleLabel.setText(tr("nav_friends"));
        rentTitleLabel.setText(tr("rent_title"));
        serverTitleLabel.setText(tr("server_title"));
        settingsTitleLabel.setText(tr("settings_title"));
        playButton.setText(tr("play_btn"));
        stopButton.setText(tr("stop_btn"));
        toolsTitleLabel.setText(tr("nav_tools"));
        renderToolsGrid();
        updateHomeLicenseBadge(authService != null && authService.hasLicense());
        addProfileButton.setText(tr("add_profile_btn"));
        profileEditorTitle.setText(tr(currentEditingProfile == null ? "profile_new_title" : "profile_edit_title"));
        profileSkinUploadBtn.setText(tr("profile_skin_upload_btn"));
        profileCapeUploadBtn.setText(tr("profile_cape_upload_btn"));
        profileDeleteBtn.setText(tr("profile_delete_btn"));
        // The line under each site name in the source chips is translated too.
        if (profileSourcesBuilt) ensureProfileSources();
        onServerBuildSelected();
        renderServerAdvancedList();
        renderMyServersQuickList();
        if (focusedServerId != null) {
            renderServerDetailHeader();
        }
    }

    // Theme swatches - all three swap the generated stylesheet (see applyTheme()).
    // The rules live once, in css/theme-template.css, and the colours come from the
    // palette, so the three themes can no longer drift apart.
    @FXML
    private void onThemeViolet() {
        applyTheme("violet");
    }

    @FXML
    private void onThemeBlack() {
        applyTheme("black");
    }

    @FXML
    private void onThemeWhite() {
        applyTheme("white");
    }

    private void applyTheme(String theme) {
        if (motionInstalled) Motion.crossFade(primaryStage.getScene());
        currentTheme = theme;
        config.setTheme(theme);
        try { configService.saveConfig(config); } catch (Exception ignored) { }

        setActiveThemeSwatch(switch (theme) {
            case "black" -> themeBlackSwatch;
            case "white" -> themeWhiteSwatch;
            // a custom palette is none of the presets, so none stays highlighted
            case Palette.CUSTOM -> null;
            default -> themeVioletSwatch;
        });

        // The stylesheet is generated from the palette (see PaletteService): the
        // launcher no longer keeps one file per theme, and a player-edited palette
        // goes through exactly the same path as a built-in one.
        Palette palette = Palette.CUSTOM.equals(theme) && config.getCustomPalette() != null
                ? config.getCustomPalette()
                : PaletteService.preset(theme);
        applyPalette(palette);
    }

    private void setActiveThemeSwatch(VBox active) {
        for (VBox swatch : List.of(themeVioletSwatch, themeBlackSwatch, themeWhiteSwatch)) {
            swatch.getStyleClass().remove("active");
        }
        // null = a custom palette is in use, so none of the presets is selected
        if (active != null) {
            active.getStyleClass().add("active");
        }
    }

    // --- Своя палитра: the editor writes a palette, PaletteService renders it ---

    /**
     * Puts a palette on screen together with the player's text size.
     *
     * <p>Every path that shows a palette goes through here - the three theme swatches, the
     * palette editor's live preview, Reset, Save, Cancel and the text-size chooser - so there
     * is one place that knows the size, and none of them can apply a palette at the default
     * size by accident (which is how a colour edit would quietly reset the text size).
     */
    private boolean motionInstalled;
    private Motion.Shimmer playShimmer;
    private boolean quiet;

    /**
     * While the launcher is minimised or hidden (the game is running) it stops everything that
     * runs by itself: the End sky, the Play shimmer, and the ZeroTier poll that starts an external
     * program every five seconds. The friends heartbeat stays - it is what shows the player as
     * online, and it costs one small request every 30 s. All of it resumes when the window is
     * opened again.
     */
    private void setQuiet(boolean on) {
        if (on == quiet) return;
        quiet = on;
        if (on) {
            if (endSky != null) endSky.stop();
            if (playShimmer != null) playShimmer.pause();
            if (ztPoller != null) ztPoller.pause();
            trimMemory();
        } else {
            if (endSky != null && endSky.isVisible()) endSky.start();
            if (playShimmer != null) playShimmer.play();
            if (ztPoller != null) ztPoller.play();
        }
    }

    /** Минута без фокуса — и лаунчер сбрасывает кучу (см. trimMemory). */
    /** Через Motion.later, не PauseTransition: минутная пауза-анимация сама держала 60 кадров/с. */
    private java.util.concurrent.ScheduledFuture<?> unfocusedTrim;

    /**
     * Полная сборка в фоне: G1 на Java 21 возвращает память системе только после неё, мелкие
     * сборки кучу не сжимают. 16.10.2026: рядом лагал Blender, а лаунчер, открытый сзади, держал
     * 1,5 ГБ; замер — одна сборка срезает ~600 МБ до ~380 МБ. Зовётся, только когда на окно
     * не смотрят, поэтому паузу в десятки миллисекунд никто не заметит.
     */
    private void trimMemory() {
        Thread trim = new Thread(System::gc, "memory-trim");
        trim.setDaemon(true);
        trim.start();
    }

    /** Window-wide press/hover feedback and the Play ripple; installed once. */
    private void installMotion() {
        if (motionInstalled || primaryStage.getScene() == null) return;
        motionInstalled = true;
        Motion.installFeedback(primaryStage.getScene());
        Motion.installSmoothScroll(primaryStage.getScene());
        Motion.installToggleBump(primaryStage.getScene());
        playShimmer = Motion.playShimmer(playButton, playRippleLayer);
        primaryStage.iconifiedProperty().addListener((o, was, now) -> setQuiet(now || !primaryStage.isShowing()));
        primaryStage.showingProperty().addListener((o, was, now) -> setQuiet(!now || primaryStage.isIconified()));
        primaryStage.focusedProperty().addListener((o, was, now) -> {
            if (unfocusedTrim != null) unfocusedTrim.cancel(false);
            unfocusedTrim = now ? null : Motion.later(60, this::trimMemory);
        });
        playButton.addEventFilter(MouseEvent.MOUSE_PRESSED, e ->
                Motion.ripple(playButton, playRippleLayer, e.getX(), e.getY()));
        Platform.runLater(() -> Motion.moveMarker(railMarker, navHomeButton));
    }

    private void applyPalette(Palette palette) {
        installMotion();
        PaletteService.apply(primaryStage.getScene(), palette, config.getFontScale());
        paletteOnScreen = palette;
        applyBackground();
    }

    /** The palette currently on screen: a built-in preset or the player's own. */
    private Palette currentPalette() {
        if (Palette.CUSTOM.equals(currentTheme) && config.getCustomPalette() != null) {
            return config.getCustomPalette().copy();
        }
        return PaletteService.preset(currentTheme);
    }

    @FXML
    private void onOpenPaletteEditor() {
        // Every edit is applied as it is made, so the effect of a colour is visible
        // immediately; Cancel re-applies whatever was saved.
        paletteEditor = new PaletteEditor(currentPalette(), this::applyPalette);
        paletteEditorRows.getChildren().setAll(paletteEditor.node());
        showOverlay(paletteOverlay);
    }

    @FXML
    private void onResetPalette() {
        if (paletteEditor == null) return;
        // Starting point for a reset is the preset the player came from - or the
        // default one when they were already on a custom palette.
        Palette preset = PaletteService.preset(Palette.CUSTOM.equals(currentTheme) ? "violet" : currentTheme);
        paletteEditor.reload(preset);
        applyPalette(preset);
    }

    @FXML
    private void onSavePalette() {
        if (paletteEditor == null) return;
        Palette edited = paletteEditor.palette();
        edited.setName(Palette.CUSTOM);
        config.setCustomPalette(edited);
        currentTheme = Palette.CUSTOM;
        config.setTheme(Palette.CUSTOM);
        try {
            configService.saveConfig(config);
        } catch (Exception e) {
            log("[THEME] Не удалось сохранить палитру: " + e.getMessage());
        }
        setActiveThemeSwatch(null);
        applyPalette(edited);
        hideOverlay(paletteOverlay);
        showToast(tr("palette_saved"), "success");
    }

    @FXML
    private void onClosePaletteEditor() {
        // Leaving without saving must not leave the live preview in place.
        applyPalette(currentPalette());
        hideOverlay(paletteOverlay);
    }

    // --- Размер текста: one multiplier, applied when the stylesheet is generated ---

    /**
     * The text-size chooser that briefly lived in Settings is gone at the owner's request: the
     * standard is 90 %, it is set in LauncherConfig, and it is applied by PaletteService
     * wherever the stylesheet is generated. MainController.applyPalette is the only path that
     * shows a palette, so there is nothing left that could apply a size of its own.
     */

    // --- Microsoft account login (unchanged flow from the previous controller) ---

    @FXML
    private void onMicrosoftLogin() {
        // The login-code box (where the person types the code shown at microsoft.com/link)
        // only exists on the Settings screen — jump there so whichever button was clicked
        // (Home's or Settings' own), the code is actually visible once beginLogin() returns.
        goToView("settings");

        microsoftLoginButton.setDisable(true);
        homeMicrosoftLoginButton.setDisable(true);
        loginStatusLabel.setText("Запрашиваем код входа...");

        executor.submit(() -> {
            try {
                Map<String, String> info = authService.beginLogin();
                String userCode = info.get("userCode");
                String verificationUri = info.get("verificationUri");

                Platform.runLater(() -> {
                    loginStatusLabel.setText("Перейдите на " + verificationUri + " и введите код:");
                    loginCodeLabel.setText(userCode);
                    loginCodeBox.setVisible(true);
                    loginCodeBox.setManaged(true);
                    log("[AUTH] Откройте " + verificationUri + " и введите код: " + userCode);
                    try {
                        Os.browse(verificationUri);
                    } catch (Exception ignored) {
                        // Headless/unsupported — the player copies the URL manually from the log.
                    }
                });

                boolean success = authService.pollForToken();

                Platform.runLater(() -> {
                    loginCodeBox.setVisible(false);
                    loginCodeBox.setManaged(false);
                    stat("login", success ? "msa ok lic=" + (authService.hasLicense() ? 1 : 0) : "msa fail");
                    if (success) {
                        applySignedInUiState();
                    } else {
                        loginStatusLabel.setText("Вход не удался или истекло время ожидания.");
                        log("[AUTH] " + authService.getLastStatus());
                    }
                });
            } catch (IOException e) {
                stat("login", "msa error " + e.getClass().getSimpleName());
                Platform.runLater(() -> {
                    loginCodeBox.setVisible(false);
                    loginCodeBox.setManaged(false);
                    loginStatusLabel.setText("Ошибка входа: " + e.getMessage());
                    log("[AUTH] Ошибка: " + e.getMessage());
                });
            } catch (RuntimeException e) {
                // Этой ветки не было, и ловился только IOException — а до сюда прекрасно доходят
                // другие: NPE, если токен-эндпоинт вернул объект без error и без access_token, и
                // IllegalStateException/JsonSyntaxException, если вместо JSON пришла HTML-страница
                // (captive portal в отеле, заглушка прокси). Исключение умирало в задаче пула
                // ПОСЛЕ setDisable(true) и ДО включения кнопок обратно — обе кнопки входа
                // оставались мёртвыми до конца сессии, а окно с кодом висело на экране.
                Platform.runLater(() -> {
                    loginCodeBox.setVisible(false);
                    loginCodeBox.setManaged(false);
                    loginStatusLabel.setText("Непредвиденная ошибка входа: "
                            + e.getClass().getSimpleName() + ". Попробуйте ещё раз.");
                    log("[AUTH] Непредвиденная ошибка: " + e);
                });
            } finally {
                // Единственное надёжное место: что бы ни случилось, кнопки должны ожить.
                Platform.runLater(() -> {
                    microsoftLoginButton.setDisable(false);
                    homeMicrosoftLoginButton.setDisable(false);
                });
            }
        });
    }

    @FXML
    private void onCopyLoginCode() {
        String code = loginCodeLabel.getText();
        if (code == null || code.isBlank()) return;
        copyToClipboard(code);
        log("[AUTH] Код скопирован в буфер обмена: " + code);
    }

    // --- Game console (Settings screen) ---

    @FXML
    private void onToggleGameConsole() {
        boolean show = !gameConsoleBody.isVisible();
        gameConsoleBody.setVisible(show);
        gameConsoleBody.setManaged(show);
        gameConsoleToggleBtn.setText(tr(show ? "game_console_hide" : "game_console_show"));
    }

    @FXML
    private void onClearConsole() {
        gameConsoleOutput.clear();
        log("[CONSOLE] Очищено");
    }

    @FXML
    private void onCopyConsole() {
        String text = gameConsoleOutput.getText();
        if (text == null || text.isBlank()) {
            log("[CONSOLE] Нечего копировать.");
            return;
        }
        copyToClipboard(text);
        log("[CONSOLE] Лог скопирован в буфер обмена.");
    }

    @FXML
    private void onSaveConsole() {
        String text = gameConsoleOutput.getText();
        if (text == null || text.isBlank()) {
            log("[CONSOLE] Нечего сохранять.");
            return;
        }
        try {
            Path logsDir = gameDir.resolve("logs");
            Files.createDirectories(logsDir);
            String stamp = java.time.LocalDateTime.now()
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            Path file = logsDir.resolve("blauncher-" + stamp + ".log");
            Files.writeString(file, text);
            log("[CONSOLE] Лог сохранён в " + file.toAbsolutePath());
        } catch (Exception ex) {
            log("[CONSOLE] Не удалось сохранить лог: " + ex.getMessage());
        }
    }

    // ============================================================
    //  Auto-update — checks blauncher.ru's manifest (a domain, not a hardcoded IP — see
    //  UpdateService's own javadoc on why), shows a changelog modal if a newer version is
    //  found, and on confirmation downloads + verifies + self-replaces via UpdateService.
    // ============================================================

    /**
     * Метки самообновления: «с какой на какую версию обновляемся» (пишется перед перезапуском) и
     * «скрипт не смог заменить файл» (пишет apply-update.bat). Относительно рабочей папки, как
     * config/launcher.json — скрипт перезапускает лаунчер с той же папкой (/D).
     *
     * 18.10.2026 игрок 281b45df семь раз нажал «Обновить», jar каждый раз скачивался целиком, а
     * лаунчер возвращался 1.14.15 — и ни лаунчер, ни статистика об этом не знали.
     */
    private static final Path UPDATE_PENDING = Path.of("config", "update-pending.txt");
    private static final Path UPDATE_FAILED = Path.of("config", "update-failed.txt");
    /** Куда молча скачивается обновление (stageUpdateSilently); переживает перезапуск без закрытия. */
    private static final Path UPDATE_STAGED_DIR = Path.of("config", "update-staged");

    /** Скачанное и проверенное обновление — ставится при закрытии (applyStagedUpdateOnExit). */
    private volatile Path stagedUpdate;
    private volatile String stagedUpdateVersion;
    /** Прошлое тихое обновление не встало — в этот раз спрашиваем окном, как раньше. */
    private volatile boolean silentUpdateFailed;

    /** Чем кончилось обновление, начатое прошлым запуском: update_ok / update_fail + подсказка. */
    private void reportPreviousUpdate() {
        String pending;
        try {
            if (!Files.exists(UPDATE_PENDING)) return;
            pending = Files.readString(UPDATE_PENDING).trim();
        } catch (Exception e) {
            return;
        }
        boolean scriptFailed = Files.exists(UPDATE_FAILED);
        // Что успел скрипт замены (буквы шагов, см. UpdateService.buildSelfReplaceScript) и остался ли
        // скачанный jar: скрипт удаляет его последней строкой, лежит — значит, скрипт не дошёл до конца
        // или не запускался вовсе. 28.10.2026: no_restart без метки «locked» ни о чём не говорил.
        Path traceFile = UPDATE_FAILED.resolveSibling(UpdateService.UPDATE_TRACE_FILE);
        String trace = "";
        try {
            if (Files.exists(traceFile)) trace = Files.readString(traceFile).replaceAll("\\s+", "");
        } catch (Exception ignored) { }
        boolean stagedLeft = false;
        try (var left = Files.list(UPDATE_STAGED_DIR)) {
            stagedLeft = left.anyMatch(f -> f.getFileName().toString().endsWith(".jar"));
        } catch (Exception ignored) { }
        try { Files.deleteIfExists(UPDATE_PENDING); } catch (Exception ignored) { }
        try { Files.deleteIfExists(UPDATE_FAILED); } catch (Exception ignored) { }
        try { Files.deleteIfExists(traceFile); } catch (Exception ignored) { }
        String[] p = pending.split(" ");
        if (p.length < 3) return;
        long at;
        try { at = Long.parseLong(p[2]); } catch (NumberFormatException e) { return; }
        // Метка недельной давности ни о чём уже не говорит (лаунчер могли переставить руками).
        if (System.currentTimeMillis() - at > 7L * 24 * 3600 * 1000) return;
        String from = p[0], to = p[1];
        boolean silent = p.length > 3 && p[3].equals("silent");
        // «handover» — открыли во время установки, и лаунчер уступил скрипту (AppLauncher).
        String tag = (silent ? " silent=1" : "") + (pending.contains("handover") ? " handover=1" : "");
        if (!updateService.isNewer(to, LAUNCHER_VERSION)) {
            stat("update_ok", from + "-" + to + tag);
            return;
        }
        stat("update_fail", from + "-" + to + " reason=" + (scriptFailed ? "locked" : "no_restart") + tag
                + " trace=" + (trace.isEmpty() ? "none" : trace) + (stagedLeft ? " staged=1" : ""));
        if (silent) silentUpdateFailed = true;
        Platform.runLater(() -> showToast("Обновление до " + to + " не установилось: Windows не дала заменить файл "
                + "лаунчера — чаще всего его держит антивирус. Перезагрузите компьютер и нажмите «Обновить» ещё раз "
                + "или скачайте установщик с blauncher.ru и поставьте поверх.", "warning"));
    }

    private void checkForUpdateInBackground() {
        reportPreviousUpdate();
        executor.submit(() -> {
            UpdateService.UpdateManifest manifest = updateService.checkForUpdate();
            if (manifest == null || manifest.latestVersion == null) {
                return; // no network, bad manifest, or check failed — never bother the person about this
            }
            if (!updateService.isNewer(manifest.latestVersion, LAUNCHER_VERSION)) {
                return; // already up to date
            }
            pendingUpdateManifest = manifest;
            if (!manifest.notify && !silentUpdateFailed && stageUpdateSilently(manifest)) return;
            Platform.runLater(this::showUpdateModal);
        });
    }

    /**
     * Тихое обновление. 26.10.2026 за сутки окно обновления показали 20 раз, «Позже» нажали 18:
     * человек открыл лаунчер поиграть, а не обновляться, и исправления до него не доходили — 12 из
     * 25 лаунчеров сидели на 1.14.25 при вышедшей 1.14.29. Теперь jar качается в фоне, проверяется
     * по sha256 и ставится, когда лаунчер закрывают; окно — только при "notify": true в манифесте
     * или если прошлое тихое обновление не встало. Только app-image: у BLauncher.bat своя схема.
     *
     * @return false — не вышло (не app-image, нет сети); тогда показываем окно, как раньше
     */
    private boolean stageUpdateSilently(UpdateService.UpdateManifest manifest) {
        if (!UpdateService.isAppImageInstall(resolveCurrentExecutablePath())) return false;
        String url = manifest.downloadUrlExe, sha = manifest.sha256Exe;
        if (url == null || url.isBlank() || sha == null || sha.isBlank()) return false;
        try {
            Path dir = UPDATE_STAGED_DIR.toAbsolutePath();
            Path target = dir.resolve("BLauncher-" + manifest.latestVersion + ".jar");
            // Уже скачано прошлым запуском (лаунчер перезапускали сменой языка, а не закрытием).
            boolean ready = Files.isRegularFile(target) && sha.trim().equalsIgnoreCase(updateService.sha256Hex(target));
            if (!ready) {
                Path downloaded = updateService.downloadAndVerify(url, sha, null);
                Files.createDirectories(dir);
                try (var old = Files.list(dir)) {   // прошлые версии не копим
                    old.forEach(f -> { try { Files.deleteIfExists(f); } catch (Exception ignored) { } });
                }
                Files.move(downloaded, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                try { Files.deleteIfExists(downloaded.getParent()); } catch (Exception ignored) { }
            }
            stagedUpdate = target;
            stagedUpdateVersion = manifest.latestVersion;
            log("[UPDATE] Скачано обновление " + manifest.latestVersion + " — установится при закрытии лаунчера");
            stat("update_offer", LAUNCHER_VERSION + "-" + manifest.latestVersion + " silent=1");
            return true;
        } catch (Exception e) {
            stat("update_fail", LAUNCHER_VERSION + "-" + manifest.latestVersion
                    + " reason=dl_" + e.getClass().getSimpleName() + " silent=1");
            return false;
        }
    }

    /** Из MainApp при закрытии окна, последним шагом перед System.exit: ставит молча скачанное
     *  обновление (stageUpdateSilently). Лаунчер после замены не перезапускается. */
    public void applyStagedUpdateOnExit() {
        Path staged = stagedUpdate;
        String to = stagedUpdateVersion;
        if (staged == null || to == null || !Files.isRegularFile(staged)) return;
        try {
            // Итог проверит и отправит в статистику следующий запуск (reportPreviousUpdate).
            Files.createDirectories(UPDATE_PENDING.getParent());
            Files.deleteIfExists(UPDATE_FAILED);
            Files.deleteIfExists(UPDATE_FAILED.resolveSibling(UpdateService.UPDATE_TRACE_FILE));
            Files.writeString(UPDATE_PENDING, LAUNCHER_VERSION + " " + to + " " + System.currentTimeMillis() + " silent");
            updateService.applyUpdateOnExit(staged, resolveCurrentExecutablePath(), UPDATE_FAILED);
        } catch (Exception e) {
            try { Files.deleteIfExists(UPDATE_PENDING); } catch (Exception ignored) { }
        }
    }

    private void showUpdateModal() {
        if (pendingUpdateManifest == null) return;
        updateVersionLabel.setText(LAUNCHER_VERSION + " → " + pendingUpdateManifest.latestVersion);
        updateChangelogLabel.setText(
                pendingUpdateManifest.changelog != null && !pendingUpdateManifest.changelog.isBlank()
                        ? pendingUpdateManifest.changelog
                        : "Список изменений недоступен.");
        updateProgressBox.setVisible(false);
        updateProgressBox.setManaged(false);
        updateNowBtn.setDisable(false);
        updateLaterBtn.setDisable(false);
        showOverlay(updateOverlay);
        // 21.10.2026: из 41 лаунчера за три дня 30 так и сидят на 1.14.11–1.14.22, и не видно,
        // предлагали ли им обновиться и отказались ли они. update_offer/update_later это и показывают.
        stat("update_offer", LAUNCHER_VERSION + "-" + pendingUpdateManifest.latestVersion);
    }

    @FXML
    private void onDismissUpdate() {
        hideOverlay(updateOverlay);
        if (pendingUpdateManifest != null) stat("update_later", LAUNCHER_VERSION + "-" + pendingUpdateManifest.latestVersion);
    }

    @FXML
    private void onConfirmUpdate() {
        if (pendingUpdateManifest == null) return;

        // Two install layouts can update themselves, and they replace different things: a jpackage
        // app image swaps app\BLauncher.jar next to the .exe, a BLauncher.bat install unpacks the
        // jar over build\classes (see UpdateService.applyClassesUpdateAndRestart). Which one this
        // is must NOT be decided by "the command ends in .exe" — under .bat the command is the
        // JDK's own javaw.exe, which passed that test and made every .bat install fail its update
        // with "Expected an 'app' folder next to ...\jdk-21\bin\javaw.exe". Anything else (an IDE
        // run, an unrecognised layout) still falls back to download-and-replace-by-hand.
        Path currentExe = resolveCurrentExecutablePath();
        boolean appImage = UpdateService.isAppImageInstall(currentExe);
        Path classesDir = appImage ? null : UpdateService.resolveOwnClassesDir();
        boolean canSelfUpdate = appImage || classesDir != null;
        String downloadUrl = appImage ? pendingUpdateManifest.downloadUrlExe : pendingUpdateManifest.downloadUrlJar;
        String expectedSha = appImage ? pendingUpdateManifest.sha256Exe : pendingUpdateManifest.sha256Jar;

        if (downloadUrl == null || downloadUrl.isBlank()) {
            showToast("Ссылка на обновление недоступна — попробуйте позже", "error");
            return;
        }

        updateNowBtn.setDisable(true);
        updateLaterBtn.setDisable(true);
        updateProgressBox.setVisible(true);
        updateProgressBox.setManaged(true);
        updateProgressLabel.setText("Скачивание обновления…");
        updateProgressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);

        executor.submit(() -> {
            try {
                Path downloaded = updateService.downloadAndVerify(downloadUrl, expectedSha,
                        pct -> Platform.runLater(() -> updateProgressBar.setProgress(pct / 100.0)));

                if (!canSelfUpdate) {
                    Platform.runLater(() -> {
                        updateProgressLabel.setText("Скачано — замените jar-файл вручную и перезапустите BLauncher.");
                        updateProgressBar.setProgress(1);
                        showToast("Обновление скачано в " + downloaded, "success");
                    });
                    return;
                }

                Platform.runLater(() -> updateProgressLabel.setText("Установка обновления — BLauncher перезапустится..."));
                // Запомнить, на что обновляемся: проверит уже перезапущенный лаунчер
                // (reportPreviousUpdate). Статистику прямо здесь не шлём — процесс сейчас выйдет,
                // и событие не успело бы уйти.
                Files.createDirectories(UPDATE_PENDING.getParent());
                Files.deleteIfExists(UPDATE_FAILED);
                Files.deleteIfExists(UPDATE_FAILED.resolveSibling(UpdateService.UPDATE_TRACE_FILE));
                Files.writeString(UPDATE_PENDING, LAUNCHER_VERSION + " " + pendingUpdateManifest.latestVersion
                        + " " + System.currentTimeMillis());
                try {
                    if (appImage) {
                        updateService.applyUpdateAndRestart(downloaded, currentExe, UPDATE_FAILED);
                    } else {
                        updateService.applyClassesUpdateAndRestart(downloaded, classesDir);
                    }
                } catch (Exception applyFailed) {
                    Files.deleteIfExists(UPDATE_PENDING);
                    stat("update_fail", LAUNCHER_VERSION + "-" + pendingUpdateManifest.latestVersion
                            + " reason=" + applyFailed.getClass().getSimpleName());
                    throw applyFailed;
                }

                // The self-replace script is now an independent process (see
                // UpdateService.applyUpdateAndRestart()'s own javadoc — same detached-process
                // pattern already used for the dedicated server / playit.gg elsewhere) — it
                // survives this JVM exiting, which is exactly what needs to happen now for it to
                // be able to overwrite this exe's file lock.
                Platform.runLater(() -> {
                    shutdownBackgroundProcesses();
                    Platform.exit();
                    System.exit(0);
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    updateNowBtn.setDisable(false);
                    updateLaterBtn.setDisable(false);
                    updateProgressBox.setVisible(false);
                    updateProgressBox.setManaged(false);
                    showToast("Не удалось установить обновление: " + ex.getMessage(), "error");
                });
            }
        });
    }

    /** Resolves this launcher's own currently-running executable path, for
     *  UpdateService.applyUpdateAndRestart() to know what file to replace. Returns null if it
     *  can't be determined (e.g. running under a debugger/IDE with no real launch command) —
     *  callers treat that the same as "not a real .exe install" and fall back to the manual-jar
     *  message instead of guessing at a path that might be wrong. */
    private Path resolveCurrentExecutablePath() {
        try {
            return ProcessHandle.current().info().command().map(Path::of).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    // ============================================================
    //  Shutdown
    // ============================================================

    /** Событие анонимной статистики (см. OnlineStatsService.event) — без ожидания сети. */
    private void stat(String event, String detail) {
        lastStatAt = System.currentTimeMillis();
        if (onlineStatsService != null) onlineStatsService.event(event, detail);
    }

    /**
     * Причина сбоя для статистики: класс исключения, шаг запуска и метка вместо текста.
     * 13.10.2026 игрок дважды получил «launch_fail IOException» на Forge 1.19.2, и по одному имени
     * класса нельзя было понять, что сломалось: скачивание, установщик или сама игра. Текст
     * сообщения не отправляем — в нём пути с именем пользователя Windows; только метки отсюда.
     */
    /**
     * Человеческая подсказка, если сбой — это сеть, иначе null. 16.10.2026 игрок c2e80bea
     * получил девять сбоев подряд (ConnectException на скачивании, затем на Fabric), и каждый раз
     * видел только «Загрузка не удалась» / «Запуск не удался» без слова о том, что дело в
     * подключении и что уже скачанное не пропадёт.
     */
    static String networkHint(Throwable ex, String phase) {
        if (isDiskFull(ex)) {
            return "На диске закончилось место — освободите несколько гигабайт (Корзина, Загрузки) "
                    + "и нажмите «Играть» ещё раз, скачанное не пропадёт";
        }
        // «dl» — скачивание самой игры; «loader»/«start» — установка загрузчика и список его версий.
        boolean loader = "loader".equals(phase) || "start".equals(phase);
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.net.UnknownHostException) {
                return "Нет интернета или DNS не находит адрес — проверьте подключение и нажмите «Играть» ещё раз";
            }
            if (t instanceof java.net.ConnectException || t instanceof java.net.NoRouteToHostException) {
                return "Не удаётся подключиться к " + (loader ? "серверу загрузчика" : "серверам Minecraft")
                        + ". Проверьте интернет, VPN или антивирус и нажмите «Играть» ещё раз — скачанное не пропадёт";
            }
            if (t instanceof java.net.SocketTimeoutException || t instanceof java.net.SocketException
                    || t instanceof javax.net.ssl.SSLException) {
                return "Связь с " + (loader ? "сервером загрузчика" : "серверами Minecraft")
                        + " обрывается. Нажмите «Играть» ещё раз — докачается только недостающее";
            }
        }
        return null;
    }

    /**
     * Java скачалась, но не встала, и это не сеть и не место на диске. 21.10.2026 (7267c860): наш
     * сервер трижды отдал JRE целиком, а у игрока то не сходилась контрольная сумма, то файл
     * обрывался на последних байтах — так ведёт себя антивирус, проверяющий архив с java.exe на
     * лету. Игрок не увидел ни слова, попробовал войти через Microsoft и ушёл.
     */
    static final String JAVA_FAIL_HINT = "Не удалось установить Java для игры. Чаще всего мешает антивирус: "
            + "добавьте папку лаунчера в исключения или ненадолго отключите проверку загрузок и нажмите «Играть» ещё раз";

    /** Сбой из-за того, что кончилось место на диске (Windows по-английски и по-русски, Linux).
     *  Установщик такое уже видел у игроков (installs: «IOException:space»), лаунчер — нет. */
    static boolean isDiskFull(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String m = t.getMessage();
            if (m == null) continue;
            String low = m.toLowerCase(java.util.Locale.ROOT);
            if (low.contains("not enough space on the disk") || low.contains("no space left on device")
                    || low.contains("недостаточно места на диске")) {
                return true;
            }
        }
        return false;
    }

    private String failDetail(Throwable ex) {
        String phase = launchPhase == null || launchPhase.isEmpty() ? "" : " phase=" + launchPhase;
        String msg = ex.getMessage() == null ? "" : ex.getMessage();
        String tag;
        java.util.regex.Matcher exit = java.util.regex.Pattern
                .compile("Установщик завершился с кодом (-?\\d+)").matcher(msg);
        if (exit.find()) {
            tag = "installer_exit=" + exit.group(1)
                    + (msg.contains("failed to download") || msg.contains("Failed to download") ? " libs_dl" : "");
        } else if (msg.startsWith("Установщик завис")) {
            tag = "installer_hang";
        } else if (msg.startsWith("Installer timed out")) {
            tag = "installer_timeout";
        } else if (msg.startsWith("Не удалось скачать установщик")) {
            tag = "installer_dl";
        } else if (msg.startsWith("Скачанный установщик повреждён")) {
            tag = "installer_bad";
        } else if (msg.startsWith("Java для запуска установщика не найдена")) {
            tag = "no_java";
        } else if (msg.startsWith("Не удалось подобрать сборку")) {
            tag = "no_build";
        } else if (msg.startsWith("Version not installed") || msg.startsWith("Version JSON not found")) {
            tag = "not_installed";
        } else if (isDiskFull(ex)) {
            tag = "disk_full";
        } else {
            tag = "";
        }
        // Первопричина часто информативнее обёртки (SocketTimeoutException внутри IOException).
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String cause = root != ex ? " cause=" + root.getClass().getSimpleName() : "";
        // Что подвело при установке Java — по источникам (JavaDownloadService.lastFailTag).
        String java = "java".equals(launchPhase) && javaDownloadService != null
                && !javaDownloadService.lastFailTag().isEmpty() ? " jv=" + javaDownloadService.lastFailTag() : "";
        return ex.getClass().getSimpleName() + phase + (tag.isEmpty() ? "" : " " + tag) + cause + java
                + (tag.isEmpty() ? failWhere(root) : "");
    }

    /**
     * Где в нашем коде случился сбой («at=GameLaunchService.syncMods:1597») и, для неверного пути,
     * код недопустимого знака («char=58» — двоеточие). 27.10.2026 ab54a7b2 около 60 раз подряд
     * получил «InvalidPathException phase=start», и понять по отметке, какой путь сломан, было
     * нельзя. Только имена классов и номер знака — без самого пути (в нём имя пользователя Windows).
     */
    static String failWhere(Throwable root) {
        String out = "";
        if (root instanceof java.nio.file.InvalidPathException ipe && ipe.getReason() != null) {
            java.util.regex.Matcher ch = java.util.regex.Pattern.compile("<(.)>").matcher(ipe.getReason());
            if (ch.find()) out += " char=" + (int) ch.group(1).charAt(0);
        }
        for (StackTraceElement f : root.getStackTrace()) {
            if (!f.getClassName().startsWith("com.example.launcher.")) continue;
            String cls = f.getClassName().substring(f.getClassName().lastIndexOf('.') + 1);
            out += " at=" + (cls + "." + f.getMethodName()).replaceAll("[^A-Za-z0-9_.]", "_") + ":" + f.getLineNumber();
            break;
        }
        return out;
    }

    /**
     * Из MainApp при закрытии окна, до System.exit. Если в этот момент идёт игра, отмечаем это:
     * дальше ping'ов от этого игрока не будет, и счётчик «в сети» его не видит (на 10.10 он
     * показывал 1 при 16 активных за сутки — какая часть разницы отсюда, этим и узнаем).
     */
    public void reportCloseIfInGame() {
        try {
            flushUiClicks(true);
            if (onlineStatsService != null && gameLaunchService != null && gameLaunchService.isGameRunning()) {
                onlineStatsService.eventNow("closed_in_game", "");
            } else if (onlineStatsService != null) {
                // Закрыл лаунчер без игры: сколько просидел, нажимал ли «Играть», есть ли вход
                // и где был последним — на каком шаге уходят новые (11.10.2026).
                long min = (System.currentTimeMillis() - sessionStartedAt) / 60_000L;
                // busy= — закрыл посреди запуска и на каком этапе («dl:45» — на 45% загрузки), 13.10.2026.
                String busy = gameStarting ? busyTag() : "";
                // last= — где был в момент закрытия и сколько секунд там пробыл, idle= — сколько
                // секунд до этого ничего не происходило, msgs= — сколько ошибок ему показали (22.10.2026).
                long now = System.currentTimeMillis();
                onlineStatsService.eventNow("closed", "min=" + min + " played=" + (playClicked ? 1 : 0)
                        + " acc=" + accountKind() + " screens=" + screensSeen.size()
                        + (busy.isEmpty() ? "" : " busy=" + busy)
                        + (currentScreen != null ? " last=" + currentScreen + ":" + (now - currentScreenSince) / 1000 : "")
                        + " idle=" + (now - lastStatAt) / 1000 + " msgs=" + msgStats);
            }
        } catch (Exception ignored) { }
    }

    /** Ловит нажатия во всём окне — кнопки, переключатели, списки, флажки, кликабельные карточки. */
    private void installUiClickTracker() {
        if (primaryStage == null) return;
        // ACTION шлют и списки, которые лаунчер заполняет сам (setItems/select): 29.10.2026 в первой
        // же ночи 1.14.41 «serverVanillaVersionCombo=15 newServerVersionCombo=0» пришло 88 раз без
        // единого щелчка. Считаем ACTION, только если чуть раньше был настоящий ввод мышью или клавишей.
        long[] lastInput = {0};
        primaryStage.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_RELEASED, e -> lastInput[0] = System.currentTimeMillis());
        primaryStage.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> lastInput[0] = System.currentTimeMillis());
        primaryStage.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            if (!(e.getTarget() instanceof Node n)) return;
            // Пункт списка выбирают во всплывающем окне — его щелчок сюда не доходит, но сам список
            // в этот момент в фокусе: его открыл человек.
            boolean userCombo = n instanceof javafx.scene.control.ComboBoxBase<?> && n.isFocused();
            if (!userCombo && System.currentTimeMillis() - lastInput[0] > 1500) return;
            countUiClick(n, true);
        });
        primaryStage.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, e -> {
            if (!(e.getTarget() instanceof Node n)) return;
            // Карточки и плитки, у которых свой onMouseClicked; кнопки уже посчитаны через ACTION.
            for (Node p = n; p != null; p = p.getParent()) {
                if (p instanceof javafx.scene.control.ButtonBase || p instanceof javafx.scene.control.ComboBoxBase<?>
                        || p instanceof javafx.scene.control.TextInputControl) return;
                if (p.getOnMouseClicked() != null || p.getOnMousePressed() != null) {
                    countUiClick(p, false);
                    return;
                }
            }
        });
    }

    private void countUiClick(Node node, boolean action) {
        String key = uiKey(node);
        if (key == null) return;
        // Состояние после нажатия — у переключателей и флажков; у списков — номер выбранного пункта
        // (не текст: в списках бывают названия сборок и ники).
        if (node instanceof CheckBox cb) key += cb.isSelected() ? "=1" : "=0";
        else if (node instanceof ToggleButton tb && !(node.getStyleClass().contains("nav-button"))) key += tb.isSelected() ? "=1" : "=0";
        else if (node instanceof ComboBox<?> c) key += "=" + c.getSelectionModel().getSelectedIndex();
        else if (!action) key = "c." + key;   // карточка, а не кнопка
        synchronized (uiClicks) {
            if (uiClicks.size() < 40 || uiClicks.containsKey(key)) uiClicks.merge(key, 1, Integer::sum);
        }
    }

    /** fx:id элемента, иначе «fx:id ближайшего родителя.первый стиль». null — нечего назвать. */
    private static String uiKey(Node node) {
        if (node.getId() != null && !node.getId().isBlank()) return node.getId();
        String kind = node.getStyleClass().isEmpty() ? node.getClass().getSimpleName() : node.getStyleClass().get(0);
        for (Node p = node.getParent(); p != null; p = p.getParent()) {
            if (p.getId() != null && !p.getId().isBlank()) return p.getId() + "." + kind;
        }
        return null;
    }

    /**
     * Отправляет накопленные нажатия экрана: «ui screen a:2 b c.card flag=1 combo=3» (event.php пропускает только A-Za-z0-9_ .:=-). detail на сервере режется
     * до 120 символов — длинный список уходит несколькими событиями (part=2…).
     */
    private void flushUiClicks(boolean now) {
        String screen = currentScreen == null ? "start" : currentScreen;
        java.util.List<String> parts = new java.util.ArrayList<>();
        synchronized (uiClicks) {
            if (uiClicks.isEmpty()) return;
            for (var e : uiClicks.entrySet()) parts.add(e.getValue() > 1 ? e.getKey() + ":" + e.getValue() : e.getKey());
            uiClicks.clear();
        }
        StringBuilder chunk = new StringBuilder();
        int part = 1;
        for (String p : parts) {
            String head = screen + (part > 1 ? " part=" + part : "") + " ";
            if (chunk.length() > 0 && head.length() + chunk.length() + 1 + p.length() > 118) {
                sendUi(head + chunk, now);
                chunk.setLength(0);
                part++;
            }
            if (chunk.length() > 0) chunk.append(' ');
            chunk.append(p);
        }
        if (chunk.length() > 0) sendUi(screen + (part > 1 ? " part=" + part : "") + " " + chunk, now);
    }

    private void sendUi(String detail, boolean now) {
        if (uiStats++ >= 120 || onlineStatsService == null) return;   // потолок на сессию, как у screen
        if (now) onlineStatsService.eventNow("ui", detail);
        else stat("ui", detail);
    }

    /** msa — вход через Microsoft, nick — задан ник для офлайна, none — ни того ни другого. */
    private String accountKind() {
        if (authService != null && authService.isLoggedIn()) return "msa";
        String nick = usernameField == null ? null : usernameField.getText();
        return nick != null && !nick.isBlank() ? "nick" : "none";
    }

    /** Одно событие на запуск лаунчера, через 20 с — когда вход восстановлен и сборки прочитаны. */
    private void reportSessionStart() {
        PauseTransition later = new PauseTransition(Duration.seconds(20));
        later.setOnFinished(e -> {
            long own = builds.stream().filter(b -> !b.isVanillaEntry()).count();
            int installed = 0;
            try (var s = Files.list(gameDir.resolve("versions"))) {
                installed = (int) s.filter(Files::isDirectory).count();
            } catch (Exception ignored) { }
            // os= — 21.10.2026 Linux-архив скачали, а запустили ли его, по отметкам было не понять:
            // отметки Linux- и Windows-лаунчера ничем не отличались.
            stat("session", "acc=" + accountKind() + " builds=" + own + " versions=" + installed
                    + " os=" + (Os.isWindows() ? "windows" : Os.isLinux() ? "linux" : Os.isMac() ? "mac" : "other")
                    + (homeRustSelected() ? " engine=rust" : ""));
            if (installed == 0) maybeStartPrefetch();
        });
        later.play();
    }

    // ============================================================
    //  Фоновая загрузка игры при первом открытии (25.10.2026)
    // ============================================================

    /**
     * За неделю 11 новичков нажали «Играть» и закрыли лаунчер, не дождавшись первой загрузки
     * (500–700 МБ, 1,5–9 минут). При этом до нажатия они сами по 2–5 минут листали разделы. Поэтому
     * при первом открытии (ни одной скачанной версии) игра начинает качаться сразу, тихо, в
     * отдельном потоке: версия той сборки, что выбрана, или «Без модов». Звуки и текстуры у версий
     * почти общие (лежат по хешам), так что выигрыш есть, даже если человек потом выберет другую.
     * На «Играть» фоновая загрузка останавливается (stopPrefetch), уже скачанное остаётся.
     */
    private volatile VersionDownloadService prefetchService;
    private volatile Thread prefetchThread;
    private volatile String prefetchVersion;

    private void maybeStartPrefetch() {
        if (prefetchThread != null || gameStarting || playClicked) return;
        String v = selectedBuild != null && !selectedBuild.isVanillaEntry() ? selectedBuild.getVersionId()
                : homeVanillaVersion();
        if (v == null || v.isBlank()) return;
        VersionDownloadService svc = new VersionDownloadService(gameDir);
        prefetchService = svc;
        prefetchVersion = v;
        Thread t = new Thread(() -> {
            long t0 = System.currentTimeMillis(), b0 = Http.DOWNLOADED.get();
            String result;
            try {
                svc.downloadVersion(v, msg -> { });
                result = "done";
            } catch (InterruptedException e) {
                result = "stopped";
            } catch (Exception e) {
                result = "fail exc=" + e.getClass().getSimpleName();
            }
            log("[PREFETCH] " + v + ": " + result);
            stat("prefetch", result + " v=" + v + " sec=" + (System.currentTimeMillis() - t0) / 1000
                    + " mb=" + (Http.DOWNLOADED.get() - b0) / 1_048_576);
            prefetchService = null;
        }, "bl-prefetch");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        prefetchThread = t;
        log("[PREFETCH] Заранее качаю Minecraft " + v + ", чтобы «Играть» сработало быстрее");
        t.start();
    }

    /**
     * Останавливает фоновую загрузку и ждёт её выхода (не дольше 20 с). Возвращает метку для
     * dl_done: « pre=<версия>» — фоновая загрузка была (её байты в dl_done уже не входят).
     */
    private String stopPrefetch() {
        Thread t = prefetchThread;
        if (t == null) return "";
        VersionDownloadService svc = prefetchService;
        if (svc != null) svc.cancel();
        try {
            t.join(20_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return " pre=" + prefetchVersion;
    }

    /** Called from MainApp's setOnCloseRequest — a dedicated server is spawned as an independent
     *  OS process, so exiting this JVM does NOT kill it on Windows without this. */
    public void shutdownBackgroundProcesses() {
        com.example.launcher.ui.DesktopNotifier.dispose();   // иначе значок в трее висит до наведения мыши
        ModLoaderService.stopRunningInstaller();   // отдельный процесс Java — сам с лаунчером не умрёт
        try {
            if (ztPoller != null) {
                ztPoller.stop();
            }
            if (ztInfoServer != null) {
                try { ztInfoServer.close(); } catch (Exception ignored) { }
                ztInfoServer = null;
            }
            if (gameLaunchService != null && gameLaunchService.isServerRunning()) {
                gameLaunchService.stopAllServers();
            }
        } catch (Exception ignored) { }
        // Авто-выключение ZeroTier при закрытии лаунчера: покидаем все комнаты, и демон сам
        // снимает их виртуальные адаптеры — иначе комнаты остаются висеть, а адаптеры
        // («ZeroTier Virtual Port #N») копятся в системе между сессиями.
        if (zeroTierService != null) {
            try {
                zeroTierService.leaveAllRooms(this::logZeroTierOutput);
            } catch (Exception ignored) { }
        }
        if (focusedPoller != null) {
            focusedPoller.stop();
        }
        if (friendsPoller != null) {
            friendsPoller.stop();
        }
        if (onlineStatsService != null) {
            onlineStatsService.stop();
        }
        executor.shutdownNow();
        // Пул опроса ZeroTier — отдельный (см. поле ztExecutor), закрывать его тоже надо здесь.
        ztExecutor.shutdownNow();
    }
}
