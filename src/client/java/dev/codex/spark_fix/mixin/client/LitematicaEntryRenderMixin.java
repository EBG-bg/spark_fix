package dev.codex.spark_fix.mixin.client;

import dev.codex.spark_fix.ProjectionButton;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** MaLiLib handles native screen input but omits native widgets from its render pass. */
@Pseudo
@Mixin(targets = "fi.dy.masa.malilib.gui.GuiBase", remap = false)
abstract class LitematicaEntryRenderMixin {
    @Inject(method = "extractRenderState", at = @At("TAIL"), remap = false)
    private void sparkFix$drawProjectionEntry(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                              float partialTick, CallbackInfo callbackInfo) {
        for (var child : ((Screen) (Object) this).children()) {
            if (child instanceof ProjectionButton button) {
                graphics.nextStratum();
                button.extractRenderState(graphics, mouseX, mouseY, partialTick);
            }
        }
    }
}
