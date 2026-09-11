package cn.reibridge.mixin;

import dev.codex.spark_fix.SparkFixConfig;
import me.shedaniel.rei.api.client.gui.compat.GuiGraphics;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = GuiGraphics.class, remap = false)
public abstract class REIFreshScissorStackMixin {
    @Redirect(
            method = "withFreshScissorStack",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/navigation/ScreenRectangle;empty()Lnet/minecraft/client/gui/navigation/ScreenRectangle;"
            ),
            require = 0
    )
    private ScreenRectangle reiRecipeBridge$useFullGuiScissorRoot() {
        if (!SparkFixConfig.reiRecipeBridgeEnabledAtStartup()) {
            return ScreenRectangle.empty();
        }
        GuiGraphics graphics = (GuiGraphics) (Object) this;
        return new ScreenRectangle(0, 0, graphics.guiWidth(), graphics.guiHeight());
    }
}
