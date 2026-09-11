package cn.reibridge.recipe;

import cn.reibridge.config.BridgeConfig;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.client.registry.display.reason.DisplayAdditionReason;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookRemovePacket;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RecipeCaptureStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("REI Recipe Bridge");
    private static final int MAX_CACHE_BYTES = 64 * 1024 * 1024;
    private static final Path DIRECTORY = FabricLoader.getInstance().getConfigDir()
            .resolve("rei_recipe_bridge")
            .resolve("recipes");

    private static ClientPacketListener loadedConnection;
    private static String loadedServer;
    private static final Map<Integer, ClientboundRecipeBookAddPacket.Entry> entries = new LinkedHashMap<>();
    private static int lastReceivedCount;
    private static int lastMergedCount;
    private static int lastRegisteredCount;

    private RecipeCaptureStore() {
    }

    public static synchronized ClientboundRecipeBookAddPacket merge(
            ClientPacketListener connection,
            ClientboundRecipeBookAddPacket packet
    ) {
        if (!BridgeConfig.get().captureUnlockedRecipes || !load(connection)) {
            return packet;
        }

        boolean firstPacket = loadedConnection != connection;
        loadedConnection = connection;
        int cachedBefore = entries.size();
        LinkedHashMap<Integer, ClientboundRecipeBookAddPacket.Entry> merged = new LinkedHashMap<>(entries);
        for (ClientboundRecipeBookAddPacket.Entry entry : packet.entries()) {
            merged.put(entry.contents().id().index(), entry);
            entries.put(entry.contents().id().index(), quiet(entry));
        }
        lastReceivedCount = packet.entries().size();
        lastMergedCount = merged.size();
        save(connection);

        LOGGER.info(
                "Recipe packet from {}: received={}, cachedBefore={}, merged={}, replace={}",
                loadedServer,
                lastReceivedCount,
                cachedBefore,
                lastMergedCount,
                packet.replace()
        );

        if (!firstPacket && !packet.replace()) {
            return packet;
        }
        return new ClientboundRecipeBookAddPacket(new ArrayList<>(merged.values()), packet.replace());
    }

    public static synchronized void remove(
            ClientPacketListener connection,
            ClientboundRecipeBookRemovePacket packet
    ) {
        if (!BridgeConfig.get().captureUnlockedRecipes || !load(connection)) {
            return;
        }
        boolean changed = packet.recipes().stream()
                .map(RecipeDisplayId::index)
                .map(entries::remove)
                .anyMatch(entry -> entry != null);
        if (changed) {
            save(connection);
            LOGGER.info(
                    "Removed recipe displays for {}; cached={}",
                    loadedServer,
                    entries.size()
            );
        }
    }

    public static synchronized void captureCurrentRecipeBook() {
        if (!BridgeConfig.get().captureUnlockedRecipes) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null || minecraft.player == null || !load(connection)) {
            return;
        }

        boolean changed = false;
        for (RecipeCollection collection : minecraft.player.getRecipeBook().getCollections()) {
            for (RecipeDisplayEntry display : collection.getRecipes()) {
                ClientboundRecipeBookAddPacket.Entry previous = entries.put(
                        display.id().index(),
                        new ClientboundRecipeBookAddPacket.Entry(display, false, false)
                );
                changed |= previous == null || !previous.contents().equals(display);
            }
        }
        if (changed) {
            save(connection);
            LOGGER.info(
                    "Captured current recipe book for {}; cached={}",
                    loadedServer,
                    entries.size()
            );
        }
    }

    public static synchronized void registerCachedDisplays(DisplayRegistry registry) {
        if (!BridgeConfig.get().captureUnlockedRecipes) {
            return;
        }
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null || !load(connection)) {
            return;
        }
        for (ClientboundRecipeBookAddPacket.Entry entry : entries.values()) {
            RecipeDisplayEntry contents = entry.contents();
            registry.addWithReason(
                    contents.display(),
                    DisplayAdditionReason.RECIPE_MANAGER,
                    DisplayAdditionReason.withId(contents.id())
            );
        }
        lastRegisteredCount = entries.size();
        LOGGER.info(
                "Registered {} cached recipe displays with REI for {}",
                lastRegisteredCount,
                loadedServer
        );
    }

    public static synchronized int currentServerCount() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null || !load(connection)) {
            return 0;
        }
        return entries.size();
    }

    public static synchronized int lastReceivedCount() {
        return lastReceivedCount;
    }

    public static synchronized int lastMergedCount() {
        return lastMergedCount;
    }

    public static synchronized int lastRegisteredCount() {
        return lastRegisteredCount;
    }

    private static boolean load(ClientPacketListener connection) {
        String server = currentServer(connection);
        if (server == null) {
            return false;
        }
        if (server.equals(loadedServer) && (loadedConnection == null || loadedConnection == connection)) {
            return true;
        }

        entries.clear();
        loadedServer = server;
        loadedConnection = null;
        Path path = cachePath(server);
        if (!Files.isRegularFile(path)) {
            return true;
        }

        try {
            long size = Files.size(path);
            if (size <= 0 || size > MAX_CACHE_BYTES) {
                throw new IOException("invalid cache size: " + size);
            }
            ByteBuf bytes = Unpooled.wrappedBuffer(Files.readAllBytes(path));
            try {
                RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(bytes, connection.registryAccess());
                ClientboundRecipeBookAddPacket packet = ClientboundRecipeBookAddPacket.STREAM_CODEC.decode(buffer);
                for (ClientboundRecipeBookAddPacket.Entry entry : packet.entries()) {
                    entries.put(entry.contents().id().index(), quiet(entry));
                }
                LOGGER.info(
                        "Loaded {} cached recipe displays for {}",
                        entries.size(),
                        loadedServer
                );
            } finally {
                bytes.release();
            }
        } catch (IOException | RuntimeException exception) {
            entries.clear();
            System.err.println("[REI Recipe Bridge] Failed to load captured recipes: " + exception.getMessage());
        }
        return true;
    }

    private static void save(ClientPacketListener connection) {
        Path path = cachePath(loadedServer);
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        ByteBuf bytes = Unpooled.buffer();
        try {
            RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(bytes, connection.registryAccess());
            ClientboundRecipeBookAddPacket packet = new ClientboundRecipeBookAddPacket(
                    List.copyOf(entries.values()),
                    false
            );
            ClientboundRecipeBookAddPacket.STREAM_CODEC.encode(buffer, packet);
            byte[] encoded = new byte[buffer.readableBytes()];
            buffer.getBytes(buffer.readerIndex(), encoded);
            Files.createDirectories(DIRECTORY);
            Files.write(temporary, encoded);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException exception) {
            System.err.println("[REI Recipe Bridge] Failed to save captured recipes: " + exception.getMessage());
        } finally {
            bytes.release();
        }
    }

    private static ClientboundRecipeBookAddPacket.Entry quiet(ClientboundRecipeBookAddPacket.Entry entry) {
        return new ClientboundRecipeBookAddPacket.Entry(entry.contents(), false, false);
    }

    private static String currentServer(ClientPacketListener connection) {
        ServerData server = connection.getServerData();
        if (server == null) {
            server = Minecraft.getInstance().getCurrentServer();
        }
        return server == null ? null : server.ip.trim().toLowerCase();
    }

    private static Path cachePath(String server) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(server.getBytes(StandardCharsets.UTF_8));
            return DIRECTORY.resolve(HexFormat.of().formatHex(digest, 0, 16) + ".bin");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
