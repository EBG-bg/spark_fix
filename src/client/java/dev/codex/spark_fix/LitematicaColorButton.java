package dev.codex.spark_fix;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

final class LitematicaColorButton extends Button {
    private final Object option;
    private final Font font;

    LitematicaColorButton(Font font, Object option, int width, Runnable open) {
        super(0, 0, width, 20, Component.translatable("config.spark_fix.litematica_color_open"), ignored -> open.run(), DEFAULT_NARRATION);
        this.font = font;
        this.option = option;
    }

    @Override protected void extractContents(GuiGraphicsExtractor graphics, int x, int y, float tick) {
        IntegrationSettingsStyle.roundedRect(graphics, getX(), getY(), getWidth(), getHeight(), 6,
                isHoveredOrFocused() ? 0xDC355775 : 0xC01B3048);
        int color = (int) LitematicaConfigDiscovery.number(option, "getIntegerValue");
        swatch(graphics, getX() + 4, getY() + 4, 12, 12, color);
        String value = new LitematicaColorState(color).hex();
        graphics.text(font, font.plainSubstrByWidth(value, Math.max(1, getWidth() - 23)), getX() + 20, getY() + 6, 0xFFFFFFFF, false);
    }

    static void swatch(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int color) {
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xCCBBD6FF);
        for (int row = 0; row < height; row += 4) for (int col = 0; col < width; col += 4) {
            graphics.fill(x + col, y + row, x + Math.min(width, col + 4), y + Math.min(height, row + 4),
                    ((row / 4 + col / 4) & 1) == 0 ? 0xFF9AABBD : 0xFF55677B);
        }
        graphics.fill(x, y, x + width, y + height, color);
    }
}
