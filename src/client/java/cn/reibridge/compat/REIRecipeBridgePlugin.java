package cn.reibridge.compat;

import cn.reibridge.recipe.RecipeCaptureStore;
import dev.codex.spark_fix.SparkFixConfig;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;

public final class REIRecipeBridgePlugin implements REIClientPlugin {
    @Override
    public void registerDisplays(DisplayRegistry registry) {
        if (!SparkFixConfig.reiRecipeBridgeEnabledAtStartup()) {
            return;
        }
        RecipeCaptureStore.registerCachedDisplays(registry);
    }
}
