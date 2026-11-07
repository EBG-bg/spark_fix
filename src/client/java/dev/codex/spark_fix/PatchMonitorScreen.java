package dev.codex.spark_fix;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A small live view of patch loading, rather than a switchboard for the repairs. */
final class PatchMonitorScreen extends Screen {
    private final Screen parent;
    private final List<PatchRow> rows = new ArrayList<>();
    private List<PatchMonitor.Report> reports = List.of();
    private String query = "";
    private int panelLeft, panelWidth, viewportTop, viewportBottom, maxScroll, refreshTicks;
    private double scroll, targetScroll;
    private long lastFrame;
    private boolean draggingScroll;
    private EditBox search;

    PatchMonitorScreen(Screen parent) {
        super(Component.translatable("gui.spark_fix.patch_monitor.title"));
        this.parent = parent;
    }

    @Override protected void init() {
        clearWidgets();
        rows.clear();
        draggingScroll = false;
        panelWidth = Math.min(680, Math.max(1, width - 20));
        panelLeft = (width - panelWidth) / 2;
        int contentWidth = Math.max(1, panelWidth - 28);
        int searchY = Math.max(59, 36 + font.split(Component.translatable("gui.spark_fix.patch_monitor.subtitle"), contentWidth).size() * font.lineHeight + 7);
        int refreshWidth = Math.min(80, Math.max(45, contentWidth / 4));
        search = addRenderableWidget(new EditBox(font, panelLeft + 14, searchY,
                Math.max(1, contentWidth - refreshWidth - 6), 20,
                Component.translatable("gui.spark_fix.patch_monitor.search")));
        search.setMaxLength(128);
        search.setHint(Component.translatable("gui.spark_fix.patch_monitor.search"));
        search.setValue(query);
        search.setResponder(value -> { query = value; targetScroll = scroll = 0; rebuildRows(); });
        addRenderableWidget(Button.builder(Component.translatable("gui.spark_fix.patch_monitor.refresh"),
                ignored -> refresh()).bounds(panelLeft + 14 + contentWidth - refreshWidth, searchY, refreshWidth, 20).build());
        viewportTop = searchY + 27;
        viewportBottom = Math.max(viewportTop + 1, height - 44);
        addRenderableWidget(Button.builder(Component.translatable("config.spark_fix.done"), ignored -> onClose())
                .bounds(width / 2 - 70, height - 32, 140, 20).build());
        refresh();
        setInitialFocus(search);
    }

    private void refresh() { reports = PatchMonitor.snapshot(); rebuildRows(); }

    @Override public void tick() {
        super.tick();
        if (++refreshTicks >= 20) {
            refreshTicks = 0;
            List<PatchMonitor.Report> latest = PatchMonitor.snapshot();
            if (!latest.equals(reports)) { reports = latest; rebuildRows(); }
        }
    }

    private void rebuildRows() {
        String focusedId = getFocused() instanceof PatchRow row ? row.report.patch().id() : null;
        for (PatchRow row : rows) removeWidget(row);
        rows.clear();
        String[] terms = query.strip().toLowerCase(Locale.ROOT).split("\\s+");
        int offset = 0;
        for (PatchMonitor.Report report : reports) {
            String text = (Component.translatable(report.patch().titleKey()).getString() + " "
                    + Component.translatable(report.patch().descriptionKey()).getString() + " "
                    + Component.translatable(report.state().key()).getString() + " " + report.target()).toLowerCase(Locale.ROOT);
            boolean matches = true;
            for (String term : terms) if (!text.contains(term)) { matches = false; break; }
            if (!matches) continue;
            PatchRow row = new PatchRow(report, offset);
            rows.add(row);
            addWidget(row);
            if (report.patch().id().equals(focusedId)) setFocused(row);
            offset += row.getHeight() + 6;
        }
        if (focusedId != null && rows.stream().noneMatch(row -> row.report.patch().id().equals(focusedId))) setFocused(search);
        maxScroll = Math.max(0, offset - 6 - (viewportBottom - viewportTop));
        targetScroll = Math.clamp(targetScroll, 0, maxScroll);
        scroll = Math.clamp(scroll, 0, maxScroll);
        positionRows();
    }

    private boolean inViewport(double x, double y) {
        return x >= panelLeft + 10 && x < panelLeft + panelWidth - 10 && y >= viewportTop && y < viewportBottom;
    }
    private void positionRows() {
        for (PatchRow row : rows) row.setY(viewportTop + row.offset - (int) Math.round(scroll));
    }
    private void animateScroll() {
        long now = System.nanoTime();
        double elapsed = lastFrame == 0 ? 1.0 / 60 : Math.min(0.1, (now - lastFrame) / 1_000_000_000.0);
        lastFrame = now;
        scroll += (targetScroll - scroll) * (1 - Math.exp(-elapsed * 18));
        if (Math.abs(targetScroll - scroll) < 0.1) scroll = targetScroll;
        positionRows();
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (!inViewport(x, y)) return super.mouseScrolled(x, y, horizontal, vertical);
        targetScroll = Math.clamp(targetScroll - vertical * 30 * SparkFixConfig.scrollSensitivity(), 0, maxScroll);
        return true;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        boolean handled = super.keyPressed(event);
        if (handled && getFocused() instanceof PatchRow row) {
            int viewport = viewportBottom - viewportTop;
            if (row.offset < targetScroll) targetScroll = row.offset;
            else if (row.offset + row.getHeight() > targetScroll + viewport) targetScroll = row.offset + row.getHeight() - viewport;
            targetScroll = Math.clamp(targetScroll, 0, maxScroll);
        }
        return handled;
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (maxScroll > 0 && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT
                && event.x() >= panelLeft + panelWidth - 13 && event.x() < panelLeft + panelWidth - 5
                && event.y() >= viewportTop && event.y() < viewportBottom) {
            draggingScroll = true;
            moveScrollbar(event.y());
            return true;
        }
        return super.mouseClicked(event, doubled);
    }
    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (draggingScroll && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) { moveScrollbar(event.y()); return true; }
        return super.mouseDragged(event, dx, dy);
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingScroll && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) { draggingScroll = false; return true; }
        return super.mouseReleased(event);
    }
    private int thumbHeight() {
        int viewport = viewportBottom - viewportTop;
        return Math.min(viewport, Math.max(18, (int) Math.round(viewport * (double) viewport / (viewport + maxScroll))));
    }
    private void moveScrollbar(double y) {
        int thumb = thumbHeight();
        targetScroll = Math.clamp((y - viewportTop - thumb / 2.0) / Math.max(1, viewportBottom - viewportTop - thumb) * maxScroll, 0, maxScroll);
        scroll = targetScroll;
        positionRows();
    }
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        animateScroll();
        IntegrationSettingsStyle.roundedRect(graphics, panelLeft, 10, panelWidth, height - 20, 12, IntegrationSettingsStyle.PANEL);
        graphics.centeredText(font, title, width / 2, 20, 0xFFFFFFFF);
        graphics.textWithWordWrap(font, Component.translatable("gui.spark_fix.patch_monitor.subtitle"),
                panelLeft + 14, 36, Math.max(1, panelWidth - 28), IntegrationSettingsStyle.MUTED);
        if (rows.isEmpty()) graphics.centeredText(font, Component.translatable("gui.spark_fix.patch_monitor.empty"), width / 2, viewportTop + 16, IntegrationSettingsStyle.MUTED);
    }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.enableScissor(panelLeft + 10, viewportTop, panelLeft + panelWidth - 14, viewportBottom);
        for (PatchRow row : rows) if (row.getBottom() > viewportTop && row.getY() < viewportBottom) row.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.disableScissor();
        if (maxScroll > 0) {
            int x = panelLeft + panelWidth - 10, thumb = thumbHeight();
            int y = viewportTop + (int) Math.round(scroll / maxScroll * (viewportBottom - viewportTop - thumb));
            graphics.fill(x, viewportTop, x + 2, viewportBottom, 0x30FFFFFF);
            IntegrationSettingsStyle.roundedRect(graphics, x - 1, y, 4, thumb, 4, 0xBFFFFFFF);
        }
    }
    @Override public void onClose() { minecraft.setScreenAndShow(parent); }

    private final class PatchRow extends Button {
        private final PatchMonitor.Report report;
        private final int offset;
        private final List<net.minecraft.util.FormattedCharSequence> heading, description, detail;

        PatchRow(PatchMonitor.Report report, int offset) {
            super(panelLeft + 14, 0, Math.max(1, panelWidth - 38), 20,
                    Component.translatable(report.patch().titleKey()).append(" · ").append(Component.translatable(report.state().key())), ignored -> { }, DEFAULT_NARRATION);
            this.report = report;
            this.offset = offset;
            int textWidth = Math.max(1, getWidth() - 18);
            heading = font.split(getMessage(), textWidth);
            description = font.split(Component.translatable(report.patch().descriptionKey()), textWidth);
            var details = Component.literal(report.target());
            if (report.expected() > 0) details.append(" · ").append(Component.translatable("gui.spark_fix.patch_monitor.hooks", report.found(), report.expected()));
            detail = font.split(details, textWidth);
            setHeight(16 + (heading.size() + description.size() + detail.size()) * font.lineHeight + 5);
            setTooltip(Tooltip.create(Component.translatable("gui.spark_fix.patch_monitor.detail." + report.state().name().toLowerCase(Locale.ROOT))));
        }
        @Override public boolean isMouseOver(double x, double y) { return inViewport(x, y) && super.isMouseOver(x, y); }
        @Override protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            IntegrationSettingsStyle.roundedRect(graphics, getX(), getY(), getWidth(), getHeight(), 8,
                    isHoveredOrFocused() ? 0x70324F6B : 0x401B2D40);
            int y = getY() + 8;
            for (var line : heading) { graphics.text(font, line, getX() + 9, y, report.state().color); y += font.lineHeight; }
            y += 3;
            for (var line : description) { graphics.text(font, line, getX() + 9, y, 0xFFE8EDF5); y += font.lineHeight; }
            y += 2;
            for (var line : detail) { graphics.text(font, line, getX() + 9, y, 0xFFB4C4DB); y += font.lineHeight; }
        }
    }
}
