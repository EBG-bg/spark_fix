package dev.codex.spark_fix;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.HashSet;
import java.util.Set;

/** Keeps the latest settings per source repository; release versions never create new profiles. */
public final class PrinterConfigPersistence {
    private static final Logger LOGGER = LoggerFactory.getLogger("spark_fix");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();
    private static final String SECTION = "litematica-printer";
    private static final String MARKER = "_spark_fix_printer";
    private static final String ARCHIVE = "printer-settings.json";
    private static final Set<Path> BLOCKED_WRITES = new HashSet<>();

    public record Profile(String modId, String source, String version) {
        public Profile {
            source = normalizeSource(source);
        }

        public String key() {
            return modId + "|" + source;
        }
    }

    private PrinterConfigPersistence() { }

    public static synchronized JsonElement load(JsonElement parsed, Path file, Profile profile) {
        if (parsed == null || !parsed.isJsonObject()) return parsed;
        try {
            JsonObject archive = readArchive(file);
            JsonObject selected = select(parsed.getAsJsonObject(), archive, profile);
            remember(archive, profile, selected);
            // Archive first. The identity lives in the main file itself, so interruption
            // between these two atomic writes cannot assign it to the wrong fork.
            writeAtomically(archivePath(file), archive);
            mark(selected, profile);
            if (!selected.equals(parsed)) writeAtomically(file, selected);
            BLOCKED_WRITES.remove(file.toAbsolutePath().normalize());
            return selected;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not select printer settings; preserving existing files {}", file, exception);
            BLOCKED_WRITES.add(file.toAbsolutePath().normalize());
            // Do not let native migration overwrite a different fork after a failed switch.
            return null;
        }
    }

    public static synchronized boolean save(JsonElement current, Path file, Profile profile) {
        if (BLOCKED_WRITES.contains(file.toAbsolutePath().normalize())) return false;
        try {
            JsonObject previous = readObject(file);
            JsonObject archive = readArchive(file);
            JsonObject merged = select(previous, archive, profile);
            merge(merged, current.getAsJsonObject());
            remember(archive, profile, merged);
            writeAtomically(archivePath(file), archive);
            mark(merged, profile);
            writeAtomically(file, merged);
            return true;
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not save printer settings; preserving the existing file {}", file, exception);
            return false;
        }
    }

    private static JsonObject select(JsonObject main, JsonObject archive, Profile profile) {
        JsonObject selected = withoutMarker(main);
        String owner = owner(main);
        if (owner == null || owner.equals(profile.key())) {
            // Same-fork upgrades and manual edits always use the main file, never an old snapshot.
            // An unmarked legacy file has no reliable fork identity: establish a baseline.
            return selected;
        }
        JsonObject profiles = archive.getAsJsonObject("profiles");
        JsonObject previous = profiles.has(owner) ? profiles.getAsJsonObject(owner) : new JsonObject();
        previous.add("config", selected.deepCopy());
        profiles.add(owner, previous);
        if (profiles.has(profile.key())) {
            return withoutMarker(profiles.getAsJsonObject(profile.key()).getAsJsonObject("config"));
        }
        // Another fork's same-name keys and schema can have incompatible meanings.
        // A first visit uses native defaults; the departing settings remain archived.
        return new JsonObject();
    }

    private static void merge(JsonObject base, JsonObject current) {
        for (var entry : current.entrySet()) {
            if (entry.getKey().equals(MARKER)) continue;
            if (entry.getKey().equals(SECTION) && base.has(SECTION)) {
                JsonObject options = base.getAsJsonObject(SECTION);
                for (var option : entry.getValue().getAsJsonObject().entrySet()) {
                    // Complete current values win, including deliberate hotkey field removal.
                    options.add(option.getKey(), option.getValue().deepCopy());
                }
            } else {
                base.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
    }

    private static void remember(JsonObject archive, Profile profile, JsonObject config) {
        JsonObject saved = new JsonObject();
        saved.addProperty("modId", profile.modId());
        saved.addProperty("source", profile.source());
        saved.addProperty("version", profile.version());
        saved.add("config", withoutMarker(config));
        archive.getAsJsonObject("profiles").add(profile.key(), saved);
    }

    private static JsonObject withoutMarker(JsonObject config) {
        JsonObject copy = config.deepCopy();
        copy.remove(MARKER);
        return copy;
    }

    private static String owner(JsonObject config) {
        if (!config.has(MARKER)) return null;
        return config.getAsJsonObject(MARKER).get("profile").getAsString();
    }

    private static void mark(JsonObject config, Profile profile) {
        JsonObject marker = new JsonObject();
        marker.addProperty("profile", profile.key());
        config.add(MARKER, marker);
    }

    private static Path archivePath(Path file) {
        return file.toAbsolutePath().normalize().getParent().resolve("spark_fix").resolve(ARCHIVE);
    }

    private static JsonObject readArchive(Path file) throws IOException {
        JsonObject archive = readObject(archivePath(file));
        if (archive.isEmpty()) {
            archive.addProperty("format", 1);
            archive.add("profiles", new JsonObject());
        }
        if (archive.get("format").getAsInt() != 1 || !archive.get("profiles").isJsonObject()) {
            throw new IOException("Unsupported printer settings archive format");
        }
        return archive;
    }

    private static JsonObject readObject(Path file) throws IOException {
        if (Files.notExists(file)) return new JsonObject();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static void writeAtomically(Path file, JsonObject config) throws IOException {
        Path target = file.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "printer-config-", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(config) + "\n", StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String normalizeSource(String source) {
        try {
            URI uri = URI.create(source.trim());
            if (uri.getHost() == null) return source.trim();
            String host = uri.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            String path = uri.getPath().replaceFirst("/+$", "").replaceFirst("\\.git$", "");
            if (host.equals("github.com") || host.equals("gitlab.com")) {
                path = path.toLowerCase(Locale.ROOT);
                String[] parts = path.split("/");
                if (parts.length >= 3) path = "/" + parts[1] + "/" + parts[2];
            }
            return host + path;
        } catch (IllegalArgumentException exception) {
            return source.trim();
        }
    }
}
