package cn.reibridge.recipe;

import cn.reibridge.config.BridgeConfig;
import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.client.registry.display.DynamicDisplayGenerator;
import me.shedaniel.rei.api.client.view.ViewSearchBuilder;
import me.shedaniel.rei.api.common.display.Display;
import me.shedaniel.rei.api.common.entry.EntryStack;
import me.shedaniel.rei.api.common.entry.type.EntryType;
import me.shedaniel.rei.api.common.entry.type.EntryTypeRegistry;
import me.shedaniel.rei.api.common.category.CategoryIdentifier;
import me.shedaniel.rei.api.common.util.EntryStacks;
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
import java.io.OutputStream;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Callable;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class RecipeCaptureStore {
    private static final Logger LOGGER = LoggerFactory.getLogger("REI Recipe Bridge");
    private static final int MAX_CACHE_BYTES = 64 * 1024 * 1024;
    private static final int CACHE_FORMAT = 1;
    private static final int MANIFEST_FORMAT = 2;
    private static final int PART_TARGET_BYTES = 4 * 1024 * 1024;
    private static final long SAVE_DELAY_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long RESTORE_BUDGET_NANOS = 2_000_000;
    private static final int RESTORE_BATCH = 32;
    private static final Path DIRECTORY = FabricLoader.getInstance().getConfigDir()
            .resolve("rei_recipe_bridge")
            .resolve("recipes");
    private static final SnapshotWriter WRITER = new SnapshotWriter();

    private static ClientPacketListener loadedConnection;
    private static String loadedServer;
    /** Cached by recipe contents rather than RecipeDisplayId.index. */
    private static final Map<String, ClientboundRecipeBookAddPacket.Entry> entries = new LinkedHashMap<>();
    /** Immutable, named-registry JSON, encoded once on the client thread. Includes unavailable historical items. */
    private static final Map<String, byte[]> encodedRecipes = new LinkedHashMap<>();
    private static final Map<RecipeDisplayEntry, String> unencodable = new IdentityHashMap<>();
    /** Indexes are only meaningful for the currently connected server session. */
    private static final Map<Integer, String> currentIndexes = new HashMap<>();
    private static int lastReceivedCount;
    private static int lastMergedCount;
    private static int lastRegisteredCount;
    private static boolean dirty;
    private static boolean cacheNeedsBackup;
    private static boolean cacheReadFailed;
    private static final Set<String> unavailableRecipes = new HashSet<>();
    private static long nextSessionKey;
    private static long saveAfterNanos;
    private static LoadState loading;
    private static final Set<String> removedRecipes = new HashSet<>();
    private static final Set<String> discardedRawKeys = new HashSet<>();
    private static final Set<String> liveKeys = new HashSet<>();
    private static final Map<String, List<Display>> historicalDisplays = new LinkedHashMap<>();
    private static final HistoricalDisplayIndex historicalIndex = new HistoricalDisplayIndex();
    private static final Set<String> displayQueue = new java.util.LinkedHashSet<>();
    private static DisplayRegistry displayRegistry;
    private static boolean displayRegistryReady;

    private record SnapshotData(Map<String, byte[]> recipes, Set<String> removed, boolean backup) {
        SnapshotData(Map<String, byte[]> recipes, Set<String> removed) { this(recipes, removed, false); }
    }
    private record RestoreRecord(String key, byte[] bytes, JsonElement json) { }
    private record ReadResult(SnapshotData snapshot, List<RestoreRecord> records, boolean migrated) { }
    public record LoadingProgress(boolean loading, int restored, int total) { }
    private static final class LoadState {
        final CompletableFuture<ReadResult> read;
        ReadResult result;
        int restored;
        LoadState(CompletableFuture<ReadResult> read) { this.read = read; }
    }

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
            displayQueue.addAll(entries.keySet());
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
            if (key != null) {
                encodedRecipes.remove(key);
                unavailableRecipes.remove(key);
                unencodable.values().removeIf(key::equals);
                if (key.startsWith("v2:")) removedRecipes.add(key);
                liveKeys.remove(key);
                removeHistoricalDisplays(key);
                displayQueue.remove(key);
            }
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
        displayRegistry = registry;
        displayRegistryReady = false;
        historicalDisplays.clear();
        historicalIndex.clear();
        displayQueue.addAll(entries.keySet());
        registry.registerGlobalDisplayGenerator(new DynamicDisplayGenerator<Display>() {
            @Override public Optional<List<Display>> getRecipeFor(EntryStack<?> stack) {
                return Optional.of(dynamicDisplays(stack, false, null));
            }
            @Override public Optional<List<Display>> getUsageFor(EntryStack<?> stack) {
                return Optional.of(dynamicDisplays(stack, true, null));
            }
            @Override public Optional<List<Display>> generate(ViewSearchBuilder builder) {
                return Optional.of(dynamicDisplays(null, false, builder));
            }
        });
    }

    public static synchronized void finishDisplayRegistration() {
        displayRegistryReady = true;
        historicalDisplays.clear();
        historicalIndex.clear();
        displayQueue.addAll(entries.keySet());
    }

    private static synchronized List<Display> dynamicDisplays(EntryStack<?> stack, boolean input, ViewSearchBuilder builder) {
        if (!BridgeConfig.get().captureUnlockedRecipes) return List.of();
        List<Display> result = new ArrayList<>();
        for (String key : historicalIndex.candidates(stack, input, builder)) {
            RecipeDisplayEntry recipe = entries.get(key).contents();
            if (key.equals(currentIndexes.get(recipe.id().index()))) continue;
            for (Display display : historicalDisplays.get(key)) {
                if (stack != null) {
                    var ingredients = input ? display.getInputEntries() : display.getOutputEntries();
                    if (ingredients.stream().flatMap(List::stream).anyMatch(candidate -> EntryStacks.equalsFuzzy(stack, candidate)))
                        result.add(display);
                } else if (builder.getCategories().contains(display.getCategoryIdentifier())) {
                    result.add(display);
                }
            }
        }
        return result;
    }

    private static void fillHistoricalDisplays(long deadline) {
        if (displayRegistry == null || !displayRegistryReady || displayQueue.isEmpty()) return;
        if (System.nanoTime() - deadline >= 0) return;
        int batch = 0;
        while (!displayQueue.isEmpty() && batch++ < RESTORE_BATCH) {
            String key = displayQueue.iterator().next();
            displayQueue.remove(key);
            var entry = entries.get(key);
            if (entry != null && !key.equals(currentIndexes.get(entry.contents().id().index()))) {
                try {
                    List<Display> displays = List.copyOf(displayRegistry.tryFillDisplay(
                            entry.contents().display(), DisplayAdditionReason.RECIPE_MANAGER));
                    historicalIndex.put(key, displays);
                    historicalDisplays.put(key, displays);
                } catch (RuntimeException exception) {
                    LOGGER.warn("Could not prepare historical recipe {} for REI", key, exception);
                }
            }
            if (System.nanoTime() - deadline >= 0) break;
        }
        lastRegisteredCount = historicalDisplays.size();
    }

    private static void removeHistoricalDisplays(String key) {
        historicalDisplays.remove(key);
        historicalIndex.remove(key);
    }

    /** Built incrementally with the bounded display queue, never during a query. */
    private static final class HistoricalDisplayIndex {
        private record FuzzyKey(EntryType<?> type, long hash) {
            // EntryStacks.equals compares entry types by identity before using their definitions.
            @Override public boolean equals(Object other) {
                return other instanceof FuzzyKey key && type == key.type && hash == key.hash;
            }
            @Override public int hashCode() { return 31 * System.identityHashCode(type) + Long.hashCode(hash); }
        }
        private record IndexedRecipe(long order, Set<FuzzyKey> inputs, Set<FuzzyKey> outputs,
                                     Set<CategoryIdentifier<?>> categories) { }
        private final Map<String, IndexedRecipe> recipes = new HashMap<>();
        private final DirectionIndex inputs = new DirectionIndex();
        private final DirectionIndex outputs = new DirectionIndex();
        private final Map<CategoryIdentifier<?>, Set<String>> categories = new HashMap<>();
        private long nextOrder;

        void put(String key, List<Display> displays) {
            Set<FuzzyKey> inputKeys = new HashSet<>(), outputKeys = new HashSet<>();
            Set<CategoryIdentifier<?>> categoryKeys = new HashSet<>();
            for (Display display : displays) {
                collect(display.getInputEntries(), inputKeys);
                collect(display.getOutputEntries(), outputKeys);
                categoryKeys.add(display.getCategoryIdentifier());
            }
            // Compute everything before replacing an existing, usable index entry.
            IndexedRecipe previous = recipes.get(key);
            IndexedRecipe recipe = new IndexedRecipe(previous == null ? nextOrder++ : previous.order(),
                    inputKeys, outputKeys, categoryKeys);
            remove(key);
            recipes.put(key, recipe);
            inputs.add(key, inputKeys);
            outputs.add(key, outputKeys);
            for (CategoryIdentifier<?> category : categoryKeys)
                categories.computeIfAbsent(category, unused -> new HashSet<>()).add(key);
        }

        private static void collect(List<? extends List<EntryStack<?>>> ingredients, Set<FuzzyKey> keys) {
            for (List<EntryStack<?>> ingredient : ingredients) for (EntryStack<?> stack : ingredient)
                keys.add(new FuzzyKey(stack.getType(), EntryStacks.hashFuzzy(stack)));
        }

        void remove(String key) {
            IndexedRecipe previous = recipes.remove(key);
            if (previous == null) return;
            inputs.remove(key, previous.inputs());
            outputs.remove(key, previous.outputs());
            for (CategoryIdentifier<?> category : previous.categories()) removeFrom(categories, category, key);
        }

        List<String> candidates(EntryStack<?> stack, boolean input, ViewSearchBuilder builder) {
            Set<String> candidates = new HashSet<>();
            if (stack != null) (input ? inputs : outputs).collectCandidates(stack, candidates);
            else for (CategoryIdentifier<?> category : builder.getCategories())
                candidates.addAll(categories.getOrDefault(category, Set.of()));
            // Preserve historical recipe/display ordering when multiple buckets contribute.
            return candidates.stream().sorted(Comparator.comparingLong(key -> recipes.get(key).order())).toList();
        }

        void clear() {
            recipes.clear(); inputs.clear(); outputs.clear(); categories.clear(); nextOrder = 0;
        }

        private static <K> void removeFrom(Map<K, Set<String>> index, K bucket, String key) {
            Set<String> values = index.get(bucket);
            if (values != null && values.remove(key) && values.isEmpty()) index.remove(bucket);
        }

        private static final class DirectionIndex {
            private final Map<FuzzyKey, Set<String>> hashes = new HashMap<>();
            private final Map<EntryType<?>, Set<String>> types = new IdentityHashMap<>();

            void add(String key, Set<FuzzyKey> keys) {
                for (FuzzyKey bucket : keys) {
                    hashes.computeIfAbsent(bucket, unused -> new HashSet<>()).add(key);
                    types.computeIfAbsent(bucket.type(), unused -> new HashSet<>()).add(key);
                }
            }
            void remove(String key, Set<FuzzyKey> keys) {
                for (FuzzyKey bucket : keys) {
                    removeFrom(hashes, bucket, key);
                    removeFrom(types, bucket.type(), key);
                }
            }
            void collectCandidates(EntryStack<?> stack, Set<String> result) {
                EntryType<?> type = stack.getType();
                result.addAll(hashes.getOrDefault(new FuzzyKey(type, EntryStacks.hashFuzzy(stack)), Set.of()));
                for (Map.Entry<EntryType<?>, Set<String>> bucket : types.entrySet()) {
                    if (bucket.getKey() == type) continue;
                    EntryTypeRegistry registry = EntryTypeRegistry.getInstance();
                    // Fuzzy equality also accepts one-way bridges in either direction. Their
                    // inverse need not be enumerable, so only bridged types need this fallback.
                    if (registry.getBridgesFor(type, bucket.getKey()).iterator().hasNext()
                            || registry.getBridgesFor(bucket.getKey(), type).iterator().hasNext())
                        result.addAll(bucket.getValue());
                }
            }
            void clear() { hashes.clear(); types.clear(); }
        }
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

    /** Describes persistence problems without making a successful live capture look like a saved one. */
    public static synchronized int unsavedRecipeCount() {
        return (int) entries.keySet().stream().filter(key -> !encodedRecipes.containsKey(key)).count();
    }

    public static synchronized int unavailableRecipeCount() {
        return unavailableRecipes.size();
    }

    public static synchronized boolean cacheReadFailed() {
        return cacheReadFailed;
    }

    public static synchronized boolean cacheWriteFailed() {
        return loadedServer != null && WRITER.failed(cachePath(loadedServer));
    }

    public static synchronized LoadingProgress loadingProgress() {
        return loading == null ? new LoadingProgress(false, 0, 0)
                : new LoadingProgress(true, loading.restored, loading.result == null ? -1 : loading.result.records().size());
    }

    /** Runs cache inspection/cleanup on the same queue as reads and atomic writes. */
    public static synchronized <T> CompletableFuture<T> maintenance(Callable<T> task) {
        flushSnapshot();
        return WRITER.maintenance(task);
    }

    public static synchronized void tick() {
        long restoreDeadline = System.nanoTime() + RESTORE_BUDGET_NANOS;
        restorePendingRecipes(restoreDeadline);
        fillHistoricalDisplays(restoreDeadline);
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
        encodedRecipes.clear();
        unencodable.clear();
        currentIndexes.clear();
        loadedConnection = null;
        loadedServer = null;
        dirty = false;
        cacheNeedsBackup = false;
        cacheReadFailed = false;
        unavailableRecipes.clear();
        removedRecipes.clear();
        discardedRawKeys.clear();
        liveKeys.clear();
        historicalDisplays.clear();
        historicalIndex.clear();
        displayQueue.clear();
        loading = null;
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
        if (!Minecraft.getInstance().isSameThread()) {
            return false;
        }
        flushSnapshot();
        clearSession();
        loadedServer = server;
        loadedConnection = connection;
        Path path = cachePath(server);
        CompletableFuture<SnapshotData> pending = WRITER.latestSnapshot(path);
        CompletableFuture<ReadResult> read = pending == null
                ? WRITER.maintenance(() -> readCache(server, path))
                : WRITER.maintenance(() -> prepareRead(pending.join(), false));
        loading = new LoadState(read);
        return true;
    }

    private static ReadResult readCache(String server, Path path) throws IOException {
        if (Files.isRegularFile(path)) return prepareRead(readSnapshotData(path), false);
        Path previous = previousCachePath(server);
        if (Files.isRegularFile(previous)) {
            if (Files.size(previous) > MAX_CACHE_BYTES) throw new IOException("legacy cache exceeds maximum size");
            Map<String, byte[]> records = new LinkedHashMap<>();
            for (JsonElement recipe : snapshotRoot(Files.readAllBytes(previous)).getAsJsonArray("recipes")) {
                byte[] bytes = canonicalJson(recipe).getBytes(StandardCharsets.UTF_8);
                records.put("v2:" + hash(bytes), bytes);
            }
            return prepareRead(new SnapshotData(records, Set.of()), true);
        }
        if (Files.isRegularFile(legacyCachePath(server))) {
            LOGGER.info("Leaving legacy network recipe cache untouched for {}; recapturing named displays", server);
        }
        return prepareRead(new SnapshotData(Map.of(), Set.of()), false);
    }

    private static ReadResult prepareRead(SnapshotData snapshot, boolean migrated) {
        List<RestoreRecord> records = new ArrayList<>();
        snapshot.recipes().forEach((key, bytes) -> records.add(new RestoreRecord(key, bytes,
                JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)))));
        return new ReadResult(snapshot, List.copyOf(records), migrated);
    }

    private static void restorePendingRecipes(long deadline) {
        LoadState state = loading;
        if (state == null || !state.read.isDone()) return;
        if (state.result == null) {
            try {
                state.result = state.read.join();
                state.result.snapshot().removed().stream().filter(key -> !liveKeys.contains(key)).forEach(removedRecipes::add);
            } catch (CompletionException exception) {
                loading = null;
                cacheNeedsBackup = true;
                cacheReadFailed = true;
                if (!encodedRecipes.isEmpty()) markDirty();
                LOGGER.warn("Failed to load captured recipes for {}; preserving the original before replacement",
                        loadedServer, exception.getCause());
                return;
            }
        }
        RegistryAccess registries = state.result.records().isEmpty() ? null : loadedConnection.registryAccess();
        int batch = 0;
        while (state.restored < state.result.records().size() && batch++ < RESTORE_BATCH) {
            RestoreRecord record = state.result.records().get(state.restored++);
            restoreEncodedRecipe(record, state.result.snapshot().removed(), registries, loadedServer);
            if (System.nanoTime() - deadline >= 0) break;
        }
        if (state.restored == state.result.records().size() && (displayRegistry == null || displayQueue.isEmpty())) {
            loading = null;
            if (state.result.migrated() || !state.result.snapshot().removed().isEmpty()
                    || !discardedRawKeys.isEmpty()) markDirty();
            LOGGER.info("Restored {} cached recipe records for {}", state.restored, loadedServer);
        }
    }

    private static void restoreEncodedRecipe(RestoreRecord record, Set<String> storedRemoved,
                                             RegistryAccess registries, String server) {
        String key = record.key();
        byte[] bytes = record.bytes();
        if (removedRecipes.contains(key) || (storedRemoved.contains(key) && !liveKeys.contains(key))) return;
        // Preserve each original record before resolving its registry references. A missing
        // mod must not discard the working records next to it, or lose its own future recovery.
        encodedRecipes.putIfAbsent(key, bytes);
        try {
            CachedRecipe recipe = CachedRecipe.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries),
                    record.json()).getOrThrow();
            EncodedRecipe normalized = encodeRecipe(recipe.historicalEntry().contents(), registries);
            if (removedRecipes.contains(normalized.key())
                    || (storedRemoved.contains(normalized.key()) && !liveKeys.contains(normalized.key()))) {
                encodedRecipes.remove(key);
                discardedRawKeys.add(key);
                return;
            }
            if (!key.equals(normalized.key())) {
                encodedRecipes.remove(key);
                discardedRawKeys.add(key);
            }
            encodedRecipes.putIfAbsent(normalized.key(), normalized.bytes());
            entries.putIfAbsent(normalized.key(), recipe.historicalEntry());
            displayQueue.add(normalized.key());
        } catch (RuntimeException exception) {
            unavailableRecipes.add(key);
            LOGGER.warn("Keeping an unavailable recipe {} in the cache for {}", key, server, exception);
        }
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
            // Only immutable JSON bytes cross the thread boundary. Registry encoding happened
            // while each record was received, so a save never re-encodes the entire recipe book.
            Map<String, byte[]> changes = Collections.unmodifiableMap(new LinkedHashMap<>(encodedRecipes));
            Set<String> deleted = new HashSet<>(removedRecipes);
            deleted.addAll(discardedRawKeys);
            boolean backup = cacheNeedsBackup;
            CompletableFuture<SnapshotData> snapshot = loading == null
                    ? CompletableFuture.completedFuture(new SnapshotData(changes, Set.copyOf(removedRecipes), backup))
                    : loading.read.handleAsync((result, error) -> error == null
                            ? mergeSnapshot(result.snapshot(), changes, deleted)
                            : new SnapshotData(changes, Set.copyOf(deleted), true), WRITER.executor);
            WRITER.submitSnapshot(path, snapshot);
            dirty = false;
            cacheNeedsBackup = false;
        } catch (RuntimeException exception) {
            saveAfterNanos = System.nanoTime() + SAVE_DELAY_NANOS;
            LOGGER.warn("Failed to encode captured recipes", exception);
        }
    }

    private static SnapshotData mergeSnapshot(SnapshotData original, Map<String, byte[]> changes, Set<String> deleted) {
        Map<String, byte[]> merged = new LinkedHashMap<>(original.recipes());
        deleted.forEach(merged::remove);
        merged.putAll(changes);
        Set<String> ignored = new HashSet<>(original.removed());
        ignored.addAll(deleted);
        ignored.removeIf(key -> !key.startsWith("v2:"));
        ignored.removeAll(changes.keySet());
        return new SnapshotData(Collections.unmodifiableMap(merged), Set.copyOf(ignored));
    }

    private static List<CachedRecipe> decodeSnapshot(byte[] snapshot, RegistryAccess registries) {
        JsonObject root = snapshotRoot(snapshot);
        return CachedRecipe.CODEC.listOf().parse(RegistryOps.create(JsonOps.INSTANCE, registries),
                root.get("recipes")).getOrThrow();
    }

    private static JsonObject snapshotRoot(byte[] snapshot) {
        JsonObject root = JsonParser.parseString(new String(snapshot, StandardCharsets.UTF_8)).getAsJsonObject();
        if (root.get("format").getAsInt() != CACHE_FORMAT
                || !SharedConstants.getCurrentVersion().id().equals(root.get("minecraft").getAsString())) {
            throw new IllegalArgumentException("unsupported recipe cache format or Minecraft version");
        }
        if (!root.get("recipes").isJsonArray()) throw new IllegalArgumentException("recipe cache has no recipe list");
        return root;
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
        String key = unencodable.get(entry.contents());
        try {
            if (key == null) {
                EncodedRecipe encoded = encodeRecipe(entry.contents(), registries);
                key = encoded.key();
                encodedRecipes.putIfAbsent(key, encoded.bytes());
                unavailableRecipes.remove(key);
            }
        } catch (RuntimeException exception) {
            // A mod can expose a display which has no persistent codec. Keep it usable in
            // this connection and report it, rather than repeatedly retrying every save or
            // preventing all other recipes from being written.
            key = "session:" + nextSessionKey++;
            unencodable.put(entry.contents(), key);
            LOGGER.warn("A recipe from {} can be shown for this session but cannot be saved", loadedServer, exception);
        }
        ClientboundRecipeBookAddPacket.Entry previousEntry = entries.get(key);
        int index = entry.contents().id().index();
        if (previousEntry != null && previousEntry.contents().id().index() != index) {
            currentIndexes.remove(previousEntry.contents().id().index(), key);
        }
        String previous = currentIndexes.put(index, key);
        if (previous != null && !previous.equals(key)) {
            entries.computeIfPresent(previous, (unused, old) -> historical(old));
            displayQueue.add(previous);
        }
        ClientboundRecipeBookAddPacket.Entry cached = quiet(entry);
        entries.put(key, cached);
        removeHistoricalDisplays(key);
        removedRecipes.remove(key);
        liveKeys.add(key);
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
        return encodeRecipe(entry, registries).key();
    }

    private record EncodedRecipe(String key, byte[] bytes) { }

    private static EncodedRecipe encodeRecipe(RecipeDisplayEntry entry, RegistryAccess registries) {
        JsonElement encoded = CachedRecipe.CODEC
                .encodeStart(RegistryOps.create(JsonOps.INSTANCE, registries), keyRecipe(entry))
                .getOrThrow();
        byte[] bytes = canonicalJson(encoded).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CACHE_BYTES) throw new IllegalStateException("one recipe exceeds the cache record limit");
        return new EncodedRecipe("v2:" + hash(bytes), bytes);
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
        return DIRECTORY.resolve(serverHash(server) + ".v2.json");
    }

    private static Path previousCachePath(String server) {
        return DIRECTORY.resolve(serverHash(server) + ".v1.json");
    }

    private static Path legacyCachePath(String server) {
        // Pre-v1 files contain network IDs with no recoverable registry table.
        // They cannot be migrated safely and are kept as the user's originals.
        return DIRECTORY.resolve(serverHash(server) + ".bin");
    }

    private static String serverHash(String server) {
        return hash(server.getBytes(StandardCharsets.UTF_8)).substring(0, 32);
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Path partsDirectory(Path manifest) {
        return manifest.resolveSibling(manifest.getFileName() + ".parts");
    }

    private static Map<String, byte[]> readParts(Path manifest) throws IOException {
        return readSnapshotData(manifest).recipes();
    }

    private static SnapshotData readSnapshotData(Path manifest) throws IOException {
        if (Files.size(manifest) > MAX_CACHE_BYTES) throw new IOException("recipe manifest is too large");
        JsonObject root = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
        if (root.get("format").getAsInt() != MANIFEST_FORMAT
                || !SharedConstants.getCurrentVersion().id().equals(root.get("minecraft").getAsString())) {
            throw new IOException("unsupported recipe cache format or Minecraft version");
        }
        Map<String, byte[]> records = new LinkedHashMap<>();
        for (JsonElement element : root.getAsJsonArray("parts")) {
            JsonObject part = element.getAsJsonObject();
            String filename = part.get("file").getAsString();
            if (!filename.matches("[0-9a-f]{64}\\.jsonl\\.gz")) throw new IOException("invalid recipe part name");
            byte[] bytes = readPartBytes(partsDirectory(manifest).resolve(filename));
            if (!hash(bytes).equals(filename.substring(0, 64))) throw new IOException("recipe part checksum mismatch");
            int count = 0;
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\n")) {
                if (line.isEmpty()) continue;
                byte[] recipe = canonicalJson(JsonParser.parseString(line)).getBytes(StandardCharsets.UTF_8);
                records.put("v2:" + hash(recipe), recipe);
                count++;
            }
            if (count != part.get("count").getAsInt()) throw new IOException("recipe part count mismatch");
        }
        Set<String> removed = new HashSet<>();
        if (root.has("removed")) {
            for (JsonElement key : root.getAsJsonArray("removed")) {
                String value = key.getAsString();
                if (!value.matches("v2:[0-9a-f]{64}")) throw new IOException("invalid removed recipe key");
                removed.add(value);
            }
        }
        return new SnapshotData(Collections.unmodifiableMap(records), Set.copyOf(removed));
    }

    private static byte[] readPartBytes(Path path) throws IOException {
        try (var input = new GZIPInputStream(Files.newInputStream(path))) {
            byte[] bytes = input.readNBytes(MAX_CACHE_BYTES + 2);
            if (bytes.length > MAX_CACHE_BYTES + 1) throw new IOException("recipe part exceeds maximum expanded size");
            return bytes;
        }
    }

    private static void replaceFile(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** The writer owns only paths and immutable encoded snapshots, never game objects. */
    private static final class SnapshotWriter {
        private final Map<Path, CompletableFuture<SnapshotData>> latest = new LinkedHashMap<>();
        private final Map<Path, CompletableFuture<SnapshotData>> pending = new LinkedHashMap<>();
        private final Set<Path> verifiedParts = new HashSet<>();
        private final Set<Path> failed = new HashSet<>();
        private final ExecutorService executor = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon(true).name("spark_fix-recipe-cache").factory()
        );
        private boolean running;
        private boolean stopped;
        private long retryAfterNanos;

        synchronized Map<String, byte[]> latest(Path path) {
            var future = latest.get(path);
            return future == null || !future.isDone() || future.isCompletedExceptionally()
                    ? null : future.join().recipes();
        }

        synchronized CompletableFuture<SnapshotData> latestSnapshot(Path path) {
            return latest.get(path);
        }

        synchronized boolean failed(Path path) {
            return failed.contains(path);
        }

        synchronized void submit(Path path, Map<String, byte[]> bytes) {
            submitSnapshot(path, CompletableFuture.completedFuture(new SnapshotData(bytes, Set.of())));
        }

        synchronized void submitSnapshot(Path path, CompletableFuture<SnapshotData> bytes) {
            if (stopped) {
                throw new IllegalStateException("Recipe cache writer has stopped");
            }
            latest.put(path, bytes);
            pending.put(path, bytes);
            bytes.whenComplete((snapshot, error) -> {
                synchronized (SnapshotWriter.this) { startWriter(); }
            });
            startWriter();
        }

        synchronized <T> CompletableFuture<T> maintenance(Callable<T> task) {
            CompletableFuture<T> result = new CompletableFuture<>();
            if (stopped) {
                result.completeExceptionally(new IllegalStateException("Recipe cache writer has stopped"));
                return result;
            }
            executor.execute(() -> {
                if (result.isCancelled()) return;
                try { result.complete(task.call()); }
                catch (Exception exception) { result.completeExceptionally(exception); }
            });
            return result;
        }

        synchronized void retryFailedWrites() {
            if (!stopped && !running && !latest.isEmpty() && System.nanoTime() - retryAfterNanos >= 0) {
                pending.putAll(latest);
                startWriter();
            }
        }

        private void startWriter() {
            if (!running && !stopped && pending.values().stream().anyMatch(CompletableFuture::isDone)) {
                running = true;
                executor.execute(this::writePending);
            }
        }

        private void writePending() {
            while (true) {
                Path path;
                CompletableFuture<SnapshotData> bytes;
                synchronized (this) {
                    var next = pending.entrySet().stream().filter(entry -> entry.getValue().isDone()).findFirst();
                    if (next.isEmpty()) {
                        running = false;
                        return;
                    }
                    path = next.orElseThrow().getKey();
                    bytes = next.orElseThrow().getValue();
                    pending.remove(path);
                }
                try {
                    writeParts(path, bytes.join());
                    synchronized (this) {
                        latest.remove(path, bytes);
                        failed.remove(path);
                    }
                } catch (IOException | RuntimeException exception) {
                    synchronized (this) {
                        retryAfterNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                        failed.add(path);
                    }
                    LOGGER.warn("Failed to save captured recipes to {}", path, exception);
                }
            }
        }

        private void writeParts(Path manifest, SnapshotData snapshot) throws IOException {
            Path directory = partsDirectory(manifest);
            Files.createDirectories(directory);
            JsonObject root = new JsonObject();
            root.addProperty("format", MANIFEST_FORMAT);
            root.addProperty("minecraft", SharedConstants.getCurrentVersion().id());
            JsonArray parts = new JsonArray();
            root.add("parts", parts);
            JsonArray removed = new JsonArray();
            snapshot.removed().stream().sorted().forEach(removed::add);
            if (!removed.isEmpty()) root.add("removed", removed);
            // Stable hash buckets keep an unrelated addition from shifting every later part.
            Map<String, List<byte[]>> buckets = new TreeMap<>();
            new TreeMap<>(snapshot.recipes()).forEach((key, bytes) ->
                    buckets.computeIfAbsent(key.substring(3, 5), unused -> new ArrayList<>()).add(bytes));
            for (List<byte[]> bucket : buckets.values()) {
                List<byte[]> part = new ArrayList<>();
                int size = 0;
                for (byte[] record : bucket) {
                    if (!part.isEmpty() && (long) size + record.length + 1 > PART_TARGET_BYTES) {
                        parts.add(writePart(directory, part));
                        part.clear();
                        size = 0;
                    }
                    part.add(record);
                    size += record.length + 1;
                }
                if (!part.isEmpty()) parts.add(writePart(directory, part));
            }
            Path temporary = manifest.resolveSibling(manifest.getFileName() + ".tmp");
            Files.writeString(temporary, root.toString(), StandardCharsets.UTF_8);
            if (snapshot.backup() && Files.isRegularFile(manifest)) {
                Path backup = manifest.resolveSibling(manifest.getFileName() + ".rejected-" + UUID.randomUUID());
                Files.copy(manifest, backup);
                LOGGER.warn("Preserved unreadable recipe cache at {}", backup);
            }
            // All immutable parts exist before the only mutable pointer is replaced.
            replaceFile(temporary, manifest);
        }

        private JsonObject writePart(Path directory, List<byte[]> records) throws IOException {
            MessageDigest digest;
            try { digest = MessageDigest.getInstance("SHA-256"); }
            catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
            for (byte[] record : records) {
                digest.update(record);
                digest.update((byte) '\n');
            }
            String checksum = HexFormat.of().formatHex(digest.digest());
            String filename = checksum + ".jsonl.gz";
            Path path = directory.resolve(filename);
            boolean present = verifiedParts.contains(path) && Files.isRegularFile(path);
            if (!present && Files.isRegularFile(path)) {
                try {
                    present = hash(readPartBytes(path)).equals(checksum);
                } catch (IOException exception) {
                    LOGGER.warn("Replacing an unreadable recipe cache part {}", path, exception);
                }
                if (!present) Files.copy(path, path.resolveSibling(filename + ".rejected-" + UUID.randomUUID()));
            }
            if (!present) {
                Path temporary = path.resolveSibling(filename + ".tmp");
                try (OutputStream output = new GZIPOutputStream(Files.newOutputStream(temporary))) {
                    for (byte[] record : records) {
                        output.write(record);
                        output.write('\n');
                    }
                }
                replaceFile(temporary, path);
            }
            verifiedParts.add(path);
            JsonObject part = new JsonObject();
            part.addProperty("file", filename);
            part.addProperty("count", records.size());
            return part;
        }

        void shutdown() {
            synchronized (this) {
                if (stopped) {
                    return;
                }
                pending.putAll(latest);
                startWriter();
                // Reads already in this queue can complete pending merged snapshots. Drain
                // again after them before terminating; no registry work is performed here.
                executor.execute(this::writePending);
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
