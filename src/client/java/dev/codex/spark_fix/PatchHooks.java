package dev.codex.spark_fix;

import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.ext.Extensions;
import org.spongepowered.asm.mixin.transformer.ext.IExtension;
import org.spongepowered.asm.mixin.transformer.ext.ITargetClassContext;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Loaded during Mixin application: deliberately has no Minecraft or optional-mod types. */
public final class PatchHooks {
    private static final Map<String, Set<String>> APPLIED = new ConcurrentHashMap<>();
    private static final Set<String> RUNTIME_READY = ConcurrentHashMap.newKeySet();
    private static final Map<String, Set<String>> TARGETS = new ConcurrentHashMap<>();
    private static boolean observerInstalled;

    private PatchHooks() { }

    public static synchronized void installObserver() {
        if (observerInstalled) return;
        var transformer = (IMixinTransformer) MixinEnvironment.getCurrentEnvironment().getActiveTransformer();
        ((Extensions) transformer.getExtensions()).add(new IExtension() {
            @Override public boolean checkActive(MixinEnvironment environment) { return true; }
            @Override public void preApply(ITargetClassContext context) { }
            @Override public void postApply(ITargetClassContext context) { }
            @Override public void export(MixinEnvironment environment, String name, boolean force, ClassNode target) {
                Set<String> mixins = TARGETS.remove(target.name);
                if (mixins != null) for (String mixin : mixins) applied(mixin, target);
            }
        });
        observerInstalled = true;
    }

    public static void watch(String mixin, ClassNode target) {
        // MixinExtras finishes operation wrappers after IMixinConfigPlugin.postApply.
        // Observe only the final class, after every extension's postApply has completed.
        TARGETS.computeIfAbsent(target.name, unused -> ConcurrentHashMap.newKeySet()).add(mixin);
    }

    public static void applied(String mixin, ClassNode target) {
        Set<String> calls = new LinkedHashSet<>();
        for (var method : target.methods) {
            for (var instruction : method.instructions) {
                if (!(instruction instanceof MethodInsnNode call) || !call.owner.equals(target.name)) continue;
                for (String prefix : new String[]{"sparkFix$", "reiRecipeBridge$", "adofaigo$"}) {
                    int start = call.name.indexOf(prefix);
                    if (start >= 0) calls.add(call.name.substring(start));
                }
            }
        }
        APPLIED.put(mixin, Set.copyOf(calls));
    }

    static Map<String, Set<String>> snapshot() { return Map.copyOf(APPLIED); }

    public static void runtimeReady(String patch) { RUNTIME_READY.add(patch); }

    static boolean isRuntimeReady(String patch) { return RUNTIME_READY.contains(patch); }
}
