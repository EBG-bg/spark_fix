package dev.codex.warmaislandfix.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.codex.warmaislandfix.BoatConsumableSupport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.ItemInHandRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ItemInHandRenderer.class)
abstract class BoatConsumableRendererMixin {
    @Shadow
    @Final
    private Minecraft minecraft;

    @ModifyExpressionValue(
            method = "tick",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;isHandsBusy()Z"
            )
    )
    private boolean warmaIslandFix$showConsumableUseAnimation(boolean handsBusy) {
        LocalPlayer player = minecraft.player;
        return handsBusy && (player == null || !BoatConsumableSupport.shouldShowUseAnimation(player));
    }
}
