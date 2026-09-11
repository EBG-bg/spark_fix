package cn.reibridge.mixin;

import dev.codex.spark_fix.SparkFixConfig;
import me.shedaniel.rei.api.client.REIRuntime;
import me.shedaniel.rei.api.client.gui.compat.GuiGraphics;
import me.shedaniel.rei.api.client.gui.widgets.Tooltip;
import me.shedaniel.rei.impl.client.gui.ScreenOverlayImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * REI draws menu-entry tooltips while the menu's scissor is still enabled.
 * Defer them to REI's normal tooltip pass, after all menus have rendered.
 */
@Mixin(targets = "me.shedaniel.rei.impl.client.gui.modules.entries.ToggleMenuEntry", remap = false)
public abstract class REIMenuTooltipMixin {
    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lme/shedaniel/rei/impl/client/gui/ScreenOverlayImpl;renderTooltip(Lme/shedaniel/rei/api/client/gui/compat/GuiGraphics;Lme/shedaniel/rei/api/client/gui/widgets/Tooltip;)V"
            ),
            remap = false,
            require = 0
    )
    private void reiRecipeBridge$deferMenuTooltip(
            ScreenOverlayImpl overlay,
            GuiGraphics graphics,
            Tooltip tooltip
    ) {
        if (!SparkFixConfig.reiRecipeBridgeEnabledAtStartup()) {
            overlay.renderTooltip(graphics, tooltip);
            return;
        }
        REIRuntime.getInstance().queueTooltip(tooltip);
    }
}
