package com.adofaigo.client.mixin;

import com.adofaigo.client.AdofoigoIconButton;
import com.adofaigo.client.SteamLauncher;
import dev.codex.spark_fix.SparkFixConfig;
import java.util.List;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin extends Screen {
    protected PauseScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void adofaigo$addButton(CallbackInfo callbackInfo) {
        if (!SparkFixConfig.adofaigoEnabledAtStartup()) {
            return;
        }
        PauseScreen pauseScreen = (PauseScreen) (Object) this;
        if (!pauseScreen.showsPauseMenu()) {
            return;
        }

        int rowY = -1;
        int firstIconX = Integer.MAX_VALUE;
        List<? extends GuiEventListener> children = pauseScreen.children();
        for (GuiEventListener child : children) {
            if (child instanceof AbstractWidget widget && widget.getWidth() == 20 && widget.getHeight() == 20) {
                if (rowY < 0) {
                    rowY = widget.getY();
                }
                if (widget.getY() == rowY) {
                    firstIconX = Math.min(firstIconX, widget.getX());
                }
            }
        }

        if (rowY < 0) {
            rowY = pauseScreen.height / 2 - 38;
            firstIconX = pauseScreen.width / 2 - 100;
        }

        int x = Math.max(4, firstIconX - 24);
        this.addRenderableWidget(new AdofoigoIconButton(x, rowY, button -> SteamLauncher.launchOrFocus()));
    }
}
