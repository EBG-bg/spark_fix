package dev.codex.spark_fix;

import net.fabricmc.loader.api.FabricLoader;

import java.lang.reflect.Constructor;

/** Keeps REI implementation types out of the ordinary settings screens' class-loading path. */
final class ReiSettingsRouter {
    private static final String REI_SETTINGS =
            "dev.codex.spark_fix.ReiInlineSettingsImpl";

    private ReiSettingsRouter() {
    }

    static ReiInlineSettings create() {
        if (!FabricLoader.getInstance().isModLoaded("roughlyenoughitems")) {
            return null;
        }

        try {
            Class<?> settingsClass = Class.forName(REI_SETTINGS, true,
                    ReiSettingsRouter.class.getClassLoader());
            Constructor<?> constructor = settingsClass.getDeclaredConstructor();
            constructor.setAccessible(true);
            return (ReiInlineSettings) constructor.newInstance();
        } catch (ReflectiveOperationException | LinkageError exception) {
            SparkFixClient.LOGGER.warn("Could not load inline REI Recipe Bridge settings.", exception);
            return null;
        }
    }
}
