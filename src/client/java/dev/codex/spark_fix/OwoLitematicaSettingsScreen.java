package dev.codex.spark_fix;

import net.minecraft.client.gui.screens.Screen;

/**
 * Owo installations use the same card implementation as the dependency-free
 * page. This keeps search, favourites and drag ordering identical regardless
 * of whether owo-lib is present.
 */
final class OwoLitematicaSettingsScreen extends VanillaLitematicaSettingsScreen {
    OwoLitematicaSettingsScreen(Screen parent) {
        super(parent);
    }
}
