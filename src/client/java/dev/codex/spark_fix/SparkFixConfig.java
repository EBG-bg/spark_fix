package dev.codex.spark_fix;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Small client-only configuration used by the optional compatibility fixes. */
public final class SparkFixConfig {
    public static final int DEFAULT_MAX_REI_CLICKS = 576;
    public static final int MIN_MAX_REI_CLICKS = 1;
    public static final int MAX_MAX_REI_CLICKS = 4096;

    private static final Logger LOGGER = LoggerFactory.getLogger("spark_fix/config");
    private static final String MAX_REI_CLICKS_KEY = "rei.max_clicks";
    private static final String SCAN_ALL_TRANSLATIONS_KEY = "axiom.scan_all_translations";
    private static final String ADOFAIGO_ENABLED_KEY = "adofaigo.enabled";
    private static final String REI_RECIPE_BRIDGE_ENABLED_KEY = "rei_recipe_bridge.enabled";
    private static final String LEGACY_CONFIG_FILE_NAME = "warmaislandfix.properties";

    private static int maxReiClicks = DEFAULT_MAX_REI_CLICKS;
    private static boolean scanAllTranslations;
    private static boolean adofaigoEnabled;
    private static boolean reiRecipeBridgeEnabled;
    private static boolean adofaigoEnabledAtStartup;
    private static boolean reiRecipeBridgeEnabledAtStartup;
    private static boolean loaded;

    private SparkFixConfig() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;

        Path file = configFile();
        boolean migrateLegacyConfig = false;
        if (!Files.isRegularFile(file)) {
            Path legacyFile = legacyConfigFile();
            if (!Files.isRegularFile(legacyFile)) {
                captureStartupValues();
                return;
            }
            file = legacyFile;
            migrateLegacyConfig = true;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
            maxReiClicks = readMaxReiClicks(properties.getProperty(MAX_REI_CLICKS_KEY));
            scanAllTranslations = Boolean.parseBoolean(
                properties.getProperty(SCAN_ALL_TRANSLATIONS_KEY, Boolean.FALSE.toString())
            );
            adofaigoEnabled = Boolean.parseBoolean(
                properties.getProperty(ADOFAIGO_ENABLED_KEY, Boolean.FALSE.toString())
            );
            reiRecipeBridgeEnabled = Boolean.parseBoolean(
                properties.getProperty(REI_RECIPE_BRIDGE_ENABLED_KEY, Boolean.FALSE.toString())
            );
            if (migrateLegacyConfig) {
                try {
                    Files.copy(file, configFile());
                    LOGGER.info("Migrated legacy warmaislandfix configuration to spark_fix.properties.");
                } catch (IOException | RuntimeException exception) {
                    LOGGER.warn("Could not migrate legacy warmaislandfix configuration.", exception);
                }
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not load spark_fix configuration; using defaults.", exception);
        }
        captureStartupValues();
    }

    public static synchronized void save() {
        load();

        Properties properties = new Properties();
        properties.setProperty(MAX_REI_CLICKS_KEY, Integer.toString(maxReiClicks));
        properties.setProperty(SCAN_ALL_TRANSLATIONS_KEY, Boolean.toString(scanAllTranslations));
        properties.setProperty(ADOFAIGO_ENABLED_KEY, Boolean.toString(adofaigoEnabled));
        properties.setProperty(REI_RECIPE_BRIDGE_ENABLED_KEY, Boolean.toString(reiRecipeBridgeEnabled));

        Path file = configFile();
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream output = Files.newOutputStream(file)) {
                properties.store(output, "spark_fix configuration");
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not save spark_fix configuration.", exception);
        }
    }

    public static synchronized int maxReiClicks() {
        load();
        return maxReiClicks;
    }

    public static synchronized void setMaxReiClicks(int value) {
        load();
        maxReiClicks = clampMaxReiClicks(value);
    }

    public static synchronized boolean scanAllTranslations() {
        load();
        return scanAllTranslations;
    }

    public static synchronized void setScanAllTranslations(boolean value) {
        load();
        scanAllTranslations = value;
    }

    public static synchronized boolean adofaigoEnabled() {
        load();
        return adofaigoEnabled;
    }

    public static synchronized void setAdofoigoEnabled(boolean value) {
        load();
        adofaigoEnabled = value;
    }

    public static synchronized boolean adofaigoEnabledAtStartup() {
        load();
        return adofaigoEnabledAtStartup;
    }

    public static synchronized boolean reiRecipeBridgeEnabled() {
        load();
        return reiRecipeBridgeEnabled;
    }

    public static synchronized void setReiRecipeBridgeEnabled(boolean value) {
        load();
        reiRecipeBridgeEnabled = value;
    }

    public static synchronized boolean reiRecipeBridgeEnabledAtStartup() {
        load();
        return reiRecipeBridgeEnabledAtStartup;
    }

    private static void captureStartupValues() {
        adofaigoEnabledAtStartup = adofaigoEnabled;
        reiRecipeBridgeEnabledAtStartup = reiRecipeBridgeEnabled;
    }

    private static int readMaxReiClicks(String value) {
        if (value == null) {
            return DEFAULT_MAX_REI_CLICKS;
        }

        try {
            return clampMaxReiClicks(Integer.parseInt(value.trim()));
        } catch (NumberFormatException exception) {
            LOGGER.warn("Invalid rei.max_clicks value '{}'; using {}.", value, DEFAULT_MAX_REI_CLICKS);
            return DEFAULT_MAX_REI_CLICKS;
        }
    }

    private static int clampMaxReiClicks(int value) {
        return Math.max(MIN_MAX_REI_CLICKS, Math.min(MAX_MAX_REI_CLICKS, value));
    }

    private static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("spark_fix.properties");
    }

    private static Path legacyConfigFile() {
        return FabricLoader.getInstance().getConfigDir().resolve(LEGACY_CONFIG_FILE_NAME);
    }
}
