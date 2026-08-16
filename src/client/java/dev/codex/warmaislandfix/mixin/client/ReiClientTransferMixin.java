package dev.codex.warmaislandfix.mixin.client;

import dev.codex.warmaislandfix.ReiClientTransferFallback;
import me.shedaniel.rei.api.client.ClientHelper;
import me.shedaniel.rei.api.client.registry.transfer.TransferHandler;
import me.shedaniel.rei.api.client.registry.transfer.simple.SimpleTransferHandler;
import me.shedaniel.rei.api.common.entry.InputIngredient;
import me.shedaniel.rei.api.common.transfer.info.stack.SlotAccessor;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Pseudo
@Mixin(targets = "me.shedaniel.rei.impl.client.transfer.SimpleTransferHandlerImpl", remap = false)
abstract class ReiClientTransferMixin {
    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void warmaIslandFix$useVanillaContainerClicks(
            TransferHandler.Context context,
            SimpleTransferHandler.MissingInputRenderer missingInputRenderer,
            List<InputIngredient<ItemStack>> inputs,
            Iterable<SlotAccessor> inputSlots,
            Iterable<SlotAccessor> inventorySlots,
            CallbackInfoReturnable<TransferHandler.Result> callbackInfo
    ) {
        if (ClientHelper.getInstance().canUseMovePackets()
                || !ReiClientTransferFallback.canTransfer(inventorySlots, inputs)) {
            return;
        }

        if (!context.isActuallyCrafting()) {
            callbackInfo.setReturnValue(TransferHandler.Result.createSuccessful());
            return;
        }

        callbackInfo.setReturnValue(
                ReiClientTransferFallback.transfer(context, inputs, inputSlots, inventorySlots)
        );
    }
}
