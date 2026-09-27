package dev.codex.spark_fix;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

/** Shared by the vanilla screen and owo's vanilla-widget adapter. */
final class ScrollSensitivitySlider extends AbstractSliderButton {
    enum Scope { INTEGRATIONS, LITEMATICA }

    private final Scope scope;

    ScrollSensitivitySlider(int x, int y, int width) {
        this(x, y, width, Scope.INTEGRATIONS);
    }

    ScrollSensitivitySlider(int x, int y, int width, Scope scope) {
        super(x, y, width, 24, Component.empty(), normalize(configuredSensitivity(scope), scope));
        this.scope = scope;
        setTooltip(Tooltip.create(Component.translatable(scope == Scope.INTEGRATIONS ? "config.spark_fix.scroll_sensitivity_hint"
                : "config.spark_fix.litematica_scroll_sensitivity_hint")));
        updateMessage();
    }

    private static double configuredSensitivity(Scope scope) {
        return scope == Scope.LITEMATICA ? SparkFixConfig.litematicaScrollSensitivity() : SparkFixConfig.scrollSensitivity();
    }

    private static double maximum(Scope scope) {
        return scope == Scope.LITEMATICA ? SparkFixConfig.MAX_LITEMATICA_SCROLL_SENSITIVITY : SparkFixConfig.MAX_SCROLL_SENSITIVITY;
    }

    private static double normalize(double sensitivity, Scope scope) {
        return (sensitivity - SparkFixConfig.MIN_SCROLL_SENSITIVITY)
                / (maximum(scope) - SparkFixConfig.MIN_SCROLL_SENSITIVITY);
    }

    private double sensitivity() {
        return Math.round((SparkFixConfig.MIN_SCROLL_SENSITIVITY + value
                * (maximum(scope) - SparkFixConfig.MIN_SCROLL_SENSITIVITY)) * 10.0) / 10.0;
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.translatable("config.spark_fix.scroll_sensitivity",
                String.format(Locale.ROOT, "%.1f", sensitivity())));
    }

    @Override
    protected void applyValue() {
        double next = sensitivity();
        if (Double.compare(next, configuredSensitivity(scope)) == 0) return;
        if (scope == Scope.LITEMATICA) SparkFixConfig.setLitematicaScrollSensitivity(next);
        else {
            SparkFixConfig.setScrollSensitivity(next);
            SparkFixConfig.save();
        }
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (input.key() == GLFW.GLFW_KEY_LEFT || input.key() == GLFW.GLFW_KEY_RIGHT) {
            setValue(normalize(sensitivity() + (input.key() == GLFW.GLFW_KEY_RIGHT ? 0.1 : -0.1), scope));
            return true;
        }
        return super.keyPressed(input);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return false;
    }
}
