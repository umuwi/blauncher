package com.example.launcher.service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * BL Core - the launcher's own small Fabric mod that draws the BLauncher badge next to the
 * nicknames of BLauncher players and shows their BLauncher capes.
 *
 * <p>It never goes into {@code mods/}: a modpack that refuses foreign mods, the mod sync that
 * makes {@code mods/} match a build exactly, and the player's own mod list all stay untouched.
 * The jar ships inside the launcher, is copied to {@code launcher_root/blcore/} and handed to
 * Fabric with {@code -Dfabric.addMods}. Only Fabric builds of a Minecraft version that has a BL
 * Core build get it; for anything else the launch is exactly what it was before.
 *
 * <p>Before the game starts, the launcher "leaves the mark" on blauncher.ru (nickname + UUID,
 * under the player's friends identity) and uploads the profile's cape, so other players' BL Core
 * can find both. With the badge turned off in Settings the mark is removed instead.
 */
public final class BlCoreService {

    private static final String RESOURCE_DIR = "/blcore/";

    private final Path dir;
    private final FriendsService friends;
    private final Supplier<Boolean> badgeEnabled;
    private final Supplier<Path> capeFile;
    private final ExecutorService executor;
    private final Consumer<String> log;

    /**
     * @param capeFile the current profile's cape file, or null; asked at launch time
     */
    public BlCoreService(Path gameDir, FriendsService friends, Supplier<Boolean> badgeEnabled,
                         Supplier<Path> capeFile, ExecutorService executor, Consumer<String> log) {
        this.dir = gameDir.resolve("blcore");
        this.friends = friends;
        this.badgeEnabled = badgeEnabled;
        this.capeFile = capeFile;
        this.executor = executor;
        this.log = log;
    }

    /** Whether the launcher carries a BL Core build for this exact Minecraft version. */
    public static boolean supports(String mcVersion) {
        return mcVersion != null && mcVersion.matches("[0-9a-z.\\-]+")
                && BlCoreService.class.getResource(resourceName(mcVersion)) != null;
    }

    private static String resourceName(String mcVersion) {
        return RESOURCE_DIR + "blcore-" + mcVersion + ".jar";
    }

    /**
     * The JVM arguments that load BL Core into this launch, and the mark left on the site. Empty
     * for anything but Fabric on a supported version - and on any failure, since a badge is never
     * worth a game that does not start.
     */
    public List<String> jvmArgs(String loader, String mcVersion, String username, String uuid) {
        List<String> args = new ArrayList<>();
        if (!"fabric".equalsIgnoreCase(loader) || !supports(mcVersion)) return args;
        boolean badge = badgeEnabled.get();
        if (!badge) {
            // The switch in Settings is also the way out if the mod ever gets in a game's way:
            // off means the game starts without BL Core at all, and the mark is taken down.
            leaveMark(username, uuid, false, null);
            log.accept("[BLCORE] Значок выключен в настройках — BL Core не подключается");
            return args;
        }
        try {
            Path jar = install(mcVersion);
            byte[] cape = normalizeCape(capeFile.get());
            Path capeOut = dir.resolve("self-cape.png");
            if (cape != null) {
                writeAtomically(capeOut, cape);
            } else {
                Files.deleteIfExists(capeOut);
            }
            args.add("-Dfabric.addMods=" + jar.toAbsolutePath());
            args.add("-Dblcore.self=" + username);
            args.add("-Dblcore.badge=" + badge);
            if (cape != null && badge) args.add("-Dblcore.selfCape=" + capeOut.toAbsolutePath());
            leaveMark(username, uuid, badge, cape);
            log.accept("[BLCORE] BL Core " + mcVersion + " подключён" + (badge ? "" : " (свой значок скрыт)"));
        } catch (Exception e) {
            log.accept("[BLCORE] Не подключён: " + e.getMessage());
            args.clear();
        }
        return args;
    }

    /** Copies the bundled jar out, rewriting it only when its content changed. */
    Path install(String mcVersion) throws IOException {
        Files.createDirectories(dir);
        byte[] bytes;
        try (InputStream in = BlCoreService.class.getResourceAsStream(resourceName(mcVersion))) {
            if (in == null) throw new IOException("нет сборки для " + mcVersion);
            bytes = in.readAllBytes();
        }
        Path target = dir.resolve("blcore-" + mcVersion + ".jar");
        if (!Files.isRegularFile(target) || !java.util.Arrays.equals(Files.readAllBytes(target), bytes)) {
            writeAtomically(target, bytes);
        }
        return target;
    }

    private static void writeAtomically(Path target, byte[] bytes) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * The profile's cape as the 64x32 PNG the game and the site expect: a 64x32 one as is, an old
     * 22x17 one placed in the corner of an empty 64x32. Anything else (HD capes, random pictures)
     * gives null - it would map onto the model wrong.
     */
    public static byte[] normalizeCape(Path capeFile) {
        try {
            if (capeFile == null || !Files.isRegularFile(capeFile)) return null;
            BufferedImage src = ImageIO.read(capeFile.toFile());
            if (src == null) return null;
            BufferedImage out = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
            if (src.getWidth() == 64 && src.getHeight() == 32) {
                out.getGraphics().drawImage(src, 0, 0, null);
            } else if (src.getWidth() == 22 && src.getHeight() == 17) {
                out.getGraphics().drawImage(src, 0, 0, null);
            } else {
                return null;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(out, "png", bytes);
            return bytes.size() <= 16384 ? bytes.toByteArray() : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** Same scheme as the site and the mod: the first 32 hex characters of the SHA-256. */
    public static String capeHash(byte[] png) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)).substring(0, 32);
        } catch (Exception e) {
            return "";
        }
    }

    private void leaveMark(String username, String uuid, boolean badge, byte[] cape) {
        String cleanUuid = uuid == null ? "" : uuid.replace("-", "").toLowerCase(Locale.ROOT);
        executor.submit(() -> {
            try {
                friends.leaveBlMark(username, cleanUuid, badge, badge ? cape : null);
            } catch (Exception e) {
                // the game runs either way; others just won't see the badge until the next launch
                log.accept("[BLCORE] Отметка не поставлена: " + e.getMessage());
            }
        });
    }
}
