package com.example.launcher.service.vps;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Несколько Minecraft-серверов на одном VPS (26.10.2026): сколько памяти и ядер у машины и сколько
 * уже отдано серверам BLauncher. Без JavaFX.
 *
 * <p>Правда — на самом VPS, а не в servers.json: сервер мог поставить другой ПК, а запись в
 * лаунчере могла пропасть. Поэтому считаются все юниты {@code blauncher-mc-*.service}, включая
 * остановленные: установленный сервер включён в автозапуск, и после перезагрузки VPS встанут
 * все разом — память под них должна найтись одновременно.
 *
 * <p>Сервер «стоит» не только свой -Xmx: сама Java (метапространство, потоки, код JIT, буферы
 * сети) берёт сверху ещё около десятой части кучи — {@link #footprintMb}. Плюс системе и sshd
 * остаётся {@link VpsInstaller#SYSTEM_RESERVE_MB}.
 */
public final class VpsCapacity {

    /** Один сервер BLauncher на VPS. ramMb = -1, port = -1 — не удалось прочитать. */
    public record Server(String id, String service, int ramMb, int port, int rconPort, boolean active, long memoryBytes) {}

    /**
     * @param memTotalMb вся память VPS
     * @param cpus       ядер
     * @param servers    серверы BLauncher (юниты blauncher-mc-*)
     * @param listening  TCP-порты, которые на VPS сейчас кто-то слушает (любые программы)
     */
    public record Snapshot(long memTotalMb, int cpus, List<Server> servers, List<Integer> listening) {

        /** Сколько всего можно раздать серверам. */
        public long budgetMb() {
            return Math.max(0, memTotalMb - VpsInstaller.SYSTEM_RESERVE_MB);
        }

        /** Отдано серверам, кроме {@code exceptId} (повторная установка того же сервера себя не считает). */
        public long usedMb(String exceptId) {
            long sum = 0;
            for (Server s : servers) {
                if (exceptId != null && exceptId.equalsIgnoreCase(s.id())) continue;
                sum += footprintMb(s.ramMb() > 0 ? s.ramMb() : DEFAULT_UNKNOWN_MB);
            }
            return sum;
        }

        /** Самый большой -Xmx, который ещё влезет новому (или переустанавливаемому) серверу. */
        public int maxRamFor(String serverId) {
            long free = budgetMb() - usedMb(serverId);
            // Обратное к footprintMb: x + max(128, x/10) ≤ free.
            long x = Math.min(free - 128, free * 10 / 11);
            return (int) Math.max(0, x);
        }

        /** Порты Minecraft и RCON всех серверов BLauncher, кроме {@code exceptId} — даже остановленных. */
        public List<Integer> takenPorts(String exceptId) {
            List<Integer> out = new ArrayList<>();
            for (Server s : servers) {
                if (exceptId != null && exceptId.equalsIgnoreCase(s.id())) continue;
                if (s.port() > 0) out.add(s.port());
                if (s.rconPort() > 0) out.add(s.rconPort());
            }
            return out;
        }

        public Server find(String serverId) {
            for (Server s : servers) if (s.id().equalsIgnoreCase(serverId)) return s;
            return null;
        }
    }

    /** Если -Xmx не прочитался (юнит правили руками), считаем как за средний сервер — лучше перестраховаться. */
    static final int DEFAULT_UNKNOWN_MB = 2048;

    /** Сколько памяти VPS на деле займёт сервер с таким -Xmx. */
    public static long footprintMb(int xmxMb) {
        return xmxMb + Math.max(128, xmxMb / 10);
    }

    private static final Duration SHORT = Duration.ofSeconds(30);
    private static final Pattern SERVICE = Pattern.compile("^blauncher-mc-([0-9a-f-]{8,64})$");

    /**
     * Один вызов от root (папки серверов — 750 у minecraft): память, ядра и по строке на сервер.
     * Память сервера: метка {@code # BLauncher-ram=} в юните (пишет установщик с 26.10.2026), иначе
     * -Xmx из ExecStart или из user_jvm_args.txt (Forge/NeoForge запускаются через run.sh).
     */
    static final String SCRIPT = String.join("\n",
            "awk '/^MemTotal:/ {print \"mem\", $2}' /proc/meminfo",
            "echo \"cpus $(nproc 2>/dev/null || echo 1)\"",
            "for f in /etc/systemd/system/blauncher-mc-*.service; do",
            "  [ -f \"$f\" ] || continue",
            "  n=$(basename \"$f\" .service)",
            "  d=$(sed -n 's/^WorkingDirectory=//p' \"$f\" | head -n 1)",
            "  r=$(sed -n 's/^# BLauncher-ram=\\([0-9][0-9]*\\).*/\\1M/p' \"$f\" | head -n 1)",
            "  [ -n \"$r\" ] || r=$(cat \"$f\" \"$d/user_jvm_args.txt\" 2>/dev/null | grep -o -- '-Xmx[0-9]*[MmGgKk]' | head -n 1 | sed 's/^-Xmx//')",
            "  p=$(sed -n 's/^server-port=//p' \"$d/server.properties\" 2>/dev/null | head -n 1 | tr -dc 0-9)",
            "  q=$(sed -n 's/^rcon.port=//p' \"$d/server.properties\" 2>/dev/null | head -n 1 | tr -dc 0-9)",
            "  a=$(systemctl is-active \"$n\" 2>/dev/null)",
            "  m=$(systemctl show -p MemoryCurrent --value \"$n\" 2>/dev/null | tr -dc 0-9)",
            "  echo \"srv $n ${r:-?} ${p:-?} ${q:-?} ${a:-?} ${m:-?}\"",
            "done",
            "echo \"listen $(ss -Htln 2>/dev/null | awk '{print $4}' | sed 's/.*://' | sort -un | tr '\\n' ' ')\"");

    public Snapshot read(VpsSession s) throws VpsException {
        VpsSession.ExecResult r = s.execRoot(SCRIPT, SHORT);
        if (!r.ok()) throw new VpsException("Не удалось прочитать, что стоит на VPS" + tail(r.stderr()));
        return parse(r.stdout());
    }

    static Snapshot parse(String out) {
        long memKb = 0;
        int cpus = 1;
        List<Server> list = new ArrayList<>();
        List<Integer> listening = new ArrayList<>();
        for (String line : out.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length == 2 && f[0].equals("mem")) memKb = parseLong(f[1]);
            else if (f.length == 2 && f[0].equals("cpus")) cpus = (int) Math.max(1, parseLong(f[1]));
            else if (f.length >= 1 && f[0].equals("listen")) {
                for (int i = 1; i < f.length; i++) {
                    long p = parseLong(f[i]);
                    if (p > 0 && p <= 65535) listening.add((int) p);
                }
            }
            else if (f.length == 7 && f[0].equals("srv")) {
                Matcher m = SERVICE.matcher(f[1]);
                if (!m.matches()) continue;
                list.add(new Server(m.group(1), f[1], parseRam(f[2]), (int) parseLong(f[3]), (int) parseLong(f[4]),
                        "active".equals(f[5]) || "activating".equals(f[5]) || "reloading".equals(f[5]),
                        parseLong(f[6])));
            }
        }
        return new Snapshot(memKb / 1024, cpus, List.copyOf(list), List.copyOf(listening));
    }

    /** «4096M», «4G», «524288k» → МБ; -1 — не число. */
    static int parseRam(String v) {
        if (v == null || v.isEmpty() || v.equals("?")) return -1;
        char unit = Character.toLowerCase(v.charAt(v.length() - 1));
        String digits = Character.isDigit(unit) ? v : v.substring(0, v.length() - 1);
        long n = parseLong(digits);
        if (n <= 0) return -1;
        return (int) switch (unit) {
            case 'g' -> n * 1024;
            case 'k' -> n / 1024;
            case 'm' -> n;
            default -> n / (1024 * 1024);   // голое число у -Xmx — байты
        };
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String tail(String err) {
        if (err == null || err.isBlank()) return "";
        String t = err.strip();
        return ": " + (t.length() > 200 ? t.substring(t.length() - 200) : t);
    }

    /** «3,5 ГБ» / «768 МБ» — для подписей. */
    public static String mbText(long mb) {
        if (mb < 1024) return mb + " МБ";
        double gb = mb / 1024.0;
        String s = gb == Math.floor(gb) ? String.valueOf((long) gb) : String.format(Locale.ROOT, "%.1f", gb);
        return s.replace('.', ',') + " ГБ";
    }
}
