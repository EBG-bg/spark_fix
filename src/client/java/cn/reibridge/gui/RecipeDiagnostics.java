package cn.reibridge.gui;

import cn.reibridge.recipe.RecipeCaptureStore;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.common.display.Display;
import net.minecraft.resources.Identifier;

public record RecipeDiagnostics(
        int totalDisplays,
        int namedDisplays,
        int vanillaDisplays,
        int cached,
        int lastReceived,
        int lastMerged,
        int lastRegistered
) {
    public static RecipeDiagnostics collect() {
        int totalDisplays = 0;
        int namedDisplays = 0;
        int vanillaDisplays = 0;
        for (var displays : DisplayRegistry.getInstance().getAll().values()) {
            for (Display display : displays) {
                totalDisplays++;
                if (display.getDisplayLocation().isPresent()) {
                    namedDisplays++;
                    if (Identifier.DEFAULT_NAMESPACE.equals(display.getDisplayLocation().orElseThrow().getNamespace())) {
                        vanillaDisplays++;
                    }
                }
            }
        }
        return new RecipeDiagnostics(
                totalDisplays,
                namedDisplays,
                vanillaDisplays,
                RecipeCaptureStore.currentServerCount(),
                RecipeCaptureStore.lastReceivedCount(),
                RecipeCaptureStore.lastMergedCount(),
                RecipeCaptureStore.lastRegisteredCount()
        );
    }
}
