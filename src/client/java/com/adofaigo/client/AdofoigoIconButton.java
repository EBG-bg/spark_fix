package com.adofaigo.client;

import com.adofaigo.AdofoigoMod;
import dev.codex.spark_fix.AdofoigoLaunchDelayScreen;
import dev.codex.spark_fix.SparkFixConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;

/** A vanilla-styled 20x20 button with the transparent ADOFAI icon over it. */
public final class AdofoigoIconButton extends Button {
    private static final Component LABEL = Component.translatable("config.spark_fix.adofai_tooltip");
    private static final net.minecraft.resources.Identifier ICON = AdofoigoMod.id("textures/gui/adofai_icon.png");

    public AdofoigoIconButton(int x, int y, OnPress onPress) {
        super(x, y, 20, 20, LABEL, onPress, DEFAULT_NARRATION);
        setTooltip(Tooltip.create(LABEL.copy().append("\n").append(Component.translatable(
                "config.spark_fix.adofaigo_delay_right_click", SparkFixConfig.adofaigoLaunchDelaySeconds()))));
    }

    @Override protected boolean isValidClickButton(MouseButtonInfo event) {
        return event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT || event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT;
    }

    @Override public void onPress(InputWithModifiers event) {
        if (event instanceof MouseButtonEvent && event.input() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            Minecraft client = Minecraft.getInstance();
            client.setScreenAndShow(new AdofoigoLaunchDelayScreen(client.gui.screen()));
        } else super.onPress(event);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int size = Math.min(getWidth() - 4, getHeight() - 4);
        graphics.blit(RenderPipelines.GUI_TEXTURED, ICON, getX() + 2, getY() + 2,
                0.0F, 0.0F, size, size, size, size);
    }
}
