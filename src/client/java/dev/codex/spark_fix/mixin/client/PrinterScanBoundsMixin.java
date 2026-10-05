package dev.codex.spark_fix.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.codex.spark_fix.PrinterScanBounds;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;

/** Preserves an empty intersection computed by the printer's range/selection clipping. */
@Pseudo
@Mixin(targets = "me.aleksilassila.litematica.printer.handler.IteratorManager", remap = false)
abstract class PrinterScanBoundsMixin {
    @Coerce
    @WrapOperation(
            method = "tryBuildBox(Lnet/minecraft/client/player/LocalPlayer;Ljava/lang/Object;)Z",
            at = @At(
                    value = "NEW",
                    target = "(IIIIII)Lme/aleksilassila/litematica/printer/printer/PrinterBox;"
            ),
            require = 0,
            remap = false
    )
    private Object sparkFix$preserveEmptyIntersection(
            int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
            Operation<Object> original
    ) {
        Object box = original.call(minX, minY, minZ, maxX, maxY, maxZ);
        // This caller computed an intersection, not two interchangeable corners. Swapping
        // inverted edges would create a huge region between unrelated selections and the player.
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            ((PrinterScanBounds) box).sparkFix$markEmptyScan();
        }
        return box;
    }
}
