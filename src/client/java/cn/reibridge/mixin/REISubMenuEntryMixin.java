package cn.reibridge.mixin;

import cn.reibridge.gui.PendingSubmenuRenderer;
import dev.codex.spark_fix.SparkFixConfig;
import me.shedaniel.math.Rectangle;
import me.shedaniel.rei.api.client.gui.compat.GuiGraphics;
import me.shedaniel.rei.impl.client.gui.modules.Menu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(targets = "me.shedaniel.rei.impl.client.gui.modules.entries.SubMenuEntry", remap = false)
public abstract class REISubMenuEntryMixin {
    @Shadow(remap = false)
    private Menu childMenu;

    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lme/shedaniel/rei/api/client/gui/compat/GuiGraphics;withFreshScissorStack(Ljava/lang/Runnable;)V"
            ),
            remap = false,
            require = 0
    )
    private void reiRecipeBridge$keepSubmenuOnScreen(GuiGraphics graphics, Runnable render) {
        if (!SparkFixConfig.reiRecipeBridgeEnabledAtStartup()) {
            graphics.withFreshScissorStack(render);
            return;
        }
        if (!moveChildMenuIntoBounds(graphics)) {
            graphics.withFreshScissorStack(render);
            return;
        }
        PendingSubmenuRenderer.defer(graphics, render);
    }

    private boolean moveChildMenuIntoBounds(GuiGraphics graphics) {
        if (childMenu == null) {
            return false;
        }

        Rectangle bounds = childMenu.createBounds();
        int x = Math.clamp(bounds.x, 0, Math.max(0, graphics.guiWidth() - bounds.width));
        int y = Math.clamp(bounds.y, 0, Math.max(0, graphics.guiHeight() - bounds.height));
        if (x == bounds.x && y == bounds.y) {
            return false;
        }

        childMenu.menuStartPoint.setLocation(x, y);
        childMenu.bounds.setAs(childMenu.createBounds().getFloatingBounds());
        return true;
    }
}
