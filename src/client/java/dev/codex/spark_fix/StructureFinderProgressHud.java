package dev.codex.spark_fix;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/** Local scan progress, independent of chat, action-bar messages and server boss bars. */
final class StructureFinderProgressHud {
    private static final Animation ANIMATION = new Animation();
    private static final int BAR_HEIGHT = 4;
    private static final int EDGE_MARGIN = 8;
    private static boolean registered;
    private static StructureFinder.ScanStage labelStage;
    private static int labelCompleted, labelTotal, labelPercent, labelWidth;
    private static Font labelFont;
    private static List<FormattedCharSequence> labelLines = List.of();

    private StructureFinderProgressHud() {}

    static void register() {
        if (registered) return;
        registered = true;
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("spark_fix", "structure_scan_progress"),
                (graphics, delta) -> render(graphics));
    }

    private static void render(GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        long now = System.nanoTime();
        StructureFinder.ScanProgress progress = StructureFinder.scanProgress();
        Frame frame = ANIMATION.update(progress, now);
        if (client.level == null || client.player == null
                || client.gui.hud.isHidden() || frame.opacity < 0.02f) return;
        int hotbarRight = graphics.guiWidth() / 2 + 91;
        int cornerWidth = graphics.guiWidth() - hotbarRight - EDGE_MARGIN * 2;
        boolean fitsCorner = cornerWidth >= 72;
        int width = Math.min(190, fitsCorner ? cornerWidth : graphics.guiWidth() - EDGE_MARGIN * 2);
        if (width <= 0) return;
        int right = graphics.guiWidth() - EDGE_MARGIN;
        int left = right - width;
        int percent = progress.stage() == StructureFinder.ScanStage.COMPLETE ? 100
                : Math.min(99, (int) (progress.fraction() * 100));
        if (labelStage != progress.stage() || labelCompleted != progress.completed()
                || labelTotal != progress.total() || labelPercent != percent || labelWidth != width
                || labelFont != client.font) {
            Component label = switch (progress.stage()) {
                case LOADING -> Component.translatable("hud.spark_fix.structure_finder.loading");
                case PREPARING -> Component.translatable("hud.spark_fix.structure_finder.preparing");
                case COMPLETE -> Component.translatable("hud.spark_fix.structure_finder.complete",
                        progress.total(), progress.total());
                case FAILED -> Component.translatable("gui.spark_fix.structure_finder.load_failed");
                default -> Component.translatable("hud.spark_fix.structure_finder.progress",
                        progress.completed(), progress.total(), percent);
            };
            labelLines = client.font.split(label, width);
            labelStage = progress.stage();
            labelCompleted = progress.completed();
            labelTotal = progress.total();
            labelPercent = percent;
            labelWidth = width;
            labelFont = client.font;
        }
        // Wrap the label within the bottom-right space beside the centered hotbar.
        int bottomMargin = fitsCorner ? EDGE_MARGIN : 58;
        int textHeight = labelLines.size() * client.font.lineHeight;
        int top = Math.max(textHeight + 4, graphics.guiHeight() - bottomMargin - BAR_HEIGHT);
        top = Math.min(top, Math.max(0, graphics.guiHeight() - EDGE_MARGIN - BAR_HEIGHT));
        int textY = Math.max(0, top - textHeight - 4);
        for (var line : labelLines) {
            graphics.text(client.font, line, right - client.font.width(line),
                    textY, fade(0xFFFFFFFF, frame.opacity), true);
            textY += client.font.lineHeight;
        }
        IntegrationSettingsStyle.roundedRect(graphics, left, top, width, BAR_HEIGHT, 2,
                fade(0x90465363, frame.opacity));
        int color = progress.stage() == StructureFinder.ScanStage.COMPLETE ? 0xFF72D4A0
                : progress.stage() == StructureFinder.ScanStage.FAILED ? 0xFFFF9292 : 0xFF87B1F9;
        if (progress.stage() == StructureFinder.ScanStage.LOADING
                || progress.stage() == StructureFinder.ScanStage.PREPARING) {
            int segment = Math.min(width, Math.max(12, width / 5));
            double phase = (Math.sin(now / 550_000_000.0) + 1) / 2;
            int x = left + (int) Math.round((width - segment) * phase);
            IntegrationSettingsStyle.roundedRect(graphics, x, top, segment, BAR_HEIGHT, 2, fade(color, frame.opacity));
        } else {
            int filled = (int) Math.round(width * frame.fraction);
            IntegrationSettingsStyle.roundedRect(graphics, left, top, filled, BAR_HEIGHT, 2, fade(color, frame.opacity));
        }
    }

    private static int fade(int color, float opacity) {
        return (Math.round((color >>> 24) * opacity) << 24) | (color & 0xFFFFFF);
    }

    record Frame(double fraction, float opacity) {}

    static final class Animation {
        private long revision = Long.MIN_VALUE;
        private long visibleSince, updatedAt;
        private double fraction;
        private StructureFinder.ScanStage previous = StructureFinder.ScanStage.IDLE;

        Frame update(StructureFinder.ScanProgress progress, long now) {
            if (progress.stage() == StructureFinder.ScanStage.IDLE) {
                revision = Long.MIN_VALUE;
                previous = StructureFinder.ScanStage.IDLE;
                return new Frame(0, 0);
            }
            if (revision != progress.revision() || previous == StructureFinder.ScanStage.IDLE) {
                revision = progress.revision();
                visibleSince = updatedAt = now;
                fraction = 0;
            }
            double elapsed = Math.clamp((now - updatedAt) / 1_000_000_000.0, 0, 1);
            fraction += (progress.fraction() - fraction) * (1 - Math.exp(-elapsed / 0.12));
            fraction = Math.clamp(fraction, 0, 1);
            updatedAt = now;
            previous = progress.stage();
            float opacity = (float) Math.clamp((now - visibleSince) / 180_000_000.0, 0, 1);
            return new Frame(fraction, opacity);
        }
    }
}
