package com.example.launcher.service.vps;

import com.example.launcher.service.Http;
import com.example.launcher.service.ServerProps;
import com.example.launcher.service.VersionDownloadService;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.SftpProgressMonitor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Установка Minecraft-сервера на VPS (этап 2 панели VPS).
 *
 * <p>Шаги идут по порядку, и каждый можно повторить: перед работой шаг проверяет, не сделано ли
 * это уже (Java нужной версии стоит, ядро той же версии лежит, пользователь есть), и тогда
 * пропускается. Поэтому «Повторить» после сбоя — это просто повторный запуск всей установки:
 * готовое пролетит за секунды, и работа продолжится с упавшего шага.
 *
 * <p>Что где лежит на VPS:
 * <ul>
 *   <li>{@code /opt/blauncher/java/temurin-<N>} — Java (Eclipse Temurin JRE с проверкой sha256),
 *       общая для всех серверов этого VPS. Не из apt: в Debian 12 есть только 17, а Minecraft
 *       26.x нужна 25 — свой JRE одинаково ставится на любой поддерживаемой системе.</li>
 *   <li>{@code /opt/blauncher/<id>} — папка сервера, владелец — системный пользователь
 *       {@code minecraft}. Сервер никогда не работает от root.</li>
 *   <li>{@code /etc/systemd/system/blauncher-mc-<id8>.service} — служба: автозапуск при загрузке
 *       и перезапуск при падении.</li>
 * </ul>
 *
 * <p>Файлы на VPS пишутся через SFTP во временную папку в домашнем каталоге пользователя SSH и
 * переносятся на место командой {@code install} от root. Не через stdin команды: при sudo с
 * паролем stdin начинается с пароля, и любая ошибка в том, кто его прочтёт, уносит пароль в файл.
 *
 * <p>Ссылки на ядро и Java получает ПК (у него уже есть код для API Mojang, Paper, Fabric, Forge,
 * NeoForge), а качает сам VPS — у него канал обычно шире, чем у домашнего интернета. Всё, что
 * публикует контрольную сумму, проверяется на VPS до того, как файл займёт своё место.
 */
public final class VpsInstaller {

    public enum Step {
        CHECK("Проверка VPS"),
        PACKAGES("Системные пакеты"),
        JAVA("Java"),
        USER("Пользователь и папка"),
        CORE("Ядро сервера"),
        MODS("Моды сборки"),
        MIGRATE("Перенос мира и настроек с ПК"),
        CONFIG("EULA и настройки"),
        SERVICE("Служба systemd"),
        FIREWALL("Брандмауэр"),
        START("Первый запуск");

        public final String label;
        Step(String label) { this.label = label; }
    }

    public interface Progress {
        void update(Step step, VpsConnector.State state, String detail);
    }

    /** Ядра, которые панель ставит. Purpur и прочие форки — не сейчас: у них нет общего API. */
    public enum Core {
        VANILLA("Vanilla"), PAPER("Paper"), FABRIC("Fabric"), FORGE("Forge"), NEOFORGE("NeoForge");

        public final String label;
        Core(String label) { this.label = label; }

        public boolean needsLoaderVersion() {
            return this == FABRIC || this == FORGE || this == NEOFORGE;
        }

        public boolean takesMods() {
            return this == FABRIC || this == FORGE || this == NEOFORGE;
        }

        /** Загрузчик сборки лаунчера (Profile.getModLoader) → ядро. null — такое не ставим. */
        public static Core fromProfileLoader(String loader) {
            if (loader == null || loader.isBlank()) return VANILLA;
            return switch (loader.toLowerCase()) {
                case "vanilla", "optifine" -> VANILLA;   // OptiFine — только клиент
                case "fabric" -> FABRIC;
                case "forge" -> FORGE;
                case "neoforge" -> NEOFORGE;
                case "paper" -> PAPER;
                default -> null;
            };
        }
    }

    /**
     * Что ставить. Собирается на ПК до начала установки.
     *
     * @param loaderVersion версия Fabric/Forge/NeoForge; для Vanilla и Paper — null
     * @param modJars       моды сборки для папки mods (пусто — чистое ядро)
     * @param profileName   имя сборки лаунчера, если ставим её; иначе null
     * @param migrateDir    папка локального сервера, который переносим на VPS (этап 4); null — новый сервер
     */
    /** @param props выбор из плиток server.properties (ServerProps.cleanAll, уже экранирован); пусто — не трогать
     *  @param cpuCores сколько ядер VPS можно занять этому серверу (CPUQuota); 0 — без ограничения */
    public record Plan(String serverId, String mcVersion, Core core, String loaderVersion,
                       int ramMb, int port, boolean eulaAccepted, List<Path> modJars, String profileName,
                       Path migrateDir, Map<String, String> props, int cpuCores) {
        public Plan {
            modJars = modJars == null ? List.of() : List.copyOf(modJars);
            props = props == null ? Map.of() : Map.copyOf(props);
            cpuCores = Math.max(0, cpuCores);
        }

        public Plan(String serverId, String mcVersion, Core core, String loaderVersion,
                    int ramMb, int port, boolean eulaAccepted, List<Path> modJars, String profileName,
                    Path migrateDir, Map<String, String> props) {
            this(serverId, mcVersion, core, loaderVersion, ramMb, port, eulaAccepted, modJars, profileName, migrateDir, props, 0);
        }

        public Plan(String serverId, String mcVersion, Core core, String loaderVersion,
                    int ramMb, int port, boolean eulaAccepted, List<Path> modJars, String profileName,
                    Path migrateDir) {
            this(serverId, mcVersion, core, loaderVersion, ramMb, port, eulaAccepted, modJars, profileName, migrateDir, null);
        }

        public Plan(String serverId, String mcVersion, Core core, String loaderVersion,
                    int ramMb, int port, boolean eulaAccepted, List<Path> modJars, String profileName) {
            this(serverId, mcVersion, core, loaderVersion, ramMb, port, eulaAccepted, modJars, profileName, null, null);
        }
    }

    /** Итог для ServerEntry. Пароль RCON — уже в SecretStore. */
    public record Result(String serverDir, String service, int rconPort, int javaMajor, String loaderVersion) {}

    private static final String BASE_DIR = "/opt/blauncher";
    private static final String JAVA_DIR = BASE_DIR + "/java";
    private static final String MC_USER = "minecraft";
    private static final String STAGING = ".blauncher-staging";
    private static final String CORE_MARKER = ".blauncher-core";
    private static final String MODS_MARKER = ".blauncher_managed";
    private static final String USER_AGENT = "BLauncher/1.0 (https://blauncher.ru)";
    private static final String DL_TMP = ".bl-download.tmp";

    private static final Duration SHORT = Duration.ofSeconds(30);
    private static final Duration APT = Duration.ofMinutes(15);
    private static final Duration DOWNLOAD = Duration.ofMinutes(15);
    private static final Duration FORGE_INSTALL = Duration.ofMinutes(25);
    /** Первый запуск Forge с сотней модов на слабом VPS легко идёт 5–8 минут. */
    private static final Duration FIRST_START = Duration.ofMinutes(15);

    /** Сколько памяти оставить системе и sshd сверх -Xmx. */
    public static final int SYSTEM_RESERVE_MB = 384;
    /** Свободное место на диске, без которого не начинаем (мир, логи, бэкапы). */
    private static final long MIN_FREE_DISK_MB = 1536;

    private static final Pattern SERVER_ID = Pattern.compile("^[0-9a-fA-F-]{8,64}$");
    private static final Pattern HEX = Pattern.compile("^[0-9a-fA-F]+$");
    private static final Pattern JAR_NAME = Pattern.compile("^[A-Za-z0-9._+\\-]{1,120}\\.jar$");

    private final VersionDownloadService vds;
    private final SecretStore secrets;
    private final SecureRandom random = new SecureRandom();

    public VpsInstaller(VersionDownloadService vds, SecretStore secrets) {
        this.vds = vds;
        this.secrets = secrets;
    }

    public static String serverDir(String serverId) {
        return BASE_DIR + "/" + requireId(serverId);
    }

    /**
     * Полный id, а не его начало: 07.10.2026 на тесте два сервера с одинаковыми первыми 8 символами
     * получили одно имя службы, и установка второго молча переписала юнит первого.
     */
    public static String serviceName(String serverId) {
        return "blauncher-mc-" + requireId(serverId).toLowerCase();
    }

    private static String requireId(String id) {
        if (id == null || !SERVER_ID.matcher(id).matches()) throw new IllegalArgumentException("Недопустимый id сервера: " + id);
        return id;
    }

    /** Java, которую ставим: LTS-сборка Temurin не ниже нужной Minecraft (16 → 17). */
    public static int javaForMinecraft(int required) {
        for (int lts : new int[]{8, 11, 17, 21, 25}) if (lts >= required) return lts;
        return required;
    }

    // ================================================================ установка

    /** Ход одной установки — чтобы не таскать сессию и план через все методы. */
    private final class Run {
        final VpsSession s;
        final Plan plan;
        final Progress progress;
        final String id;
        final String dir;
        final String service;
        Step current = Step.CHECK;
        String arch;
        int javaMajor;
        String javaBin;
        String loaderVersion;
        int rconPort;
        String stagingAbs;
        /** Что уже стоит на VPS (другие серверы BLauncher) — читается на шаге CHECK. */
        VpsCapacity.Snapshot capacity;

        Run(VpsSession s, Plan plan, Progress progress) {
            this.s = s;
            this.plan = plan;
            this.progress = progress;
            this.id = requireId(plan.serverId());
            this.dir = serverDir(id);
            this.service = serviceName(id);
            this.loaderVersion = plan.loaderVersion();
        }

        void step(Step step, String detail) {
            current = step;
            progress.update(step, VpsConnector.State.RUNNING, detail);
        }

        void info(String detail) { progress.update(current, VpsConnector.State.RUNNING, detail); }

        void done(String detail) { progress.update(current, VpsConnector.State.DONE, detail); }

        VpsException fail(String message) {
            progress.update(current, VpsConnector.State.FAILED, message);
            return new VpsException(message);
        }

        VpsSession.ExecResult root(String cmd, Duration timeout) throws VpsException {
            return s.execRoot(cmd, timeout);
        }

        /** Команда от root, которая обязана пройти; иначе — шаг падает с этим текстом. */
        String rootOk(String cmd, Duration timeout, String errorText) throws VpsException {
            VpsSession.ExecResult r = root(cmd, timeout);
            if (!r.ok()) throw fail(errorText + tail(r));
            return r.stdout();
        }
    }

    public Result install(VpsSession session, Plan plan, Progress progress) throws VpsException {
        Run run = new Run(session, plan, progress);
        try {
            if (!plan.eulaAccepted()) {
                run.current = Step.CHECK;
                throw run.fail("Без принятия EULA Minecraft сервер не запустится — отметьте галочку.");
            }
            check(run);
            packages(run);
            java(run);
            user(run);
            core(run);
            mods(run);
            migrate(run);
            config(run);
            service(run);
            firewall(run);
            start(run);
            return new Result(run.dir, run.service, run.rconPort, run.javaMajor, run.loaderVersion);
        } catch (VpsException e) {
            throw e;
        } catch (IOException e) {
            throw run.fail("Ошибка: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw run.fail("Установка прервана");
        } catch (RuntimeException e) {
            throw run.fail("Непредвиденная ошибка: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        } finally {
            cleanupStaging(run);
        }
    }

    // ---------------------------------------------------------------- 1. проверка

    private void check(Run r) throws VpsException, IOException, InterruptedException {
        r.step(Step.CHECK, "архитектура, память, диск, порт");
        Shell.requireVersion(r.plan.mcVersion());
        if (r.plan.core().needsLoaderVersion() && r.loaderVersion != null) Shell.requireVersion(r.loaderVersion);
        if (r.plan.port() < 1024 || r.plan.port() > 65535) throw r.fail("Порт Minecraft должен быть от 1024 до 65535");

        String m = r.s.exec("uname -m", SHORT).stdout().trim();
        r.arch = switch (m) {
            case "x86_64", "amd64" -> "x64";
            case "aarch64", "arm64" -> "aarch64";
            default -> throw r.fail("Процессор VPS (" + m + ") не поддерживается: нужен x86_64 или aarch64");
        };

        if (!r.s.exec("test -d /run/systemd/system", SHORT).ok()) {
            throw r.fail("На VPS не запущен systemd — без него сервер не сможет стартовать сам. "
                    + "Обычно так бывает в контейнерах OpenVZ/LXC; нужен полноценный VPS (KVM).");
        }

        // Несколько серверов на одном VPS (26.10.2026): память считается за все серверы BLauncher на
        // машине, включая остановленные — после перезагрузки VPS они стартуют одновременно, и
        // перерасход кончился бы тем, что ядро Linux убьёт один из них посреди игры.
        r.capacity = new VpsCapacity().read(r.s);
        long memMb = r.capacity.memTotalMb();
        int others = r.capacity.servers().size() - (r.capacity.find(r.id) != null ? 1 : 0);
        if (memMb > 0) {
            int max = r.capacity.maxRamFor(r.id);
            if (r.plan.ramMb() > max) {
                throw r.fail(others == 0
                        ? "На VPS " + memMb + " МБ памяти — серверу можно дать не больше " + max
                                + " МБ (остальное нужно системе и самой Java). Выберите меньше."
                        : "На VPS " + memMb + " МБ памяти, и " + VpsCapacity.mbText(r.capacity.usedMb(r.id))
                                + " уже за другими серверами (" + others + "). Этому можно дать не больше "
                                + max + " МБ — выберите меньше или удалите ненужный сервер с VPS.");
            }
        }
        if (r.plan.cpuCores() > r.capacity.cpus()) {
            throw r.fail("На VPS ядер: " + r.capacity.cpus() + " — больше серверу не дать");
        }

        long freeMb = parseLong(r.s.exec("df -Pk / | awk 'NR==2 {print $4}'", SHORT).stdout()) / 1024;
        long modsMb = 0;
        for (Path p : r.plan.modJars()) modsMb += Files.size(p) / (1024 * 1024);
        // Перенос: архив в staging + его копия для minecraft + распакованное — до трёх объёмов разом.
        long migrateMb = r.plan.migrateDir() == null ? 0
                : ServerMigration.scan(r.plan.migrateDir()).bytes() / (1024 * 1024);
        long needMb = MIN_FREE_DISK_MB + modsMb * 2 + migrateMb * 3;
        if (freeMb > 0 && freeMb < needMb) {
            throw r.fail("На диске VPS свободно " + freeMb + " МБ — для сервера нужно хотя бы " + needMb + " МБ"
                    + (migrateMb > 0 ? " (переносимый мир и настройки — " + migrateMb + " МБ)." : "."));
        }

        // Порт занят чем-то, кроме нашей же службы (повторная установка поверх работающей)?
        boolean ourActive = r.s.exec("systemctl is-active --quiet " + Shell.quote(r.service), SHORT).ok();
        if (!ourActive && listeningPorts(r).contains(r.plan.port())) {
            throw r.fail("Порт " + r.plan.port() + " на VPS уже занят другой программой. Выберите другой порт Minecraft.");
        }
        // Остановленный сосед порт не слушает, но займёт его при следующем запуске.
        if (r.capacity.takenPorts(r.id).contains(r.plan.port())) {
            throw r.fail("Порт " + r.plan.port() + " уже отдан другому серверу на этом VPS. Выберите другой порт Minecraft.");
        }

        r.javaMajor = javaForMinecraft(resolveRequiredJava(r));
        r.javaBin = JAVA_DIR + "/temurin-" + r.javaMajor + "/bin/java";
        r.done(m + ", " + memMb + " МБ памяти, " + (freeMb / 1024) + " ГБ свободно на диске"
                + (others > 0 ? ", серверов рядом: " + others : ""));
    }

    private int resolveRequiredJava(Run r) throws VpsException, IOException, InterruptedException {
        try {
            return vds.getServerJarInfo(r.plan.mcVersion()).javaMajor();
        } catch (IOException e) {
            throw r.fail("Не удалось получить данные версии " + r.plan.mcVersion() + " у Mojang: " + e.getMessage());
        }
    }

    private static Set<Integer> listeningPorts(Run r) throws VpsException {
        Set<Integer> ports = new HashSet<>();
        VpsSession.ExecResult res = r.s.exec("ss -Htln 2>/dev/null", SHORT);
        for (String line : res.stdout().split("\n")) {
            String[] cols = line.trim().split("\\s+");
            if (cols.length < 4) continue;
            String local = cols[3];
            int colon = local.lastIndexOf(':');
            if (colon < 0) continue;
            try { ports.add(Integer.parseInt(local.substring(colon + 1))); } catch (NumberFormatException ignored) {}
        }
        return ports;
    }

    // ---------------------------------------------------------------- 2. пакеты

    private void packages(Run r) throws VpsException {
        r.step(Step.PACKAGES, "curl, tar, sha256sum");
        // python3 — для RCON из автобэкапа и планового перезапуска (на минимальном Debian его нет, 26.10.2026).
        if (r.s.exec("command -v curl && command -v tar && command -v gzip && command -v sha256sum && command -v runuser"
                + " && command -v python3", SHORT).ok()) {
            r.done("всё уже есть");
            return;
        }
        r.info("ставлю curl и сертификаты через apt (до нескольких минут)");
        // Lock::Timeout — на свежем VPS apt часто занят unattended-upgrades в первые минуты.
        r.rootOk("export DEBIAN_FRONTEND=noninteractive; "
                        + "apt-get -o DPkg::Lock::Timeout=300 update -qq && "
                        + "apt-get -o DPkg::Lock::Timeout=300 install -y -qq curl ca-certificates tar gzip coreutils util-linux python3-minimal",
                APT, "apt не смог поставить пакеты");
        r.done("curl и сертификаты установлены");
    }

    // ---------------------------------------------------------------- 3. Java

    private void java(Run r) throws VpsException {
        r.step(Step.JAVA, "нужна Java " + r.javaMajor);
        if (javaPresent(r)) {
            r.done("Java " + r.javaMajor + " уже установлена");
            return;
        }
        r.info("ищу Eclipse Temurin " + r.javaMajor + " для " + r.arch);
        String[] pkg = adoptium(r);
        String url = pkg[0];
        String sha256 = pkg[1];
        r.info("скачиваю и проверяю Temurin " + r.javaMajor + " (≈50 МБ)");
        String target = JAVA_DIR + "/temurin-" + r.javaMajor;
        // Распаковка во временную папку рядом и замена одним mv: обрыв посреди tar не оставит
        // полуразобранную Java, которую javaPresent принял бы за целую.
        String cmd = "mkdir -p " + Shell.quote(JAVA_DIR) + " && chmod 755 " + Shell.quote(BASE_DIR) + " " + Shell.quote(JAVA_DIR)
                + " && T=$(mktemp -d " + Shell.quote(JAVA_DIR + "/.dl-XXXXXX") + ") || exit 1; "
                + "( curl -fsSL --retry 3 --connect-timeout 20 -A " + Shell.quote(USER_AGENT) + " -o \"$T/jre.tar.gz\" " + Shell.quote(url)
                + " && printf '%s  %s\\n' " + Shell.quote(sha256) + " \"$T/jre.tar.gz\" | sha256sum -c --quiet - "
                + " && mkdir \"$T/x\" && tar -xzf \"$T/jre.tar.gz\" -C \"$T/x\" --strip-components=1 "
                + " && rm -rf " + Shell.quote(target) + " && mv \"$T/x\" " + Shell.quote(target)
                + " && chmod -R a+rX " + Shell.quote(target) + " ); rc=$?; rm -rf \"$T\"; exit $rc";
        VpsSession.ExecResult res = r.root(cmd, DOWNLOAD);
        if (!res.ok()) {
            String err = res.stderr();
            if (err.contains("FAILED") || err.contains("checksum")) throw r.fail("Скачанная Java не прошла проверку sha256 — повторите установку");
            throw r.fail("Не удалось скачать Java" + tail(res));
        }
        if (!javaPresent(r)) throw r.fail("Java распакована, но не запускается на этом VPS");
        r.done("Temurin " + r.javaMajor + " установлен в " + target);
    }

    private boolean javaPresent(Run r) throws VpsException {
        VpsSession.ExecResult v = r.s.exec(Shell.quote(r.javaBin) + " -version 2>&1", SHORT);
        if (!v.ok()) return false;
        String want = r.javaMajor == 8 ? "\"1.8." : "\"" + r.javaMajor;
        return v.stdout().contains(want);
    }

    /** @return {ссылка, sha256} архива JRE Temurin под архитектуру VPS. */
    private String[] adoptium(Run r) throws VpsException {
        for (String image : new String[]{"jre", "jdk"}) {
            String api = "https://api.adoptium.net/v3/assets/latest/" + r.javaMajor + "/hotspot?architecture=" + r.arch
                    + "&image_type=" + image + "&os=linux&vendor=eclipse";
            try {
                Http.Response resp = Http.get(api, Map.of("User-Agent", USER_AGENT), Duration.ofSeconds(30));
                if (!resp.ok()) continue;
                JsonArray arr = JsonParser.parseString(resp.text()).getAsJsonArray();
                for (JsonElement el : arr) {
                    JsonObject pkg = el.getAsJsonObject().getAsJsonObject("binary").getAsJsonObject("package");
                    String link = pkg.get("link").getAsString();
                    String sum = pkg.get("checksum").getAsString();
                    if (link.startsWith("https://") && link.endsWith(".tar.gz") && sum.length() == 64 && HEX.matcher(sum).matches()) {
                        return new String[]{link, sum.toLowerCase()};
                    }
                }
            } catch (IOException | RuntimeException e) {
                throw r.fail("Не удалось получить ссылку на Java у Adoptium: " + e.getMessage());
            }
        }
        throw r.fail("У Adoptium нет Java " + r.javaMajor + " для " + r.arch);
    }

    // ---------------------------------------------------------------- 4. пользователь

    private void user(Run r) throws VpsException {
        r.step(Step.USER, MC_USER + ", " + r.dir);
        String cmd = "id -u " + MC_USER + " >/dev/null 2>&1 || useradd --system --user-group --home-dir " + Shell.quote(BASE_DIR)
                + " --no-create-home --shell /usr/sbin/nologin " + MC_USER + "; "
                + "mkdir -p " + Shell.quote(BASE_DIR) + " && chmod 755 " + Shell.quote(BASE_DIR)
                + " && install -d -o " + MC_USER + " -g " + MC_USER + " -m 750 " + Shell.quote(r.dir);
        r.rootOk(cmd, SHORT, "Не удалось создать пользователя " + MC_USER + " или папку сервера");
        r.done("сервер работает от пользователя " + MC_USER + ", не от root");
    }

    // ---------------------------------------------------------------- 5. ядро

    private record Download(String url, String algo, String hash) {}

    private void core(Run r) throws VpsException, IOException, InterruptedException {
        Core core = r.plan.core();
        r.step(Step.CORE, core.label + " " + r.plan.mcVersion());
        if (core.needsLoaderVersion() && (r.loaderVersion == null || r.loaderVersion.isBlank())) {
            r.info("выбираю последнюю версию " + core.label);
            List<String> versions = switch (core) {
                case FABRIC -> vds.getFabricVersions(r.plan.mcVersion());
                case FORGE -> vds.getForgeVersions(r.plan.mcVersion());
                default -> vds.getNeoForgeVersions(r.plan.mcVersion());
            };
            if (versions.isEmpty()) throw r.fail(core.label + " не выпущен для Minecraft " + r.plan.mcVersion());
            r.loaderVersion = Shell.requireVersion(versions.get(0));
        }
        if (!core.needsLoaderVersion()) r.loaderVersion = null;

        String marker = "core=" + core.name() + "\nmc=" + r.plan.mcVersion() + "\nloader="
                + (r.loaderVersion == null ? "" : r.loaderVersion) + "\n";
        VpsSession.ExecResult cur = r.root("cat " + Shell.quote(r.dir + "/" + CORE_MARKER) + " 2>/dev/null", SHORT);
        if (cur.ok() && cur.stdout().equals(marker) && launchSpec(r) != null) {
            r.done(coreTitle(r) + " уже установлен");
            return;
        }

        switch (core) {
            case VANILLA -> {
                VersionDownloadService.ServerJarInfo info = vds.getServerJarInfo(r.plan.mcVersion());
                if (info.url() == null) throw r.fail("Mojang не публикует сервер для версии " + r.plan.mcVersion());
                download(r, new Download(info.url(), "sha1", info.sha1()), "server.jar", "server.jar");
            }
            case PAPER -> {
                VersionDownloadService.PaperBuild b;
                try {
                    b = vds.getPaperBuild(r.plan.mcVersion());
                } catch (IOException e) {
                    throw r.fail(e.getMessage());
                }
                download(r, new Download(b.url(), "sha256", b.sha256()), "paper.jar", b.fileName());
            }
            case FABRIC -> {
                String installer = vds.getLatestFabricInstallerVersion();
                String url = vds.getFabricServerLauncherUrl(r.plan.mcVersion(), r.loaderVersion, installer);
                // Fabric Meta контрольных сумм для этого jar не даёт — только HTTPS.
                download(r, new Download(url, null, null), "fabric-server-launch.jar", "Fabric " + r.loaderVersion);
            }
            case FORGE, NEOFORGE -> installForgeLike(r);
        }
        if (launchSpec(r) == null) throw r.fail("Ядро скачано, но запускаемый файл сервера не найден");
        writeFile(r, r.dir + "/" + CORE_MARKER, marker.getBytes(StandardCharsets.UTF_8), MC_USER, "640");
        r.done(coreTitle(r));
    }

    private static String coreTitle(Run r) {
        return r.plan.core().label + " " + r.plan.mcVersion() + (r.loaderVersion != null ? " (" + r.loaderVersion + ")" : "");
    }

    private void installForgeLike(Run r) throws VpsException {
        boolean neo = r.plan.core() == Core.NEOFORGE;
        String url = neo ? vds.getNeoForgeInstallerUrl(r.plan.mcVersion(), r.loaderVersion)
                : vds.getForgeInstallerUrl(r.plan.mcVersion(), r.loaderVersion);
        String sha1 = vds.fetchMavenSha1(url);
        download(r, new Download(url, sha1 == null ? null : "sha1", sha1), ".bl-installer.jar", "установщик " + r.plan.core().label);
        // В ошибку — строки с исключением и последние строки лога, а не хвост стектрейса: по хвосту
        // («at …getResponseCode») не понять, что случилось, а первая строка говорит прямо
        // («SocketTimeoutException: Connect timed out»).
        String cmd = "cd " + Shell.quote(r.dir) + " && runuser -u " + MC_USER + " -- " + Shell.quote(r.javaBin)
                + " -jar .bl-installer.jar --installServer > .bl-installer.log 2>&1; rc=$?; "
                + "if [ $rc -ne 0 ]; then { grep -m 3 -E 'Exception|Error:' .bl-installer.log; tail -n 1 .bl-installer.log; } >&2; fi; exit $rc";
        VpsSession.ExecResult res = null;
        // Три попытки: установщик ходит в сеть сотни раз и сам не повторяет ни одного запроса — один
        // «Connect timed out» роняет всю установку. На тесте 07.10.2026 (WSL за VPN-туннелем, ~30%
        // соединений зависали) так падал каждый второй запуск. Повторный запуск докачивает только
        // недостающее, поэтому каждая попытка продвигает установку дальше.
        for (int attempt = 1; attempt <= 3; attempt++) {
            r.info(attempt == 1 ? "установщик " + r.plan.core().label + " качает библиотеки и Minecraft (до 10 минут)"
                    : "сеть подвела — запускаю установщик ещё раз");
            res = r.root(cmd, FORGE_INSTALL);
            if (res.ok()) break;
        }
        r.root("rm -f " + Shell.quote(r.dir + "/.bl-installer.jar"), SHORT);
        if (!res.ok()) throw r.fail("Установщик " + r.plan.core().label + " завершился с ошибкой" + tail(res));
    }

    /** Скачивание на VPS от имени minecraft прямо в папку сервера, с проверкой суммы. */
    private void download(Run r, Download d, String target, String what) throws VpsException {
        if (!d.url().startsWith("https://")) throw r.fail("Небезопасная ссылка на " + what);
        if (d.hash() != null && !HEX.matcher(d.hash()).matches()) throw r.fail("Неверная контрольная сумма для " + what);
        r.info("скачиваю " + what);
        String sumTool = "sha256".equals(d.algo()) ? "sha256sum" : "sha1sum";
        String check = d.hash() == null ? "true"
                : "printf '%s  %s\\n' " + Shell.quote(d.hash().toLowerCase()) + " " + DL_TMP + " | " + sumTool + " -c --quiet -";
        String cmd = "cd " + Shell.quote(r.dir) + " && rm -f " + DL_TMP + " && runuser -u " + MC_USER + " -- curl -fsSL --retry 3 --connect-timeout 20 -A "
                + Shell.quote(USER_AGENT) + " -o " + DL_TMP + " " + Shell.quote(d.url())
                + " && { " + check + " || { rm -f " + DL_TMP + "; echo 'checksum mismatch' >&2; exit 3; }; }"
                + " && mv -f " + DL_TMP + " " + Shell.quote(target);
        VpsSession.ExecResult res = r.root(cmd, DOWNLOAD);
        if (res.exitCode() == 3) throw r.fail("Файл " + what + " скачался с неверной контрольной суммой — повторите установку");
        if (!res.ok()) throw r.fail("Не удалось скачать " + what + tail(res));
    }

    /**
     * Чем запускать сервер: «jar:имя» или «script:run.sh». null — ядро не установлено.
     * Forge и NeoForge 1.17+ кладут run.sh (classpath в @-файлах), старые Forge — forge-*.jar.
     */
    private String launchSpec(Run r) throws VpsException {
        VpsSession.ExecResult ls = r.root("ls -1 " + Shell.quote(r.dir), SHORT);
        if (!ls.ok()) return null;
        Set<String> files = new HashSet<>(List.of(ls.stdout().split("\n")));
        return switch (r.plan.core()) {
            case VANILLA -> files.contains("server.jar") ? "jar:server.jar" : null;
            case PAPER -> files.contains("paper.jar") ? "jar:paper.jar" : null;
            case FABRIC -> files.contains("fabric-server-launch.jar") ? "jar:fabric-server-launch.jar" : null;
            case FORGE, NEOFORGE -> {
                if (files.contains("run.sh")) yield "script:run.sh";
                for (String f : files) {
                    if (f.startsWith("forge-") && JAR_NAME.matcher(f).matches() && !f.contains("installer")) yield "jar:" + f;
                }
                yield null;
            }
        };
    }

    // ---------------------------------------------------------------- 6. моды

    private void mods(Run r) throws VpsException {
        r.step(Step.MODS, null);
        if (!r.plan.core().takesMods() || r.plan.modJars().isEmpty()) {
            r.done(r.plan.migrateDir() != null ? "у локального сервера нет модов"
                    : r.plan.profileName() == null ? "не нужно — чистое ядро" : "в сборке нет серверных модов");
            return;
        }
        List<Path> jars = r.plan.modJars();
        Set<String> names = new HashSet<>();
        for (Path p : jars) {
            String n = p.getFileName().toString();
            if (n.contains("/") || n.contains("\\") || !n.endsWith(".jar")) throw r.fail("Странное имя мода: " + n);
            Shell.requireRelativePath(n);
            names.add(n);
        }

        String stagingRel = STAGING + "/" + r.id + "/mods";
        ChannelSftp sftp = r.s.sftp();
        try {
            r.stagingAbs = sftp.getHome() + "/" + STAGING + "/" + r.id;
            mkdirs(sftp, stagingRel);
            // Лишнее в staging — от прошлой попытки с другим составом сборки.
            for (Object o : sftp.ls(stagingRel)) {
                ChannelSftp.LsEntry e = (ChannelSftp.LsEntry) o;
                if (!e.getAttrs().isDir() && !names.contains(e.getFilename())) sftp.rm(stagingRel + "/" + e.getFilename());
            }
            int i = 0;
            for (Path p : jars) {
                i++;
                String n = p.getFileName().toString();
                long size = Files.size(p);
                SftpATTRS a = statOrNull(sftp, stagingRel + "/" + n);
                if (a != null && a.getSize() == size) continue;   // докачано в прошлый раз
                r.info("загружаю " + i + "/" + jars.size() + ": " + n);
                try (InputStream in = Files.newInputStream(p)) {
                    sftp.put(in, stagingRel + "/" + n, (SftpProgressMonitor) null, ChannelSftp.OVERWRITE);
                }
            }
        } catch (SftpException | IOException e) {
            throw r.fail("Не удалось загрузить моды на VPS: " + e.getMessage());
        } finally {
            sftp.disconnect();
        }

        // Моды, которые лаунчер клал раньше, а в сборке их больше нет, — убрать; чужие (положенные
        // руками) — не трогать. Та же логика, что у локального сервера (GameLaunchService.syncServerMods).
        //
        // Внутри папки сервера всё делает minecraft, а не root (проверка безопасности 26.10.2026).
        // Папка mods и файл-метка принадлежат minecraft, а от его имени работает код модов и
        // плагинов: заражённый мод мог подменить mods ссылкой на /etc и вписать в метку любые имена,
        // и при следующей установке root по его указке удалил бы чужие .jar, положил файлы в чужую
        // папку, а «install -d -o minecraft» на ссылке отдал бы ему саму папку. Root только кладёт
        // моды в свою папку H рядом (/opt/blauncher принадлежит root — подменить её minecraft не
        // может), остальное — runuser: по ссылкам он попадает лишь туда, куда ему и так можно.
        String modsDir = r.dir + "/mods";
        String handoff = BASE_DIR + "/.mods-" + r.id;
        String staged = r.stagingAbs + "/mods";
        String put = "rm -rf " + Shell.quote(handoff)
                + " && install -d -o root -g " + MC_USER + " -m 750 " + Shell.quote(handoff)
                + " && find " + Shell.quote(staged) + " -maxdepth 1 -type f -name '*.jar' -exec install -o root -g "
                + MC_USER + " -m 640 -t " + Shell.quote(handoff) + " {} +";
        String sync = "set -e; D=\"$1\"; H=\"$2\"; M=\"$3\"; mkdir -p -- \"$D\"; "
                // убрать прежние наши моды, которых нет в новой сборке
                + "if [ -f \"$D/$M\" ]; then while IFS= read -r n; do case \"$n\" in ''|*/*) continue;; *.jar) "
                + "[ -e \"$H/$n\" ] || rm -f -- \"$D/$n\";; esac; done < \"$D/$M\"; fi; "
                + "for f in \"$H\"/*.jar; do [ -e \"$f\" ] || continue; cp -f -- \"$f\" \"$D/\"; done; "
                + "for f in \"$H\"/*.jar; do [ -e \"$f\" ] && basename -- \"$f\"; done > \"$D/$M\"; exit 0";
        String cmd = put + " && runuser -u " + MC_USER + " -- sh -c " + Shell.quote(sync) + " sh "
                + Shell.quote(modsDir) + " " + Shell.quote(handoff) + " " + Shell.quote(MODS_MARKER)
                + "; rc=$?; rm -rf " + Shell.quote(handoff) + "; exit $rc";
        r.rootOk(cmd, DOWNLOAD, "Не удалось разложить моды в папку сервера");
        r.done(r.plan.migrateDir() != null ? "модов с локального сервера: " + jars.size()
                : "модов из сборки «" + r.plan.profileName() + "»: " + jars.size());
    }

    /**
     * Моды сборки, которые имеет смысл везти на сервер: все .jar из папки, кроме чисто клиентских.
     * <ul>
     *   <li>Fabric/Quilt: "environment": "client" в fabric.mod.json. Сервер такие и сам не грузит —
     *       просто незачем заливать их по SSH.</li>
     *   <li>Forge/NeoForge: зависимость от minecraft объявлена с side="CLIENT" (Sodium, Iris, Chat
     *       Heads). NeoForge такие моды на сервере ГРУЗИТ и падает: 07.10.2026 сборка «create
     *       сборка» с sodium-neoforge роняла сервер на старте с ClassNotFoundException:
     *       org.lwjgl.Version. displayTest="IGNORE_ALL_VERSION" признаком не считаем — его ставят и
     *       чисто серверные моды, которые как раз нужны.</li>
     * </ul>
     *
     * @return {[0] — что везти, [1] — что пропущено (только имена для сообщения)}
     */
    @SuppressWarnings("unchecked")
    public static List<Path>[] collectServerMods(Path modsDir) throws IOException {
        List<Path> keep = new ArrayList<>();
        List<Path> skipped = new ArrayList<>();
        if (modsDir == null || !Files.isDirectory(modsDir)) return new List[]{keep, skipped};
        try (Stream<Path> st = Files.list(modsDir)) {
            for (Path p : st.filter(f -> f.getFileName().toString().endsWith(".jar")).sorted().toList()) {
                if (isClientOnlyMod(p)) skipped.add(p);
                else keep.add(p);
            }
        }
        return new List[]{keep, skipped};
    }

    static boolean isClientOnlyMod(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            for (String toml : new String[]{"META-INF/neoforge.mods.toml", "META-INF/mods.toml"}) {
                ZipEntry e = z.getEntry(toml);
                if (e == null) continue;
                try (InputStream in = z.getInputStream(e)) {
                    return minecraftDependencyIsClientOnly(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            ZipEntry e = z.getEntry("fabric.mod.json");
            if (e == null) return false;
            try (InputStream in = z.getInputStream(e)) {
                JsonElement root = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                if (!root.isJsonObject()) return false;
                JsonElement env = root.getAsJsonObject().get("environment");
                return env != null && env.isJsonPrimitive() && "client".equalsIgnoreCase(env.getAsString());
            }
        } catch (IOException | RuntimeException e) {
            return false;   // не разобрали — везём: лишний мод безопаснее потерянного
        }
    }

    private static final Pattern TOML_KV = Pattern.compile("^\\s*(modId|side)\\s*=\\s*\"([^\"]*)\"");

    /**
     * Есть ли в mods.toml блок {@code [[dependencies.*]]} с modId="minecraft" и side="CLIENT".
     * Полный разбор TOML тут не нужен: значения в этих файлах — простые строки в кавычках.
     */
    static boolean minecraftDependencyIsClientOnly(String toml) {
        boolean inDependency = false;
        String modId = null;
        String side = null;
        for (String raw : (toml + "\n[[end]]").split("\n")) {
            String line = raw.strip();
            if (line.startsWith("[")) {
                if (inDependency && "minecraft".equals(modId) && "CLIENT".equalsIgnoreCase(side)) return true;
                inDependency = line.startsWith("[[dependencies");
                modId = null;
                side = null;
                continue;
            }
            if (!inDependency) continue;
            java.util.regex.Matcher m = TOML_KV.matcher(line);
            if (m.find()) {
                if (m.group(1).equals("modId")) modId = m.group(2);
                else side = m.group(2);
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 6½. перенос с ПК

    /**
     * Мир, настройки и плагины локального сервера — одним архивом (см. {@link ServerMigration}).
     * Архив распаковывается от имени minecraft, а не root: в нём пользовательские файлы, и даже
     * странное имя внутри не должно суметь записать что-то вне папки сервера с правами root.
     * Повтор шага безопасен: распаковка поверх просто перезапишет те же файлы.
     */
    private void migrate(Run r) throws VpsException, IOException {
        r.step(Step.MIGRATE, null);
        Path src = r.plan.migrateDir();
        if (src == null) {
            r.done("не нужно — новый сервер");
            return;
        }
        if (!Files.isDirectory(src)) throw r.fail("Папка локального сервера не найдена: " + src);
        ServerMigration.Summary sum = ServerMigration.scan(src);
        Path tmp = Files.createTempFile("blauncher-migrate-", ".tar.gz");
        try {
            long total = Math.max(1, sum.bytes());
            long[] shown = {0};
            r.info("упаковываю " + mb(sum.bytes()) + " (" + sum.files() + " файлов)");
            ServerMigration.writeArchive(src, tmp, done -> {
                if (done - shown[0] >= 16L * 1024 * 1024) {
                    shown[0] = done;
                    r.info("упаковываю " + mb(done) + " из " + mb(total));
                }
            });
            long size = Files.size(tmp);

            String dirRel = STAGING + "/" + r.id;
            String fileRel = dirRel + "/migrate.tar.gz";
            ChannelSftp sftp = r.s.sftp();
            try {
                r.stagingAbs = sftp.getHome() + "/" + STAGING + "/" + r.id;
                mkdirs(sftp, dirRel);
                long[] sent = {0, 0};
                SftpProgressMonitor mon = new SftpProgressMonitor() {
                    @Override public void init(int op, String s, String d, long max) {}
                    @Override public boolean count(long n) {
                        sent[0] += n;
                        if (sent[0] - sent[1] >= 4L * 1024 * 1024) {
                            sent[1] = sent[0];
                            r.info("загружаю на VPS " + mb(sent[0]) + " из " + mb(size));
                        }
                        return true;
                    }
                    @Override public void end() {}
                };
                try (InputStream in = Files.newInputStream(tmp)) {
                    sftp.put(in, fileRel, mon, ChannelSftp.OVERWRITE);
                }
                sftp.chmod(0600, fileRel);
            } catch (SftpException e) {
                throw r.fail("Не удалось загрузить архив сервера на VPS: " + e.getMessage());
            } finally {
                sftp.disconnect();
            }

            r.info("распаковываю на VPS");
            String staged = r.stagingAbs + "/migrate.tar.gz";
            String arch = BASE_DIR + "/.migrate-" + r.id + ".tar.gz";
            String cmd = "install -o " + MC_USER + " -g " + MC_USER + " -m 600 " + Shell.quote(staged) + " " + Shell.quote(arch)
                    + " && rm -f " + Shell.quote(staged)
                    + " && runuser -u " + MC_USER + " -- tar -xzf " + Shell.quote(arch) + " -C " + Shell.quote(r.dir)
                    + " --no-same-owner --no-same-permissions"
                    + "; rc=$?; rm -f " + Shell.quote(arch) + "; exit $rc";
            r.rootOk(cmd, DOWNLOAD, "Не удалось распаковать архив сервера на VPS");

            StringBuilder what = new StringBuilder("перенесено ").append(mb(sum.bytes()));
            if (!sum.worlds().isEmpty()) what.append(", миры: ").append(String.join(", ", sum.worlds()));
            if (sum.plugins()) what.append(", плагины");
            r.done(what.toString());
        } finally {
            try { Files.deleteIfExists(tmp); } catch (IOException ignored) { }
        }
    }

    private static String mb(long bytes) {
        if (bytes < 1024L * 1024 * 1024) return Math.max(1, bytes / (1024 * 1024)) + " МБ";
        return String.format(java.util.Locale.ROOT, "%.1f ГБ", bytes / (1024.0 * 1024 * 1024)).replace('.', ',');
    }

    // ---------------------------------------------------------------- 7. EULA и настройки

    private void config(Run r) throws VpsException, IOException {
        r.step(Step.CONFIG, "eula.txt, server.properties, RCON");
        writeFile(r, r.dir + "/eula.txt",
                ("# EULA Minecraft (https://aka.ms/MinecraftEULA) принята владельцем сервера в BLauncher "
                        + LocalDate.now() + "\neula=true\n").getBytes(StandardCharsets.UTF_8), MC_USER, "640");

        // server.properties содержит пароль RCON — его текст никуда, кроме VPS, не уходит.
        VpsSession.ExecResult cur = r.root("cat " + Shell.quote(r.dir + "/server.properties") + " 2>/dev/null", SHORT);
        boolean fresh = !cur.ok();
        Map<String, String> props = parseProperties(fresh ? "" : cur.stdout());

        int existingRcon = parseInt(props.get("rcon.port"));
        Set<Integer> busy = listeningPorts(r);
        // RCON остановленного соседа порт не слушает, но он занят: иначе два сервера получили бы
        // 25575, и у второго не работала бы консоль.
        if (r.capacity != null) busy.addAll(r.capacity.takenPorts(r.id));
        if (existingRcon > 0 && existingRcon != r.plan.port() && "true".equals(props.get("enable-rcon"))) {
            r.rconPort = existingRcon;   // повтор установки — порт уже наш
        } else {
            int p = 25575;
            while (busy.contains(p) || p == r.plan.port()) p++;
            r.rconPort = p;
        }

        String rconPassword = secrets.get(secretRconName(r.id));
        if (rconPassword == null) {
            rconPassword = randomPassword(32);
            // Сначала в хранилище, потом на VPS: если упадём между ними, повтор возьмёт этот же пароль.
            secrets.put(secretRconName(r.id), rconPassword);
        }

        if (fresh) {
            // Как у локального сервера (GameLaunchService.writeServerDefaultsIfMissing):
            // online-mode=false — игроки BLauncher заходят с офлайн-аккаунтами.
            props.put("online-mode", "false");
            props.put("white-list", "false");
            props.put("motd", "A BLauncher server");
        }
        // Плитки из формы установки (17.10.2026). Значения уже проверены и экранированы
        // ServerProps.cleanAll; ключи — закрытый список без порта и RCON, а их ниже всё равно
        // ставит лаунчер. Перевод строки в значении — второй замок от подмены соседних ключей.
        for (Map.Entry<String, String> e : r.plan.props().entrySet()) {
            String v = e.getValue();
            ServerProps.Field f = ServerProps.field(e.getKey());
            if (f == null || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) continue;
            // До 1.13 режим и сложность в файле — номер; «creative» такой сервер не понимает и
            // молча ставит выживание (найдено ревью Android-копии 18.10.2026). Порядок choices
            // совпадает с номерами Minecraft.
            if (f.kind() == ServerProps.Kind.CHOICE && f.choices().contains(v) && before113(r.plan.mcVersion())) {
                v = String.valueOf(f.choices().indexOf(v));
            }
            props.put(e.getKey(), v);
        }
        if (r.plan.migrateDir() != null) {
            // С ПК мог приехать server-ip=192.168.x.x — на VPS такого адреса нет, и сервер не
            // открыл бы порт вовсе. Пусто = слушать все адреса VPS.
            props.put("server-ip", "");
        }
        props.put("server-port", String.valueOf(r.plan.port()));
        props.put("enable-rcon", "true");
        props.put("rcon.port", String.valueOf(r.rconPort));
        props.put("rcon.password", rconPassword);
        props.put("broadcast-rcon-to-ops", "false");

        StringBuilder sb = new StringBuilder("# Настройки записаны BLauncher; менять можно, лаунчер трогает только порты и RCON\n");
        for (Map.Entry<String, String> e : props.entrySet()) sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        writeFile(r, r.dir + "/server.properties", sb.toString().getBytes(StandardCharsets.UTF_8), MC_USER, "640");

        if (r.plan.core() == Core.FORGE || r.plan.core() == Core.NEOFORGE) {
            // run.sh берёт память отсюда (java @user_jvm_args.txt …).
            String args = "# Память сервера — записано BLauncher\n-Xms" + xms(r) + "M\n-Xmx" + r.plan.ramMb() + "M\n";
            writeFile(r, r.dir + "/user_jvm_args.txt", args.getBytes(StandardCharsets.UTF_8), MC_USER, "640");
        }
        r.done("EULA принята, порт " + r.plan.port() + ", RCON на " + r.rconPort + " (пароль — только на этом ПК и на VPS)");
    }

    public static String secretRconName(String serverId) {
        return "vps/" + serverId + "/rcon";
    }

    /** Версия Minecraft старше 1.13 («1.12.2», «1.8.9»); снапшоты и «26.x» — нет. */
    static boolean before113(String mc) {
        if (mc == null) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^1\\.(\\d+)").matcher(mc.trim());
        return m.find() && Integer.parseInt(m.group(1)) < 13;
    }

    static Map<String, String> parseProperties(String text) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#") || l.startsWith("!")) continue;
            int eq = l.indexOf('=');
            if (eq <= 0) continue;
            out.put(l.substring(0, eq).trim(), l.substring(eq + 1).trim());
        }
        return out;
    }

    private String randomPassword(int len) {
        String abc = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(abc.charAt(random.nextInt(abc.length())));
        return sb.toString();
    }

    private static int xms(Run r) {
        return Math.min(1024, r.plan.ramMb());
    }

    // ---------------------------------------------------------------- 8. служба

    private void service(Run r) throws VpsException {
        r.step(Step.SERVICE, r.service + ".service");
        String spec = launchSpec(r);
        if (spec == null) throw r.fail("Не найден файл запуска сервера");
        String execStart;
        if (spec.startsWith("script:")) {
            execStart = "/bin/sh " + r.dir + "/" + spec.substring("script:".length()) + " nogui";
        } else {
            String jar = spec.substring("jar:".length());
            if (!JAR_NAME.matcher(jar).matches()) throw r.fail("Странное имя jar сервера: " + jar);
            execStart = r.javaBin + " -Xms" + xms(r) + "M -Xmx" + r.plan.ramMb() + "M -jar " + jar + " nogui";
        }
        String javaHome = JAVA_DIR + "/temurin-" + r.javaMajor;
        // Описание — без имени сервера из лаунчера: юнит-файл не место для пользовательского текста.
        String unit = "# Создано BLauncher. Управляется из лаунчера (сервер " + r.id + ").\n"
                // По этой метке VpsCapacity считает память сервера: у Forge -Xmx не в юните, а в user_jvm_args.txt.
                + "# BLauncher-ram=" + r.plan.ramMb() + "\n"
                + "[Unit]\n"
                + "Description=Minecraft server " + r.id + " (BLauncher)\n"
                + "After=network-online.target\n"
                + "Wants=network-online.target\n"
                // Не дольше 5 падений за 10 минут — дальше systemd перестаёт перезапускать, а не
                // крутит падающий сервер бесконечно.
                + "StartLimitIntervalSec=600\n"
                + "StartLimitBurst=5\n\n"
                + "[Service]\n"
                + "Type=simple\n"
                + "User=" + MC_USER + "\n"
                + "Group=" + MC_USER + "\n"
                + "WorkingDirectory=" + r.dir + "\n"
                + "Environment=JAVA_HOME=" + javaHome + "\n"
                + "Environment=PATH=" + javaHome + "/bin:/usr/local/bin:/usr/bin:/bin\n"
                // Закрыть порт RCON снаружи перед каждым запуском: правила nft/iptables не переживают
                // перезагрузку. «+» — от root, хотя служба от minecraft; «-» — сбой не мешает запуску.
                + "ExecStartPre=-+" + RCON_GUARD + " " + r.rconPort + " " + r.plan.port() + "\n"
                + "ExecStart=" + execStart + "\n"
                + "Restart=on-failure\n"
                + "RestartSec=10\n"
                // SIGTERM: Minecraft сохраняет миры в shutdown hook и выходит с 143 — это не сбой.
                + "SuccessExitStatus=0 143\n"
                + "TimeoutStopSec=120\n"
                + limits(r.plan)
                + "NoNewPrivileges=true\n"
                + "ProtectSystem=full\n"
                + "ProtectHome=true\n"
                + "PrivateTmp=true\n\n"
                + "[Install]\n"
                + "WantedBy=multi-user.target\n";
        writeFile(r, "/etc/systemd/system/" + r.service + ".service", unit.getBytes(StandardCharsets.UTF_8), "root", "644");
        r.rootOk("systemctl daemon-reload && systemctl enable " + Shell.quote(r.service) + " 2>&1",
                SHORT, "systemd не принял службу");
        r.done("автозапуск включён, перезапуск при падении");
    }

    /**
     * Ограничения cgroup (26.10.2026, несколько серверов на VPS). MemoryMax — с большим запасом над
     * -Xmx: это не обычный предел, а забор между соседями. Утечка нативной памяти в одном сервере
     * (её -Xmx не держит, у модов бывает) убьёт только его, и systemd его перезапустит, — а не
     * соседа, которого выбрал бы OOM killer ядра. CPUQuota — только если человек сам ограничил ядра.
     */
    static String limits(Plan plan) {
        int xmx = plan.ramMb();
        String out = "MemoryMax=" + (xmx + Math.max(768, xmx / 2)) + "M\n";
        if (plan.cpuCores() > 0) out += "CPUQuota=" + (plan.cpuCores() * 100) + "%\n";
        return out;
    }

    // ---------------------------------------------------------------- 9. брандмауэр

    /**
     * Скрипт, закрывающий порт RCON для всего, кроме самого сервера (26.10.2026, проверка
     * безопасности). Minecraft слушает RCON на всех адресах (server-ip пустой), а закрывали его
     * только через ufw — на свежем VPS ufw обычно выключен, а в Debian его нет, и консоль сервера
     * смотрела в интернет. Лаунчеру RCON нужен лишь через SSH-туннель на 127.0.0.1, поэтому снаружи
     * порт закрыт всегда: nft (таблица inet blauncher — IPv4 и IPv6 сразу), иначе iptables/ip6tables.
     * Второй аргумент — игровой порт этого сервера: если он раньше был чьим-то портом RCON (сервер
     * удалили, порт отдали другому), его правило снимается, чтобы не закрыть игру.
     * Выход 3 — нечем закрыть (нет ни nft, ни iptables).
     */
    private static final String RCON_GUARD = BASE_DIR + "/bin/bl-rcon-guard";
    private static final String RCON_GUARD_SCRIPT = "#!/bin/sh\n"
            + "# Создано BLauncher: порт RCON — только для 127.0.0.1 (лаунчер ходит в него через SSH-туннель).\n"
            + "R=\"$1\"; G=\"$2\"\n"
            + "case \"$R\" in ''|*[!0-9]*) exit 2;; esac\n"
            + "case \"$G\" in ''|*[!0-9]*) G=;; esac\n"
            + "if command -v nft >/dev/null 2>&1; then\n"
            + "  nft add table inet blauncher || exit 1\n"
            + "  nft add chain inet blauncher input '{ type filter hook input priority -10; policy accept; }' || exit 1\n"
            + "  nft add set inet blauncher rcon '{ type inet_service; }' || exit 1\n"
            + "  nft list chain inet blauncher input | grep -q '@rcon' || nft add rule inet blauncher input iifname != lo tcp dport @rcon drop || exit 1\n"
            + "  [ -n \"$G\" ] && nft delete element inet blauncher rcon \"{ $G }\" 2>/dev/null\n"
            + "  nft add element inet blauncher rcon \"{ $R }\" || exit 1\n"
            + "  exit 0\n"
            + "fi\n"
            + "found=\n"
            + "for T in iptables ip6tables; do\n"
            + "  command -v $T >/dev/null 2>&1 || continue\n"
            + "  found=1\n"
            + "  [ -n \"$G\" ] && $T -D INPUT -p tcp --dport \"$G\" ! -i lo -j DROP 2>/dev/null\n"
            + "  $T -C INPUT -p tcp --dport \"$R\" ! -i lo -j DROP 2>/dev/null || $T -I INPUT -p tcp --dport \"$R\" ! -i lo -j DROP || exit 1\n"
            + "done\n"
            + "[ -n \"$found\" ] || exit 3\n"
            + "exit 0\n";

    private void firewall(Run r) throws VpsException {
        r.step(Step.FIREWALL, "порт " + r.plan.port() + "/tcp");
        r.rootOk("install -d -m 755 " + Shell.quote(BASE_DIR + "/bin"), SHORT, "Не удалось создать " + BASE_DIR + "/bin");
        writeFile(r, RCON_GUARD, RCON_GUARD_SCRIPT.getBytes(StandardCharsets.UTF_8), "root", "755");
        VpsSession.ExecResult g = r.root(RCON_GUARD + " " + r.rconPort + " " + r.plan.port(), SHORT);
        String rcon = g.ok() ? "RCON (" + r.rconPort + ") закрыт снаружи"
                : g.exitCode() == 3 ? "на VPS нет ни nft, ни iptables — закройте порт RCON " + r.rconPort + " в брандмауэре хостера"
                : "закрыть порт RCON " + r.rconPort + " не удалось — закройте его в брандмауэре хостера";
        if (!r.s.exec("command -v ufw >/dev/null 2>&1 || test -x /usr/sbin/ufw", SHORT).ok()) {
            r.done("ufw не установлен — если у хостера есть свой брандмауэр, откройте в его панели порт " + r.plan.port() + "; " + rcon);
            return;
        }
        VpsSession.ExecResult st = r.root("ufw status", SHORT);
        if (!st.ok() || !st.stdout().contains("Status: active")) {
            r.done("ufw выключен — игровой порт открыт; " + rcon);
            return;
        }
        r.rootOk("ufw allow " + r.plan.port() + "/tcp comment 'BLauncher Minecraft'", SHORT, "ufw не открыл порт");
        r.done("в ufw открыт порт " + r.plan.port() + "/tcp; " + rcon);
    }

    // ---------------------------------------------------------------- 10. запуск

    private void start(Run r) throws VpsException, InterruptedException {
        r.step(Step.START, "запускаю службу");
        long since = parseLong(r.s.exec("date +%s", SHORT).stdout());
        if (since <= 0) throw r.fail("Не удалось прочитать время на VPS");
        String q = Shell.quote(r.service);
        // NRestarts ручной restart не обнуляет: после прошлой упавшей попытки он уже больше нуля, и
        // сравнение «> 0» объявляло падением совершенно здоровый старт (тест 07.10.2026).
        int restartsBefore = parseInt(r.s.exec("systemctl show -p NRestarts --value " + q, SHORT).stdout());
        r.rootOk("systemctl reset-failed " + q + " 2>/dev/null; systemctl restart " + q, SHORT, "Служба не запустилась");

        String sinceArg = Shell.quote("@" + since);
        String poll = "systemctl is-active " + q + "; systemctl show -p NRestarts --value " + q + "; "
                + "journalctl -u " + q + " --since " + sinceArg + " -o cat --no-pager | grep -c -F 'Done ('; "
                + "journalctl -u " + q + " --since " + sinceArg + " -o cat --no-pager -n 1";
        long t0 = System.nanoTime();
        while (true) {
            Thread.sleep(3000);
            long secs = (System.nanoTime() - t0) / 1_000_000_000L;
            String[] lines = (r.root(poll, SHORT).stdout() + "\n\n\n\n").split("\n", -1);
            String state = lines[0].trim();
            int restarts = parseInt(lines[1].trim()) - restartsBefore;
            int done = parseInt(lines[2].trim());
            String last = stripAnsi(lines[3]).trim();
            if (done > 0 && "active".equals(state)) {
                r.done("сервер работает: порт " + r.plan.port() + ", запустился за " + secs + " с");
                return;
            }
            if (restarts > 0 || "failed".equals(state) || "inactive".equals(state)) {
                // Сначала строки с причиной (первые исключения), потом хвост: по одному хвосту
                // стектрейса не понять, какой мод уронил сервер.
                String journal = "journalctl -u " + q + " --since " + sinceArg + " -o cat --no-pager";
                String log = stripAnsi(r.root("{ " + journal + " | grep -m 4 -E 'Exception|Caused by|ERROR|FATAL|Error:'; echo '…'; "
                        + journal + " -n 4; }", SHORT).stdout()).trim();
                // Падающий сервер не крутим: Restart=on-failure перезапускал бы его снова и снова.
                r.root("systemctl stop " + q, SHORT);
                throw r.fail("Сервер упал при запуске. Из журнала:\n" + clip(log, 1500));
            }
            if (secs > FIRST_START.toSeconds()) {
                throw r.fail("Сервер не дошёл до «Done» за " + FIRST_START.toMinutes() + " минут. Последняя строка: " + clip(last, 300));
            }
            r.info("запускается… " + secs + " с" + (last.isEmpty() ? "" : " · " + clip(last, 140)));
        }
    }

    // ---------------------------------------------------------------- файлы

    /**
     * Пишет файл на VPS от root: SFTP во временную папку пользователя SSH, затем {@code install}
     * с нужным владельцем и правами. Содержимое не проходит через командную строку и stdin.
     */
    private void writeFile(Run r, String absPath, byte[] content, String owner, String mode) throws VpsException {
        if (!absPath.startsWith("/") || absPath.contains("..")) throw new IllegalArgumentException("Путь: " + absPath);
        if (!mode.matches("[0-7]{3,4}")) throw new IllegalArgumentException("Права: " + mode);
        Shell.requireUnixUser(owner);
        String rel = STAGING + "/" + r.id + "/files";
        String name = "f" + Long.toHexString(random.nextLong());
        ChannelSftp sftp = r.s.sftp();
        try {
            r.stagingAbs = sftp.getHome() + "/" + STAGING + "/" + r.id;
            mkdirs(sftp, rel);
            sftp.put(new ByteArrayInputStream(content), rel + "/" + name, ChannelSftp.OVERWRITE);
            sftp.chmod(0600, rel + "/" + name);
        } catch (SftpException e) {
            throw r.fail("Не удалось передать файл на VPS: " + e.getMessage());
        } finally {
            sftp.disconnect();
        }
        String src = r.stagingAbs + "/files/" + name;
        r.rootOk("install -o " + owner + " -g " + owner + " -m " + mode + " " + Shell.quote(src) + " " + Shell.quote(absPath)
                + "; rc=$?; rm -f " + Shell.quote(src) + "; exit $rc", SHORT, "Не удалось записать " + absPath);
    }

    private static void mkdirs(ChannelSftp sftp, String rel) throws SftpException {
        String path = "";
        for (String part : rel.split("/")) {
            path = path.isEmpty() ? part : path + "/" + part;
            if (statOrNull(sftp, path) == null) {
                sftp.mkdir(path);
                sftp.chmod(0700, path);
            }
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

    /** Временную папку убираем всегда: в ней могли остаться копии server.properties с паролем RCON. */
    private void cleanupStaging(Run r) {
        if (r.stagingAbs == null) return;
        try {
            // rmdir родителя — только если пуст: там может идти установка другого сервера.
            String parent = r.stagingAbs.substring(0, r.stagingAbs.lastIndexOf('/'));
            r.s.exec("rm -rf " + Shell.quote(r.stagingAbs) + "; rmdir " + Shell.quote(parent) + " 2>/dev/null; true", SHORT);
        } catch (VpsException ignored) {
            // Связь пропала — файлы в staging имеют права 600 и лежат в домашней папке пользователя.
        }
    }

    // ---------------------------------------------------------------- мелочи

    private static String tail(VpsSession.ExecResult r) {
        String err = r.stderr().isBlank() ? r.stdout() : r.stderr();
        err = err.strip();
        if (err.isEmpty()) return " (код " + r.exitCode() + ")";
        return ": " + clip(err, 600);
    }

    /** NeoForge красит журнал escape-кодами — в окне лаунчера они только мусор. */
    static String stripAnsi(String s) {
        return s == null ? "" : s.replaceAll("\u001B\\[[0-9;]*[A-Za-z]", "");
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : "…" + s.substring(s.length() - max);
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); } catch (RuntimeException e) { return 0; }
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s == null ? "" : s.trim()); } catch (RuntimeException e) { return 0; }
    }
}
