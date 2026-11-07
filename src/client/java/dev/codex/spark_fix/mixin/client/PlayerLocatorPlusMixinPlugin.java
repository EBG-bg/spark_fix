package dev.codex.spark_fix.mixin.client;

import net.fabricmc.loader.api.FabricLoader;
import dev.codex.spark_fix.PatchHooks;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** This patch targets the confirmed 2.4.0 implementation, not future upstream fixes. */
public final class PlayerLocatorPlusMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) { PatchHooks.installObserver(); }
    @Override public String getRefMapperConfig() { return null; }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return FabricLoader.getInstance().getModContainer("player-locator-plus")
                .map(mod -> "2.4.0".equals(mod.getMetadata().getVersion().getFriendlyString()))
                .orElse(false);
    }

    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        PatchHooks.watch(mixinClassName, targetClass);
    }
}
