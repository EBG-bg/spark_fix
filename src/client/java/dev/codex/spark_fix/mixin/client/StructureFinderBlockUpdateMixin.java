package dev.codex.spark_fix.mixin.client;

import dev.codex.spark_fix.StructureFinder;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Both server block packets and local prediction use this change callback. */
@Mixin(ClientLevel.class)
abstract class StructureFinderBlockUpdateMixin {
    @Inject(method = "setBlocksDirty", at = @At("TAIL"))
    private void sparkFix$invalidateStructureSnapshot(BlockPos pos, BlockState oldState,
                                                     BlockState newState, CallbackInfo ci) {
        StructureFinder.onBlockChanged((ClientLevel) (Object) this, pos,
                oldState.getBlock(), newState.getBlock());
    }
}
