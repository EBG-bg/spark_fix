package dev.codex.warmaislandfix;

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
public final class WarmaIslandFixConfig {
    public static final int DEFAULT_MAX_REI_CLICKS = 576;
    public static final int MIN_MAX_REI_CLICKS = 1;
    public static final int MAX_MAX_REI_CLICKS = 4096;

    private static final Logger LOGGER = LoggerFactory.getLogger("warmaislandfix/config");
    private static final String MAX_REI_CLICKS_KEY = "rei.max_clicks";
    private static final String SCAN_ALL_TRANSLATIONS_KEY = "axiom.scan_all_translations";

    private static int maxReiClicks = DEFAULT_MAX_REI_CLICKS;
    private static boolean scanAllTranslations;
    private static boolean loaded;

    private WarmaIslandFixConfig() {
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;

        Path file = configFile();
        if (!Files.isRegularFile(file)) {
            return;
        }

        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
            maxReiClicks = readMaxReiClicks(properties.getProperty(MAX_REI_CLICKS_KEY));
            scanAllTranslations = Boolean.parseBoolean(
                properties.getProperty(SCAN_ALL_TRANSLATIONS_KEY, Boolean.FALSE.toString())
            );
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not load warmaislandfix configuration; using defaults.", exception);
        }
    }

    public static synchronized void save() {
        load();

        Properties properties = new Properties();
        properties.setProperty(MAX_REI_CLICKS_KEY, Integer.toString(maxReiClicks));
        properties.setProperty(SCAN_ALL_TRANSLATIONS_KEY, Boolean.toString(scanAllTranslations));

        Path file = configFile();
        try {
            Files.createDirectories(file.getParent());
            try (OutputStream output = Files.newOutputStream(file)) {
                properties.store(output, "warmaislandfix configuration");
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not save warmaislandfix configuration.", exception);
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
        return FabricLoader.getInstance().getConfigDir().resolve("warmaislandfix.properties");
    }
}
