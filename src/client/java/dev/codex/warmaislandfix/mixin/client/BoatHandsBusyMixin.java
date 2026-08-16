package dev.codex.warmaislandfix.mixin.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents the client-only rowing flag from surviving a dismount.
 *
 * <p>In 26.2 {@code LocalPlayer.rideTick()} sets {@code handsBusy} while a
 * boat input key is held, but there is no corresponding reset on the first
 * normal tick after dismounting.  Treating that stale value as busy blocks
 * both attacking and starting any item use on foot.</p>
 */
@Mixin(LocalPlayer.class)
abstract class BoatHandsBusyMixin {
    @Inject(method = "isHandsBusy", at = @At("RETURN"), cancellable = true)
    private void warmaIslandFix$ignoreStaleRowingState(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()
                && !(((LocalPlayer) (Object) this).getControlledVehicle() instanceof AbstractBoat)) {
            cir.setReturnValue(false);
        }
    }
}
