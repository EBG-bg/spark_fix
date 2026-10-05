package dev.codex.spark_fix.mixin.client;

import java.util.Collections;
import java.util.Iterator;
import dev.codex.spark_fix.PrinterScanBounds;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "me.aleksilassila.litematica.printer.printer.PrinterBox", remap = false)
abstract class PrinterInvalidBoundsMixin implements PrinterScanBounds {
    @Unique private boolean sparkFix$emptyScan;
    @Shadow @Final public int minX;
    @Shadow @Final public int minY;
    @Shadow @Final public int minZ;
    @Shadow @Final public int maxX;
    @Shadow @Final public int maxY;
    @Shadow @Final public int maxZ;

    @Override
    public void sparkFix$markEmptyScan() {
        sparkFix$emptyScan = true;
    }

    @Inject(
        method = "iterator()Ljava/util/Iterator;",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private void sparkFix$skipInvalidBounds(CallbackInfoReturnable<Iterator<?>> cir) {
        // Out-of-world height clipping can invert Y; the printer's iterator then never reaches its end.
        if (sparkFix$emptyScan || minX > maxX || minY > maxY || minZ > maxZ) {
            cir.setReturnValue(Collections.emptyIterator());
        }
    }
}
