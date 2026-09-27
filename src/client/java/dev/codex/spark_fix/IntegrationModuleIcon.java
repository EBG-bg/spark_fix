package dev.codex.spark_fix;

import io.wispforest.owo.ui.base.BaseUIComponent;
import io.wispforest.owo.ui.core.OwoUIGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Fixed-size, non-interactive icon used beside an integrated module card. */
final class IntegrationModuleIcon extends BaseUIComponent {
    private final Identifier texture;
    private final int textureSize;

    IntegrationModuleIcon(Identifier texture, int textureSize) {
        this.texture = texture;
        this.textureSize = textureSize;
        this.sizing(io.wispforest.owo.ui.core.Sizing.fixed(texture == null ? 0 : 32),
                io.wispforest.owo.ui.core.Sizing.fixed(texture == null ? 0 : 32));
        this.margins(io.wispforest.owo.ui.core.Insets.right(texture == null ? 0 : 4));
    }

    @Override
    protected int determineHorizontalContentSize(io.wispforest.owo.ui.core.Sizing sizing) {
        return texture == null ? 0 : 32;
    }

    @Override
    protected int determineVerticalContentSize(io.wispforest.owo.ui.core.Sizing sizing) {
        return texture == null ? 0 : 32;
    }

    @Override
    public void draw(OwoUIGraphics graphics, int mouseX, int mouseY, float partialTicks, float delta) {
        if (texture != null) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, texture, x(), y(), 0, 0, width(), height(),
                    textureSize, textureSize);
        }
    }
}
