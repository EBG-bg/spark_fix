package dev.codex.spark_fix;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Compact configuration screen for the two guarded compatibility options. */
final class SparkFixConfigScreen extends Screen {
    private final Screen parent;
    private EditBox maxClicksField;
    private boolean scanAllTranslations;

    SparkFixConfigScreen(Screen parent) {
        super(Component.translatable("config.spark_fix.title"));
        this.parent = parent;
        this.scanAllTranslations = SparkFixConfig.scanAllTranslations();
    }

    @Override
    protected void init() {
        int center = this.width / 2;
        int top = Math.max(42, this.height / 2 - 54);

        this.addRenderableWidget(new StringWidget(
            center - 100,
            top + 2,
            200,
            14,
            Component.translatable("config.spark_fix.rei_max_clicks"),
            this.font
        ));

        this.maxClicksField = this.addRenderableWidget(new EditBox(
            this.font,
            center - 100,
            top + 18,
            200,
            20,
            Component.translatable("config.spark_fix.rei_max_clicks")
        ));
        this.maxClicksField.setMaxLength(5);
        this.maxClicksField.setValue(Integer.toString(SparkFixConfig.maxReiClicks()));

        CycleButton<Boolean> scanButton = CycleButton.booleanBuilder(
            Component.translatable("config.spark_fix.on"),
            Component.translatable("config.spark_fix.off"),
            this.scanAllTranslations
        ).create(
            center - 100,
            top + 54,
            200,
            20,
            Component.translatable("config.spark_fix.axiom_scan_all_translations"),
            (button, value) -> this.scanAllTranslations = value
        );
        this.addRenderableWidget(scanButton);

        this.addRenderableWidget(Button.builder(
            Component.translatable("config.spark_fix.done"),
            button -> this.saveAndClose()
        ).bounds(center - 100, top + 90, 200, 20).build());

        this.setInitialFocus(this.maxClicksField);
    }

    @Override
    public void onClose() {
        this.saveAndClose();
    }

    @Override
    public void extractRenderState(
        GuiGraphicsExtractor graphics,
        int mouseX,
        int mouseY,
        float partialTick
    ) {
        // Extract widgets first so the custom screen title is layered above them.
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.centeredText(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
    }

    private void saveAndClose() {
        int maxClicks;
        try {
            maxClicks = Integer.parseInt(this.maxClicksField.getValue().trim());
        } catch (NumberFormatException exception) {
            maxClicks = SparkFixConfig.DEFAULT_MAX_REI_CLICKS;
        }

        SparkFixConfig.setMaxReiClicks(maxClicks);
        SparkFixConfig.setScanAllTranslations(this.scanAllTranslations);
        SparkFixConfig.save();
        this.minecraft.setScreenAndShow(this.parent);
    }
}
