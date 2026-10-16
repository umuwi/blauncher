package com.example.launcher.service.vps;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Управление установленным Minecraft-сервером на VPS (этап 3 панели VPS): статус и нагрузка,
 * старт/стоп, консоль, игроки, файлы, бэкапы. Без JavaFX — UI в VpsManageController.
 *
 * <p>Файлы сервера и бэкапы трогаются от имени пользователя {@code minecraft} ({@code runuser}), а
 * не от root. Папкой сервера владеет minecraft, а значит, и любой плагин: подложи он символьную
 * ссылку {@code plugins -> /etc}, загрузка файла от root записала бы его в /etc. От minecraft такая
 * запись просто не пройдёт, а чтение через ссылку не достанет чужих файлов. От root здесь только
 * то, без чего нельзя: systemctl, журнал и папка бэкапов (её minecraft не видит, чтобы сервер не
 * мог испортить собственные бэкапы).
 */
public final class VpsServerManager {

    private static final String MC_USER = "minecraft";
    private static final String AS_MC = "runuser -u " + MC_USER + " -- ";
    private static final String BACKUP_ROOT = "/opt/blauncher/backups";
    private static final String STAGING = ".blauncher-staging";

    private static final Duration SHORT = Duration.ofSeconds(30);
    private static final Duration STOP = Duration.ofSeconds(180);
    private static final Duration LONG = Duration.ofHours(2);

    private static final Pattern BACKUP_NAME = Pattern.compile("^\\d{8}-\\d{6}\\.tar\\.gz$");

    private final SecureRandom random = new SecureRandom();

    /** Сервер на VPS: всё, что нужно для команд, — из ServerEntry. Значения проверяются. */
    public record Srv(String id, String dir, String service, int rconPort) {
        public Srv {
            if (!VpsInstaller.serverDir(id).equals(dir)) throw new IllegalArgumentException("Папка сервера не своя: " + dir);
            if (!VpsInstaller.serviceName(id).equals(service)) throw new IllegalArgumentException("Служба не своя: " + service);
            if (rconPort < 1 || rconPort > 65535) throw new IllegalArgumentException("Порт RCON: " + rconPort);
        }

        String backupDir() { return BACKUP_ROOT + "/" + id; }
    }

    // ================================================================ статус и нагрузка

    /**
     * Снимок состояния. Память и процессор — счётчики cgroup службы (всё дерево процессов: у
     * Forge java запускается из run.sh и не является MainPID). -1 — systemd их не ведёт.
     */
    public record Status(String activeState, String subState, int restarts, long memoryBytes, long cpuNs,
                         long clockNs, int cpus, long memTotalKb, long memAvailKb,
                         long diskTotalKb, long diskAvailKb, String load) {
        public boolean running() { return "active".equals(activeState); }

        /** Запускается, останавливается или ждёт перезапуска после падения. */
        public boolean transitioning() {
            return "activating".equals(activeState) || "deactivating".equals(activeState) || "reloading".equals(activeState);
        }

        public String label() {
            return switch (activeState) {
                case "active" -> "Работает";
                case "activating" -> "auto-restart".equals(subState) ? "Упал, перезапускается…" : "Запускается…";
                case "deactivating" -> "Останавливается…";
                case "failed" -> "Упал";
                default -> "Остановлен";
            };
        }
    }

    /** Загрузка процессора между двумя снимками, % от всех ядер VPS; -1 — посчитать нельзя. */
    public static double cpuPercent(Status prev, Status cur) {
        if (prev == null || cur == null || prev.cpuNs < 0 || cur.cpuNs < 0) return -1;
        long dt = cur.clockNs - prev.clockNs;
        long dc = cur.cpuNs - prev.cpuNs;
        if (dt <= 0 || dc < 0 || cur.cpus <= 0) return -1;
        return Math.min(100.0, 100.0 * dc / dt / cur.cpus);
    }

    public Status status(VpsSession s, Srv srv) throws VpsException {
        String q = Shell.quote(srv.service());
        String cmd = "systemctl show -p ActiveState -p SubState -p NRestarts -p MemoryCurrent -p CPUUsageNSec " + q + "; "
                + "echo \"clock=$(date +%s%N)\"; echo \"cpus=$(nproc)\"; "
                + "awk '/^MemTotal:/ {print \"memtotal=\" $2} /^MemAvailable:/ {print \"memavail=\" $2}' /proc/meminfo; "
                + "df -Pk /opt | awk 'NR==2 {print \"disktotal=\" $2; print \"diskavail=\" $4}'; "
                + "echo \"load=$(cut -d' ' -f1-3 /proc/loadavg)\"";
        VpsSession.ExecResult r = s.exec(cmd, SHORT);
        Map<String, String> kv = new HashMap<>();
        for (String line : r.stdout().split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) kv.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        if (!kv.containsKey("ActiveState")) throw new VpsException("Не удалось прочитать состояние службы" + errTail(r));
        return new Status(kv.get("ActiveState"), kv.getOrDefault("SubState", ""), (int) num(kv.get("NRestarts")),
                counter(kv.get("MemoryCurrent")), counter(kv.get("CPUUsageNSec")), num(kv.get("clock")),
                (int) num(kv.get("cpus")), num(kv.get("memtotal")), num(kv.get("memavail")),
                num(kv.get("disktotal")), num(kv.get("diskavail")), kv.getOrDefault("load", ""));
    }

    /** Размер папки сервера, КБ. du по большому миру идёт секунды — не звать каждые 3 с. */
    public long serverDirSizeKb(VpsSession s, Srv srv) throws VpsException {
        VpsSession.ExecResult r = s.execRoot("du -sk " + Shell.quote(srv.dir()) + " | cut -f1", Duration.ofMinutes(2));
        return num(r.stdout().trim());
    }

    // ================================================================ старт / стоп

    public void start(VpsSession s, Srv srv) throws VpsException {
        rootOk(s, "systemctl reset-failed " + Shell.quote(srv.service()) + " 2>/dev/null; systemctl start "
                + Shell.quote(srv.service()), SHORT, "Служба не запустилась");
    }

    /** Ждёт, пока сервер сохранит миры и выйдет (до TimeoutStopSec=120 в юните). */
    public void stop(VpsSession s, Srv srv) throws VpsException {
        rootOk(s, "systemctl stop " + Shell.quote(srv.service()), STOP, "Сервер не остановился");
    }

    public void restart(VpsSession s, Srv srv) throws VpsException {
        rootOk(s, "systemctl reset-failed " + Shell.quote(srv.service()) + " 2>/dev/null; systemctl restart "
                + Shell.quote(srv.service()), STOP, "Сервер не перезапустился");
    }

    // ================================================================ консоль

    /**
     * Вывод сервера в реальном времени: journalctl -f по службе, сначала последние {@code backlog}
     * строк. Команды идут не сюда, а через RCON ({@link #openRcon}).
     */
    public VpsSession.Stream console(VpsSession s, Srv srv, int backlog, Consumer<String> onLine, Runnable onEnd)
            throws VpsException {
        String cmd = "journalctl -u " + Shell.quote(srv.service()) + " -f -o cat --no-pager -n " + Math.max(0, Math.min(backlog, 2000));
        return s.streamRoot(cmd, line -> onLine.accept(VpsInstaller.stripAnsi(line)), onEnd);
    }

    /** RCON через SSH-туннель. Закрытие убирает и туннель. */
    public static final class RconLink implements AutoCloseable {
        private final VpsSession session;
        private final int localPort;
        public final Rcon rcon;

        private RconLink(VpsSession session, int localPort, Rcon rcon) {
            this.session = session;
            this.localPort = localPort;
            this.rcon = rcon;
        }

        public boolean isOpen() { return rcon.isOpen() && session.isConnected(); }

        @Override public void close() {
            rcon.close();
            session.removeForward(localPort);
        }
    }

    public RconLink openRcon(VpsSession s, Srv srv, String password) throws VpsException {
        if (password == null) throw new VpsException("Пароль RCON этого сервера не найден на этом ПК");
        int local = s.forwardLocal(srv.rconPort());
        try {
            return new RconLink(s, local, Rcon.connect(local, password));
        } catch (VpsException e) {
            s.removeForward(local);
            throw e;
        }
    }

    // ================================================================ игроки

    public record Online(int count, int max, List<String> names) {}

    private static final Pattern LIST = Pattern.compile("(\\d+)\\s*(?:of a max of|/)\\s*(\\d+)[^:]*:(.*)", Pattern.DOTALL);

    /** Разбор ответа на «list»: «There are 1 of a max of 20 players online: Steve» (и вариант 1/20). */
    public static Online parseList(String response) {
        Matcher m = LIST.matcher(response == null ? "" : response);
        if (!m.find()) return new Online(0, 0, List.of());
        List<String> names = new ArrayList<>();
        for (String n : m.group(3).split(",")) {
            String t = n.trim();
            if (!t.isEmpty()) names.add(t);
        }
        return new Online(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), names);
    }

    public Online online(Rcon rcon) throws VpsException {
        return parseList(rcon.command("list"));
    }

    /** Списки из файлов сервера — читаются и у выключенного сервера. */
    public record PlayerLists(List<String> ops, List<String> whitelist, List<String> banned, boolean whitelistEnabled) {}

    public PlayerLists playerLists(VpsSession s, Srv srv) throws VpsException {
        String cmd = "cd " + Shell.quote(srv.dir()) + " && for f in ops.json whitelist.json banned-players.json; do "
                + "echo \"=== $f\"; " + AS_MC + "cat \"$f\" 2>/dev/null; echo; done; echo '=== props'; "
                + AS_MC + "grep -E '^white-list=' server.properties 2>/dev/null";
        VpsSession.ExecResult r = s.execRoot(cmd, SHORT);
        Map<String, StringBuilder> parts = new HashMap<>();
        StringBuilder cur = null;
        for (String line : r.stdout().split("\n")) {
            if (line.startsWith("=== ")) {
                cur = new StringBuilder();
                parts.put(line.substring(4).trim(), cur);
            } else if (cur != null) {
                cur.append(line).append('\n');
            }
        }
        boolean wl = parts.containsKey("props") && parts.get("props").toString().contains("white-list=true");
        return new PlayerLists(names(parts.get("ops.json")), names(parts.get("whitelist.json")),
                names(parts.get("banned-players.json")), wl);
    }

    private static List<String> names(StringBuilder json) {
        List<String> out = new ArrayList<>();
        if (json == null || json.toString().isBlank()) return out;
        try {
            JsonElement root = JsonParser.parseString(json.toString());
            if (!root.isJsonArray()) return out;
            for (JsonElement el : (JsonArray) root) {
                if (el.isJsonObject() && el.getAsJsonObject().has("name")) out.add(el.getAsJsonObject().get("name").getAsString());
            }
        } catch (RuntimeException ignored) {
            // файл правят руками — битый просто покажем пустым
        }
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** Действия с игроком — только из этого списка; ник проверяется, так что лишних аргументов не будет. */
    public enum PlayerAction {
        OP("op %s"), DEOP("deop %s"), WHITELIST_ADD("whitelist add %s"), WHITELIST_REMOVE("whitelist remove %s"),
        BAN("ban %s"), PARDON("pardon %s"), KICK("kick %s");

        private final String template;
        PlayerAction(String template) { this.template = template; }
    }

    public String playerAction(Rcon rcon, PlayerAction action, String player) throws VpsException {
        return rcon.command(String.format(action.template, Shell.requirePlayerName(player)));
    }

    public String setWhitelist(Rcon rcon, boolean on) throws VpsException {
        return rcon.command(on ? "whitelist on" : "whitelist off");
    }

    // ================================================================ файлы

    public record FileItem(String name, boolean dir, long size, long mtimeSec) {}

    /** Путь внутри папки сервера; "" — сама папка. */
    static String resolve(Srv srv, String rel) {
        String n = normalize(rel);
        return n.isEmpty() ? srv.dir() : srv.dir() + "/" + n;
    }

    /** «a/./b/» → «a/b»; запрещены «..», абсолютные пути и управляющие символы. */
    static String normalize(String rel) {
        if (rel == null || rel.isEmpty()) return "";
        String p = Shell.requireRelativePath(rel);
        StringBuilder sb = new StringBuilder();
        for (String part : p.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (sb.length() > 0) sb.append('/');
            sb.append(part);
        }
        return sb.toString();
    }

    private static String requireName(String name) {
        if (name == null || name.isEmpty() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) {
            throw new IllegalArgumentException("Недопустимое имя файла: " + name);
        }
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) < 0x20 || name.charAt(i) == 0x7f) throw new IllegalArgumentException("Управляющий символ в имени файла");
        }
        return name;
    }

    public List<FileItem> listFiles(VpsSession s, Srv srv, String rel) throws VpsException {
        String path = resolve(srv, rel);
        // \0 между записями: в имени файла может оказаться что угодно, кроме него и «/».
        // И cd, и find — от minecraft: cd от root прошёл бы по ссылке туда, куда minecraft не пускают.
        VpsSession.ExecResult r = s.execRoot(AS_MC + "sh -c 'cd \"$1\" && find . -mindepth 1 -maxdepth 1 "
                + "-printf \"%y\\t%s\\t%T@\\t%f\\0\"' sh " + Shell.quote(path), SHORT);
        if (!r.ok()) throw new VpsException("Не удалось открыть папку" + errTail(r));
        List<FileItem> items = new ArrayList<>();
        for (String rec : r.stdout().split("\0")) {
            String[] f = rec.split("\t", 4);
            if (f.length < 4 || f[3].isEmpty()) continue;
            items.add(new FileItem(f[3], "d".equals(f[0]), num(f[1]), (long) Double.parseDouble(f[2].isEmpty() ? "0" : f[2])));
        }
        items.sort(Comparator.comparing((FileItem i) -> !i.dir()).thenComparing(i -> i.name().toLowerCase()));
        return items;
    }

    /** Скачивает файл на ПК (во временный рядом и переименованием — оборванная загрузка не выдаёт себя за файл). */
    public void download(VpsSession s, Srv srv, String rel, Path local, LongConsumer progress) throws VpsException, IOException {
        String path = resolve(srv, rel);
        if (normalize(rel).isEmpty()) throw new IllegalArgumentException("Выберите файл");
        Path tmp = local.resolveSibling(local.getFileName() + ".part");
        try (OutputStream out = new CountingOutput(Files.newOutputStream(tmp), progress)) {
            VpsSession.ExecResult r = s.execRootTo(AS_MC + "cat -- " + Shell.quote(path), out, LONG);
            if (!r.ok()) throw new VpsException("Не удалось скачать файл" + errTail(r));
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, local, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Загружает файл с ПК в папку {@code relDir}; существующий файл с тем же именем заменяется. */
    public void upload(VpsSession s, Srv srv, String relDir, Path local, LongConsumer progress) throws VpsException, IOException {
        String name = requireName(local.getFileName().toString());
        String dest = resolve(srv, relDir) + "/" + name;
        String staged = stage(s, srv, local, progress);
        try {
            // root читает файл из staging, пишет его minecraft — через ссылки в папке сервера не пробиться.
            rootOk(s, "cat " + Shell.quote(staged) + " | " + AS_MC + "sh -c 'cat > \"$1\"' sh " + Shell.quote(dest),
                    LONG, "Не удалось положить файл на сервер");
        } finally {
            s.exec("rm -f " + Shell.quote(staged), SHORT);
        }
    }

    public void mkdir(VpsSession s, Srv srv, String relDir, String name) throws VpsException {
        String dest = resolve(srv, relDir) + "/" + requireName(name);
        rootOk(s, AS_MC + "mkdir -p -- " + Shell.quote(dest), SHORT, "Не удалось создать папку");
    }

    /** Удаляет файл или папку целиком. Саму папку сервера удалить нельзя. */
    public void delete(VpsSession s, Srv srv, String rel) throws VpsException {
        if (normalize(rel).isEmpty()) throw new IllegalArgumentException("Нельзя удалить папку сервера целиком");
        rootOk(s, AS_MC + "rm -rf -- " + Shell.quote(resolve(srv, rel)), Duration.ofMinutes(10), "Не удалось удалить");
    }

    // ================================================================ бэкапы

    public record Backup(String name, long size, long mtimeSec) {}

    public List<Backup> listBackups(VpsSession s, Srv srv) throws VpsException {
        String bk = Shell.quote(srv.backupDir());
        VpsSession.ExecResult r = s.execRoot("test -d " + bk + " && find " + bk
                + " -maxdepth 1 -type f -name '*.tar.gz' -printf '%f\\t%s\\t%T@\\n'", SHORT);
        List<Backup> out = new ArrayList<>();
        for (String line : r.stdout().split("\n")) {
            String[] f = line.split("\t");
            if (f.length < 3 || !BACKUP_NAME.matcher(f[0]).matches()) continue;
            out.add(new Backup(f[0], num(f[1]), (long) Double.parseDouble(f[2])));
        }
        out.sort(Comparator.comparing(Backup::name).reversed());
        return out;
    }

    /**
     * Бэкап всей папки сервера (без logs и crash-reports). Если сервер работает и передан RCON,
     * автосохранение на время архивации выключается (save-off / save-all flush / save-on) — иначе
     * в архив попадают полузаписанные регионы мира.
     */
    public Backup createBackup(VpsSession s, Srv srv, Rcon rconOrNull, Consumer<String> progress) throws VpsException {
        long sizeKb = serverDirSizeKb(s, srv);
        long freeKb = num(s.exec("df -Pk /opt | awk 'NR==2 {print $4}'", SHORT).stdout().trim());
        if (freeKb > 0 && freeKb < sizeKb + 256 * 1024) {
            throw new VpsException("На диске VPS свободно " + freeKb / 1024 + " МБ, а сервер занимает " + sizeKb / 1024
                    + " МБ — для бэкапа места не хватит. Удалите старые бэкапы.");
        }
        String name = s.exec("date +%Y%m%d-%H%M%S", SHORT).stdout().trim() + ".tar.gz";
        if (!BACKUP_NAME.matcher(name).matches()) throw new VpsException("Не удалось прочитать время на VPS");
        String bk = srv.backupDir();
        boolean savingOff = false;
        try {
            if (rconOrNull != null) {
                progress.accept("сохраняю мир и приостанавливаю автосохранение");
                rconOrNull.command("save-off");
                savingOff = true;
                rconOrNull.command("save-all flush");
            }
            progress.accept("архивирую " + sizeKb / 1024 + " МБ");
            // tar читает от minecraft, архив пишет root в папку, которую minecraft не видит.
            // Код 1 у GNU tar — «файл менялся во время чтения» (например, логи плагинов) — не ошибка.
            String part = bk + "/" + name + ".part";
            String cmd = "install -d -m 700 " + Shell.quote(BACKUP_ROOT) + " " + Shell.quote(bk) + " && "
                    + AS_MC + "tar -czf - -C " + Shell.quote(srv.dir()) + " --exclude=./logs --exclude=./crash-reports . > "
                    + Shell.quote(part) + "; rc=$?; if [ $rc -le 1 ]; then mv -f " + Shell.quote(part) + " "
                    + Shell.quote(bk + "/" + name) + " && rc=0; fi; rm -f " + Shell.quote(part) + "; exit $rc";
            rootOk(s, cmd, LONG, "Не удалось создать бэкап");
        } finally {
            if (savingOff) {
                try {
                    rconOrNull.command("save-on");
                } catch (VpsException e) {
                    progress.accept("не удалось включить автосохранение обратно — выполните save-on в консоли");
                }
            }
        }
        for (Backup b : listBackups(s, srv)) if (b.name().equals(name)) return b;
        throw new VpsException("Бэкап создан, но не найден в списке");
    }

    /**
     * Откат к бэкапу: сервер останавливается, архив распаковывается рядом, и только если распаковка
     * прошла, текущая папка заменяется на распакованную. Сломанный архив не оставит сервер без мира.
     */
    public void restoreBackup(VpsSession s, Srv srv, String name, boolean startAfter, Consumer<String> progress) throws VpsException {
        requireBackup(name);
        String file = srv.backupDir() + "/" + name;
        long sizeKb = num(s.execRoot("du -k " + Shell.quote(file) + " | cut -f1", SHORT).stdout().trim());
        if (sizeKb <= 0) throw new VpsException("Бэкап " + name + " не найден");
        long freeKb = num(s.exec("df -Pk /opt | awk 'NR==2 {print $4}'", SHORT).stdout().trim());
        if (freeKb > 0 && freeKb < sizeKb * 3) {
            throw new VpsException("Для отката нужно примерно " + (sizeKb * 3 / 1024) + " МБ свободного места, на VPS — " + freeKb / 1024 + " МБ");
        }
        progress.accept("останавливаю сервер");
        stop(s, srv);
        progress.accept("распаковываю " + name);
        String tmp = "/opt/blauncher/.restore-" + srv.id();
        String old = "/opt/blauncher/.before-restore-" + srv.id();
        String cmd = "rm -rf " + Shell.quote(tmp) + " " + Shell.quote(old)
                + " && install -d -o " + MC_USER + " -g " + MC_USER + " -m 750 " + Shell.quote(tmp)
                + " && cat " + Shell.quote(file) + " | " + AS_MC + "tar -xzf - -C " + Shell.quote(tmp)
                + " && mv " + Shell.quote(srv.dir()) + " " + Shell.quote(old)
                + " && mv " + Shell.quote(tmp) + " " + Shell.quote(srv.dir())
                + " && rm -rf " + Shell.quote(old) + "; rc=$?; rm -rf " + Shell.quote(tmp) + "; exit $rc";
        rootOk(s, cmd, LONG, "Не удалось распаковать бэкап — текущий мир не тронут");
        if (startAfter) {
            progress.accept("запускаю сервер");
            start(s, srv);
        }
    }

    // ================================================================ автобэкап (11.10.2026)
    //
    // Делает сам VPS по systemd-таймеру — а не лаунчер, — иначе при выключенном ПК (ради чего VPS
    // и берут) бэкапов бы не было. Скрипт лежит рядом, от root; пароль RCON для паузы автосохранения
    // он читает из server.properties на месте, по сети он не ходит. Имена — те же, что у ручных
    // бэкапов, поэтому автокопии видны в общем списке; какие из них автоматические, помнит
    // auto.list — удаляются только они, ручные бэкапы скрипт не трогает.

    public static final int AUTO_BACKUP_KEEP = 7;
    private static final String BIN_DIR = "/opt/blauncher/bin";

    private static String autoUnit(Srv srv) {
        return "blauncher-backup-" + srv.id().toLowerCase();
    }

    public boolean autoBackupEnabled(VpsSession s, Srv srv) throws VpsException {
        return s.exec("systemctl is-enabled --quiet " + Shell.quote(autoUnit(srv) + ".timer"), SHORT).ok();
    }

    public void setAutoBackup(VpsSession s, Srv srv, boolean on) throws VpsException {
        String unit = autoUnit(srv);
        String script = BIN_DIR + "/" + unit + ".sh";
        String svc = "/etc/systemd/system/" + unit + ".service";
        String timer = "/etc/systemd/system/" + unit + ".timer";
        if (!on) {
            rootOk(s, "systemctl disable --now " + Shell.quote(unit + ".timer") + " 2>/dev/null; rm -f "
                    + Shell.quote(svc) + " " + Shell.quote(timer) + " " + Shell.quote(script)
                    + "; systemctl daemon-reload", SHORT, "Не удалось выключить автобэкап");
            return;
        }
        String unitSvc = "# Создано BLauncher: автобэкап сервера " + srv.id() + "\n"
                + "[Unit]\nDescription=BLauncher backup " + srv.id() + "\n\n"
                + "[Service]\nType=oneshot\nExecStart=" + script + "\nNice=10\nIOSchedulingClass=idle\n";
        // 4:00 по Москве — ночью, когда на сервере почти никого; Persistent — если VPS в это время
        // был выключен, бэкап сделается после включения.
        String unitTimer = "# Создано BLauncher: автобэкап сервера " + srv.id() + "\n"
                + "[Unit]\nDescription=BLauncher daily backup " + srv.id() + "\n\n"
                + "[Timer]\nOnCalendar=*-*-* 04:00:00 Europe/Moscow\nRandomizedDelaySec=900\nPersistent=true\n\n"
                + "[Install]\nWantedBy=timers.target\n";
        String cmd = "install -d -m 755 " + Shell.quote(BIN_DIR)
                + " && printf '%s' " + Shell.quote(autoScript(srv)) + " > " + Shell.quote(script)
                + " && chmod 700 " + Shell.quote(script)
                + " && printf '%s' " + Shell.quote(unitSvc) + " > " + Shell.quote(svc)
                + " && printf '%s' " + Shell.quote(unitTimer) + " > " + Shell.quote(timer)
                + " && chmod 644 " + Shell.quote(svc) + " " + Shell.quote(timer)
                + " && systemctl daemon-reload && systemctl enable --now " + Shell.quote(unit + ".timer");
        rootOk(s, cmd, SHORT, "Не удалось включить автобэкап");
    }

    static String autoScript(Srv srv) {
        String py = String.join("\n",
                "import re, socket, struct, sys",
                "props = open(sys.argv[1], encoding='utf-8', errors='replace').read()",
                "def g(k):",
                "    m = re.search(r'^' + re.escape(k) + r'=(.*)$', props, re.M)",
                "    return m.group(1).strip() if m else ''",
                "if g('enable-rcon') != 'true': sys.exit(2)",
                "s = socket.create_connection(('127.0.0.1', int(g('rcon.port') or 25575)), 10)",
                "s.settimeout(120)",
                "def recv(n):",
                "    b = b''",
                "    while len(b) < n:",
                "        c = s.recv(n - len(b))",
                "        if not c: raise SystemExit(4)",
                "        b += c",
                "    return b",
                "def pkt(i, t, body):",
                "    d = struct.pack('<ii', i, t) + body.encode('utf-8') + b'\\0\\0'",
                "    s.sendall(struct.pack('<i', len(d)) + d)",
                "    n = struct.unpack('<i', recv(4))[0]",
                "    return struct.unpack('<i', recv(n)[:4])[0]",
                "if pkt(1, 3, g('rcon.password')) == -1: sys.exit(3)",
                "for c in sys.argv[2:]: pkt(2, 2, c)");
        return "#!/bin/bash\n"
                + "# Создано BLauncher: ежедневный бэкап сервера " + srv.id() + ". Хранит последние "
                + AUTO_BACKUP_KEEP + " автокопий; ручные бэкапы не трогает.\n"
                + "set -u\n"
                + "DIR=" + Shell.quote(srv.dir()) + "\n"
                + "BK=" + Shell.quote(srv.backupDir()) + "\n"
                + "SVC=" + Shell.quote(srv.service()) + "\n"
                + "KEEP=" + AUTO_BACKUP_KEEP + "\n"
                + "PY=" + Shell.quote(py) + "\n"
                + "rcon() { command -v python3 >/dev/null && python3 -c \"$PY\" \"$DIR/server.properties\" \"$@\"; }\n"
                + "install -d -m 700 " + Shell.quote(BACKUP_ROOT) + " \"$BK\" || exit 1\n"
                + "SIZE=$(du -sk \"$DIR\" | cut -f1); FREE=$(df -Pk /opt | awk 'NR==2 {print $4}')\n"
                + "if [ \"$FREE\" -lt $((SIZE + 262144)) ]; then echo \"мало места: свободно ${FREE} КБ\"; exit 1; fi\n"
                + "NAME=$(date +%Y%m%d-%H%M%S).tar.gz\n"
                + "OFF=0\n"
                + "if systemctl is-active --quiet \"$SVC\" && rcon save-off 'save-all flush'; then OFF=1; fi\n"
                + "runuser -u " + MC_USER + " -- tar -czf - -C \"$DIR\" --exclude=./logs --exclude=./crash-reports . > \"$BK/$NAME.part\"\n"
                + "rc=$?\n"
                // Включить автосохранение обратно важнее всего остального: без него сервер не пишет мир.
                + "if [ $OFF = 1 ]; then for i in 1 2 3; do rcon save-on && break; sleep 5; done; fi\n"
                + "if [ $rc -gt 1 ]; then rm -f \"$BK/$NAME.part\"; echo \"tar: код $rc\"; exit $rc; fi\n"
                + "mv -f \"$BK/$NAME.part\" \"$BK/$NAME\" && echo \"$NAME\" >> \"$BK/auto.list\"\n"
                + "LIST=$(grep -E '^[0-9]{8}-[0-9]{6}\\.tar\\.gz$' \"$BK/auto.list\" | sort -u)\n"
                + "N=$(printf '%s\\n' \"$LIST\" | grep -c .)\n"
                + "if [ \"$N\" -gt \"$KEEP\" ]; then\n"
                + "  printf '%s\\n' \"$LIST\" | head -n $((N - KEEP)) | while read -r f; do rm -f -- \"$BK/$f\"; done\n"
                + "  printf '%s\\n' \"$LIST\" | tail -n \"$KEEP\" > \"$BK/auto.list\"\n"
                + "fi\n"
                + "echo \"бэкап $NAME готов\"\n";
    }

    public void deleteBackup(VpsSession s, Srv srv, String name) throws VpsException {
        requireBackup(name);
        rootOk(s, "rm -f -- " + Shell.quote(srv.backupDir() + "/" + name), SHORT, "Не удалось удалить бэкап");
    }

    public void downloadBackup(VpsSession s, Srv srv, String name, Path local, LongConsumer progress) throws VpsException, IOException {
        requireBackup(name);
        Path tmp = local.resolveSibling(local.getFileName() + ".part");
        try (OutputStream out = new CountingOutput(Files.newOutputStream(tmp), progress)) {
            VpsSession.ExecResult r = s.execRootTo("cat -- " + Shell.quote(srv.backupDir() + "/" + name), out, LONG);
            if (!r.ok()) throw new VpsException("Не удалось скачать бэкап" + errTail(r));
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, local, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void requireBackup(String name) {
        if (name == null || !BACKUP_NAME.matcher(name).matches()) throw new IllegalArgumentException("Недопустимое имя бэкапа: " + name);
    }

    // ================================================================ мелочи

    /** Кладёт файл с ПК во временную папку пользователя SSH (права 600); возвращает абсолютный путь. */
    private String stage(VpsSession s, Srv srv, Path local, LongConsumer progress) throws VpsException, IOException {
        ChannelSftp sftp = s.sftp();
        try {
            String rel = STAGING + "/" + srv.id();
            String path = "";
            for (String part : rel.split("/")) {
                path = path.isEmpty() ? part : path + "/" + part;
                if (statOrNull(sftp, path) == null) {
                    sftp.mkdir(path);
                    sftp.chmod(0700, path);
                }
            }
            String name = rel + "/up-" + Long.toHexString(random.nextLong());
            long[] sent = {0};
            SftpProgressMonitor mon = new SftpProgressMonitor() {
                @Override public void init(int op, String src, String dest, long max) {}
                @Override public boolean count(long count) {
                    sent[0] += count;
                    if (progress != null) progress.accept(sent[0]);
                    return true;
                }
                @Override public void end() {}
            };
            try (InputStream in = Files.newInputStream(local)) {
                sftp.put(in, name, mon, ChannelSftp.OVERWRITE);
            }
            sftp.chmod(0600, name);
            return sftp.getHome() + "/" + name;
        } catch (SftpException e) {
            throw new VpsException("Не удалось передать файл на VPS: " + e.getMessage(), e);
        } finally {
            sftp.disconnect();
        }
    }

    private static SftpATTRS statOrNull(ChannelSftp sftp, String path) throws SftpException {
        try {
            return sftp.stat(path);
        } catch (SftpException e) {
            if (e.id == ChannelSftp.SSH_FX_NO_SUCH_FILE) return null;
            throw e;
        }
    }

    private static String rootOk(VpsSession s, String cmd, Duration timeout, String error) throws VpsException {
        VpsSession.ExecResult r = s.execRoot(cmd, timeout);
        if (!r.ok()) throw new VpsException(error + errTail(r));
        return r.stdout();
    }

    private static String errTail(VpsSession.ExecResult r) {
        String e = r.stderr().strip();
        if (e.isEmpty()) return " (код " + r.exitCode() + ")";
        return ": " + (e.length() > 400 ? "…" + e.substring(e.length() - 400) : e);
    }

    private static long num(String s) {
        try { return Long.parseLong(s == null ? "" : s.trim()); } catch (RuntimeException e) { return 0; }
    }

    /** Счётчик systemd: «[not set]» или 2^64-1 — значит, не ведётся. */
    private static long counter(String s) {
        if (s == null) return -1;
        try {
            long v = Long.parseUnsignedLong(s.trim());
            return v == -1L ? -1 : v;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Считает переданные байты — для полосы прогресса скачивания. */
    private static final class CountingOutput extends OutputStream {
        private final OutputStream out;
        private final LongConsumer progress;
        private long total;
        private long lastReport;

        CountingOutput(OutputStream out, LongConsumer progress) {
            this.out = out;
            this.progress = progress;
        }

        @Override public void write(int b) throws IOException {
            out.write(b);
            add(1);
        }

        @Override public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            add(len);
        }

        private void add(long n) {
            total += n;
            if (progress != null && total - lastReport >= 256 * 1024) {
                lastReport = total;
                progress.accept(total);
            }
        }

        @Override public void flush() throws IOException { out.flush(); }

        @Override public void close() throws IOException {
            if (progress != null) progress.accept(total);
            out.close();
        }
    }
}
