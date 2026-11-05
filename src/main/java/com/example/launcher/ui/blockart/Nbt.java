package com.example.launcher.ui.blockart;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Формат NBT игры: чертежи .schem, .litematic и структуры .nbt — это он в gzip. Значения — обычные объекты
 * Java: Map (compound), List, Byte, Short, Integer, Long, Float, Double, String, byte[], int[], long[].
 */
final class Nbt {

    private static final int MAX_DEPTH = 64;

    private Nbt() {
    }

    static Map<String, Object> read(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file))))) {
            int type = in.readUnsignedByte();
            if (type != 10) throw new IOException("not an NBT compound");
            in.readUTF();
            return compound(in, 0);
        }
    }

    private static Map<String, Object> compound(DataInputStream in, int depth) throws IOException {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int type; (type = in.readUnsignedByte()) != 0; ) {
            String name = in.readUTF();
            out.put(name, value(in, type, depth + 1));
        }
        return out;
    }

    private static Object value(DataInputStream in, int type, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT too deep");
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: {
                byte[] a = new byte[length(in)];
                in.readFully(a);
                return a;
            }
            case 8: return in.readUTF();
            case 9: {
                int inner = in.readUnsignedByte();
                int n = in.readInt();
                if (n < 0) throw new IOException("bad NBT list");
                List<Object> list = new ArrayList<>(Math.min(n, 1 << 16));
                for (int i = 0; i < n; i++) list.add(value(in, inner, depth + 1));
                return list;
            }
            case 10: return compound(in, depth);
            case 11: {
                int[] a = new int[length(in)];
                for (int i = 0; i < a.length; i++) a[i] = in.readInt();
                return a;
            }
            case 12: {
                long[] a = new long[length(in)];
                for (int i = 0; i < a.length; i++) a[i] = in.readLong();
                return a;
            }
            default: throw new IOException("NBT tag " + type);
        }
    }

    /** Чужой файл не должен заставить выделить гигабайты: больше 256 блоков по стороне мы и не открываем. */
    private static int length(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > 64_000_000) throw new IOException("NBT array too long");
        return n;
    }

    static void write(Path file, String rootName, Map<String, Object> root) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp))))) {
            out.writeByte(10);
            out.writeUTF(rootName);
            writeCompound(out, root);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static void writeCompound(DataOutputStream out, Map<?, ?> map) throws IOException {
        for (Map.Entry<?, ?> e : map.entrySet()) {
            out.writeByte(typeOf(e.getValue()));
            out.writeUTF((String) e.getKey());
            writeValue(out, e.getValue());
        }
        out.writeByte(0);
    }

    private static int typeOf(Object v) {
        if (v instanceof Byte) return 1;
        if (v instanceof Short) return 2;
        if (v instanceof Integer) return 3;
        if (v instanceof Long) return 4;
        if (v instanceof byte[]) return 7;
        if (v instanceof String) return 8;
        if (v instanceof List) return 9;
        if (v instanceof Map) return 10;
        if (v instanceof int[]) return 11;
        if (v instanceof long[]) return 12;
        throw new IllegalArgumentException("NBT value " + v);
    }

    private static void writeValue(DataOutputStream out, Object v) throws IOException {
        if (v instanceof Byte b) {
            out.writeByte(b);
        } else if (v instanceof Short s) {
            out.writeShort(s);
        } else if (v instanceof Integer i) {
            out.writeInt(i);
        } else if (v instanceof Long l) {
            out.writeLong(l);
        } else if (v instanceof byte[] a) {
            out.writeInt(a.length);
            out.write(a);
        } else if (v instanceof String s) {
            out.writeUTF(s);
        } else if (v instanceof List<?> list) {
            out.writeByte(list.isEmpty() ? 0 : typeOf(list.get(0)));
            out.writeInt(list.size());
            for (Object o : list) writeValue(out, o);
        } else if (v instanceof Map<?, ?> m) {
            writeCompound(out, m);
        } else if (v instanceof int[] a) {
            out.writeInt(a.length);
            for (int x : a) out.writeInt(x);
        } else if (v instanceof long[] a) {
            out.writeInt(a.length);
            for (long x : a) out.writeLong(x);
        }
    }
}
