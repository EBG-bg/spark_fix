package dev.codex.spark_fix;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** Shared translucent entry; no vanilla or MaLiLib button background is drawn. */
public final class ProjectionButton extends AbstractWidget {
    private static final Identifier ICON = Identifier.fromNamespaceAndPath("litematica", "icon.png");
    private final Runnable action;
    private final IntegrationSettingsStyle.HoverFade hoverFade = new IntegrationSettingsStyle.HoverFade();

    public ProjectionButton(int x, int y, int width, Runnable action) {
        super(x, y, width, heightForWidth(width), Component.translatable("config.spark_fix.litematica_open"));
        this.action = action;
    }

    public static int heightForWidth(int width) {
        return Math.max(72, Math.min(112, width));
    }

    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        float hover = hoverFade.sample(active && isHoveredOrFocused());
        int fill = IntegrationSettingsStyle.blend(0x18223A60, 0x303A64A0, hover);
        IntegrationSettingsStyle.roundedRect(graphics, getX(), getY(), getWidth(), getHeight(), 8, fill);
        IntegrationSettingsStyle.moduleToggleOutline(graphics, getX(), getY(), getWidth(), getHeight(), hover);
        int iconSize = Math.min(48, Math.max(32, getWidth() - 20));
        graphics.blit(RenderPipelines.GUI_TEXTURED, ICON, getX() + (getWidth() - iconSize) / 2,
                getY() + 7, 0.0F, 0.0F, iconSize, iconSize, 32, 32, 32, 32);
        var font = Minecraft.getInstance().font;
        int textWidth = Math.max(1, getWidth() - 10);
        int textHeight = font.wordWrapHeight(getMessage(), textWidth);
        graphics.textWithWordWrap(font, getMessage(), getX() + 5,
                getY() + getHeight() - textHeight - 7, textWidth,
                active ? 0xFFFFFFFF : 0xFFA0A0A0);
    }

    @Override
    public void onClick(MouseButtonEvent click, boolean doubled) {
        if (active && visible && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) action.run();
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (!active || !visible || !isFocused() || !input.isSelection()) return false;
        playDownSound(Minecraft.getInstance().getSoundManager());
        action.run();
        return true;
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
