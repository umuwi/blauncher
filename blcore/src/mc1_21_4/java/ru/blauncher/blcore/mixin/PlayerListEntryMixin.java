package ru.blauncher.blcore.mixin;

import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.util.SkinTextures;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.blauncher.blcore.BlRegistry;
import ru.blauncher.blcore.Capes;

/**
 * A BLauncher cape for players who have one and no cape of their own: an official Mojang cape
 * always wins, BLauncher only fills the empty slot.
 */
@Mixin(PlayerListEntry.class)
public abstract class PlayerListEntryMixin {

    @Inject(method = "getSkinTextures", at = @At("RETURN"), cancellable = true, require = 0)
    private void blcore$cape(CallbackInfoReturnable<SkinTextures> cir) {
        try {
            SkinTextures t = cir.getReturnValue();
            if (t == null || t.capeTexture() != null) return;
            var profile = ((PlayerListEntry) (Object) this).getProfile();
            BlRegistry.Mark mark = BlRegistry.get(profile.getName(), profile.getId());
            if (!mark.bl() || mark.capeHash() == null) return;
            Identifier cape = Capes.texture(mark.capeHash());
            if (cape == null) return;
            cir.setReturnValue(new SkinTextures(t.texture(), t.textureUrl(), cape, null, t.model(), t.secure()));
        } catch (Throwable ignored) {
            // the player keeps the textures the game gave them
        }
    }
}
