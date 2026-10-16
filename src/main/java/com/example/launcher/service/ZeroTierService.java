package com.example.launcher.service;

import com.example.launcher.service.GameLaunchService.GameOutputCallback;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Drives ZeroTier One — the launcher's own "play together" overlay network (the open-source,
 * embeddable replacement for the closed Radmin VPN, which has no API for joining networks).
 *
 * <p><b>Why ZeroTier:</b> it is Apache-2.0 licensed, needs no self-hosted server (its free
 * public controller at my.zerotier.com coordinates networks), installs silently as a Windows
 * service (verified: {@code msiexec /i "ZeroTier One.msi" /qn /norestart}), and exposes both a
 * CLI ({@code zerotier-cli join/leave}, verified working) and a local JSON API on
 * {@code 127.0.0.1:9993} authenticated by a token file that a normal (non-elevated) user can
 * read (verified on a real install: {@code %ProgramData%\ZeroTier\One\authtoken.secret}).
 *
 * <p><b>Model:</b> a ZeroTier "network" is exactly the "room" the launcher UI talks about.
 * Networks are either public (anyone who knows the 16-hex ID joins instantly — "комната без
 * пароля") or private (the owner approves each member in the controller — "комната с паролем").
 * Every member gets a virtual-interface IP (typically 10.x.x.x), which is the address friends
 * type into Minecraft to join a game hosted by anyone in the room — no dedicated launcher server
 * required. Creating a room uses the ZeroTier Central API and needs a one-time API token from the
 * owner's my.zerotier.com account (same UX pattern as the playit.gg secret key elsewhere).
 *
 * <p>Join/leave go through the CLI (the local API's POST /network returned 404 on the tested
 * 1.16.2 daemon; the CLI's "200 join OK" is the verified path). Status reads use the local JSON
 * API, which is cleaner to parse than the CLI's space-separated listnetworks output.
 */
public class ZeroTierService {

    public static final String MSI_URL = "https://download.zerotier.com/dist/ZeroTier%20One.msi";
    public static final String CENTRAL_API = "https://api.zerotier.com/api/v1/network";

    private static final String SERVICE_NAME = "ZeroTierOneService";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(15);

    private final Path gameDir;

    public ZeroTierService(Path gameDir) {
        this.gameDir = gameDir;
    }

    /** Where the installer is kept so it never needs re-downloading. */
    public Path installerPath() {
        return gameDir.resolve("zerotier").resolve("ZeroTier One.msi");
    }

    // ---------- detection ----------

    /** True if the service binary or CLI is present on this PC. */
    public boolean isInstalled() {
        return cliPath() != null || Files.exists(Path.of(dataDir(), "zerotier-one_x64.exe"));
    }

    /** Full path to zerotier-cli.bat, or null if ZeroTier isn't installed. */
    public String cliPath() {
        String pf86 = System.getenv("ProgramFiles(x86)");
        String pf64 = System.getenv("ProgramFiles");
        for (String base : new String[]{pf86, pf64}) {
            if (base == null || base.isBlank()) continue;
            Path p = Path.of(base, "ZeroTier", "One", "zerotier-cli.bat");
            if (Files.exists(p)) return p.toString();
        }
        return null;
    }

    private String dataDir() {
        String pd = System.getenv("ProgramData");
        return (pd != null && !pd.isBlank()) ? pd + "\\ZeroTier\\One" : "C:\\ProgramData\\ZeroTier\\One";
    }

    private Path tokenFile() {
        return Path.of(dataDir(), "authtoken.secret");
    }

    private Path portFile() {
        return Path.of(dataDir(), "zerotier-one.port");
    }

    public boolean isServiceRunning() {
        try {
            return runCommandCapture("sc", "query", SERVICE_NAME).contains("RUNNING");
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- install ----------

    /** Наименьший осмысленный размер ZeroTier One.msi — он около 12 МБ, так что 4 отсекают обрыв. */
    private static final long MIN_MSI_BYTES = 4L * 1024 * 1024;

    /**
     * Скачивает официальный установщик в игровую папку. Повторный вызов ничего не делает,
     * если уже лежащий файл похож на настоящий MSI.
     *
     * <p>Раньше признаком «уже скачан» было одно существование файла. Обрыв связи оставлял
     * обрезанный MSI, и msiexec падал на нём при каждой попытке, а перекачать его было нельзя:
     * лаунчер сообщал «Установщик ZeroTier уже скачан» и шёл дальше. Выход был один — удалить
     * файл руками, о чём пользователь знать не мог. Теперь негодный файл перекачивается сам.
     */
    public void downloadInstaller(GameOutputCallback callback) throws IOException {
        if (looksLikeMsi(installerPath())) {
            callback.onOutput("[ZT] Установщик ZeroTier уже скачан.");
            return;
        }
        if (Files.exists(installerPath())) {
            callback.onOutput("[ZT] Скачанный ранее установщик негодный — качаю заново.");
            Files.deleteIfExists(installerPath());
        }
        callback.onOutput("[ZT] Скачиваю ZeroTier One (~12 МБ)...");
        long written = Http.download(MSI_URL, installerPath(), Map.of(), Duration.ofMinutes(3));
        if (written < 0) {
            throw new IOException("Не удалось скачать установщик ZeroTier: " + MSI_URL);
        }
        if (!looksLikeMsi(installerPath())) {
            Files.deleteIfExists(installerPath());
            throw new IOException("Установщик ZeroTier скачался повреждённым ("
                    + written + " байт). Проверьте интернет и повторите.");
        }
        callback.onOutput("[ZT] Установщик сохранён: " + installerPath());
    }

    /**
     * Проверяет, что файл — действительно MSI, а не обрезок и не страница ошибки.
     *
     * <p>MSI — это документ OLE2, он всегда начинается с одной и той же подписи
     * {@code D0 CF 11 E0 A1 B1 1A E1}. Хеша для этого файла взять негде (он качается прямо
     * с сайта ZeroTier), поэтому проверяем подпись и размер — этого достаточно, чтобы отличить
     * целый файл от обрыва или подсунутого HTML.
     */
    private boolean looksLikeMsi(Path path) {
        try {
            if (!Files.isRegularFile(path)) return false;
            if (Files.size(path) < MIN_MSI_BYTES) return false;
            byte[] magic = new byte[8];
            try (java.io.InputStream in = Files.newInputStream(path)) {
                if (in.read(magic) != 8) return false;
            }
            byte[] ole2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
                           (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
            return java.util.Arrays.equals(magic, ole2);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Silent install of the ZeroTier One service + TAP driver. Needs one-time administrator
     * rights; routes the single privileged msiexec through a UAC prompt instead of requiring the
     * whole launcher to run elevated (same pattern as PlayitService's net-start helper).
     */
    public void install(GameOutputCallback callback) throws IOException, InterruptedException {
        if (isInstalled()) {
            callback.onOutput("[ZT] ZeroTier уже установлен.");
            return;
        }
        downloadInstaller(callback);
        callback.onOutput("[ZT] Устанавливаю ZeroTier — подтвердите запрос администратора (UAC)...");
        String msi = installerPath().toAbsolutePath().toString();
        // "if (-not $p) { exit 1602 }" — ключевая часть. Если запуск с повышением прав вообще
        // не состоялся (человек отклонил UAC, заблокировал антивирус, запретила политика), то
        // Start-Process ничего не возвращает, $p остаётся $null, и прежний "exit $p.ExitCode"
        // завершался с кодом 0 — лаунчер сообщал об установке, которой не было. Дальше сразу
        // падал запуск службы с кодом 2 («служба не найдена»), и человек видел невнятную
        // ошибку вместо «вы не подтвердили UAC». 1602 — это и есть «отменено пользователем».
        String psCommand = "$p = Start-Process msiexec -ArgumentList '/i','\"" + msi
                + "\"','/qn','/norestart' -Verb RunAs -Wait -PassThru -ErrorAction SilentlyContinue; "
                + "if (-not $p) { exit 1602 } else { exit $p.ExitCode }";
        // Таймаут обязателен. Голый waitFor() держал поток общего пула лаунчера столько, сколько
        // открыт диалог UAC, то есть бесконечно, если человек отошёл от компьютера. Четыре таких
        // операции подряд исчерпывали пул из четырёх потоков, и после этого молча перестаёт
        // работать всё фоновое — включая запуск игры. Пять минут с запасом хватает, чтобы
        // прочитать запрос и нажать «Да».
        ElevatedResult res = runElevated(psCommand, Duration.ofMinutes(5), "установка ZeroTier");
        String output = res.output();
        int exit = res.exitCode();
        if (!output.isEmpty()) {
            // Вывод процесса раньше просто выбрасывался (readAllBytes без использования), а в нём
            // как раз лежит настоящая причина отказа — например текст ошибки Start-Process.
            callback.onOutput("[ZT] msiexec: " + output.replace('\n', ' ').replace('\r', ' '));
        }

        // 3010 и 1641 — это УСПЕХ, которому нужна перезагрузка. Для ZeroTier с его TAP-драйвером
        // это обычный исход, особенно когда прежняя версия драйвера ещё загружена, а ключ
        // /norestart делает его ещё вероятнее. Прежнее «любой ненулевой код — провал» заставляло
        // человека жать «Включить» снова и снова вместо того, чтобы перезагрузиться.
        if (exit == 3010 || exit == 1641) {
            callback.onOutput("[ZT] ZeroTier установлен, но для работы нужна перезагрузка Windows.");
            return;
        }
        if (exit != 0) {
            discardInstallerIfCorrupt(exit);
            throw new IOException("Установка ZeroTier не завершилась: " + msiExitMessage(exit, output));
        }
        callback.onOutput("[ZT] ZeroTier установлен.");
    }

    /**
     * Человеческое объяснение кода возврата msiexec с конкретным следующим шагом.
     *
     * <p>Раньше к любому коду приписывалось «Если появился запрос UAC — нажмите „Да"». Это
     * сбивало с толку: в присланном логе человек четыре раза нажимал «Да» при коде 1639, который
     * означает «неверная командная строка» и к UAC отношения не имеет вовсе.
     */
    private String msiExitMessage(int exit, String output) {
        String hint;
        switch (exit) {
            case 1602:
                hint = "запуск с правами администратора не состоялся. Нажмите «Да» в запросе UAC;"
                     + " если запроса не было — возможно, его заблокировал антивирус или политика.";
                break;
            case 1603:
                hint = "установщик Windows сообщил о неустранимой ошибке. Чаще всего помогает"
                     + " перезагрузка, а если нет — установите ZeroTier One вручную с zerotier.com.";
                break;
            case 1618:
                hint = "в системе уже идёт другая установка. Дождитесь её окончания и повторите.";
                break;
            case 1619:
            case 1620:
                hint = "файл установщика повреждён. Он будет скачан заново при следующей попытке.";
                break;
            case 1638:
                hint = "в системе уже установлена другая версия ZeroTier. Удалите её через"
                     + " «Установка и удаление программ» и повторите.";
                break;
            case 1639:
                hint = "установщик Windows не принял параметры запуска. Это не связано с UAC —"
                     + " установите ZeroTier One вручную с zerotier.com и нажмите «Включить» снова.";
                break;
            case 1625:
            case 1643:
                hint = "установку запрещает политика Windows. Нужны права администратора машины.";
                break;
            default:
                hint = "код " + exit + ". Попробуйте установить ZeroTier One вручную с zerotier.com.";
                break;
        }
        String tail = output == null || output.isBlank()
                ? ""
                : " (подробности: " + output.replace('\n', ' ').replace('\r', ' ') + ")";
        return hint + tail;
    }

    /** Результат операции, запрошенной с повышением прав. */
    private record ElevatedResult(int exitCode, String output) {}

    /**
     * Запускает powershell-команду, которая внутри поднимает процесс через UAC, и ждёт её
     * не дольше {@code timeout}. Вывод вычитывается параллельно (см. runCaptured — тот же
     * повод: иначе забитый буфер пайпа не даёт процессу завершиться).
     *
     * <p>Код 1602 («отменено пользователем») возвращается и когда время вышло: с точки зрения
     * вызывающего это тот же случай — подтверждения не было.
     */
    private ElevatedResult runElevated(String psCommand, Duration timeout, String what)
            throws IOException, InterruptedException {
        Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", psCommand)
                .redirectErrorStream(true).start();
        final StringBuilder sink = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (java.io.InputStream in = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    sink.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
            }
        }, "zt-elevated-out");
        reader.setDaemon(true);
        reader.start();

        try {
            if (!p.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroy();
                if (!p.waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroyForcibly();
                reader.join(1000);
                System.out.println("[ZT] " + what + ": не дождались ответа за "
                        + timeout.toMinutes() + " мин — считаю, что UAC не подтвердили");
                return new ElevatedResult(1602, sink.toString().trim());
            }
            reader.join(2000);
            return new ElevatedResult(p.exitValue(), sink.toString().trim());
        } catch (InterruptedException e) {
            p.destroyForcibly();
            throw e;
        }
    }

    /** Повреждённый установщик удаляем, чтобы следующая попытка начала с чистой загрузки. */
    private void discardInstallerIfCorrupt(int exit) {
        if (exit != 1619 && exit != 1620) return;
        try {
            Files.deleteIfExists(installerPath());
            System.out.println("[ZT] установщик признан повреждённым и удалён,"
                    + " следующая попытка скачает заново");
        } catch (IOException ignored) {
        }
    }

    /** Starts the ZeroTierOneService if it's installed but stopped (elevated, like install). */
    public void startService(GameOutputCallback callback) throws IOException, InterruptedException {
        if (isServiceRunning()) {
            return;
        }
        // Служба могла ещё не зарегистрироваться: MSI возвращает управление раньше, чем Windows
        // доводит до конца регистрацию службы и установку TAP-драйвера. Из-за этого "net start"
        // отвечал кодом 2 («служба не найдена»), лаунчер показывал ошибку, и человек жал
        // «Включить» по пять раз, пока не совпадало по времени. Теперь ждём появления службы.
        if (!awaitServiceRegistered(callback)) {
            throw new IOException("Служба ZeroTier так и не появилась в системе."
                    + " Если установка только что прошла — перезагрузите Windows и попробуйте снова.");
        }

        String psCommand = "$p = Start-Process -FilePath 'net' -ArgumentList 'start','" + SERVICE_NAME
                + "' -Verb RunAs -WindowStyle Hidden -PassThru -Wait -ErrorAction SilentlyContinue; "
                + "if (-not $p) { exit 1602 } else { exit $p.ExitCode }";

        // Две попытки: служба могла быть в состоянии START_PENDING или STOP_PENDING.
        int exit = 0;
        String output = "";
        for (int attempt = 1; attempt <= 2; attempt++) {
            ElevatedResult res = runElevated(psCommand, Duration.ofMinutes(2), "запуск службы ZeroTier");
            output = res.output();
            exit = res.exitCode();
            if (exit == 0 || isServiceRunning()) {
                callback.onOutput("[ZT] Служба ZeroTier запущена.");
                return;
            }
            if (attempt == 1) {
                callback.onOutput("[ZT] Служба не поднялась с первого раза, пробую ещё раз...");
                Thread.sleep(2000);
            }
        }

        String hint;
        if (exit == 1602) {
            hint = "запуск с правами администратора не состоялся — нажмите «Да» в запросе UAC.";
        } else if (exit == 2) {
            hint = "Windows не нашла службу. Похоже, установка ZeroTier не завершилась —"
                 + " попробуйте установить его заново.";
        } else if (exit == 5) {
            hint = "отказано в доступе: нужны права администратора.";
        } else {
            hint = "код " + exit + ".";
        }
        if (!output.isBlank()) {
            hint += " (подробности: " + output.replace('\n', ' ').replace('\r', ' ') + ")";
        }
        throw new IOException("Не удалось запустить службу ZeroTier: " + hint);
    }

    /**
     * Ждёт, пока служба ZeroTier появится в системе — до 30 секунд, проверяя раз в секунду.
     * Возвращает false, если так и не появилась.
     */
    private boolean awaitServiceRegistered(GameOutputCallback callback) throws InterruptedException {
        for (int i = 0; i < 30; i++) {
            if (isServiceRegistered()) return true;
            if (i == 2) callback.onOutput("[ZT] Ожидаю регистрации службы ZeroTier в Windows...");
            Thread.sleep(1000);
        }
        return isServiceRegistered();
    }

    /** Служба заведена в системе (не обязательно запущена). */
    public boolean isServiceRegistered() {
        try {
            String out = runCommandCapture("sc", "query", SERVICE_NAME);
            // Нет службы — sc пишет «указанная служба не установлена» и код 1060.
            return out.contains("STATE") || out.contains("СОСТОЯНИЕ") || out.contains("RUNNING");
        } catch (Exception e) {
            return false;
        }
    }

    // ---------- local API (reads) ----------

    private String apiToken() throws IOException {
        return Files.readString(tokenFile()).trim();
    }

    private int apiPort() {
        try {
            return Integer.parseInt(Files.readString(portFile()).trim());
        } catch (Exception e) {
            return 9993;
        }
    }

    private Http.Response apiGet(String path) throws IOException {
        return Http.get("http://127.0.0.1:" + apiPort() + path,
                Map.of("X-ZT1-Auth", apiToken()), HTTP_TIMEOUT);
    }

    /** True when the daemon is installed, running AND connected to ZeroTier's planet. */
    public boolean isOnline() {
        try {
            Http.Response r = apiGet("/status");
            if (!r.ok()) return false;
            JsonObject o = JsonParser.parseString(r.text()).getAsJsonObject();
            return o.has("online") && o.get("online").getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    /** "address version ONLINE/OFFLINE" for the status line, or null if the daemon is unreachable. */
    public String getStatusText() {
        try {
            Http.Response r = apiGet("/status");
            if (!r.ok()) return null;
            JsonObject o = JsonParser.parseString(r.text()).getAsJsonObject();
            String addr = o.has("address") ? o.get("address").getAsString() : "?";
            String ver = o.has("version") ? o.get("version").getAsString() : "?";
            boolean online = o.has("online") && o.get("online").getAsBoolean();
            return addr + " · v" + ver + (online ? " · ONLINE" : " · OFFLINE");
        } catch (Exception e) {
            return null;
        }
    }

    /** One joined "room" as reported by the local API. */
    public static class NetworkInfo {
        public String id = "";
        public String name = "";
        public String status = "";
        public String type = "";
        /** true — закрытая комната (владелец одобряет вход), false — открытая (вход сразу). */
        public boolean privateRoom;
        public List<String> ips = new ArrayList<>();
    }

    public List<NetworkInfo> listNetworks() throws IOException {
        Http.Response r = apiGet("/network");
        if (!r.ok()) {
            throw new IOException("ZeroTier API error: HTTP " + r.status());
        }
        JsonArray arr = JsonParser.parseString(r.text()).getAsJsonArray();
        List<NetworkInfo> out = new ArrayList<>();
        for (var el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            NetworkInfo n = new NetworkInfo();
            n.id = o.has("id") ? o.get("id").getAsString() : "";
            n.name = (o.has("name") && !o.get("name").isJsonNull()) ? o.get("name").getAsString() : "";
            n.status = o.has("status") ? o.get("status").getAsString() : "";
            n.type = o.has("type") ? o.get("type").getAsString() : "";
            n.privateRoom = o.has("private") && !o.get("private").isJsonNull()
                    && o.get("private").getAsBoolean();
            if (o.has("assignedAddresses") && o.get("assignedAddresses").isJsonArray()) {
                for (var a : o.getAsJsonArray("assignedAddresses")) {
                    n.ips.add(a.getAsString());
                }
            }
            out.add(n);
        }
        return out;
    }

    /**
     * The first real (non-link-local) IP the local node holds on any joined room — the address
     * friends connect to. Returns null when nothing is configured yet.
     */
    public String getMyVpnIp() throws IOException {
        for (NetworkInfo n : listNetworks()) {
            for (String ip : n.ips) {
                String host = ip.contains("/") ? ip.substring(0, ip.indexOf('/')) : ip;
                if (!host.startsWith("169.254.") && !host.startsWith("127.") && !host.startsWith("fe80:")) {
                    return host;
                }
            }
        }
        return null;
    }

    // ---------- CLI actions (join / leave) ----------

    /** Joins a room by its 16-hex network ID. Returns the CLI's output line. */
    public String join(String networkId, GameOutputCallback callback) throws IOException {
        if (networkId == null || !networkId.matches("[0-9a-fA-F]{16}")) {
            throw new IOException("ID комнаты должен быть 16-значным числом (пример: a1b2c3d4e5f60718).");
        }
        // Локальный HTTP API, а не CLI: zerotier-cli на реальной машине зависал (процесс не
        // завершался — см. комментарий в leave()), и каждый вход копил висящие процессы
        // cmd/CLI в диспетчере задач. API-путь мгновенный; CLI остаётся только запасным.
        try {
            Http.Response r = Http.post("http://127.0.0.1:" + apiPort() + "/network/" + networkId,
                    "{}", "application/json", Map.of("X-ZT1-Auth", apiToken()), HTTP_TIMEOUT);
            if (r.ok()) {
                callback.onOutput("[ZT] join: ok (" + networkId + ")");
                return r.text();
            }
            callback.onOutput("[ZT] join через API не удался (HTTP " + r.status() + ") — пробую CLI");
        } catch (IOException apiFailed) {
            callback.onOutput("[ZT] join через API: " + apiFailed + " — пробую CLI");
        }
        String out = runCli("join", networkId);
        callback.onOutput("[ZT] join: " + out.trim());
        return out;
    }

    public void leave(String networkId) throws IOException {
        if (networkId == null || !networkId.matches("[0-9a-fA-F]{16}")) {
            return;
        }
        // The local HTTP API's DELETE is instant, whereas spawning zerotier-cli for "leave" was
        // observed on a real install to take tens of seconds (the CLI process lingers). Fall
        // back to the CLI only if the API route fails (very old daemon, etc.).
        try {
            Http.Response r = Http.request("DELETE",
                    "http://127.0.0.1:" + apiPort() + "/network/" + networkId,
                    null, null, Map.of("X-ZT1-Auth", apiToken()), HTTP_TIMEOUT, null);
            if (r.ok()) return;
        } catch (IOException apiFailed) {
            // fall through to the CLI
        }
        runCli("leave", networkId);
    }

    /**
     * Авто-выключение при закрытии лаунчера: покидает ВСЕ комнаты. Демон при этом сам снимает
     * виртуальные адаптеры этих комнат (проверено: после DELETE /network/{nwid} адаптер
     * исчезает), так что в системе не копятся «ZeroTier Virtual Port #N». Best-effort —
     * каждый выход независим, сеть может быть недоступна при завершении.
     */
    public void leaveAllRooms(GameOutputCallback callback) {
        try {
            for (NetworkInfo n : listNetworks()) {
                try {
                    leave(n.id);
                    callback.onOutput("[ZT] Выход из комнаты " + n.id);
                    // Если комната создана на этом ПК — удаляем её с локального контроллера.
                    // Тогда она рвётся сразу у всех участников, а не только у хоста.
                    deleteRoomLocal(n.id);
                } catch (Exception e) {
                    callback.onOutput("[ZT] Не удалось покинуть " + n.id + ": " + e.getMessage());
                }
            }
        } catch (Exception e) {
            callback.onOutput("[ZT] Не удалось получить список комнат при выключении: " + e.getMessage());
        }
    }

    /** Удаляет комнату с локального контроллера, если она создана этим узлом. Это обрывает её у
     *  всех участников сразу. Для чужих комнат ничего не делает. */
    public void deleteRoomLocal(String nwid) throws IOException {
        if (nwid == null || !nwid.matches("[0-9a-fA-F]{16}")) return;
        String own = getNodeAddress();
        if (own == null || own.length() != 10
                || !nwid.toLowerCase().startsWith(own.toLowerCase())) {
            return;
        }
        Http.Response r = Http.request("DELETE",
                "http://127.0.0.1:" + apiPort() + "/controller/network/" + nwid,
                null, null, Map.of("X-ZT1-Auth", apiToken()), HTTP_TIMEOUT, null);
        if (!r.ok()) {
            throw new IOException("Не удалось удалить комнату: HTTP " + r.status());
        }
    }

    // ---------- Central API (create room) ----------

    // ---------- Local controller (create room WITHOUT any token / account) ----------

    /**
     * The ZeroTier daemon embeds a full network controller (the {@code controller.d} folder is
     * created in %ProgramData%\ZeroTier\One\ on every Windows install). That lets the launcher
     * create rooms entirely on the host's own machine — no my.zerotier.com account, no API
     * token, nothing outside the launcher. Friends then join by the returned 16-hex ID exactly
     * like any other room. The room exists while the host's daemon runs, which is the natural
     * lifetime for a "play together now" lobby. Verified live against a real 1.16.2 daemon:
     * creating the network, joining, automatic member authorization for public rooms, and
     * 10.147.x.x managed-IP assignment all work with the canonical config below (a minimal
     * config created the network but never assigned IPs — the routes/dns/flowRules fields turn
     * out to be required for the controller to deliver a full member config).
     */
    public String createRoomLocal(String name, boolean privateRoom, GameOutputCallback callback) throws IOException {
        String nwid = generateNetworkId();

        // Уникальная подсеть для каждой комнаты: третья октета берётся из хвоста ID комнаты.
        // Один общий пул 10.147.0.x на все комнаты приводил бы к конфликту, если один и тот же
        // друг войдёт в две комнаты (одинаковый адрес на двух сетях одного интерфейса).
        int third = Integer.parseInt(nwid.substring(nwid.length() - 2), 16);
        if (third == 0) third = 1;
        String subnet = "10.147." + third;
        String poolStart = subnet + ".10";
        String poolEnd = subnet + ".254";
        String routeTarget = subnet + ".0/24";

        JsonObject cfg = new JsonObject();
        cfg.addProperty("id", nwid);
        cfg.addProperty("name", name == null || name.isBlank() ? "BLauncher комната" : name);
        cfg.addProperty("private", privateRoom);
        cfg.addProperty("enableBroadcast", true);

        JsonObject v4 = new JsonObject();
        v4.addProperty("zt", true);
        cfg.add("v4AssignMode", v4);

        JsonArray pools = new JsonArray();
        JsonObject pool = new JsonObject();
        pool.addProperty("ipRangeStart", poolStart);
        pool.addProperty("ipRangeEnd", poolEnd);
        pools.add(pool);
        cfg.add("ipAssignmentPools", pools);

        JsonArray routes = new JsonArray();
        JsonObject route = new JsonObject();
        route.addProperty("target", routeTarget);
        route.add("via", JsonNull.INSTANCE);
        routes.add(route);
        cfg.add("routes", routes);

        JsonObject dns = new JsonObject();
        dns.addProperty("domain", "");
        dns.add("servers", new JsonArray());
        cfg.add("dns", dns);

        cfg.addProperty("mtu", 2800);
        cfg.addProperty("multicastLimit", 32);
        cfg.addProperty("flowRules", "accept;");
        cfg.add("rules", new JsonArray());
        cfg.add("capabilities", new JsonArray());
        cfg.add("tags", new JsonArray());
        cfg.add("remoteTraceTarget", JsonNull.INSTANCE);

        Http.Response r = Http.post("http://127.0.0.1:" + apiPort() + "/controller/network/" + nwid,
                cfg.toString(), "application/json", Map.of("X-ZT1-Auth", apiToken()), HTTP_TIMEOUT);
        if (!r.ok()) {
            throw new IOException("Не удалось создать комнату: HTTP " + r.status());
        }
        callback.onOutput("[ZT] Комната создана на локальном контроллере: " + nwid);
        join(nwid, callback);
        // The creator must be authorized on their own room. For a private room the controller
        // starts every member (including the creator) as pending, and making the host approve
        // their own first join is confusing - authorize automatically once the member record
        // shows up (it appears a moment after the join request, so poll briefly).
        String ownAddress = getNodeAddress();
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                authorizeMember(nwid, ownAddress);
                return nwid;
            } catch (Exception retry) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        callback.onOutput("[ZT] Не удалось автоматически одобрить вход (если комната приватная, одобрите его в списке комнат).");
        return nwid;
    }

    /** 16-hex network ID owned by THIS node's controller: the node's own 10-hex address plus 6
     *  random hex. The controller only accepts networks whose ID starts with its own address. */
    public String generateNetworkId() throws IOException {
        String addr = getNodeAddress();
        if (addr == null || addr.length() != 10) {
            throw new IOException("Не удалось получить адрес узла ZeroTier.");
        }
        String suffix = String.format("%06x", java.util.concurrent.ThreadLocalRandom.current().nextInt(0x1000000));
        return addr + suffix;
    }

    public String getNodeAddress() {
        try {
            Http.Response r = apiGet("/status");
            if (!r.ok()) return null;
            JsonObject o = JsonParser.parseString(r.text()).getAsJsonObject();
            return o.has("address") ? o.get("address").getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** One member of a room waiting for the host's approval (private rooms only). */
    public static class MemberInfo {
        public String address;
        public String name;
    }    /** One joined member of a room, for the "who's online" list. */
    public static class RoomMember {
        public String address = "";
        public String name = "";
        public String ip = "";
        public boolean authorized;
        /** True for the node that created the room (its 10-hex address is the nwid prefix). */
        public boolean owner;
    }

    /**
     * All members of {@code nwid} as the controller sees them, each with its managed IP and
     * nick (empty until the host names them). The owner is the node whose 10-hex address is the
     * nwid prefix — that is how ZeroTier namespaces network IDs, so it always exists and is
     * always the creator. Returns null when this room is NOT hosted on this machine's controller.
     *
     * <p>The list route returns {@code {"<addr>":1}} (just the addresses, as numbers) on the
     * tested 1.16.2 daemon, so each member is fetched by its own detail route; some daemons put
     * the full member object right in the list, which is handled too.
     */
    public List<RoomMember> listMembers(String nwid) throws IOException {
        Http.Response r = apiGet("/controller/network/" + nwid + "/member");
        if (!r.ok()) return null;
        JsonObject members = JsonParser.parseString(r.text()).getAsJsonObject();
        String ownerAddress = nwid.substring(0, 10).toLowerCase();
        List<RoomMember> out = new ArrayList<>();
        for (var entry : members.entrySet()) {
            String address = entry.getKey().toLowerCase();
            JsonObject m = null;
            if (entry.getValue().isJsonObject()) {
                m = entry.getValue().getAsJsonObject();
            } else {
                // Detail route: full member object for one address.
                Http.Response d = apiGet("/controller/network/" + nwid + "/member/" + address);
                if (d.ok()) {
                    try {
                        m = JsonParser.parseString(d.text()).getAsJsonObject();
                    } catch (Exception ignored) {
                    }
                }
            }
            RoomMember rm = new RoomMember();
            rm.address = address;
            rm.owner = rm.address.equals(ownerAddress);
            if (m != null) {
                rm.name = m.has("name") && !m.get("name").isJsonNull() ? m.get("name").getAsString() : "";
                rm.authorized = m.has("authorized") && m.get("authorized").getAsBoolean();
                if (m.has("ipAssignments") && m.get("ipAssignments").isJsonArray()
                        && m.getAsJsonArray("ipAssignments").size() > 0) {
                    rm.ip = m.getAsJsonArray("ipAssignments").get(0).getAsString();
                }
            }
            out.add(rm);
        }
        out.sort((a, b) -> {
            if (a.owner != b.owner) return a.owner ? -1 : 1;
            return a.address.compareTo(b.address);
        });
        return out;
    }

    /** Sets (or clears) a member's nick on this room's controller. */
    public void setMemberName(String nwid, String memberAddress, String name) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("name", name == null ? "" : name);
        Http.Response r = Http.post("http://127.0.0.1:" + apiPort()
                        + "/controller/network/" + nwid + "/member/" + memberAddress,
                body.toString(), "application/json", Map.of("X-ZT1-Auth", apiToken()), HTTP_TIMEOUT);
        if (!r.ok()) {
            throw new IOException("Не удалось сохранить ник участника: HTTP " + r.status());
        }
    }

    /** The host's own managed IP inside {@code nwid}, or null when it has none yet. */
    public String getMyVpnIpForRoom(String nwid) throws IOException {
        Http.Response r = apiGet("/network/" + nwid);
        if (!r.ok()) return null;
        JsonObject o = JsonParser.parseString(r.text()).getAsJsonObject();
        if (!o.has("assignedAddresses") || !o.get("assignedAddresses").isJsonArray()) return null;
        for (var a : o.getAsJsonArray("assignedAddresses")) {
            String host = a.getAsString();
            if (host.contains("/")) host = host.substring(0, host.indexOf('/'));
            if (!host.startsWith("169.254.") && !host.startsWith("127.") && !host.startsWith("fe80:")) {
                return host;
            }
        }
        return null;
    }

    /** Members of {@code nwid} who aren't authorized yet, or null when this room is NOT hosted
     *  on this machine's controller (only the creator's controller answers these routes). */
    public List<MemberInfo> listPendingMembers(String nwid) throws IOException {
        Http.Response r = apiGet("/controller/network/" + nwid + "/member");
        if (!r.ok()) return null;
        JsonObject members = JsonParser.parseString(r.text()).getAsJsonObject();
        List<MemberInfo> out = new ArrayList<>();
        for (var entry : members.entrySet()) {
            JsonObject m = null;
            if (entry.getValue().isJsonObject()) {
                m = entry.getValue().getAsJsonObject();
            } else {
                // На части демонов list-route отдаёт только адреса («addr»: 1), а полный объект
                // участника лежит в detail-route. Раньше здесь такие записи пропускались, из-за
                // чего одобрение входа в приватную комнату «не работало».
                Http.Response d = apiGet("/controller/network/" + nwid + "/member/" + entry.getKey());
                if (d.ok()) {
                    try {
                        m = JsonParser.parseString(d.text()).getAsJsonObject();
                    } catch (Exception ignored) {
                    }
                }
            }
            if (m == null) continue;
            boolean authorized = m.has("authorized") && m.get("authorized").getAsBoolean();
            if (authorized) continue;
            MemberInfo mi = new MemberInfo();
            mi.address = entry.getKey();
            mi.name = m.has("name") && !m.get("name").isJsonNull() ? m.get("name").getAsString() : "";
            out.add(mi);
        }
        return out;
    }

    /** Host-side approval for a friend who requested entry into a private room. */
    public void authorizeMember(String nwid, String memberAddress) throws IOException {
        Http.Response r = Http.post("http://127.0.0.1:" + apiPort()
                        + "/controller/network/" + nwid + "/member/" + memberAddress,
                "{\"authorized\":true}", "application/json", Map.of("X-ZT1-Auth", apiToken()), HTTP_TIMEOUT);
        if (!r.ok()) {
            throw new IOException("Не удалось одобрить участника: HTTP " + r.status());
        }
    }

    // ---------- виртуальные адаптеры (ZeroTier Virtual Port) ----------

    /** Один виртуальный адаптер ZeroTier, как его видит Windows. */
    public static class AdapterInfo {
        /** Имя адаптера, например "ZeroTier One [3d9ec58252ec283b]". */
        public String name = "";
        /** PnP-путь устройства (ROOT\NET\0001 и т.п.) для pnputil. */
        public String pnpId = "";
        /** ID комнаты из имени адаптера, или null если имя не в формате "[nwid]". */
        public String nwid = null;
    }

    /**
     * Перечисляет виртуальные адаптеры ZeroTier через PowerShell (Get-NetAdapter — чтение,
     * прав не требует). Каждый адаптер привязан к одной комнате и носит её ID в имени.
     */
    public List<AdapterInfo> listAdapters() throws IOException {
        List<AdapterInfo> out = new ArrayList<>();
        String ps = "Get-NetAdapter | Where-Object { $_.InterfaceDescription -like '*ZeroTier*' } | "
                + "ForEach-Object { $_.Name + '|' + $_.PnPDeviceID }";
        String raw;
        try {
            raw = runCommandCapture("powershell", "-NoProfile", "-NonInteractive", "-Command", ps);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return out;
        }
        for (String line : raw.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty()) continue;
            AdapterInfo a = new AdapterInfo();
            int bar = line.indexOf('|');
            if (bar >= 0) {
                a.name = line.substring(0, bar).trim();
                a.pnpId = line.substring(bar + 1).trim();
            } else {
                a.name = line;
            }
            int open = a.name.lastIndexOf('[');
            int close = a.name.lastIndexOf(']');
            if (open >= 0 && close > open + 1) {
                String id = a.name.substring(open + 1, close).trim();
                if (id.matches("[0-9a-fA-F]{16}")) a.nwid = id.toLowerCase();
            }
            out.add(a);
        }
        return out;
    }

    /** Удаляет один виртуальный адаптер по PnP-пути (pnputil, нужны права администратора). */
    public void removeAdapter(String pnpId, GameOutputCallback callback) throws IOException, InterruptedException {
        if (pnpId == null || pnpId.isBlank()) return;
        String psCommand = "$p = Start-Process -FilePath 'pnputil' "
                + "-ArgumentList '/remove-device','\"" + pnpId + "\"' "
                + "-Verb RunAs -WindowStyle Hidden -PassThru -Wait -ErrorAction SilentlyContinue; "
                + "if (-not $p) { exit 1602 } else { exit $p.ExitCode }";
        // Через runElevated — с таймаутом и с вычитыванием вывода, как и остальные операции
        // с повышением прав (см. H23/H26 в отчёте об аудите).
        ElevatedResult res = runElevated(psCommand, Duration.ofMinutes(2), "удаление адаптера");
        int exit = res.exitCode();
        if (exit != 0) {
            String why = exit == 1602
                    ? "подтверждение администратора не получено — нажмите «Да» в запросе UAC."
                    : "код " + exit + ".";
            throw new IOException("Не удалось удалить адаптер " + pnpId + ": " + why);
        }
        callback.onOutput("[ZT] Удалён виртуальный адаптер " + pnpId);
    }

    /**
     * Удаляет виртуальный адаптер конкретной комнаты (по ID из имени «ZeroTier One [nwid]»).
     * Вызывается после выхода из комнаты: сам демон не всегда снимает адаптер, и они копятся.
     */
    public boolean removeAdapterForRoom(String nwid, GameOutputCallback callback) throws IOException, InterruptedException {
        if (nwid == null) return false;
        String want = nwid.toLowerCase();
        for (AdapterInfo a : listAdapters()) {
            if (want.equals(a.nwid)) {
                removeAdapter(a.pnpId, callback);
                return true;
            }
        }
        return false;
    }

    // ---------- helpers ----------

    private String runCli(String... args) throws IOException {
        String cli = cliPath();
        if (cli == null) {
            throw new IOException("ZeroTier CLI не найден — установите движок ZeroTier.");
        }
        List<String> cmd = new ArrayList<>();
        cmd.add("cmd");
        cmd.add("/c");
        cmd.add(cli);
        for (String a : args) cmd.add(a);
        try {
            return runCaptured(cmd, "ZeroTier CLI");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Прервано ожидание ZeroTier CLI.");
        }
    }

    /**
     * Запускает процесс, вычитывая его вывод ПАРАЛЛЕЛЬНО ожиданию, и возвращает этот вывод.
     *
     * <p>Здесь была настоящая причина «CLI зависает навсегда», про которую рядом стоял
     * комментарий. Прежний код сначала делал {@code waitFor(таймаут)}, и только потом
     * {@code readAllBytes()}. При {@code redirectErrorStream(true)} оба потока идут в один пайп,
     * буфер которого на Windows около 4 КБ: процесс, напечатавший больше, блокируется на записи
     * и завершиться уже не может — то есть {@code waitFor} гарантированно уходил в таймаут.
     * Достигалось это, например, из {@code listAdapters()}: он печатает по 50-80 байт на
     * адаптер, а адаптеры, по собственному замечанию кода, накапливаются — после примерно
     * 60-80 штук метод начинал падать всегда, {@code cleanupOrphanAdapters()} больше ничего не
     * чистил, и накопление становилось необратимым. Отказ сам себя подпитывал.
     *
     * <p>Лечится порядком: читатель работает всё время, поэтому пайп не переполняется никогда.
     */
    private String runCaptured(List<String> cmd, String what) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        final StringBuilder sink = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (java.io.InputStream in = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    sink.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // Процесс убит по таймауту — обрыв чтения тут ожидаем.
            }
        }, "zt-out");
        reader.setDaemon(true);
        reader.start();

        try {
            if (!p.waitFor(HTTP_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                p.destroy();
                if (!p.waitFor(3000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    p.destroyForcibly();
                }
                reader.join(1000);
                throw new IOException(what + " не завершился за " + HTTP_TIMEOUT.toSeconds()
                        + " с — процесс остановлен.");
            }
            reader.join(2000);   // поток завершится сам, как только пайп закроется
            return sink.toString();
        } catch (InterruptedException e) {
            p.destroyForcibly();
            throw e;
        }
    }

    private String runCommandCapture(String... command) throws IOException, InterruptedException {
        // Тот же порядок чтения, что и в runCli — см. runCaptured.
        return runCaptured(java.util.Arrays.asList(command), "Команда " + String.join(" ", command));
    }
}
