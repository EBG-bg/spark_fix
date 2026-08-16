package dev.codex.warmaislandfix.mixin.client;

import dev.codex.warmaislandfix.AxiomFontFallback;
import dev.codex.warmaislandfix.AxiomFontToast;
import imgui.moulberry92.ImFont;
import imgui.moulberry92.ImFontAtlas;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Recovers only after Axiom's original Chinese font atlas has failed to build. */
@Pseudo
@Mixin(targets = "com.moulberry.axiom.editor.EditorUI", remap = false)
abstract class AxiomEditorUIFontMixin {
    private static final Logger WARMAISLANDFIX_LOGGER =
        LoggerFactory.getLogger("warmaislandfix/AxiomFonts");

    @Shadow
    private static String languageCode;

    @Shadow
    private static ImFont font;

    @Shadow
    public static ImFont icons;

    @Shadow
    public static ImFont monospace;

    @Shadow
    private static byte[] loadFont(String name) {
        throw new AssertionError();
    }

    @Shadow
    public static float getUiScale() {
        throw new AssertionError();
    }

    @Redirect(
        method = "initFonts(Ljava/lang/String;)V",
        at = @At(
            value = "INVOKE",
            target = "Limgui/moulberry92/ImFontAtlas;build()Z",
            remap = false
        ),
        require = 0,
        remap = false
    )
    private static boolean warmaIslandFix$recoverFailedChineseAtlas(ImFontAtlas atlas) {
        boolean originalBuildSucceeded = AxiomFontFallback.buildWithoutAtlasOverflow(atlas);
        if (originalBuildSucceeded || !AxiomFontFallback.isChinese(languageCode)) {
            return originalBuildSucceeded;
        }

        int requestedSize = Math.max(1, (int) (16.0F * getUiScale()));
        WARMAISLANDFIX_LOGGER.warn(
            "Axiom's original Chinese font atlas did not fit at {}px; rebuilding a bounded fallback atlas.",
            requestedSize
        );

        try {
            AxiomFontFallback.RecoveryResult result = AxiomFontFallback.rebuild(
                atlas,
                languageCode,
                requestedSize,
                AxiomEditorUIFontMixin::loadFont
            );
            if (result == null) {
                WARMAISLANDFIX_LOGGER.error("Axiom font recovery could not build even the minimal atlas.");
                AxiomFontToast.showFailure();
                return false;
            }

            font = result.regular();
            icons = result.icons();
            monospace = result.monospace();
            AxiomFontToast.showRecovery(requestedSize, result.fontSize(), result.minimal());
            WARMAISLANDFIX_LOGGER.warn(
                "Axiom font recovery succeeded at {}px (minimal={}); the client will continue.",
                result.fontSize(),
                result.minimal()
            );
            return true;
        } catch (Throwable throwable) {
            WARMAISLANDFIX_LOGGER.error("Unexpected error while rebuilding Axiom's font atlas.", throwable);
            AxiomFontToast.showFailure();
            return false;
        }
    }
}
