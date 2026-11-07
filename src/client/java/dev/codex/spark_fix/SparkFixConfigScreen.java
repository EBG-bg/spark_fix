package dev.codex.spark_fix;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Compact configuration screen exposed by Mod Menu. */
final class SparkFixConfigScreen extends Screen {
    private final Screen parent;
    private EditBox maxClicksField;
    /**
     * The text currently being edited, kept separately from the persisted
     * value so a widget rebuild (for example after a resize) does not discard
     * an unsaved draft.
     */
    private String maxClicksDraft;
    private boolean scanAllTranslations;

    SparkFixConfigScreen(Screen parent) {
        super(Component.translatable("config.spark_fix.title"));
        this.parent = parent;
        this.maxClicksDraft = Integer.toString(SparkFixConfig.maxReiClicks());
        this.scanAllTranslations = SparkFixConfig.scanAllTranslations();
    }

    @Override
    protected void init() {
        // Screen.init can run more than once. Capture the old widget before it
        // is replaced so the latest unsaved text survives the rebuild.
        if (this.maxClicksField != null) {
            this.maxClicksDraft = this.maxClicksField.getValue();
        }
        int center = this.width / 2;
        int top = Math.max(42, this.height / 2 - 68);
        this.addRenderableWidget(Button.builder(
                Component.translatable("config.spark_fix.integration_settings"),
                button -> saveThen(() -> this.minecraft.setScreenAndShow(IntegrationSettingsRouter.create(this))))
                .bounds(this.width - 210, 8, 200, 20).build());
        this.addRenderableWidget(new StringWidget(center - 100, top + 2, 200, 14,
                Component.translatable("config.spark_fix.rei_max_clicks"), this.font));
        this.maxClicksField = this.addRenderableWidget(new EditBox(this.font, center - 100, top + 18, 200, 20,
                Component.translatable("config.spark_fix.rei_max_clicks")));
        this.maxClicksField.setMaxLength(5);
        this.maxClicksField.setValue(this.maxClicksDraft);
        this.maxClicksField.setTooltip(Tooltip.create(Component.translatable("config.spark_fix.max_clicks_range",
                SparkFixConfig.MIN_MAX_REI_CLICKS, SparkFixConfig.MAX_MAX_REI_CLICKS)));
        this.maxClicksField.setResponder(value -> {
            this.maxClicksDraft = value;
            this.maxClicksField.setTextColor(validMaxClicks() ? 0xFFE0E0E0 : 0xFFFF7272);
        });
        this.addRenderableWidget(CycleButton.booleanBuilder(Component.translatable("config.spark_fix.on"),
                Component.translatable("config.spark_fix.off"), this.scanAllTranslations)
                .create(center - 100, top + 54, 200, 20,
                        Component.translatable("config.spark_fix.axiom_scan_all_translations"),
                        (button, value) -> this.scanAllTranslations = value));
        var filterFavorites = this.addRenderableWidget(CycleButton.booleanBuilder(Component.translatable("config.spark_fix.on"),
                Component.translatable("config.spark_fix.off"), SparkFixConfig.litematicaFilterFavorites())
                .create(center - 100, top + 80, 200, 20,
                        Component.translatable("config.spark_fix.litematica_filter_favorites"),
                        (button, value) -> {
                            SparkFixConfig.setLitematicaFilterFavorites(value);
                            SettingsSaveFeedback.saveNow();
                        }));
        filterFavorites.setTooltip(Tooltip.create(Component.translatable("config.spark_fix.litematica_filter_favorites_hint")));
        this.addRenderableWidget(Button.builder(Component.translatable("gui.spark_fix.patch_monitor.open"),
                button -> saveThen(() -> this.minecraft.setScreenAndShow(new PatchMonitorScreen(this))))
                .bounds(center - 100, top + 106, 200, 20).build());
        this.addRenderableWidget(Button.builder(Component.translatable("config.spark_fix.done"),
                button -> this.saveAndClose()).bounds(center - 100, top + 132, 200, 20).build());
        this.setInitialFocus(this.maxClicksField);
    }

    /** CycleButton normally changes value on wheel input; settings are click-only. */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        return true;
    }

    @Override
    public void onClose() { this.saveAndClose(); }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
    }

    private void saveAndClose() {
        saveThen(() -> this.minecraft.setScreenAndShow(this.parent));
    }

    private boolean validMaxClicks() {
        try {
            int value = Integer.parseInt(maxClicksDraft.trim());
            return value >= SparkFixConfig.MIN_MAX_REI_CLICKS && value <= SparkFixConfig.MAX_MAX_REI_CLICKS;
        } catch (NumberFormatException exception) { return false; }
    }

    private void saveThen(Runnable next) {
        this.maxClicksDraft = this.maxClicksField.getValue();
        if (!validMaxClicks()) {
            maxClicksField.setTextColor(0xFFFF7272);
            setFocused(maxClicksField);
            return;
        }
        SparkFixConfig.setMaxReiClicks(Integer.parseInt(this.maxClicksDraft.trim()));
        SparkFixConfig.setScanAllTranslations(this.scanAllTranslations);
        if (SparkFixConfig.trySave()) next.run();
        else SettingsSaveFeedback.showFailure(this, () -> saveThen(next));
    }
}
