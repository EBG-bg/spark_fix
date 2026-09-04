package dev.codex.spark_fix.mixin.client;

import dev.codex.spark_fix.AxiomChineseText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

/** Translates Axiom 5.5.0 labels that bypass its language table. */
@Pseudo
@Mixin(targets = "com.moulberry.axiom.screen.SwitchBuilderToolScreen", remap = false)
abstract class AxiomLiteralTextMixin {
    @ModifyConstant(
        method = "init",
        constant = @Constant(stringValue = "Copy Air"),
        require = 0,
        remap = false
    )
    private String sparkFix$copyAirLabel(String original) {
        return AxiomChineseText.forCurrentLanguage(original, "复制空气");
    }

    @ModifyConstant(
        method = "init",
        constant = @Constant(stringValue = "Copy Entities"),
        require = 0,
        remap = false
    )
    private String sparkFix$copyEntitiesLabel(String original) {
        return AxiomChineseText.forCurrentLanguage(original, "复制实体");
    }

    @ModifyConstant(
        method = "init",
        constant = @Constant(stringValue = "Keep Existing"),
        require = 0,
        remap = false
    )
    private String sparkFix$keepExistingLabel(String original) {
        return AxiomChineseText.forCurrentLanguage(original, "保留现有");
    }
}
