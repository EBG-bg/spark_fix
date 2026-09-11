package dev.codex.spark_fix;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.fabricmc.loader.api.FabricLoader;

/** Independent page reserved for A Dance of Fire and Ice Go integration settings. */
final class AdofoigoSettingsScreen extends Screen {
    private final Screen parent;
    private boolean adofaigo;
    private boolean rei;

    AdofoigoSettingsScreen(Screen parent) {
        super(Component.translatable("config.spark_fix.adofaigo_full_name"));
        this.parent = parent;
        this.adofaigo = SparkFixConfig.adofaigoEnabled();
        this.rei = SparkFixConfig.reiRecipeBridgeEnabled();
    }

    @Override
    protected void init() {
        int left = this.width / 2 - 150;
        this.addRenderableWidget(CycleButton.onOffBuilder(adofaigo)
                .create(left, 58, 300, 20, Component.translatable("config.spark_fix.adofaigo_enabled"),
                        (b, value) -> adofaigo = value));
        this.addRenderableWidget(CycleButton.onOffBuilder(rei)
                .create(left, 84, 300, 20, Component.translatable("config.spark_fix.rei_bridge_enabled"),
                        (b, value) -> rei = value));
        boolean reiInstalled = FabricLoader.getInstance().isModLoaded("roughlyenoughitems");
        Button reiSettings = Button.builder(Component.translatable(reiInstalled
                                ? "config.spark_fix.rei_bridge_settings"
                                : "config.spark_fix.rei_settings_unavailable"),
                        button -> this.minecraft.setScreenAndShow(new ReiRecipeBridgeSettingsScreen(this)))
                .bounds(left, 112, 300, 20).build();
        reiSettings.active = reiInstalled;
        this.addRenderableWidget(reiSettings);
        this.addRenderableWidget(Button.builder(
                        Component.translatable("config.spark_fix.done"),
                        button -> saveAndClose())
                .bounds(this.width / 2 - 100, this.height - 45, 200, 20).build());
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return true;
    }

    @Override
    public void onClose() {
        saveAndClose();
    }

    private void saveAndClose() {
        SparkFixConfig.setAdofoigoEnabled(adofaigo);
        SparkFixConfig.setReiRecipeBridgeEnabled(rei);
        SparkFixConfig.save();
        this.minecraft.setScreenAndShow(this.parent);
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 24, 0xFFFFFFFF);
        graphics.textWithWordWrap(this.font,
                Component.translatable("config.spark_fix.adofaigo_description"),
                this.width / 2 - 180, 148, 360, 0xFFB8B8B8);
    }
}
