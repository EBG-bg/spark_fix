package dev.codex.spark_fix;

import cn.reibridge.config.BridgeConfig;
import cn.reibridge.gui.RecipeDiagnostics;
import cn.reibridge.recipe.RecipeCaptureStore;
import me.shedaniel.rei.api.client.REIRuntime;
import me.shedaniel.rei.api.client.config.ConfigObject;
import me.shedaniel.rei.api.client.overlay.ScreenOverlay;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Standalone REI Recipe Bridge page, separate from the main integration toggles. */
final class ReiRecipeBridgeSettingsScreen extends Screen {
    private final Screen parent;
    private RecipeDiagnostics diagnostics;

    ReiRecipeBridgeSettingsScreen(Screen parent) {
        super(Component.translatable("rei_recipe_bridge.title"));
        this.parent = parent;
        BridgeConfig.load();
    }

    @Override
    protected void init() {
        BridgeConfig config = BridgeConfig.get();
        int left = this.width / 2 - 150;

        this.addRenderableWidget(CycleButton.onOffBuilder(config.provideVanillaRecipes)
                .create(left, 58, 300, 20,
                        Component.translatable("rei_recipe_bridge.vanilla_toggle"),
                        (button, value) -> config.provideVanillaRecipes = value));
        this.addRenderableWidget(CycleButton.onOffBuilder(config.captureUnlockedRecipes)
                .create(left, 84, 300, 20,
                        Component.translatable("rei_recipe_bridge.capture_toggle"),
                        (button, value) -> config.captureUnlockedRecipes = value));
        this.addRenderableWidget(Button.builder(Component.translatable("rei_recipe_bridge.refresh"),
                        button -> refresh())
                .bounds(left, this.height - 52, 145, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("rei_recipe_bridge.save"),
                        button -> saveAndClose())
                .bounds(left + 155, this.height - 52, 145, 20).build());
        refresh();
    }

    /** REI's configuration switches must be click-only; the wheel is intentionally consumed. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return true;
    }

    private void refresh() {
        BridgeConfig.save();
        try {
            RecipeCaptureStore.captureCurrentRecipeBook();
            this.diagnostics = RecipeDiagnostics.collect();
            REIRuntime.getInstance().getOverlay().ifPresent(ScreenOverlay::queueReloadSearch);
        } catch (RuntimeException exception) {
            SparkFixClient.LOGGER.debug("Could not refresh REI Recipe Bridge settings", exception);
        }
    }

    private void saveAndClose() {
        refresh();
        this.minecraft.setScreenAndShow(this.parent);
    }

    @Override
    public void onClose() {
        saveAndClose();
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 20, 0xFFFFFFFF);
        graphics.text(this.font, Component.translatable("rei_recipe_bridge.vanilla_mode",
                ConfigObject.getInstance().getForceLocalRecipes().name()),
                this.width / 2 - 150, 38, 0xFFB0B0B0);
        if (this.diagnostics == null || this.diagnostics.totalDisplays() == 0) {
            graphics.textWithWordWrap(this.font, Component.translatable("rei_recipe_bridge.no_recipes"),
                    this.width / 2 - 150, 110, 300, 0xFFFFAA00);
        } else {
            int y = 110;
            graphics.text(this.font, Component.translatable("rei_recipe_bridge.total", this.diagnostics.totalDisplays()),
                    this.width / 2 - 150, y, 0xFFFFFFFF);
            graphics.text(this.font, Component.translatable("rei_recipe_bridge.named", this.diagnostics.namedDisplays()),
                    this.width / 2 - 150, y + 14, 0xFFB0B0B0);
            graphics.text(this.font, Component.translatable("rei_recipe_bridge.vanilla", this.diagnostics.vanillaDisplays()),
                    this.width / 2 - 150, y + 28, 0xFFB0B0B0);
            graphics.text(this.font, Component.translatable("rei_recipe_bridge.cached", this.diagnostics.cached()),
                    this.width / 2 - 150, y + 42, 0xFFB0B0B0);
            graphics.text(this.font, Component.translatable("rei_recipe_bridge.packet",
                            this.diagnostics.lastReceived(), this.diagnostics.lastMerged(),
                            this.diagnostics.lastRegistered()),
                    this.width / 2 - 150, y + 56, 0xFFB0B0B0);
        }
        graphics.textWithWordWrap(this.font, Component.translatable("rei_recipe_bridge.capture_hint"),
                this.width / 2 - 150, this.height - 92, 300, 0xFFFFAA00);
    }
}
