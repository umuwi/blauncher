package com.example.launcher;

import com.example.launcher.model.LauncherConfig;
import com.example.launcher.service.*;
import com.example.launcher.controller.MainController;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.text.Font;
import javafx.stage.Stage;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;

public class MainApp extends Application {
    private LauncherConfig config;

    /**
     * Loads i18n/strings_&lt;lang&gt;.properties as UTF-8 — java.util.ResourceBundle's default
     * .properties loading assumes ISO-8859-1, which would mangle every Cyrillic string in
     * strings_ru.properties. PropertyResourceBundle itself is encoding-agnostic; it just reads
     * whatever Reader it's given, so wrapping the stream in an explicit UTF-8 InputStreamReader
     * is what actually fixes this.
     */
    private static ResourceBundle loadStringsBundle(String lang) throws Exception {
        String resourcePath = "/i18n/strings_" + ("en".equals(lang) ? "en" : "ru") + ".properties";
        try (Reader reader = new InputStreamReader(
                MainApp.class.getResourceAsStream(resourcePath), StandardCharsets.UTF_8)) {
            return new PropertyResourceBundle(reader);
        }
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        // Kept, with a caveat that was measured rather than assumed: this call does NOT leave
        // the launcher without JavaFX's own styling. In the running window the dropdown arrow
        // is still JavaFX's white mark and the RAM slider still has JavaFX's black thumb, so a
        // control the theme does not name is painted by JavaFX - visible in every theme, and
        // wrong in the white one. The theme therefore has to name every part it wants to own
        // (see css/theme-template.css).
        setUserAgentStylesheet(null);
        loadBundledFonts();

        Path configPath = Path.of("config", "launcher.json");
        ConfigService configService = new ConfigService(configPath);
        this.config = configService.loadConfig();

        Path gameDir = Path.of(config.getGameDirectory()).toAbsolutePath().normalize();

        // Single instance: hold a lock file so a second BLauncher refuses to start and tells the
        // player it is already running. Two instances would both poll ZeroTier and both try to
        // manage the same dedicated servers - the second one's "Запустить" would collide with
        // the first one's server process (measured: two BLauncher.exe instances on this machine
        // during development produced exactly that confusion).
        if (!acquireSingleInstanceLock(gameDir)) {
            showAlreadyRunning(primaryStage);
            return;
        }

        VersionDownloadService versionDownloadService = new VersionDownloadService(gameDir);
        ProfileService         profileService         = new ProfileService(gameDir.resolve("profiles"));
        ModLoaderService       modLoaderService       = new ModLoaderService(gameDir, versionDownloadService);
        ModrinthService        modrinthService        = new ModrinthService(gameDir);
        JavaVersionDetector    javaVersionDetector    = new JavaVersionDetector();
        JavaDownloadService    javaDownloadService    = new JavaDownloadService(gameDir);
        ServerService          serverService          = new ServerService(gameDir.resolve("servers.json"));

        ResourceBundle bundle = loadStringsBundle(config.getLanguage());
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"), bundle);
        Parent root = loader.load();

        primaryStage.setTitle("BLauncher");
        try {
            primaryStage.getIcons().add(new Image(getClass().getResourceAsStream("/images/logo.png")));
        } catch (Exception ignored) { /* icon is cosmetic - never block startup on it */ }
        // 1050x700 is both the resize floor and the startup size - at the previous floor
        // (1000x650) the Play/Stop row had no breathing room and pressed right up against the
        // window's bottom edge, so the floor itself was raised by 50px in each dimension rather
        // than just padding the (separately larger, 1280x800) startup size around it.
        double startWidth = 1050;
        double startHeight = 700;
        primaryStage.setMinWidth(startWidth);
        primaryStage.setMinHeight(startHeight);
        Scene scene = new Scene(root, startWidth, startHeight);
        // No stylesheet here on purpose: the theme is generated from the saved palette
        // (PaletteService) and applied by MainController.init(), which runs before the
        // window is shown, so nothing flashes unstyled. The launcher used to ship one
        // hand-maintained stylesheet per theme, and they had drifted apart.
        primaryStage.setScene(scene);
        primaryStage.setWidth(startWidth);
        primaryStage.setHeight(startHeight);

        // Scene must exist BEFORE controller.init() runs — init() calls applyTheme(), which
        // needs primaryStage.getScene() to already be non-null to restore a saved theme choice.
        MainController controller = loader.getController();
        controller.init(config, configService, versionDownloadService, profileService,
                modLoaderService, modrinthService, javaVersionDetector, javaDownloadService,
                serverService, gameDir, primaryStage);

        primaryStage.setOnCloseRequest(e -> {
            // Идёт первая загрузка или установка загрузчика — переспросить (13.10.2026: трое
            // новичков закрыли окно посреди загрузки и до игры так и не дошли).
            if (!controller.confirmCloseDuringLaunch()) {
                e.consume();
                return;
            }
            // A dedicated server / playit.gg agent are spawned via ProcessBuilder as independent
            // OS processes - exiting this JVM does NOT kill them on Windows, so without this
            // they'd keep running invisibly (and pile up across launcher restarts) every time
            // the player closes BLauncher while either is still up.
            controller.reportCloseIfInGame();   // до shutdown: он останавливает статистику
            controller.shutdownBackgroundProcesses();
            controller.applyStagedUpdateOnExit();   // тихое обновление — после всего остального
            System.exit(0);
        });
        primaryStage.show();
    }

    /**
     * Registers the launcher's own fonts (src/main/resources/fonts) before any
     * scene exists.
     *
     * <p>Without this the stylesheet's font requests cannot be satisfied: it used
     * to ask for {@code "Inter"}, which is installed nowhere, so JavaFX resolved
     * every text node to family {@code System} and answered {@code -fx-font-weight}
     * requests with a synthesised bold - which is what made the interface look
     * cheap and slightly blurred.
     *
     * <p>The files are static TTFs, not variable ones, on purpose: JavaFX renders
     * a variable font as a single default instance and fakes the other weights.
     * They are also subset to Latin + Cyrillic + box drawing, so all seven faces
     * cost about 900 KB in the jar instead of 2.6 MB in the auto-update payload.
     *
     * <p>Note for anyone editing the CSS: because these faces declare their own
     * families, "Inter Medium" and "Inter SemiBold" are separate families
     * to JavaFX, so {@code -fx-font-weight: 500/600} never selects them - the
     * stylesheet must name the family explicitly.
     */
    private static void loadBundledFonts() {
        // Order matters, and it is not cosmetic. JavaFX's embedded font registry
        // keeps only two slots per family: one "regular-ish" face and one bold
        // face. Measured with FxFontOrderExperiment: every weight request from
        // THIN to SEMI_BOLD answers with whichever non-bold face was loaded LAST,
        // and only BOLD picks the bold one. Loading Regular last therefore makes
        // the default text resolve to Regular instead of to Medium/SemiBold.
        //
        // Medium and SemiBold cannot be reached by weight at all in this setup -
        // the stylesheet names them explicitly ("Inter SemiBold"), which does
        // resolve to the right face, as measured with CssThemeProbe.
        String[] fonts = {
            "/fonts/Inter-Medium.ttf",
            "/fonts/Inter-SemiBold.ttf",
            "/fonts/Inter-Bold.ttf",
            "/fonts/Inter-Regular.ttf",
            "/fonts/JetBrainsMono-Medium.ttf",
            "/fonts/JetBrainsMono-Bold.ttf",
            "/fonts/JetBrainsMono-Regular.ttf",
        };
        int loaded = 0;
        for (String path : fonts) {
            try (InputStream in = MainApp.class.getResourceAsStream(path)) {
                if (in == null) {
                    System.out.println("[fonts] нет в jar: " + path);
                    continue;
                }
                if (Font.loadFont(in, 13) == null) {
                    System.out.println("[fonts] не загрузился: " + path);
                } else {
                    loaded++;
                }
            } catch (Exception e) {
                System.out.println("[fonts] ошибка загрузки " + path + ": " + e);
            }
        }
        System.out.println("[fonts] загружено начертаний: " + loaded + " из " + fonts.length);
    }

    public static void main(String[] args) {
        launch(args);
    }

    /** The launcher keeps a lock file inside its game dir; returns true when this instance owns it. */
    private static java.nio.channels.FileLock singleInstanceLock;

    private static boolean acquireSingleInstanceLock(Path gameDir) {
        try {
            java.nio.file.Files.createDirectories(gameDir);
            java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(
                    gameDir.resolve("blauncher.lock"),
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
            java.nio.channels.FileLock lock = ch.tryLock();
            if (lock == null) {
                ch.close();
                return false;
            }
            singleInstanceLock = lock; // held for the life of the JVM; released on exit
            return true;
        } catch (Exception e) {
            // Locking is best-effort: an unusual filesystem should not block startup.
            return true;
        }
    }

    /**
     * Отпускает лок единственного экземпляра ПЕРЕД тем, как запустить свою замену.
     *
     * <p>Пути перезапуска (обновление и смена языка) стартуют новый процесс, пока этот ещё жив,
     * и тот сразу пробует взять тот же лок. Если он успевает раньше, чем эта JVM завершится, он
     * видит лок занятым, пишет «BLauncher уже запущен» и выходит — человек остаётся вообще без
     * лаунчера. Лок больше не нужен с момента, когда решение перезапуститься принято, поэтому
     * отдаём его сразу.
     */
    public static void releaseSingleInstanceLock() {
        java.nio.channels.FileLock lock = singleInstanceLock;
        singleInstanceLock = null;
        if (lock == null) return;
        try {
            java.nio.channels.Channel ch = lock.acquiredBy();
            lock.release();
            if (ch != null) ch.close();
        } catch (Exception ignored) {
            // Выход всё равно освободит его — это лишь ускоряет передачу эстафеты.
        }
    }

    private static void showAlreadyRunning(Stage primaryStage) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.INFORMATION);
        alert.setTitle("BLauncher");
        alert.setHeaderText(null);
        alert.setContentText("BLauncher уже запущен.\nОткройте существующее окно — второй экземпляр не нужен.");
        // Only own the dialog when the stage actually has a scene: this code runs before
        // the window is built, and Dialog.initOwner on a scene-less stage throws
        // NullPointerException inside JavaFX (HeavyweightDialog.updateStageBindings) - so
        // the second instance used to die with a stack trace instead of telling the player
        // that the launcher was already running.
        if (primaryStage.getScene() != null) {
            alert.initOwner(primaryStage);
        }
        alert.showAndWait();
        javafx.application.Platform.exit();
    }
}
