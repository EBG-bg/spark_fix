package dev.codex.spark_fix.mixin.client;

import dev.codex.spark_fix.AxiomFontFallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures only the synchronous font-atlas overflow assertion handled by the fallback. */
@Pseudo
@Mixin(targets = "imgui.moulberry92.ImGui$1", remap = false)
abstract class AxiomImAssertCallbackMixin {
    @Inject(
        method = "imAssertCallback(Ljava/lang/String;ILjava/lang/String;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private void sparkFix$captureFontAtlasOverflow(
        String expression,
        int line,
        String file,
        CallbackInfo callbackInfo
    ) {
        if (AxiomFontFallback.recordImGuiAssertion(expression)) {
            callbackInfo.cancel();
        }
    }
}
