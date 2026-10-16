package com.example.launcher.service;

import com.example.launcher.model.LauncherConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigService {
    private final Path configPath;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public ConfigService(Path configPath) {
        this.configPath = configPath;
    }

    /**
     * Читает настройки, а при негодном файле молча возвращается к значениям по умолчанию.
     *
     * <p>Раньше здесь обрабатывался только случай «файла нет». Файл, который есть, но непригоден
     * (пустой или обрезанный), ронял метод: {@code gson.fromJson("")} возвращает {@code null},
     * и следующая же строка падала с NPE — внутри {@code MainApp.start()}, то есть окно вообще
     * не открывалось, и так при каждом следующем запуске. Вылечить это пользователь мог только
     * удалив файл руками. Теперь битый файл откладывается в {@code .corrupt} (чтобы не потерять
     * возможность разобраться) и подставляются дефолты.
     */
    public LauncherConfig loadConfig() throws IOException {
        if (!Files.exists(configPath)) {
            LauncherConfig config = createDefaultConfig();
            saveConfig(config);
            return config;
        }

        LauncherConfig loaded;
        try {
            loaded = gson.fromJson(Files.readString(configPath), LauncherConfig.class);
        } catch (com.google.gson.JsonParseException ex) {
            loaded = null;   // обрезанный JSON — ниже тот же путь, что и для пустого файла
        }
        if (loaded == null) {
            quarantineUnreadable();
            LauncherConfig config = createDefaultConfig();
            saveConfig(config);
            return config;
        }
        // Backfill any new fields that might be missing in old configs
        if (loaded.getGameDirectory() == null) loaded.setGameDirectory("./launcher_root");
        if (loaded.getMinMemoryMb() <= 0) loaded.setMinMemoryMb(512);
        // 0 is a valid "not set yet" marker; Settings replaces it with the recommended value.
        if (loaded.getMaxMemoryMb() < 0) loaded.setMaxMemoryMb(0);
        if (loaded.getTheme() == null) loaded.setTheme("light");
        if (loaded.getJavaPath() == null) loaded.setJavaPath("");
        if (loaded.getLastSelectedVersion() == null) loaded.setLastSelectedVersion("");
        if (loaded.getScrollFps() <= 0) loaded.setScrollFps(60);
        return loaded;
    }

    /**
     * Сохраняет настройки атомарно: сначала во временный файл, потом одним переименованием.
     *
     * <p>Прямая запись в целевой файл была опасна именно из-за частоты вызовов — каждый тумблер
     * настроек, каждое сохранение профиля, и ранее каждое изменение слайдера памяти. Обрыв
     * питания в этот момент оставлял нулевой или обрезанный {@code launcher.json}, после чего
     * лаунчер не запускался вообще. При переименовании такого промежуточного состояния нет:
     * на диске либо прежний файл целиком, либо новый целиком.
     */
    public void saveConfig(LauncherConfig config) throws IOException {
        Files.createDirectories(configPath.getParent());
        Path tmp = configPath.resolveSibling(configPath.getFileName() + ".tmp");
        Files.writeString(tmp, gson.toJson(config));
        try {
            Files.move(tmp, configPath,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            // Редкие файловые системы и сетевые диски атомарного переименования не умеют —
            // там обычное всё равно лучше, чем запись поверх живого файла.
            Files.move(tmp, configPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Отводит непригодный файл в сторону, чтобы причину можно было посмотреть потом. */
    private void quarantineUnreadable() {
        try {
            Path broken = configPath.resolveSibling(configPath.getFileName() + ".corrupt");
            Files.move(configPath, broken, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[CONFIG] " + configPath.getFileName()
                    + " непригоден, отложен как " + broken.getFileName() + ", беру значения по умолчанию");
        } catch (IOException ex) {
            System.out.println("[CONFIG] не удалось отложить битый файл: " + ex.getMessage());
        }
    }

    private LauncherConfig createDefaultConfig() {
        LauncherConfig config = new LauncherConfig();
        config.setGameDirectory("./launcher_root");
        config.setJavaPath("");
        config.setMinMemoryMb(512);
        // 0 lets Settings choose the recommended value for this machine on first run.
        config.setMaxMemoryMb(0);
        config.setEnableAds(true);
        config.setTheme("light");
        config.setLastSelectedVersion("");
        config.setScrollFps(60);
        return config;
    }
}
