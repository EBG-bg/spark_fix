package dev.codex.spark_fix.mixin.client;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.codex.spark_fix.PrinterScanBudget;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

import java.util.function.Predicate;
import java.util.function.Supplier;

@Pseudo
@Mixin(targets = "me.aleksilassila.litematica.printer.handler.Module", remap = false)
abstract class PrinterScanPhaseMixin {
    @Shadow protected abstract int getIterationTimeLimit();

    @WrapMethod(
            method = "iteratePhase(ILjava/util/function/Supplier;Ljava/util/function/Predicate;Ljava/lang/Runnable;)Z",
            require = 0,
            remap = false
    )
    private boolean sparkFix$boundInnerScan(int maxExecutions, Supplier<BlockPos> positions,
            Predicate<BlockPos> action, Runnable onComplete, Operation<Boolean> original) {
        return PrinterScanBudget.run(getIterationTimeLimit(),
                () -> original.call(maxExecutions, positions, action, onComplete));
    }
}
