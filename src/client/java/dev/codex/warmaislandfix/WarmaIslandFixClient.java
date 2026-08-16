package dev.codex.warmaislandfix;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Timer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Compatibility cleanup for mods which leave non-daemon scheduling threads alive
 * after Minecraft's render thread has stopped.
 */
public final class WarmaIslandFixClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("warmaislandfix");
    private static final AtomicBoolean CLEANUP_STARTED = new AtomicBoolean();

    @Override
    public void onInitializeClient() {
        LOGGER.info("Loaded. Shutdown, ALI/JEI, Chat Patches, waypoint disconnect, Axiom font recovery, REI transfer, and boat consumable fixes are enabled.");
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> cleanupAll());
    }

    private static void cleanupAll() {
        if (!CLEANUP_STARTED.compareAndSet(false, true)) {
            return;
        }

        LOGGER.info("Minecraft is stopping; cleaning leaked scheduler threads...");
        runCleanup("Leawind's Third Person", "leawind_third_person", WarmaIslandFixClient::cleanupLeawindThirdPerson);
        runCleanup("PatPat", "patpat", WarmaIslandFixClient::cleanupPatPat);
        runCleanup("TabTPS", "tabtps-fabric", WarmaIslandFixClient::cleanupTabTps);
        LOGGER.info("Shutdown thread cleanup finished.");
    }

    private static void runCleanup(String displayName, String modId, ThrowingRunnable cleanup) {
        if (!FabricLoader.getInstance().isModLoaded(modId)) {
            return;
        }

        try {
            cleanup.run();
            LOGGER.info("Cleaned up {}.", displayName);
        } catch (Throwable throwable) {
            // Never allow one third-party compatibility failure to prevent the other cleanups.
            LOGGER.error("Failed to clean up {}. Minecraft will continue shutting down.", displayName, throwable);
        }
    }

    private static void cleanupLeawindThirdPerson() throws Exception {
        Class<?> managerClass = loadClass("com.github.leawind.thirdperson.core.config.ConfigManager");
        Object manager = managerClass.getField("INSTANCE").get(null);

        // Preserve config changes which may still be waiting for the mod's delayed save timer.
        try {
            Method trySave = managerClass.getMethod("trySave");
            trySave.invoke(manager);
        } catch (ReflectiveOperationException exception) {
            LOGGER.warn("Could not request a final Leawind config save; the timer will still be cancelled.", exception);
        }

        Field timerField = managerClass.getDeclaredField("lazySaveTimer");
        timerField.setAccessible(true);
        Object value = timerField.get(manager);
        if (value instanceof Timer timer) {
            timer.cancel();
            timer.purge();
        } else {
            throw new IllegalStateException("Leawind lazySaveTimer was not a java.util.Timer");
        }
    }

    private static void cleanupPatPat() throws Exception {
        Class<?> autoSaveManager = loadClass(
            "net.lopymine.patpat.client.config.PatPatClientStatsConfig$AutoSaveManager"
        );
        Field serviceField = autoSaveManager.getDeclaredField("SERVICE");
        serviceField.setAccessible(true);
        Object value = serviceField.get(null);
        if (value instanceof ExecutorService executor) {
            stopExecutor("PatPat auto-save", executor);
        } else {
            throw new IllegalStateException("PatPat AutoSaveManager.SERVICE was not an ExecutorService");
        }
    }

    private static void cleanupTabTps() throws Exception {
        Class<?> fabricClass = loadClass("xyz.jpenilla.tabtps.fabric.TabTPSFabric");
        Object fabricInstance = fabricClass.getMethod("get").invoke(null);
        Object tabTps = fabricClass.getMethod("tabTPS").invoke(fabricInstance);
        tabTps.getClass().getMethod("shutdown").invoke(tabTps);
    }

    private static void stopExecutor(String name, ExecutorService executor) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(250, TimeUnit.MILLISECONDS)) {
                LOGGER.warn("{} did not stop promptly; interrupting remaining tasks.", name);
                executor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static Class<?> loadClass(String name) throws ClassNotFoundException {
        return Class.forName(name, true, WarmaIslandFixClient.class.getClassLoader());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
