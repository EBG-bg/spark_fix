package com.adofaigo.client;

import com.adofaigo.AdofoigoMod;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

/** A vanilla-styled 20x20 button with the transparent ADOFAI icon over it. */
public final class AdofoigoIconButton extends Button {
    private static final Component LABEL = Component.literal("adofai");
    private static final Tooltip TOOLTIP = Tooltip.create(Component.translatable("config.spark_fix.adofai_tooltip"));
    private static final net.minecraft.resources.Identifier ICON = AdofoigoMod.id("textures/gui/adofai_icon.png");

    public AdofoigoIconButton(int x, int y, OnPress onPress) {
        super(x, y, 20, 20, LABEL, onPress, DEFAULT_NARRATION);
        setTooltip(TOOLTIP);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int size = Math.min(getWidth() - 4, getHeight() - 4);
        graphics.blit(RenderPipelines.GUI_TEXTURED, ICON, getX() + 2, getY() + 2,
                0.0F, 0.0F, size, size, size, size);
    }
}
