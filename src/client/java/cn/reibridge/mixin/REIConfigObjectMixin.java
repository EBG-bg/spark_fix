package cn.reibridge.mixin;

import cn.reibridge.config.BridgeConfig;
import dev.codex.spark_fix.SparkFixConfig;
import me.shedaniel.rei.api.client.gui.config.ForceLocalRecipesMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "me.shedaniel.rei.impl.client.config.ConfigObjectImpl", remap = false)
public abstract class REIConfigObjectMixin {
    @Inject(method = "getForceLocalRecipes", at = @At("HEAD"), cancellable = true, remap = false)
    private void reiRecipeBridge$provideLocalRecipes(CallbackInfoReturnable<ForceLocalRecipesMode> callbackInfo) {
        if (!SparkFixConfig.reiRecipeBridgeEnabledAtStartup()) {
            return;
        }
        callbackInfo.setReturnValue(BridgeConfig.get().provideVanillaRecipes
                ? ForceLocalRecipesMode.ALWAYS
                : ForceLocalRecipesMode.NEVER);
    }
}
