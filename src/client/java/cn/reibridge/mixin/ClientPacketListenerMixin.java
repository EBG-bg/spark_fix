package cn.reibridge.mixin;

import cn.reibridge.recipe.RecipeCaptureStore;
import dev.codex.spark_fix.SparkFixConfig;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookRemovePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @ModifyVariable(
            method = "handleRecipeBookAdd",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
                    shift = At.Shift.AFTER
            ),
            argsOnly = true
    )
    private ClientboundRecipeBookAddPacket reiRecipeBridge$mergeCapturedRecipes(
            ClientboundRecipeBookAddPacket packet
    ) {
        if (!SparkFixConfig.reiRecipeBridgeEnabledAtStartup()) {
            return packet;
        }
        return RecipeCaptureStore.merge((ClientPacketListener) (Object) this, packet);
    }

    @Inject(method = "handleRecipeBookRemove", at = @At("TAIL"))
    private void reiRecipeBridge$removeCapturedRecipes(
            ClientboundRecipeBookRemovePacket packet,
            CallbackInfo callbackInfo
    ) {
        if (!SparkFixConfig.reiRecipeBridgeEnabledAtStartup()) {
            return;
        }
        RecipeCaptureStore.remove((ClientPacketListener) (Object) this, packet);
    }
}
