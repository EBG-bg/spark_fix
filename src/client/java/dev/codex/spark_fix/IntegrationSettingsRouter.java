package dev.codex.spark_fix;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.Screen;

import java.lang.reflect.Constructor;

/** Selects the enhanced screen only when the optional owo library is present. */
public final class IntegrationSettingsRouter {
    private static final String OWO_SCREEN =
            "dev.codex.spark_fix.OwoIntegrationSettingsScreen";

    private IntegrationSettingsRouter() {
    }

    public static Screen create(Screen parent) {
        if (FabricLoader.getInstance().isModLoaded("owo")) {
            try {
                Class<?> screenClass = Class.forName(OWO_SCREEN, true,
                        IntegrationSettingsRouter.class.getClassLoader());
                Constructor<?> constructor = screenClass.getDeclaredConstructor(Screen.class);
                constructor.setAccessible(true);
                Object screen = constructor.newInstance(parent);
                if (screen instanceof Screen owoScreen) {
                    return owoScreen;
                }
            } catch (ReflectiveOperationException | LinkageError exception) {
                SparkFixClient.LOGGER.warn(
                        "Could not load optional owo integration settings screen; using vanilla UI.",
                        exception
                );
            }
        }

        return new VanillaIntegrationSettingsScreen(parent);
    }

    public static Screen createLitematica(Screen parent) {
        if (FabricLoader.getInstance().isModLoaded("owo")) {
            try {
                Class<?> screenClass = Class.forName("dev.codex.spark_fix.OwoLitematicaSettingsScreen", true,
                        IntegrationSettingsRouter.class.getClassLoader());
                Constructor<?> constructor = screenClass.getDeclaredConstructor(Screen.class);
                constructor.setAccessible(true);
                Object screen = constructor.newInstance(parent);
                if (screen instanceof Screen owoScreen) return owoScreen;
            } catch (ReflectiveOperationException | LinkageError exception) {
                SparkFixClient.LOGGER.warn("Could not load optional owo Litematica settings screen", exception);
            }
        }
        return new VanillaLitematicaSettingsScreen(parent);
    }
}
