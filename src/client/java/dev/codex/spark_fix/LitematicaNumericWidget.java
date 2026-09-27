package dev.codex.spark_fix;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.PreeditEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Numeric editing with MaLiLib's own bounds and slider/text preference. */
final class LitematicaNumericWidget extends AbstractWidget {
    private final Object option;
    private final boolean integer;
    private final double minimum;
    private final double maximum;
    private final EditBox input;
    private final ValueSlider slider;
    private final SettingsIconButton toggle;
    private AbstractWidget focused;

    LitematicaNumericWidget(Font font, Object option, String name, int width) {
        super(0, 0, width, heightFor(width), Component.literal(name));
        this.option = option;
        integer = LitematicaConfigDiscovery.integerOption(option);
        minimum = LitematicaConfigDiscovery.number(option, integer ? "getMinIntegerValue" : "getMinDoubleValue");
        maximum = LitematicaConfigDiscovery.number(option, integer ? "getMaxIntegerValue" : "getMaxDoubleValue");
        input = new EditBox(font, 0, 0, width, 20, getMessage());
        input.setMaxLength(64);
        input.setValue(LitematicaConfigDiscovery.value(option));
        input.setResponder(this::edit);
        slider = new ValueSlider();
        toggle = new SettingsIconButton(0, 0, 18, SettingsIconButton.Icon.MODE,
                Component.translatable("config.spark_fix.litematica_numeric_mode"), () -> {
                    LitematicaConfigDiscovery.toggleSlider(option);
                    input.setValue(LitematicaConfigDiscovery.value(option));
                    slider.sync();
                    focus(body());
                }, true) {
            // The containing screen draws this at screen coordinates after its scissor is removed.
            @Override protected void extractTooltipForNextRenderPass(GuiGraphicsExtractor graphics, int x, int y) { }
        };
        setWidth(width);
    }

    static int heightFor(int width) { return width < 58 ? 42 : 20; }

    @Override public void setWidth(int width) {
        super.setWidth(width);
        setHeight(heightFor(width));
        if (input == null) return;
        int bodyWidth = width < 58 ? width : width - 22;
        input.setWidth(Math.max(1, bodyWidth));
        slider.setWidth(Math.max(1, bodyWidth));
        toggle.setX(width < 58 ? 0 : width - 18);
        toggle.setY(width < 58 ? 24 : 1);
    }

    private AbstractWidget body() { return LitematicaConfigDiscovery.usesSlider(option) ? slider : input; }

    Component hoverText(double x, double y) {
        return toggle.isMouseOver(x, y) ? toggle.getMessage() : null;
    }

    private double current() {
        return LitematicaConfigDiscovery.number(option, integer ? "getIntegerValue" : "getDoubleValue");
    }

    private void edit(String text) {
        try {
            double number = integer ? Integer.parseInt(text) : Double.parseDouble(text);
            if (!Double.isFinite(number) || number < minimum || number > maximum) throw new NumberFormatException();
            LitematicaConfigDiscovery.setNumber(option, number, integer);
            input.setTextColor(0xFFE0E0E0);
            if (slider != null) slider.sync();
        } catch (NumberFormatException ignored) {
            input.setTextColor(0xFFFF7272);
        }
    }

    private void focus(AbstractWidget widget) {
        if (focused != null) focused.setFocused(false);
        focused = widget;
        focused.setFocused(isFocused());
    }

    @Override public void setFocused(boolean focused) {
        super.setFocused(focused);
        if (this.focused == null) this.focused = body();
        this.focused.setFocused(focused);
        if (!focused && option instanceof LitematicaRenderLayerSettings.Setting) syncFromOption();
    }

    private void syncFromOption() {
        String current = LitematicaConfigDiscovery.value(option);
        if (!input.getValue().equals(current)) input.setValue(current);
        slider.sync();
    }

    @Override protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int x, int y, float tick) {
        if (!input.isFocused() && option instanceof LitematicaRenderLayerSettings.Setting) syncFromOption();
        body().extractRenderState(graphics, x, y, tick);
        toggle.extractRenderState(graphics, x, y, tick);
    }

    @Override public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
        if (toggle.mouseClicked(click, doubled)) return true;
        AbstractWidget body = body();
        if (!body.mouseClicked(click, doubled)) return false;
        focus(body);
        return true;
    }

    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) { return body().mouseDragged(event, dx, dy); }
    @Override public boolean mouseReleased(MouseButtonEvent event) { return body().mouseReleased(event); }
    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == GLFW.GLFW_KEY_TAB) {
            if (event.hasShiftDown() ? focused != toggle : focused == toggle) return false;
            focus(focused == toggle ? body() : toggle);
            return true;
        }
        return focused != null && focused.keyPressed(event);
    }
    @Override public boolean charTyped(CharacterEvent event) { return focused != null && focused.charTyped(event); }
    @Override public boolean preeditUpdated(PreeditEvent event) { return focused != null && focused.preeditUpdated(event); }
    @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }

    private final class ValueSlider extends AbstractSliderButton {
        ValueSlider() {
            super(0, 0, LitematicaNumericWidget.this.getWidth(), 20, Component.empty(), 0);
            sync();
        }
        void sync() {
            value = maximum > minimum ? Math.clamp((current() - minimum) / (maximum - minimum), 0, 1) : 0;
            updateMessage();
        }
        @Override protected void updateMessage() { setMessage(Component.literal(LitematicaConfigDiscovery.value(option))); }
        @Override protected void applyValue() {
            double next = minimum * (1 - value) + maximum * value;
            if (integer) next = Math.round(next);
            LitematicaConfigDiscovery.setNumber(option, Math.clamp(next, minimum, maximum), integer);
            input.setValue(LitematicaConfigDiscovery.value(option));
        }
        @Override public boolean keyPressed(KeyEvent event) {
            if (event.key() == GLFW.GLFW_KEY_LEFT || event.key() == GLFW.GLFW_KEY_RIGHT) {
                double step = integer ? 1 : (maximum - minimum) / 100.0;
                double next = Math.clamp(current() + (event.key() == GLFW.GLFW_KEY_RIGHT ? step : -step), minimum, maximum);
                if (maximum > minimum) setValue((next - minimum) / (maximum - minimum));
                return true;
            }
            return super.keyPressed(event);
        }
        @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { return false; }
    }
}
