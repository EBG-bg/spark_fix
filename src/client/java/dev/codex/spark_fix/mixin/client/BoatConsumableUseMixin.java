package dev.codex.spark_fix.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.codex.spark_fix.BoatConsumableSupport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
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
    private boolean sparkFix$allowConsumablesWhileRowing(boolean handsBusy) {
        LocalPlayer player = ((Minecraft) (Object) this).player;
        return handsBusy && (player == null || !BoatConsumableSupport.canStartUsingConsumable(player));
    }

    @WrapOperation(
            method = "startUseItem",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;useItemOn(Lnet/minecraft/client/player/LocalPlayer;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;"
            )
    )
    private InteractionResult sparkFix$fallThroughFailedMainHandBlockUse(
            MultiPlayerGameMode gameMode,
            LocalPlayer player,
            InteractionHand hand,
            BlockHitResult hitResult,
            Operation<InteractionResult> original
    ) {
        InteractionResult result = original.call(gameMode, player, hand, hitResult);
        return BoatConsumableSupport.shouldPassFailedMainHand(player, hand, result)
                ? InteractionResult.PASS
                : result;
    }

    @WrapOperation(
            method = "startUseItem",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;useItem(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;"
            )
    )
    private InteractionResult sparkFix$fallThroughFailedMainHandUse(
            MultiPlayerGameMode gameMode,
            Player player,
            InteractionHand hand,
            Operation<InteractionResult> original
    ) {
        InteractionResult result = original.call(gameMode, player, hand);
        if (player instanceof LocalPlayer localPlayer
                && BoatConsumableSupport.shouldPassFailedMainHand(localPlayer, hand, result)) {
            return InteractionResult.PASS;
        }
        return result;
    }
}
