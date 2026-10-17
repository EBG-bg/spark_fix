package cn.reibridge.recipe;

import cn.reibridge.config.BridgeConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.client.registry.display.reason.DisplayAdditionReason;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundRecipeBookAddPacket;
import net.minecraft.network.protocol.game.ClientboundRecipeBookRemovePacket;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.RecipeDisplayId;
import net.minecraft.world.item.Item;
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
import java.util.HexFormat;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Comparator;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class RecipeCaptureStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("REI Recipe Bridge");
    private static final int MAX_CACHE_BYTES = 64 * 1024 * 1024;
    private static final int CACHE_FORMAT = 1;
    private static final long SAVE_DELAY_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final Path DIRECTORY = FabricLoader.getInstance().getConfigDir()
            .resolve("rei_recipe_bridge")
            .resolve("recipes");
    private static final SnapshotWriter WRITER = new SnapshotWriter();

    private static ClientPacketListener loadedConnection;
    private static String loadedServer;
    /** Cached by recipe contents rather than RecipeDisplayId.index. */
    private static final Map<String, ClientboundRecipeBookAddPacket.Entry> entries = new LinkedHashMap<>();
    /** Indexes are only meaningful for the currently connected server session. */
    private static final Map<Integer, String> currentIndexes = new HashMap<>();
    private static int lastReceivedCount;
    private static int lastMergedCount;
    private static int lastRegisteredCount;
    private static boolean dirty;
    private static boolean cacheNeedsBackup;
    private static long saveAfterNanos;

    private RecipeCaptureStore() {
    }

    public static synchronized ClientboundRecipeBookAddPacket merge(
            ClientPacketListener connection,
            ClientboundRecipeBookAddPacket packet
    ) {
        if (!BridgeConfig.get().captureUnlockedRecipes || !load(connection)) {
            return packet;
        }

        if (packet.replace()) {
            // A replace packet is a fresh index table for this connection.
            // Keep its historical displays, without their obsolete server IDs.
            currentIndexes.clear();
            entries.replaceAll((key, entry) -> historical(entry));
        }
        int cachedBefore = entries.size();
        boolean changed = false;
        RegistryAccess registries = connection.registryAccess();
        for (ClientboundRecipeBookAddPacket.Entry entry : packet.entries()) {
            changed |= rememberCurrent(entry, registries);
        }
        lastReceivedCount = packet.entries().size();
        lastMergedCount = packet.entries().size();
        if (changed) {
            markDirty();
        }

        LOGGER.info(
                "Recipe packet from {}: received={}, cachedBefore={}, merged={}, replace={}",
                loadedServer,
                lastReceivedCount,
                cachedBefore,
                lastMergedCount,
                packet.replace()
        );

        // RecipeDisplayId belongs to this server session. Historical displays
        // are registered with REI separately; adding them to the vanilla book
        // would make its placement button submit an unconfirmed/obsolete ID.
        return packet;
    }

    public static synchronized void remove(
            ClientPacketListener connection,
            ClientboundRecipeBookRemovePacket packet
    ) {
        if (!BridgeConfig.get().captureUnlockedRecipes || !load(connection)) {
            return;
        }
        // Do not use anyMatch here: it short-circuits after the first removed
        // entry and leaves the remaining recipe displays in the cache.
        boolean changed = false;
        for (RecipeDisplayId recipe : packet.recipes()) {
            String key = currentIndexes.remove(recipe.index());
            changed |= key != null && entries.remove(key) != null;
        }
        if (changed) {
            markDirty();
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
        RegistryAccess registries = connection.registryAccess();
        for (RecipeCollection collection : minecraft.player.getRecipeBook().getCollections()) {
            for (RecipeDisplayEntry display : collection.getRecipes()) {
                changed |= rememberCurrent(new ClientboundRecipeBookAddPacket.Entry(display, false, false), registries);
            }
        }
        if (changed) {
            markDirty();
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
        for (Map.Entry<String, ClientboundRecipeBookAddPacket.Entry> cached : entries.entrySet()) {
            String key = cached.getKey();
            ClientboundRecipeBookAddPacket.Entry entry = cached.getValue();
            RecipeDisplayEntry contents = entry.contents();
            if (key.equals(currentIndexes.get(contents.id().index()))) {
                registry.addWithReason(contents.display(), DisplayAdditionReason.RECIPE_MANAGER,
                        DisplayAdditionReason.withId(contents.id()));
            } else {
                registry.addWithReason(contents.display(), DisplayAdditionReason.RECIPE_MANAGER);
            }
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

    public static synchronized void tick() {
        if (dirty && System.nanoTime() - saveAfterNanos >= 0) {
            flushSnapshot();
        }
        WRITER.retryFailedWrites();
    }

    public static synchronized void disconnect(ClientPacketListener connection) {
        if (loadedConnection != connection) {
            return;
        }
        flushSnapshot();
        clearSession();
    }

    public static void shutdown() {
        synchronized (RecipeCaptureStore.class) {
            flushSnapshot();
            clearSession();
        }
        WRITER.shutdown();
    }

    private static void clearSession() {
        entries.clear();
        currentIndexes.clear();
        loadedConnection = null;
        loadedServer = null;
        dirty = false;
        cacheNeedsBackup = false;
        lastReceivedCount = 0;
        lastMergedCount = 0;
        lastRegisteredCount = 0;
    }

    private static void markDirty() {
        if (!dirty) {
            saveAfterNanos = System.nanoTime() + SAVE_DELAY_NANOS;
        }
        dirty = true;
    }

    private static boolean load(ClientPacketListener connection) {
        String server = currentServer(connection);
        if (server == null) {
            return false;
        }
        if (server.equals(loadedServer) && loadedConnection == connection) {
            return true;
        }

        // REI may reload on another thread. Never encode the old session there.
        if (dirty && !Minecraft.getInstance().isSameThread()) {
            return false;
        }
        flushSnapshot();
        clearSession();
        loadedServer = server;
        loadedConnection = connection;
        Path path = cachePath(server);
        byte[] snapshot = WRITER.latest(path);
        if (snapshot == null && !Files.isRegularFile(path)) {
            if (Files.isRegularFile(legacyCachePath(server))) {
                LOGGER.info("Leaving legacy network recipe cache untouched for {}; recapturing named displays", server);
            }
            return true;
        }

        try {
            RegistryAccess registries = connection.registryAccess();
            long size = snapshot == null ? Files.size(path) : snapshot.length;
            if (size <= 0 || size > MAX_CACHE_BYTES) {
                throw new IOException("invalid cache size: " + size);
            }
            for (CachedRecipe recipe : decodeSnapshot(snapshot == null ? Files.readAllBytes(path) : snapshot,
                    registries)) {
                ClientboundRecipeBookAddPacket.Entry entry = recipe.historicalEntry();
                entries.put(stableKey(entry.contents(), registries), entry);
            }
            LOGGER.info("Loaded {} cached recipe displays for {}", entries.size(), loadedServer);
        } catch (IOException | RuntimeException exception) {
            entries.clear();
            cacheNeedsBackup = true;
            LOGGER.warn("Failed to load captured recipes from {}; preserving the original before any replacement", path, exception);
        }
        return true;
    }

    private static void flushSnapshot() {
        if (!dirty || loadedConnection == null || loadedServer == null) {
            return;
        }
        if (!Minecraft.getInstance().isSameThread()) {
            throw new IllegalStateException("Recipe cache encoding must run on the client thread");
        }
        Path path = cachePath(loadedServer);
        try {
            byte[] encoded = encodeSnapshot(entries.values().stream().map(CachedRecipe::of).toList(),
                    loadedConnection.registryAccess());
            if (encoded.length > MAX_CACHE_BYTES) {
                throw new IllegalStateException("recipe cache exceeds maximum size");
            }
            if (cacheNeedsBackup && Files.isRegularFile(path)) {
                Path backup = path.resolveSibling(path.getFileName() + ".rejected-" + UUID.randomUUID());
                Files.copy(path, backup);
                cacheNeedsBackup = false;
                LOGGER.warn("Preserved unreadable recipe cache at {}", backup);
            }
            WRITER.submit(path, encoded);
            dirty = false;
        } catch (IOException | RuntimeException exception) {
            saveAfterNanos = System.nanoTime() + SAVE_DELAY_NANOS;
            LOGGER.warn("Failed to encode captured recipes", exception);
        }
    }

    private static byte[] encodeSnapshot(List<CachedRecipe> recipes, RegistryAccess registries) {
        JsonObject root = new JsonObject();
        root.addProperty("format", CACHE_FORMAT);
        root.addProperty("minecraft", SharedConstants.getCurrentVersion().id());
        root.add("recipes", CachedRecipe.CODEC.listOf()
                .encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), recipes).getOrThrow());
        return root.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static List<CachedRecipe> decodeSnapshot(byte[] snapshot, RegistryAccess registries) {
        JsonObject root = JsonParser.parseString(new String(snapshot, StandardCharsets.UTF_8)).getAsJsonObject();
        if (root.get("format").getAsInt() != CACHE_FORMAT
                || !SharedConstants.getCurrentVersion().id().equals(root.get("minecraft").getAsString())) {
            throw new IllegalArgumentException("unsupported recipe cache format or Minecraft version");
        }
        return CachedRecipe.CODEC.listOf().parse(RegistryOps.create(JsonOps.INSTANCE, registries),
                root.get("recipes")).getOrThrow();
    }

    /** Disk data has named registry references and no session-local recipe or group IDs. */
    private record CachedRecipe(RecipeDisplay display, RecipeBookCategory category,
                                Optional<List<Ingredient>> requirements) {
        private static final Codec<CachedRecipe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                RecipeDisplay.CODEC.fieldOf("display").forGetter(CachedRecipe::display),
                BuiltInRegistries.RECIPE_BOOK_CATEGORY.byNameCodec().fieldOf("category").forGetter(CachedRecipe::category),
                Ingredient.CODEC.listOf().optionalFieldOf("requirements").forGetter(CachedRecipe::requirements)
        ).apply(instance, CachedRecipe::new));

        static CachedRecipe of(ClientboundRecipeBookAddPacket.Entry entry) {
            return of(entry.contents());
        }

        static CachedRecipe of(RecipeDisplayEntry contents) {
            return new CachedRecipe(contents.display(), contents.category(), contents.craftingRequirements());
        }

        ClientboundRecipeBookAddPacket.Entry historicalEntry() {
            return new ClientboundRecipeBookAddPacket.Entry(new RecipeDisplayEntry(new RecipeDisplayId(-1), display,
                    OptionalInt.empty(), category, requirements), false, false);
        }
    }

    private static ClientboundRecipeBookAddPacket.Entry historical(ClientboundRecipeBookAddPacket.Entry entry) {
        return CachedRecipe.of(entry).historicalEntry();
    }

    private static ClientboundRecipeBookAddPacket.Entry quiet(ClientboundRecipeBookAddPacket.Entry entry) {
        return new ClientboundRecipeBookAddPacket.Entry(entry.contents(), false, false);
    }

    /** Bind only a live entry to a server ID; displaced entries remain display-only history. */
    private static boolean rememberCurrent(ClientboundRecipeBookAddPacket.Entry entry, RegistryAccess registries) {
        String key = stableKey(entry.contents(), registries);
        ClientboundRecipeBookAddPacket.Entry previousEntry = entries.get(key);
        int index = entry.contents().id().index();
        if (previousEntry != null && previousEntry.contents().id().index() != index) {
            currentIndexes.remove(previousEntry.contents().id().index(), key);
        }
        String previous = currentIndexes.put(index, key);
        if (previous != null && !previous.equals(key)) {
            entries.computeIfPresent(previous, (unused, old) -> historical(old));
        }
        ClientboundRecipeBookAddPacket.Entry cached = quiet(entry);
        entries.put(key, cached);
        return previousEntry == null || !Objects.equals(CachedRecipe.of(previousEntry), CachedRecipe.of(cached));
    }

    /**
     * RecipeDisplayId and group are session-local and must not identify a cached
     * recipe. Encode the actual display, category, requirements and all nested
     * item component data through the same named-registry codec used on disk,
     * then hash canonical JSON. Object keys are sorted, while arrays retain
     * their protocol order (slot order and requirement order are meaningful).
     */
    private static String stableKey(RecipeDisplayEntry entry, RegistryAccess registries) {
        JsonElement encoded = CachedRecipe.CODEC
                .encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), keyRecipe(entry))
                .getOrThrow();
        String canonical = canonicalJson(encoded);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return "v2:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static CachedRecipe keyRecipe(RecipeDisplayEntry entry) {
        CachedRecipe recipe = CachedRecipe.of(entry);
        return new CachedRecipe(recipe.display(), recipe.category(),
                recipe.requirements().map(RecipeCaptureStore::normalizeIngredients));
    }

    /** Ingredient candidates are a set; normalize their named holders before encoding. */
    private static List<Ingredient> normalizeIngredients(List<Ingredient> ingredients) {
        return ingredients.stream().map(RecipeCaptureStore::normalizeIngredient).toList();
    }

    private static Ingredient normalizeIngredient(Ingredient ingredient) {
        if (ingredient.isEmpty()) {
            return ingredient;
        }
        List<Holder<Item>> holders = ingredient.items()
                .sorted(Comparator.comparing(RecipeCaptureStore::holderName))
                .toList();
        return Ingredient.of(HolderSet.direct(holders));
    }

    private static String holderName(Holder<Item> holder) {
        return holder.unwrapKey().map(key -> key.identifier().toString()).orElse("");
    }

    private static String canonicalJson(JsonElement element) {
        StringBuilder output = new StringBuilder();
        appendCanonicalJson(element, output);
        return output.toString();
    }

    private static void appendCanonicalJson(JsonElement element, StringBuilder output) {
        if (element.isJsonObject()) {
            output.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonElement> entry : new TreeMap<>(element.getAsJsonObject().asMap()).entrySet()) {
                if (!first) {
                    output.append(',');
                }
                first = false;
                output.append(new com.google.gson.JsonPrimitive(entry.getKey()));
                output.append(':');
                appendCanonicalJson(entry.getValue(), output);
            }
            output.append('}');
        } else if (element.isJsonArray()) {
            output.append('[');
            boolean first = true;
            for (JsonElement child : element.getAsJsonArray()) {
                if (!first) {
                    output.append(',');
                }
                first = false;
                appendCanonicalJson(child, output);
            }
            output.append(']');
        } else {
            output.append(element);
        }
    }

    private static String currentServer(ClientPacketListener connection) {
        ServerData server = connection.getServerData();
        if (server == null) {
            server = Minecraft.getInstance().getCurrentServer();
        }
        return server == null ? null : server.ip.trim().toLowerCase(Locale.ROOT);
    }

    private static Path cachePath(String server) {
        return DIRECTORY.resolve(serverHash(server) + ".v1.json");
    }

    private static Path legacyCachePath(String server) {
        // Pre-v1 files contain network IDs with no recoverable registry table.
        // They cannot be migrated safely and are kept as the user's originals.
        return DIRECTORY.resolve(serverHash(server) + ".bin");
    }

    private static String serverHash(String server) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(server.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    /** The writer owns only paths and immutable encoded snapshots, never game objects. */
    private static final class SnapshotWriter {
        private final Map<Path, byte[]> latest = new LinkedHashMap<>();
        private final Map<Path, byte[]> pending = new LinkedHashMap<>();
        private final ExecutorService executor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon(true).name("spark_fix-recipe-cache").factory()
        );
        private boolean running;
        private boolean stopped;
        private long retryAfterNanos;

        synchronized byte[] latest(Path path) {
            return latest.get(path);
        }

        synchronized void submit(Path path, byte[] bytes) {
            if (stopped) {
                throw new IllegalStateException("Recipe cache writer has stopped");
            }
            latest.put(path, bytes);
            pending.put(path, bytes);
            startWriter();
        }

        synchronized void retryFailedWrites() {
            if (!stopped && !running && !latest.isEmpty() && System.nanoTime() - retryAfterNanos >= 0) {
                pending.putAll(latest);
                startWriter();
            }
        }

        private void startWriter() {
            if (!running && !pending.isEmpty()) {
                running = true;
                executor.execute(this::writePending);
            }
        }

        private void writePending() {
            while (true) {
                Path path;
                byte[] bytes;
                synchronized (this) {
                    if (pending.isEmpty()) {
                        running = false;
                        return;
                    }
                    Map.Entry<Path, byte[]> next = pending.entrySet().iterator().next();
                    path = next.getKey();
                    bytes = next.getValue();
                    pending.remove(path);
                }
                Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
                try {
                    Files.createDirectories(path.getParent());
                    Files.write(temporary, bytes);
                    try {
                        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException exception) {
                        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                    }
                    synchronized (this) {
                        latest.remove(path, bytes);
                    }
                } catch (IOException | RuntimeException exception) {
                    synchronized (this) {
                        retryAfterNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    }
                    LOGGER.warn("Failed to save captured recipes to {}", path, exception);
                }
            }
        }

        void shutdown() {
            synchronized (this) {
                if (stopped) {
                    return;
                }
                pending.putAll(latest);
                startWriter();
                stopped = true;
                executor.shutdown();
            }
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    LOGGER.warn("Recipe cache writer is still finishing during shutdown");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                LOGGER.warn("Interrupted while finishing recipe cache writes", exception);
            }
        }
    }
}
