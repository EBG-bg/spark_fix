package dev.codex.spark_fix;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Uses the loaded config class's owner, including nested mods, instead of fork/version names. */
public final class PrinterConfigIdentity {
    private static final ClassValue<Optional<ModContainer>> OWNERS = new ClassValue<>() {
        @Override protected Optional<ModContainer> computeValue(Class<?> type) {
            String resource = type.getName().replace('.', '/') + ".class";
            for (var container : FabricLoader.getInstance().getAllMods()) {
                if (container.findPath(resource).isPresent()) return Optional.of(container);
            }
            return Optional.empty();
        }
    };
    private static final ClassValue<List<Object>> OPTIONS = new ClassValue<>() {
        @Override protected List<Object> computeValue(Class<?> type) {
            List<Object> options = new ArrayList<>();
            try {
                Class<?> base = Class.forName("fi.dy.masa.malilib.config.IConfigBase", false, type.getClassLoader());
                collect(type, base, options);
                var owner = OWNERS.get(type);
                if (owner.isPresent()) {
                    var metadataFile = owner.get().findPath("fabric.mod.json");
                    if (metadataFile.isPresent()) {
                        try (var reader = Files.newBufferedReader(metadataFile.get(), StandardCharsets.UTF_8)) {
                            var entries = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("entrypoints");
                            if (entries != null) {
                                for (String kind : List.of("client", "main")) {
                                    if (!entries.has(kind)) continue;
                                    for (JsonElement entry : entries.getAsJsonArray(kind)) {
                                        String value = entry.isJsonPrimitive() ? entry.getAsString()
                                                : entry.getAsJsonObject().get("value").getAsString();
                                        try {
                                            Class<?> entryType = Class.forName(value.split("::", 2)[0], false,
                                                    type.getClassLoader());
                                            collect(entryType, base, options);
                                        } catch (ReflectiveOperationException | LinkageError exception) {
                                            SparkFixClient.LOGGER.debug("Could not collect printer entrypoint options {}", value, exception);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception | LinkageError exception) {
                SparkFixClient.LOGGER.debug("Could not collect all printer settings", exception);
            }
            return List.copyOf(options);
        }
    };

    private PrinterConfigIdentity() { }

    public static Optional<PrinterConfigPersistence.Profile> forConfigClass(Class<?> type) {
        return OWNERS.get(type).map(container -> {
            var metadata = container.getMetadata();
            String source = metadata.getContact().get("sources")
                    .or(() -> metadata.getContact().get("homepage"))
                    .orElse("class:" + type.getName());
            return new PrinterConfigPersistence.Profile(metadata.getId(), source,
                    metadata.getVersion().getFriendlyString());
        });
    }

    public static List<?> completeOptions(Class<?> type, List<?> original) {
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Object> complete = new ArrayList<>(original.size());
        for (Object option : original) if (seen.add(option)) complete.add(option);
        for (Object option : OPTIONS.get(type)) if (seen.add(option)) complete.add(option);
        return complete;
    }

    private static void collect(Class<?> type, Class<?> base, List<Object> options) {
        for (var field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !base.isAssignableFrom(field.getType())) continue;
            try {
                if (field.trySetAccessible()) {
                    Object option = field.get(null);
                    if (option != null) options.add(option);
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                SparkFixClient.LOGGER.debug("Could not collect printer option {}", field.getName(), exception);
            }
        }
        for (Class<?> nested : type.getDeclaredClasses()) collect(nested, base, options);
    }
}
