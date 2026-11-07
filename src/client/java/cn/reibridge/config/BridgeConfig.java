package cn.reibridge.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class BridgeConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("REI Recipe Bridge/config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("rei_recipe_bridge.json");
    private static BridgeConfig instance = new BridgeConfig();
    private static boolean loadFailed;
    private static boolean saveFailed;
    private static String lastError;

    public boolean provideVanillaRecipes = true;
    public boolean captureUnlockedRecipes = false;

    private BridgeConfig() {
    }

    public static BridgeConfig get() {
        return instance;
    }

    public static synchronized void load() {
        // Reopening settings must not replace a draft whose previous save failed.
        if (saveFailed) return;
        if (Files.notExists(PATH)) { trySave(); return; }
        try {
            instance = readOriginal();
            loadFailed = false;
            lastError = null;
        } catch (IOException | RuntimeException exception) {
            loadFailed = true;
            lastError = describe(exception);
            LOGGER.warn("Could not read REI Recipe Bridge configuration; keeping the original file and current values", exception);
        }
    }

    public static void save() { trySave(); }

    public static synchronized boolean saveFailed() { return saveFailed; }
    public static synchronized boolean loadFailed() { return loadFailed; }
    public static synchronized String lastError() { return lastError; }

    /** Returns false without replacing the original if encoding or writing fails. */
    public static synchronized boolean trySave() {
        Path temporary = null;
        try {
            if (loadFailed && !Files.notExists(PATH)) {
                // A repaired original unlocks Retry without discarding edits made in the UI.
                readOriginal();
            }
            String json = GSON.toJson(instance) + "\n";
            Files.createDirectories(PATH.getParent());
            temporary = Files.createTempFile(PATH.getParent(), "rei-recipe-bridge-", ".tmp");
            Files.writeString(temporary, json, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, PATH, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, PATH, StandardCopyOption.REPLACE_EXISTING);
            }
            loadFailed = false;
            saveFailed = false;
            lastError = null;
            return true;
        } catch (IOException | RuntimeException exception) {
            saveFailed = true;
            lastError = describe(exception);
            LOGGER.warn("Could not save REI Recipe Bridge configuration; keeping the original file and current values", exception);
            return false;
        } finally {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); }
                catch (IOException exception) { LOGGER.warn("Could not remove REI Recipe Bridge temporary file {}", temporary, exception); }
            }
        }
    }

    private static BridgeConfig readOriginal() throws IOException {
        try (Reader reader = Files.newBufferedReader(PATH, StandardCharsets.UTF_8)) {
            JsonObject object = JsonParser.parseReader(reader).getAsJsonObject();
            for (String key : new String[]{"provideVanillaRecipes", "captureUnlockedRecipes"}) {
                if (object.has(key) && (!object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isBoolean())) {
                    throw new IllegalArgumentException("Expected a boolean for " + key);
                }
            }
            return GSON.fromJson(object, BridgeConfig.class);
        }
    }

    private static String describe(Exception exception) {
        return exception.getClass().getSimpleName() + (exception.getMessage() == null ? "" : ": " + exception.getMessage());
    }
}
