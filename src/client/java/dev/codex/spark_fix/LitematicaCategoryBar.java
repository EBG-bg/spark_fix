package dev.codex.spark_fix;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** A single-row native tab strip; narrow windows can scroll it without changing a selection. */
final class LitematicaCategoryBar extends AbstractWidget {
    record Choice(String id, Component label) { }
    private final List<Button> buttons = new ArrayList<>();
    private final Supplier<ScreenRectangle> viewport;
    private int offset;
    private int totalWidth;
    private int keyboardIndex;

    LitematicaCategoryBar(int x, int y, int width, List<Choice> choices, String selected,
                          net.minecraft.client.gui.Font font, Supplier<ScreenRectangle> viewport, Consumer<String> select) {
        super(x, y, width, 20, Component.translatable("config.spark_fix.litematica_categories"));
        this.viewport = viewport;
        for (Choice choice : choices) {
            Button button = Button.builder(choice.label(), ignored -> select.accept(choice.id()))
                    .bounds(0, y, Math.max(30, Math.min(100, font.width(choice.label()) + 12)), 20)
                    .tooltip(Tooltip.create(choice.label())).build();
            button.active = !choice.id().equals(selected);
            buttons.add(button);
            totalWidth += button.getWidth() + 3;
        }
        totalWidth = Math.max(0, totalWidth - 3);
        positionButtons();
    }

    private void positionButtons() {
        int x = getX() - offset;
        for (Button button : buttons) {
            button.setX(x);
            button.setY(getY());
            x += button.getWidth() + 3;
        }
    }

    @Override public void setX(int x) { super.setX(x); if (buttons != null) positionButtons(); }
    @Override public void setY(int y) { super.setY(y); if (buttons != null) positionButtons(); }

    @Override public boolean isMouseOver(double x, double y) {
        ScreenRectangle area = viewport.get();
        return y >= area.top() && y < area.bottom() && super.isMouseOver(x, y);
    }

    @Override protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int x, int y, float tick) {
        graphics.enableScissor(getX(), getY(), getRight(), getBottom());
        for (Button button : buttons) {
            if (button.getRight() <= getX() || button.getX() >= getRight()) continue;
            button.extractRenderState(graphics, isMouseOver(x, y) ? x : -10000, isMouseOver(x, y) ? y : -10000, tick);
        }
        if (offset > 0) graphics.fill(getX(), getY() + 3, getX() + 2, getBottom() - 3, 0xCC87B1F9);
        if (offset + getWidth() < totalWidth) graphics.fill(getRight() - 2, getY() + 3, getRight(), getBottom() - 3, 0xCC87B1F9);
        graphics.disableScissor();
    }

    @Override public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (!isMouseOver(click.x(), click.y())) return false;
        for (Button button : buttons) if (button.mouseClicked(click, doubled)) return true;
        return true;
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (!isMouseOver(x, y)) return false;
        offset = Math.clamp(offset - (int) Math.round((horizontal != 0 ? horizontal : vertical) * 30),
                0, Math.max(0, totalWidth - getWidth()));
        positionButtons();
        return true;
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (!isFocused() || buttons.isEmpty()) return false;
        if (event.key() == GLFW.GLFW_KEY_LEFT || event.key() == GLFW.GLFW_KEY_RIGHT) {
            keyboardIndex = Math.floorMod(keyboardIndex + (event.key() == GLFW.GLFW_KEY_RIGHT ? 1 : -1), buttons.size());
            Button button = buttons.get(keyboardIndex);
            if (button.getX() < getX()) offset += button.getX() - getX();
            if (button.getRight() > getRight()) offset += button.getRight() - getRight();
            positionButtons();
            return true;
        }
        if (event.isSelection()) {
            Button button = buttons.get(keyboardIndex);
            if (button.active) button.onPress(event);
            return true;
        }
        return false;
    }

    @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
}
