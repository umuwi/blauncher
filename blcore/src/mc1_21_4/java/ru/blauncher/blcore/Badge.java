package ru.blauncher.blcore;

import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.UUID;

/** The BLauncher logo as one glyph of the mod's own font, put in front of a name. */
public final class Badge {

    private static final String GLYPH = "";
    private static final Identifier FONT = Identifier.of("blcore", "badge");
    private static final Style STYLE = Style.EMPTY.withFont(FONT).withColor(0xFFFFFF).withBold(false).withItalic(false);

    private Badge() {}

    /** The name with the badge in front when this player plays through BLauncher; the name itself otherwise. */
    public static Text decorate(Text name, String playerName, UUID uuid) {
        if (name == null) return null;
        if (!BlRegistry.get(playerName, uuid).bl()) return name;
        // already decorated (another call site handed the result back in)
        if (name.getString().startsWith(GLYPH)) return name;
        MutableText out = Text.literal(GLYPH).setStyle(STYLE);
        out.append(Text.literal(" ").setStyle(Style.EMPTY));
        out.append(name);
        return out;
    }
}
