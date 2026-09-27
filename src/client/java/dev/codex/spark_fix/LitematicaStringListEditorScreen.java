package dev.codex.spark_fix;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.List;

/** Edits a copy of a MaLiLib string list and writes it only when confirmed. */
final class LitematicaStringListEditorScreen extends Screen {
    private static final int PANEL = 0x4887B1F9;
    private static final int ROW_HEIGHT = 26;
    private static final int ROW_STEP = 31;

    private final Screen parent;
    private final Object option;
    private final Component inputHint;
    private final Component inputGuide;
    private final List<Entry> entries = new ArrayList<>();
    private final List<Row> rows = new ArrayList<>();
    private final List<AbstractWidget> contentWidgets = new ArrayList<>();
    private List<FormattedCharSequence> guideLines = List.of();
    private int panelLeft;
    private int panelWidth;
    private int viewportTop;
    private int viewportBottom;
    private int scroll;
    private int maxScroll;
    private Button addButton;
    private boolean dirty;
    private long openedAt;

    LitematicaStringListEditorScreen(Screen parent, Object option, String group, String name) {
        super(Component.translatable("config.spark_fix.litematica_string_list_title", name));
        this.parent = parent;
        this.option = option;
        String format = "generic";
        if ("litematica-printer".equals(group)) {
            format = switch (LitematicaConfigDiscovery.stableName(option)) {
                case "挖掘白名单" -> "mining_whitelist";
                case "挖掘黑名单" -> "mining_blacklist";
                case "跳过放置名单", "基岩模式白名单", "可覆盖方块" -> "blocks";
                case "库存白名单" -> "containers";
                case "替换方块名单" -> "replacements";
                default -> "generic";
            };
        }
        inputHint = Component.translatable("config.spark_fix.litematica_string_list_hint_" + format);
        inputGuide = Component.translatable("config.spark_fix.litematica_string_list_guide_" + format);
        for (String value : LitematicaConfigDiscovery.stringListValue(option)) entries.add(new Entry(value));
    }

    @Override
    protected void init() {
        if (openedAt == 0) openedAt = System.nanoTime();
        layoutWidgets(null);
    }

    private void layoutWidgets(Entry focusEntry) {
        clearWidgets();
        rows.clear();
        contentWidgets.clear();
        panelWidth = Math.min(680, Math.max(1, width - 24));
        panelLeft = (width - panelWidth) / 2;
        guideLines = font.split(inputGuide, Math.max(1, panelWidth - 40));
        viewportTop = 51 + guideLines.size() * (font.lineHeight + 2) + 6;
        viewportBottom = Math.max(viewportTop + 1, height - 47);
        int rowX = panelLeft + 14;
        int rowWidth = Math.max(1, panelWidth - 28);
        int contentY = viewportTop + 3;

        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            int y = contentY + i * ROW_STEP;
            EditBox input = new EditBox(font, rowX + 38, y + 3, Math.max(20, rowWidth - 68), 20,
                    Component.translatable("config.spark_fix.litematica_string_list_item", i + 1)) {
                @Override public boolean isMouseOver(double x, double mouseY) {
                    return inViewport(x, mouseY) && super.isMouseOver(x, mouseY);
                }
            };
            input.setMaxLength(32767);
            input.setValue(entry.value);
            input.setHint(inputHint);
            input.setResponder(value -> {
                entry.value = value;
                dirty = true;
            });
            SettingsIconButton remove = new SettingsIconButton(rowX + rowWidth - 23, y + 4, 18,
                    SettingsIconButton.Icon.REMOVE,
                    Component.translatable("config.spark_fix.litematica_string_list_remove"),
                    () -> removeEntry(entry)) {
                @Override public boolean isMouseOver(double x, double mouseY) {
                    return inViewport(x, mouseY) && super.isMouseOver(x, mouseY);
                }
            };
            rows.add(new Row(entry, input, remove, y));
            contentWidgets.add(input);
            contentWidgets.add(remove);
            addWidget(input);
            addWidget(remove);
        }

        int addY = contentY + Math.max(1, entries.size()) * ROW_STEP + 3;
        addButton = new AddEntryButton(rowX + 38, addY, Math.max(20, Math.min(144, rowWidth - 68)));
        contentWidgets.add(addButton);
        addWidget(addButton);
        maxScroll = Math.max(0, addY + 26 - viewportBottom);
        scroll = Math.clamp(scroll, 0, maxScroll);
        if (focusEntry != null) {
            for (Row row : rows) {
                if (row.entry != focusEntry) continue;
                if (row.y - scroll < viewportTop + 3) scroll = row.y - viewportTop - 3;
                if (row.y + ROW_HEIGHT - scroll > viewportBottom - 3) {
                    scroll = row.y + ROW_HEIGHT - viewportBottom + 3;
                }
                scroll = Math.clamp(scroll, 0, maxScroll);
                break;
            }
        }
        for (AbstractWidget widget : contentWidgets) widget.setY(widget.getY() - scroll);

        int buttonWidth = Math.max(60, Math.min(96, (panelWidth - 36) / 2));
        int footerY = height - 36;
        addRenderableWidget(Button.builder(Component.translatable("config.spark_fix.cancel"), ignored -> onClose())
                .bounds(panelLeft + panelWidth - 2 * buttonWidth - 22, footerY, buttonWidth, 22).build());
        addRenderableWidget(Button.builder(Component.translatable("config.spark_fix.done"), ignored -> commitAndClose())
                .bounds(panelLeft + panelWidth - buttonWidth - 12, footerY, buttonWidth, 22).build());
        if (focusEntry != null) {
            for (Row row : rows) {
                if (row.entry == focusEntry) {
                    setFocused(row.input);
                    row.input.moveCursorToEnd(false);
                    break;
                }
            }
        }
    }

    private void addEntry(int index) {
        Entry entry = new Entry("");
        entries.add(index, entry);
        dirty = true;
        layoutWidgets(entry);
    }

    private void removeEntry(Entry entry) {
        int index = entries.indexOf(entry);
        if (index < 0) return;
        entries.remove(index);
        dirty = true;
        layoutWidgets(entries.isEmpty() ? null : entries.get(Math.min(index, entries.size() - 1)));
    }

    private void commitAndClose() {
        if (dirty) {
            List<String> values = entries.stream().map(entry -> entry.value)
                    .filter(value -> !value.isBlank()).toList();
            if (!LitematicaConfigDiscovery.setStringListValue(option, values)) return;
        }
        onClose();
    }

    @Override public void onClose() { minecraft.setScreenAndShow(parent); }

    private boolean inViewport(double x, double y) {
        return x >= panelLeft + 8 && x < panelLeft + panelWidth - 8
                && y >= viewportTop && y < viewportBottom;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (!inViewport(mouseX, mouseY)) return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
        int next = Math.clamp(scroll - (int) Math.round(vertical * 24), 0, maxScroll);
        int delta = scroll - next;
        scroll = next;
        for (AbstractWidget widget : contentWidgets) widget.setY(widget.getY() + delta);
        return true;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        float progress = Math.clamp((System.nanoTime() - openedAt) / 180_000_000f, 0f, 1f);
        float eased = progress * progress * (3f - 2f * progress);
        int panelColor = (Math.round((PANEL >>> 24) * eased) << 24) | (PANEL & 0xFFFFFF);
        IntegrationSettingsStyle.roundedRect(graphics, panelLeft, 12, panelWidth, height - 24, 12, panelColor);
        graphics.centeredText(font, title, width / 2, 23, 0xFFFFFFFF);
        int guideY = 51;
        for (FormattedCharSequence line : guideLines) {
            graphics.text(font, line, panelLeft + 20, guideY, 0xFFEAF3FF);
            guideY += font.lineHeight + 2;
        }
        graphics.enableScissor(panelLeft + 8, viewportTop, panelLeft + panelWidth - 8, viewportBottom);
        int rowX = panelLeft + 14;
        int rowWidth = panelWidth - 28;
        if (entries.isEmpty()) {
            graphics.text(font, Component.translatable("config.spark_fix.litematica_string_list_no_items"),
                    rowX + 8, viewportTop + 10 - scroll, 0xFFE2ECFC, false);
        }
        for (int i = 0; i < rows.size(); i++) {
            int y = rows.get(i).y - scroll;
            if (y + ROW_HEIGHT < viewportTop || y > viewportBottom) continue;
            IntegrationSettingsStyle.roundedRect(graphics, rowX, y, rowWidth, ROW_HEIGHT, 6, 0xA51B3552);
            graphics.text(font, Integer.toString(i + 1), rowX + 10, y + 8, 0xFFD9E9FF, false);
        }
        graphics.disableScissor();
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.enableScissor(panelLeft + 8, viewportTop, panelLeft + panelWidth - 8, viewportBottom);
        for (AbstractWidget widget : contentWidgets) {
            if (widget.getBottom() > viewportTop && widget.getY() < viewportBottom) {
                widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
            }
        }
        graphics.disableScissor();
    }

    private static final class Entry {
        private String value;
        private Entry(String value) { this.value = value; }
    }

    private final class AddEntryButton extends Button {
        private AddEntryButton(int x, int y, int width) {
            super(x, y, width, 22, Component.translatable("config.spark_fix.litematica_string_list_add"),
                    ignored -> addEntry(entries.size()), DEFAULT_NARRATION);
        }

        @Override public boolean isMouseOver(double x, double mouseY) {
            return inViewport(x, mouseY) && super.isMouseOver(x, mouseY);
        }

        @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            IntegrationSettingsStyle.roundedRect(graphics, getX(), getY(), getWidth(), getHeight(), 6,
                    isHoveredOrFocused() ? 0xD13D6895 : 0xB51B3B56);
            graphics.centeredText(font, getMessage(), getX() + getWidth() / 2, getY() + 7, 0xFFFFFFFF);
        }
    }

    private record Row(Entry entry, EditBox input, SettingsIconButton remove, int y) { }
}
