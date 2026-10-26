package com.example.launcher.service.vps;

import com.example.launcher.service.Http;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Аренда VPS у serv.host по партнёрской ссылке (17.10.2026).
 *
 * <p>Покупка идёт целиком на сайте хостинга, в системном браузере: лаунчер не знает и не хранит
 * ничего об аккаунте serv.host. После покупки человек вводит IP и пароль в обычную форму панели
 * VPS, а сервер получает отметку {@link #SOURCE} — только для счётчика «подключили купленный».
 *
 * <p>Ссылка зашита здесь, но при старте перечитывается с сайта ({@link #REMOTE_CONFIG}), чтобы
 * сменить её без выпуска лаунчера. Нет файла, нет сети, пришло не https — остаётся зашитая.
 *
 * <p>С 26.10.2026 тариф выбирают в самом лаунчере: 45 дата-центров ({@link #LOCATIONS}) и их
 * тарифы ({@link #catalog}), а «Оплатить» открывает в системном браузере кабинет с партнёрской
 * меткой ({@link #orderUrl}). Кабинет my.serv.host сам регистрирует переход по {@code ?from=}
 * (referralprogram.registerclick в их api.js) — тот же путь, что у ссылки с главной serv.host.
 * Открыть сразу конкретный тариф кабинет пока не умеет: startform/plid/datacenter мы передаём,
 * как их сайт, но новый кабинет их не читает, поэтому название тарифа ещё и копируется в буфер.
 */
public final class ServHostRent {

    /** Партнёрская ссылка по умолчанию. */
    public static final String DEFAULT_URL = "https://serv.host/?from=18507";
    /** Значение ServerEntry.rentedVia для серверов, подключённых после «Я купил». */
    public static final String SOURCE = "servhost";
    /** {"url":"https://…"} — правится на сервере, лаунчер не обновляется. */
    private static final String REMOTE_CONFIG = "https://blauncher.ru/api/rent.json";

    /**
     * Тарифы по дата-центрам — тот же формат, что вшитый {@link #PLANS_RESOURCE}; выложенный файл
     * заменяет вшитый без выпуска лаунчера. Снять заново: войти в my.serv.host, «Заказ услуги», и
     * в консоли браузера для каждого datacenter вызвать
     * {@code BM.call("v2.vds.order.pricelist", {datacenter, fperiod: "null", sv_field: "datacenter"}, {method: "POST"})},
     * из doc.list.elem взять pricelist, title, detail (ядра/ОЗУ/диск) и цену за период 1.
     */
    private static final String PLANS_REMOTE = "https://blauncher.ru/api/rent_plans.txt";
    private static final String PLANS_RESOURCE = "/rent/servhost-plans.txt";
    /** Кабинет: сюда ведёт кнопка «Оплатить»; партнёрскую метку и тариф добавляет orderUrl. */
    private static final String CABINET_URL = "https://my.serv.host/billmgr";

    private static volatile String url = DEFAULT_URL;

    /** Тариф дата-центра. id — номер прайс-листа биллинга (plid), name — без «Тариф «…»». */
    public record Plan(String id, String name, int cores, int ramGb, int diskGb, int priceRub) {
    }

    /**
     * Дата-центр = локация × процессор, как в кабинете. dc — его номер в биллинге (поле datacenter
     * формы заказа, ключ в каталоге), family — как процессор назван в кабинете («Венгрия AMD
     * Ryzen»), model — модель со страницы serv.host/billing/vps, если известна.
     */
    public record Cpu(int dc, String family, String model) {
    }

    /** Локация: код (ключ rent_c_*), флаг и её дата-центры в порядке кабинета. */
    public record Location(String code, String flag, List<Cpu> cpus) {
    }

    /**
     * Тарифы по номеру дата-центра, как дата-центр назван в кабинете и его характеристики из
     * описания тарифа в кабинете (cpu, ddos=guard|basic, net, burst, ipv4, ipv6, disk, virt, city).
     */
    public record Catalog(Map<Integer, List<Plan>> plans, Map<Integer, String> names,
                          Map<Integer, Map<String, String>> specs) {
    }

    private static Cpu epycEu(int dc) { return new Cpu(dc, "AMD EPYC", "AMD EPYC 7713"); }

    private static Cpu ryzenEu(int dc) { return new Cpu(dc, "AMD Ryzen", "AMD Ryzen 9 5950X"); }

    private static Location eu(String code, int epyc, int ryzen) {
        return new Location(code, code, List.of(epycEu(epyc), ryzenEu(ryzen)));
    }

    /** Страны, открытые с 07.10.2026: один Ryzen, модель на сайте не указана. */
    private static Location fresh(String code, int dc) {
        return new Location(code, code, List.of(new Cpu(dc, "AMD Ryzen", "AMD Ryzen")));
    }

    /**
     * Все 45 дата-центров формы заказа VPS (список datacenter из v2.vds.order.pricelist, снят
     * 26.10.2026 из кабинета владельца). 26.10.2026 сначала было 4 плитки с ценами из их
     * vps.json (Европа одной), но у каждой страны в кабинете свои тарифы и номера, а цены местами
     * расходятся (Финляндия EPYC 8 ГБ — 1690, не 1700; во Франции у Ryzen нет 32 ГБ) — поэтому
     * список как в кабинете и его же прайс.
     */
    public static final List<Location> LOCATIONS = List.of(
            new Location("msk", "ru", List.of(new Cpu(2, "AMD EPYC", "AMD EPYC 7502"),
                    new Cpu(44, "AMD Ryzen", "AMD Ryzen 7 5800X"))),
            new Location("nsk", "ru", List.of(new Cpu(5, "Intel Xeon", "Intel Xeon 2667v2"),
                    new Cpu(43, "AMD Ryzen", "AMD Ryzen 9 7950X"))),
            eu("gb", 7, 41), eu("de", 8, 40), eu("nl", 9, 39), eu("fi", 11, 38), eu("no", 13, 36),
            eu("cz", 16, 33), eu("at", 24, 25), eu("dk", 23, 26), eu("it", 22, 27), eu("ee", 20, 29),
            eu("lt", 19, 30), eu("lv", 18, 31), eu("be", 14, 35), eu("ch", 15, 34), eu("se", 12, 37),
            eu("fr", 6, 42),
            new Location("us", "us", List.of(new Cpu(10, "AMD Ryzen", "AMD Ryzen 9 9950X"))),
            new Location("ca", "ca", List.of(new Cpu(17, "AMD Ryzen", "AMD Ryzen 9 9950X"))),
            fresh("by", 45), fresh("sk", 46), fresh("hu", 47), fresh("ro", 48), fresh("tr", 49),
            fresh("bg", 50), fresh("rs", 51));

    private ServHostRent() {
    }

    public static String url() {
        return url;
    }

    /** Перечитать ссылку с сайта. Сеть — вызывать не из потока UI. Ошибки молча глотаются. */
    public static void refresh() {
        try {
            Http.Response r = Http.get(REMOTE_CONFIG, Map.of("User-Agent", "BLauncher"), Duration.ofSeconds(8));
            if (r.status() != 200 || r.body().length > 4096) return;
            JsonObject o = JsonParser.parseString(new String(r.body(), StandardCharsets.UTF_8)).getAsJsonObject();
            if (!o.has("url") || !o.get("url").isJsonPrimitive()) return;
            String candidate = o.get("url").getAsString().trim();
            if (isAcceptable(candidate)) url = candidate;
        } catch (Exception ignored) {
            // остаётся зашитая ссылка
        }
    }

    /**
     * Каталог тарифов: выложенный на blauncher.ru, если он читается и в нём есть тарифы, иначе
     * вшитый. Сеть — не из потока UI. Исключение — только если не разобрался и вшитый.
     */
    public static Catalog catalog() throws Exception {
        try {
            Http.Response r = Http.get(PLANS_REMOTE, Map.of("User-Agent", "BLauncher"), Duration.ofSeconds(8));
            if (r.status() == 200 && r.body().length < 256 * 1024) {
                Catalog c = parseCatalog(new String(r.body(), StandardCharsets.UTF_8));
                if (!c.plans().isEmpty()) return c;
            }
        } catch (Exception ignored) {
            // нет файла на сайте или нет сети — вшитый список
        }
        try (var in = ServHostRent.class.getResourceAsStream(PLANS_RESOURCE)) {
            if (in == null) throw new IllegalStateException("no " + PLANS_RESOURCE);
            Catalog c = parseCatalog(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            if (c.plans().isEmpty()) throw new IllegalStateException("empty " + PLANS_RESOURCE);
            return c;
        }
    }

    /**
     * «dc|название|plid,имя,ядра,ОЗУ,диск,цена;…|ключ=значение;…» построчно, # — комментарий.
     * Последнее поле (характеристики) необязательно — файл без него тоже читается. Битые тарифы и
     * строки пропускаются; внутри дата-центра — по памяти, как в кабинете.
     */
    static Catalog parseCatalog(String text) {
        Map<Integer, List<Plan>> plans = new LinkedHashMap<>();
        Map<Integer, String> names = new LinkedHashMap<>();
        Map<Integer, Map<String, String>> specs = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] parts = line.split("\\|", 4);
            if (parts.length < 3) continue;
            int dc;
            try {
                dc = Integer.parseInt(parts[0].trim());
            } catch (NumberFormatException e) {
                continue;
            }
            List<Plan> list = new ArrayList<>();
            for (String t : parts[2].split(";")) {
                String[] f = t.split(",");
                if (f.length != 6 || !f[0].trim().matches("\\d{1,9}")) continue;
                try {
                    Plan p = new Plan(f[0].trim(), cleanName(f[1]), Integer.parseInt(f[2].trim()),
                            Integer.parseInt(f[3].trim()), Integer.parseInt(f[4].trim()), Integer.parseInt(f[5].trim()));
                    if (p.ramGb() > 0 && p.priceRub() > 0) list.add(p);
                } catch (NumberFormatException ignored) {
                    // тариф без чисел не показать
                }
            }
            if (list.isEmpty()) continue;
            list.sort(Comparator.comparingInt(Plan::ramGb).thenComparingInt(Plan::priceRub));
            plans.put(dc, List.copyOf(list));
            names.put(dc, parts[1].trim().replaceAll("\\s+", " "));
            Map<String, String> spec = new LinkedHashMap<>();
            if (parts.length > 3) {
                for (String kv : parts[3].split(";")) {
                    int eq = kv.indexOf('=');
                    if (eq > 0) spec.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                }
            }
            specs.put(dc, spec);
        }
        return new Catalog(plans, names, specs);
    }

    /** «Тариф «AMD-Ryzen-2»» → «AMD-Ryzen-2»; имя без кавычек остаётся как есть. */
    static String cleanName(String raw) {
        String s = raw.trim();
        int a = s.indexOf('«'), b = s.lastIndexOf('»');
        if (a >= 0 && b > a) s = s.substring(a + 1, b);
        return s.length() > 60 ? s.substring(0, 60) : s;
    }

    /**
     * Кабинет с партнёрской меткой и тарифом. Номер партнёра берём из текущей ссылки (её можно
     * сменить через rent.json); нет в ней from= — отдаём саму ссылку, метка там своя.
     */
    public static String orderUrl(Plan plan, Cpu cpu) {
        String partner = queryParam(url, "from");
        if (partner == null || !partner.matches("\\d{1,10}")) return url;
        StringBuilder sb = new StringBuilder(CABINET_URL).append("?from=").append(partner);
        if (plan != null) sb.append("&startform=v2.vds.order.param&plid=").append(plan.id());
        if (plan != null && cpu != null) sb.append("&datacenter=").append(cpu.dc());
        return sb.toString();
    }

    static String queryParam(String link, String name) {
        try {
            String q = URI.create(link).getRawQuery();
            if (q == null) return null;
            for (String part : q.split("&")) {
                int eq = part.indexOf('=');
                if (eq > 0 && part.substring(0, eq).equals(name)) return part.substring(eq + 1);
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    /** Только https с нормальным хостом: ссылка уходит прямо в браузер человека. */
    static boolean isAcceptable(String candidate) {
        if (candidate.isEmpty() || candidate.length() > 300 || !candidate.startsWith("https://")) return false;
        try {
            URI u = URI.create(candidate);
            return u.getHost() != null && !u.getHost().isBlank() && u.getUserInfo() == null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
