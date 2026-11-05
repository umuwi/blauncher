package com.example.launcher.ui.blockart;

import com.example.launcher.ui.studio.McAssets;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Какие блоки годятся для картинки и какого они цвета: средний цвет настоящих текстур из файлов игры у
 * игрока, ничего не качается. Берём только полные непрозрачные кубы, которые спокойно стоят сами.
 */
final class BlockColors {

    record Block(String state, int rgb) {}

    /** Падают, светятся по команде, ломаются, запрещены в выживании или выглядят не так, как в файле текстуры. */
    private static final Pattern UNSUITABLE = Pattern.compile(
            "(^|_)(sand|gravel|concrete_powder|command_block|structure_block|jigsaw|barrier|spawner|bedrock|tnt"
                    + "|infested|shulker_box|budding|glass|ice|slime|honey|leaves|piston|observer|dispenser|dropper"
                    + "|repeating|chain_command|reinforced_deepslate|end_portal|portal|sculk_sensor|sculk_shrieker"
                    + "|trial_spawner|vault|test_block|test_instance_block|powder_snow|frosted|mushroom_stem|furnace"
                    + "|crafting_table|smithing_table|fletching_table|cartography_table|loom|lectern|target"
                    + "|sign|gateway|lava|water|pot|heart|crafter|bulb|jukebox|note_block|anchor|barrel|beehive|nest"
                    + "|melon|pumpkin|lantern|smoker|catalyst|magma|sculk|debris|ore|bookshelf|sponge|lodestone"
                    + "|redstone_lamp|grass_block|mycelium|podzol|farmland|dirt_path)(_|$)");
    private static final Pattern BASIC = Pattern.compile(".*(_wool|_concrete|_terracotta)$|^terracotta$");

    private final Map<String, Integer> byId = new HashMap<>();
    private final List<Block> all = new ArrayList<>();
    private final List<Block> basic = new ArrayList<>();

    BlockColors(McAssets assets) {
        for (String id : assets.blockIds()) {
            try {
                McAssets.BlockState state = assets.blockState(id);
                int rgb = average(assets, state.quads());
                if (rgb < 0) continue;
                byId.put(id, rgb);
                if (UNSUITABLE.matcher(id).find() || !isOpaqueCube(assets, state.quads())) continue;
                Block b = new Block(stateName(id, state.properties()), rgb);
                all.add(b);
                if (BASIC.matcher(id).matches()) basic.add(b);
            } catch (RuntimeException ignored) {
                // сломанная модель одного блока не мешает остальным
            }
        }
    }

    private static String stateName(String id, Map<String, String> props) {
        if (props.isEmpty()) return "minecraft:" + id;
        StringBuilder sb = new StringBuilder("minecraft:").append(id).append('[');
        boolean first = true;
        for (var e : props.entrySet()) {
            if (!first) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
            first = false;
        }
        return sb.append(']').toString();
    }

    List<Block> blocks(boolean onlyBasic) {
        return onlyBasic && !basic.isEmpty() ? basic : all;
    }

    /** Цвет блока для предпросмотра чужого чертежа; серый, если такого в этой версии нет. */
    int colorOf(String state) {
        Integer c = byId.get(VoxelGrid.idPart(state));
        return c != null ? c : 0x8A8A8A;
    }

    private static boolean isOpaqueCube(McAssets assets, List<McAssets.Quad> quads) {
        if (quads.size() != 6) return false;
        for (McAssets.Quad q : quads) {
            if (q.tinted()) return false;
            float[] p = q.pos();
            float minX = 9, maxX = -9, minY = 9, maxY = -9, minZ = 9, maxZ = -9;
            for (int i = 0; i < 12; i += 3) {
                minX = Math.min(minX, p[i]);
                maxX = Math.max(maxX, p[i]);
                minY = Math.min(minY, p[i + 1]);
                maxY = Math.max(maxY, p[i + 1]);
                minZ = Math.min(minZ, p[i + 2]);
                maxZ = Math.max(maxZ, p[i + 2]);
            }
            int full = 0;
            if (maxX - minX > 0.99f) full++;
            if (maxY - minY > 0.99f) full++;
            if (maxZ - minZ > 0.99f) full++;
            if (full != 2) return false;
            Image t = assets.texture(q.texture());
            if (t == null || t.getPixelReader() == null) return false;
            PixelReader r = t.getPixelReader();
            for (int y = 0; y < (int) t.getHeight(); y++) {
                for (int x = 0; x < (int) t.getWidth(); x++) {
                    if ((r.getArgb(x, y) >>> 24) < 250) return false;
                }
            }
        }
        return true;
    }

    /** Средний цвет по граням; -1 — у блока нет текстур. */
    private static int average(McAssets assets, List<McAssets.Quad> quads) {
        long r = 0, g = 0, b = 0, n = 0;
        for (McAssets.Quad q : quads) {
            Image t = assets.texture(q.texture());
            if (t == null || t.getPixelReader() == null) continue;
            PixelReader px = t.getPixelReader();
            for (int y = 0; y < (int) t.getHeight(); y++) {
                for (int x = 0; x < (int) t.getWidth(); x++) {
                    int c = px.getArgb(x, y);
                    if ((c >>> 24) < 128) continue;
                    r += (c >> 16) & 255;
                    g += (c >> 8) & 255;
                    b += c & 255;
                    n++;
                }
            }
        }
        if (n == 0) return -1;
        return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
    }
}
