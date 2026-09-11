package cn.reibridge.compat;

import cn.reibridge.recipe.RecipeCaptureStore;
import dev.codex.spark_fix.SparkFixConfig;
import dev.architectury.event.EventResult;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import net.minecraft.resources.Identifier;

public final class REIRecipeBridgePlugin implements REIClientPlugin {
    @Override
    public void registerDisplays(DisplayRegistry registry) {
        if (!SparkFixConfig.reiRecipeBridgeEnabledAtStartup()) {
            return;
        }
        RecipeCaptureStore.registerCachedDisplays(registry);
        registry.registerVisibilityPredicate((category, display) -> {
            if (display.getDisplayLocation()
                    .filter(recipeId -> !Identifier.DEFAULT_NAMESPACE.equals(recipeId.getNamespace()))
                    .isPresent()) {
                return EventResult.interruptFalse();
            }
            return EventResult.pass();
        });
    }
}
