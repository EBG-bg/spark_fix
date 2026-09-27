package dev.codex.spark_fix;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Draft and startup state shared by the vanilla and optional owo settings screens.
 * The integration switches are intentionally saved as desired values and only
 * become runtime values after the next Minecraft restart.
 */
final class IntegrationSettingsState {
    private boolean adofaigo;
    private boolean reiRecipeBridge;
    private boolean litematica;
    private ReiInlineSettings reiSettings;
    private boolean reiSettingsLoaded;

    IntegrationSettingsState() {
        this.adofaigo = SparkFixConfig.adofaigoEnabled();
        this.reiRecipeBridge = SparkFixConfig.reiRecipeBridgeEnabled();
        this.litematica = SparkFixConfig.litematicaEnabled();
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

    boolean pendingRestart() {
        return adofaigoPendingRestart() || reiRecipeBridgePendingRestart() || litematicaPendingRestart();
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

    void save() {
        SparkFixConfig.setAdofoigoEnabled(this.adofaigo);
        SparkFixConfig.setReiRecipeBridgeEnabled(this.reiRecipeBridge);
        SparkFixConfig.setLitematicaEnabled(this.litematica);
        SparkFixConfig.save();
    }
}
