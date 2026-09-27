package dev.codex.spark_fix;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Loaded-mod icon with a font-independent green check or red cross for visibility. */
final class LitematicaModuleButton extends AbstractWidget {
    static final int SIZE = 24;
    // Registered dynamic textures live with Minecraft's texture manager, once per loaded mod.
    private static final Map<String, Optional<Identifier>> ICONS = new HashMap<>();
    private final LitematicaConfigDiscovery.DiscoveredModule module;
    private final Consumer<Boolean> onChange;
    private boolean shown;
    private boolean categoryMenuOpen;

    LitematicaConfigDiscovery.DiscoveredModule module() { return module; }
    boolean shown() { return shown; }
    void setCategoryMenuOpen(boolean open) { categoryMenuOpen = open; }

    @Override protected void extractTooltipForNextRenderPass(GuiGraphicsExtractor graphics, int x, int y) {
        if (!categoryMenuOpen) super.extractTooltipForNextRenderPass(graphics, x, y);
    }

    LitematicaModuleButton(int x, int y, LitematicaConfigDiscovery.DiscoveredModule module,
                           boolean shown, Consumer<Boolean> onChange) {
        super(x, y, SIZE, SIZE, Component.empty());
        this.module = module;
        this.shown = shown;
        this.onChange = onChange;
        updateLabel();
    }

    private void updateLabel() {
        setMessage(Component.translatable(shown ? "config.spark_fix.litematica_module_shown"
                : "config.spark_fix.litematica_module_hidden", module.name()));
        setTooltip(Tooltip.create(getMessage().copy().append("\n" + module.id() + "\n")
                .append(Component.translatable("config.spark_fix.litematica_module_filter_hint"))));
    }

    private void toggle() {
        shown = !shown;
        updateLabel();
        onChange.accept(shown);
    }

    @Override public void onClick(MouseButtonEvent click, boolean doubled) {
        if (active && visible && click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) toggle();
    }

    @Override public boolean keyPressed(KeyEvent event) {
        if (!active || !visible || !isFocused() || !event.isSelection()) return false;
        playDownSound(Minecraft.getInstance().getSoundManager());
        toggle();
        return true;
    }

    @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }

    @Override protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tick) {
        int x = getX();
        int y = getY();
        IntegrationSettingsStyle.roundedRect(graphics, x, y, SIZE, SIZE, 6, 0xA087B1F9);
        IntegrationSettingsStyle.roundedRect(graphics, x + 1, y + 1, SIZE - 2, SIZE - 2, 5,
                isHoveredOrFocused() ? 0xBF355071 : 0x801A2B43);
        Optional<Identifier> icon = ICONS.computeIfAbsent(module.id(), ignored -> loadIcon());
        if (icon.isPresent()) {
            graphics.blit(icon.get(), x + 3, y + 3, SIZE - 6, SIZE - 6, 0, 1, 0, 1);
        } else {
            String initials = module.id().substring(0, Math.min(2, module.id().length())).toUpperCase(Locale.ROOT);
            graphics.centeredText(Minecraft.getInstance().font, Component.literal(initials), x + SIZE / 2, y + 6, 0xFFFFFFFF);
        }
        int badgeX = x + SIZE - 10;
        int badgeY = y + SIZE - 10;
        IntegrationSettingsStyle.roundedRect(graphics, badgeX - 1, badgeY - 1, 11, 11, 4, 0xEE17283B);
        int color = shown ? 0xFF56D886 : 0xFFFF7272;
        if (shown) {
            for (int i = 0; i < 3; i++) graphics.fill(badgeX + i, badgeY + 3 + i, badgeX + i + 2, badgeY + 5 + i, color);
            for (int i = 0; i < 5; i++) graphics.fill(badgeX + 3 + i, badgeY + 5 - i, badgeX + 5 + i, badgeY + 7 - i, color);
        } else {
            for (int i = 0; i < 7; i++) {
                graphics.fill(badgeX + 1 + i, badgeY + 1 + i, badgeX + 3 + i, badgeY + 3 + i, color);
                graphics.fill(badgeX + 1 + i, badgeY + 7 - i, badgeX + 3 + i, badgeY + 9 - i, color);
            }
        }
    }

    private Optional<Identifier> loadIcon() {
        if (module.container() == null) return Optional.empty();
        String path = module.container().getMetadata().getIconPath(32).orElse(null);
        if (path == null) return Optional.empty();
        if (path.startsWith("assets/")) {
            String[] parts = path.split("/", 3);
            if (parts.length == 3) return Optional.ofNullable(Identifier.tryBuild(parts[1], parts[2]));
        }
        var file = module.container().findPath(path);
        if (file.isEmpty()) return Optional.empty();
        Identifier id = Identifier.fromNamespaceAndPath("spark_fix", "module_icons/" + module.id());
        DynamicTexture texture = null;
        NativeImage image = null;
        try (var input = Files.newInputStream(file.get())) {
            image = NativeImage.read(input);
            texture = new DynamicTexture(() -> "spark_fix module icon: " + module.id(), image);
            image = null; // Owned by the texture from here.
            Minecraft.getInstance().getTextureManager().register(id, texture);
            return Optional.of(id);
        } catch (IOException | RuntimeException exception) {
            if (texture != null) texture.close();
            else if (image != null) image.close();
            SparkFixClient.LOGGER.debug("Could not load settings filter icon for {}", module.id(), exception);
            return Optional.empty();
        }
    }
}
