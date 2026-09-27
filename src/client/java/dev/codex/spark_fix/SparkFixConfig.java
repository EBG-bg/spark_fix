package dev.codex.spark_fix;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.HashSet;

/** Small client-only configuration used by the optional compatibility fixes. */
public final class SparkFixConfig {
    public static final int DEFAULT_MAX_REI_CLICKS = 576;
    public static final int MIN_MAX_REI_CLICKS = 1;
    public static final int MAX_MAX_REI_CLICKS = 4096;
    public static final double MIN_SCROLL_SENSITIVITY = 0.2;
    public static final double MAX_SCROLL_SENSITIVITY = 3.0;
    public static final double MAX_LITEMATICA_SCROLL_SENSITIVITY = 4.0;
    public static final double DEFAULT_SCROLL_SENSITIVITY = 1.0;
    /** Five discrete layout levels. Kept as a double for config/API compatibility. */
    public static final double MIN_LITEMATICA_COMPACTNESS = 1.0;
    public static final double MAX_LITEMATICA_COMPACTNESS = 5.0;
    public static final double DEFAULT_LITEMATICA_COMPACTNESS = 1.0;
    public static final int MAX_ADOFAIGO_LAUNCH_DELAY_SECONDS = 60;

    private static final Logger LOGGER = LoggerFactory.getLogger("spark_fix/config");
    private static final String MAX_REI_CLICKS_KEY = "rei.max_clicks";
    private static final String SCAN_ALL_TRANSLATIONS_KEY = "axiom.scan_all_translations";
    private static final String ADOFAIGO_ENABLED_KEY = "adofaigo.enabled";
    private static final String ADOFAIGO_LAUNCH_DELAY_KEY = "adofaigo.launch_delay_seconds";
    private static final String REI_RECIPE_BRIDGE_ENABLED_KEY = "rei_recipe_bridge.enabled";
    private static final String LITEMATICA_ENABLED_KEY = "litematica.enabled";
    private static final String LITEMATICA_FAVORITES_KEY = "litematica.settings.favorites";
    private static final String LITEMATICA_ORDER_KEY = "litematica.settings.order";
    private static final String LITEMATICA_ALIASES_KEY = "litematica.settings.aliases";
    private static final String LITEMATICA_NAMES_KEY = "litematica.settings.names";
    private static final String LITEMATICA_COLUMNS_KEY = "litematica.settings.columns";
    private static final String SCROLL_SENSITIVITY_KEY = "ui.scroll_sensitivity";
    private static final String LITEMATICA_SCROLL_SENSITIVITY_KEY = "litematica.ui.scroll_sensitivity";
    private static final String LITEMATICA_COMPACTNESS_KEY = "litematica.ui.compactness";
    private static final String LITEMATICA_HIDDEN_MODULES_KEY = "litematica.ui.hidden_modules";
    private static final String LITEMATICA_FILTER_FAVORITES_KEY = "litematica.ui.filter_favorites";
    private static final String LITEMATICA_ALIAS_PRESETS_APPLIED_KEY = "litematica.settings.alias_presets_applied";
    private static final String LITEMATICA_CATEGORY_ORDERED_KEY = "litematica.settings.category_ordered";
    private static final String LEGACY_CONFIG_FILE_NAME = "warmaislandfix.properties";
    private static final int CONFIG_FIELD_PART_LENGTH = 1800;

    private static int maxReiClicks = DEFAULT_MAX_REI_CLICKS;
    private static boolean scanAllTranslations;
    private static boolean adofaigoEnabled;
    private static int adofaigoLaunchDelaySeconds;
    private static boolean reiRecipeBridgeEnabled;
    private static boolean litematicaEnabled;
    private static boolean adofaigoEnabledAtStartup;
    private static boolean reiRecipeBridgeEnabledAtStartup;
    private static boolean litematicaEnabledAtStartup;
    private static boolean loaded;
    private static double scrollSensitivity = DEFAULT_SCROLL_SENSITIVITY;
    private static double litematicaScrollSensitivity = DEFAULT_SCROLL_SENSITIVITY;
    private static double litematicaCompactness = DEFAULT_LITEMATICA_COMPACTNESS;
    private static List<String> litematicaFavorites;
    private static List<String> litematicaSettingsOrder;
    private static Map<String, List<String>> litematicaAliases;
    private static Map<String, List<String>> litematicaNames;
    private static Map<String, List<String>> litematicaColumns;
    private static List<String> litematicaHiddenModules;
    private static boolean litematicaFilterFavorites;
    private static List<String> litematicaAliasPresetsApplied;
    private static List<String> litematicaCategoryOrdered;

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
            scrollSensitivity = readScrollSensitivity(properties, SCROLL_SENSITIVITY_KEY, MAX_SCROLL_SENSITIVITY);
            litematicaScrollSensitivity = readScrollSensitivity(properties, LITEMATICA_SCROLL_SENSITIVITY_KEY,
                    MAX_LITEMATICA_SCROLL_SENSITIVITY);
            litematicaCompactness = readLitematicaCompactness(properties.getProperty(LITEMATICA_COMPACTNESS_KEY));
            scanAllTranslations = Boolean.parseBoolean(
                properties.getProperty(SCAN_ALL_TRANSLATIONS_KEY, Boolean.FALSE.toString())
            );
            adofaigoEnabled = Boolean.parseBoolean(
                properties.getProperty(ADOFAIGO_ENABLED_KEY, Boolean.FALSE.toString())
            );
            try {
                adofaigoLaunchDelaySeconds = Math.clamp(Integer.parseInt(
                        properties.getProperty(ADOFAIGO_LAUNCH_DELAY_KEY, "0").trim()),
                        0, MAX_ADOFAIGO_LAUNCH_DELAY_SECONDS);
            } catch (NumberFormatException ignored) {
                adofaigoLaunchDelaySeconds = 0;
            }
            reiRecipeBridgeEnabled = Boolean.parseBoolean(
                properties.getProperty(REI_RECIPE_BRIDGE_ENABLED_KEY, Boolean.FALSE.toString())
            );
            litematicaEnabled = Boolean.parseBoolean(
                properties.getProperty(LITEMATICA_ENABLED_KEY, Boolean.FALSE.toString())
            );
            litematicaFavorites = readStringList(properties, LITEMATICA_FAVORITES_KEY);
            litematicaSettingsOrder = readStringList(properties, LITEMATICA_ORDER_KEY);
            litematicaAliases = readStringListMap(properties, LITEMATICA_ALIASES_KEY);
            if (litematicaAliases == null) {
                Map<String, String> legacyAliases = readStringMap(properties, LITEMATICA_ALIASES_KEY);
                litematicaAliases = new LinkedHashMap<>();
                if (legacyAliases != null) {
                    for (Map.Entry<String, String> entry : legacyAliases.entrySet()) {
                        litematicaAliases.put(entry.getKey(), List.of(entry.getValue()));
                    }
                }
            }
            litematicaNames = readStringListMap(properties, LITEMATICA_NAMES_KEY);
            litematicaColumns = readStringListMap(properties, LITEMATICA_COLUMNS_KEY);
            litematicaHiddenModules = readStringList(properties, LITEMATICA_HIDDEN_MODULES_KEY);
            litematicaFilterFavorites = Boolean.parseBoolean(properties.getProperty(LITEMATICA_FILTER_FAVORITES_KEY, "false"));
            litematicaAliasPresetsApplied = readStringList(properties, LITEMATICA_ALIAS_PRESETS_APPLIED_KEY);
            litematicaCategoryOrdered = readStringList(properties, LITEMATICA_CATEGORY_ORDERED_KEY);
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
        properties.setProperty(SCROLL_SENSITIVITY_KEY, Double.toString(scrollSensitivity));
        properties.setProperty(LITEMATICA_SCROLL_SENSITIVITY_KEY, Double.toString(litematicaScrollSensitivity));
        properties.setProperty(LITEMATICA_COMPACTNESS_KEY, Double.toString(litematicaCompactness));
        properties.setProperty(SCAN_ALL_TRANSLATIONS_KEY, Boolean.toString(scanAllTranslations));
        properties.setProperty(ADOFAIGO_ENABLED_KEY, Boolean.toString(adofaigoEnabled));
        properties.setProperty(ADOFAIGO_LAUNCH_DELAY_KEY, Integer.toString(adofaigoLaunchDelaySeconds));
        properties.setProperty(REI_RECIPE_BRIDGE_ENABLED_KEY, Boolean.toString(reiRecipeBridgeEnabled));
        properties.setProperty(LITEMATICA_ENABLED_KEY, Boolean.toString(litematicaEnabled));
        if (litematicaFavorites != null) writeStringList(properties, LITEMATICA_FAVORITES_KEY, litematicaFavorites);
        if (litematicaSettingsOrder != null) writeStringList(properties, LITEMATICA_ORDER_KEY, litematicaSettingsOrder);
        if (litematicaAliases != null) writeStringListMap(properties, LITEMATICA_ALIASES_KEY, litematicaAliases);
        if (litematicaNames != null) writeStringListMap(properties, LITEMATICA_NAMES_KEY, litematicaNames);
        if (litematicaColumns != null) writeStringListMap(properties, LITEMATICA_COLUMNS_KEY, litematicaColumns);
        if (litematicaHiddenModules != null) writeStringList(properties, LITEMATICA_HIDDEN_MODULES_KEY, litematicaHiddenModules);
        properties.setProperty(LITEMATICA_FILTER_FAVORITES_KEY, Boolean.toString(litematicaFilterFavorites));
        if (litematicaAliasPresetsApplied != null) writeStringList(properties,
                LITEMATICA_ALIAS_PRESETS_APPLIED_KEY, litematicaAliasPresetsApplied);
        if (litematicaCategoryOrdered != null) writeStringList(properties, LITEMATICA_CATEGORY_ORDERED_KEY, litematicaCategoryOrdered);

        Path file = configFile();
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "spark_fix-", ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "spark_fix configuration");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not save spark_fix configuration.", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    LOGGER.warn("Could not remove spark_fix temporary configuration.", exception);
                }
            }
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

    public static synchronized int adofaigoLaunchDelaySeconds() {
        load();
        return adofaigoLaunchDelaySeconds;
    }

    public static synchronized void setAdofoigoLaunchDelaySeconds(int seconds) {
        load();
        adofaigoLaunchDelaySeconds = Math.clamp(seconds, 0, MAX_ADOFAIGO_LAUNCH_DELAY_SECONDS);
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

    public static synchronized boolean litematicaEnabled() {
        load();
        return litematicaEnabled;
    }

    public static synchronized void setLitematicaEnabled(boolean value) {
        load();
        litematicaEnabled = value;
    }

    public static synchronized boolean litematicaEnabledAtStartup() {
        load();
        return litematicaEnabledAtStartup;
    }

    public static synchronized List<String> litematicaAliasPresetsApplied() {
        load();
        return litematicaAliasPresetsApplied == null ? List.of() : List.copyOf(litematicaAliasPresetsApplied);
    }

    public static synchronized List<String> litematicaCategoryOrdered() {
        load();
        return litematicaCategoryOrdered == null ? List.of() : List.copyOf(litematicaCategoryOrdered);
    }

    public static synchronized void setLitematicaCategoryOrdered(List<String> groups) {
        load();
        litematicaCategoryOrdered = distinctKeys(groups);
    }

    public static synchronized void setLitematicaAliasPresetsApplied(List<String> applied) {
        load();
        litematicaAliasPresetsApplied = distinctKeys(applied);
    }

    public static synchronized List<String> litematicaHiddenModules(List<String> discoveredIds) {
        load();
        if (litematicaHiddenModules == null) return List.of();
        Set<String> available = new HashSet<>(discoveredIds);
        return litematicaHiddenModules.stream().filter(available::contains).toList();
    }

    public static synchronized void setLitematicaHiddenModules(List<String> hiddenIds) {
        load();
        litematicaHiddenModules = distinctKeys(hiddenIds);
    }

    public static synchronized boolean litematicaFilterFavorites() {
        load();
        return litematicaFilterFavorites;
    }

    public static synchronized void setLitematicaFilterFavorites(boolean value) {
        load();
        litematicaFilterFavorites = value;
    }

    public static synchronized List<String> litematicaFavorites(List<String> discoveredKeys) {
        load();
        return List.copyOf(visibleKeys(litematicaFavorites, availableOptionKeys(discoveredKeys)));
    }

    public static synchronized List<String> litematicaSettingsOrder(List<String> discoveredKeys) {
        load();
        List<String> defaults = distinctKeys(discoveredKeys);
        List<String> result = visibleKeys(litematicaSettingsOrder, availableOptionKeys(defaults));
        for (String key : defaults) {
            if (!result.contains(key)) result.add(key);
        }
        return List.copyOf(result);
    }

    public static synchronized void setLitematicaSettingsOrder(List<String> favorites, List<String> settingsOrder) {
        load();
        litematicaFavorites = distinctKeys(favorites);
        litematicaSettingsOrder = distinctKeys(settingsOrder);
    }

    public static synchronized Map<String, List<String>> litematicaColumns(List<String> discoveredKeys) {
        load();
        AvailableOptionKeys available = availableOptionKeys(discoveredKeys);
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (litematicaColumns != null) {
            litematicaColumns.forEach((layout, keys) -> {
                List<String> retained = visibleKeys(keys, available);
                if (!retained.isEmpty()) result.put(layout, retained);
            });
        }
        return copyStringListMap(result);
    }

    public static synchronized void setLitematicaColumns(Map<String, List<String>> columns) {
        load();
        litematicaColumns = copyStringListMap(columns);
    }

    public static synchronized Map<String, List<String>> litematicaAliases(List<String> discoveredKeys) {
        load();
        return visibleValues(litematicaAliases, availableOptionKeys(discoveredKeys));
    }

    public static synchronized void setLitematicaAliases(Map<String, List<String>> aliases) {
        load();
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (aliases != null) {
            for (Map.Entry<String, List<String>> entry : aliases.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().isBlank()) {
                    List<String> values = distinctKeys(entry.getValue());
                    if (!values.isEmpty()) result.put(entry.getKey(), values);
                }
            }
        }
        litematicaAliases = result;
    }

    public static synchronized Map<String, List<String>> litematicaNames(List<String> discoveredKeys) {
        load();
        return visibleValues(litematicaNames, availableOptionKeys(discoveredKeys));
    }

    public static synchronized void setLitematicaNames(Map<String, List<String>> names) {
        load();
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (names != null) {
            for (Map.Entry<String, List<String>> entry : names.entrySet()) {
                if (entry.getKey() != null && !entry.getKey().isBlank()) {
                    List<String> values = distinctKeys(entry.getValue());
                    if (!values.isEmpty()) result.put(entry.getKey(), values);
                }
            }
        }
        litematicaNames = result;
    }

    public static synchronized void setLitematicaSettingsSnapshot(List<String> favorites, List<String> order,
            Map<String, List<String>> names, Map<String, List<String>> aliases,
            Map<String, List<String>> columns, List<String> discoveredKeys) {
        load();
        AvailableOptionKeys available = availableOptionKeys(discoveredKeys);
        litematicaFavorites = retainUndiscovered(favorites, litematicaFavorites, available);
        litematicaSettingsOrder = retainUndiscovered(order, litematicaSettingsOrder, available);
        litematicaNames = retainUndiscoveredValues(names, litematicaNames, available);
        litematicaAliases = retainUndiscoveredValues(aliases, litematicaAliases, available);
        Map<String, List<String>> mergedColumns = copyStringListMap(columns);
        if (litematicaColumns != null) {
            litematicaColumns.forEach((layout, keys) -> {
                List<String> missing = keys.stream().filter(key -> available.resolve(key) == null).toList();
                if (missing.isEmpty()) return;
                List<String> merged = new ArrayList<>(mergedColumns.getOrDefault(layout, List.of()));
                merged.addAll(missing);
                mergedColumns.put(layout, distinctKeys(merged));
            });
        }
        litematicaColumns = mergedColumns;
    }

    private static List<String> visibleKeys(List<String> saved, AvailableOptionKeys available) {
        List<String> result = new ArrayList<>();
        if (saved == null) return result;
        for (String key : saved) {
            String resolved = available.resolve(key);
            if (resolved != null && !result.contains(resolved)) result.add(resolved);
        }
        return result;
    }

    private static Map<String, List<String>> visibleValues(Map<String, List<String>> saved, AvailableOptionKeys available) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (saved != null) saved.forEach((key, values) -> {
            String resolved = available.resolve(key);
            if (resolved != null && !values.isEmpty()) result.putIfAbsent(resolved, distinctKeys(values));
        });
        return result;
    }

    private static List<String> retainUndiscovered(List<String> current, List<String> saved, AvailableOptionKeys available) {
        List<String> result = distinctKeys(current);
        if (saved != null) {
            for (String key : saved) {
                if (available.resolve(key) == null && !result.contains(key)) result.add(key);
            }
        }
        return result;
    }

    private static Map<String, List<String>> retainUndiscoveredValues(Map<String, List<String>> current,
            Map<String, List<String>> saved, AvailableOptionKeys available) {
        Map<String, List<String>> result = copyStringListMap(current);
        if (saved != null) saved.forEach((key, values) -> {
            if (available.resolve(key) == null) result.putIfAbsent(key, values);
        });
        return result;
    }

    private record OptionIdentity(String group, String name) { }

    record AvailableOptionKeys(Set<String> exact, Map<OptionIdentity, String> unique) {
        String resolve(String key) {
            if (exact.contains(key)) return key;
            OptionIdentity identity = optionIdentity(key);
            return identity == null ? null : unique.get(identity);
        }
    }

    static AvailableOptionKeys availableOptionKeys(List<String> discoveredKeys) {
        Set<String> exact = new LinkedHashSet<>(distinctKeys(discoveredKeys));
        Map<OptionIdentity, String> unique = new LinkedHashMap<>();
        Set<OptionIdentity> ambiguous = new HashSet<>();
        for (String key : exact) {
            OptionIdentity identity = optionIdentity(key);
            if (identity != null && unique.putIfAbsent(identity, key) != null) ambiguous.add(identity);
        }
        for (OptionIdentity identity : ambiguous) unique.remove(identity);
        return new AvailableOptionKeys(exact, unique);
    }

    private static OptionIdentity optionIdentity(String key) {
        if (key == null) return null;
        int first = key.indexOf("::");
        int second = first < 0 ? -1 : key.indexOf("::", first + 2);
        if (first <= 0 || second <= first + 2 || second + 2 >= key.length()) return null;
        try {
            Integer.parseInt(key.substring(first + 2, second));
        } catch (NumberFormatException ignored) {
            return null;
        }
        return new OptionIdentity(key.substring(0, first), key.substring(second + 2));
    }

    private static Map<String, List<String>> copyStringListMap(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : source.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return copy;
    }

    private static void captureStartupValues() {
        adofaigoEnabledAtStartup = adofaigoEnabled;
        reiRecipeBridgeEnabledAtStartup = reiRecipeBridgeEnabled;
        litematicaEnabledAtStartup = litematicaEnabled;
    }

    public static synchronized double scrollSensitivity() {
        load();
        return scrollSensitivity;
    }

    public static synchronized void setScrollSensitivity(double value) {
        load();
        scrollSensitivity = clampScrollSensitivity(value, MAX_SCROLL_SENSITIVITY);
    }

    public static synchronized double litematicaScrollSensitivity() {
        load();
        return litematicaScrollSensitivity;
    }

    public static synchronized void setLitematicaScrollSensitivity(double value) {
        load();
        litematicaScrollSensitivity = clampScrollSensitivity(value, MAX_LITEMATICA_SCROLL_SENSITIVITY);
    }

    public static synchronized double litematicaCompactness() {
        load();
        return litematicaCompactness;
    }

    public static synchronized void setLitematicaCompactness(double value) {
        load();
        litematicaCompactness = clampLitematicaCompactness(value);
    }

    private static double readScrollSensitivity(Properties properties, String key, double maximum) {
        String value = properties.getProperty(key);
        if (value == null) return DEFAULT_SCROLL_SENSITIVITY;
        try {
            return clampScrollSensitivity(Double.parseDouble(value.trim()), maximum);
        } catch (NumberFormatException exception) {
            LOGGER.warn("Invalid {}; using the default.", key);
            return DEFAULT_SCROLL_SENSITIVITY;
        }
    }

    private static double clampScrollSensitivity(double value, double maximum) {
        if (!Double.isFinite(value)) return DEFAULT_SCROLL_SENSITIVITY;
        return Math.round(Math.max(MIN_SCROLL_SENSITIVITY,
                Math.min(maximum, value)) * 10.0) / 10.0;
    }

    private static double readLitematicaCompactness(String value) {
        if (value == null) return DEFAULT_LITEMATICA_COMPACTNESS;
        try {
            return clampLitematicaCompactness(Double.parseDouble(value.trim()));
        } catch (NumberFormatException exception) {
            LOGGER.warn("Invalid litematica.ui.compactness; using the default.");
            return DEFAULT_LITEMATICA_COMPACTNESS;
        }
    }

    private static double clampLitematicaCompactness(double value) {
        if (!Double.isFinite(value)) return DEFAULT_LITEMATICA_COMPACTNESS;
        // Round old continuous values to the nearest of the five new levels.
        return Math.max(MIN_LITEMATICA_COMPACTNESS,
                Math.min(MAX_LITEMATICA_COMPACTNESS, Math.rint(value)));
    }

    private static List<String> distinctKeys(List<String> keys) {
        if (keys == null || keys.isEmpty()) return new ArrayList<>();
        LinkedHashSet<String> distinct = new LinkedHashSet<>();
        for (String key : keys) {
            if (key != null && !key.isEmpty()) distinct.add(key);
        }
        return new ArrayList<>(distinct);
    }

    private static List<String> readStringList(Properties properties, String field) {
        String encoded = readConfigField(properties, field);
        if (encoded == null) return null;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            try (var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
                int count = input.readInt();
                if (count < 0 || count > input.available() / Integer.BYTES) {
                    throw new IOException("Invalid item count");
                }
                List<String> values = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    int length = input.readInt();
                    if (length < 0 || length > input.available()) throw new IOException("Invalid item length");
                    byte[] value = new byte[length];
                    input.readFully(value);
                    values.add(new String(value, StandardCharsets.UTF_8));
                }
                if (input.available() != 0) throw new IOException("Unexpected trailing data");
                return distinctKeys(values);
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Invalid {} configuration; using the discovered defaults.", field);
            return null;
        }
    }

    private static Map<String, String> readStringMap(Properties properties, String field) {
        String encoded = readConfigField(properties, field);
        if (encoded == null) return null;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            try (var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
                int count = input.readInt();
                if (count < 0 || count > 10000) throw new IOException("Invalid map count");
                Map<String, String> values = new LinkedHashMap<>();
                for (int i = 0; i < count; i++) {
                    String key = input.readUTF();
                    String value = input.readUTF();
                    if (!key.isBlank() && !value.isBlank()) values.put(key, value.trim());
                }
                if (input.available() != 0) throw new IOException("Unexpected trailing data");
                return values;
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Invalid {} configuration; using no aliases.", field);
            return null;
        }
    }

    private static String readConfigField(Properties properties, String field) {
        String value = properties.getProperty(field);
        if (value != null) return value;
        String firstPart = field + ".part.0";
        if (properties.getProperty(firstPart) == null) return null;
        String prefix = field + ".part.";
        int partCount = 0;
        for (String name : properties.stringPropertyNames()) {
            if (!name.startsWith(prefix)) continue;
            try {
                int part = Integer.parseInt(name.substring(prefix.length()));
                if (part >= 0 && name.equals(prefix + part)) partCount++;
            } catch (NumberFormatException ignored) {
                // Ignore unrelated names, as removeConfigPartsAfter does.
            }
        }
        StringBuilder combined = new StringBuilder();
        for (int part = 0; part < partCount; part++) {
            String piece = properties.getProperty(prefix + part);
            if (piece == null) {
                LOGGER.warn("Incomplete {} configuration; using the discovered defaults.", field);
                return null;
            }
            combined.append(piece);
        }
        return combined.toString();
    }

    private static void writeStringList(Properties properties, String field, List<String> values) {
        List<String> distinct = distinctKeys(values);
        byte[] bytes;
        try (var output = new java.io.ByteArrayOutputStream();
             var data = new java.io.DataOutputStream(output)) {
            data.writeInt(distinct.size());
            for (String value : distinct) {
                byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
                data.writeInt(encoded.length);
                data.write(encoded);
            }
            data.flush();
            bytes = output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode " + field, exception);
        }
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        if (encoded.length() <= CONFIG_FIELD_PART_LENGTH) {
            properties.setProperty(field, encoded);
            removeConfigParts(properties, field);
            return;
        }
        for (int offset = 0, part = 0; offset < encoded.length(); offset += CONFIG_FIELD_PART_LENGTH, part++) {
            int end = Math.min(encoded.length(), offset + CONFIG_FIELD_PART_LENGTH);
            properties.setProperty(field + ".part." + part, encoded.substring(offset, end));
        }
        properties.remove(field);
        removeConfigPartsAfter(properties, field, (encoded.length() + CONFIG_FIELD_PART_LENGTH - 1) / CONFIG_FIELD_PART_LENGTH);
    }

    private static void writeStringMap(Properties properties, String field, Map<String, String> values) {
        byte[] bytes;
        try (var output = new java.io.ByteArrayOutputStream();
             var data = new java.io.DataOutputStream(output)) {
            Map<String, String> clean = new LinkedHashMap<>();
            if (values != null) {
                for (Map.Entry<String, String> entry : values.entrySet()) {
                    if (entry.getKey() != null && !entry.getKey().isBlank()
                            && entry.getValue() != null && !entry.getValue().isBlank()) {
                        clean.put(entry.getKey(), entry.getValue().trim());
                    }
                }
            }
            data.writeInt(clean.size());
            for (Map.Entry<String, String> entry : clean.entrySet()) {
                data.writeUTF(entry.getKey());
                data.writeUTF(entry.getValue());
            }
            data.flush();
            bytes = output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode " + field, exception);
        }
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        if (encoded.length() <= CONFIG_FIELD_PART_LENGTH) {
            properties.setProperty(field, encoded);
            removeConfigParts(properties, field);
            return;
        }
        removeConfigParts(properties, field);
        for (int offset = 0, part = 0; offset < encoded.length(); offset += CONFIG_FIELD_PART_LENGTH, part++) {
            int end = Math.min(encoded.length(), offset + CONFIG_FIELD_PART_LENGTH);
            properties.setProperty(field + ".part." + part, encoded.substring(offset, end));
        }
    }

    private static Map<String, List<String>> readStringListMap(Properties properties, String field) {
        String encoded = readConfigField(properties, field);
        if (encoded == null) return null;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            try (var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes))) {
                int count = input.readInt();
                if (count < 0 || count > 10000) throw new IOException("Invalid map count");
                Map<String, List<String>> values = new LinkedHashMap<>();
                for (int i = 0; i < count; i++) {
                    String key = input.readUTF();
                    int valueCount = input.readInt();
                    if (valueCount < 0 || valueCount > 10000) throw new IOException("Invalid value count");
                    List<String> list = new ArrayList<>();
                    for (int j = 0; j < valueCount; j++) list.add(input.readUTF());
                    if (!key.isBlank() && !list.isEmpty()) values.put(key, distinctKeys(list));
                }
                if (input.available() != 0) throw new IOException("Unexpected trailing data");
                return values;
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Invalid {} configuration; using no values.", field);
            return null;
        }
    }

    private static void writeStringListMap(Properties properties, String field,
                                           Map<String, List<String>> values) {
        byte[] bytes;
        try (var output = new java.io.ByteArrayOutputStream();
             var data = new java.io.DataOutputStream(output)) {
            Map<String, List<String>> clean = new LinkedHashMap<>();
            if (values != null) {
                for (Map.Entry<String, List<String>> entry : values.entrySet()) {
                    if (entry.getKey() == null || entry.getKey().isBlank()) continue;
                    List<String> list = distinctKeys(entry.getValue());
                    if (!list.isEmpty()) clean.put(entry.getKey(), list);
                }
            }
            data.writeInt(clean.size());
            for (Map.Entry<String, List<String>> entry : clean.entrySet()) {
                data.writeUTF(entry.getKey());
                data.writeInt(entry.getValue().size());
                for (String value : entry.getValue()) data.writeUTF(value);
            }
            data.flush();
            bytes = output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not encode " + field, exception);
        }
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        if (encoded.length() <= CONFIG_FIELD_PART_LENGTH) {
            properties.setProperty(field, encoded);
            removeConfigParts(properties, field);
            return;
        }
        for (int offset = 0, part = 0; offset < encoded.length(); offset += CONFIG_FIELD_PART_LENGTH, part++) {
            int end = Math.min(encoded.length(), offset + CONFIG_FIELD_PART_LENGTH);
            properties.setProperty(field + ".part." + part, encoded.substring(offset, end));
        }
        properties.remove(field);
        removeConfigPartsAfter(properties, field,
                (encoded.length() + CONFIG_FIELD_PART_LENGTH - 1) / CONFIG_FIELD_PART_LENGTH);
    }

    private static void removeConfigParts(Properties properties, String field) {
        removeConfigPartsAfter(properties, field, 0);
    }

    private static void removeConfigPartsAfter(Properties properties, String field, int firstPartToRemove) {
        String prefix = field + ".part.";
        for (String name : new ArrayList<>(properties.stringPropertyNames())) {
            if (!name.startsWith(prefix)) continue;
            try {
                if (Integer.parseInt(name.substring(prefix.length())) >= firstPartToRemove) {
                    properties.remove(name);
                }
            } catch (NumberFormatException ignored) {
                // Leave unrelated property names with the same prefix untouched.
            }
        }
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
