package com.example.launcher.service.vps;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;

/**
 * Одно SSH-подключение к VPS пользователя (JSch).
 *
 * <p>Ключ хоста проверяется всегда. Первое подключение к новому серверу идёт через
 * {@link #probeHostKey}: оно обрывается сразу после обмена ключами, ДО отправки пароля, — чтобы
 * человек сначала подтвердил отпечаток, а пароль ушёл только на подтверждённый сервер. Все
 * дальнейшие подключения закреплены за этим отпечатком; несовпадение — {@link VpsException.HostKeyChanged}.
 *
 * <p>Команды выполняются через {@link #exec}. Собирать их можно только из констант и
 * {@link Shell#quote} — см. javadoc {@link Shell}. Пароль sudo передаётся sudo через stdin
 * ({@code sudo -S}), а не в командной строке: её видят все процессы на VPS через ps.
 */
public final class VpsSession implements AutoCloseable {

    /** Адрес сервера и пользователь, под которым входим. */
    public record Target(String host, int port, String user) {
        public Target {
            if (host == null || host.isBlank()) throw new IllegalArgumentException("Не указан адрес сервера");
            if (port < 1 || port > 65535) throw new IllegalArgumentException("Порт SSH должен быть от 1 до 65535");
            if (user == null || user.isBlank()) throw new IllegalArgumentException("Не указан пользователь");
        }
    }

    /** Чем входим: паролем или приватным ключом (текст в формате OpenSSH/PEM). */
    public sealed interface Auth permits Password, PrivateKey {}
    public record Password(String password) implements Auth {
        @Override public String toString() { return "Password[***]"; }
    }
    public record PrivateKey(String pem, String passphrase) implements Auth {
        @Override public String toString() { return "PrivateKey[***]"; }
    }

    /** Ключ хоста: тип (ssh-ed25519 …) и отпечаток в виде OpenSSH ({@code SHA256:…}). */
    public record HostKeyInfo(String type, String fingerprint) {}

    /** Как на этом сервере получать права root. */
    public enum Privilege {
        /** Вошли под root. */
        ROOT,
        /** sudo без пароля (NOPASSWD). */
        SUDO_NOPASSWD,
        /** sudo с паролем — он хранится зашифрованным (SecretStore) и идёт в sudo через stdin. */
        SUDO_PASSWORD
    }

    /** Результат команды. stdout/stderr — как есть; в лог их писать с осторожностью. */
    public record ExecResult(int exitCode, String stdout, String stderr) {
        public boolean ok() { return exitCode == 0; }
    }

    private static final int CONNECT_TIMEOUT_MS = 15_000;

    private final Session session;
    private final Target target;
    private Privilege privilege = Privilege.ROOT;
    private String sudoPassword;

    private VpsSession(Session session, Target target) {
        this.session = session;
        this.target = target;
    }

    public Target target() { return target; }

    /** Задать, как повышать права (определяется при первом подключении — см. VpsConnector). */
    public void setPrivilege(Privilege privilege, String sudoPassword) {
        this.privilege = privilege;
        this.sudoPassword = privilege == Privilege.SUDO_PASSWORD ? sudoPassword : null;
    }

    // ---------------------------------------------------------------- подключение

    /**
     * Узнаёт ключ хоста, не отправляя никаких учётных данных: подключение обрывается сразу после
     * обмена ключами.
     */
    public static HostKeyInfo probeHostKey(String host, int port) throws VpsException {
        PinningRepository repo = new PinningRepository(null);
        Session s = null;
        try {
            JSch jsch = new JSch();
            s = jsch.getSession("blauncher-probe", host, port);
            s.setHostKeyRepository(repo);
            s.setConfig("StrictHostKeyChecking", "yes");
            s.connect(CONNECT_TIMEOUT_MS);
        } catch (JSchException e) {
            // Ожидаемый исход: «reject HostKey». Важно лишь, успели ли мы увидеть ключ.
            if (repo.seen == null) throw translate(e, host, port);
        } finally {
            if (s != null) s.disconnect();
        }
        if (repo.seen == null) throw new VpsException("Сервер " + host + ":" + port + " не прислал ключ хоста");
        return repo.seen;
    }

    /**
     * Подключается к серверу с закреплённым отпечатком {@code pinnedFingerprint}.
     *
     * @throws VpsException.HostKeyChanged если сервер предъявил другой ключ — пароль при этом
     *                                     не отправляется (проверка идёт до аутентификации)
     */
    public static VpsSession open(Target target, Auth auth, String pinnedFingerprint) throws VpsException {
        if (pinnedFingerprint == null || pinnedFingerprint.isBlank()) {
            throw new IllegalArgumentException("Подключение без подтверждённого отпечатка хоста запрещено");
        }
        PinningRepository repo = new PinningRepository(pinnedFingerprint);
        JSch jsch = new JSch();   // свой экземпляр: identity одного сервера не должны попадать в другой
        Session s;
        try {
            s = jsch.getSession(target.user(), target.host(), target.port());
            s.setHostKeyRepository(repo);
            s.setConfig("StrictHostKeyChecking", "yes");
            s.setServerAliveInterval(30_000);
            switch (auth) {
                case Password p -> {
                    // setPassword(byte[]), а не (String): строковый вариант в JSch 2.x помечен устаревшим.
                    s.setPassword(p.password().getBytes(StandardCharsets.UTF_8));
                    s.setUserInfo(new PasswordUserInfo(p.password()));
                    s.setConfig("PreferredAuthentications", "password,keyboard-interactive");
                }
                case PrivateKey k -> {
                    jsch.addIdentity("blauncher",
                            k.pem().getBytes(StandardCharsets.UTF_8), null,
                            k.passphrase() == null || k.passphrase().isEmpty() ? null
                                    : k.passphrase().getBytes(StandardCharsets.UTF_8));
                    s.setConfig("PreferredAuthentications", "publickey");
                }
            }
            s.connect(CONNECT_TIMEOUT_MS);
        } catch (JSchException e) {
            if (repo.mismatch != null) throw new VpsException.HostKeyChanged(pinnedFingerprint, repo.mismatch);
            throw translate(e, target.host(), target.port());
        }
        return new VpsSession(s, target);
    }

    // ---------------------------------------------------------------- команды

    /** Команда от имени пользователя, под которым вошли. */
    public ExecResult exec(String command, Duration timeout) throws VpsException {
        return run(command, null, timeout);
    }

    /** То же, но с данными на stdin команды. */
    public ExecResult exec(String command, byte[] stdin, Duration timeout) throws VpsException {
        return run(command, stdin, timeout);
    }

    /**
     * Команда с правами root. {@code command} передаётся в {@code sh -c} одним аргументом,
     * поэтому может содержать конвейеры — но, как и везде, только константы и {@link Shell#quote}.
     */
    public ExecResult execRoot(String command, Duration timeout) throws VpsException {
        return execRoot(command, null, timeout);
    }

    public ExecResult execRoot(String command, byte[] stdin, Duration timeout) throws VpsException {
        return run(asRoot(command), rootStdin(stdin), timeout);
    }

    /** Команда, обёрнутая для выполнения от root тем способом, который есть на этом сервере. */
    private String asRoot(String command) throws VpsException {
        return switch (privilege) {
            case ROOT -> command;
            case SUDO_NOPASSWD -> "sudo -n -- sh -c " + Shell.quote(command);
            // -p '' — без приглашения «[sudo] password for …» в stderr.
            // -k — не брать кэш sudo: с кэшем sudo пароль не спрашивает, и строка пароля
            // доставалась бы stdin самой команды (а с ним — например, в файл, который она пишет).
            case SUDO_PASSWORD -> {
                if (sudoPassword == null) throw new VpsException("Нужен пароль sudo, а он не сохранён");
                yield "sudo -k -S -p '' -- sh -c " + Shell.quote(command);
            }
        };
    }

    /** stdin для команды от root: при sudo с паролем первой строкой идёт пароль. */
    private byte[] rootStdin(byte[] stdin) {
        if (privilege != Privilege.SUDO_PASSWORD) return stdin;
        byte[] pw = (sudoPassword + "\n").getBytes(StandardCharsets.UTF_8);
        if (stdin == null) return pw;
        byte[] all = new byte[pw.length + stdin.length];
        System.arraycopy(pw, 0, all, 0, pw.length);
        System.arraycopy(stdin, 0, all, pw.length, stdin.length);
        return all;
    }

    /**
     * Команда от root, stdout которой пишется прямо в {@code sink}, а не в память: так скачиваются
     * файлы и бэкапы с VPS (бэкап мира — сотни мегабайт). {@code timeout} — на всю передачу.
     * Возвращает результат с пустым stdout.
     */
    public ExecResult execRootTo(String command, OutputStream sink, Duration timeout) throws VpsException {
        return run(asRoot(command), rootStdin(null), timeout, sink);
    }

    /** Открытый поток вывода долгой команды (консоль). Закрыть — {@link #close()}. */
    public final class Stream implements AutoCloseable {
        private final ChannelExec ch;
        private Stream(ChannelExec ch) { this.ch = ch; }
        public boolean isOpen() { return ch.isConnected() && !ch.isClosed(); }
        @Override public void close() { ch.disconnect(); }
    }

    /**
     * Запускает долгую команду от root (например {@code journalctl -f}) и отдаёт её вывод по строкам
     * в фоновом потоке. {@code onEnd} вызывается, когда команда завершилась или связь пропала.
     */
    public Stream streamRoot(String command, java.util.function.Consumer<String> onLine, Runnable onEnd) throws VpsException {
        try {
            ChannelExec ch = (ChannelExec) session.openChannel("exec");
            ch.setCommand(asRoot(command));
            ch.setErrStream(OutputStream.nullOutputStream());
            InputStream out = ch.getInputStream();
            OutputStream in = ch.getOutputStream();
            ch.connect(CONNECT_TIMEOUT_MS);
            byte[] stdin = rootStdin(null);
            if (stdin != null) in.write(stdin);
            in.close();
            Thread t = new Thread(() -> {
                try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(out, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) onLine.accept(line);
                } catch (IOException ignored) {
                    // поток закрыт (close() или обрыв связи) — onEnd ниже скажет об этом
                } finally {
                    ch.disconnect();
                    if (onEnd != null) onEnd.run();
                }
            }, "vps-stream");
            t.setDaemon(true);
            t.start();
            return new Stream(ch);
        } catch (JSchException | IOException e) {
            throw new VpsException("Не удалось открыть поток с сервера: " + e.getMessage(), e);
        }
    }

    /**
     * Туннель с ПК до порта на самом VPS (127.0.0.1 там): так лаунчер ходит в RCON, не открывая
     * его в интернет. Возвращает локальный порт на 127.0.0.1 этого ПК.
     */
    public int forwardLocal(int remotePort) throws VpsException {
        try {
            return session.setPortForwardingL("127.0.0.1", 0, "127.0.0.1", remotePort);
        } catch (JSchException e) {
            throw new VpsException("Не удалось открыть туннель к порту " + remotePort + " на VPS: " + e.getMessage(), e);
        }
    }

    public void removeForward(int localPort) {
        try {
            session.delPortForwardingL("127.0.0.1", localPort);
        } catch (JSchException ignored) {
            // туннеля уже нет — то, что и нужно
        }
    }

    private ExecResult run(String command, byte[] stdin, Duration timeout) throws VpsException {
        return run(command, stdin, timeout, null);
    }

    private ExecResult run(String command, byte[] stdin, Duration timeout, OutputStream sink) throws VpsException {
        ChannelExec ch = null;
        try {
            ch = (ChannelExec) session.openChannel("exec");
            ch.setCommand(command);
            ByteArrayOutputStream err = new ByteArrayOutputStream();
            ch.setErrStream(err, true);
            InputStream out = ch.getInputStream();
            OutputStream in = ch.getOutputStream();
            ch.connect(CONNECT_TIMEOUT_MS);
            if (stdin != null) in.write(stdin);
            in.close();

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            OutputStream target = sink != null ? sink : buf;
            // Блокирующее чтение до конца потока, а не опрос available(): на 07.10.2026 при
            // скачивании бэкапа в 227 МБ available() у пайпа JSch возвращал 0 при заполненном
            // буфере — поток сессии ждал места, мы ждали данных, и загрузка вставала на 0 байт.
            // Таймаут держит сторож: по сроку он закрывает канал, и read() выходит.
            ChannelExec channel = ch;
            java.util.concurrent.atomic.AtomicBoolean timedOut = new java.util.concurrent.atomic.AtomicBoolean();
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(timeout.toMillis());
                    timedOut.set(true);
                    channel.disconnect();
                } catch (InterruptedException ignored) {
                    // команда завершилась раньше срока
                }
            }, "vps-exec-timeout");
            watchdog.setDaemon(true);
            watchdog.start();
            try {
                byte[] chunk = new byte[65536];
                int n;
                while ((n = out.read(chunk)) >= 0) target.write(chunk, 0, n);
            } catch (IOException e) {
                if (!timedOut.get()) throw e;
            } finally {
                watchdog.interrupt();
            }
            if (timedOut.get()) throw new VpsException("Команда на сервере не завершилась за " + timeout.toSeconds() + " с");
            // Код выхода приходит отдельным сообщением рядом с концом потока — подождать его.
            for (int i = 0; i < 500 && !ch.isClosed(); i++) Thread.sleep(10);
            return new ExecResult(ch.getExitStatus(),
                    buf.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } catch (JSchException | IOException e) {
            if (e instanceof VpsException ve) throw ve;
            throw new VpsException("Связь с сервером прервалась: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VpsException("Операция прервана", e);
        } finally {
            if (ch != null) ch.disconnect();
        }
    }

    /** Открывает SFTP-канал. Закрыть — {@code disconnect()} у канала. */
    public ChannelSftp sftp() throws VpsException {
        try {
            ChannelSftp ch = (ChannelSftp) session.openChannel("sftp");
            ch.connect(CONNECT_TIMEOUT_MS);
            return ch;
        } catch (JSchException e) {
            throw new VpsException("SFTP на сервере недоступен: " + e.getMessage(), e);
        }
    }

    public boolean isConnected() { return session.isConnected(); }

    @Override
    public void close() {
        session.disconnect();
    }

    // ---------------------------------------------------------------- ключ хоста

    /** Отпечаток в формате OpenSSH: SHA256 от блоба ключа, base64 без '='. */
    public static String fingerprint(byte[] keyBlob) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(keyBlob);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Тип ключа — первая строка блоба (uint32 длина + имя). */
    private static String keyType(byte[] blob) {
        if (blob.length < 4) return "?";
        int len = ((blob[0] & 0xff) << 24) | ((blob[1] & 0xff) << 16) | ((blob[2] & 0xff) << 8) | (blob[3] & 0xff);
        if (len <= 0 || 4 + len > blob.length) return "?";
        return new String(blob, 4, len, StandardCharsets.US_ASCII);
    }

    /**
     * Хранилище ключей хоста на один отпечаток. Ничего не пишет на диск: закреплённый отпечаток
     * лежит в ServerEntry, а не в known_hosts, чтобы у каждого сервера в лаунчере был свой.
     */
    private static final class PinningRepository implements HostKeyRepository {
        private final String expected;
        HostKeyInfo seen;
        HostKeyInfo mismatch;

        PinningRepository(String expected) { this.expected = expected; }

        @Override
        public int check(String host, byte[] key) {
            HostKeyInfo info = new HostKeyInfo(keyType(key), fingerprint(key));
            seen = info;
            if (expected == null) return NOT_INCLUDED;          // режим «только посмотреть»
            if (expected.equals(info.fingerprint())) return OK;
            mismatch = info;
            return CHANGED;
        }

        @Override public void add(HostKey hostkey, UserInfo ui) { /* закрепление — забота вызывающего */ }
        @Override public void remove(String host, String type) {}
        @Override public void remove(String host, String type, byte[] key) {}
        @Override public String getKnownHostsRepositoryID() { return "blauncher-pinned"; }
        @Override public HostKey[] getHostKey() { return new HostKey[0]; }
        @Override public HostKey[] getHostKey(String host, String type) { return new HostKey[0]; }
    }

    /** Для серверов, где вход по паролю идёт через keyboard-interactive, а не password. */
    private static final class PasswordUserInfo implements UserInfo, UIKeyboardInteractive {
        private final String password;
        PasswordUserInfo(String password) { this.password = password; }
        @Override public String getPassphrase() { return null; }
        @Override public String getPassword() { return password; }
        @Override public boolean promptPassword(String message) { return true; }
        @Override public boolean promptPassphrase(String message) { return false; }
        @Override public boolean promptYesNo(String message) { return false; }
        @Override public void showMessage(String message) {}
        @Override
        public String[] promptKeyboardInteractive(String destination, String name, String instruction,
                                                  String[] prompt, boolean[] echo) {
            // Отвечаем паролем только на единственный скрытый вопрос — на что-то другое (2FA и т.п.)
            // подставлять пароль нельзя.
            if (prompt.length == 1 && !echo[0]) return new String[]{password};
            return null;
        }
    }

    /** Переводит ошибки JSch и сети в понятный текст. */
    private static VpsException translate(JSchException e, String host, int port) {
        Throwable c = e.getCause();
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (c instanceof UnknownHostException) return new VpsException("Адрес " + host + " не найден — проверьте IP", e);
        if (c instanceof ConnectException) return new VpsException("Сервер " + host + ":" + port + " не принимает подключения — проверьте IP и порт SSH", e);
        if (c instanceof NoRouteToHostException) return new VpsException("Нет маршрута до " + host + " — сервер недоступен", e);
        if (c instanceof SocketTimeoutException || m.contains("timeout")) return new VpsException("Сервер " + host + ":" + port + " не ответил вовремя", e);
        if (m.startsWith("Auth fail") || m.startsWith("Auth cancel")) return new VpsException("Сервер не принял логин или пароль (ключ)", e);
        if (m.contains("invalid privatekey")) return new VpsException("Файл ключа не распознан — нужен приватный ключ OpenSSH или PEM", e);
        return new VpsException("Не удалось подключиться к " + host + ":" + port + ": " + m, e);
    }
}
