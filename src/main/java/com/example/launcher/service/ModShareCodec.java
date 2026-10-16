package com.example.launcher.service;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * Encodes/decodes a shareable modpack "link": a compact, self-contained code that carries a
 * build's name, target Minecraft version + loader, and its list of Modrinth mods. Pasting the
 * code into another BLauncher recreates the build (under its original name) and re-downloads
 * the mods.
 *
 * <p>Format: {@code Blauncher/link/<base64url(iv + AES-GCM ciphertext)>} — deliberately not an
 * {@code https://} URL (there's no server behind it, it never leaves the clipboard/chat app
 * that carries it) and not plain base64-of-JSON either: the payload is AES-256-GCM encrypted
 * with a key embedded in the launcher before encoding, so a code pasted into a text editor
 * shows opaque bytes rather than a readable build name/mod list. That key ships in every copy
 * of the app, so this is obfuscation against a casual look/paste, not a security boundary —
 * anyone who decompiles the jar can decrypt any code, same as when this was plain base64.
 */
public final class ModShareCodec {

    private static final String PREFIX = "Blauncher/link/";
    private static final Gson GSON = new Gson();
    private static final byte[] KEY_BYTES = sha256("BLauncher-share-link-v2-static-key");
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_BITS = 128;

    private ModShareCodec() {}

    /** A single shared mod: Modrinth project id + display title (title is cosmetic on import). */
    public static final class Mod {
        public String id;
        public String t;
        public Mod() {}
        public Mod(String id, String title) { this.id = id; this.t = title; }
    }

    /** The decoded contents of a share code. */
    public static final class Payload {
        public String n;          // build/profile name
        public String v;          // Minecraft version, e.g. "1.21.1"
        public String l;          // loader, e.g. "neoforge" / "fabric" / "vanilla"
        public String lv;         // loader version, e.g. "21.1.234" (may be null/empty)
        public List<Mod> m = new ArrayList<>();
    }

    public static String encode(String buildName, String mcVersion, String loader, String loaderVersion, List<Mod> mods) {
        Payload p = new Payload();
        p.n = buildName;
        p.v = mcVersion;
        p.l = loader;
        p.lv = loaderVersion;
        p.m = mods != null ? mods : new ArrayList<>();
        String json = GSON.toJson(p);
        byte[] encrypted = encrypt(json.getBytes(StandardCharsets.UTF_8));
        String b64 = Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
        return PREFIX + b64;
    }

    /** Parses a share code. Tolerates surrounding whitespace; throws on a malformed/foreign code. */
    public static Payload decode(String code) {
        if (code == null) throw new IllegalArgumentException("Empty code.");
        String trimmed = code.trim();
        if (!trimmed.startsWith(PREFIX)) {
            throw new IllegalArgumentException("Not a BLauncher build link (must start with \"" + PREFIX + "\").");
        }
        String b64 = trimmed.substring(PREFIX.length());
        byte[] encrypted;
        try {
            encrypted = Base64.getUrlDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("The link is corrupted (bad encoding).");
        }
        byte[] jsonBytes;
        try {
            jsonBytes = decrypt(encrypted);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("The link is corrupted or wasn't created by this launcher.");
        }
        Payload p;
        try {
            p = GSON.fromJson(new String(jsonBytes, StandardCharsets.UTF_8), Payload.class);
        } catch (JsonSyntaxException e) {
            throw new IllegalArgumentException("The link is corrupted (bad data).");
        }
        if (p == null || p.m == null || p.m.isEmpty()) {
            throw new IllegalArgumentException("The link contains no mods.");
        }
        return p;
    }

    private static byte[] encrypt(byte[] plaintext) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(KEY_BYTES, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM unavailable on this JVM", e);
        }
    }

    private static byte[] decrypt(byte[] blob) throws GeneralSecurityException {
        if (blob.length < GCM_IV_LENGTH) {
            throw new GeneralSecurityException("Blob too short to contain an IV.");
        }
        byte[] iv = Arrays.copyOfRange(blob, 0, GCM_IV_LENGTH);
        byte[] ciphertext = Arrays.copyOfRange(blob, GCM_IV_LENGTH, blob.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY_BYTES, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(ciphertext);
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
