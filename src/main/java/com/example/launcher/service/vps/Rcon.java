package com.example.launcher.service.vps;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Клиент RCON Minecraft. Подключается к 127.0.0.1:&lt;локальный порт туннеля&gt; (см.
 * {@link VpsSession#forwardLocal}): сам RCON-порт наружу не открыт.
 *
 * <p>Длинные ответы сервер режет на пакеты по 4096 байт, и признака «это последний» в протоколе
 * нет. Известный приём — послать следом пакет неизвестного типа и ждать «Unknown request» — с
 * Minecraft не годится: его RCON читает из сокета один кусок за раз и, если оба пакета пришли
 * вместе, рвёт соединение (07.10.2026 так обрывалась команда «say …» на Paper 1.21.4). Поэтому
 * продолжение дочитывается, только если пакет пришёл полным, и с коротким таймаутом.
 */
public final class Rcon implements AutoCloseable {

    private static final int TYPE_COMMAND = 2;
    private static final int TYPE_LOGIN = 3;
    /** Столько байт Minecraft кладёт в один пакет ответа; полный пакет — признак продолжения. */
    private static final int FRAGMENT = 4096;
    private static final int READ_TIMEOUT_MS = 15_000;
    /** Ответ команды не может быть больше этого — защита от бесконечного чтения. */
    private static final int MAX_RESPONSE = 1 << 20;

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextId = 1;

    private Rcon(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    /** Подключение и вход. Неверный пароль — VpsException с понятным текстом. */
    public static Rcon connect(int localPort, String password) throws VpsException {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress("127.0.0.1", localPort), 10_000);
            s.setSoTimeout(READ_TIMEOUT_MS);
            Rcon r = new Rcon(s);
            int id = r.send(TYPE_LOGIN, password);
            Packet p = r.read();
            if (p.id == -1) throw new VpsException("RCON не принял пароль — сохранённый пароль не совпадает с server.properties");
            if (p.id != id) throw new VpsException("RCON ответил не на тот запрос");
            return r;
        } catch (VpsException e) {
            closeQuietly(s);
            throw e;
        } catch (IOException e) {
            closeQuietly(s);
            throw new VpsException("RCON недоступен (сервер ещё запускается или выключен): " + e.getMessage(), e);
        }
    }

    /** Выполняет команду (без «/») и возвращает ответ сервера без цветовых кодов. */
    public synchronized String command(String command) throws VpsException {
        if (command == null || command.isBlank()) return "";
        if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("Команда RCON не может содержать перевод строки");
        }
        try {
            int id = send(TYPE_COMMAND, command);
            StringBuilder sb = new StringBuilder();
            Packet p;
            do {
                p = read();
            } while (p.id != id);
            sb.append(p.body);
            // Полный пакет (4096 байт) — значит, у ответа есть продолжение. Ждём его недолго: сервер
            // шлёт части подряд, а признака «последняя» в протоколе нет.
            socket.setSoTimeout(400);
            try {
                while (p.body.getBytes(StandardCharsets.UTF_8).length >= FRAGMENT && sb.length() < MAX_RESPONSE) {
                    p = read();
                    if (p.id == id) sb.append(p.body);
                }
            } catch (SocketTimeoutException endOfResponse) {
                // продолжения нет — ответ целиком
            } finally {
                socket.setSoTimeout(READ_TIMEOUT_MS);
            }
            return stripColors(sb.toString());
        } catch (SocketTimeoutException e) {
            throw new VpsException("Сервер не ответил на команду за 15 секунд", e);
        } catch (IOException e) {
            throw new VpsException("Связь с RCON прервалась: " + e.getMessage(), e);
        }
    }

    public boolean isOpen() {
        return !socket.isClosed();
    }

    @Override
    public void close() {
        closeQuietly(socket);
    }

    // ---------------------------------------------------------------- протокол

    private record Packet(int id, int type, String body) {}

    private int send(int type, String body) throws IOException {
        int id = nextId++;
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(14 + b.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(10 + b.length).putInt(id).putInt(type).put(b).put((byte) 0).put((byte) 0);
        out.write(buf.array());
        out.flush();
        return id;
    }

    private Packet read() throws IOException {
        byte[] lenBytes = in.readNBytes(4);
        if (lenBytes.length < 4) throw new EOFException("RCON закрыл соединение");
        int len = ByteBuffer.wrap(lenBytes).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (len < 10 || len > 1 << 16) throw new IOException("RCON прислал пакет странной длины: " + len);
        byte[] data = in.readNBytes(len);
        if (data.length < len) throw new EOFException("RCON оборвал пакет");
        ByteBuffer p = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int id = p.getInt();
        int type = p.getInt();
        String body = new String(data, 8, len - 10, StandardCharsets.UTF_8);
        return new Packet(id, type, body);
    }

    /** §a, §l и т.п. — коды цвета Minecraft, в консоли лаунчера они только мусор. */
    static String stripColors(String s) {
        return s.replaceAll("§[0-9a-fk-orA-FK-OR]", "");
    }

    private static void closeQuietly(Socket s) {
        try { s.close(); } catch (IOException ignored) {}
    }
}
