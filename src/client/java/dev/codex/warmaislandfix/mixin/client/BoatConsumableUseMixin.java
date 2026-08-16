package dev.codex.warmaislandfix.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.codex.warmaislandfix.BoatConsumableSupport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
abstract class BoatConsumableUseMixin {
    @ModifyExpressionValue(
            method = "startUseItem",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;isHandsBusy()Z"
            )
    )
    private boolean warmaIslandFix$allowConsumablesWhileRowing(boolean handsBusy) {
        LocalPlayer player = ((Minecraft) (Object) this).player;
        return handsBusy && (player == null || !BoatConsumableSupport.canStartUsingConsumable(player));
    }
}
