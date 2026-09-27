package dev.codex.spark_fix;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Shared decoration without an optional UI dependency. */
final class IntegrationSettingsStyle {
    // 15% black overlay, matching the requested translucent CozyUI-style panel.
    static final int PANEL = 0x26000000;
    static final int SETTINGS_PANEL = 0x40000000;
    static final int MODULE_CARD = 0x14FFFFFF;
    static final int MODULE_CARD_HOVER = 0x24000000;
    static final int MUTED = 0xFFE0E5ED;
    private static final Identifier MODULE_OUTLINE = Identifier.fromNamespaceAndPath("spark_fix", "module_outline");
    private static final Identifier ROUNDED_6 = sprite("rounded_6");
    private static final Identifier ROUNDED_7 = sprite("rounded_7");
    private static final Identifier ROUNDED_8 = sprite("rounded_8");
    private static final Identifier ROUNDED_12 = sprite("rounded_12");
    private static final Identifier TOGGLE_TRACK = sprite("toggle_track");
    private static final Identifier TOGGLE_THUMB = sprite("toggle_thumb");
    private static final Identifier DISCLOSURE_RIGHT = sprite("disclosure_right");
    private static final Identifier DISCLOSURE_DOWN = sprite("disclosure_down");

    private static Identifier sprite(String name) {
        return Identifier.fromNamespaceAndPath("spark_fix", name);
    }

    private IntegrationSettingsStyle() {}

    /** Real-time transition, independent of frame rate and paused game ticks. */
    static final class HoverFade {
        private long changedAt;
        private float start;
        private boolean target;

        float sample(boolean hovered) {
            long now = System.nanoTime();
            float elapsed = Math.clamp((now - changedAt) / 120_000_000f, 0f, 1f);
            float eased = elapsed * elapsed * (3f - 2f * elapsed);
            float value = start + ((target ? 1f : 0f) - start) * eased;
            if (hovered != target) {
                start = value;
                target = hovered;
                changedAt = now;
            }
            return value;
        }
    }

    static int blend(int from, int to, float amount) {
        int color = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            int a = (from >>> shift) & 255;
            int b = (to >>> shift) & 255;
            color |= Math.round(a + (b - a) * amount) << shift;
        }
        return color;
    }

    /** Packaged alpha masks avoid both per-frame geometry and font emoji substitution. */
    static void disclosure(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
                           boolean expanded, boolean hovered) {
        if (hovered) roundedRect(graphics, x, y, width, height, 6, 0x28FFFFFF);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, expanded ? DISCLOSURE_DOWN : DISCLOSURE_RIGHT,
                x + (width - 10) / 2, y + (height - 10) / 2, 10, 10, 0xFFE0E5ED);
    }

    static void toggle(GuiGraphicsExtractor graphics, Font font, Component message,
                       int x, int y, int width, int height, boolean selected, boolean hovered) {
        toggle(graphics, font, message, x, y, width, height, selected, hovered ? 1f : 0f);
    }

    static void toggle(GuiGraphicsExtractor graphics, Font font, Component message,
                       int x, int y, int width, int height, boolean selected, float hoverAmount) {
        int hoverColor = blend(0x00FFFFFF, 0x28FFFFFF, hoverAmount);
        if ((hoverColor >>> 24) != 0) roundedRect(graphics, x, y, width, height, 7, hoverColor);
        var lines = font.split(message, Math.max(1, width - 48));
        int textY = y + (height - lines.size() * font.lineHeight) / 2;
        for (var line : lines) {
            graphics.text(font, line, x + 5, textY, 0xFFFFFFFF);
            textY += font.lineHeight;
        }
        int trackX = x + width - 36;
        int trackY = y + (height - 18) / 2;
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, TOGGLE_TRACK, trackX, trackY, 32, 18,
                selected ? 0xB347AD90 : 0x70525D6C);
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, TOGGLE_THUMB,
                trackX + (selected ? 17 : 3), trackY + 3, 12, 12, 0xFFF3F7FB);
    }

    static void moduleToggleOutline(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
                                    float hoverAmount) {
        int color = blend(0xFF87B1F9, 0xFF6694DE, hoverAmount);
        // Four physical texels per GUI pixel; nine-slicing keeps corners round at either button width.
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, MODULE_OUTLINE, x, y, width, height, color);
    }

    /** Precomputed corners and non-overlapping nine-slicing preserve uniform translucent fills. */
    static void roundedRect(GuiGraphicsExtractor graphics, int x, int y, int width, int height,
                            int radius, int color) {
        if (width <= 0 || height <= 0) return;
        Identifier texture = switch (radius) {
            // Small controls such as the alias '+' button do not need a
            // separate texture atlas entry. The 6px mask is the closest
            // precomputed nine-slice and remains valid at this size.
            case 4, 5, 6 -> ROUNDED_6;
            case 7 -> ROUNDED_7;
            case 8 -> ROUNDED_8;
            case 12 -> ROUNDED_12;
            default -> radius < 7 ? ROUNDED_6 : radius < 9 ? ROUNDED_8 : ROUNDED_12;
        };
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, texture, x, y, width, height, color);
    }
}
