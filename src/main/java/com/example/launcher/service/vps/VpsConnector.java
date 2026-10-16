package com.example.launcher.service.vps;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;
import com.jcraft.jsch.SftpException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Первое подключение к VPS (этап 1 панели VPS): проверка ОС, выяснение прав и перевод на ключ.
 *
 * <p>Порядок важен: сначала читаем систему и права и только потом что-то меняем на сервере.
 * На неподдерживаемой ОС или без прав root/sudo мы уходим, ничего не тронув.
 *
 * <p>Ключ всегда генерируется свой — отдельный ed25519 на каждый сервер в лаунчере, — даже если
 * человек вошёл своим ключом: тогда лаунчеру не нужно хранить чужой ключ (часто он открывает и
 * другие серверы), а удаление сервера из лаунчера убирает ровно наш ключ из authorized_keys.
 */
public final class VpsConnector {

    public enum Step {
        CONNECT("Подключение"),
        SYSTEM("Проверка системы"),
        PRIVILEGE("Права администратора"),
        KEY("Вход по ключу");

        public final String label;
        Step(String label) { this.label = label; }
    }

    public enum State { RUNNING, DONE, FAILED }

    public interface Progress {
        void update(Step step, State state, String detail);
    }

    /**
     * @param sudoPassword пароль sudo, если вход по ключу и sudo его требует; при входе по паролю
     *                     берётся пароль входа. Может быть null.
     * @param keyComment   комментарий к нашему ключу в authorized_keys — по нему человек на VPS
     *                     поймёт, чей это ключ
     */
    public record Request(VpsSession.Target target, VpsSession.Auth auth, String sudoPassword,
                          String pinnedFingerprint, String keyComment) {}

    /** Что сохранить: приватный ключ и пароль sudo — в SecretStore, остальное — в ServerEntry. */
    public record Result(String privateKeyPem, String publicKeyLine, VpsSession.Privilege privilege,
                         String sudoPasswordToStore, OsInfo os) {
        @Override public String toString() { return "Result[privilege=" + privilege + ", os=" + os + "]"; }
    }

    /** Данные /etc/os-release. */
    public record OsInfo(String id, String versionId, String prettyName) {
        /** Ubuntu 22.04+ и Debian 12+ — то, на чём панель проверена и что умеет ставить. */
        public boolean supported() {
            int[] v = parseVersion(versionId);
            return switch (id) {
                case "ubuntu" -> v[0] > 22 || (v[0] == 22 && v[1] >= 4);
                case "debian" -> v[0] >= 12;
                default -> false;
            };
        }
    }

    private static final Duration SHORT = Duration.ofSeconds(20);

    public Result connect(Request req, Progress progress) throws VpsException {
        progress.update(Step.CONNECT, State.RUNNING, req.target().user() + "@" + req.target().host() + ":" + req.target().port());
        try (VpsSession session = VpsSession.open(req.target(), req.auth(), req.pinnedFingerprint())) {
            progress.update(Step.CONNECT, State.DONE, null);

            // ---- система
            progress.update(Step.SYSTEM, State.RUNNING, null);
            OsInfo os = readOs(session);
            if (!os.supported()) {
                String msg = "На сервере " + os.prettyName() + ". Панель поддерживает только Ubuntu 22.04 и новее "
                        + "или Debian 12 и новее. Переустановите систему на VPS через панель хостера и подключитесь снова.";
                progress.update(Step.SYSTEM, State.FAILED, msg);
                throw new VpsException(msg);
            }
            progress.update(Step.SYSTEM, State.DONE, os.prettyName());

            // ---- права
            progress.update(Step.PRIVILEGE, State.RUNNING, null);
            String sudoPw = req.sudoPassword();
            if ((sudoPw == null || sudoPw.isEmpty()) && req.auth() instanceof VpsSession.Password p) {
                sudoPw = p.password();
            }
            VpsSession.Privilege privilege = detectPrivilege(session, sudoPw);
            session.setPrivilege(privilege, sudoPw);
            progress.update(Step.PRIVILEGE, State.DONE, switch (privilege) {
                case ROOT -> "root";
                case SUDO_NOPASSWD -> "sudo без пароля";
                case SUDO_PASSWORD -> "sudo с паролем (пароль сохраняется зашифрованным на этом ПК)";
            });

            // ---- ключ
            progress.update(Step.KEY, State.RUNNING, "создаю ключ ed25519 для этого сервера");
            String[] pair = generateKey(req.keyComment());
            String privatePem = pair[0];
            String publicLine = pair[1];
            installAuthorizedKey(session, publicLine);
            progress.update(Step.KEY, State.RUNNING, "проверяю вход по новому ключу");
            try (VpsSession byKey = VpsSession.open(req.target(), new VpsSession.PrivateKey(privatePem, null),
                    req.pinnedFingerprint())) {
                if (!byKey.exec("true", SHORT).ok()) throw new VpsException("Вход по новому ключу не удался");
            } catch (VpsException e) {
                progress.update(Step.KEY, State.FAILED,
                        "Ключ добавлен в authorized_keys, но сервер по нему не пускает. Проверьте, что в "
                                + "/etc/ssh/sshd_config не выключен PubkeyAuthentication.");
                throw e;
            }
            progress.update(Step.KEY, State.DONE, "дальше лаунчер входит только по ключу");

            return new Result(privatePem, publicLine, privilege,
                    privilege == VpsSession.Privilege.SUDO_PASSWORD ? sudoPw : null, os);
        } catch (VpsException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new VpsException("Непредвиденная ошибка: " + e.getMessage(), e);
        }
    }

    // ---------------------------------------------------------------- шаги

    static OsInfo readOs(VpsSession session) throws VpsException {
        VpsSession.ExecResult r = session.exec("cat /etc/os-release", SHORT);
        if (!r.ok()) throw new VpsException("Не удалось определить систему: нет /etc/os-release");
        Map<String, String> kv = parseOsRelease(r.stdout());
        String id = kv.getOrDefault("ID", "").toLowerCase();
        String ver = kv.getOrDefault("VERSION_ID", "");
        String pretty = kv.getOrDefault("PRETTY_NAME", id + " " + ver).trim();
        return new OsInfo(id, ver, pretty);
    }

    static Map<String, String> parseOsRelease(String text) {
        Map<String, String> kv = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String v = line.substring(eq + 1).trim();
            if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
                v = v.substring(1, v.length() - 1);
            }
            kv.put(line.substring(0, eq).trim(), v);
        }
        return kv;
    }

    private static int[] parseVersion(String v) {
        int[] out = {0, 0};
        if (v == null) return out;
        String[] parts = v.split("\\.");
        for (int i = 0; i < Math.min(2, parts.length); i++) {
            try { out[i] = Integer.parseInt(parts[i].replaceAll("\\D.*$", "")); } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    static VpsSession.Privilege detectPrivilege(VpsSession session, String sudoPassword) throws VpsException {
        VpsSession.ExecResult id = session.exec("id -u", SHORT);
        if (id.ok() && id.stdout().trim().equals("0")) return VpsSession.Privilege.ROOT;

        if (!session.exec("command -v sudo", SHORT).ok()) {
            throw new VpsException("Пользователь не root, а sudo на сервере не установлен. Войдите под root.");
        }
        if (session.exec("sudo -n true", SHORT).ok()) return VpsSession.Privilege.SUDO_NOPASSWD;

        if (sudoPassword == null || sudoPassword.isEmpty()) {
            throw new VpsException("sudo на сервере требует пароль. Укажите пароль sudo в форме подключения.");
        }
        // -k — не пользоваться кэшем sudo от прошлых вызовов: проверяем именно этот пароль.
        VpsSession.ExecResult r = session.exec("sudo -k -S -p '' true",
                (sudoPassword + "\n").getBytes(StandardCharsets.UTF_8), SHORT);
        if (r.ok()) return VpsSession.Privilege.SUDO_PASSWORD;
        String err = r.stderr().toLowerCase();
        if (err.contains("not in the sudoers") || err.contains("may not run sudo")) {
            throw new VpsException("У пользователя нет прав sudo. Войдите под root или выдайте права: usermod -aG sudo <пользователь>");
        }
        throw new VpsException("sudo не принял пароль");
    }

    /** @return {приватный ключ OpenSSH, строка для authorized_keys} */
    static String[] generateKey(String comment) throws VpsException {
        try {
            KeyPair kp = KeyPair.genKeyPair(new JSch(), KeyPair.ED25519);
            ByteArrayOutputStream prv = new ByteArrayOutputStream();
            ByteArrayOutputStream pub = new ByteArrayOutputStream();
            // Формат OpenSSH v1, а не writePrivateKey: тот пишет старый PEM, в котором ed25519 нет
            // (UnsupportedOperationException). Без пароля — ключ и так лежит под DPAPI в SecretStore.
            kp.writeOpenSSHv1PrivateKey(prv, null);
            kp.writePublicKey(pub, comment);
            kp.dispose();
            return new String[]{prv.toString(StandardCharsets.UTF_8), pub.toString(StandardCharsets.UTF_8).trim()};
        } catch (JSchException e) {
            throw new VpsException("Не удалось создать SSH-ключ: " + e.getMessage(), e);
        }
    }

    private static final String SSH_DIR = ".ssh";
    private static final String AUTH_KEYS = ".ssh/authorized_keys";
    private static final String AUTH_KEYS_TMP = ".ssh/authorized_keys.blauncher-tmp";

    /**
     * Добавляет строку ключа в ~/.ssh/authorized_keys того, под кем вошли.
     *
     * <p>Через SFTP, а не {@code echo … >>}: содержимое ключа вообще не проходит через shell. Файл
     * пишется целиком во временный и заменяется одним {@code mv} — обрыв связи посреди записи не
     * оставит человека с обрезанным authorized_keys (а с ним — и без входа по своим ключам).
     */
    static void installAuthorizedKey(VpsSession session, String publicLine) throws VpsException {
        rewriteAuthorizedKeys(session, lines -> {
            String blob = keyBlob(publicLine);
            for (String l : lines) if (blob != null && blob.equals(keyBlob(l))) return lines;   // уже есть
            List<String> out = new ArrayList<>(lines);
            out.add(publicLine);
            return out;
        });
    }

    /** Убирает наш ключ (по самому ключу, не по комментарию) из authorized_keys. */
    public static void removeAuthorizedKey(VpsSession session, String publicLine) throws VpsException {
        String blob = keyBlob(publicLine);
        if (blob == null) return;
        rewriteAuthorizedKeys(session, lines -> {
            List<String> out = new ArrayList<>();
            for (String l : lines) if (!blob.equals(keyBlob(l))) out.add(l);
            return out;
        });
    }

    private interface LinesEdit { List<String> apply(List<String> lines); }

    private static void rewriteAuthorizedKeys(VpsSession session, LinesEdit edit) throws VpsException {
        ChannelSftp sftp = session.sftp();
        try {
            boolean hadDir = exists(sftp, SSH_DIR);
            if (!hadDir) {
                sftp.mkdir(SSH_DIR);
                sftp.chmod(0700, SSH_DIR);
            }
            List<String> lines = new ArrayList<>();
            if (exists(sftp, AUTH_KEYS)) {
                ByteArrayOutputStream cur = new ByteArrayOutputStream();
                sftp.get(AUTH_KEYS, cur);
                for (String l : cur.toString(StandardCharsets.UTF_8).split("\r?\n")) {
                    if (!l.isEmpty()) lines.add(l);
                }
            }
            List<String> updated = edit.apply(lines);
            if (updated == lines) return;   // менять нечего

            StringBuilder sb = new StringBuilder();
            for (String l : updated) sb.append(l).append('\n');
            sftp.put(new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)),
                    AUTH_KEYS_TMP, ChannelSftp.OVERWRITE);
            sftp.chmod(0600, AUTH_KEYS_TMP);
        } catch (SftpException e) {
            throw new VpsException("Не удалось записать ~/.ssh/authorized_keys: " + e.getMessage(), e);
        } finally {
            sftp.disconnect();
        }
        // Пути — константы, относительно домашней папки (рабочий каталог exec — тоже она).
        VpsSession.ExecResult mv = session.exec("mv -f " + Shell.quote(AUTH_KEYS_TMP) + " " + Shell.quote(AUTH_KEYS), SHORT);
        if (!mv.ok()) throw new VpsException("Не удалось обновить ~/.ssh/authorized_keys: " + mv.stderr().trim());
    }

    private static boolean exists(ChannelSftp sftp, String path) throws SftpException {
        try {
            sftp.lstat(path);
            return true;
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return false;
            throw e;
        }
    }

    /** Сам ключ (base64) из строки authorized_keys, без опций и комментария. */
    static String keyBlob(String line) {
        if (line == null) return null;
        String t = line.trim();
        if (t.isEmpty() || t.startsWith("#")) return null;
        String[] parts = t.split("\\s+");
        for (int i = 0; i + 1 < parts.length; i++) {
            if (parts[i].startsWith("ssh-") || parts[i].startsWith("ecdsa-") || parts[i].startsWith("sk-")) {
                return parts[i + 1];
            }
        }
        return null;
    }

    /** Подпись ключа для authorized_keys: только безопасные символы. */
    public static String keyComment(String serverId) {
        String pc = System.getenv("COMPUTERNAME");
        if (pc == null || pc.isBlank()) pc = "pc";
        pc = pc.replaceAll("[^A-Za-z0-9_.-]", "");
        String id = serverId == null ? "" : serverId.replaceAll("[^A-Za-z0-9]", "");
        return "blauncher-" + (id.length() > 8 ? id.substring(0, 8) : id) + "@" + pc;
    }
}
