package com.example.launcher.service.vps;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Хранилище секретов панели VPS: SSH-ключи, пароль sudo, пароль RCON.
 *
 * <p>Секреты живут только на этом ПК, в {@code config/vps_secrets.json}, и никогда не уходят ни
 * на blauncher.ru, ни в лог. Каждый секрет зашифрован AES-256-GCM главным ключом, а сам главный
 * ключ — через DPAPI Windows (ProtectedData, область CurrentUser). Значит, файл, скопированный на
 * другой ПК или открытый из-под другой учётной записи Windows, расшифровать нельзя.
 *
 * <p>Почему DPAPI вызывается через PowerShell, а не напрямую: в Java 21 нет готового доступа к
 * DPAPI (FFM без --enable-preview недоступен, а рантайм закреплён на 21), а тянуть JNA ради двух
 * вызовов — лишние 2 МБ в каждом обновлении. PowerShell вызывается ОДИН раз за сеанс — раскрыть
 * главный ключ; дальше всё шифрование идёт в Java. Ключ передаётся через stdin процесса, а не
 * аргументом: командную строку чужого процесса видит любой процесс того же пользователя.
 *
 * <p>Имя записи (например {@code vps/<id>/key}) входит в шифр как AAD: зашифрованное значение
 * одной записи нельзя молча подставить в другую.
 */
public final class SecretStore {

    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    /** Дополнительная энтропия DPAPI: без неё любой процесс пользователя расшифровал бы ключ одним
     *  вызовом Unprotect с null — с ней нужно ещё знать эту строку. Не секрет, но не пустота. */
    private static final String DPAPI_ENTROPY = "BLauncher-VPS-secrets-v1";

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final SecureRandom random = new SecureRandom();

    /** Раскрытый главный ключ — только в памяти этого процесса. */
    private byte[] masterKey;
    private Stored stored;

    /** Формат файла на диске. */
    private static final class Stored {
        int version = 1;
        /** Главный ключ, защищённый DPAPI, в base64. */
        String masterKey;
        /** имя → base64(iv || шифртекст+тег) */
        Map<String, String> items = new LinkedHashMap<>();
    }

    public SecretStore(Path file) {
        this.file = file;
    }

    /**
     * Проверяет, что хранилище работает (DPAPI доступен, главный ключ раскрывается), ничего не
     * записывая в секреты. Вызывать ДО изменений на VPS: иначе ключ окажется в authorized_keys,
     * а сохранить его у себя лаунчер уже не сможет.
     */
    public synchronized void ensureReady() throws IOException {
        load();
        masterKey();
    }

    public synchronized void put(String name, String secret) throws IOException {
        if (secret == null) {
            remove(name);
            return;
        }
        load();
        byte[] key = masterKey();
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(ct, 0, packed, iv.length, ct.length);
            stored.items.put(name, Base64.getEncoder().encodeToString(packed));
        } catch (GeneralSecurityException e) {
            throw new IOException("Не удалось зашифровать секрет", e);
        }
        save();
    }

    /** @return секрет или null, если такой записи нет. */
    public synchronized String get(String name) throws IOException {
        load();
        String packedB64 = stored.items.get(name);
        if (packedB64 == null) return null;
        byte[] key = masterKey();
        try {
            byte[] packed = Base64.getDecoder().decode(packedB64);
            if (packed.length <= GCM_IV_BYTES) throw new IOException("Запись " + name + " повреждена");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, packed, 0, GCM_IV_BYTES));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            byte[] pt = cipher.doFinal(packed, GCM_IV_BYTES, packed.length - GCM_IV_BYTES);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IOException("Не удалось расшифровать секрет " + name
                    + " — файл повреждён или создан под другой учётной записью Windows", e);
        }
    }

    public synchronized void remove(String name) throws IOException {
        load();
        if (stored.items.remove(name) != null) save();
    }

    /** Убирает все записи с этим префиксом — например всё, что относится к удалённому серверу. */
    public synchronized void removePrefix(String prefix) throws IOException {
        load();
        if (stored.items.keySet().removeIf(k -> k.startsWith(prefix))) save();
    }

    // ---------------------------------------------------------------- файл

    private void load() throws IOException {
        if (stored != null) return;
        if (!Files.exists(file)) {
            stored = new Stored();
            return;
        }
        try {
            Stored s = gson.fromJson(Files.readString(file), Stored.class);
            stored = s != null ? s : new Stored();
            if (stored.items == null) stored.items = new LinkedHashMap<>();
        } catch (JsonParseException e) {
            // Битый файл не затираем: в нём могут быть единственные копии ключей доступа к VPS.
            throw new IOException("Файл секретов " + file + " повреждён — его нужно проверить вручную", e);
        }
    }

    private void save() throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, gson.toJson(stored));
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private byte[] masterKey() throws IOException {
        if (masterKey != null) return masterKey;
        if (stored.masterKey == null) {
            byte[] fresh = new byte[32];
            random.nextBytes(fresh);
            stored.masterKey = Base64.getEncoder().encodeToString(dpapi(fresh, true));
            masterKey = fresh;
            save();
        } else {
            masterKey = dpapi(Base64.getDecoder().decode(stored.masterKey), false);
            if (masterKey.length != 32) {
                throw new IOException("Главный ключ хранилища секретов повреждён");
            }
        }
        return masterKey;
    }

    // ---------------------------------------------------------------- DPAPI

    private static final String DPAPI_SCRIPT =
            "$ErrorActionPreference='Stop';"
            + "Add-Type -AssemblyName System.Security;"
            + "$in=[Convert]::FromBase64String([Console]::In.ReadToEnd().Trim());"
            + "$e=[Text.Encoding]::UTF8.GetBytes('" + DPAPI_ENTROPY + "');"
            + "$s=[Security.Cryptography.DataProtectionScope]::CurrentUser;"
            + "$r=if($env:BL_DPAPI_MODE -eq 'protect'){[Security.Cryptography.ProtectedData]::Protect($in,$e,$s)}"
            + "else{[Security.Cryptography.ProtectedData]::Unprotect($in,$e,$s)};"
            + "[Console]::Out.Write([Convert]::ToBase64String($r))";

    private static byte[] dpapi(byte[] data, boolean protect) throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return keyFileWrap(data, protect);
        }
        String encoded = Base64.getEncoder().encodeToString(DPAPI_SCRIPT.getBytes(StandardCharsets.UTF_16LE));
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded);
        pb.environment().put("BL_DPAPI_MODE", protect ? "protect" : "unprotect");
        pb.redirectErrorStream(false);
        Process p = pb.start();
        try (OutputStream stdin = p.getOutputStream()) {
            stdin.write(Base64.getEncoder().encode(data));
        }
        byte[] out = readAll(p.getInputStream());
        byte[] err = readAll(p.getErrorStream());
        try {
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("DPAPI: PowerShell не ответил за 30 секунд");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("DPAPI: прервано", e);
        }
        if (p.exitValue() != 0) {
            // Текст ошибки PowerShell секретов не содержит: данные идут через stdin, не аргументами.
            String msg = new String(err, StandardCharsets.UTF_8).trim();
            throw new IOException("DPAPI: " + (msg.isEmpty() ? "код " + p.exitValue() : firstLine(msg)));
        }
        try {
            return Base64.getDecoder().decode(new String(out, StandardCharsets.US_ASCII).trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("DPAPI: неожиданный ответ PowerShell", e);
        } finally {
            Arrays.fill(out, (byte) 0);
        }
    }

    // ---------------------------------------------------------------- Linux

    /**
     * Замена DPAPI вне Windows (с 20.10.2026, Linux-сборка): главный ключ шифруется ключом из
     * файла {@code ~/.config/blauncher/secrets.key} с правами 0600.
     *
     * <p>Связки ключей (libsecret/KWallet) нет в WSL и на части рабочих столов, а без хранилища
     * панель VPS не работает вовсе, поэтому — файл. Он лежит НЕ рядом с vps_secrets.json, а в
     * домашнем каталоге: скопированная папка данных лаунчера без него не расшифровывается, как
     * и файл с DPAPI на другом ПК. От процессов того же пользователя файл не защищает — DPAPI
     * без энтропии не защищает тоже.
     */
    private static byte[] keyFileWrap(byte[] data, boolean protect) throws IOException {
        byte[] kek = keyFileKey();
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] aad = DPAPI_ENTROPY.getBytes(StandardCharsets.UTF_8);
            if (protect) {
                byte[] iv = new byte[GCM_IV_BYTES];
                new SecureRandom().nextBytes(iv);
                cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kek, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
                cipher.updateAAD(aad);
                byte[] ct = cipher.doFinal(data);
                byte[] packed = new byte[iv.length + ct.length];
                System.arraycopy(iv, 0, packed, 0, iv.length);
                System.arraycopy(ct, 0, packed, iv.length, ct.length);
                return packed;
            }
            if (data.length <= GCM_IV_BYTES) throw new IOException("Главный ключ хранилища секретов повреждён");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kek, "AES"), new GCMParameterSpec(GCM_TAG_BITS, data, 0, GCM_IV_BYTES));
            cipher.updateAAD(aad);
            return cipher.doFinal(data, GCM_IV_BYTES, data.length - GCM_IV_BYTES);
        } catch (GeneralSecurityException e) {
            throw new IOException("Главный ключ не расшифровывается: файл секретов создан с другим "
                    + keyFilePath() + " или под другим пользователем", e);
        } finally {
            Arrays.fill(kek, (byte) 0);
        }
    }

    private static Path keyFilePath() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = (xdg != null && !xdg.isBlank()) ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("blauncher").resolve("secrets.key");
    }

    private static byte[] keyFileKey() throws IOException {
        Path keyFile = keyFilePath();
        if (Files.exists(keyFile)) {
            byte[] key = Files.readAllBytes(keyFile);
            if (key.length != 32) throw new IOException("Файл " + keyFile + " повреждён (ожидалось 32 байта)");
            return key;
        }
        Files.createDirectories(keyFile.getParent());
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        // Файл создаётся сразу с 0600: сначала создать, потом сузить права — значит на мгновение
        // оставить ключ читаемым для всех.
        Path tmp = keyFile.resolveSibling("secrets.key.tmp");
        Files.deleteIfExists(tmp);
        Files.createFile(tmp, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
        Files.write(tmp, key);
        Files.move(tmp, keyFile, StandardCopyOption.ATOMIC_MOVE);
        return key;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        in.transferTo(buf);
        return buf.toByteArray();
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl).trim();
    }
}
