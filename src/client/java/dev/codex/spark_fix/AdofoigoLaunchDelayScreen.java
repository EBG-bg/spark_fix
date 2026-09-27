package dev.codex.spark_fix;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** A saved launch delay, edited from the pause-menu icon's right-click action. */
public final class AdofoigoLaunchDelayScreen extends Screen {
    private final Screen parent;
    private int seconds = SparkFixConfig.adofaigoLaunchDelaySeconds();
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private EditBox input;
    private DelaySlider slider;
    private Button done;
    private boolean updating;

    public AdofoigoLaunchDelayScreen(Screen parent) {
        super(Component.translatable("config.spark_fix.adofaigo_delay_title"));
        this.parent = parent;
    }

    @Override protected void init() {
        panelWidth = Math.min(300, Math.max(1, width - 24));
        panelLeft = (width - panelWidth) / 2;
        panelTop = Math.max(12, (height - 136) / 2);
        slider = addRenderableWidget(new DelaySlider(panelLeft + 14, panelTop + 45, panelWidth - 94));
        input = addRenderableWidget(new EditBox(font, panelLeft + panelWidth - 72, panelTop + 45, 42, 20,
                Component.translatable("config.spark_fix.adofaigo_delay_title")));
        input.setMaxLength(2);
        input.setValue(Integer.toString(seconds));
        input.setHint(Component.literal("0-60"));
        input.setResponder(text -> {
            if (updating) return;
            try {
                int value = Integer.parseInt(text);
                if (value < 0 || value > SparkFixConfig.MAX_ADOFAIGO_LAUNCH_DELAY_SECONDS) throw new NumberFormatException();
                seconds = value;
                slider.sync();
                input.setTextColor(0xFFFFFFFF);
                done.active = true;
            } catch (NumberFormatException ignored) {
                input.setTextColor(0xFFFF8585);
                done.active = false;
            }
        });
        int buttonWidth = Math.min(100, (panelWidth - 40) / 2);
        addRenderableWidget(Button.builder(Component.translatable("config.spark_fix.cancel"), ignored -> onClose())
                .bounds(panelLeft + 14, panelTop + 99, buttonWidth, 22).build());
        done = addRenderableWidget(Button.builder(Component.translatable("config.spark_fix.done"), ignored -> saveAndClose())
                .bounds(panelLeft + panelWidth - buttonWidth - 14, panelTop + 99, buttonWidth, 22).build());
    }

    private void saveAndClose() {
        if (!done.active) return;
        SparkFixConfig.setAdofoigoLaunchDelaySeconds(seconds);
        SparkFixConfig.save();
        onClose();
    }

    @Override public void onClose() { minecraft.setScreenAndShow(parent); }

    @Override public boolean keyPressed(KeyEvent event) {
        if ((event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) && getFocused() == input) {
            saveAndClose();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float tick) {
        super.extractBackground(graphics, mouseX, mouseY, tick);
        IntegrationSettingsStyle.roundedRect(graphics, panelLeft, panelTop, panelWidth, 136, 8, 0x7087B1F9);
        graphics.centeredText(font, title, width / 2, panelTop + 17, 0xFFFFFFFF);
        graphics.text(font, Component.translatable("config.spark_fix.adofaigo_delay_unit"),
                panelLeft + panelWidth - 26, panelTop + 51, 0xFFFFFFFF, false);
        graphics.centeredText(font, Component.translatable("config.spark_fix.adofaigo_delay_zero"),
                width / 2, panelTop + 78, 0xFFEAF3FF);
    }

    private final class DelaySlider extends AbstractSliderButton {
        private DelaySlider(int x, int y, int width) {
            super(x, y, width, 20, Component.empty(), seconds / (double) SparkFixConfig.MAX_ADOFAIGO_LAUNCH_DELAY_SECONDS);
            updateMessage();
        }

        private void sync() { setValue(seconds / (double) SparkFixConfig.MAX_ADOFAIGO_LAUNCH_DELAY_SECONDS); }

        @Override protected void updateMessage() {
            setMessage(Component.translatable("config.spark_fix.adofaigo_delay", seconds));
        }

        @Override protected void applyValue() {
            seconds = (int) Math.round(value * SparkFixConfig.MAX_ADOFAIGO_LAUNCH_DELAY_SECONDS);
            value = seconds / (double) SparkFixConfig.MAX_ADOFAIGO_LAUNCH_DELAY_SECONDS;
            if (input == null || done == null) return;
            updating = true;
            try {
                input.setValue(Integer.toString(seconds));
                input.setTextColor(0xFFFFFFFF);
                done.active = true;
            } finally { updating = false; }
        }

        @Override public boolean keyPressed(KeyEvent event) {
            if (event.key() == GLFW.GLFW_KEY_LEFT || event.key() == GLFW.GLFW_KEY_RIGHT) {
                setValue(Math.clamp(seconds + (event.key() == GLFW.GLFW_KEY_LEFT ? -1 : 1),
                        0, SparkFixConfig.MAX_ADOFAIGO_LAUNCH_DELAY_SECONDS)
                        / (double) SparkFixConfig.MAX_ADOFAIGO_LAUNCH_DELAY_SECONDS);
                return true;
            }
            return super.keyPressed(event);
        }
    }
}
