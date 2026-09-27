package dev.codex.spark_fix;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** A primary-name field and free-standing alias tags sharing an unsaved draft. */
final class LitematicaAliasEditorScreen extends Screen {
    private static final int PANEL = 0x4887B1F9;
    private static final int LABEL = 0xCEB7D2FB;
    private static final int MAIN_TAG = 0xE6A7C8FA;
    private static final int ALIAS_TAG = 0xDED3E4FC;
    private static final int ROW_HEIGHT = 24;
    private static final Object MAIN_ENTRY = new Object();
    private static final Identifier FIELD = Identifier.withDefaultNamespace("widget/text_field");

    private final Screen parent;
    private final Consumer<Names> committed;
    private final LitematicaNameDraft draft;
    private final List<AbstractWidget> formWidgets = new ArrayList<>();
    private final List<Chip> chips = new ArrayList<>();
    private final Map<EditBox, Object> inputTags = new IdentityHashMap<>();
    private final Map<LitematicaNameDraft.Tag, SettingsIconButton> downButtons = new IdentityHashMap<>();
    private EditBox mainEntry;
    private String pendingMainText = "";
    private Object pendingFocus;
    private boolean layoutDirty;
    private int panelLeft;
    private int panelWidth;
    private int viewportTop;
    private int viewportBottom;
    private int scroll;
    private int maxScroll;
    private Box mainBox;
    private Box aliasBox;
    private int mainLabelY;
    private int aliasLabelY;
    private long openedAt;

    LitematicaAliasEditorScreen(Screen parent, VanillaLitematicaSettingsScreen.Option option,
                               List<String> names, List<String> aliases, Consumer<Names> committed) {
        super(Component.translatable("config.spark_fix.litematica_alias_title"));
        this.parent = parent;
        this.committed = committed;
        this.draft = new LitematicaNameDraft(option.name(), names, aliases);
    }

    @Override
    protected void init() {
        if (openedAt == 0L) openedAt = System.nanoTime();
        layoutWidgets();
    }

    private void layoutWidgets() {
        Object focusTarget = pendingFocus != null ? pendingFocus : inputTags.get(getFocused());
        Map<Object, EditBox> previousInputs = new IdentityHashMap<>();
        inputTags.forEach((input, tag) -> previousInputs.put(tag, input));
        pendingFocus = null;
        clearWidgets();
        formWidgets.clear();
        inputTags.clear();
        downButtons.clear();
        chips.clear();
        layoutDirty = false;

        panelWidth = Math.min(900, Math.max(1, width - 24));
        panelLeft = (width - panelWidth) / 2;
        viewportTop = 48;
        viewportBottom = Math.max(viewportTop + 1, height - 48);
        int boxX = panelLeft + 12;
        int boxWidth = Math.max(1, panelWidth - 54);

        mainLabelY = viewportTop;
        mainBox = layoutTags(true, boxX, mainLabelY + 24, boxWidth, previousInputs);
        aliasLabelY = mainBox.y + mainBox.height + 14;
        aliasBox = layoutTags(false, boxX, aliasLabelY + 24, Math.max(1, panelWidth - 24), previousInputs);
        maxScroll = Math.max(0, aliasBox.y + aliasBox.height + 8 - viewportBottom);
        scroll = Math.clamp(scroll, 0, maxScroll);

        EditBox focus = null;
        for (var entry : inputTags.entrySet()) {
            if (entry.getValue() == focusTarget) {
                focus = entry.getKey();
                break;
            }
        }
        if (focus != null) {
            if (focus.getY() - scroll < viewportTop + 4) scroll = focus.getY() - viewportTop - 4;
            if (focus.getBottom() - scroll > viewportBottom - 4) scroll = focus.getBottom() - viewportBottom + 4;
            scroll = Math.clamp(scroll, 0, maxScroll);
        }
        for (AbstractWidget widget : formWidgets) widget.setY(widget.getY() - scroll);
        updateMoveButtons();

        int buttonWidth = Math.max(1, Math.min(96, (panelWidth - 36) / 2));
        int footerY = height - 36;
        addRenderableWidget(Button.builder(Component.translatable("config.spark_fix.cancel"), ignored -> onClose())
                .bounds(panelLeft + panelWidth - 2 * buttonWidth - 22, footerY, buttonWidth, 22).build());
        addRenderableWidget(Button.builder(Component.translatable("config.spark_fix.done"), ignored -> commitAndClose())
                .bounds(panelLeft + panelWidth - buttonWidth - 12, footerY, buttonWidth, 22).build());
        if (focus != null) {
            if (getFocused() != focus) setFocused(focus);
        } else if (!children().contains(getFocused())) setFocused(null);
    }

    private Box layoutTags(boolean main, int x, int y, int boxWidth, Map<Object, EditBox> previousInputs) {
        int inset = main ? Math.min(8, Math.max(1, boxWidth / 8)) : 0;
        int innerWidth = Math.max(1, boxWidth - inset * 2);
        int cursorX = x + inset;
        int cursorY = y + (main ? 8 : 0);
        int right = x + boxWidth - inset;
        List<LitematicaNameDraft.Tag> tags = main ? draft.main : draft.aliases;
        for (LitematicaNameDraft.Tag tag : tags) {
            int chipWidth = Math.min(innerWidth, Math.min(280, Math.max(90, font.width(tag.text) + 36)));
            if (cursorX > x + inset && cursorX + chipWidth > right) {
                cursorX = x + inset;
                cursorY += ROW_HEIGHT + 6;
            }
            addTagInput(tag, main, cursorX, cursorY, chipWidth, previousInputs.get(tag));
            cursorX += chipWidth + 6;
        }
        if (main) {
            int entryWidth = Math.min(innerWidth, 156);
            if (cursorX > x + inset && cursorX + Math.min(entryWidth, 100) > right) {
                cursorX = x + inset;
                cursorY += ROW_HEIGHT + 6;
            }
            mainEntry = input(cursorX + 3, cursorY + 7, Math.max(1, right - cursorX - 6),
                    pendingMainText, Component.translatable("config.spark_fix.litematica_name_new"),
                    previousInputs.get(MAIN_ENTRY));
            mainEntry.setHint(Component.translatable("config.spark_fix.litematica_name_new"));
            mainEntry.setResponder(value -> pendingMainText = value);
            inputTags.put(mainEntry, MAIN_ENTRY);
        }
        int addX = x + boxWidth + 6;
        int addY = y + 8;
        if (!main) {
            // Keep the alias action beside the tags, including when the list is empty.
            if (cursorX + 18 > right) {
                cursorX = x;
                cursorY += ROW_HEIGHT + 6;
            }
            addX = cursorX;
            addY = cursorY + 3;
        }
        int boxHeight = Math.max(main ? 42 : ROW_HEIGHT, cursorY + ROW_HEIGHT + (main ? 8 : 0) - y);
        icon(addX, addY, SettingsIconButton.Icon.ADD,
                Component.translatable(main ? "config.spark_fix.litematica_name_add"
                        : "config.spark_fix.litematica_alias_add"),
                () -> {
                    if (main && !pendingMainText.isBlank()) commitMainEntry();
                    else addTag(main);
                });
        return new Box(x, y, boxWidth, boxHeight);
    }

    private void addTagInput(LitematicaNameDraft.Tag tag, boolean main, int x, int y, int tagWidth, EditBox previous) {
        chips.add(new Chip(tag, main, x, y, tagWidth));
        EditBox input = input(x + 6, y + 7, Math.max(1, tagWidth - 28), tag.text,
                Component.translatable(main ? "config.spark_fix.litematica_alias_main"
                        : "config.spark_fix.litematica_alias_aliases"), previous);
        input.setTextColor(0xFF233249);
        input.setHint(Component.translatable("config.spark_fix.litematica_tag_empty"));
        input.setResponder(value -> {
            tag.text = value;
            layoutDirty = true;
        });
        inputTags.put(input, tag);
        SettingsIconButton button = icon(x + tagWidth - 20, y + 3,
                main ? SettingsIconButton.Icon.DOWN : SettingsIconButton.Icon.REMOVE,
                Component.translatable(main ? "config.spark_fix.litematica_name_move"
                        : "config.spark_fix.litematica_alias_remove"),
                () -> {
                    if (main) moveMainToAlias(tag);
                    else removeAlias(tag);
                });
        if (main) downButtons.put(tag, button);
    }

    private EditBox input(int x, int y, int inputWidth, String value, Component label, EditBox previous) {
        EditBox input = previous;
        if (input == null) {
            input = new EditBox(font, x, y, inputWidth, Math.max(10, font.lineHeight + 1), label) {
                @Override public boolean isMouseOver(double mouseX, double mouseY) {
                    return inViewport(mouseX, mouseY) && super.isMouseOver(mouseX, mouseY);
                }
            };
            input.setMaxLength(128);
            input.setBordered(false);
            input.setTextShadow(false);
            input.setValue(value);
        } else {
            // Retain the native selection, clipboard handling and IME state while tags reflow.
            input.setPosition(x, y);
            input.setWidth(inputWidth);
            input.setMessage(label);
            if (!input.getValue().equals(value)) input.setValue(value);
            input.setCursorPosition(input.getCursorPosition());
        }
        formWidgets.add(input);
        addWidget(input);
        return input;
    }

    private SettingsIconButton icon(int x, int y, SettingsIconButton.Icon icon, Component label, Runnable action) {
        SettingsIconButton button = new SettingsIconButton(x, y, 18, icon, label, action) {
            @Override public boolean isMouseOver(double mouseX, double mouseY) {
                return inViewport(mouseX, mouseY) && super.isMouseOver(mouseX, mouseY);
            }
        };
        formWidgets.add(button);
        addWidget(button);
        return button;
    }

    private void addTag(boolean main) {
        pendingFocus = draft.add(main);
        layoutDirty = true;
    }

    private void moveMainToAlias(LitematicaNameDraft.Tag tag) {
        if (draft.moveToAliases(tag)) {
            pendingFocus = tag;
            layoutDirty = true;
        }
    }

    private void removeAlias(LitematicaNameDraft.Tag tag) {
        if (draft.aliases.remove(tag)) layoutDirty = true;
    }

    private void commitMainEntry() {
        String value = pendingMainText.trim();
        if (value.isEmpty()) return;
        draft.add(true).text = value;
        pendingMainText = "";
        pendingFocus = MAIN_ENTRY;
        layoutDirty = true;
    }

    private void updateMoveButtons() {
        downButtons.forEach((tag, button) -> {
            button.active = draft.canMove(tag);
            button.visible = button.active;
        });
    }

    private void commitAndClose() {
        if (!pendingMainText.isBlank()) commitMainEntry();
        committed.accept(new Names(draft.mainNames(), draft.searchAliases()));
        minecraft.setScreenAndShow(parent);
    }

    @Override public void onClose() { minecraft.setScreenAndShow(parent); }

    @Override
    public void tick() {
        super.tick();
        if (layoutDirty) layoutWidgets();
        updateMoveButtons();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (layoutDirty) layoutWidgets();
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            if (getFocused() == mainEntry) {
                commitMainEntry();
                return true;
            }
            if (getFocused() instanceof EditBox input && inputTags.containsKey(input)) {
                pendingFocus = inputTags.get(input);
                layoutDirty = true;
                return true;
            }
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (layoutDirty) layoutWidgets();
        return super.charTyped(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (layoutDirty) layoutWidgets();
        if (super.mouseClicked(click, doubled)) return true;
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT || !inViewport(click.x(), click.y())) return false;
        for (Chip chip : chips) {
            if (click.x() < chip.x || click.x() >= chip.x + chip.width
                    || click.y() < chip.y - scroll || click.y() >= chip.y - scroll + ROW_HEIGHT) continue;
            for (var entry : inputTags.entrySet()) {
                if (entry.getValue() != chip.tag) continue;
                // The entire tag is clickable, not only its short text baseline.
                setFocused(entry.getKey());
                entry.getKey().onClick(click, doubled);
                setDragging(true);
                return true;
            }
        }
        if (click.x() >= mainBox.x && click.x() < mainBox.x + mainBox.width
                && click.y() >= mainBox.y - scroll && click.y() < mainBox.y - scroll + mainBox.height) {
            setFocused(mainEntry);
            mainEntry.moveCursorToEnd(false);
            return true;
        }
        return false;
    }

    private boolean inViewport(double x, double y) {
        return x >= panelLeft + 8 && x < panelLeft + panelWidth - 8
                && y >= viewportTop && y < viewportBottom;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (!inViewport(mouseX, mouseY)) return false;
        int next = Math.clamp(scroll - (int) Math.round(vertical * 24), 0, maxScroll);
        if (next != scroll) {
            int delta = scroll - next;
            scroll = next;
            for (AbstractWidget widget : formWidgets) widget.setY(widget.getY() + delta);
        }
        return true;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        if (layoutDirty) layoutWidgets();
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        float progress = Math.clamp((System.nanoTime() - openedAt) / 180_000_000f, 0f, 1f);
        float eased = progress * progress * (3f - 2f * progress);
        int panelColor = (Math.round((PANEL >>> 24) * eased) << 24) | (PANEL & 0xFFFFFF);
        IntegrationSettingsStyle.roundedRect(graphics, panelLeft, 12, panelWidth, height - 24, 12, panelColor);
        graphics.centeredText(font, title, width / 2, 23, 0xFFFFFFFF);
        graphics.enableScissor(panelLeft + 8, viewportTop, panelLeft + panelWidth - 8, viewportBottom);
        label(graphics, mainBox.x, mainLabelY - scroll, mainBox.width,
                Component.translatable("config.spark_fix.litematica_alias_main"));
        label(graphics, aliasBox.x, aliasLabelY - scroll, aliasBox.width,
                Component.translatable("config.spark_fix.litematica_alias_aliases"));
        // Only primary names use an enclosing field; aliases sit directly on the panel.
        graphics.blitSprite(RenderPipelines.GUI_TEXTURED, FIELD,
                mainBox.x, mainBox.y - scroll, mainBox.width, mainBox.height);
        for (Chip chip : chips) {
            IntegrationSettingsStyle.roundedRect(graphics, chip.x, chip.y - scroll, chip.width,
                    ROW_HEIGHT, 6, chip.main ? MAIN_TAG : ALIAS_TAG);
        }
        graphics.disableScissor();
    }

    private void label(GuiGraphicsExtractor graphics, int x, int y, int availableWidth, Component text) {
        int labelWidth = Math.min(availableWidth, font.width(text) + 18);
        IntegrationSettingsStyle.roundedRect(graphics, x, y, labelWidth, 19, 6, LABEL);
        graphics.text(font, text, x + 8, y + 5, 0xFF233249, false);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.enableScissor(panelLeft + 8, viewportTop, panelLeft + panelWidth - 8, viewportBottom);
        for (AbstractWidget widget : formWidgets) {
            if (widget.getBottom() > viewportTop && widget.getY() < viewportBottom) {
                widget.extractRenderState(graphics, mouseX, mouseY, partialTick);
            }
        }
        graphics.disableScissor();
    }

    record Names(List<String> mainNames, List<String> aliases) { }
    private record Box(int x, int y, int width, int height) { }
    private record Chip(LitematicaNameDraft.Tag tag, boolean main, int x, int y, int width) { }
}
