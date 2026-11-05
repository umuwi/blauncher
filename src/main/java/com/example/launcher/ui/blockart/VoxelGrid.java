package com.example.launcher.ui.blockart;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Постройка из блоков: коробка w×h×l и в каждой клетке номер в палитре. 0 — воздух. Состояние блока записано
 * как в игре: «minecraft:oak_log[axis=y]».
 */
final class VoxelGrid {

    static final String AIR = "minecraft:air";
    /** Больше по любой стороне не открываем: 16 млн клеток — это уже около 32 МБ памяти. */
    static final int MAX_SIDE = 256;

    final int w;
    final int h;
    final int l;
    final short[] cells;
    final List<String> palette = new ArrayList<>();
    private final Map<String, Short> index = new HashMap<>();

    VoxelGrid(int w, int h, int l) {
        if (w < 1 || h < 1 || l < 1 || w > MAX_SIDE || h > MAX_SIDE || l > MAX_SIDE) {
            throw new IllegalArgumentException(w + "x" + h + "x" + l);
        }
        this.w = w;
        this.h = h;
        this.l = l;
        cells = new short[w * h * l];
        idOf(AIR);
    }

    short idOf(String state) {
        return index.computeIfAbsent(state, s -> {
            palette.add(s);
            return (short) (palette.size() - 1);
        });
    }

    int at(int x, int y, int z) {
        return cells[x + z * w + y * w * l];
    }

    void set(int x, int y, int z, short id) {
        cells[x + z * w + y * w * l] = id;
    }

    boolean empty(int x, int y, int z) {
        return x < 0 || y < 0 || z < 0 || x >= w || y >= h || z >= l || cells[x + z * w + y * w * l] == 0;
    }

    int blockCount() {
        int n = 0;
        for (short c : cells) if (c != 0) n++;
        return n;
    }

    /** Имя блока без пространства имён и свойств: «oak_log». */
    static String idPart(String state) {
        int b = state.indexOf('[');
        String s = b >= 0 ? state.substring(0, b) : state;
        return s.startsWith("minecraft:") ? s.substring(10) : s;
    }
}
