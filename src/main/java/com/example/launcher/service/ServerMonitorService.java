package com.example.launcher.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Implements Minecraft's Server List Ping protocol (the same one the multiplayer server list uses)
 * to query a public Minecraft server's status: online/offline, MOTD, version, player count, and
 * round-trip latency. Pure Java + TCP — no third-party dependencies.
 *
 * <p>This is the protocol a client speaks to the server's status port before joining. It works for
 * vanilla, Paper, Purpur, Forge, Fabric, proxies (Velocity/BungeeCord) — anything that implements
 * the standard status handshake.
 */
public class ServerMonitorService {

    /** A single monitorable server's current state, refreshed on demand. */
    public static class ServerStatus {
        public final String host;
        public final int port;
        public final boolean online;
        public final String motd;
        public final String version;
        public final int onlinePlayers;
        public final int maxPlayers;
        public final long pingMs;

        public ServerStatus(String host, int port, boolean online, String motd, String version,
                            int onlinePlayers, int maxPlayers, long pingMs) {
            this.host = host;
            this.port = port;
            this.online = online;
            this.motd = motd;
            this.version = version;
            this.onlinePlayers = onlinePlayers;
            this.maxPlayers = maxPlayers;
            this.pingMs = pingMs;
        }

        public static ServerStatus offline(String host, int port) {
            return new ServerStatus(host, port, false, null, null, 0, 0, -1);
        }
    }

    private static final int DEFAULT_PORT = 25565;
    private static final int CONNECT_TIMEOUT_MS = 4000;
    private static final int READ_TIMEOUT_MS = 4000;

    /** Parses "host", "host:port", "host:25565" into address parts (port defaults to 25565). */
    public static String[] parseAddress(String address) {
        if (address == null || address.isBlank()) return null;
        String trimmed = address.trim();
        if (trimmed.startsWith("[")) {
            // IPv6 literal in [..]:port form, e.g. [::1]:25565
            int closing = trimmed.indexOf(']');
            if (closing > 0) {
                String host = trimmed.substring(1, closing);
                String rest = trimmed.substring(closing + 1);
                int port = DEFAULT_PORT;
                if (rest.startsWith(":") && rest.length() > 1) {
                    try { port = Integer.parseInt(rest.substring(1)); } catch (NumberFormatException ignored) {}
                }
                return new String[]{host, String.valueOf(port)};
            }
            return new String[]{trimmed, String.valueOf(DEFAULT_PORT)};
        }
        int lastColon = trimmed.lastIndexOf(':');
        if (lastColon > 0 && lastColon < trimmed.length() - 1) {
            // More than one colon => raw IPv6 without brackets — not supported for status anyway
            if (trimmed.indexOf(':') != lastColon) {
                return new String[]{trimmed, String.valueOf(DEFAULT_PORT)};
            }
            String host = trimmed.substring(0, lastColon);
            String portStr = trimmed.substring(lastColon + 1);
            try {
                int port = Integer.parseInt(portStr);
                return new String[]{host, String.valueOf(port)};
            } catch (NumberFormatException ignored) {
                return new String[]{trimmed, String.valueOf(DEFAULT_PORT)};
            }
        }
        return new String[]{trimmed, String.valueOf(DEFAULT_PORT)};
    }

    /** Pings the server and returns its status. Never throws — failures become {@link #offline}. */
    public ServerStatus ping(String address) {
        String[] parts = parseAddress(address);
        if (parts == null) return ServerStatus.offline("", DEFAULT_PORT);
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);
        try {
            long start = System.currentTimeMillis();
            // Сокет создаётся внутри try-with-resources: прежний try/finally начинался уже после
            // connect(), так что сорвавшееся подключение (а это обычный случай для офлайн-сервера)
            // оставляло незакрытый сокет. Монитор опрашивает список по таймеру — дескрипторы
            // копились до сборки мусора.
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(READ_TIMEOUT_MS);
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                DataInputStream in = new DataInputStream(socket.getInputStream());

                // Handshake packet: id 0x00, protocol version, host, port, next state 1 (status).
                // Each packet is framed with a VarInt length prefix.
                byte[] handshakePayload;
                try {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                    DataOutputStream p = new DataOutputStream(bo);
                    p.writeByte(0x00); // handshake packet id
                    writeVarInt(p, 765); // MC 1.20.x protocol — accepted by every modern server for status
                    writeString(p, host);
                    p.writeShort(port);
                    writeVarInt(p, 1); // next state: status
                    p.flush();
                    handshakePayload = bo.toByteArray();
                } catch (IOException e) {
                    return ServerStatus.offline(host, port);
                }
                writeVarInt(out, handshakePayload.length);
                out.write(handshakePayload);
                out.flush();

                // Status request: empty packet with id 0x00
                writeVarInt(out, 1);
                out.writeByte(0x00);
                out.flush();

                // Status response: packet length + packet id 0x00 + JSON string
                readVarInt(in); // packet length
                int packetId = readVarInt(in);
                if (packetId != 0x00) {
                    return ServerStatus.offline(host, port);
                }
                String statusJson = readString(in);

                long ping = System.currentTimeMillis() - start;

                JsonObject obj = JsonParser.parseString(statusJson).getAsJsonObject();
                String motd = extractPlainText(obj.has("description")
                        ? obj.get("description").toString() : "—");
                String version = obj.has("version") && obj.getAsJsonObject("version").has("name")
                        ? obj.getAsJsonObject("version").get("name").getAsString() : "";
                int online = 0, max = 0;
                if (obj.has("players")) {
                    JsonObject players = obj.getAsJsonObject("players");
                    online = players.has("online") ? players.get("online").getAsInt() : 0;
                    max = players.has("max") ? players.get("max").getAsInt() : 0;
                }

                // Optional latency packet: send ping, read pong — gives more precise RTT.
                long precisePing = measurePing(in, out);

                return new ServerStatus(host, port, true, motd, version, online, max,
                        precisePing >= 0 ? precisePing : ping);
            }
        } catch (Exception e) {
            return ServerStatus.offline(host, port);
        }
    }

    /** Sends the 0x01 ping packet and reads the 0x01 pong, returning RTT in ms (or -1 on failure). */
    private long measurePing(DataInputStream in, DataOutputStream out) {
        try {
            long sent = System.currentTimeMillis();
            // Ping packet: length (9) + id 0x01 + 8-byte payload
            writeVarInt(out, 9);
            out.writeByte(0x01);
            out.writeLong(System.currentTimeMillis());
            out.flush();
            readVarInt(in); // packet length
            int packetId = readVarInt(in);
            if (packetId != 0x01) return -1;
            in.readLong();
            return System.currentTimeMillis() - sent;
        } catch (IOException e) {
            return -1;
        }
    }

    /** Strips §-colour codes and extracts plain text from a MOTD JSON (string or {"text": ...}). */
    private static String extractPlainText(String json) {
        StringBuilder sb = new StringBuilder();
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            appendText(obj, sb);
        } catch (Exception e) {
            // already a plain string with surrounding quotes, or unparseable — strip quotes/colours
            if (json.length() >= 2 && json.startsWith("\"") && json.endsWith("\"")) {
                json = json.substring(1, json.length() - 1);
            }
            sb.append(json);
        }
        String result = sb.toString().replaceAll("§.", "").trim();
        return result.isEmpty() ? "—" : result;
    }

    private static void appendText(JsonObject obj, StringBuilder sb) {
        if (obj.has("text") && obj.get("text").isJsonPrimitive()) {
            sb.append(obj.get("text").getAsString());
        }
        if (obj.has("extra") && obj.get("extra").isJsonArray()) {
            for (var extra : obj.getAsJsonArray("extra")) {
                if (extra.isJsonObject()) {
                    appendText(extra.getAsJsonObject(), sb);
                } else if (extra.isJsonPrimitive()) {
                    sb.append(extra.getAsString());
                }
            }
        }
    }

    // ---------- Minecraft protocol primitives ----------

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while (true) {
            int bits = value & 0x7F;
            value >>>= 7;
            if (value != 0) {
                out.writeByte(bits | 0x80);
            } else {
                out.writeByte(bits);
                return;
            }
        }
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int result = 0;
        int shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift > 35) throw new IOException("VarInt too big");
        }
        return result;
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int len = readVarInt(in);
        if (len < 0 || len > 32767) throw new IOException("Invalid string length: " + len);
        byte[] bytes = new byte[len];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

}
