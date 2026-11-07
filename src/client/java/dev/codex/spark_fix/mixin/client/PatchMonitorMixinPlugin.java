package dev.codex.spark_fix.mixin.client;

import dev.codex.spark_fix.PatchHooks;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/** Observes actual applied hooks without changing which patches are selected. */
public final class PatchMonitorMixinPlugin implements IMixinConfigPlugin {
    @Override public void onLoad(String mixinPackage) { PatchHooks.installObserver(); }
    @Override public String getRefMapperConfig() { return null; }
    @Override public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }
    @Override public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) { }
    @Override public List<String> getMixins() { return null; }
    @Override public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) { }
    @Override public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
        PatchHooks.watch(mixinClassName, targetClass);
    }
}
