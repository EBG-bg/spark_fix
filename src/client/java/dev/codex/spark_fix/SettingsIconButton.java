package dev.codex.spark_fix;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Small action icons drawn without font shadows or resource-pack glyph substitutions. */
class SettingsIconButton extends AbstractWidget {
    enum Icon { ADD, REMOVE, DOWN, INFO, MODE, RESET }

    private final Icon icon;
    private final Runnable action;
    private final boolean framed;

    SettingsIconButton(int x, int y, int size, Icon icon, Component label, Runnable action) {
        this(x, y, size, icon, label, action, false);
    }

    SettingsIconButton(int x, int y, int size, Icon icon, Component label, Runnable action, boolean framed) {
        super(x, y, size, size, label);
        this.icon = icon;
        this.action = action;
        this.framed = framed;
        setTooltip(Tooltip.create(label));
    }

    @Override public void onClick(MouseButtonEvent event, boolean doubled) {
        if (active && visible && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) action.run();
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (active && visible && isFocused() && (event.key() == GLFW.GLFW_KEY_ENTER
                || event.key() == GLFW.GLFW_KEY_KP_ENTER || event.key() == GLFW.GLFW_KEY_SPACE)) {
            action.run();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (framed) {
            IntegrationSettingsStyle.roundedRect(graphics, getX(), getY(), getWidth(), getHeight(), 6, 0xA0A7C8FA);
            IntegrationSettingsStyle.roundedRect(graphics, getX() + 1, getY() + 1,
                    getWidth() - 2, getHeight() - 2, 5,
                    isHoveredOrFocused() && active ? 0xD12B5371 : 0xB51B3B56);
        } else if (isHoveredOrFocused() && active) {
            IntegrationSettingsStyle.roundedRect(graphics, getX(), getY(), getWidth(), getHeight(), 6, 0x24FFFFFF);
        }
        int color = !active ? 0x607E8793 : icon == Icon.ADD ? 0xFF56D886
                : icon == Icon.INFO || icon == Icon.MODE || icon == Icon.RESET ? 0xFFD3E4FC : 0xFF27364D;
        int x = getX() + getWidth() / 2;
        int y = getY() + getHeight() / 2;
        switch (icon) {
            case RESET -> {
                graphics.fill(x - 3, y - 5, x + 3, y - 3, color);
                graphics.fill(x + 3, y - 3, x + 5, y + 3, color);
                graphics.fill(x - 3, y + 3, x + 3, y + 5, color);
                graphics.fill(x - 5, y, x - 3, y + 3, color);
                graphics.fill(x - 5, y - 5, x - 3, y - 1, color);
                graphics.fill(x - 3, y - 3, x, y - 1, color);
            }
            case MODE -> {
                graphics.fill(x - 5, y - 4, x + 5, y - 3, color);
                graphics.fill(x - 5, y + 3, x + 5, y + 4, color);
                graphics.fill(x - 5, y - 3, x - 4, y + 3, color);
                graphics.fill(x + 4, y - 3, x + 5, y + 3, color);
                graphics.fill(x - 2, y - 2, x, y + 2, color);
            }
            case INFO -> {
                graphics.fill(x - 1, y - 5, x + 1, y - 3, color);
                graphics.fill(x - 1, y - 1, x + 1, y + 5, color);
                graphics.fill(x - 2, y - 1, x - 1, y, color);
                graphics.fill(x - 2, y + 4, x + 3, y + 5, color);
            }
            case ADD -> {
                graphics.fill(x - 4, y - 1, x + 4, y + 1, color);
                graphics.fill(x - 1, y - 4, x + 1, y - 1, color);
                graphics.fill(x - 1, y + 1, x + 1, y + 4, color);
            }
            case REMOVE -> {
                for (int i = -3; i <= 3; i++) {
                    graphics.fill(x + i, y + i, x + i + 1, y + i + 1, color);
                    if (i != 0) graphics.fill(x + i, y - i, x + i + 1, y - i + 1, color);
                }
            }
            case DOWN -> {
                graphics.fill(x, y - 4, x + 1, y + 3, color);
                for (int i = 1; i <= 3; i++) {
                    graphics.fill(x - i, y + 3 - i, x - i + 1, y + 4 - i, color);
                    graphics.fill(x + i, y + 3 - i, x + i + 1, y + 4 - i, color);
                }
            }
        }
    }
}
