package dev.codex.spark_fix;

import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Describes installed repair hooks; it never loads an optional target just to inspect it. */
final class PatchMonitor {
    private static final String CLIENT = "dev.codex.spark_fix.mixin.client.";
    private static final String BRIDGE = "cn.reibridge.mixin.";

    enum State {
        LOADED(0xFF83DFAC), WAITING(0xFFAFCEF9), PARTIAL(0xFFFFCC83),
        UNMATCHED(0xFFFF9999), ABSENT(0xFFADB7C6), UNSUPPORTED(0xFFFFCC83),
        DISABLED(0xFFADB7C6), READY(0xFF83DFAC);

        final int color;
        State(int color) { this.color = color; }
        String key() { return "gui.spark_fix.patch_monitor.state." + name().toLowerCase(java.util.Locale.ROOT); }
    }

    record Hook(String mixin, List<String> methods) { }
    record Patch(String id, String mod, String version, String integration, List<Hook> hooks) {
        String titleKey() { return "gui.spark_fix.patch_monitor." + id + ".title"; }
        String descriptionKey() { return "gui.spark_fix.patch_monitor." + id + ".description"; }
    }
    record Report(Patch patch, State state, int found, int expected, String target) { }

    private static Hook hook(String mixin, String... methods) {
        return new Hook(CLIENT + mixin, List.of(methods));
    }
    private static Hook bridge(String mixin, String... methods) {
        return new Hook(BRIDGE + mixin, List.of(methods));
    }
    private static Patch patch(String id, String mod, Hook... hooks) {
        return new Patch(id, mod, "", "", List.of(hooks));
    }

    static final List<Patch> PATCHES = List.of(
            patch("singleplayer_exit", "", hook("IntegratedServerThreadFixMixin", "sparkFix$refreshChatOnRenderThread")),
            patch("tabtps_exit", "tabtps-fabric"),
            patch("ali_wait", "ali", hook("AliJeiClientCompatibilityMixin", "sparkFix$skipUnavailableServerWait")),
            patch("chat_history", "chatpatches", hook("ChatPatchesHolderSetCodecMixin", "sparkFix$allowChatPatchesHolderSet")),
            patch("waypoint_updates", "", hook("ClientWaypointManagerFixMixin", "sparkFix$recoverOrphanWaypointUpdate")),
            new Patch("locator", "player-locator-plus", "2.4.0", "", List.of(hook("PlayerLocatorPlusProjectionMixin",
                    "sparkFix$rememberUpdatedMarker", "sparkFix$forgetRemovedMarkers", "sparkFix$clearProjectionOrigins",
                    "sparkFix$selectMarkerOrigin", "sparkFix$projectFromMarkerOrigin", "sparkFix$useMainCameraViewVector"))),
            patch("axiom_fonts", "axiom", hook("AxiomEditorUIFontMixin", "sparkFix$recoverFailedChineseAtlas"),
                    hook("AxiomImAssertCallbackMixin", "sparkFix$captureFontAtlasOverflow")),
            patch("axiom_labels", "axiom", hook("AxiomLiteralTextMixin", "sparkFix$copyAirLabel",
                    "sparkFix$copyEntitiesLabel", "sparkFix$keepExistingLabel")),
            patch("rei_placement", "roughlyenoughitems", hook("ReiClientTransferMixin", "sparkFix$useVanillaContainerClicks")),
            patch("boat_use", "", hook("BoatHandsBusyMixin", "sparkFix$recordBoatHandsBusy", "sparkFix$clearBoatHandsBusy",
                    "sparkFix$ignoreStaleRowingState"), hook("BoatConsumableUseMixin", "sparkFix$allowConsumablesWhileRowing",
                    "sparkFix$fallThroughFailedMainHandBlockUse", "sparkFix$fallThroughFailedMainHandUse"),
                    hook("BoatConsumableRendererMixin", "sparkFix$showConsumableUseAnimation")),
            patch("printer_scan", "litematica-printer", hook("PrinterInvalidBoundsMixin", "sparkFix$skipInvalidBounds"),
                    hook("PrinterScanBoundsMixin", "sparkFix$preserveEmptyIntersection"),
                    hook("PrinterIteratorBudgetMixin", "sparkFix$pauseFilteredScan"),
                    hook("PrinterScanPhaseMixin", "sparkFix$boundInnerScan")),
            patch("printer_connection", "litematica-printer", hook("PrinterMovementPacketFixMixin", "sparkFix$preserveStatusPacket")),
            patch("printer_settings", "litematica-printer", hook("PrinterConfigSaveMixin", "sparkFix$selectPrinterSettings",
                    "sparkFix$loadHiddenOptions", "sparkFix$saveHiddenOptions", "sparkFix$preserveOtherPrinterSettings")),
            patch("xaero_cleanup", "xaerominimap", hook("XaeroGuiWaypointsMixin", "sparkFix$addDeathpointButton",
                    "sparkFix$updateDeathpointButton", "sparkFix$refreshDeleteButtons", "sparkFix$clickDeleteButton")),
            new Patch("rei_history", "roughlyenoughitems", "", "rei", List.of(
                    bridge("ClientPacketListenerMixin", "reiRecipeBridge$mergeCapturedRecipes", "reiRecipeBridge$removeCapturedRecipes"),
                    bridge("REIConfigObjectMixin", "reiRecipeBridge$provideLocalRecipes"))),
            new Patch("rei_menus", "roughlyenoughitems", "", "rei", List.of(
                    bridge("REIFreshScissorStackMixin", "reiRecipeBridge$useFullGuiScissorRoot"),
                    bridge("REIMenuTooltipMixin", "reiRecipeBridge$deferMenuTooltip"),
                    bridge("REISubMenuEntryMixin", "reiRecipeBridge$keepSubmenuOnScreen"),
                    bridge("REIMenuRenderMixin", "reiRecipeBridge$renderDeferredSubmenus"))),
            new Patch("litematica_page", "litematica", "", "litematica", List.of(
                    hook("LitematicaMainMenuMixin", "sparkFix$addSettingsButton"),
                    hook("LitematicaEntryRenderMixin", "sparkFix$drawProjectionEntry")))
    );

    private PatchMonitor() { }

    static List<Report> snapshot() {
        var loader = FabricLoader.getInstance();
        Map<String, String> mods = new java.util.HashMap<>();
        loader.getAllMods().forEach(mod -> mods.put(mod.getMetadata().getId(), mod.getMetadata().getVersion().getFriendlyString()));
        var hooks = PatchHooks.snapshot();
        List<Report> reports = new ArrayList<>();
        for (Patch patch : PATCHES) {
            boolean enabled = switch (patch.integration()) {
                case "rei" -> SparkFixConfig.reiRecipeBridgeEnabledAtStartup();
                case "litematica" -> SparkFixConfig.litematicaEnabledAtStartup();
                default -> true;
            };
            reports.add(evaluate(patch, mods, enabled, hooks, PatchHooks.isRuntimeReady(patch.id())));
        }
        return List.copyOf(reports);
    }

    static Report evaluate(Patch patch, Map<String, String> mods, boolean enabled,
                           Map<String, Set<String>> observations, boolean runtimeReady) {
        int expected = patch.hooks().stream().mapToInt(hook -> hook.methods().size()).sum();
        String target = patch.mod().isEmpty() ? "Minecraft " + mods.getOrDefault("minecraft", "")
                : patch.mod() + (mods.containsKey(patch.mod()) ? " " + mods.get(patch.mod()) : "");
        if (!patch.mod().isEmpty() && !mods.containsKey(patch.mod())) return new Report(patch, State.ABSENT, 0, expected, target);
        if (!patch.version().isEmpty() && !patch.version().equals(mods.get(patch.mod()))) {
            return new Report(patch, State.UNSUPPORTED, 0, expected, target);
        }
        if (!enabled) return new Report(patch, State.DISABLED, 0, expected, target);
        if (patch.hooks().isEmpty()) return new Report(patch, runtimeReady ? State.READY : State.WAITING, 0, 0, target);
        int found = 0;
        boolean waiting = false;
        for (Hook hook : patch.hooks()) {
            Set<String> calls = observations.get(hook.mixin());
            if (calls == null) { waiting = true; continue; }
            for (String method : hook.methods()) if (calls.contains(method)) found++;
        }
        State state = found == expected ? State.LOADED : waiting ? State.WAITING : found > 0 ? State.PARTIAL : State.UNMATCHED;
        return new Report(patch, state, found, expected, target);
    }
}
