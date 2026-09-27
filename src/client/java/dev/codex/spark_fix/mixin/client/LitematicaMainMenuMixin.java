package dev.codex.spark_fix.mixin.client;

import dev.codex.spark_fix.IntegrationSettingsRouter;
import dev.codex.spark_fix.ProjectionButton;
import dev.codex.spark_fix.SparkFixConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;

/** Adds the spark_fix entry beside Litematica's own configuration button when enabled. */
@Pseudo
@Mixin(targets = "fi.dy.masa.litematica.gui.GuiMainMenu", remap = false)
abstract class LitematicaMainMenuMixin extends Screen {
    @Unique private ProjectionButton sparkFix$settingsButton;

    protected LitematicaMainMenuMixin(Component title) {
        super(title);
    }

    @Inject(method = "initGui", at = @At("TAIL"), remap = false)
    private void sparkFix$addSettingsButton(CallbackInfo callbackInfo) {
        if (sparkFix$settingsButton != null) {
            removeWidget(sparkFix$settingsButton);
            sparkFix$settingsButton = null;
        }
        if (!SparkFixConfig.litematicaEnabledAtStartup()) return;
        try {
            Class<?> selfClass = getClass();
            Method widthMethod = selfClass.getDeclaredMethod("getButtonWidth");
            widthMethod.setAccessible(true);
            Method screenWidthMethod = selfClass.getMethod("getScreenWidth");
            int buttonWidth = ((Number) widthMethod.invoke(this)).intValue();
            int screenWidth = ((Number) screenWidthMethod.invoke(this)).intValue();
            int configX = 12 + buttonWidth + 20;
            int buttonSize = Math.max(76, Math.min(104, buttonWidth));
            int x = configX + buttonWidth + 20;
            int y = 26;
            if (x + buttonSize > screenWidth - 12) {
                x = configX;
                y = 30 + buttonWidth + 14;
            }
            if (x + buttonSize > screenWidth - 8 || y + ProjectionButton.heightForWidth(buttonSize) > height - 20) {
                x = Math.max(12, screenWidth - buttonSize - 12);
                y = Math.max(56, height - ProjectionButton.heightForWidth(buttonSize) - 32);
            }
            sparkFix$settingsButton = addWidget(new ProjectionButton(x, y, buttonSize,
                    () -> Minecraft.getInstance().setScreenAndShow(
                            IntegrationSettingsRouter.createLitematica(this))));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            // Optional integrations must never prevent the Litematica menu from opening.
        }
    }
}
