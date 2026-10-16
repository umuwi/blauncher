package ru.blauncher.blcore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * What the launcher told the mod on the command line. Nothing is stored by the mod itself.
 *
 * <pre>
 *   -Dblcore.self=Steve           the player's own nickname
 *   -Dblcore.badge=false          the player hid their own badge in the launcher's settings
 *   -Dblcore.selfCape=C:\...\cape.png   the cape drawn in the launcher, shown at once without a lookup
 * </pre>
 */
public final class BlConfig {

    public static final String VERSION = "1.0.0";

    private static final String SELF = System.getProperty("blcore.self", "");
    private static final boolean BADGE = !"false".equalsIgnoreCase(System.getProperty("blcore.badge", "true"));
    private static final String SELF_CAPE = System.getProperty("blcore.selfCape", "");
    private static volatile String selfCapeHash;
    private static volatile byte[] selfCapeBytes;

    private BlConfig() {}

    public static boolean isSelf(String name) {
        return !SELF.isEmpty() && SELF.toLowerCase(Locale.ROOT).equals(name.toLowerCase(Locale.ROOT));
    }

    public static boolean selfBadge() {
        return BADGE;
    }

    /** The own cape's content hash (same scheme as the site's), so it shares the texture cache. */
    public static String selfCapeHash() {
        if (SELF_CAPE.isEmpty()) return null;
        if (selfCapeHash == null) {
            try {
                byte[] bytes = Files.readAllBytes(Path.of(SELF_CAPE));
                if (bytes.length > 64 * 1024) return null;
                selfCapeBytes = bytes;
                byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
                selfCapeHash = HexFormat.of().formatHex(digest).substring(0, 32);
            } catch (Exception e) {
                selfCapeHash = "";
            }
        }
        return selfCapeHash.isEmpty() ? null : selfCapeHash;
    }

    /** The own cape's bytes, read from disk rather than downloaded. */
    public static byte[] selfCapeBytes() {
        selfCapeHash();
        return selfCapeBytes;
    }
}
