package com.example.launcher.service;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tiny HTTP endpoint reachable through the ZeroTier overlay: the phone asks it "who is in room
 * X?" and gets back every member with its nick, managed IP and owner flag — plus the host's own
 * IP in that room, which is what friends connect to. Serves the same data the launcher's own
 * ZeroTier panel shows, so the Android side does not have to re-implement the controller API.
 *
 * <p>Binds 0.0.0.0 so the request arrives over the virtual interface (ZT routes the phone's
 * 10.147.x.y → host). Port 9994: 9993 is ZeroTier's own, 9995+ are free.
 *
 * <h2>Кто сюда допускается</h2>
 *
 * <p>Прежнее рассуждение «аутентификация не нужна, оверлей сам ограничивает круг» было неверным
 * дважды, и оба случая проверены по коду.
 *
 * <p><b>Раз.</b> Бинд на {@code 0.0.0.0} означает не только виртуальный интерфейс, но и обычную
 * локалку. Сосед по Wi-Fi, знающий номер комнаты, открывал
 * {@code /zt/authorize?nwid=…&address=<свой-id>} — и оказывался внутри приватной комнаты, то есть
 * обходил ровно то подтверждение владельцем, ради которого приватные комнаты и нужны. Номер
 * комнаты — не секрет: его знает каждый, кто когда-либо в ней был, включая выгнанного.
 * Теперь изменяющие запросы принимаются только с адресов внутри подсетей, которые ZeroTier выдал
 * этой машине, — то есть именно из оверлея. См. {@link #fromOverlay}. Исключение —
 * {@code /zt/create}: телефон зовёт её ещё до того, как попал в оверлей, разбор в самом
 * обработчике.
 *
 * <p><b>Два.</b> Изменяющие операции работали обычным GET, без проверки {@code Origin}. Значит
 * любая открытая в браузере страница делала за человека {@code <img
 * src="http://127.0.0.1:9994/zt/create?name=x">}: ответ браузер бы заблокировал, а побочный
 * эффект — нет. Теперь запрос с заголовком {@code Origin} или {@code Referer} отвергается:
 * браузер их проставляет сам, а родное приложение на телефоне не присылает ни того, ни другого.
 * См. {@link #fromBrowser}.
 *
 * <p><b>Что ещё не закрыто.</b> Полное решение — токен, который лаунчер генерирует при включении
 * ZeroTier и кладёт в тот же QR, что и номер комнаты, плюс POST вместо GET на изменяющих
 * операциях. Это требует одновременной правки приложения на телефоне, которого в этом
 * репозитории нет, поэтому сделано то, что работает без него. Пока токена нет, устройство,
 * которое УЖЕ в комнате, по-прежнему может звать изменяющие ручки — но это участник, а не
 * посторонний.
 */
public final class ZtInfoServer implements AutoCloseable {

    public static final int PORT = 9994;

    private final HttpServer server;
    private final ExecutorService pool;
    private final ZeroTierService zt;
    private final Path gameDir;

    private volatile java.util.function.Consumer<String> logger = msg -> { };

    /** Подсети, которые ZeroTier выдал этой машине, и когда они были прочитаны. */
    private volatile List<Subnet> overlaySubnets = List.of();
    private volatile long subnetsReadAt;
    private static final long SUBNETS_TTL_MS = 15_000;

    /** Окно и предел для /zt/create — чтобы никто не засыпал контроллер пустыми комнатами. */
    private static final long CREATE_WINDOW_MS = 10 * 60 * 1000L;
    private static final int CREATE_LIMIT = 5;
    private final java.util.ArrayDeque<Long> createTimes = new java.util.ArrayDeque<>();

    private ZtInfoServer(HttpServer server, ExecutorService pool, ZeroTierService zt, Path gameDir) {
        this.server = server;
        this.pool = pool;
        this.zt = zt;
        this.gameDir = gameDir;
    }

    /** Куда писать отказы: без этого диагностировать «телефон не видит ПК» было бы нечем. */
    public void setLogger(java.util.function.Consumer<String> logger) {
        if (logger != null) this.logger = logger;
    }

    private void log(String message) {
        try {
            logger.accept(message);
        } catch (Exception ignored) {
            // лог не должен ронять обработку запроса
        }
    }

    /** Адрес с префиксом, как его отдаёт ZeroTier в assignedAddresses («10.147.17.42/24»). */
    private record Subnet(byte[] network, int prefix) {
        boolean contains(byte[] addr) {
            if (addr == null || addr.length != network.length) return false;
            int fullBytes = prefix / 8;
            int restBits = prefix % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (addr[i] != network[i]) return false;
            }
            if (restBits == 0) return true;
            int mask = (0xFF << (8 - restBits)) & 0xFF;
            return (addr[fullBytes] & mask) == (network[fullBytes] & mask);
        }

        static Subnet parse(String cidr) {
            try {
                int slash = cidr.indexOf('/');
                if (slash < 0) return null;
                String host = cidr.substring(0, slash).trim();
                int prefix = Integer.parseInt(cidr.substring(slash + 1).trim());
                byte[] addr = java.net.InetAddress.getByName(host).getAddress();
                if (prefix < 0 || prefix > addr.length * 8) return null;
                return new Subnet(addr, prefix);
            } catch (Exception e) {
                return null;
            }
        }
    }

    public static ZtInfoServer start(Path gameDir) throws IOException {
        ZeroTierService zt = new ZeroTierService(gameDir);
        ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "zt-info");
            t.setDaemon(true);
            return t;
        });
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", PORT), 0);
        ZtInfoServer self = new ZtInfoServer(server, pool, zt, gameDir);
        self.refreshOverlaySubnets();
        server.createContext("/zt/members", self::handleMembers);
        server.createContext("/zt/nick", self::handleNick);
        server.createContext("/zt/create", self::handleCreate);
        server.createContext("/zt/authorize", self::handleAuthorize);
        server.setExecutor(pool);
        server.start();
        return self;
    }

    /**
     * Запрос пришёл из браузера? Тогда это не наш телефон, а чужая страница, открытая у человека.
     *
     * <p>{@code Origin} браузер обязан ставить на любой POST и на любой кросс-доменный запрос,
     * {@code Referer} — на подгрузку картинкой. Родные клиенты (телефонное приложение,
     * HttpURLConnection, OkHttp) не присылают ни того, ни другого, если их об этом не просить.
     * Поэтому наличие любого из двух — верный признак, что запрос сделан не нами.
     */
    private boolean fromBrowser(com.sun.net.httpserver.HttpExchange ex) {
        var headers = ex.getRequestHeaders();
        return headers.getFirst("Origin") != null || headers.getFirst("Referer") != null;
    }

    /**
     * Адрес отправителя лежит внутри подсети, которую ZeroTier выдал этой машине?
     *
     * <p>Если подсетей выяснить не удалось (ZeroTier не отвечает, ни одна комната не
     * подключена) — возвращаем true. Это сознательный выбор в сторону работоспособности:
     * запретить всё, не зная оверлея, значит сломать телефон у людей, у которых ZeroTier только
     * поднимается. Проверка нужна против соседа по локалке, а он опасен ровно тогда, когда
     * комната есть, то есть когда подсеть как раз известна.
     */
    private boolean fromOverlay(com.sun.net.httpserver.HttpExchange ex) {
        List<Subnet> subnets = overlaySubnets;
        if (System.currentTimeMillis() - subnetsReadAt > SUBNETS_TTL_MS) {
            refreshOverlaySubnets();
            subnets = overlaySubnets;
        }
        if (subnets.isEmpty()) return true;

        java.net.InetSocketAddress remote = ex.getRemoteAddress();
        if (remote == null || remote.getAddress() == null) return false;
        byte[] addr = remote.getAddress().getAddress();
        for (Subnet net : subnets) {
            if (net.contains(addr)) return true;
        }
        return false;
    }

    private void refreshOverlaySubnets() {
        List<Subnet> fresh = new java.util.ArrayList<>();
        try {
            for (ZeroTierService.NetworkInfo n : zt.listNetworks()) {
                if (n.ips == null) continue;
                for (String cidr : n.ips) {
                    Subnet net = Subnet.parse(cidr);
                    if (net != null) fresh.add(net);
                }
            }
        } catch (Exception e) {
            // ZeroTier молчит — оставляем список пустым, см. оговорку в fromOverlay
        }
        overlaySubnets = List.copyOf(fresh);
        subnetsReadAt = System.currentTimeMillis();
    }

    /**
     * Общая проверка для ручек, которые меняют состояние контроллера.
     * Возвращает true, если запрос обслуживать НЕ надо (ответ уже отправлен).
     *
     * @param requireOverlay требовать, чтобы запрос пришёл из подсети ZeroTier. Для
     *                       {@code /zt/create} — НЕТ, и это не недосмотр: см. ниже.
     */
    private boolean rejectWrite(com.sun.net.httpserver.HttpExchange ex, String what,
                                boolean requireOverlay) throws IOException {
        if (fromBrowser(ex)) {
            log("[ZT-INFO] " + what + ": отказ, запрос из браузера (Origin/Referer) — "
                    + ex.getRemoteAddress());
            respond(ex, 403, "{\"error\":\"browser requests are not allowed\"}");
            return true;
        }
        if (requireOverlay && !fromOverlay(ex)) {
            log("[ZT-INFO] " + what + ": отказ, адрес вне подсетей ZeroTier — "
                    + ex.getRemoteAddress());
            respond(ex, 403, "{\"error\":\"only ZeroTier peers may change rooms\"}");
            return true;
        }
        return false;
    }

    /** Не чаще CREATE_LIMIT комнат за CREATE_WINDOW_MS — защита от засыпания контроллера. */
    private boolean createAllowed() {
        long now = System.currentTimeMillis();
        synchronized (createTimes) {
            while (!createTimes.isEmpty() && now - createTimes.peekFirst() > CREATE_WINDOW_MS) {
                createTimes.pollFirst();
            }
            if (createTimes.size() >= CREATE_LIMIT) return false;
            createTimes.addLast(now);
            return true;
        }
    }

    /** POST /zt/create?name=…&private=0|1 → creates a room on this PC's controller, returns its ID. */
    private void handleCreate(com.sun.net.httpserver.HttpExchange ex) throws IOException {
        try {
            // Проверку «только из подсети ZeroTier» здесь НЕ применяем, и это проверено по
            // исходникам приложения на телефоне (BLauncher-android, ZtRoomClient).
            //
            // Создание комнаты — единственная операция, которую телефон выполняет ДО того, как
            // попал в оверлей: ZtRoomClient.findHostAddress ищет ПК по сохранённым адресам, по
            // LAN-адресу телефона и сканированием его подсети, потому что «ПК в той же Wi-Fi
            // сети находится по своему LAN-адресу даже без комнаты». Требование оверлея здесь
            // ломало создание второй комнаты: первая работала (у ПК ещё нет ZT-адресов, и
            // fromOverlay пропускает всех), а когда одна комната уже есть — отказ 403.
            //
            // Риск от этого ограниченный: создание даёт пустую комнату, в которой посторонний
            // не участник, и оно ограничено пятью комнатами за десять минут. Опасная операция —
            // /zt/authorize, впустить себя в чужую приватную комнату, и там оверлей требуется:
            // телефон владельца всегда зовёт её по ZT-адресу ПК из ответа /zt/members.
            if (rejectWrite(ex, "create", false)) return;
            if (!createAllowed()) {
                log("[ZT-INFO] create: отказ, слишком много комнат за последние "
                        + (CREATE_WINDOW_MS / 60000) + " минут");
                respond(ex, 429, "{\"error\":\"too many rooms created, try later\"}");
                return;
            }
            String name = queryParam(ex, "name");
            String privateParam = queryParam(ex, "private");
            boolean privateRoom = "1".equals(privateParam) || "true".equals(privateParam);
            if (name == null) name = "";
            if (name.length() > 40) name = name.substring(0, 40);
            String nwid = zt.createRoomLocal(name, privateRoom,
                    msg -> { /* телефону не нужен лог создания */ });
            respond(ex, 200, "{\"ok\":true,\"nwid\":\"" + nwid + "\"}");
        } catch (Throwable t) {
            respond(ex, 500, "{\"error\":\"" + json(String.valueOf(t)) + "\"}");
        }
    }

    /** GET /zt/members?nwid=… → JSON list of members. */
    private void handleMembers(com.sun.net.httpserver.HttpExchange ex) throws IOException {
        try {
            // Чтение из браузера тоже отсекаем: страница не должна узнавать состав комнаты,
            // ники и выданные адреса. Для родного клиента это ничего не меняет.
            if (fromBrowser(ex)) {
                log("[ZT-INFO] members: отказ, запрос из браузера — " + ex.getRemoteAddress());
                respond(ex, 403, "{\"error\":\"browser requests are not allowed\"}");
                return;
            }
            String nwid = queryParam(ex, "nwid");
            if (nwid == null || !nwid.matches("[0-9a-fA-F]{16}")) {
                respond(ex, 400, "{\"error\":\"bad nwid\"}");
                return;
            }
            List<ZeroTierService.RoomMember> members = zt.listMembers(nwid);
            String hostIp;
            try {
                hostIp = zt.getMyVpnIpForRoom(nwid);
            } catch (Exception e) {
                hostIp = null;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("{\"nwid\":\"").append(nwid).append("\",\"hostIp\":");
            sb.append(hostIp == null ? "null" : "\"" + hostIp + "\"");
            sb.append(",\"members\":[");
            if (members != null) {
                for (int i = 0; i < members.size(); i++) {
                    ZeroTierService.RoomMember m = members.get(i);
                    if (i > 0) sb.append(',');
                    sb.append("{\"address\":\"").append(json(m.address))
                            .append("\",\"name\":\"").append(json(m.name))
                            .append("\",\"ip\":\"").append(json(m.ip))
                            .append("\",\"authorized\":").append(m.authorized)
                            .append(",\"owner\":").append(m.owner)
                            .append('}');
                }
            }
            sb.append("]}");
            respond(ex, 200, sb.toString());
        } catch (Throwable t) {
            respond(ex, 500, "{\"error\":\"" + json(String.valueOf(t)) + "\"}");
        }
    }

    /** POST /zt/nick?nwid=…&address=…&name=… → names a member on the controller. */
    private void handleNick(com.sun.net.httpserver.HttpExchange ex) throws IOException {
        try {
            // Оверлей требуется: приложение на телефоне эту ручку не зовёт вовсе (проверено
            // по исходникам), так что ограничение ничего не ломает.
            if (rejectWrite(ex, "nick", true)) return;
            String nwid = queryParam(ex, "nwid");
            String address = queryParam(ex, "address");
            String name = queryParam(ex, "name");
            if (nwid == null || !nwid.matches("[0-9a-fA-F]{16}")
                    || address == null || !address.matches("[0-9a-fA-F]{10}")
                    || name == null || name.length() > 32) {
                respond(ex, 400, "{\"error\":\"bad params\"}");
                return;
            }
            zt.setMemberName(nwid, address, name);
            respond(ex, 200, "{\"ok\":true}");
        } catch (Throwable t) {
            respond(ex, 500, "{\"error\":\"" + json(String.valueOf(t)) + "\"}");
        }
    }

    /** POST/GET /zt/authorize?nwid=…&address=… → approves a player waiting to enter a private room. */
    private void handleAuthorize(com.sun.net.httpserver.HttpExchange ex) throws IOException {
        try {
            // Оверлей требуется обязательно: это и есть та операция, ради которой вся проверка
            // затевалась — «сосед по Wi-Fi впускает себя в приватную комнату». Телефон владельца
            // зовёт её по ZT-адресу ПК (hostIp из ответа /zt/members), так что не страдает.
            if (rejectWrite(ex, "authorize", true)) return;
            String nwid = queryParam(ex, "nwid");
            String address = queryParam(ex, "address");
            if (nwid == null || !nwid.matches("[0-9a-fA-F]{16}")
                    || address == null || !address.matches("[0-9a-fA-F]{10}")) {
                respond(ex, 400, "{\"error\":\"bad params\"}");
                return;
            }
            zt.authorizeMember(nwid, address);
            respond(ex, 200, "{\"ok\":true}");
        } catch (Throwable t) {
            respond(ex, 500, "{\"error\":\"" + json(String.valueOf(t)) + "\"}");
        }
    }

    private static String queryParam(com.sun.net.httpserver.HttpExchange ex, String key) {
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) return null;
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            if (key.equals(pair.substring(0, eq))) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String json(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    @Override
    public void close() {
        server.stop(0);
        pool.shutdownNow();
    }
}
