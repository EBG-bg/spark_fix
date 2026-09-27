package dev.codex.spark_fix;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Compact list summary; editing takes place in a separate draft screen. */
final class LitematicaStringListButton extends Button {
    LitematicaStringListButton(Font font, Object option, int width, Runnable openEditor) {
        super(0, 0, width, 20, summary(font, option, width), ignored -> openEditor.run(), DEFAULT_NARRATION);
        setTooltip(Tooltip.create(Component.translatable("config.spark_fix.litematica_string_list_open")));
    }

    private static Component summary(Font font, Object option, int width) {
        List<String> entries = LitematicaConfigDiscovery.stringListValue(option).stream()
                .filter(value -> !value.isBlank()).toList();
        if (entries.isEmpty()) return Component.translatable("config.spark_fix.litematica_string_list_empty");
        Component prefix = Component.translatable("config.spark_fix.litematica_string_list_summary", entries.size(), "");
        int previewWidth = width - font.width(prefix) - 16;
        if (previewWidth < 12) return Component.translatable("config.spark_fix.litematica_string_list_count", entries.size());
        return Component.translatable("config.spark_fix.litematica_string_list_summary",
                entries.size(), font.plainSubstrByWidth(entries.getFirst(), previewWidth));
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        IntegrationSettingsStyle.roundedRect(graphics, getX(), getY(), getWidth(), getHeight(), 6,
                active && isHoveredOrFocused() ? 0xCC87B1F9 : 0x8887B1F9);
        IntegrationSettingsStyle.roundedRect(graphics, getX() + 1, getY() + 1,
                getWidth() - 2, getHeight() - 2, 5,
                active && isHoveredOrFocused() ? 0xE12B5371 : 0xD51B3048);
        extractDefaultLabel(graphics.textRenderer());
    }
}
