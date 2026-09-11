package cn.reibridge.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BridgeConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("rei_recipe_bridge.json");
    private static BridgeConfig instance = new BridgeConfig();

    public boolean provideVanillaRecipes = true;
    public boolean captureUnlockedRecipes = false;

    private BridgeConfig() {
    }

    public static BridgeConfig get() {
        return instance;
    }

    public static void load() {
        if (!Files.isRegularFile(PATH)) {
            save();
            return;
        }

        try (Reader reader = Files.newBufferedReader(PATH)) {
            BridgeConfig loaded = GSON.fromJson(reader, BridgeConfig.class);
            if (loaded != null) {
                instance = loaded;
            }
        } catch (IOException | RuntimeException exception) {
            System.err.println("[REI Recipe Bridge] Failed to load config: " + exception.getMessage());
        }
    }

    public static void save() {
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(PATH)) {
                GSON.toJson(instance, writer);
            }
        } catch (IOException exception) {
            System.err.println("[REI Recipe Bridge] Failed to save config: " + exception.getMessage());
        }
    }

}
