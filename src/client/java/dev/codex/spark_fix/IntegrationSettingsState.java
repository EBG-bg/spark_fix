package dev.codex.spark_fix;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Draft and startup state shared by the vanilla and optional owo settings screens.
 * The integration switches are intentionally saved as desired values and only
 * become runtime values after the next Minecraft restart.
 */
final class IntegrationSettingsState {
    private boolean adofaigo;
    private boolean reiRecipeBridge;
    private boolean litematica;
    private boolean structureFinder;
    private ReiInlineSettings reiSettings;
    private boolean reiSettingsLoaded;
    private List<Component> saveFailures = List.of();

    IntegrationSettingsState() {
        this.adofaigo = SparkFixConfig.adofaigoEnabled();
        this.reiRecipeBridge = SparkFixConfig.reiRecipeBridgeEnabled();
        this.litematica = SparkFixConfig.litematicaEnabled();
        this.structureFinder = SparkFixConfig.structureFinderEnabled();
    }

    boolean adofaigo() {
        return this.adofaigo;
    }

    void adofaigo(boolean value) {
        this.adofaigo = value;
    }

    boolean reiRecipeBridge() {
        return this.reiRecipeBridge;
    }

    void reiRecipeBridge(boolean value) {
        this.reiRecipeBridge = value;
    }

    boolean litematica() {
        return this.litematica;
    }

    void litematica(boolean value) {
        this.litematica = value;
    }

    boolean adofaigoAtStartup() {
        return SparkFixConfig.adofaigoEnabledAtStartup();
    }

    boolean reiRecipeBridgeAtStartup() {
        return SparkFixConfig.reiRecipeBridgeEnabledAtStartup();
    }

    boolean adofaigoPendingRestart() {
        return this.adofaigo != this.adofaigoAtStartup();
    }

    boolean reiRecipeBridgePendingRestart() {
        return this.reiRecipeBridge != this.reiRecipeBridgeAtStartup();
    }

    boolean litematicaPendingRestart() {
        return this.litematica != SparkFixConfig.litematicaEnabledAtStartup();
    }

    boolean structureFinder() { return structureFinder; }

    void structureFinder(boolean value) { structureFinder = value; }

    boolean structureFinderPendingRestart() {
        return structureFinder != SparkFixConfig.structureFinderEnabledAtStartup();
    }

    boolean pendingRestart() {
        return adofaigoPendingRestart() || reiRecipeBridgePendingRestart() || litematicaPendingRestart()
                || structureFinderPendingRestart();
    }

    boolean reiInstalled() {
        return FabricLoader.getInstance().isModLoaded("roughlyenoughitems");
    }

    boolean litematicaInstalled() {
        return FabricLoader.getInstance().isModLoaded("litematica");
    }

    ReiInlineSettings reiSettings() {
        if (!reiSettingsLoaded) {
            reiSettingsLoaded = true;
            reiSettings = ReiSettingsRouter.create();
        }
        return reiSettings;
    }

    boolean trySave() {
        SparkFixConfig.setAdofoigoEnabled(this.adofaigo);
        SparkFixConfig.setReiRecipeBridgeEnabled(this.reiRecipeBridge);
        SparkFixConfig.setLitematicaEnabled(this.litematica);
        SparkFixConfig.setStructureFinderEnabled(this.structureFinder);
        List<Component> failed = new ArrayList<>();
        if (!SparkFixConfig.trySave()) failed.add(Component.translatable("config.spark_fix.title"));
        if (reiSettings != null && reiSettings.saveFailed() && !reiSettings.trySave()) {
            failed.add(Component.literal("REI Recipe Bridge"));
        }
        saveFailures = List.copyOf(failed);
        return failed.isEmpty();
    }

    List<Component> saveFailures() { return saveFailures; }
}
