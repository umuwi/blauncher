package com.example.launcher.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Rustcraft — Minecraft: Java Edition 26.3, переписанный на Rust (github.com/neotoxicfr/rustcraft).
 *
 * <p>Сам он игру не качает: ему нужны уже установленные файлы 26.3 (versions/, assets/) и Java 25
 * на один первый запуск — им он из официального jar вытаскивает данные игры в свой кэш. Поэтому в
 * лаунчере это «язык игры: Rust» на карточке «Без модов и сборок»: версия ставится как обычная 26.3, а запускается не java.exe, а
 * rustcraft.exe с теми же аргументами игры (--username, --uuid, --accessToken, --quickPlay…).
 *
 * <p>Программа лежит в &lt;gameDir&gt;/engines/rustcraft/&lt;тег выпуска&gt;/. С 29.10.2026 движок —
 * наша сборка (исправления поверх выпусков автора, тег вида v0.0.1-bl1) с blauncher.ru/rustcraft/;
 * сайт недоступен — берём уже скачанный, а GitHub автора — только если не скачано ничего.
 * Архив сверяется с sha256 (из latest.txt зеркала или от GitHub).
 */
public class RustcraftService {

    public static final String REPO = "neotoxicfr/rustcraft";
    /** Версии Minecraft, которые Rustcraft умеет запускать (их показывает «язык игры: Rust»). */
    public static final List<String> SUPPORTED_VERSIONS = List.of("26.3");

    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final Map<String, String> GITHUB_HEADERS = Map.of(
            "Accept", "application/vnd.github+json",
            "User-Agent", "BLauncher");

    private final Path gameDir;
    private final Path root;

    public RustcraftService(Path gameDir) {
        this.gameDir = gameDir;
        this.root = gameDir.resolve("engines").resolve("rustcraft");
    }

    /**
     * Видеокарта Windows: производитель (VEN_xxxx), версия и дата драйвера ("" — Windows не сказала);
     * basic — драйвера нет, Windows подставила свой «Базовый видеоадаптер (Майкрософт)».
     */
    public record Gpu(String ven, String driver, String date, boolean basic) {
        /** Короткое имя производителя для отметок и подсказки: nv, amd, intel или код. */
        public String vendor() {
            return switch (ven) {
                case "VEN_10DE" -> "nv";
                case "VEN_1002", "VEN_1022" -> "amd";
                case "VEN_8086" -> "intel";
                default -> ven.replace("VEN_", "");
            };
        }

        /** Сколько месяцев драйверу; -1 — дата неизвестна. */
        public int ageMonths() {
            try {
                java.time.LocalDate d = java.time.LocalDate.parse(date);
                return (int) java.time.temporal.ChronoUnit.MONTHS.between(d, java.time.LocalDate.now());
            } catch (Exception e) {
                return -1;
            }
        }
    }

    private static volatile List<Gpu> gpuCache;
    /** Почему список пуст: timeout | fail exc=… | none [skip=<шина_устройство>]; "" — не пуст. */
    private static volatile String gpuStatus = "";

    /**
     * Видеокарты Windows — только настоящие PCI-устройства (виртуальные экраны, удалённый рабочий стол
     * и Basic Render в счёт не идут). Спрашиваем Windows один раз за работу лаунчера: powershell — это
     * секунда-две. Пустой список — не узнали.
     */
    public static List<Gpu> windowsGpus() {
        List<Gpu> cached = gpuCache;
        if (cached != null) return cached;
        if (!Os.isWindows()) return gpuCache = List.of();
        Path tmp = null;
        try {
            // Вывод — в файл, а не в поток: readAllBytes() до waitFor ждал бы зависший powershell
            // без всякого таймаута, а это поток запуска игры — «Играть» висело бы вечно.
            tmp = Files.createTempFile("bl-gpu-", ".txt");
            Process p = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command",
                    "Get-CimInstance Win32_VideoController | ForEach-Object { $d = ''; "
                            + "if ($_.DriverDate) { $d = $_.DriverDate.ToString('yyyy-MM-dd') }; "
                            + "$_.PNPDeviceID + '|' + $_.DriverVersion + '|' + $d + '|' + $_.InfFilename }")
                    .redirectErrorStream(true)
                    .redirectOutput(tmp.toFile())
                    .redirectInput(ProcessBuilder.Redirect.from(new java.io.File("NUL")))
                    .start();
            if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                gpuStatus = "timeout";
                return gpuCache = List.of();
            }
            // ISO-8859-1: коды устройств — латиница, а русский текст ошибки в UTF-8 бросил бы исключение.
            String out = Files.readString(tmp, java.nio.charset.StandardCharsets.ISO_8859_1);
            List<Gpu> gpus = new java.util.ArrayList<>();
            String firstSkipped = null;
            for (String line : out.split("\\R")) {
                String[] f = line.trim().split("\\|", -1);
                if (f[0].isEmpty()) continue;
                String inf = f.length > 3 ? f[3].trim().toLowerCase() : "";
                String driver = f.length > 1 && f[1].matches("[0-9.]{1,30}") ? f[1] : "";
                String date = f.length > 2 && f[2].matches("\\d{4}-\\d{2}-\\d{2}") ? f[2] : "";
                // display.inf — «Базовый видеоадаптер (Майкрософт)»: устройство есть, драйвера производителя нет.
                boolean basic = inf.equals("display.inf") || inf.equals("basicdisplay.inf");
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^PCI\\\\(VEN_[0-9A-F]{4})")
                        .matcher(f[0].toUpperCase());
                if (m.find() && !"VEN_1414".equals(m.group(1))) {   // 1414 — Microsoft
                    gpus.add(new Gpu(m.group(1), driver, date, basic));
                } else if (basic) {
                    // Базовый адаптер не на PCI (30.10.2026 bfd02baf: Basic Render, а список был пустой —
                    // проверка «нет драйвера» промахнулась, Rust 8 минут собирался на процессоре).
                    gpus.add(new Gpu("BASIC", driver, date, true));
                } else if (firstSkipped == null) {
                    // Только тип шины и устройства («ROOT_BASICRENDER»), без серийных хвостов.
                    String[] id = f[0].toUpperCase().split("\\\\");
                    firstSkipped = (id[0] + (id.length > 1 ? "_" + id[1] : "")).replaceAll("[^A-Z0-9_&]", "");
                    if (firstSkipped.length() > 40) firstSkipped = firstSkipped.substring(0, 40);
                }
            }
            // Есть настоящая видеокарта — базовый адаптер не на PCI лишний: иначе он считался бы второй
            // видеокартой (hybridGpu, «у вас две видеокарты») и ломал onlyIntelGpu.
            if (gpus.stream().anyMatch(g -> !"BASIC".equals(g.ven()))) gpus.removeIf(g -> "BASIC".equals(g.ven()));
            gpuStatus = gpus.isEmpty() ? "none" + (firstSkipped != null ? " skip=" + firstSkipped : "") : "";
            return gpuCache = List.copyOf(gpus);
        } catch (Exception e) {
            // Неудачу тоже запоминаем: подсказку о драйвере зовут из потока окна, и второй
            // зависший powershell подвесил бы лаунчер ещё на 10 с.
            gpuStatus = "fail exc=" + e.getClass().getSimpleName();
            return gpuCache = List.of();
        } finally {
            if (tmp != null) try { Files.deleteIfExists(tmp); } catch (Exception ignored) { }
        }
    }

    /** Настройки графики из options.txt → короткое имя в отметке rustcraft_set. */
    private static final String[][] RUST_SETTINGS = {
            {"graphicsPreset", "p"}, {"renderDistance", "rd"}, {"simulationDistance", "sd"}, {"gamma", "g"},
            {"maxFps", "fps"}, {"enableVsync", "vs"}, {"fullscreen", "fs"}, {"improvedTransparency", "it"},
            {"textureFiltering", "tf"}, {"mipmapLevels", "mm"}, {"ao", "ao"}, {"entityShadows", "es"},
            {"renderClouds", "cl"}, {"biomeBlendRadius", "bb"}, {"guiScale", "gs"}, {"fov", "fov"},
    };

    /**
     * Графика, с которой играли на Rust (29.10.2026: RTX 2060 «всё тёмное после настройки», а какую
     * настройку меняли — не узнать). Только числа и слова из фиксированного списка ключей options.txt,
     * «p=fancy rd=16 g=0.5 …»; "" — файла нет.
     */
    public static String settingsInfo(Path gameDir) {
        Map<String, String> vals = new java.util.HashMap<>();
        try {
            for (String line : Files.readAllLines(gameDir.resolve("options.txt"), java.nio.charset.StandardCharsets.ISO_8859_1)) {
                int i = line.indexOf(':');
                if (i > 0) vals.put(line.substring(0, i), line.substring(i + 1).replace("\"", "").trim());
            }
        } catch (Exception e) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String[] k : RUST_SETTINGS) {
            String v = vals.get(k[0]);
            if (v == null || !v.matches("[A-Za-z0-9.-]{1,10}")) continue;
            if (v.matches("-?\\d+\\.\\d{3,}")) v = v.substring(0, v.indexOf('.') + 3);   // 0.5000000 → 0.50
            if (sb.length() > 0) sb.append(' ');
            sb.append(k[1]).append('=').append(v);
        }
        return sb.length() > 120 ? sb.substring(0, 120) : sb.toString();
    }

    /**
     * Ни у одной видеокарты нет драйвера производителя — Windows рисует процессором (Basic Render).
     * 29.10.2026 у 2dd3373f Rust так запускался 10 минут и «лагал жёстко»; таких пятеро за двое суток.
     */
    public static boolean noGpuDriver(List<Gpu> gpus) {
        return !gpus.isEmpty() && gpus.stream().allMatch(Gpu::basic);
    }

    /** Видеокарты одной строкой «VEN_xxxx;VEN_yyyy»; "" — не узнали. */
    public static String windowsGpuVendors() {
        StringBuilder ven = new StringBuilder();
        for (Gpu g : windowsGpus()) {
            if (ven.length() > 0) ven.append(';');
            ven.append(g.ven());
        }
        return ven.toString();
    }

    /**
     * Две видеокарты — обычно ноутбук, где встройка выводит картинку, а NVIDIA/AMD считает. Здесь
     * DirectX 12 после смены размера окна рисует со сдвигом (известная ошибка драйверов, wgpu
     * known_driver_issues, «Intel/DX12 offset after resizing … dual-GPU»), Vulkan тоже капризнее.
     */
    public static boolean hybridGpu(List<Gpu> gpus) {
        return gpus.size() >= 2;
    }

    /** Отметка rustcraft_drv: «gpus=2 hybrid=1 nv=31.0.15.3623:2023-05 intel=…»; "" — Windows не ответила. */
    public static String driverInfo(List<Gpu> gpus) {
        // Пустой список — тоже в отметку: без неё bfd02baf (Basic Render) был не виден вовсе.
        if (gpus.isEmpty()) return Os.isWindows() ? "gpus=0 " + gpuStatus : "";
        StringBuilder sb = new StringBuilder("gpus=" + gpus.size() + (hybridGpu(gpus) ? " hybrid=1" : ""));
        for (Gpu g : gpus) {
            sb.append(' ').append(g.vendor()).append('=').append(g.driver().isEmpty() || g.basic() ? "none" : g.driver());
            if (g.date().length() >= 7) sb.append(':').append(g.date(), 0, 7);
        }
        return sb.toString();
    }

    /**
     * Только встройка Intel (VEN_8086) без дискретной видеокарты. На ней Rustcraft на Vulkan падает
     * сразу при выборе видеокарты (0xC0000005, step=adapter, 2 из 2 за 29.10.2026), а на DirectX 12
     * идёт нормально — таким сразу даём DX12, без лишнего первого вылета.
     */
    public static boolean onlyIntelGpu(String vendors) {
        if (vendors == null || vendors.isEmpty()) return false;
        for (String v : vendors.split(";")) if (!"VEN_8086".equals(v)) return false;
        return true;
    }

    /** Linux x86-64 и Windows x64 — других сборок у проекта нет. */
    public static boolean platformSupported() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        return x64 && (Os.isWindows() || Os.isLinux());
    }

    /**
     * Путь к исполняемому файлу Rustcraft: свежий выпуск с GitHub (скачивается, если его ещё нет)
     * или уже скачанный, если до GitHub не достучаться.
     */
    public Path ensureInstalled(Consumer<String> log) throws IOException {
        if (!platformSupported()) {
            throw new IOException("Rustcraft есть только для Windows x64 и Linux x86-64");
        }
        // Наша сборка первой: в выпуске автора нет наших исправлений (картинка после сворачивания и др.),
        // а GitHub без входа даёт 60 запросов в час на адрес (28.10.2026, aa421f38 — запуск сорвался).
        try {
            return installFromMirror(log);
        } catch (Exception mirrorEx) {
            Path installed = newestInstalled();
            if (installed != null) {
                log.accept("[RUSTCRAFT] blauncher.ru недоступен (" + mirrorEx.getMessage() + ") — беру уже скачанный "
                        + installed.getParent().getFileName());
                return installed;
            }
            log.accept("[RUSTCRAFT] blauncher.ru недоступен (" + mirrorEx.getMessage() + ") — качаю выпуск автора с GitHub");
            try {
                return installFromGithub(log);
            } catch (Exception e) {
                IOException io = new IOException("Не удалось скачать Rustcraft ни с blauncher.ru, ни с GitHub: "
                        + e.getMessage(), e);
                io.addSuppressed(mirrorEx);
                throw io;
            }
        }
    }

    /** Последний выпуск автора с GitHub — запасной путь, когда наш сайт недоступен и ничего не скачано. */
    private Path installFromGithub(Consumer<String> log) throws IOException {
        Http.Response r = Http.get(API, GITHUB_HEADERS, Duration.ofSeconds(15));
        if (!r.ok()) throw new IOException("GitHub ответил " + r.status());
        JsonObject release = JsonParser.parseString(r.text()).getAsJsonObject();

        String tag = safeName(release.get("tag_name").getAsString());
        Path exe = root.resolve(tag).resolve(exeName());
        if (Files.isRegularFile(exe)) return exe;

        String suffix = assetSuffix();
        JsonObject asset = null;
        JsonArray assets = release.getAsJsonArray("assets");
        for (JsonElement el : assets) {
            JsonObject a = el.getAsJsonObject();
            if (a.get("name").getAsString().endsWith(suffix)) {
                asset = a;
                break;
            }
        }
        if (asset == null) throw new IOException("В выпуске Rustcraft " + tag + " нет сборки *" + suffix);

        String digest = asset.has("digest") && !asset.get("digest").isJsonNull() ? asset.get("digest").getAsString() : null;
        log.accept("[RUSTCRAFT] Скачиваю " + asset.get("name").getAsString() + " ("
                + asset.get("size").getAsLong() / 1_048_576 + " МБ)…");
        return install(tag, asset.get("name").getAsString(), asset.get("browser_download_url").getAsString(),
                digest != null && digest.startsWith("sha256:") ? digest.substring(7) : null, log);
    }

    /** Наша сборка на сайте: latest.txt = «тег имя-архива sha256» по строке на ОС. */
    static final String MIRROR = "https://blauncher.ru/rustcraft/";

    private Path installFromMirror(Consumer<String> log) throws IOException {
        Http.Response r = Http.get(MIRROR + "latest.txt", Map.of(), Duration.ofSeconds(15));
        if (!r.ok()) throw new IOException("blauncher.ru ответил " + r.status());
        for (String line : r.text().split("\\R")) {
            String[] f = line.trim().split("\\s+");
            if (f.length != 3 || !f[1].endsWith(assetSuffix())) continue;
            String tag = safeName(f[0]);
            Path exe = root.resolve(tag).resolve(exeName());
            if (Files.isRegularFile(exe)) return exe;
            if (!f[1].matches("[A-Za-z0-9._-]+")) throw new IOException("Странное имя архива: " + f[1]);
            log.accept("[RUSTCRAFT] Скачиваю " + f[1] + " с blauncher.ru…");
            return install(tag, f[1], MIRROR + f[1], f[2], log);
        }
        throw new IOException("На blauncher.ru нет сборки *" + assetSuffix());
    }

    private static String assetSuffix() {
        return Os.isWindows() ? "-windows-x64.zip" : "-linux-x64.tar.gz";
    }

    /**
     * Данные игры уже подготовлены (Rustcraft сделал это на прошлом запуске) — Java ему больше
     * не нужна. Папку кэша задаёт GameLaunchService (RUSTCRAFT_CACHE = engines/rustcraft/cache).
     */
    public boolean dataReady(String mcVersion) {
        return Files.isRegularFile(root.resolve("cache").resolve(mcVersion).resolve("data.bin"));
    }

    /*
     * Урезанный кэш (29.10.2026). Готовя данные, Rustcraft берёт класспатх клиента из
     * versions/<v>/<v>.json и требует ВСЕ библиотеки, чьё правило пропускает os.name, — архитектуру
     * он не смотрит (rc-data/src/prepare.rs, client_classpath/rules_allow_on). Лаунчер же на x64 не
     * качает natives-windows-arm64 (LibraryRules.isForeignNative). Не найдя хоть одну, движок молча
     * уходит на запасной путь через серверный jar и собирает кэш без окраски травы, неба, моделей
     * мобов и игрока, вкладок креатива и команд — и этот кэш остаётся навсегда. Игрок под видео:
     * «трава чёрно-белая, неба нет, мобы невидимые, креатив не открывается».
     */

    /**
     * Докачать библиотеки версии, которые движок требует, а лаунчер для игры на Java пропускает.
     * Правило — точная копия движковой: нет rules — нужна; иначе решает последнее правило без
     * features, у которого os.name нет или совпадает с нашей ОС. Возвращает число скачанных файлов.
     */
    public int ensureEngineLibraries(String mcVersion, Consumer<String> log) throws IOException {
        Path json = gameDir.resolve("versions").resolve(mcVersion).resolve(mcVersion + ".json");
        if (!Files.isRegularFile(json)) return 0;
        JsonObject meta = JsonParser.parseString(Files.readString(json)).getAsJsonObject();
        if (!meta.has("libraries") || !meta.get("libraries").isJsonArray()) return 0;
        String os = Os.isWindows() ? "windows" : Os.isLinux() ? "linux" : "osx";
        int got = 0;
        for (JsonElement el : meta.getAsJsonArray("libraries")) {
            if (!el.isJsonObject()) continue;
            JsonObject lib = el.getAsJsonObject();
            if (!engineRulesAllow(lib.get("rules"), os)) continue;
            if (!lib.has("downloads") || !lib.getAsJsonObject("downloads").has("artifact")) continue;
            JsonObject art = lib.getAsJsonObject("downloads").getAsJsonObject("artifact");
            if (!art.has("path") || !art.has("url")) continue;
            Path dest = gameDir.resolve("libraries").resolve(art.get("path").getAsString());
            if (Files.isRegularFile(dest)) continue;
            log.accept("[RUSTCRAFT] Докачиваю библиотеку для движка: " + dest.getFileName());
            Files.createDirectories(dest.getParent());
            if (Http.download(art.get("url").getAsString(), dest, Map.of(), Duration.ofSeconds(60)) < 0) {
                throw new IOException("Сервер не отдал " + dest.getFileName());
            }
            if (art.has("sha1") && !art.get("sha1").getAsString().equalsIgnoreCase(digest(dest, "SHA-1"))) {
                Files.deleteIfExists(dest);
                throw new IOException("Не сошлась контрольная сумма " + dest.getFileName());
            }
            got++;
        }
        return got;
    }

    private static boolean engineRulesAllow(JsonElement rules, String os) {
        if (rules == null || !rules.isJsonArray()) return true;
        boolean allowed = false;
        for (JsonElement r : rules.getAsJsonArray()) {
            if (!r.isJsonObject()) continue;
            JsonObject rule = r.getAsJsonObject();
            JsonObject ruleOs = rule.has("os") && rule.get("os").isJsonObject() ? rule.getAsJsonObject("os") : null;
            boolean osOk = ruleOs == null || !ruleOs.has("name") || os.equals(ruleOs.get("name").getAsString());
            if (osOk && !rule.has("features")) {
                allowed = rule.has("action") && "allow".equals(rule.get("action").getAsString());
            }
        }
        return allowed;
    }

    /**
     * Кэш, собранный по запасному пути (есть папка server\ или нет хоть одного файла из клиента), —
     * удалить: на этом запуске движок соберёт его заново. Возвращает причину для отметки или null,
     * если кэша нет или он полный.
     */
    public String dropPartialCache(String mcVersion) throws IOException {
        Path dir = root.resolve("cache").resolve(mcVersion);
        if (!Files.isRegularFile(dir.resolve("data.bin"))) {
            // Запасной путь начинался и оборвался (до 1.14.41 — InternalError без jdk.compiler):
            // server\ остался без data.bin. Полный кэш соберётся рядом с ней, а следующий запуск
            // примет его за урезанный и пересоберёт ещё раз (f6b98965, 29.10.2026). Убираем сразу.
            if (Files.isDirectory(dir.resolve("server"))) deleteRecursively(dir.resolve("server"));
            return null;
        }
        String why = Files.isDirectory(dir.resolve("server")) ? "server" : null;
        if (why == null) {
            for (String f : List.of("client_facts.json", "entity_models.json", "creative.json", "commands.bin")) {
                if (!Files.isRegularFile(dir.resolve(f))) { why = "no_" + f; break; }
            }
        }
        if (why != null) deleteRecursively(dir);
        return why;
    }

    /** Скачать архив выпуска, сверить sha256 (если известен), распаковать в &lt;root&gt;/&lt;tag&gt;/. */
    private Path install(String tag, String name, String url, String sha256Hex, Consumer<String> log) throws IOException {
        Path dir = root.resolve(tag);
        Path exe = dir.resolve(exeName());
        Files.createDirectories(root);
        Path archive = root.resolve(name);
        long got = Http.download(url, archive, Map.of("User-Agent", "BLauncher"), Duration.ofSeconds(60));
        if (got < 0) throw new IOException("Не скачался " + name);
        try {
            if (sha256Hex != null) {
                String actual = sha256(archive);
                if (!actual.equalsIgnoreCase(sha256Hex)) {
                    throw new IOException("Контрольная сумма " + name + " не совпала — файл повреждён при загрузке");
                }
            }
            Path tmp = root.resolve(tag + ".tmp");
            deleteRecursively(tmp);
            Files.createDirectories(tmp);
            if (name.endsWith(".zip")) unzip(archive, tmp);
            else untar(archive, tmp);
            Path found = findExe(tmp);
            if (found == null) throw new IOException("В архиве " + name + " нет " + exeName());
            if (!Os.isWindows()) found.toFile().setExecutable(true);
            Path sh = found.resolveSibling("rustcraft.sh");
            if (Files.exists(sh)) sh.toFile().setExecutable(true);
            Path extractedRoot = found.getParent();
            deleteRecursively(dir);
            Files.move(extractedRoot, dir, StandardCopyOption.ATOMIC_MOVE);
            deleteRecursively(tmp);
        } finally {
            Files.deleteIfExists(archive);
        }
        log.accept("[RUSTCRAFT] Установлен " + tag);
        removeOldReleases(tag);
        return exe;
    }

    private static String exeName() {
        return Os.isWindows() ? "rustcraft.exe" : "rustcraft";
    }

    /** Тег выпуска идёт в имя папки — только безопасные символы. */
    private static String safeName(String tag) {
        String s = tag.replaceAll("[^A-Za-z0-9._-]", "_");
        if (s.isEmpty() || s.startsWith(".")) throw new IllegalArgumentException("Странный тег выпуска: " + tag);
        return s;
    }

    private Path newestInstalled() throws IOException {
        if (!Files.isDirectory(root)) return null;
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(p -> !p.getFileName().toString().endsWith(".tmp") && !isCacheDir(p))
                    .map(p -> p.resolve(exeName()))
                    .filter(Files::isRegularFile)
                    .max((a, b) -> Long.compare(a.toFile().lastModified(), b.toFile().lastModified()))
                    .orElse(null);
        }
    }

    /** engines/rustcraft/cache — данные игры (RUSTCRAFT_CACHE), а не выпуск движка. */
    private boolean isCacheDir(Path p) {
        return p.getFileName().toString().equals("cache");
    }

    private void removeOldReleases(String keep) {
        try (Stream<Path> s = Files.list(root)) {
            for (Path p : s.toList()) {
                // Кэш не трогаем: иначе смена выпуска стирала бы готовые данные игры и запуск
                // снова ждал бы их пересборки (до минуты, с Java).
                if (Files.isDirectory(p) && !isCacheDir(p) && !p.getFileName().toString().equals(keep)) deleteRecursively(p);
            }
        } catch (IOException ignored) {
            // старый выпуск остался на диске — не повод мешать запуску
        }
    }

    private static Path findExe(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir, 3)) {
            return s.filter(p -> p.getFileName().toString().equals(exeName()) && Files.isRegularFile(p))
                    .findFirst().orElse(null);
        }
    }

    private static void unzip(Path zip, Path dest) throws IOException {
        Path base = dest.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                Path out = base.resolve(e.getName()).normalize();
                if (!out.startsWith(base)) throw new IOException("Подозрительный путь в архиве: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** tar.gz только на Linux, а там tar есть всегда. */
    private static void untar(Path archive, Path dest) throws IOException {
        Process p = new ProcessBuilder("tar", "-xzf", archive.toAbsolutePath().toString(),
                "--no-same-owner", "-C", dest.toAbsolutePath().toString())
                .redirectErrorStream(true).start();
        String out;
        try (InputStream in = p.getInputStream()) {
            out = new String(in.readAllBytes());
        }
        try {
            if (p.waitFor() != 0) throw new IOException("tar не распаковал архив: " + out.trim());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Распаковка прервана", e);
        }
    }

    private static String sha256(Path file) throws IOException {
        return digest(file, "SHA-256");
    }

    private static String digest(Path file, String algorithm) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance(algorithm);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            for (Path x : s.sorted(java.util.Comparator.reverseOrder()).toList()) Files.deleteIfExists(x);
        }
    }
}
