package dev.codex.spark_fix;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;

/** Imports alias-only deltas while accepting the earlier wrapped preset format. */
final class LitematicaAliasPresets {
    private LitematicaAliasPresets() { }

    static Map<String, List<String>> apply(List<String> discoveredKeys, Map<String, List<String>> aliases,
                                           Set<String> applied, Map<String, List<String>> mainNames) {
        if (discoveredKeys.isEmpty()) return Map.of();
        return apply(FabricLoader.getInstance().getConfigDir().resolve("spark_fix"), discoveredKeys, aliases, applied, mainNames);
    }

    static void apply(Path directory, List<String> discoveredKeys, Map<String, List<String>> aliases, Set<String> applied) {
        apply(directory, discoveredKeys, aliases, applied, Map.of());
    }

    static Map<String, List<String>> apply(Path directory, List<String> discoveredKeys, Map<String, List<String>> aliases,
                                           Set<String> applied, Map<String, List<String>> mainNames) {
        Map<String, List<String>> presetAliases = new LinkedHashMap<>();
        if (!Files.isDirectory(directory)) return presetAliases;
        var available = SparkFixConfig.availableOptionKeys(discoveredKeys);
        List<Path> files;
        try (var paths = Files.list(directory)) {
            files = paths.filter(Files::isRegularFile).filter(path -> {
                String name = path.getFileName().toString();
                return name.startsWith("常用别名_") && name.endsWith(".json")
                        && !name.equals(LitematicaCustomAliases.FILE_NAME);
            }).sorted().toList();
        } catch (IOException | RuntimeException exception) {
            SparkFixClient.LOGGER.warn("Could not list Litematica alias presets", exception);
            return presetAliases;
        }
        for (Path file : files) {
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject preset = JsonParser.parseReader(reader).getAsJsonObject();
                boolean legacy = preset.has("schema_version");
                if (legacy && preset.get("schema_version").getAsInt() != 1) {
                    throw new IllegalArgumentException("Unsupported alias preset schema_version");
                }
                int revision = legacy && preset.has("revision") ? preset.get("revision").getAsInt() : 1;
                if (revision < 1) throw new IllegalArgumentException("Alias preset revision must be positive");
                if (legacy && preset.has("enabled") && !preset.get("enabled").getAsBoolean()) continue;
                JsonObject entries = legacy ? preset.getAsJsonObject("aliases") : preset;
                if (entries == null) throw new IllegalArgumentException("Missing aliases object");
                String prefix = file.getFileName() + "::" + revision + "::";
                for (Map.Entry<String, JsonElement> entry : entries.entrySet()) {
                    String key = available.resolve(entry.getKey());
                    if (key == null) continue;
                    if (!entry.getValue().isJsonArray()) continue;
                    List<String> definitions = new ArrayList<>();
                    for (JsonElement value : entry.getValue().getAsJsonArray()) {
                        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) continue;
                        String alias = value.getAsString().strip();
                        if (!normalizedName(alias).isEmpty() && alias.length() <= 256) definitions.add(alias);
                    }
                    presetAliases.computeIfAbsent(key, ignored -> new ArrayList<>()).addAll(definitions);
                    if (applied.contains(prefix + key) || applied.contains(prefix + entry.getKey())) continue;
                    Set<String> knownNames = new HashSet<>();
                    mainNames.getOrDefault(key, List.of()).forEach(name -> knownNames.add(normalizedName(name)));
                    String[] identity = key.split("::", 3);
                    if (identity.length == 3) knownNames.add(normalizedName(identity[2]));
                    aliases.getOrDefault(key, List.of()).forEach(name -> knownNames.add(normalizedName(name)));
                    LinkedHashSet<String> additions = new LinkedHashSet<>();
                    for (String alias : definitions) {
                        String normalized = normalizedName(alias);
                        if (knownNames.add(normalized)) additions.add(alias);
                    }
                    // Already-present words are processed too; deleting them later must stay effective.
                    applied.add(prefix + key);
                    if (additions.isEmpty()) continue;
                    LinkedHashSet<String> merged = new LinkedHashSet<>(aliases.getOrDefault(key, List.of()));
                    merged.addAll(additions);
                    aliases.put(key, new ArrayList<>(merged));
                }
            } catch (IOException | RuntimeException exception) {
                SparkFixClient.LOGGER.warn("Could not read Litematica alias preset {}", file.getFileName(), exception);
            }
        }
        return presetAliases;
    }

    static String normalizedName(String name) {
        return name.replaceAll("[\\s\\-_—－:：()（）]", "").toLowerCase(Locale.ROOT);
    }
}
