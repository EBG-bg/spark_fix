package dev.codex.warmaislandfix.mixin.client;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
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
    @Shadow
    private boolean handsBusy;

    @Unique
    private boolean warmaislandfix$boatHandsBusy;

    @Inject(method = "rideTick", at = @At("RETURN"))
    private void warmaislandfix$recordBoatHandsBusy(CallbackInfo callbackInfo) {
        if (((LocalPlayer) (Object) this).getControlledVehicle() instanceof AbstractBoat
                && this.handsBusy) {
            this.warmaislandfix$boatHandsBusy = true;
        }
    }

    @Inject(method = "removeVehicle", at = @At("RETURN"))
    private void warmaislandfix$clearBoatHandsBusy(CallbackInfo callbackInfo) {
        if (this.warmaislandfix$boatHandsBusy && this.handsBusy) {
            this.handsBusy = false;
        }
        this.warmaislandfix$boatHandsBusy = false;
    }

    @Inject(method = "isHandsBusy", at = @At("RETURN"), cancellable = true)
    private void warmaIslandFix$ignoreStaleRowingState(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()
                && this.warmaislandfix$boatHandsBusy
                && !(((LocalPlayer) (Object) this).getControlledVehicle() instanceof AbstractBoat)) {
            this.handsBusy = false;
            this.warmaislandfix$boatHandsBusy = false;
            cir.setReturnValue(false);
        }
    }
}
