package dev.codex.spark_fix.mixin.client;

import dev.codex.spark_fix.XaeroDeathpointCleaner;
import dev.codex.spark_fix.SparkFixConfig;
import dev.codex.spark_fix.XaeroStructureWaypointCleaner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.world.MinimapWorld;

import java.io.IOException;

@Pseudo
@Mixin(targets = "xaero.common.gui.GuiWaypoints", remap = false)
abstract class XaeroGuiWaypointsMixin extends Screen {
    @Unique private static final Logger sparkFix$LOGGER = LoggerFactory.getLogger("spark_fix/xaero-deathpoints");
    @Unique private Button sparkFix$deleteDeathpointsButton;
    @Unique private Button sparkFix$deleteStructureWaypointsButton;

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
        sparkFix$deleteStructureWaypointsButton = null;
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
        sparkFix$addStructureWaypointButton();
    }

    @Inject(method = "updateSortedList", at = @At("TAIL"), remap = false)
    private void sparkFix$updateDeathpointButton(CallbackInfo callbackInfo) {
        sparkFix$refreshDeathpointButton();
        sparkFix$refreshStructureWaypointButton();
    }

    @Inject(method = "updateButtons", at = @At("TAIL"), remap = false)
    private void sparkFix$refreshDeleteButtons(CallbackInfo callbackInfo) {
        sparkFix$refreshDeathpointButton();
        sparkFix$refreshStructureWaypointButton();
    }

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true, remap = false)
    private void sparkFix$clickDeleteButton(MouseButtonEvent event, boolean doubleClick,
                                            CallbackInfoReturnable<Boolean> callbackInfo) {
        // Xaero's list is registered before these buttons and can consume
        // their clicks. Handle our own widgets before its drag/list logic.
        if ((sparkFix$deleteDeathpointsButton != null
                && sparkFix$deleteDeathpointsButton.mouseClicked(event, doubleClick))
                || (sparkFix$deleteStructureWaypointsButton != null
                && sparkFix$deleteStructureWaypointsButton.mouseClicked(event, doubleClick))) {
            callbackInfo.setReturnValue(true);
        }
    }

    @Unique
    private void sparkFix$addStructureWaypointButton() {
        sparkFix$deleteStructureWaypointsButton = null;
        if (!SparkFixConfig.structureFinderEnabledAtStartup() || sparkFix$deleteDeathpointsButton == null) return;
        Component label = Component.translatable("gui.spark_fix.xaero.delete_structure_waypoints");
        int buttonWidth = Math.max(76, font.width(label) + 12);
        // The row below Clear belongs to Xaero's waypoint list. Use the
        // free header slot above the deathpoint button, outside that list.
        int x = sparkFix$deleteDeathpointsButton.getX();
        int y = Math.max(4, clearButton.getY() - clearButton.getHeight() - 4);
        int buttonHeight = clearButton.getHeight();
        if (sparkFix$deleteDeathpointsButton.getY() != clearButton.getY()) {
            // Xaero's dimension dropdown starts at y=17. On narrow screens,
            // use the gap between its two header titles, above the dropdowns.
            int leftEdge = Math.max(4, width / 2 - 102
                    + (font.width(Component.translatable("gui.xaero_world_server")) + 1) / 2 + 4);
            int rightEdge = Math.min(width - 4, width / 2 + 102
                    - font.width(Component.translatable("gui.xaero_subworld_dimension")) / 2 - 4);
            buttonWidth = Math.min(buttonWidth, rightEdge - leftEdge);
            x = leftEdge + (rightEdge - leftEdge - buttonWidth) / 2;
            y = 1;
            buttonHeight = 14;
        }
        buttonWidth = Math.min(buttonWidth, width - x - 4);
        if (buttonWidth < 32 || x < 0 || x + buttonWidth > width) return;
        sparkFix$deleteStructureWaypointsButton = addRenderableWidget(Button.builder(label,
                        button -> sparkFix$deleteStructureWaypoints())
                .bounds(x, y, buttonWidth, buttonHeight)
                .tooltip(Tooltip.create(Component.translatable(
                        "gui.spark_fix.xaero.delete_structure_waypoints_hint")))
                .build());
        sparkFix$refreshStructureWaypointButton();
    }

    @Unique
    private void sparkFix$refreshStructureWaypointButton() {
        if (sparkFix$deleteStructureWaypointsButton != null) {
            sparkFix$deleteStructureWaypointsButton.active =
                    XaeroStructureWaypointCleaner.hasWaypoints(displayedWorld);
        }
    }

    @Unique
    private void sparkFix$deleteStructureWaypoints() {
        Minecraft minecraft = Minecraft.getInstance();
        if (session == null || displayedWorld == null || minecraft.level == null) return;
        undrag();
        int deleted;
        try {
            deleted = XaeroStructureWaypointCleaner.delete(displayedWorld, session.getWorldManagerIO()::saveWorld);
        } catch (IOException | RuntimeException exception) {
            sparkFix$LOGGER.error("Failed to delete Structure Finder waypoints", exception);
            if (minecraft.player != null) {
                minecraft.player.sendOverlayMessage(Component.translatable(
                        "gui.spark_fix.xaero.structure_waypoints_delete_failed"));
            }
            sparkFix$refreshStructureWaypointButton();
            return;
        }
        clearSelection();
        updateSortedList();
        for (GuiEventListener child : children()) {
            if (child instanceof ObjectSelectionList<?> list) list.setScrollAmount(0);
        }
        if (minecraft.player != null) {
            minecraft.player.sendOverlayMessage(Component.translatable(
                    "gui.spark_fix.xaero.structure_waypoints_deleted", deleted));
        }
        minecraft.gui.chatListener().handleSystemMessage(Component.translatable(
                "gui.spark_fix.xaero.structure_waypoints_deleted", deleted), false);
        sparkFix$refreshStructureWaypointButton();
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
