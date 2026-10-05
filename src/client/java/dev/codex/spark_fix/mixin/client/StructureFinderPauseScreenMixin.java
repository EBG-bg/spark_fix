package dev.codex.spark_fix.mixin.client;

import dev.codex.spark_fix.SparkFixConfig;
import dev.codex.spark_fix.StructureFinderIconButton;
import dev.codex.spark_fix.StructureFinderScreen;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Mixin(value = PauseScreen.class, priority = 900)
public abstract class StructureFinderPauseScreenMixin extends Screen {
    protected StructureFinderPauseScreenMixin(Component title) { super(title); }

    @Inject(method = "init", at = @At("TAIL"))
    private void sparkFix$addStructureFinder(CallbackInfo ci) {
        PauseScreen pause = (PauseScreen) (Object) this;
        if (!SparkFixConfig.structureFinderEnabledAtStartup() || !pause.showsPauseMenu()) return;

        // Find the actual tool row, including other mods' buttons, rather than
        // assuming a resource pack or a fixed menu layout.
        Map<Integer, int[]> rows = new LinkedHashMap<>();
        List<AbstractWidget> widgets = new ArrayList<>();
        for (GuiEventListener child : pause.children()) {
            if (!(child instanceof AbstractWidget widget)) continue;
            widgets.add(widget);
            if (widget.getWidth() != 20 || widget.getHeight() != 20) continue;
            int[] row = rows.computeIfAbsent(widget.getY(), ignored -> new int[]{0, 0});
            row[0]++;
            row[1] = Math.max(row[1], widget.getRight());
        }
        int rowY = height / 2 - 38;
        int right = width / 2 + 100;
        int count = 0;
        for (Map.Entry<Integer, int[]> row : rows.entrySet()) {
            if (row.getValue()[0] <= count) continue;
            count = row.getValue()[0];
            rowY = row.getKey();
            right = row.getValue()[1];
        }
        int x = right + 4;
        // Preserve every existing control even when a narrow window or another
        // mod fills the tool row. Prefer the right side before a free top corner.
        if (!sparkFix$freeSlot(widgets, x, rowY)) {
            x = Math.max(4, Math.min(width / 2 + 104, width - 24));
            boolean found = false;
            for (int candidateY = rowY; candidateY >= 4; candidateY -= 24) {
                if (!sparkFix$freeSlot(widgets, x, candidateY)) continue;
                rowY = candidateY;
                found = true;
                break;
            }
            if (!found) {
                x = width - 24;
                rowY = 4;
                if (!sparkFix$freeSlot(widgets, x, rowY)) return;
            }
        }
        addRenderableWidget(new StructureFinderIconButton(x, rowY,
                ignored -> minecraft.setScreenAndShow(new StructureFinderScreen(this))));
    }

    @Unique
    private boolean sparkFix$freeSlot(List<AbstractWidget> widgets, int x, int y) {
        if (x < 4 || y < 4 || x + 20 > width - 4 || y + 20 > height - 4) return false;
        for (AbstractWidget widget : widgets) {
            if (widget.visible && x < widget.getRight() && x + 20 > widget.getX()
                    && y < widget.getBottom() && y + 20 > widget.getY()) return false;
        }
        return true;
    }
}
