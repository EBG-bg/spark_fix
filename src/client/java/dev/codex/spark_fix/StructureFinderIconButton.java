package dev.codex.spark_fix;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Native button frame and a resource-pack-aware three-dimensional block icon. */
public final class StructureFinderIconButton extends Button {
    private static final Component LABEL = Component.translatable("gui.spark_fix.structure_finder.title");
    private final ItemStack icon = new ItemStack(Items.CRACKED_STONE_BRICKS);

    public StructureFinderIconButton(int x, int y, OnPress onPress) {
        super(x, y, 20, 20, LABEL, onPress, DEFAULT_NARRATION);
        setTooltip(Tooltip.create(LABEL));
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        extractDefaultSprite(graphics);
        graphics.item(icon, getX() + 2, getY() + 2);
    }
}
