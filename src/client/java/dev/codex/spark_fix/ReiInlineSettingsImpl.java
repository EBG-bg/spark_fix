package dev.codex.spark_fix;

import cn.reibridge.config.BridgeConfig;
import cn.reibridge.gui.RecipeDiagnostics;
import cn.reibridge.recipe.RecipeCaptureStore;
import me.shedaniel.rei.api.client.REIRuntime;
import me.shedaniel.rei.api.client.config.ConfigObject;
import me.shedaniel.rei.api.client.overlay.ScreenOverlay;
import net.minecraft.network.chat.Component;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.List;

/** Loaded reflectively only when REI is installed. Both layouts share this state and behavior. */
final class ReiInlineSettingsImpl implements ReiInlineSettings {
    private List<Line> information = List.of();

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
        BridgeConfig.save();
        refresh();
    }

    @Override
    public void toggleCapture() {
        BridgeConfig.get().captureUnlockedRecipes = !captureUnlocked();
        BridgeConfig.save();
        refresh();
    }

    @Override
    public boolean canRefresh() {
        Minecraft client = Minecraft.getInstance();
        return client.level != null && client.player != null && client.getConnection() != null;
    }

    @Override
    public void refresh() {
        List<Line> lines = new ArrayList<>();
        try {
            // Options can be edited before restarting to enable the integration.
            // Do not start runtime capture while it is still disabled for this session.
            if (canRefresh() && SparkFixConfig.reiRecipeBridgeEnabledAtStartup()) {
                RecipeCaptureStore.captureCurrentRecipeBook();
                REIRuntime.getInstance().getOverlay().ifPresent(ScreenOverlay::queueReloadSearch);
            }
            lines.add(new Line(Component.translatable("rei_recipe_bridge.vanilla_mode",
                    ConfigObject.getInstance().getForceLocalRecipes().name()), IntegrationSettingsStyle.MUTED));
            if (!canRefresh()) {
                lines.add(new Line(Component.translatable("rei_recipe_bridge.no_recipes"), 0xFFFFD782));
            } else {
                RecipeDiagnostics diagnostics = RecipeDiagnostics.collect();
                lines.add(new Line(Component.translatable("rei_recipe_bridge.total", diagnostics.totalDisplays()), 0xFFFFFFFF));
                lines.add(new Line(Component.translatable("rei_recipe_bridge.named", diagnostics.namedDisplays()), IntegrationSettingsStyle.MUTED));
                lines.add(new Line(Component.translatable("rei_recipe_bridge.vanilla", diagnostics.vanillaDisplays()), IntegrationSettingsStyle.MUTED));
                lines.add(new Line(Component.translatable("rei_recipe_bridge.cached", diagnostics.cached()), IntegrationSettingsStyle.MUTED));
                lines.add(new Line(Component.translatable("rei_recipe_bridge.packet", diagnostics.lastReceived(),
                        diagnostics.lastMerged(), diagnostics.lastRegistered()), IntegrationSettingsStyle.MUTED));
                if (diagnostics.totalDisplays() == 0) {
                    lines.add(new Line(Component.translatable("config.spark_fix.rei_empty_in_world"), 0xFFFFD782));
                }
            }
        } catch (RuntimeException | LinkageError exception) {
            SparkFixClient.LOGGER.warn("Could not refresh inline REI Recipe Bridge settings", exception);
            lines.add(new Line(Component.translatable("config.spark_fix.rei_settings_failed"), 0xFFFFD782));
        }
        lines.add(new Line(Component.translatable("rei_recipe_bridge.capture_hint"), IntegrationSettingsStyle.MUTED));
        information = List.copyOf(lines);
    }

    @Override
    public List<Line> information() { return information; }
}
