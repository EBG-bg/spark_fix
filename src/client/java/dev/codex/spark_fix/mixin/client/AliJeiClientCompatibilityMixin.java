package dev.codex.spark_fix.mixin.client;

import dev.codex.spark_fix.AliServerSupport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BiConsumer;

/**
 * ALI's JEI bridge blocks the render thread while waiting for loot data even
 * when Fabric reports that the server cannot receive its request packet. On
 * plugin/non-ALI servers, complete the registration with ALI's supported empty
 * payload instead of waiting through three thirty-second timeouts.
 */
@Pseudo
@Mixin(targets = "com.yanny.ali.compatibility.common.GenericUtils", remap = false)
abstract class AliJeiClientCompatibilityMixin {
    @Inject(
        method = "register(Ljava/lang/Object;Ljava/util/function/BiConsumer;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private static void sparkFix$skipUnavailableServerWait(
        Object registry,
        BiConsumer<Object, byte[]> registerData,
        CallbackInfo ci
    ) {
        if (AliServerSupport.canRequestLootData()) {
            return;
        }

        AliServerSupport.logUnavailableChannel();
        registerData.accept(registry, new byte[0]);
        ci.cancel();
    }
}
