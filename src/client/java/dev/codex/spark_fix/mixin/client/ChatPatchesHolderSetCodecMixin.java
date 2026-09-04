package dev.codex.spark_fix.mixin.client;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import dev.codex.spark_fix.ChatPatchesCodecSupport;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.HolderSet;
import net.minecraft.resources.HolderSetCodec;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Extends Chat Patches' existing registry-tolerant log codec to HolderSetCodec, which is
 * now used by components such as minecraft:damage_resistant in Minecraft 26.2.
 */
@Mixin(HolderSetCodec.class)
abstract class ChatPatchesHolderSetCodecMixin {
    @Redirect(
        method = "encode(Lnet/minecraft/core/HolderSet;Lcom/mojang/serialization/DynamicOps;Ljava/lang/Object;)Lcom/mojang/serialization/DataResult;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/core/HolderSet;canSerializeIn(Lnet/minecraft/core/HolderOwner;)Z"
        ),
        require = 0
    )
    @SuppressWarnings({"rawtypes", "unchecked"})
    private boolean sparkFix$allowChatPatchesHolderSet(
        HolderSet holderSet,
        HolderOwner owner
    ) {
        boolean vanillaResult = holderSet.canSerializeIn(owner);
        return ChatPatchesCodecSupport.allowChatPatchesUnsafeSerialization(vanillaResult);
    }
}