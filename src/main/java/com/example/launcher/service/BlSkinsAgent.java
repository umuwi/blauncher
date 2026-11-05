package com.example.launcher.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * BL Skins: встроенный в лаунчер способ показать свой скин и плащ в игре, на чистой игре и с любым
 * загрузчиком (исходники агента — skinagent/). Раньше без лицензии скин работал только с модом
 * CustomSkinLoader в сборке: --userProperties новая игра не читает, а адреса 127.0.0.1 не пропускает.
 */
public final class BlSkinsAgent {

    private static final String RESOURCE = "/blskins/blskins-agent.jar";
    /** Путь от рабочей папки игры: с кириллицей в абсолютном пути JVM не открывает агент и не запускается. */
    private static final String RELATIVE = "blcore/blskins-agent.jar";

    private BlSkinsAgent() {
    }

    /** Ключи JVM для этого запуска; пусто, если подставлять нечего или агент не удалось положить на диск. */
    public static List<String> jvmArgs(Path gameDir, String username, String skinUrl, String capeUrl, Consumer<String> log) {
        List<String> args = new ArrayList<>();
        if (username == null || username.isBlank() || (skinUrl == null && capeUrl == null)) return args;
        try {
            install(gameDir.resolve(RELATIVE));
            args.add("-javaagent:" + RELATIVE);
            args.add("-Dblskins.name=" + username);
            if (skinUrl != null) args.add("-Dblskins.skin=" + skinUrl);
            if (capeUrl != null) args.add("-Dblskins.cape=" + capeUrl);
            if (skinUrl != null && skinUrl.contains("-slim.")) args.add("-Dblskins.slim=true");
        } catch (Exception e) {
            args.clear();
            log.accept("[SKIN] BL Skins не подключён: " + e.getMessage());
        }
        return args;
    }

    private static void install(Path target) throws IOException {
        byte[] bytes;
        try (InputStream in = BlSkinsAgent.class.getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IOException("нет файла агента в сборке лаунчера");
            bytes = in.readAllBytes();
        }
        if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), bytes)) return;
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
}
