package com.example.launcher.service;

import com.example.launcher.model.Profile;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ProfileService {
    private final Path profilesPath;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public ProfileService(Path profilesPath) {
        this.profilesPath = profilesPath;
    }

    public List<Profile> loadAllProfiles() throws IOException {
        if (!Files.exists(profilesPath)) {
            Files.createDirectories(profilesPath);
            return new ArrayList<>();
        }

        List<Profile> profiles = new ArrayList<>();
        try (var stream = Files.list(profilesPath)) {
            stream.filter(path -> path.toString().endsWith(".json"))
                    .forEach(path -> {
                        // Каждый файл разбирается отдельно и его поломка не касается остальных.
                        // Ловить тут только IOException было мало: обрезанный JSON даёт
                        // JsonSyntaxException, и один плохой файл скрывал ВСЕ сборки сразу,
                        // а пустой файл давал в списке null, который потом ронял старт в
                        // discoverModFolderBuilds с NPE — до показа окна.
                        try {
                            String json = Files.readString(path);
                            Profile profile = gson.fromJson(json, Profile.class);
                            if (profile == null || profile.getName() == null || profile.getName().isBlank()) {
                                System.out.println("[PROFILES] пропускаю негодный файл " + path.getFileName());
                                return;
                            }
                            profiles.add(profile);
                        } catch (Exception e) {
                            System.out.println("[PROFILES] пропускаю " + path.getFileName() + ": " + e.getMessage());
                        }
                    });
        }
        return profiles;
    }

    public void saveProfile(Profile profile) throws IOException {
        Files.createDirectories(profilesPath);
        Path profileFile = profileFile(profile.getName());
        // Атомарно, по той же причине, что и в ConfigService: прерванная запись оставляла
        // нулевой файл профиля, а он потом ломал загрузку списка сборок.
        Path tmp = profileFile.resolveSibling(profileFile.getFileName() + ".tmp");
        Files.writeString(tmp, gson.toJson(profile));
        try {
            Files.move(tmp, profileFile,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
            Files.move(tmp, profileFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void deleteProfile(String profileName) throws IOException {
        Path profileFile = profileFile(profileName);
        if (Files.exists(profileFile)) {
            Files.delete(profileFile);
        }
    }

    /**
     * Путь к файлу профиля по его имени.
     *
     * <p>Имя сборки раньше подставлялось в путь как есть, а оно не всегда своё: при импорте по
     * коду обмена оно приходит из чужой строки (MainController.onConfirmImport), и шифрование в
     * ModShareCodec по его собственному javadoc — обфускация, а не граница доверия. Имя вида
     * {@code ..\..\AppData\Roaming\...} заставляло лаунчер писать файлы за пределами своей папки.
     * Поэтому разделители и обход вырезаются, а итоговый путь дополнительно проверяется на
     * вложенность — ровно так же, как это делает ModpackService для файлов модпака.
     */
    private Path profileFile(String profileName) throws IOException {
        String safe = sanitizeFileName(profileName);
        Path candidate = profilesPath.resolve(safe + ".json").normalize();
        Path root = profilesPath.toAbsolutePath().normalize();
        if (!candidate.toAbsolutePath().normalize().startsWith(root)) {
            throw new IOException("Недопустимое имя сборки: " + profileName);
        }
        return candidate;
    }

    /** Убирает из имени всё, что делает его путём, а не именем. Остальное (включая кириллицу)
     *  сохраняется как есть, чтобы уже существующие файлы профилей продолжали находиться. */
    static String sanitizeFileName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Имя сборки пустое");
        }
        String safe = name.trim()
                .replace("\\", "_")
                .replace("/", "_")
                .replace(":", "_")
                .replace("*", "_")
                .replace("?", "_")
                .replace("\"", "_")
                .replace("<", "_")
                .replace(">", "_")
                .replace("|", "_");
        // Точки по краям: ".." превращается в "__", а имя из одних точек Windows не принимает.
        while (safe.startsWith(".")) safe = "_" + safe.substring(1);
        while (safe.endsWith(".")) safe = safe.substring(0, safe.length() - 1) + "_";
        if (safe.isBlank()) {
            throw new IllegalArgumentException("Имя сборки состоит только из недопустимых символов");
        }
        // Запас под ".json" и ".tmp" в пределах лимита имени файла.
        return safe.length() > 120 ? safe.substring(0, 120) : safe;
    }
}
