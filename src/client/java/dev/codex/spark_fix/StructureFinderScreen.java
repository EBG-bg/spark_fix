package dev.codex.spark_fix;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Search and continuous selection controls, usable with or without owo-lib. */
public final class StructureFinderScreen extends Screen {
    private static final int ROW_STEP = 30;
    private final Screen parent;
    private final List<TypeButton> rows = new ArrayList<>();
    private EditBox search;
    private String query = "";
    private int panelLeft;
    private int panelWidth;
    private int viewportTop;
    private int viewportBottom;
    private int maxScroll;
    private double targetScroll;
    private double scroll;
    private long lastFrame;
    private boolean draggingScroll;

    public StructureFinderScreen(Screen parent) {
        super(Component.translatable("gui.spark_fix.structure_finder.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearWidgets();
        panelWidth = Math.min(640, Math.max(1, width - 24));
        panelLeft = (width - panelWidth) / 2;
        int actionGap = 5;
        int selectAllWidth = Math.max(58,
                font.width(Component.translatable("gui.spark_fix.structure_finder.select_all")) + 16);
        int clearAllWidth = Math.max(58,
                font.width(Component.translatable("gui.spark_fix.structure_finder.deselect_all")) + 16);
        int actionRight = panelLeft + 16 + selectAllWidth + actionGap + clearAllWidth;
        boolean separateActionRow = actionRight + 8 > (width - font.width(title)) / 2;
        int controlsOffset = separateActionRow ? 24 : 0;
        int searchWidth = Math.max(60, panelWidth - 32);
        search = addRenderableWidget(new EditBox(font, panelLeft + 16, 57 + controlsOffset, searchWidth, 20,
                Component.translatable("gui.spark_fix.structure_finder.search")));
        search.setMaxLength(128);
        search.setHint(Component.translatable("gui.spark_fix.structure_finder.search"));
        search.setValue(query);
        search.setResponder(value -> {
            query = value;
            targetScroll = scroll = 0;
            rebuildRows();
        });
        int actionY = separateActionRow ? 57 : 20;
        addRenderableWidget(Button.builder(Component.translatable("gui.spark_fix.structure_finder.select_all"),
                        ignored -> selectAllTypes())
                .bounds(panelLeft + 16, actionY, selectAllWidth, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.spark_fix.structure_finder.deselect_all"),
                        ignored -> clearAllTypes())
                .bounds(panelLeft + 16 + selectAllWidth + actionGap, actionY, clearAllWidth, 20).build());
        addRenderableWidget(new SimilaritySlider(panelLeft + 16, 86 + controlsOffset, panelWidth - 32));
        viewportTop = 119 + controlsOffset;
        viewportBottom = Math.max(viewportTop + 1, height - 52);
        addRenderableWidget(Button.builder(Component.translatable("config.spark_fix.done"), ignored -> onClose())
                .bounds(panelLeft + panelWidth - 106, height - 36, 90, 22).build());
        rebuildRows();
        setFocused(search);
    }

    private void selectAllTypes() {
        Set<String> all = new LinkedHashSet<>();
        for (StructureFinderCatalog.Entry entry : StructureFinderCatalog.entries()) {
            if (entry.detectable()) all.add(entry.id());
        }
        StructureFinder.setSelectedTypes(all);
        SparkFixConfig.save();
    }

    private void clearAllTypes() {
        StructureFinder.setSelectedTypes(Set.of());
        SparkFixConfig.save();
    }

    private void rebuildRows() {
        if (getFocused() instanceof TypeButton) setFocused(search);
        for (TypeButton row : rows) removeWidget(row);
        rows.clear();
        int index = 0;
        for (StructureFinderCatalog.Entry entry : StructureFinderCatalog.entries()) {
            if (!entry.matches(query)) continue;
            TypeButton row = new TypeButton(entry, index++);
            rows.add(row);
            addWidget(row);
        }
        maxScroll = Math.max(0, rows.size() * ROW_STEP - (viewportBottom - viewportTop));
        targetScroll = Math.clamp(targetScroll, 0, maxScroll);
        scroll = Math.clamp(scroll, 0, maxScroll);
        positionRows();
    }

    private boolean inViewport(double x, double y) {
        return x >= panelLeft + 10 && x < panelLeft + panelWidth - 10
                && y >= viewportTop && y < viewportBottom;
    }

    private void positionRows() {
        for (TypeButton row : rows) row.setY(viewportTop + row.index * ROW_STEP - (int) Math.round(scroll));
    }

    private void animateScroll() {
        long now = System.nanoTime();
        double elapsed = lastFrame == 0 ? 1.0 / 60 : Math.min(0.1, (now - lastFrame) / 1_000_000_000.0);
        lastFrame = now;
        scroll += (targetScroll - scroll) * (1 - Math.exp(-elapsed * 18));
        if (Math.abs(targetScroll - scroll) < 0.1) scroll = targetScroll;
        positionRows();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        boolean handled = super.keyPressed(event);
        if (handled && getFocused() instanceof TypeButton row) {
            int top = row.index * ROW_STEP;
            int viewportHeight = viewportBottom - viewportTop;
            if (top < targetScroll) targetScroll = top;
            else if (top + row.getHeight() > targetScroll + viewportHeight) {
                targetScroll = top + row.getHeight() - viewportHeight;
            }
            targetScroll = Math.clamp(targetScroll, 0, maxScroll);
        }
        return handled;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (!inViewport(x, y)) return super.mouseScrolled(x, y, horizontal, vertical);
        targetScroll = Math.clamp(targetScroll - vertical * ROW_STEP * SparkFixConfig.scrollSensitivity(), 0, maxScroll);
        return true;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (maxScroll > 0 && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT
                && event.x() >= panelLeft + panelWidth - 13 && event.x() < panelLeft + panelWidth - 6
                && event.y() >= viewportTop && event.y() < viewportBottom) {
            draggingScroll = true;
            moveScrollbar(event.y());
            return true;
        }
        return super.mouseClicked(event, doubled);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingScroll && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            moveScrollbar(event.y());
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingScroll && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            draggingScroll = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    private int scrollThumbHeight() {
        int h = viewportBottom - viewportTop;
        return Math.max(18, (int) Math.round(h * h / (double) (h + maxScroll)));
    }

    private void moveScrollbar(double y) {
        int h = scrollThumbHeight();
        targetScroll = Math.clamp((y - viewportTop - h / 2.0)
                / Math.max(1, viewportBottom - viewportTop - h) * maxScroll, 0, maxScroll);
        scroll = targetScroll;
        positionRows();
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        animateScroll();
        IntegrationSettingsStyle.roundedRect(graphics, panelLeft, 12, panelWidth, height - 24, 12,
                IntegrationSettingsStyle.PANEL);
        graphics.centeredText(font, title, width / 2, 24, 0xFFFFFFFF);
        graphics.centeredText(font, Component.translatable("gui.spark_fix.structure_finder.subtitle"),
                width / 2, 40, IntegrationSettingsStyle.MUTED);
        if (rows.isEmpty()) graphics.centeredText(font,
                Component.translatable("gui.spark_fix.structure_finder.no_results"), width / 2, viewportTop + 14, 0xFFE0E5ED);
        Component status;
        int statusColor = 0xFFE0E5ED;
        if (StructureFinder.templateError() != null && !StructureFinder.templateError().isBlank()) {
            status = Component.translatable("gui.spark_fix.structure_finder.load_failed");
            statusColor = 0xFFFF9292;
        } else if (StructureFinder.templatesLoading()) {
            status = Component.translatable("gui.spark_fix.structure_finder.loading");
        } else if (StructureFinder.selectedTypes().isEmpty()) {
            status = Component.translatable("gui.spark_fix.structure_finder.none_selected");
        } else {
            status = Component.translatable("gui.spark_fix.structure_finder.status",
                    StructureFinder.selectedTypes().size(), StructureFinder.detections().size(),
                    StructureFinder.scannedChunks(), StructureFinder.scanTotalChunks());
        }
        graphics.textWithWordWrap(font, status, panelLeft + 16, height - 36,
                Math.max(1, panelWidth - 128), statusColor);
        if (maxScroll > 0) {
            int x = panelLeft + panelWidth - 11;
            int h = scrollThumbHeight();
            int y = viewportTop + (int) Math.round(scroll / maxScroll * (viewportBottom - viewportTop - h));
            graphics.fill(x, viewportTop, x + 3, viewportBottom, 0x30FFFFFF);
            IntegrationSettingsStyle.roundedRect(graphics, x - 1, y, 5, h, 2, 0xAFFFFFFF);
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.enableScissor(panelLeft + 10, viewportTop, panelLeft + panelWidth - 14, viewportBottom);
        for (TypeButton row : rows) {
            if (row.getBottom() > viewportTop && row.getY() < viewportBottom) {
                row.extractRenderState(graphics, mouseX, mouseY, partialTick);
            }
        }
        graphics.disableScissor();
    }

    @Override
    public void onClose() {
        SparkFixConfig.save();
        minecraft.setScreenAndShow(parent);
    }

    private final class TypeButton extends Button {
        private final StructureFinderCatalog.Entry entry;
        private final int index;

        private TypeButton(StructureFinderCatalog.Entry entry, int index) {
            super(panelLeft + 16, viewportTop + index * ROW_STEP, Math.max(1, panelWidth - 40), 26,
                    entry.name(), ignored -> {}, DEFAULT_NARRATION);
            this.entry = entry;
            this.index = index;
            Component tooltip = entry.detectable()
                    ? Component.literal(entry.enName() + " · " + entry.id())
                    : Component.translatable("gui.spark_fix.structure_finder.unreliable");
            setTooltip(Tooltip.create(tooltip));
            active = entry.detectable();
        }

        @Override
        public void onPress(net.minecraft.client.input.InputWithModifiers input) {
            Set<String> selected = new LinkedHashSet<>(StructureFinder.selectedTypes());
            if (!entry.detectable()) return;
            if (!selected.remove(entry.id())) selected.add(entry.id());
            StructureFinder.setSelectedTypes(selected);
            SparkFixConfig.save();
        }

        @Override
        public boolean isMouseOver(double x, double y) { return inViewport(x, y) && super.isMouseOver(x, y); }

        @Override
        protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            boolean selected = StructureFinder.selectedTypes().contains(entry.id());
            IntegrationSettingsStyle.roundedRect(graphics, getX(), getY(), getWidth(), getHeight(), 6,
                    isHoveredOrFocused() ? 0x80324F6B : selected ? 0x66355477 : 0x401B2D40);
            int bx = getX() + 8;
            int by = getY() + 5;
            IntegrationSettingsStyle.roundedRect(graphics, bx, by, 16, 16, 3, selected ? 0xFF87B1F9 : 0x704C6178);
            if (selected) {
                graphics.fill(bx + 4, by + 7, bx + 6, by + 10, 0xFF163456);
                graphics.fill(bx + 6, by + 9, bx + 8, by + 12, 0xFF163456);
                graphics.fill(bx + 8, by + 7, bx + 10, by + 10, 0xFF163456);
                graphics.fill(bx + 10, by + 4, bx + 12, by + 8, 0xFF163456);
            }
            int textColor = entry.detectable() ? 0xFFFFFFFF : 0xFF9AA3AD;
            graphics.text(font, font.plainSubstrByWidth(entry.name().getString(), Math.max(1, getWidth() - 38)),
                    getX() + 32, getY() + 9, textColor);
        }
    }

    private static final class SimilaritySlider extends AbstractSliderButton {
        private boolean dirty;
        private SimilaritySlider(int x, int y, int width) {
            super(x, y, Math.max(1, width), 22, Component.empty(), (SparkFixConfig.structureFinderSimilarity() - 50) / 50.0);
            setTooltip(Tooltip.create(Component.translatable("gui.spark_fix.structure_finder.similarity_hint")));
            updateMessage();
        }

        private int percentage() { return Math.clamp(50 + (int) Math.round(value * 50), 50, 100); }

        @Override protected void updateMessage() {
            setMessage(Component.translatable("gui.spark_fix.structure_finder.similarity", percentage()));
        }

        @Override protected void applyValue() {
            if (SparkFixConfig.structureFinderSimilarity() == percentage()) return;
            SparkFixConfig.setStructureFinderSimilarity(percentage());
            dirty = true;
        }

        @Override public boolean mouseReleased(MouseButtonEvent event) {
            boolean handled = super.mouseReleased(event);
            if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && dirty) saveValue();
            return handled;
        }

        @Override public boolean keyPressed(KeyEvent event) {
            if (active && isFocused() && (event.isLeft() || event.isRight())) {
                setValue(value + (event.isRight() ? 0.02 : -0.02));
                if (dirty) saveValue();
                return true;
            }
            return super.keyPressed(event);
        }

        @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { return false; }

        private void saveValue() {
            SparkFixConfig.save();
            dirty = false;
        }
    }
}
