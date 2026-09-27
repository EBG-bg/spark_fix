package dev.codex.spark_fix;

import cn.reibridge.config.BridgeConfig;
import com.adofaigo.client.AdofoigoLaunchHud;
import com.adofaigo.client.SteamLauncher;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Stops TabTPS's non-daemon schedulers before the JVM waits for them to exit.
 */
public final class SparkFixClient implements ClientModInitializer {
    static final Logger LOGGER = LoggerFactory.getLogger("spark_fix");
    private static final AtomicBoolean CLEANUP_STARTED = new AtomicBoolean();

    @Override
    public void onInitializeClient() {
        SparkFixConfig.load();
        if (SparkFixConfig.adofaigoEnabledAtStartup()) AdofoigoLaunchHud.register();
        if (FabricLoader.getInstance().isModLoaded("roughlyenoughitems")) {
            BridgeConfig.load();
        }
        LOGGER.info(
            "Loaded. ADOFAI={}, REI recipe bridge={}, REI transfer fallback and client compatibility fixes are ready.",
            SparkFixConfig.adofaigoEnabledAtStartup(),
            SparkFixConfig.reiRecipeBridgeEnabledAtStartup()
        );
        ClientPlayConnectionEvents.DISCONNECT.register((connection, client) -> SteamLauncher.cancelPendingLaunch());
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            SteamLauncher.cancelPendingLaunch();
            cleanupAll();
        });
    }

    private static void cleanupAll() {
        if (!CLEANUP_STARTED.compareAndSet(false, true)) {
            return;
        }

        LOGGER.info("Minecraft is stopping; cleaning leaked scheduler threads...");
        runCleanup("TabTPS", "tabtps-fabric", SparkFixClient::cleanupTabTps);
        LOGGER.info("Shutdown thread cleanup finished.");
    }

    private static void runCleanup(String displayName, String modId, ThrowingRunnable cleanup) {
        if (!FabricLoader.getInstance().isModLoaded(modId)) {
            return;
        }

        try {
            cleanup.run();
            LOGGER.info("Cleaned up {}.", displayName);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            // An optional compatibility failure must not interrupt Minecraft's shutdown.
            LOGGER.error("Failed to clean up {}. Minecraft will continue shutting down.", displayName, exception);
        }
    }

    private static void cleanupTabTps() throws ReflectiveOperationException {
        Class<?> fabricClass = loadClass("xyz.jpenilla.tabtps.fabric.TabTPSFabric");
        Object fabricInstance = fabricClass.getMethod("get").invoke(null);
        Object tabTps = fabricClass.getMethod("tabTPS").invoke(fabricInstance);
        tabTps.getClass().getMethod("shutdown").invoke(tabTps);
    }

    private static Class<?> loadClass(String name) throws ClassNotFoundException {
        return Class.forName(name, true, SparkFixClient.class.getClassLoader());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws ReflectiveOperationException;
    }
}
