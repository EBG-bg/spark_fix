package dev.codex.spark_fix;

import cn.reibridge.config.BridgeConfig;
import cn.reibridge.gui.RecipeDiagnostics;
import cn.reibridge.recipe.RecipeCaptureStore;
import me.shedaniel.rei.api.client.REIRuntime;
import me.shedaniel.rei.api.client.config.ConfigObject;
import me.shedaniel.rei.api.client.overlay.ScreenOverlay;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.util.ArrayList;
import java.util.List;

/** Loaded reflectively only when REI is installed. Both layouts share this state and behavior. */
final class ReiInlineSettingsImpl implements ReiInlineSettings {
    private List<Line> information = List.of();
    private RecipeDiagnostics displayDiagnostics;
    private int pollTicks;
    private boolean refreshFailed;
    private boolean wasLoading;
    private ClientPacketListener diagnosticsConnection;

    ReiInlineSettingsImpl() {
        BridgeConfig.load();
        refresh();
    }

    @Override
    public boolean provideVanilla() { return BridgeConfig.get().provideVanillaRecipes; }

    @Override
    public boolean captureUnlocked() { return BridgeConfig.get().captureUnlockedRecipes; }

    @Override
    public void toggleVanilla() {
        BridgeConfig.get().provideVanillaRecipes = !provideVanilla();
        BridgeConfig.trySave();
        refresh();
    }

    @Override
    public void toggleCapture() {
        BridgeConfig.get().captureUnlockedRecipes = !captureUnlocked();
        BridgeConfig.trySave();
        refresh();
    }

    @Override
    public boolean canRefresh() {
        Minecraft client = Minecraft.getInstance();
        return client.level != null && client.player != null && client.getConnection() != null;
    }

    @Override
    public boolean saveFailed() { return BridgeConfig.saveFailed() || BridgeConfig.loadFailed(); }

    @Override
    public boolean trySave() {
        boolean saved = BridgeConfig.trySave();
        if (saved) refresh();
        else updateInformation();
        return saved;
    }

    @Override
    public void refresh() {
        refreshFailed = false;
        try {
            if (canRefresh() && SparkFixConfig.reiRecipeBridgeEnabledAtStartup()) {
                RecipeCaptureStore.captureCurrentRecipeBook();
                REIRuntime.getInstance().getOverlay().ifPresent(ScreenOverlay::queueReloadSearch);
            }
        } catch (RuntimeException | LinkageError exception) {
            refreshFailed = true;
            SparkFixClient.LOGGER.warn("Could not refresh REI Recipe Bridge recipes", exception);
        }
        displayDiagnostics = null;
        updateInformation();
    }

    @Override
    public boolean pollInformation() {
        if (++pollTicks < 20) return false;
        pollTicks = 0;
        List<Line> previous = information;
        updateInformation();
        return !information.equals(previous);
    }

    @Override
    public Screen cacheScreen(Screen parent) { return new ReiCacheMaintenanceScreen(parent); }

    private void updateInformation() {
        List<Line> lines = new ArrayList<>();
        if (saveFailed()) {
            lines.add(new Line(Component.translatable(BridgeConfig.loadFailed()
                    ? "rei_recipe_bridge.config_read_failed" : "rei_recipe_bridge.config_save_failed"), 0xFFFFD782));
            if (BridgeConfig.lastError() != null) {
                lines.add(new Line(Component.literal(BridgeConfig.lastError()), IntegrationSettingsStyle.MUTED));
            }
        }
        try {
            lines.add(new Line(Component.translatable("rei_recipe_bridge.vanilla_mode",
                    ConfigObject.getInstance().getForceLocalRecipes().name()), IntegrationSettingsStyle.MUTED));
            if (!canRefresh() || !SparkFixConfig.reiRecipeBridgeEnabledAtStartup()) {
                displayDiagnostics = null;
                diagnosticsConnection = null;
                wasLoading = false;
                lines.add(new Line(Component.translatable(canRefresh()
                        ? "config.spark_fix.restart_required" : "rei_recipe_bridge.no_recipes"), 0xFFFFD782));
            } else {
                var connection = Minecraft.getInstance().getConnection();
                var loading = RecipeCaptureStore.loadingProgress();
                if (diagnosticsConnection != connection || (wasLoading && !loading.loading())) displayDiagnostics = null;
                diagnosticsConnection = connection;
                wasLoading = loading.loading();
                if (displayDiagnostics == null) displayDiagnostics = RecipeDiagnostics.collect();
                RecipeDiagnostics diagnostics = displayDiagnostics;
                lines.add(new Line(Component.translatable("rei_recipe_bridge.total", diagnostics.totalDisplays()), 0xFFFFFFFF));
                lines.add(new Line(Component.translatable("rei_recipe_bridge.named", diagnostics.namedDisplays()), IntegrationSettingsStyle.MUTED));
                lines.add(new Line(Component.translatable("rei_recipe_bridge.vanilla", diagnostics.vanillaDisplays()), IntegrationSettingsStyle.MUTED));
                lines.add(new Line(Component.translatable("rei_recipe_bridge.cached", RecipeCaptureStore.currentServerCount()), IntegrationSettingsStyle.MUTED));
                lines.add(new Line(Component.translatable("rei_recipe_bridge.packet", RecipeCaptureStore.lastReceivedCount(),
                        RecipeCaptureStore.lastMergedCount(), RecipeCaptureStore.lastRegisteredCount()), IntegrationSettingsStyle.MUTED));
                if (loading.loading()) lines.add(new Line(Component.translatable(loading.total() < 0
                        ? "rei_recipe_bridge.cache_loading_files" : "rei_recipe_bridge.cache_loading_records",
                        loading.restored(), loading.total()), 0xFFAFCEF9));
                int unsaved = RecipeCaptureStore.unsavedRecipeCount();
                int unavailable = RecipeCaptureStore.unavailableRecipeCount();
                if (unsaved > 0) lines.add(new Line(Component.translatable("rei_recipe_bridge.cache_unsaved", unsaved), 0xFFFFD782));
                if (unavailable > 0) lines.add(new Line(Component.translatable("rei_recipe_bridge.cache_unavailable", unavailable), 0xFFFFD782));
                if (RecipeCaptureStore.cacheReadFailed()) lines.add(new Line(Component.translatable("rei_recipe_bridge.cache_read_failed"), 0xFFFFD782));
                if (RecipeCaptureStore.cacheWriteFailed()) lines.add(new Line(Component.translatable("rei_recipe_bridge.cache_write_failed"), 0xFFFFD782));
                if (diagnostics.totalDisplays() == 0) {
                    lines.add(new Line(Component.translatable("config.spark_fix.rei_empty_in_world"), 0xFFFFD782));
                }
            }
        } catch (RuntimeException | LinkageError exception) {
            SparkFixClient.LOGGER.warn("Could not refresh inline REI Recipe Bridge settings", exception);
            lines.add(new Line(Component.translatable("config.spark_fix.rei_settings_failed"), 0xFFFFD782));
        }
        if (refreshFailed) lines.add(new Line(Component.translatable("config.spark_fix.rei_settings_failed"), 0xFFFFD782));
        lines.add(new Line(Component.translatable("rei_recipe_bridge.capture_hint"), IntegrationSettingsStyle.MUTED));
        information = List.copyOf(lines);
    }

    @Override
    public List<Line> information() { return information; }
}
