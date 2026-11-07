package cn.reibridge.compat;

import cn.reibridge.recipe.RecipeCaptureStore;
import dev.codex.spark_fix.SparkFixConfig;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.common.plugins.PluginManager;
import me.shedaniel.rei.api.common.registry.ReloadStage;

public final class REIRecipeBridgePlugin implements REIClientPlugin {
    @Override
    public void registerDisplays(DisplayRegistry registry) {
        if (!SparkFixConfig.reiRecipeBridgeEnabledAtStartup()) {
            return;
        }
        RecipeCaptureStore.registerCachedDisplays(registry);
    }

    @Override
    public void postStage(PluginManager<REIClientPlugin> manager, ReloadStage stage) {
        if (stage == ReloadStage.END && SparkFixConfig.reiRecipeBridgeEnabledAtStartup()) {
            RecipeCaptureStore.finishDisplayRegistration();
        }
    }
}
