package dev.codex.spark_fix.mixin.client;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps the printer from treating a status packet's unused zero coordinates as a position. */
@Pseudo
@Mixin(targets = "me.aleksilassila.litematica.printer.utils.PacketUtils", remap = false)
abstract class PrinterMovementPacketFixMixin {
    @Inject(
        method = "getFixedPacket(Lnet/minecraft/network/protocol/Packet;)Lnet/minecraft/network/protocol/Packet;",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private static void sparkFix$preserveStatusPacket(Packet<?> packet, CallbackInfoReturnable<Packet<?>> cir) {
        // StatusOnly carries ground/collision flags, not an absolute position or rotation.
        if (packet instanceof ServerboundMovePlayerPacket.StatusOnly) {
            cir.setReturnValue(packet);
        }
    }
}
