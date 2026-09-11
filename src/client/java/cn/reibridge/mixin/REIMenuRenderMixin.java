package cn.reibridge.mixin;

import cn.reibridge.gui.PendingSubmenuRenderer;
import dev.codex.spark_fix.SparkFixConfig;
import me.shedaniel.rei.api.client.gui.compat.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "me.shedaniel.rei.impl.client.gui.modules.Menu", remap = false)
public abstract class REIMenuRenderMixin {
    @Inject(method = "render", at = @At("TAIL"), remap = false)
    private void reiRecipeBridge$renderDeferredSubmenus(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float delta,
            CallbackInfo callbackInfo
    ) {
        if (!SparkFixConfig.reiRecipeBridgeEnabledAtStartup()) {
            return;
        }
        PendingSubmenuRenderer.renderPending();
    }
}
