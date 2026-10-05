package dev.codex.spark_fix.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.codex.spark_fix.PrinterScanBudget;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Iterator;

/** Lets the outer scan phase pause while next() is filtering unreachable positions. */
@Pseudo
@Mixin(targets = "me.aleksilassila.litematica.printer.handler.IteratorManager", remap = false)
abstract class PrinterIteratorBudgetMixin {
    @WrapOperation(
            method = "next()Lnet/minecraft/core/BlockPos;",
            at = @At(value = "INVOKE", target = "Ljava/util/Iterator;next()Ljava/lang/Object;"),
            require = 0,
            remap = false
    )
    private Object sparkFix$pauseFilteredScan(Iterator<?> iterator, Operation<Object> original) {
        PrinterScanBudget.checkpoint();
        return original.call(iterator);
    }
}
