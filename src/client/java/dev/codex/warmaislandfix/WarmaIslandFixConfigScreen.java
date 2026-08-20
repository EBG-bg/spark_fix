package dev.codex.warmaislandfix;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Compact configuration screen for the two guarded compatibility options. */
final class WarmaIslandFixConfigScreen extends Screen {
    private final Screen parent;
    private EditBox maxClicksField;
    private boolean scanAllTranslations;

    WarmaIslandFixConfigScreen(Screen parent) {
        super(Component.translatable("config.warmaislandfix.title"));
        this.parent = parent;
        this.scanAllTranslations = WarmaIslandFixConfig.scanAllTranslations();
    }

    @Override
    protected void init() {
        int center = this.width / 2;
        int top = Math.max(42, this.height / 2 - 54);

        this.maxClicksField = this.addRenderableWidget(new EditBox(
            this.font,
            center - 100,
            top + 18,
            200,
            20,
            Component.translatable("config.warmaislandfix.rei_max_clicks")
        ));
        this.maxClicksField.setMaxLength(5);
        this.maxClicksField.setValue(Integer.toString(WarmaIslandFixConfig.maxReiClicks()));

        CycleButton<Boolean> scanButton = CycleButton.booleanBuilder(
            Component.translatable("config.warmaislandfix.on"),
            Component.translatable("config.warmaislandfix.off"),
            this.scanAllTranslations
        ).create(
            center - 100,
            top + 54,
            200,
            20,
            Component.translatable("config.warmaislandfix.axiom_scan_all_translations"),
            (button, value) -> this.scanAllTranslations = value
        );
        this.addRenderableWidget(scanButton);

        this.addRenderableWidget(Button.builder(
            Component.translatable("config.warmaislandfix.done"),
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
        this.extractMenuBackground(graphics);
        graphics.centeredText(this.font, this.title, this.width / 2, 20, 0xFFFFFF);
        graphics.text(
            this.font,
            Component.translatable("config.warmaislandfix.rei_max_clicks"),
            this.maxClicksField.getX(),
            this.maxClicksField.getY() - 12,
            0xFFFFFF
        );
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void saveAndClose() {
        int maxClicks;
        try {
            maxClicks = Integer.parseInt(this.maxClicksField.getValue().trim());
        } catch (NumberFormatException exception) {
            maxClicks = WarmaIslandFixConfig.DEFAULT_MAX_REI_CLICKS;
        }

        WarmaIslandFixConfig.setMaxReiClicks(maxClicks);
        WarmaIslandFixConfig.setScanAllTranslations(this.scanAllTranslations);
        WarmaIslandFixConfig.save();
        this.minecraft.setScreenAndShow(this.parent);
    }
}
