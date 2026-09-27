package dev.codex.spark_fix;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Editable alias deltas, separate from packaged defaults and primary names. */
final class LitematicaCustomAliases {
    static final String FILE_NAME = "常用别名_自定义.json";

    private final Path file;
    private final List<String> discoveredKeys;
    private final Map<String, List<String>> presetAliases;
    private final Map<String, List<String>> values = new LinkedHashMap<>();
    private boolean writable = true;

    static LitematicaCustomAliases load(List<String> discoveredKeys, Map<String, List<String>> aliases,
                                        Map<String, List<String>> presetAliases, Map<String, List<String>> mainNames) {
        return new LitematicaCustomAliases(FabricLoader.getInstance().getConfigDir().resolve("spark_fix"),
                discoveredKeys, aliases, presetAliases, mainNames);
    }

    LitematicaCustomAliases(Path directory, List<String> discoveredKeys, Map<String, List<String>> aliases,
                            Map<String, List<String>> presetAliases, Map<String, List<String>> mainNames) {
        this.file = directory.resolve(FILE_NAME);
        this.discoveredKeys = List.copyOf(discoveredKeys);
        this.presetAliases = presetAliases;
        if (!Files.exists(file)) return;
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            var object = JsonParser.parseReader(reader).getAsJsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                if (!entry.getValue().isJsonArray()) throw new IllegalArgumentException("Expected an alias array");
                List<String> entries = new ArrayList<>();
                for (JsonElement value : entry.getValue().getAsJsonArray()) {
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                        throw new IllegalArgumentException("Expected an alias string");
                    }
                    entries.add(value.getAsString());
                }
                List<String> cleaned = clean(entry.getKey(), entries, List.of());
                if (!cleaned.isEmpty()) values.put(entry.getKey(), cleaned);
            }
            for (String key : discoveredKeys) {
                Set<String> packaged = names(presetAliases.getOrDefault(key, List.of()));
                List<String> merged = new ArrayList<>();
                // The JSON owns personal aliases; cached personal words removed from it must stay removed.
                for (String alias : aliases.getOrDefault(key, List.of())) {
                    if (packaged.contains(LitematicaAliasPresets.normalizedName(alias))) merged.add(alias);
                }
                merged.addAll(values.getOrDefault(key, List.of()));
                putAliases(aliases, key, clean(key, merged, mainNames.getOrDefault(key, List.of())));
            }
        } catch (IOException | RuntimeException exception) {
            writable = false;
            SparkFixClient.LOGGER.warn("Could not read custom Litematica aliases; preserving the file and cached aliases", exception);
        }
    }

    void save(Map<String, List<String>> aliases, Map<String, List<String>> mainNames) {
        if (!writable) return;
        // Retain entries for temporarily unavailable mods, but export only personal words for loaded options.
        for (String key : discoveredKeys) {
            Set<String> packaged = names(presetAliases.getOrDefault(key, List.of()));
            Set<String> personal = names(values.getOrDefault(key, List.of()));
            List<String> additions = clean(key, aliases.getOrDefault(key, List.of()), mainNames.getOrDefault(key, List.of()))
                    .stream().filter(alias -> {
                        String name = LitematicaAliasPresets.normalizedName(alias);
                        return personal.contains(name) || !packaged.contains(name);
                    }).toList();
            putAliases(values, key, additions);
        }
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), "custom-aliases-", ".tmp");
            String json = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create().toJson(values) + "\n";
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException exception) {
            SparkFixClient.LOGGER.warn("Could not save custom Litematica aliases", exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    SparkFixClient.LOGGER.warn("Could not remove custom alias temporary file", exception);
                }
            }
        }
    }

    private static List<String> clean(String key, List<String> aliases, List<String> mainNames) {
        Set<String> known = names(mainNames);
        String[] identity = key.split("::", 3);
        if (identity.length == 3) known.add(LitematicaAliasPresets.normalizedName(identity[2]));
        List<String> result = new ArrayList<>();
        for (String value : aliases) {
            String alias = value.strip();
            String normalized = LitematicaAliasPresets.normalizedName(alias);
            if (!normalized.isEmpty() && alias.length() <= 256 && known.add(normalized)) result.add(alias);
        }
        return result;
    }

    private static Set<String> names(List<String> aliases) {
        Set<String> result = new HashSet<>();
        aliases.forEach(alias -> result.add(LitematicaAliasPresets.normalizedName(alias)));
        return result;
    }

    private static void putAliases(Map<String, List<String>> target, String key, List<String> aliases) {
        if (aliases.isEmpty()) target.remove(key);
        else target.put(key, aliases);
    }
}
