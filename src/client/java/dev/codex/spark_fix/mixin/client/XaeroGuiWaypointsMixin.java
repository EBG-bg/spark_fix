package dev.codex.spark_fix.mixin.client;

import dev.codex.spark_fix.XaeroDeathpointCleaner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.world.MinimapWorld;

import java.io.IOException;

@Pseudo
@Mixin(targets = "xaero.common.gui.GuiWaypoints", remap = false)
abstract class XaeroGuiWaypointsMixin extends Screen {
    @Unique private static final Logger sparkFix$LOGGER = LoggerFactory.getLogger("spark_fix/xaero-deathpoints");
    @Unique private Button sparkFix$deleteDeathpointsButton;

    @Shadow private Button clearButton;
    @Shadow private MinimapWorld displayedWorld;
    @Shadow private MinimapSession session;
    @Shadow public abstract void clearSelection();
    @Shadow private void updateSortedList() { }
    @Shadow private void undrag() { }

    protected XaeroGuiWaypointsMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"), remap = false)
    private void sparkFix$addDeathpointButton(CallbackInfo callbackInfo) {
        sparkFix$deleteDeathpointsButton = null;
        if (clearButton == null || displayedWorld == null || session == null) return;
        Component label = Component.translatable("gui.spark_fix.xaero.delete_deathpoints");
        int buttonWidth = Math.max(76, font.width(label) + 12);
        int x = clearButton.getX() + clearButton.getWidth() + 4;
        int y = clearButton.getY();
        if (x + buttonWidth > width - 4) {
            // Beside Done on narrow screens; leave space for XaeroPlus's toggle.
            x = width / 2 + 106;
            y = height - 29;
            buttonWidth = Math.min(buttonWidth, Math.min(72, width - x - 4));
        }
        if (buttonWidth < 32) return;
        sparkFix$deleteDeathpointsButton = addRenderableWidget(Button.builder(label,
                        button -> sparkFix$deleteDeathpoints())
                .bounds(x, y, buttonWidth, clearButton.getHeight())
                .tooltip(Tooltip.create(Component.translatable("gui.spark_fix.xaero.delete_deathpoints_hint")))
                .build());
        sparkFix$refreshDeathpointButton();
    }

    @Inject(method = "updateSortedList", at = @At("TAIL"), remap = false)
    private void sparkFix$updateDeathpointButton(CallbackInfo callbackInfo) {
        sparkFix$refreshDeathpointButton();
    }

    @Unique
    private void sparkFix$refreshDeathpointButton() {
        if (sparkFix$deleteDeathpointsButton != null) {
            sparkFix$deleteDeathpointsButton.active = XaeroDeathpointCleaner.hasDeathpoints(displayedWorld);
        }
    }

    @Unique
    private void sparkFix$deleteDeathpoints() {
        Minecraft minecraft = Minecraft.getInstance();
        if (session == null || displayedWorld == null || minecraft.level == null) return;
        undrag();
        int deleted;
        try {
            deleted = XaeroDeathpointCleaner.delete(displayedWorld, session.getWorldManagerIO()::saveWorld);
        } catch (IOException | RuntimeException exception) {
            sparkFix$LOGGER.error("Failed to delete Xaero deathpoints", exception);
            if (minecraft.player != null) {
                minecraft.player.sendOverlayMessage(Component.translatable(
                        "gui.spark_fix.xaero.deathpoints_delete_failed"));
            }
            sparkFix$refreshDeathpointButton();
            return;
        }
        clearSelection();
        updateSortedList();
        for (GuiEventListener child : children()) {
            if (child instanceof ObjectSelectionList<?> list) list.setScrollAmount(0);
        }
        if (minecraft.player != null) {
            minecraft.player.sendOverlayMessage(Component.translatable(
                    "gui.spark_fix.xaero.deathpoints_deleted", deleted));
        }
        sparkFix$refreshDeathpointButton();
    }
}
