package ru.blauncher.blcore.mixin;

import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.blauncher.blcore.Badge;

/** The badge in the tab list, after the server's own formatting is applied. */
@Mixin(PlayerListHud.class)
public abstract class PlayerListHudMixin {

    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true, require = 0)
    private void blcore$badge(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        try {
            var profile = entry.getProfile();
            cir.setReturnValue(Badge.decorate(cir.getReturnValue(), profile.getName(), profile.getId()));
        } catch (Throwable ignored) {
            // never break the tab list over a badge
        }
    }
}
