package dev.codex.spark_fix;

import cn.reibridge.recipe.RecipeCacheMaintenance;
import cn.reibridge.recipe.RecipeCaptureStore;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Cache inspection is read-only until Clean is clicked; all disk work is queued with the writer. */
final class ReiCacheMaintenanceScreen extends Screen {
    private enum Task { INSPECT, CLEAN }
    private record TextLine(FormattedCharSequence text, int color) { }

    private final Screen parent;
    private final Path directory;
    private RecipeCacheMaintenance.Stats stats;
    private CompletableFuture<?> pending;
    private Task task;
    private Component notice;
    private Component error;
    private List<String> cleanupProblems = List.of();
    private final List<TextLine> lines = new ArrayList<>();
    private boolean requested;
    private int panelLeft, panelWidth, viewportTop, viewportBottom, maxScroll;
    private double scroll, targetScroll;
    private long lastFrame;
    private boolean draggingScroll;
    private Button inspectButton, cleanButton;

    ReiCacheMaintenanceScreen(Screen parent) {
        this(parent, FabricLoader.getInstance().getConfigDir().resolve("rei_recipe_bridge").resolve("recipes"));
    }

    ReiCacheMaintenanceScreen(Screen parent, Path directory) {
        super(Component.translatable("rei_recipe_bridge.cache_maintenance.title"));
        this.parent = parent;
        this.directory = directory;
    }

    @Override protected void init() {
        clearWidgets();
        draggingScroll = false;
        panelWidth = Math.min(620, Math.max(1, width - 24));
        panelLeft = (width - panelWidth) / 2;
        int inner = Math.max(1, panelWidth - 28);
        int buttonWidth = Math.max(1, (inner - 12) / 3);
        inspectButton = addRenderableWidget(Button.builder(Component.translatable("rei_recipe_bridge.cache_maintenance.inspect"),
                ignored -> inspect()).bounds(panelLeft + 14, height - 36, buttonWidth, 22).build());
        cleanButton = addRenderableWidget(Button.builder(Component.translatable("rei_recipe_bridge.cache_maintenance.clean"),
                ignored -> clean()).bounds(panelLeft + 20 + buttonWidth, height - 36, buttonWidth, 22).build());
        cleanButton.setTooltip(Tooltip.create(Component.translatable("rei_recipe_bridge.cache_maintenance.clean_hint")));
        addRenderableWidget(Button.builder(Component.translatable("config.spark_fix.done"), ignored -> onClose())
                .bounds(panelLeft + 26 + 2 * buttonWidth, height - 36, buttonWidth, 22).build());
        viewportTop = 44;
        viewportBottom = Math.max(viewportTop + 1, height - 46);
        if (!requested) inspect();
        else rebuildLines();
        updateButtons();
    }

    private void inspect() {
        if (pending != null) return;
        requested = true;
        task = Task.INSPECT;
        error = null;
        Path cacheDirectory = directory;
        pending = RecipeCaptureStore.maintenance(() -> RecipeCacheMaintenance.inspect(cacheDirectory));
        rebuildLines();
        updateButtons();
    }

    private void clean() {
        if (pending != null || stats == null || stats.reclaimableFiles() == 0) return;
        notice = null;
        error = null;
        cleanupProblems = List.of();
        task = Task.CLEAN;
        Path cacheDirectory = directory;
        pending = RecipeCaptureStore.maintenance(() -> RecipeCacheMaintenance.clean(cacheDirectory));
        rebuildLines();
        updateButtons();
    }

    @Override public void tick() {
        super.tick();
        if (pending == null || !pending.isDone()) return;
        CompletableFuture<?> finished = pending;
        Task completed = task;
        pending = null;
        try {
            Object result = finished.join();
            if (completed == Task.INSPECT) stats = (RecipeCacheMaintenance.Stats) result;
            else {
                var cleaned = (RecipeCacheMaintenance.Result) result;
                notice = Component.translatable("rei_recipe_bridge.cache_maintenance.cleaned",
                        cleaned.deletedFiles(), formatBytes(cleaned.reclaimedBytes()));
                cleanupProblems = List.copyOf(cleaned.problems());
                inspect();
            }
        } catch (CompletionException | java.util.concurrent.CancellationException exception) {
            Throwable cause = exception.getCause() == null ? exception : exception.getCause();
            SparkFixClient.LOGGER.warn("REI recipe cache maintenance failed: {}", completed, cause);
            error = Component.translatable("rei_recipe_bridge.cache_maintenance.failed");
        }
        rebuildLines();
        updateButtons();
    }

    private void updateButtons() {
        if (inspectButton != null) inspectButton.active = pending == null;
        if (cleanButton != null) cleanButton.active = pending == null && stats != null && stats.reclaimableFiles() > 0;
    }

    private void append(Component message, int color) {
        for (var line : font.split(message, Math.max(1, panelWidth - 38))) lines.add(new TextLine(line, color));
        lines.add(new TextLine(FormattedCharSequence.EMPTY, color));
    }

    private void rebuildLines() {
        lines.clear();
        append(Component.translatable("rei_recipe_bridge.cache_maintenance.description"), IntegrationSettingsStyle.MUTED);
        if (stats != null) {
            append(Component.translatable("rei_recipe_bridge.cache_maintenance.total", formatBytes(stats.totalBytes())), 0xFFFFFFFF);
            append(Component.translatable("rei_recipe_bridge.cache_maintenance.reclaimable",
                    formatBytes(stats.reclaimableBytes()), stats.reclaimableFiles()), 0xFFAFCEF9);
            if (stats.skippedServers() > 0) append(Component.translatable("rei_recipe_bridge.cache_maintenance.skipped", stats.skippedServers()), 0xFFFFD782);
            if (stats.reclaimableFiles() == 0 && pending == null) append(Component.translatable("rei_recipe_bridge.cache_maintenance.nothing"), 0xFF83DFAC);
        }
        if (notice != null) append(notice, 0xFF83DFAC);
        if (pending != null) append(Component.translatable(task == Task.CLEAN
                ? "rei_recipe_bridge.cache_maintenance.cleaning" : "rei_recipe_bridge.cache_maintenance.inspecting"), 0xFFAFCEF9);
        if (error != null) append(error, 0xFFFF9999);
        List<String> problems = new ArrayList<>(cleanupProblems);
        if (stats != null) for (String problem : stats.problems()) if (!problems.contains(problem)) problems.add(problem);
        for (String problem : problems) append(Component.literal(problem), 0xFFFFD782);
        maxScroll = Math.max(0, lines.size() * font.lineHeight - (viewportBottom - viewportTop));
        targetScroll = Math.clamp(targetScroll, 0, maxScroll);
        scroll = Math.clamp(scroll, 0, maxScroll);
    }

    static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double amount = bytes;
        String[] units = {"KiB", "MiB", "GiB", "TiB"};
        int unit = -1;
        do { amount /= 1024; unit++; } while (amount >= 1024 && unit < units.length - 1);
        return String.format(Locale.ROOT, "%.1f %s", amount, units[unit]);
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (y < viewportTop || y >= viewportBottom) return super.mouseScrolled(x, y, horizontal, vertical);
        targetScroll = Math.clamp(targetScroll - vertical * 30 * SparkFixConfig.scrollSensitivity(), 0, maxScroll);
        return true;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) return super.keyPressed(event);
        if (event.key() == GLFW.GLFW_KEY_PAGE_DOWN || event.key() == GLFW.GLFW_KEY_PAGE_UP) {
            targetScroll = Math.clamp(targetScroll + (event.key() == GLFW.GLFW_KEY_PAGE_DOWN ? 1 : -1)
                    * (viewportBottom - viewportTop), 0, maxScroll);
            return true;
        }
        return super.keyPressed(event);
    }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
        if (maxScroll > 0 && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT
                && event.x() >= panelLeft + panelWidth - 13 && event.x() < panelLeft + panelWidth - 5
                && event.y() >= viewportTop && event.y() < viewportBottom) {
            draggingScroll = true; moveScrollbar(event.y()); return true;
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
    }
    @Override public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        long now = System.nanoTime();
        double elapsed = lastFrame == 0 ? 1.0 / 60 : Math.min(0.1, (now - lastFrame) / 1_000_000_000.0);
        lastFrame = now;
        scroll += (targetScroll - scroll) * (1 - Math.exp(-elapsed * 18));
        if (Math.abs(targetScroll - scroll) < 0.1) scroll = targetScroll;
        IntegrationSettingsStyle.roundedRect(graphics, panelLeft, 10, panelWidth, height - 20, 12, IntegrationSettingsStyle.PANEL);
        graphics.centeredText(font, title, width / 2, 22, 0xFFFFFFFF);
        graphics.enableScissor(panelLeft + 12, viewportTop, panelLeft + panelWidth - 14, viewportBottom);
        int y = viewportTop - (int) Math.round(scroll);
        for (TextLine line : lines) {
            if (y + font.lineHeight > viewportTop && y < viewportBottom) graphics.text(font, line.text(), panelLeft + 14, y, line.color());
            y += font.lineHeight;
        }
        graphics.disableScissor();
        if (maxScroll > 0) {
            int x = panelLeft + panelWidth - 10, thumb = thumbHeight();
            int thumbY = viewportTop + (int) Math.round(scroll / maxScroll * (viewportBottom - viewportTop - thumb));
            graphics.fill(x, viewportTop, x + 2, viewportBottom, 0x30FFFFFF);
            IntegrationSettingsStyle.roundedRect(graphics, x - 1, thumbY, 4, thumb, 4, 0xBFFFFFFF);
        }
    }
    @Override public void onClose() { minecraft.setScreenAndShow(parent); }
}
