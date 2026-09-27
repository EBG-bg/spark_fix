package dev.codex.spark_fix;

import net.minecraft.client.gui.screens.Screen;

/**
 * Compatibility alias retained for older callers. New navigation uses
 * {@link IntegrationSettingsRouter} so an optional owo UI can be selected.
 */
@Deprecated(forRemoval = false)
final class AdofoigoSettingsScreen extends VanillaIntegrationSettingsScreen {
    AdofoigoSettingsScreen(Screen parent) {
        super(parent);
    }
}
