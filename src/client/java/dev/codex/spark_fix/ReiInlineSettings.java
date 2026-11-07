package dev.codex.spark_fix;

import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.screens.Screen;
import java.util.List;

/** UI-facing contract with no REI types, so the ordinary screens remain optional-dependency safe. */
interface ReiInlineSettings {
    boolean provideVanilla();
    boolean captureUnlocked();
    void toggleVanilla();
    void toggleCapture();
    void refresh();
    boolean canRefresh();
    boolean saveFailed();
    boolean trySave();
    List<Line> information();
    default boolean pollInformation() { return false; }
    Screen cacheScreen(Screen parent);

    default Component vanillaLabel() {
        return optionLabel("rei_recipe_bridge.vanilla_toggle", provideVanilla());
    }

    default Component captureLabel() {
        return optionLabel("rei_recipe_bridge.capture_toggle", captureUnlocked());
    }

    private static Component optionLabel(String key, boolean enabled) {
        return Component.translatable(key).append(": ").append(Component.translatable(
                enabled ? "config.spark_fix.on" : "config.spark_fix.off"));
    }

    record Line(Component text, int color) {}
}
