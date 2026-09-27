package dev.codex.spark_fix;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Independent palette UI; only the option's public getter/setter and default are reused. */
final class LitematicaColorEditorScreen extends Screen {
    private final Screen parent;
    private final Object option;
    private final int original;
    private final LitematicaColorState color;
    private final Map<LitematicaColorState.Channel, EditBox> inputs = new EnumMap<>(LitematicaColorState.Channel.class);
    private final Map<LitematicaColorState.Channel, ChannelSlider> sliders = new EnumMap<>(LitematicaColorState.Channel.class);
    private final Set<EditBox> invalid = new HashSet<>();
    private EditBox hex;
    private Button done;
    private ColorPlane plane;
    private int panelLeft;
    private int panelWidth;
    private int previewY;
    private int leftWidth;
    private boolean updating;
    private long openedAt;

    LitematicaColorEditorScreen(Screen parent, Object option, String name) {
        super(Component.translatable("config.spark_fix.litematica_color_title", name));
        this.parent = parent;
        this.option = option;
        original = (int) LitematicaConfigDiscovery.number(option, "getIntegerValue");
        color = new LitematicaColorState(original);
    }

    @Override protected void init() {
        if (openedAt == 0) openedAt = System.nanoTime();
        clearWidgets();
        inputs.clear();
        sliders.clear();
        invalid.clear();
        panelWidth = Math.min(620, width - 24);
        panelLeft = (width - panelWidth) / 2;
        int bodyWidth = panelWidth - 24;
        int bodyHeight = Math.max(112, height - 104);
        leftWidth = Math.clamp(bodyWidth * 2 / 5, 104, 220);
        int squareSize = Math.max(40, Math.min(leftWidth - 22, bodyHeight - 70));
        int left = panelLeft + 12;
        int top = 52;
        plane = addRenderableWidget(new ColorPlane(left, top, squareSize, false));
        addRenderableWidget(new ColorPlane(left + squareSize + 8, top, squareSize, true));
        previewY = top + squareSize + 17;
        hex = addRenderableWidget(new EditBox(font, left + 5, previewY + 30, leftWidth - 10, 16,
                Component.literal("HEX (#AARRGGBB)")));
        hex.setBordered(false);
        hex.setMaxLength(9);
        hex.setTooltip(Tooltip.create(Component.translatable("config.spark_fix.litematica_color_hex_hint")));
        hex.setResponder(value -> {
            if (updating) return;
            if (color.parseHex(value)) { invalid.remove(hex); sync(hex); }
            else { invalid.add(hex); hex.setTextColor(0xFFFF8F9A); done.active = false; }
        });
        int rowHeight = Math.min(26, bodyHeight / 7);
        int column = left + leftWidth + 16;
        int rightWidth = panelLeft + panelWidth - 12 - column;
        for (var channel : LitematicaColorState.Channel.values()) {
            int y = top + channel.ordinal() * rowHeight;
            ChannelSlider slider = addRenderableWidget(new ChannelSlider(channel, column + 14, y,
                    Math.max(20, rightWidth - 62), Math.min(20, rowHeight - 2)));
            sliders.put(channel, slider);
            EditBox input = addRenderableWidget(new EditBox(font, panelLeft + panelWidth - 48, y + 4, 32, 14,
                    Component.literal(channel.name())));
            input.setBordered(false);
            input.setMaxLength(8);
            input.setResponder(value -> {
                if (updating) return;
                try {
                    color.set(channel, Double.parseDouble(value));
                    invalid.remove(input);
                    sync(input);
                } catch (IllegalArgumentException exception) {
                    invalid.add(input);
                    input.setTextColor(0xFFFF8F9A);
                    done.active = false;
                }
            });
            inputs.put(channel, input);
        }
        int buttonWidth = Math.min(100, (panelWidth - 40) / 3);
        addRenderableWidget(new PaletteButton(left, height - 38, buttonWidth,
                Component.translatable("config.spark_fix.litematica_color_reset"), () -> {
                    color.setArgb((int) LitematicaConfigDiscovery.number(option, "getDefaultIntegerValue"));
                    sync(null);
                }));
        addRenderableWidget(new PaletteButton(panelLeft + panelWidth - 18 - 2 * buttonWidth, height - 38, buttonWidth,
                Component.translatable("config.spark_fix.cancel"), this::onClose));
        done = addRenderableWidget(new PaletteButton(panelLeft + panelWidth - 12 - buttonWidth, height - 38, buttonWidth,
                Component.translatable("config.spark_fix.done"), () -> {
                    if (!invalid.isEmpty()) return;
                    LitematicaConfigDiscovery.setNumber(option, color.argb(), true);
                    onClose();
                }));
        sync(null);
    }

    private void sync(EditBox source) {
        updating = true;
        try {
            for (var entry : inputs.entrySet()) {
                EditBox input = entry.getValue();
                if (input != source) input.setValue(Long.toString(Math.round(color.get(entry.getKey()))));
                input.setTextColor(0xFFF3F7FF);
            }
            if (hex != source) hex.setValue(color.hex());
            hex.setTextColor(0xFFF3F7FF);
            invalid.clear();
            for (ChannelSlider slider : sliders.values()) slider.sync();
            done.active = true;
        } finally { updating = false; }
    }

    @Override public void onClose() { minecraft.setScreenAndShow(parent); }

    @Override public void extractBackground(GuiGraphicsExtractor graphics, int x, int y, float tick) {
        super.extractBackground(graphics, x, y, tick);
        float progress = Math.clamp((System.nanoTime() - openedAt) / 180_000_000f, 0, 1);
        IntegrationSettingsStyle.roundedRect(graphics, panelLeft, 12, panelWidth, height - 24, 12,
                (Math.round(100 * progress) << 24) | 0x87B1F9);
        graphics.enableScissor(panelLeft + 8, 14, panelLeft + panelWidth - 8, 44);
        graphics.centeredText(font, title, width / 2, 23, 0xFFFFFFFF);
        graphics.disableScissor();
        int left = panelLeft + 12;
        int swatchWidth = (leftWidth - 8) / 2;
        graphics.text(font, Component.translatable("config.spark_fix.litematica_color_before"), left, previewY - 12, 0xFFE4EEFF, false);
        graphics.text(font, Component.translatable("config.spark_fix.litematica_color_after"), left + swatchWidth + 8, previewY - 12, 0xFFE4EEFF, false);
        LitematicaColorButton.swatch(graphics, left, previewY, swatchWidth, 18, original);
        LitematicaColorButton.swatch(graphics, left + swatchWidth + 8, previewY, swatchWidth, 18, color.argb());
        inputBackground(graphics, hex);
        for (var entry : inputs.entrySet()) {
            EditBox input = entry.getValue();
            inputBackground(graphics, input);
            graphics.text(font, Component.literal(entry.getKey().name()), sliders.get(entry.getKey()).getX() - 14,
                    input.getY(), 0xFFE4EEFF, false);
        }
    }

    private void inputBackground(GuiGraphicsExtractor graphics, EditBox input) {
        IntegrationSettingsStyle.roundedRect(graphics, input.getX() - 4, input.getY() - 4,
                input.getWidth() + 8, input.getHeight() + 6, 6, input.isFocused() ? 0xCD345D84 : 0xB01C354F);
    }

    private final class PaletteButton extends Button {
        PaletteButton(int x, int y, int width, Component label, Runnable action) {
            super(x, y, width, 22, label, ignored -> action.run(), DEFAULT_NARRATION);
        }
        @Override protected void extractContents(GuiGraphicsExtractor graphics, int x, int y, float tick) {
            IntegrationSettingsStyle.roundedRect(graphics, getX(), getY(), getWidth(), getHeight(), 6,
                    isHoveredOrFocused() && active ? 0xE03D6895 : 0xBC203D5D);
            graphics.centeredText(font, getMessage(), getX() + getWidth() / 2, getY() + 7, active ? 0xFFFFFFFF : 0xFF91A0B5);
        }
    }

    private final class ColorPlane extends AbstractWidget {
        private final boolean hue;
        ColorPlane(int x, int y, int size, boolean hue) {
            super(x, y, hue ? 14 : size, size, Component.translatable(hue
                    ? "config.spark_fix.litematica_color_hue" : "config.spark_fix.litematica_color_sv"));
            this.hue = hue;
        }
        private void select(double x, double y) {
            if (hue) color.set(LitematicaColorState.Channel.H, Math.clamp((y - getY()) / (getHeight() - 1), 0, 1) * 360);
            else color.setSv((x - getX()) / (getWidth() - 1), 1 - (y - getY()) / (getHeight() - 1));
            sync(null);
        }
        @Override public void onClick(MouseButtonEvent event, boolean doubled) { select(event.x(), event.y()); }
        @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
            if (event.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false;
            select(event.x(), event.y());
            return true;
        }
        @Override public boolean keyPressed(KeyEvent event) {
            int key = event.key();
            if (key != GLFW.GLFW_KEY_LEFT && key != GLFW.GLFW_KEY_RIGHT && key != GLFW.GLFW_KEY_UP && key != GLFW.GLFW_KEY_DOWN) return false;
            if (hue) color.set(LitematicaColorState.Channel.H, Math.clamp(color.get(LitematicaColorState.Channel.H)
                    + (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_LEFT ? -1 : 1), 0, 360));
            else color.setSv(color.get(LitematicaColorState.Channel.S) / 100
                            + (key == GLFW.GLFW_KEY_LEFT ? -.01 : key == GLFW.GLFW_KEY_RIGHT ? .01 : 0),
                    color.get(LitematicaColorState.Channel.V) / 100
                            + (key == GLFW.GLFW_KEY_DOWN ? -.01 : key == GLFW.GLFW_KEY_UP ? .01 : 0));
            sync(null);
            return true;
        }
        @Override protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int x, int y, float tick) {
            graphics.outline(getX() - 1, getY() - 1, getWidth() + 2, getHeight() + 2, 0xBEBCD7FF);
            if (hue) {
                for (int row = 0; row < getHeight(); row++) graphics.fill(getX(), getY() + row, getRight(), getY() + row + 1,
                        0xFF000000 | LitematicaColorState.rgb(row * 360.0 / (getHeight() - 1), 1, 1));
                int marker = getY() + (int) Math.round(color.get(LitematicaColorState.Channel.H) / 360 * (getHeight() - 1));
                graphics.outline(getX() - 2, marker - 2, getWidth() + 4, 5, 0xFFFFFFFF);
            } else {
                for (int column = 0; column < getWidth(); column++) graphics.fillGradient(getX() + column, getY(), getX() + column + 1, getBottom(),
                        0xFF000000 | LitematicaColorState.rgb(color.get(LitematicaColorState.Channel.H), column / (double) (getWidth() - 1), 1), 0xFF000000);
                int markerX = getX() + (int) Math.round(color.get(LitematicaColorState.Channel.S) / 100 * (getWidth() - 1));
                int markerY = getY() + (int) Math.round((1 - color.get(LitematicaColorState.Channel.V) / 100) * (getHeight() - 1));
                graphics.outline(markerX - 3, markerY - 3, 7, 7, 0xFF162A43);
                graphics.outline(markerX - 2, markerY - 2, 5, 5, 0xFFFFFFFF);
            }
        }
        @Override protected void updateWidgetNarration(NarrationElementOutput output) { defaultButtonNarrationText(output); }
    }

    private final class ChannelSlider extends AbstractSliderButton {
        private final LitematicaColorState.Channel channel;
        ChannelSlider(LitematicaColorState.Channel channel, int x, int y, int width, int height) {
            super(x, y, width, height, Component.literal(channel.name()), 0);
            this.channel = channel;
        }
        void sync() { value = color.get(channel) / LitematicaColorState.maximum(channel); updateMessage(); }
        @Override protected void updateMessage() { setMessage(Component.literal(channel + ": " + Math.round(color.get(channel)))); }
        @Override protected void applyValue() { color.set(channel, value * LitematicaColorState.maximum(channel)); LitematicaColorEditorScreen.this.sync(null); }
        @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { return false; }
        @Override public boolean keyPressed(KeyEvent event) {
            if (event.key() == GLFW.GLFW_KEY_LEFT || event.key() == GLFW.GLFW_KEY_RIGHT) {
                setValue(Math.clamp(value + (event.key() == GLFW.GLFW_KEY_RIGHT ? 1 : -1) / LitematicaColorState.maximum(channel), 0, 1));
                return true;
            }
            return super.keyPressed(event);
        }
        private int sample(double fraction) {
            double h = color.get(LitematicaColorState.Channel.H), s = color.get(LitematicaColorState.Channel.S) / 100;
            double v = color.get(LitematicaColorState.Channel.V) / 100;
            int channelValue = (int) Math.round(fraction * 255), rgb = color.argb() & 0xFFFFFF;
            return switch (channel) {
                case H -> 0xFF000000 | LitematicaColorState.rgb(fraction * 360, 1, 1);
                case S -> 0xFF000000 | LitematicaColorState.rgb(h, fraction, v);
                case V -> 0xFF000000 | LitematicaColorState.rgb(h, s, fraction);
                case R -> 0xFF000000 | rgb & 0x00FFFF | channelValue << 16;
                case G -> 0xFF000000 | rgb & 0xFF00FF | channelValue << 8;
                case B -> 0xFF000000 | rgb & 0xFFFF00 | channelValue;
                case A -> rgb | channelValue << 24;
            };
        }
        @Override public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int x, int y, float tick) {
            IntegrationSettingsStyle.roundedRect(graphics, getX(), getY(), getWidth(), getHeight(), 6, 0xA01C354F);
            int trackX = getX() + 4, trackY = getY() + 6, trackWidth = getWidth() - 8, trackHeight = getHeight() - 12;
            if (channel == LitematicaColorState.Channel.A) LitematicaColorButton.swatch(graphics, trackX, trackY, trackWidth, trackHeight, 0);
            int steps = Math.min(64, trackWidth);
            for (int column = 0; column < steps; column++) graphics.fill(trackX + column * trackWidth / steps, trackY,
                    trackX + (column + 1) * trackWidth / steps, trackY + trackHeight, sample(column / (double) Math.max(1, steps - 1)));
            int marker = trackX + (int) Math.round(value * (trackWidth - 1));
            IntegrationSettingsStyle.roundedRect(graphics, marker - 2, getY() + 2, 5, getHeight() - 4, 4, 0xFFF0F6FF);
        }
    }
}
