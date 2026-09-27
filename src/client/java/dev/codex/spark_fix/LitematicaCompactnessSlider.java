package dev.codex.spark_fix;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** Five marked column counts; persisted together when the screen closes. */
final class LitematicaCompactnessSlider extends AbstractSliderButton {
    private static final Identifier TRACK = Identifier.withDefaultNamespace("widget/slider");
    private static final Identifier TRACK_FOCUSED = Identifier.withDefaultNamespace("widget/slider_highlighted");
    private static final Identifier HANDLE = Identifier.withDefaultNamespace("widget/slider_handle");
    private static final Identifier HANDLE_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/slider_handle_highlighted");
    private static final int LEVELS = 5;
    private final Runnable changed;

    LitematicaCompactnessSlider(int x, int y, int width, Runnable changed) {
        super(x, y, width, 20, Component.empty(), normalizedValue());
        this.changed = changed;
        setTooltip(Tooltip.create(Component.translatable("config.spark_fix.litematica_compactness_hint")));
        updateMessage();
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.translatable("config.spark_fix.litematica_compactness", level()));
    }

    @Override
    protected void applyValue() {
        SparkFixConfig.setLitematicaCompactness(level());
        changed.run();
    }

    @Override
    public void setValue(double next) {
        super.setValue(snap(next));
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (active && isFocused() && (event.isLeft() || event.isRight())) {
            setValue(value + (event.isRight() ? 1.0 : -1.0) / (LEVELS - 1));
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        int tint = (Math.round(255 * alpha) << 24) | 0xFFFFFF;
        Identifier track = active && isFocused() && !canChangeValue ? TRACK_FOCUSED : TRACK;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, track, getX(), getY(), getWidth(), getHeight(), tint);

        int selected = level() - 1;
        int markColor = (Math.round(110 * alpha) << 24) | 0xFFFFFF;
        for (int index = 1; index < LEVELS - 1; index++) {
            // The native handle covers the selected tick. Omit that line so it
            // cannot bleed through a translucent resource-pack handle.
            if (index == selected) continue;
            int x = stopLeft(index) + HANDLE_WIDTH / 2;
            // Resource packs such as CozyUI leave transparent margins above and
            // below the track. Keep marks inside its body, clear of the border.
            graphics.fill(x, getY() + 6, x + 1, getBottom() - 6, markColor);
        }

        Identifier handle = active && (isHovered() || canChangeValue) ? HANDLE_HIGHLIGHTED : HANDLE;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, handle,
                stopLeft(selected), getY(), HANDLE_WIDTH, getHeight(), tint);
        extractScrollingStringOverContents(
                graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE), getMessage(), 2);
        handleCursor(graphics);
    }

    private int stopLeft(int index) {
        return getX() + (int) Math.round(index / (double) (LEVELS - 1) * (getWidth() - HANDLE_WIDTH));
    }

    private int level() {
        return 1 + (int) Math.round(value * (LEVELS - 1));
    }

    private static double snap(double normalized) {
        int index = Math.clamp((int) Math.round(normalized * (LEVELS - 1)), 0, LEVELS - 1);
        return index / (double) (LEVELS - 1);
    }

    private static double normalizedValue() {
        return (Math.clamp((int) Math.round(SparkFixConfig.litematicaCompactness()), 1, LEVELS) - 1.0) / (LEVELS - 1);
    }
}
