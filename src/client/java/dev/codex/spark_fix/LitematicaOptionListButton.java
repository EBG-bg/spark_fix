package dev.codex.spark_fix;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Uses the option's own display name, cycle order and change callbacks. */
final class LitematicaOptionListButton extends Button {
    private final Object option;
    private Component hoverText;

    LitematicaOptionListButton(Object option, int width) {
        super(0, 0, width, 20, Component.empty(), ignored -> { }, DEFAULT_NARRATION);
        this.option = option;
        refresh();
    }

    @Override
    public void onPress(InputWithModifiers event) {
        cycle(event instanceof KeyEvent ? !event.hasShiftDown() : event.input() == GLFW.GLFW_MOUSE_BUTTON_LEFT);
    }

    @Override
    protected boolean isValidClickButton(MouseButtonInfo event) {
        return event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT || event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (active && visible && isFocused()
                && (event.key() == GLFW.GLFW_KEY_LEFT || event.key() == GLFW.GLFW_KEY_RIGHT)) {
            cycle(event.key() == GLFW.GLFW_KEY_RIGHT);
            return true;
        }
        return super.keyPressed(event);
    }

    private void cycle(boolean forward) {
        LitematicaConfigDiscovery.cycleOptionList(option, forward);
        refresh();
    }

    private void refresh() {
        setMessage(Component.literal(LitematicaConfigDiscovery.optionListDisplayName(option)));
        var tooltip = getMessage().copy();
        Component hover = LitematicaConfigDiscovery.optionListHover(option);
        if (!hover.getString().isBlank()) tooltip.append("\n").append(hover);
        tooltip.append("\n").append(Component.translatable("config.spark_fix.litematica_option_cycle_hint"));
        hoverText = tooltip;
    }

    Component hoverText() { return hoverText; }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // Draw our own fill: the inherited contents hook otherwise leaves only a label,
        // and transparent resource-pack button sprites cannot distinguish it from the card.
        IntegrationSettingsStyle.roundedRect(graphics, getX(), getY(), getWidth(), getHeight(), 6,
                active && isHoveredOrFocused() ? 0xCC87B1F9 : 0x8887B1F9);
        IntegrationSettingsStyle.roundedRect(graphics, getX() + 1, getY() + 1,
                getWidth() - 2, getHeight() - 2, 5,
                active && isHoveredOrFocused() ? 0xE12B5371 : 0xD51B3048);
        extractDefaultLabel(graphics.textRenderer());
    }
}
