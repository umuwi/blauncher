package ru.blauncher.blcore.mixin;

import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.blauncher.blcore.Badge;

/**
 * The badge over a player's head: the name tag is the player's display name, so that is where it
 * goes. Only client-side players - in singleplayer the integrated server shares this class, and its
 * chat messages must stay untouched.
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin {

    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true, require = 0)
    private void blcore$badge(CallbackInfoReturnable<Text> cir) {
        try {
            if (!((Object) this instanceof AbstractClientPlayerEntity player)) return;
            var profile = player.getGameProfile();
            cir.setReturnValue(Badge.decorate(cir.getReturnValue(), profile.getName(), profile.getId()));
        } catch (Throwable ignored) {
            // a name tag without a badge is fine; a crash is not
        }
    }
}
