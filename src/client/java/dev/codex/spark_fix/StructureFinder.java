package dev.codex.spark_fix;

import io.netty.buffer.Unpooled;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/** Recognises vanilla pieces using only block data already received by the client. */
public final class StructureFinder {
    // Keep the work bounded per frame while allowing a loaded area to finish
    // in a reasonable time on a stationary client.
    private static final long TICK_BUDGET_NANOS = 3_000_000;
    private static final int BLOCK_BATCH = 256;
    private static final int CANDIDATE_BATCH = 32;
    private static final int MAX_NEW_CHUNK_STEPS = 512;
    private static final int MAX_SNAPSHOT_CACHE = 256;
    private static final int MAX_NOTIFICATION_RESULTS = 8;
    private static final long WORKER_SLICE_NANOS = 8_000_000;
    private static final long WORKER_REST_NANOS = 2_000_000;
    private static final Set<Long> LOADED_CHUNKS = new LinkedHashSet<>();
    private static final Set<Long> NEW_CHUNKS = new LinkedHashSet<>();
    private static final Set<Long> CHANGED_CHUNKS = new LinkedHashSet<>();
    private static final Set<Long> READY_CHANGED_CHUNKS = new LinkedHashSet<>();
    private static final Set<Long> DEFERRED_CHUNKS = new LinkedHashSet<>();
    // Chunk packets and scanning both run on the client thread. Refresh this
    // cache every tick rather than keeping stale chunk objects across packets.
    private static final Long2ObjectOpenHashMap<LevelChunk> TICK_CHUNKS = new Long2ObjectOpenHashMap<>();
    private static final StructureFinderResults RESULTS = new StructureFinderResults();
    private static Set<String> selected = Set.of();
    private static Set<Block> relevantMaterials = Set.of();
    private static Set<Block> pendingRelevantMaterials;
    // The renderer receives immutable snapshots and never accesses the live world.
    private static volatile List<Detection> detections = List.of();
    private static ClientLevel world;
    private static CompletableFuture<List<StructureFinderCatalog.Pattern>> templateFuture;
    private static List<StructureFinderCatalog.Pattern> rawPatterns;
    private static final List<CompiledPattern> compiledPatterns = new ArrayList<>();
    private static int compileIndex;
    private static boolean templatesReady;
    private static String loadError;
    private static boolean registered;
    private static Map<Block, List<Anchor>> anchors = Map.of();
    private static volatile StructureFinderScanFilter discoveryFilter;
    private static final Map<BlockState, List<Anchor>> STATE_ANCHORS = new IdentityHashMap<>();
    private static Map<Block, List<Anchor>> pendingIndex;
    private static int indexCompileIndex;
    private static boolean indexDirty = true;
    private static int similarity = 85;
    private static List<Long> pass = new ArrayList<>();
    private static final Set<Long> scheduledChunks = new LinkedHashSet<>();
    private static final Set<Long> completedChunks = new LinkedHashSet<>();
    private static final Set<Long> previouslyScannedChunks = new LinkedHashSet<>();
    private static final Map<Long, Set<Long>> missingNeighbours = new HashMap<>();
    private static final Set<Long> priorityQueued = new LinkedHashSet<>();
    private static final ArrayDeque<Long> priorityChunks = new ArrayDeque<>();
    private static final Set<Long> freshQueued = new LinkedHashSet<>();
    private static final ArrayDeque<Long> freshChunks = new ArrayDeque<>();
    private static final Map<Long, ScanJob> activeJobs = new LinkedHashMap<>();
    private static final Map<Long, CachedSnapshot> snapshotCache = new LinkedHashMap<>(128, 0.75f, true);
    private static ThreadPoolExecutor workers;
    private static int workerCount;
    private static SnapshotPreparation snapshotPreparation;
    private static Set<String> indexedTypes = Set.of();
    private static ChunkPos lastScanCenter;
    private static List<Long> reconcileChunks = List.of();
    private static int reconcileIndex;
    private static ClientLevel reconcileWorld;
    private static int chunkDispatches;
    private static int passIndex;
    private static boolean passStarted;
    private static PassPreparation passPreparation;
    private static NewChunkPreparation newChunkPreparation;
    private static ChangedChunkPreparation changedChunkPreparation;
    private static int priorityRadius = 1;
    private static long scanTick;
    private static long scanRevision;
    private static StructureFinderScanCache scanCache;
    private static String cacheIdentity;
    private static String templateSignature;
    private static final MessageDigest TEMPLATE_DIGEST = sha256();

    private StructureFinder() {}

    public record Detection(String type, BlockPos pos, AABB bounds, int similarity) {}

    public enum ScanStage { IDLE, LOADING, PREPARING, SCANNING, COMPLETE, FAILED }

    public record ScanProgress(ScanStage stage, int completed, int total, double fraction, long revision) {}

    public static void register() {
        if (registered) return;
        registered = true;
        StructureFinderXaeroBridge.register();
        selected = validTypes(SparkFixConfig.selectedStructureTypes());
        similarity = SparkFixConfig.structureFinderSimilarity();
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            changeWorld(level);
            receiveChunk(chunk.getPos().pack());
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            if (level == world) forgetChunk(chunk.getPos().pack());
        });
        ClientPlayConnectionEvents.DISCONNECT.register((connection, client) -> changeWorld(null));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            changeWorld(null);
            if (workers != null) workers.shutdownNow();
            StructureFinderScanCache.shutdown();
        });
        ClientTickEvents.END_CLIENT_TICK.register(StructureFinder::tick);
        StructureFinderHighlight.register();
        StructureFinderProgressHud.register();
        SparkFixClient.LOGGER.info("Structure Finder enabled: selected={}, similarity={}%, loaded-chunk scan only",
                selected, similarity);
    }

    public static Set<String> selectedTypes() {
        return registered ? selected : validTypes(SparkFixConfig.selectedStructureTypes());
    }

    public static void setSelectedTypes(Set<String> types) {
        Set<String> value = validTypes(types);
        SparkFixConfig.setSelectedStructureTypes(value);
        if (selected.equals(value)) return;
        selected = value;
        RESULTS.retainTypes(value);
        detections = RESULTS.detections();
        indexDirty = true;
        pendingIndex = null;
        resetPass();
    }

    private static Set<String> validTypes(Set<String> types) {
        if (types == null || types.isEmpty()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (StructureFinderCatalog.Entry entry : StructureFinderCatalog.entries()) {
            if (entry.detectable() && types.contains(entry.id())) result.add(entry.id());
        }
        return Set.copyOf(result);
    }

    public static List<Detection> detections() { return detections; }

    static List<StructureFinderResults.HighlightGroup> highlightGroups() { return RESULTS.highlightGroups(); }

    /**
     * Hides a detected structure without deleting its remembered group. The
     * retained bounds prevent a rescan from immediately restoring the same
     * highlight; changing the selected structure types clears that history.
     */
    public static boolean dismissHighlight(Detection detection) {
        List<Detection> pieces = RESULTS.rememberedPieces(detection);
        if (!RESULTS.dismiss(detection)) return false;
        if (scanCache != null) scanCache.dismiss(pieces.isEmpty() ? List.of(detection) : pieces);
        detections = RESULTS.detections();
        return true;
    }

    public static int dismissAllHighlights() {
        List<Detection> pieces = highlightGroups().stream().flatMap(group -> group.pieces().stream()).toList();
        int dismissed = RESULTS.dismissAll();
        if (scanCache != null) scanCache.dismiss(pieces);
        detections = RESULTS.detections();
        return dismissed;
    }

    public static int scannedChunks() { return completedChunks.size(); }
    public static int scanTotalChunks() { return scheduledChunks.size(); }
    public static boolean templatesLoading() { return registered && !selected.isEmpty() && !templatesReady && loadError == null; }
    public static String templateError() { return loadError; }

    public static ScanProgress scanProgress() {
        if (!registered || world == null || selected.isEmpty()) return progress(ScanStage.IDLE, 0, 0, 0);
        return currentScanProgress();
    }

    static ScanProgress currentScanProgress() {
        int total = scheduledChunks.size();
        int completed = Math.min(completedChunks.size(), total);
        if (loadError != null) return progress(ScanStage.FAILED, completed, total, 0);
        if (!templatesReady) return progress(ScanStage.LOADING, completed, total, 0);
        if (indexDirty) return progress(ScanStage.PREPARING, completed, total, 0);
        if (anchors.isEmpty()) return progress(ScanStage.IDLE, 0, 0, 0);
        if (!passStarted) return progress(ScanStage.PREPARING, completed, total, 0);
        // Walking may reconcile the existing load list without creating any
        // actual scan work. Keep its completed HUD rather than flashing 99%.
        if (total > 0 && completed == total && activeJobs.isEmpty() && priorityQueued.isEmpty()
                && freshQueued.isEmpty() && NEW_CHUNKS.isEmpty() && READY_CHANGED_CHUNKS.isEmpty()
                && changedChunkPreparation == null) {
            return progress(ScanStage.COMPLETE, completed, total, 1);
        }
        if (total == 0) return progress(ScanStage.PREPARING, 0, 0, 0);
        double partial = 0;
        for (ScanJob job : activeJobs.values()) {
            if (!completedChunks.contains(job.packed)) partial += job.fraction;
        }
        return progress(ScanStage.SCANNING, completed, total, Math.min(0.999, (completed + partial) / total));
    }

    static double chunkScanFraction(int sections, int section, int block, boolean checkingCandidate) {
        if (sections <= 0) return 0;
        // The chunk cursor advances before its triggered template comparison finishes.
        double visited = (long) section * 4096 + block - (checkingCandidate ? 1 : 0);
        return Math.clamp(visited / (sections * 4096.0), 0, 1);
    }

    private static ScanProgress progress(ScanStage stage, int completed, int total, double fraction) {
        return new ScanProgress(stage, completed, total, fraction, scanRevision);
    }

    private static void changeWorld(ClientLevel level) {
        if (world == level) return;
        closeScanCache();
        world = level;
        StructureFinderXaeroBridge.clear();
        LOADED_CHUNKS.clear();
        TICK_CHUNKS.clear();
        RESULTS.clear();
        detections = List.of();
        indexDirty = true;
        pendingIndex = null;
        resetPass();
    }

    private static void resetPass() {
        cancelJobs();
        snapshotCache.clear();
        lastScanCenter = null;
        reconcileChunks = List.of();
        reconcileIndex = 0;
        reconcileWorld = null;
        scanRevision++;
        pass = new ArrayList<>();
        scheduledChunks.clear();
        completedChunks.clear();
        previouslyScannedChunks.clear();
        missingNeighbours.clear();
        priorityQueued.clear();
        priorityChunks.clear();
        freshQueued.clear();
        freshChunks.clear();
        chunkDispatches = 0;
        NEW_CHUNKS.clear();
        CHANGED_CHUNKS.clear();
        READY_CHANGED_CHUNKS.clear();
        DEFERRED_CHUNKS.clear();
        passIndex = 0;
        passStarted = false;
        passPreparation = null;
        newChunkPreparation = null;
        changedChunkPreparation = null;
    }

    private static void tick(Minecraft client) {
        if (client.level != world) changeWorld(client.level);
        if (world == null || client.player == null || selected.isEmpty()) return;
        if (client.isPaused() && !isXaeroScreen(client.gui.screen())) return;
        RESULTS.expire(System.nanoTime() / 1_000_000);
        detections = RESULTS.detections();
        sendNotifications(client);
        scanTick++;
        TICK_CHUNKS.clear();
        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
        pruneLoadedChunks(client, Math.min(deadline, System.nanoTime() + TICK_BUDGET_NANOS / 8));
        if (!prepareTemplates(deadline)) return;
        int newSimilarity = SparkFixConfig.structureFinderSimilarity();
        if (newSimilarity != similarity) {
            similarity = newSimilarity;
            RESULTS.clearMatches();
            detections = List.of();
            indexDirty = true;
            pendingIndex = null;
            resetPass();
        }
        if (indexDirty && !rebuildIndex(deadline)) return;
        if (anchors.isEmpty()) return;
        prepareScanCache(client);
        if (scanCache != null && !scanCache.ready()) return;
        configureWorkers(SparkFixConfig.structureFinderScanThreads());
        if (!passStarted) beginPass(client);
        long preparationDeadline = Math.min(deadline, System.nanoTime() + TICK_BUDGET_NANOS / 4);
        preparePass(preparationDeadline);
        prepareNewChunks(preparationDeadline);
        ChunkPos scanCenter = client.player.chunkPosition();
        // Ordinary edits invalidate stale snapshots immediately, but their
        // rescan batch starts only after crossing a chunk boundary.
        if (!scanCenter.equals(lastScanCenter)) {
            if (lastScanCenter != null) {
                releaseChangedChunks();
                // Also discover cache entries whose load callback preceded registration.
                if (passPreparation == null) passPreparation = new PassPreparation(scanCenter, loadedScanRadius(client));
            }
            lastScanCenter = scanCenter;
        }
        prepareChangedChunks(Math.min(deadline, System.nanoTime() + TICK_BUDGET_NANOS / 4));
        collectJobs(deadline);
        if (passIndex >= 1024) {
            pass = new ArrayList<>(pass.subList(passIndex, pass.size()));
            passIndex = 0;
        }
        while (System.nanoTime() < deadline) {
            if (snapshotPreparation == null) {
                if (activeJobs.size() >= workerCount + 1) break;
                Long packed = nextScanChunk(scanCenter);
                if (packed == null) break;
                if (loadedChunk(world, ChunkPos.getX(packed), ChunkPos.getZ(packed)) == null) {
                    forgetChunk(packed);
                    continue;
                }
                ScanJob job = new ScanJob(packed, scanRevision, anchors, client.player.getBlockY());
                activeJobs.put(packed, job);
                snapshotPreparation = new SnapshotPreparation(job, client.player.getBlockY());
            }
            if (snapshotPreparation.step()) snapshotPreparation = null;
        }
    }

    private static boolean isXaeroScreen(net.minecraft.client.gui.screens.Screen screen) {
        if (screen == null) return false;
        // Keep Xaero optional, and also recognise subclasses supplied by addons.
        for (Class<?> type = screen.getClass(); type != null; type = type.getSuperclass()) {
            String name = type.getName();
            if (name.startsWith("xaero.") || name.startsWith("xaeroplus.")) return true;
        }
        return false;
    }

    private static int chunkDistance(ChunkPos first, ChunkPos second) {
        return Math.max(Math.abs(first.x() - second.x()), Math.abs(first.z() - second.z()));
    }

    private static void receiveChunk(long packed) {
        snapshotCache.remove(packed);
        // CHUNK_LOAD also fires for repeated full packets at an existing coordinate.
        if (LOADED_CHUNKS.add(packed)) {
            NEW_CHUNKS.add(packed);
            if (passStarted) scheduleChunk(packed, true);
        } else {
            invalidateChunkAndDependants(packed);
        }
    }

    /** Called on the client thread after a live block's type changes. */
    public static void onBlockChanged(ClientLevel level, BlockPos position, Block oldBlock, Block newBlock) {
        if (!registered || world == null || level != world || selected.isEmpty() || oldBlock == newBlock) return;
        // Comparison materials include all samples, not just discovery anchors.
        // Unrelated edits cannot change a match and need no neighbourhood rescan.
        if (!indexDirty && !relevantMaterials.contains(oldBlock) && !relevantMaterials.contains(newBlock)) return;
        long packed = ChunkPos.pack(position.getX() >> 4, position.getZ() >> 4);
        receiveBlockChange(packed);
    }

    private static void receiveBlockChange(long packed) {
        // The palette cache must not survive an in-place LevelChunk mutation.
        snapshotCache.remove(packed);
        invalidateCachedRegion(packed);
        if (!passStarted) return;
        CHANGED_CHUNKS.add(packed);
        ChunkPos changed = ChunkPos.unpack(packed);
        // Cancel stale regions, including a partly copied neighbour. Do not
        // submit them again until movement releases the pending edit batch.
        List<Long> stale = new ArrayList<>();
        for (ScanJob job : activeJobs.values()) {
            if (chunkDistance(changed, ChunkPos.unpack(job.packed)) <= priorityRadius) stale.add(job.packed);
        }
        for (long dependant : stale) {
            cancelJob(dependant);
            if (previouslyScannedChunks.contains(dependant)) {
                DEFERRED_CHUNKS.add(dependant);
                completedChunks.add(dependant);
            }
            else queueRescan(dependant);
        }
    }

    private static void releaseChangedChunks() {
        READY_CHANGED_CHUNKS.addAll(CHANGED_CHUNKS);
        CHANGED_CHUNKS.clear();
        DEFERRED_CHUNKS.clear();
    }

    private static void invalidateChunkAndDependants(long packed) {
        List<Long> affected = new ArrayList<>();
        ChunkPos changed = ChunkPos.unpack(packed);
        for (int ring = 0; ring <= priorityRadius; ring++) {
            for (int offset = 0; offset < Math.max(1, ring * 8); offset++) {
                long candidate = ringChunk(changed, ring, offset);
                if (completedChunks.contains(candidate) || activeJobs.containsKey(candidate)
                        || DEFERRED_CHUNKS.contains(candidate)) affected.add(candidate);
            }
        }
        for (long candidate : affected) {
            cancelJob(candidate);
            DEFERRED_CHUNKS.remove(candidate);
            queueRescan(candidate);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String digest(String value) {
        return HexFormat.of().formatHex(sha256().digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static void closeScanCache() {
        if (scanCache != null) scanCache.close();
        scanCache = null;
        cacheIdentity = null;
    }

    private static void prepareScanCache(Minecraft client) {
        String identity;
        var server = client.getSingleplayerServer();
        if (server != null) {
            identity = "local:" + server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        } else {
            var data = client.getConnection() == null ? null : client.getConnection().getServerData();
            if (data == null) data = client.getCurrentServer();
            if (data == null) return;
            identity = "server:" + data.ip.trim().toLowerCase(Locale.ROOT);
        }
        String dimension = world.dimension().identifier().toString();
        String rules = "matcher-3:" + templateSignature + ":" + similarity;
        String key = identity + '\n' + dimension + '\n' + rules;
        if (key.equals(cacheIdentity)) return;
        closeScanCache();
        Path directory = FabricLoader.getInstance().getConfigDir().resolve("spark_fix")
                .resolve("structure-scan-cache").resolve(digest(identity).substring(0, 32));
        scanCache = new StructureFinderScanCache(directory.resolve(
                digest(dimension + '\n' + rules).substring(0, 32) + ".jsonl.gz"), rules);
        cacheIdentity = key;
    }

    private static void invalidateCachedRegion(long changed) {
        if (scanCache == null) return;
        ChunkPos center = ChunkPos.unpack(changed);
        // Include all template extents, even those currently unchecked.
        for (int ring = 0; ring <= 4; ring++) {
            for (int offset = 0; offset < Math.max(1, ring * 8); offset++) {
                scanCache.remove(ringChunk(center, ring, offset));
            }
        }
    }

    private static void scheduleChunk(long packed, boolean fresh) {
        // A CHUNK_LOAD notification can race a view-center update. Do not let
        // an old slot (or a Voxy-only LOD section) enter the progress denominator.
        if (!isLiveScannableChunk(packed)) {
            forgetChunk(packed);
            return;
        }
        enqueueChunk(packed, fresh);
    }

    private static void enqueueChunk(long packed, boolean fresh) {
        if (scheduledChunks.add(packed)) pass.add(packed);
        if (fresh && !completedChunks.contains(packed) && freshQueued.add(packed)) freshChunks.addLast(packed);
    }

    /** Effective vanilla cache radius, including the client's storage margin. */
    static int loadedScanRadius(Minecraft client) {
        if (client == null) return 5;
        return Math.clamp(client.options.getEffectiveRenderDistance() + 3, 5, 64);
    }

    private static boolean isLiveScannableChunk(long packed) {
        if (world == null) return false;
        ChunkPos pos = ChunkPos.unpack(packed);
        // Check the source directly: a load callback may follow a cached null
        // from the previous tick, before tick() clears the lookup cache again.
        LevelChunk chunk = world.getChunkSource().getChunk(pos.x(), pos.z(), ChunkStatus.FULL, false);
        TICK_CHUNKS.put(packed, chunk);
        if (chunk == null || chunk.getPos().pack() != packed) return false;
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level != world || client.player == null) return true;
        return chunkDistance(pos, client.player.chunkPosition()) <= loadedScanRadius(client);
    }

    /**
     * Reconciles a bounded number of remembered coordinates with the live client
     * chunk cache. This is a fallback for view-center moves where no unload event
     * is emitted; normal CHUNK_UNLOAD callbacks still remove entries immediately.
     */
    static int pruneLoadedChunks(Minecraft client, long deadline) {
        if (client == null || client.level != world || world == null || client.player == null) return 0;
        if (System.nanoTime() >= deadline) return 0;
        if (reconcileWorld != world || reconcileIndex >= reconcileChunks.size()) {
            LinkedHashSet<Long> keys = new LinkedHashSet<>(LOADED_CHUNKS);
            keys.addAll(scheduledChunks);
            keys.addAll(NEW_CHUNKS);
            keys.addAll(freshQueued);
            keys.addAll(priorityQueued);
            reconcileChunks = List.copyOf(keys);
            reconcileIndex = 0;
            reconcileWorld = world;
        }
        int checked = 0;
        while (checked < 128 && reconcileIndex < reconcileChunks.size() && System.nanoTime() < deadline) {
            long packed = reconcileChunks.get(reconcileIndex++);
            if (!isLiveScannableChunk(packed)) forgetChunk(packed);
            checked++;
        }
        if (reconcileIndex >= reconcileChunks.size()) {
            reconcileChunks = List.of();
            reconcileIndex = 0;
        }
        return checked;
    }

    static Long nextScanChunk(ChunkPos center) {
        // Three nearby/new tasks then one older task: flying never leaves new
        // landmarks behind a several-thousand-chunk initial queue.
        Long packed = (++chunkDispatches & 3) == 0 ? nextPassChunk() : null;
        if (packed == null) {
            for (int ring = 0; ring <= 4 && packed == null; ring++) {
                for (int offset = 0; offset < Math.max(1, ring * 8); offset++) {
                    long nearby = ringChunk(center, ring, offset);
                    if (scheduledChunks.contains(nearby) && !DEFERRED_CHUNKS.contains(nearby) && !activeJobs.containsKey(nearby)
                            && (!completedChunks.contains(nearby) || priorityQueued.contains(nearby))) {
                        packed = nearby;
                        break;
                    }
                }
            }
        }
        while (packed == null && !freshChunks.isEmpty()) {
            long arrived = freshChunks.removeFirst();
            freshQueued.remove(arrived);
            if (scheduledChunks.contains(arrived) && !DEFERRED_CHUNKS.contains(arrived) && !activeJobs.containsKey(arrived)
                    && !completedChunks.contains(arrived)) packed = arrived;
        }
        if (packed == null) packed = nextPassChunk();
        while (packed == null && !priorityChunks.isEmpty()) {
            long rescan = priorityChunks.removeFirst();
            priorityQueued.remove(rescan);
            if (scheduledChunks.contains(rescan) && !DEFERRED_CHUNKS.contains(rescan) && !activeJobs.containsKey(rescan)) packed = rescan;
        }
        if (packed != null) {
            if (freshQueued.remove(packed)) freshChunks.remove(packed);
            if (priorityQueued.remove(packed)) priorityChunks.remove(packed);
        }
        return packed;
    }

    private static Long nextPassChunk() {
        for (int skipped = 0; skipped < MAX_NEW_CHUNK_STEPS && passIndex < pass.size(); skipped++) {
            long packed = pass.get(passIndex++);
            if (scheduledChunks.contains(packed) && !DEFERRED_CHUNKS.contains(packed) && !activeJobs.containsKey(packed)
                    && !completedChunks.contains(packed)) return packed;
        }
        return null;
    }

    private static void queueRescan(long packed) {
        // A neighbouring update changes comparison coverage, not this chunk's
        // palette. Reuse its immutable snapshot and decoded section caches.
        if (DEFERRED_CHUNKS.contains(packed)) return;
        ScanJob job = activeJobs.get(packed);
        if (job != null) {
            job.rerun = true;
            return;
        }
        if (scheduledChunks.contains(packed)) {
            completedChunks.remove(packed);
            if (priorityQueued.add(packed)) priorityChunks.addLast(packed);
        }
    }

    private static void prepareChangedChunks(long deadline) {
        for (int steps = 0; steps < MAX_NEW_CHUNK_STEPS && System.nanoTime() < deadline; steps++) {
            if (changedChunkPreparation == null) {
                if (READY_CHANGED_CHUNKS.isEmpty()) break;
                long changed = READY_CHANGED_CHUNKS.iterator().next();
                READY_CHANGED_CHUNKS.remove(changed);
                changedChunkPreparation = new ChangedChunkPreparation(changed);
            }
            if (changedChunkPreparation.step()) changedChunkPreparation = null;
        }
    }

    private static void prepareNewChunks(long deadline) {
        // Reserve most of each tick for scanning even while chunk packets keep arriving.
        for (int steps = 0; steps < MAX_NEW_CHUNK_STEPS && System.nanoTime() < deadline; steps++) {
            if (newChunkPreparation == null) {
                if (NEW_CHUNKS.isEmpty()) break;
                long arrived = NEW_CHUNKS.iterator().next();
                NEW_CHUNKS.remove(arrived);
                newChunkPreparation = new NewChunkPreparation(arrived);
            }
            if (newChunkPreparation.step()) newChunkPreparation = null;
        }
    }

    private static void preparePass(long deadline) {
        for (int steps = 0; passPreparation != null && steps < MAX_NEW_CHUNK_STEPS
                && System.nanoTime() < deadline; steps++) {
            if (passPreparation.step()) passPreparation = null;
        }
    }

    private static void configureWorkers(int count) {
        if (workers == null) {
            workers = new ThreadPoolExecutor(count, count, 15, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> {
                Thread thread = new Thread(task, "spark-fix-structure-scan");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
            workers.allowCoreThreadTimeOut(true);
        } else if (count > workerCount) {
            workers.setMaximumPoolSize(count);
            workers.setCorePoolSize(count);
        } else if (count < workerCount) {
            workers.setCorePoolSize(count);
            workers.setMaximumPoolSize(count);
        }
        workerCount = count;
    }

    private static void cancelJob(long packed) {
        ScanJob job = activeJobs.remove(packed);
        if (job == null) return;
        job.cancelled = true;
        if (job.future != null) job.future.cancel(true);
        if (snapshotPreparation != null && snapshotPreparation.job == job) snapshotPreparation = null;
        if (workers != null) workers.purge();
    }

    private static void cancelJobs() {
        for (ScanJob job : activeJobs.values()) {
            job.cancelled = true;
            if (job.future != null) job.future.cancel(true);
        }
        activeJobs.clear();
        snapshotPreparation = null;
        if (workers != null) workers.purge();
    }

    private static void collectJobs(long deadline) {
        var iterator = activeJobs.entrySet().iterator();
        while (iterator.hasNext() && System.nanoTime() < deadline) {
            ScanJob job = iterator.next().getValue();
            if (job.future == null || !job.future.isDone()) continue;
            if (job.cancelled || job.revision != scanRevision || !scheduledChunks.contains(job.packed)) {
                iterator.remove();
                continue;
            }
            LevelChunk live = loadedChunk(world, ChunkPos.getX(job.packed), ChunkPos.getZ(job.packed));
            CachedSnapshot source = job.source;
            if (live == null) {
                iterator.remove();
                forgetChunk(job.packed);
                continue;
            }
            if (job.rerun || source == null || live != source.source || job.missingChunks.stream()
                    .anyMatch(packed -> loadedChunk(world, ChunkPos.getX(packed), ChunkPos.getZ(packed)) != null)) {
                iterator.remove();
                queueRescan(job.packed);
                continue;
            }
            try {
                if (job.pendingDetections == null) job.pendingDetections = job.future.get();
                // All shared result state and chat notifications stay on the client thread.
                // Large result batches obey the same frame budget as preparation.
                long now = System.nanoTime() / 1_000_000;
                while (job.resultIndex < job.pendingDetections.size()) {
                    if (System.nanoTime() >= deadline) return;
                    Detection detection = job.pendingDetections.get(job.resultIndex++);
                    String type = detection.type();
                    long age = job.cacheEntry == null ? 0 : Math.max(0,
                            System.currentTimeMillis() - job.cacheEntry.scannedAt().getOrDefault(type, System.currentTimeMillis()));
                    RESULTS.add(detection, now - age);
                    if (age >= 3_600_000 || (job.cacheEntry != null && job.cacheEntry.dismissed()
                            .contains(StructureFinderScanCache.detectionKey(detection)))) RESULTS.dismiss(detection);
                }
                if (scanCache != null && job.cacheEntry != null && job.missingChunks.isEmpty()) {
                    scanCache.put(job.cacheEntry);
                }
                iterator.remove();
                completedChunks.add(job.packed);
                previouslyScannedChunks.add(job.packed);
                if (job.missingChunks.isEmpty()) missingNeighbours.remove(job.packed);
                else missingNeighbours.put(job.packed, Set.copyOf(job.missingChunks));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (java.util.concurrent.ExecutionException exception) {
                iterator.remove();
                loadError = "Scan failed: " + exception.getCause().getClass().getSimpleName();
                SparkFixClient.LOGGER.error("Structure Finder background scan failed", exception.getCause());
                cancelJobs();
                return;
            }
        }
        detections = RESULTS.detections();
    }

    /** Copy palette storage only on the client thread, one section per budgeted step. */
    private static final class SnapshotPreparation {
        final ScanJob job;
        final ChunkPos center;
        final Long2ObjectOpenHashMap<ChunkSnapshot> region = new Long2ObjectOpenHashMap<>();
        final int playerY;
        final int radius;
        int ring, offset;
        ChunkSnapshotBuilder builder;

        SnapshotPreparation(ScanJob job, int playerY) {
            this.job = job;
            this.center = ChunkPos.unpack(job.packed);
            this.playerY = playerY;
            job.cached = scanCache == null ? null : scanCache.get(job.packed);
            int needed = priorityRadius;
            if (job.cached != null) {
                for (long dependency : job.cached.content().keySet()) {
                    needed = Math.max(needed, Math.min(4, chunkDistance(center, ChunkPos.unpack(dependency))));
                }
            }
            radius = needed;
        }

        boolean step() {
            if (job.cancelled) return true;
            if (job.rerun) {
                cancelJob(job.packed);
                queueRescan(job.packed);
                return true;
            }
            long packed = ringChunk(center, ring, offset);
            LevelChunk live = loadedChunk(world, ChunkPos.getX(packed), ChunkPos.getZ(packed));
            if (live == null) {
                builder = null;
                if (ring == 0) { cancelJob(job.packed); forgetChunk(job.packed); return true; }
                // Remember exactly which neighbour was absent. A later chunk
                // arrival only needs to revisit jobs that lacked that coverage.
                job.missingChunks.add(packed);
            } else {
                CachedSnapshot cached = snapshotCache.get(packed);
                if (cached == null || cached.source != live) {
                    if (builder == null || builder.source != live) builder = new ChunkSnapshotBuilder(live, playerY);
                    if (!builder.step()) return false;
                    cached = new CachedSnapshot(live, builder.finish());
                    builder = null;
                    snapshotCache.put(packed, cached);
                    if (snapshotCache.size() > MAX_SNAPSHOT_CACHE) {
                        snapshotCache.remove(snapshotCache.keySet().iterator().next());
                    }
                }
                region.put(packed, cached.snapshot);
                if (ring == 0) {
                    job.source = cached;
                    // A chunk without any selected discovery block cannot
                    // produce a candidate whose template needs neighbours.
                    // Avoid copying surrounding palettes for it.
                    StructureFinderScanFilter filter = discoveryFilter(job.index);
                    job.skipNeighbours = !cached.snapshot.hasPotentialTrigger(filter)
                            && (job.cached == null || job.cached.content().size() == 1);
                }
            }
            if (++offset == Math.max(1, ring * 8)) { ring++; offset = 0; }
            if (job.skipNeighbours) {
                job.future = workers.submit(() -> job.scan(region));
                return true;
            }
            if (ring <= radius) return false;
            // The submitted scan reads only the copied region.
            job.future = workers.submit(() -> job.scan(region));
            return true;
        }
    }

    private record CachedSnapshot(LevelChunk source, ChunkSnapshot snapshot) {}

    static final class ChunkSnapshot {
        final long packed;
        final int minimumY;
        final PalettedContainer<BlockState>[] states;
        final BlockPos[] markers;
        final StructureFinderScanPlan.Terrain terrain;
        final AtomicReferenceArray<Block[]> decoded;
        final AtomicIntegerArray reads;
        final AtomicInteger decodedCount = new AtomicInteger();
        private volatile TriggerCache triggerCache;
        private volatile Set<Block> paletteMaterials;
        private volatile String contentSignature;

        ChunkSnapshot(long packed, int minimumY, PalettedContainer<BlockState>[] states,
                      BlockPos[] markers, StructureFinderScanPlan.Terrain terrain) {
            this.packed = packed;
            this.minimumY = minimumY;
            this.states = states;
            this.markers = markers;
            this.terrain = terrain;
            decoded = new AtomicReferenceArray<>(states.length);
            reads = new AtomicIntegerArray(states.length);
        }

        synchronized TriggerCache triggers(StructureFinderScanFilter filter) {
            TriggerCache cached = triggerCache;
            if (cached == null || cached.filter.selectionIdentity != filter.selectionIdentity) {
                triggerCache = cached = new TriggerCache(filter, states.length);
            }
            return cached;
        }

        boolean hasPotentialTrigger(StructureFinderScanFilter filter) {
            for (PalettedContainer<BlockState> section : states) {
                if (filter.maybeHas(section)) return true;
            }
            return false;
        }

        synchronized Set<Block> materials() {
            if (paletteMaterials == null) {
                Set<Block> materials = new LinkedHashSet<>();
                for (var section : states) {
                    if (section != null) section.forEachInPalette(state -> materials.add(state.getBlock()));
                }
                // Unused palette entries may overestimate availability, which
                // costs work but can never exclude a real matching template.
                paletteMaterials = Set.copyOf(materials);
            }
            return paletteMaterials;
        }

        synchronized String contentSignature() {
            if (contentSignature != null) return contentSignature;
            MessageDigest hash = sha256();
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                buffer.writeInt(minimumY);
                buffer.writeInt(states.length);
                hash.update(buffer.nioBuffer());
                for (var section : states) {
                    buffer.clear();
                    buffer.writeBoolean(section != null);
                    if (section != null) section.write(buffer);
                    hash.update(buffer.nioBuffer());
                }
                buffer.clear();
                for (long marker : Arrays.stream(markers).mapToLong(BlockPos::asLong).sorted().toArray()) {
                    buffer.writeLong(marker);
                }
                hash.update(buffer.nioBuffer());
                contentSignature = HexFormat.of().formatHex(hash.digest());
                return contentSignature;
            } finally {
                buffer.release();
            }
        }

        Block blockAt(int x, int y, int z) {
            int section = (y - minimumY) >> 4;
            if (section < 0 || section >= states.length) return null;
            var contents = states[section];
            if (contents == null) return Blocks.AIR;
            int offset = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
            Block[] blocks = decoded.get(section);
            if (blocks != null) return blocks[offset];
            // Decode only sections repeatedly visited by template comparisons,
            // on the worker. Four per snapshot bounds this cache to 64 KiB with
            // compressed references, instead of expanding every loaded section.
            if (decodedCount.get() < 4 && reads.incrementAndGet(section) == 128
                    && decodedCount.getAndIncrement() < 4) {
                blocks = new Block[4096];
                for (int index = 0; index < blocks.length; index++) {
                    blocks[index] = contents.get(index & 15, index >> 8, index >> 4 & 15).getBlock();
                }
                decoded.set(section, blocks);
                return blocks[offset];
            }
            return contents.get(x & 15, y & 15, z & 15).getBlock();
        }
    }

    private static final class TriggerCache {
        final StructureFinderScanFilter filter;
        final AtomicReferenceArray<StructureFinderScanFilter.Positions> sections;

        TriggerCache(StructureFinderScanFilter filter, int count) {
            this.filter = filter;
            sections = new AtomicReferenceArray<>(count);
        }
    }

    private static synchronized StructureFinderScanFilter discoveryFilter(Map<Block, List<Anchor>> index) {
        if (discoveryFilter != null && discoveryFilter.selectionIdentity == index) return discoveryFilter;
        Map<Block, Boolean> triggers = new IdentityHashMap<>();
        for (var entry : index.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            triggers.put(entry.getKey(), entry.getValue().stream()
                    .allMatch(anchor -> anchor.pattern.source.type().equals("buried_treasure")));
        }
        return discoveryFilter = new StructureFinderScanFilter(index, triggers);
    }

    private static final class ChunkSnapshotBuilder {
        final LevelChunk source;
        final PalettedContainer<BlockState>[] states;
        final int playerY;
        int section;

        @SuppressWarnings("unchecked")
        ChunkSnapshotBuilder(LevelChunk source, int playerY) {
            this.source = source;
            this.playerY = playerY;
            states = (PalettedContainer<BlockState>[]) new PalettedContainer<?>[source.getSections().length];
        }

        boolean step() {
            if (section < states.length) {
                LevelChunkSection live = source.getSections()[section];
                if (!live.hasOnlyAir()) states[section] = live.getStates().copy();
                section++;
            }
            return section >= states.length;
        }

        ChunkSnapshot finish() {
            int minimumY = source.getSectionYFromSectionIndex(0) << 4;
            var terrain = new StructureFinderScanPlan.Terrain(minimumY, states.length,
                    snapshotColumnHeight(states, minimumY, Heightmap.Types.MOTION_BLOCKING_NO_LEAVES),
                    snapshotColumnHeight(states, minimumY, Heightmap.Types.OCEAN_FLOOR), world.getSeaLevel(), playerY,
                    world.dimension().identifier().getPath());
            return new ChunkSnapshot(source.getPos().pack(), minimumY, states,
                    source.getBlockEntitiesPos().toArray(BlockPos[]::new), terrain);
        }
    }

    static int snapshotColumnHeight(PalettedContainer<BlockState>[] states, int minimumY, Heightmap.Types type) {
        // OCEAN_FLOOR is not sent to the client. ChunkAccess.getHeight would
        // prime all 256 columns synchronously; the priority hint needs only one.
        var predicate = type.isOpaque();
        for (int section = states.length - 1; section >= 0; section--) {
            if (states[section] == null) continue;
            for (int y = 15; y >= 0; y--) {
                if (predicate.test(states[section].get(8, y, 8))) return minimumY + section * 16 + y;
            }
        }
        return minimumY - 1;
    }

    static final class SnapshotLookup implements BlockLookup {
        final Long2ObjectOpenHashMap<ChunkSnapshot> region;
        final ChunkSnapshot[] grid;
        final int minimumX, minimumZ, width, depth;
        int lastX = Integer.MIN_VALUE, lastZ = Integer.MIN_VALUE;
        ChunkSnapshot last;

        SnapshotLookup(Long2ObjectOpenHashMap<ChunkSnapshot> region) {
            this.region = region;
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            for (long packed : region.keySet()) {
                minX = Math.min(minX, ChunkPos.getX(packed));
                minZ = Math.min(minZ, ChunkPos.getZ(packed));
                maxX = Math.max(maxX, ChunkPos.getX(packed));
                maxZ = Math.max(maxZ, ChunkPos.getZ(packed));
            }
            minimumX = minX;
            minimumZ = minZ;
            width = region.isEmpty() ? 0 : maxX - minX + 1;
            depth = region.isEmpty() ? 0 : maxZ - minZ + 1;
            // Production regions span at most 9x9 chunks. Keep a map fallback
            // for sparse callers without allocating a huge coordinate grid.
            grid = width > 0 && width <= 9 && depth > 0 && depth <= 9 ? new ChunkSnapshot[width * depth] : null;
            if (grid != null) {
                for (var entry : region.long2ObjectEntrySet()) {
                    int x = ChunkPos.getX(entry.getLongKey()) - minimumX;
                    int z = ChunkPos.getZ(entry.getLongKey()) - minimumZ;
                    grid[z * width + x] = entry.getValue();
                }
            }
        }

        @Override public Block get(int x, int y, int z) {
            int chunkX = x >> 4, chunkZ = z >> 4;
            if (lastX != chunkX || lastZ != chunkZ) {
                lastX = chunkX;
                lastZ = chunkZ;
                if (grid == null) last = region.get(ChunkPos.pack(chunkX, chunkZ));
                else {
                    int localX = chunkX - minimumX, localZ = chunkZ - minimumZ;
                    last = localX >= 0 && localX < width && localZ >= 0 && localZ < depth
                            ? grid[localZ * width + localX] : null;
                }
            }
            return last == null ? null : last.blockAt(x, y, z);
        }
    }

    private static boolean prepareTemplates(long deadline) {
        if (templatesReady) return true;
        if (loadError != null) return false;
        if (templateFuture == null) templateFuture = StructureFinderCatalog.patternsFuture();
        if (!templateFuture.isDone()) return false;
        if (rawPatterns == null) {
            try {
                rawPatterns = templateFuture.join();
                if (rawPatterns.isEmpty()) throw new IllegalStateException("No vanilla structure fingerprints loaded");
                SparkFixClient.LOGGER.info("Structure Finder loaded {} vanilla structure fingerprints", rawPatterns.size());
            } catch (CompletionException | IllegalStateException exception) {
                loadError = exception.toString();
                SparkFixClient.LOGGER.error("Unable to load Structure Finder templates", exception);
                return false;
            }
        }
        // Resolve registry entries in small batches on the client thread.
        while (compileIndex < rawPatterns.size() && System.nanoTime() < deadline) {
            CompiledPattern pattern = compile(rawPatterns.get(compileIndex++));
            if (pattern != null) {
                compiledPatterns.add(pattern);
                TEMPLATE_DIGEST.update(pattern.source.toString().getBytes(StandardCharsets.UTF_8));
                for (Sample sample : pattern.samples) {
                    TEMPLATE_DIGEST.update(sample.acceptedBlocks.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        templatesReady = compileIndex == rawPatterns.size();
        if (templatesReady && templateSignature == null) {
            for (Block block : BuiltInRegistries.BLOCK) {
                String registry = BuiltInRegistries.BLOCK.getKey(block) + ":" + Block.getId(block.defaultBlockState())
                        + ":" + block.getStateDefinition().getPossibleStates().size();
                TEMPLATE_DIGEST.update(registry.getBytes(StandardCharsets.UTF_8));
            }
            FabricLoader.getInstance().getAllMods().stream()
                    .map(mod -> mod.getMetadata().getId() + ':' + mod.getMetadata().getVersion().getFriendlyString())
                    .sorted().forEach(mod -> TEMPLATE_DIGEST.update(mod.getBytes(StandardCharsets.UTF_8)));
            templateSignature = HexFormat.of().formatHex(TEMPLATE_DIGEST.digest());
        }
        return templatesReady;
    }

    private static CompiledPattern compile(StructureFinderCatalog.Pattern pattern) {
        List<Sample> samples = new ArrayList<>();
        for (int index : orderedSampleIndices(pattern)) {
            StructureFinderCatalog.Sample sample = pattern.samples().get(index);
            Identifier id = Identifier.tryParse(sample.blockId());
            if (id == null) return null;
            Block block = BuiltInRegistries.BLOCK.getValue(id);
            if (block == null || block == Blocks.AIR) return null;
            List<Block> accepted = new ArrayList<>();
            for (String acceptedId : StructureFinderCatalog.acceptedBlockIds(pattern.type(), sample.blockId())) {
                Identifier parsed = Identifier.tryParse(acceptedId);
                if (parsed == null) continue;
                Block replacement = BuiltInRegistries.BLOCK.getValue(parsed);
                if (replacement != null && replacement != Blocks.AIR && !accepted.contains(replacement)) {
                    accepted.add(replacement);
                }
            }
            if (!accepted.contains(block)) accepted.add(block);
            samples.add(new Sample(sample.x(), sample.y(), sample.z(), block, List.copyOf(accepted),
                    pattern.type().equals("village") && StructureFinderCatalog.isVillageAnchor(sample.blockId())));
        }
        if (samples.size() < 8) return null;
        int transformCount = pattern.type().equals("buried_treasure") || pattern.type().equals("monster_room")
                || isFortressCrossing(pattern) ? 1 : 8;
        List<Transform> transforms = new ArrayList<>(transformCount);
        for (int rotation = 0; rotation < transformCount; rotation++) {
            int[] x = new int[samples.size()];
            int[] z = new int[samples.size()];
            for (int index = 0; index < samples.size(); index++) {
                Sample sample = samples.get(index);
                x[index] = rotateX(sample.x, sample.z, rotation);
                z[index] = rotateZ(sample.x, sample.z, rotation);
            }
            transforms.add(new Transform(x, z));
        }
        return new CompiledPattern(pattern, List.copyOf(samples), List.copyOf(transforms));
    }

    /** Pure planning helpers also used by the headless scan regression. */
    static int[] orderedSampleIndices(StructureFinderCatalog.Pattern pattern) {
        Integer[] indices = new Integer[pattern.samples().size()];
        for (int index = 0; index < indices.length; index++) indices[index] = index;
        Arrays.sort(indices, Comparator.comparingInt((Integer index) -> {
            var sample = pattern.samples().get(index);
            String id = sample.blockId();
            if (pattern.type().equals("desert_pyramid") && sample.equals(pattern.anchor())) return 1000;
            if (pattern.type().equals("buried_treasure")) {
                return id.equals("minecraft:chest") ? 2 : id.equals("minecraft:sandstone") ? 1 : 0;
            }
            boolean marker = pattern.type().equals("village") && StructureFinderCatalog.isVillageAnchor(id)
                    || pattern.type().equals("trail_ruins") && StructureFinderCatalog.isTrailRuinsAnchor(id);
            return featurePriority(id) + (marker ? 1000 : 0);
        }).reversed());
        return Arrays.stream(indices).mapToInt(Integer::intValue).toArray();
    }

    static int discoveryAnchorCount(StructureFinderCatalog.Pattern pattern, int percentage) {
        // Treasure checks start only at a chest, never at the surrounding terrain.
        if (pattern.type().equals("buried_treasure") || pattern.type().equals("monster_room")
                || pattern.type().equals("desert_pyramid") || isFortressCrossing(pattern)) return 1;
        int count = pattern.samples().size();
        // Fossil overlays may replace an arbitrary number of valid bone samples.
        // Try every remaining bone position, without treating ordinary ore as a trigger.
        if (pattern.type().equals("fossil")) return count;
        int required = (count * percentage + 99) / 100;
        // If at most N samples may be missing, N+1 discovery positions
        // guarantee a surviving anchor. Repeated rare positions are preferable
        // to an arbitrary backup plank that triggers throughout ordinary builds.
        int origins = count - required + 1;
        if (pattern.type().equals("village")) {
            int markers = (int) pattern.samples().stream()
                    .filter(sample -> StructureFinderCatalog.isVillageAnchor(sample.blockId())).count();
            origins = Math.min(origins, markers);
        } else if (pattern.type().equals("trail_ruins")) {
            int markers = (int) pattern.samples().stream()
                    .filter(sample -> StructureFinderCatalog.isTrailRuinsAnchor(sample.blockId())).count();
            origins = Math.min(origins, markers);
        }
        return origins;
    }

    private static boolean isFortressCrossing(StructureFinderCatalog.Pattern pattern) {
        return pattern.type().equals("fortress") && pattern.template().equals("vanilla:fortress_bridge_crossing");
    }

    private static int featurePriority(String id) {
        if (id.contains("spawner") || id.contains("vault") || id.contains("end_portal")) return 100;
        if (StructureFinderCatalog.isVillageAnchor(id) || id.contains("chest")) return 95;
        if (id.contains("dispenser") || id.contains("dropper") || id.contains("tnt")) return 90;
        if (id.contains("terracotta") || id.contains("obsidian") || id.contains("prismarine")
                || id.contains("sulfur") || id.contains("cinnabar") || id.contains("purpur")
                || id.contains("gold_block") || id.contains("bone_block")) return 80;
        if (id.contains("lantern") || id.contains("shroomlight")) return 70;
        if (id.contains("sculk") || id.contains("cobweb")) return 60;
        if (id.contains("brick")) return 50;
        if (id.contains("wall") || id.contains("trapdoor") || id.contains("ladder")) return 35;
        if (id.contains("stairs") || id.contains("slab") || id.contains("fence")) return 25;
        if (id.contains("log") || id.contains("wood") || id.contains("stem")) return 10;
        if (id.contains("planks")) return 5;
        return 20;
    }

    private static boolean rebuildIndex(long deadline) {
        String dimension = world.dimension().identifier().getPath();
        Map<String, String> dimensions = new HashMap<>();
        for (var entry : StructureFinderCatalog.entries()) dimensions.put(entry.id(), entry.dimension());
        if (pendingIndex == null) {
            pendingIndex = new IdentityHashMap<>();
            pendingRelevantMaterials = new LinkedHashSet<>();
            indexCompileIndex = 0;
            priorityRadius = 1;
            resetPass();
        }
        // Fingerprints are bounded (treasure checks use 125 samples). Resume between them
        // rather than indexing every selected template in a single tick.
        while (indexCompileIndex < compiledPatterns.size() && System.nanoTime() < deadline) {
            CompiledPattern pattern = compiledPatterns.get(indexCompileIndex++);
            if (!selected.contains(pattern.source.type())) continue;
            String expected = dimensions.get(pattern.source.type());
            // Keep custom dimensions searchable rather than assuming their world generation.
            if (!"any".equals(expected)
                    && (dimension.equals("overworld") || dimension.equals("the_nether") || dimension.equals("the_end"))
                    && !dimension.equals(switch (expected) {
                        case "nether" -> "the_nether";
                        case "end" -> "the_end";
                        default -> "overworld";
                    })) continue;
            int originCount = discoveryAnchorCount(pattern.source, similarity);
            for (Sample sample : pattern.samples) pendingRelevantMaterials.addAll(sample.acceptedBlocks);
            int required = pattern.source.type().equals("buried_treasure") || isFortressCrossing(pattern.source)
                    ? pattern.samples.size()
                    : (pattern.samples.size() * similarity + 99) / 100;
            priorityRadius = Math.max(priorityRadius, Math.min(4,
                    (Math.max(pattern.source.sizeX(), pattern.source.sizeZ()) + 14) / 16));
            for (int origin = 0; origin < originCount; origin++) {
                Sample sample = pattern.samples.get(origin);
                Anchor anchor = new Anchor(pattern, origin, required);
                List<Block> discoveryBlocks = pattern.source.type().equals("fossil")
                        ? List.of(sample.block) : sample.acceptedBlocks;
                for (Block block : discoveryBlocks) {
                    pendingIndex.computeIfAbsent(block, ignored -> new ArrayList<>()).add(anchor);
                }
            }
        }
        if (indexCompileIndex < compiledPatterns.size()) return false;
        anchors = pendingIndex;
        relevantMaterials = Set.copyOf(pendingRelevantMaterials);
        pendingRelevantMaterials = null;
        Set<String> activeTypes = new LinkedHashSet<>();
        for (List<Anchor> group : anchors.values()) {
            for (Anchor anchor : group) activeTypes.add(anchor.pattern.source.type());
        }
        indexedTypes = Set.copyOf(activeTypes);
        STATE_ANCHORS.clear();
        pendingIndex = null;
        indexDirty = false;
        return true;
    }

    private static void beginPass(Minecraft client) {
        scanRevision++;
        ChunkPos center = client.player.chunkPosition();
        pass = new ArrayList<>();
        scheduledChunks.clear();
        completedChunks.clear();
        previouslyScannedChunks.clear();
        missingNeighbours.clear();
        priorityChunks.clear();
        priorityQueued.clear();
        freshChunks.clear();
        freshQueued.clear();
        chunkDispatches = 0;
        NEW_CHUNKS.clear();
        CHANGED_CHUNKS.clear();
        READY_CHANGED_CHUNKS.clear();
        DEFERRED_CHUNKS.clear();
        changedChunkPreparation = null;
        passIndex = 0;
        passStarted = true;
        passPreparation = new PassPreparation(center,
                loadedScanRadius(client));
    }

    private static void forgetChunk(long packed) {
        LOADED_CHUNKS.remove(packed);
        NEW_CHUNKS.remove(packed);
        CHANGED_CHUNKS.remove(packed);
        READY_CHANGED_CHUNKS.remove(packed);
        DEFERRED_CHUNKS.remove(packed);
        scheduledChunks.remove(packed);
        completedChunks.remove(packed);
        previouslyScannedChunks.remove(packed);
        missingNeighbours.remove(packed);
        priorityQueued.remove(packed);
        priorityChunks.remove(packed);
        freshQueued.remove(packed);
        freshChunks.remove(packed);
        TICK_CHUNKS.remove(packed);
        if (newChunkPreparation != null && newChunkPreparation.center.pack() == packed) {
            newChunkPreparation = null;
        }
        cancelJob(packed);
        snapshotCache.remove(packed);
    }

    /** Visit concentric chunk rings without allocating or sorting a whole area. */
    static long ringChunk(ChunkPos center, int ring, int offset) {
        if (ring == 0) return center.pack();
        int sideLength = ring * 2;
        int along = offset % sideLength;
        return switch (offset / sideLength) {
            case 0 -> ChunkPos.pack(center.x() - ring + along, center.z() - ring);
            case 1 -> ChunkPos.pack(center.x() + ring, center.z() - ring + along);
            case 2 -> ChunkPos.pack(center.x() + ring - along, center.z() + ring);
            default -> ChunkPos.pack(center.x() - ring, center.z() + ring - along);
        };
    }

    private static final class PassPreparation {
        final ChunkPos center;
        final int radius;
        int ring, offset;

        PassPreparation(ChunkPos center, int radius) {
            this.center = center;
            this.radius = radius;
        }

        boolean step() {
            if (ring > radius) return true;
            long packed = ringChunk(center, ring, offset);
            ChunkPos pos = ChunkPos.unpack(packed);
            // Covers chunks received before callbacks were registered, while
            // still reading only the existing client cache (create=false).
            if (loadedChunk(world, pos.x(), pos.z()) != null) {
                LOADED_CHUNKS.add(packed);
                scheduleChunk(packed, false);
            } else forgetChunk(packed);
            if (++offset == Math.max(1, ring * 8)) {
                ring++;
                offset = 0;
            }
            return ring > radius;
        }
    }

    private static final class NewChunkPreparation {
        final ChunkPos center;
        int ring, offset;

        NewChunkPreparation(long packed) { center = ChunkPos.unpack(packed); }

        boolean step() {
            long packed = ringChunk(center, ring, offset);
            // A new neighbour may complete a template that previously lacked
            // loaded samples. Each neighbour is scheduled as one budgeted step.
            if (LOADED_CHUNKS.contains(packed)) {
                scheduleChunk(packed, ring == 0);
                // A complete snapshot region needs no repeat just because an
                // unrelated chunk arrived nearby. Retry only missing coverage.
                if (needsNewNeighbour(packed, center.pack())) {
                    DEFERRED_CHUNKS.remove(packed);
                    queueRescan(packed);
                }
            }
            if (++offset == Math.max(1, ring * 8)) {
                ring++;
                offset = 0;
            }
            return ring > priorityRadius;
        }
    }

    static boolean needsNewNeighbour(long packed, long arrived) {
        ScanJob job = activeJobs.get(packed);
        if (job != null) return job.missingChunks.contains(arrived);
        Set<Long> missing = missingNeighbours.get(packed);
        return completedChunks.contains(packed) && missing != null && missing.contains(arrived);
    }

    /** Coalesce changed blocks by chunk, then invalidate one dependant per step. */
    private static final class ChangedChunkPreparation {
        final ChunkPos center;
        int ring, offset;

        ChangedChunkPreparation(long packed) { center = ChunkPos.unpack(packed); }

        boolean step() {
            long packed = ringChunk(center, ring, offset);
            ScanJob job = activeJobs.get(packed);
            if (job != null && job.rerun) cancelJob(packed);
            if (scheduledChunks.contains(packed) && !activeJobs.containsKey(packed)) queueRescan(packed);
            if (++offset == Math.max(1, ring * 8)) { ring++; offset = 0; }
            return ring > priorityRadius;
        }
    }

    private static LevelChunk loadedChunk(ClientLevel level, int x, int z) {
        long packed = ChunkPos.pack(x, z);
        LevelChunk cached = TICK_CHUNKS.get(packed);
        if (cached != null || TICK_CHUNKS.containsKey(packed)) return cached;
        // false is deliberate: never substitute an empty chunk or request new chunks.
        LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        TICK_CHUNKS.put(packed, chunk);
        return chunk;
    }

    private static Block blockAt(ClientLevel level, int x, int y, int z) {
        if (level.isOutsideBuildHeight(y)) return null;
        LevelChunk chunk = loadedChunk(level, x >> 4, z >> 4);
        if (chunk == null) return null;
        return chunk.getSections()[chunk.getSectionIndex(y)]
                .getBlockState(x & 15, y & 15, z & 15).getBlock();
    }

    private static List<Anchor> stateAnchors(BlockState state) {
        return STATE_ANCHORS.computeIfAbsent(state, value -> {
            List<Anchor> possible = anchors.get(value.getBlock());
            return possible == null ? List.of() : possible;
        });
    }

    /** A dynamically selected whitelist; irrelevant terrain is skipped in one tight batch. */
    static int nextTrigger(PalettedContainer<BlockState> states, int start, int end) {
        for (int index = start; index < end; index++) {
            if (!stateAnchors(states.get(index & 15, index >> 8, index >> 4 & 15)).isEmpty()) return index;
        }
        return end;
    }

    static int[] sectionVisitOrder(int count, int surface) {
        int[] order = new int[count];
        if (count == 0) return order;
        surface = Math.clamp(surface, 0, count - 1);
        int index = 0;
        order[index++] = surface;
        for (int distance = 1; index < count; distance++) {
            if (surface - distance >= 0) order[index++] = surface - distance;
            if (surface + distance < count) order[index++] = surface + distance;
        }
        return order;
    }

    private static final class ScanJob {
        final long packed, revision;
        final Map<Block, List<Anchor>> index;
        final Set<String> types;
        final int playerY;
        final boolean persistent;
        final Set<Long> missingChunks = new LinkedHashSet<>();
        boolean skipNeighbours;
        volatile boolean cancelled;
        volatile double fraction;
        boolean rerun;
        Future<List<Detection>> future;
        List<Detection> pendingDetections;
        int resultIndex;
        CachedSnapshot source;
        StructureFinderScanCache.Entry cached;
        StructureFinderScanCache.Entry cacheEntry;

        ScanJob(long packed, long revision, Map<Block, List<Anchor>> index) {
            this(packed, revision, index, Integer.MIN_VALUE);
        }

        ScanJob(long packed, long revision, Map<Block, List<Anchor>> index, int playerY) {
            this.packed = packed;
            this.revision = revision;
            this.index = index;
            types = indexedTypes;
            this.playerY = playerY;
            persistent = scanCache != null;
        }

        List<Detection> scan(Long2ObjectOpenHashMap<ChunkSnapshot> region) {
            ChunkSnapshot chunk = region.get(packed);
            if (chunk == null || cancelled) return List.of();
            var content = new LinkedHashMap<Long, String>();
            if (persistent || cached != null) {
                for (var snapshot : region.values()) {
                    if (cancelled || Thread.currentThread().isInterrupted()) return List.of();
                    content.put(snapshot.packed, snapshot.contentSignature());
                }
                for (long missing : missingChunks) content.put(missing, "missing");
            }
            var scanned = new LinkedHashMap<String, List<Detection>>();
            var scannedAt = new LinkedHashMap<String, Long>();
            var dismissed = new LinkedHashSet<String>();
            if (matchesCachedContent(cached, content)) {
                scanned.putAll(cached.scanned());
                scannedAt.putAll(cached.scannedAt());
                dismissed.addAll(cached.dismissed());
            }
            Set<String> remaining = new LinkedHashSet<>(types);
            remaining.removeAll(scanned.keySet());
            Map<Block, List<Anchor>> viable = remaining.size() == types.size()
                    ? index : anchorsForTypes(index, remaining);
            viable = feasibleAnchors(viable, region);
            List<Detection> found = new ArrayList<>();
            if (!viable.isEmpty()) scanTemplates(chunk, viable, remaining, region, found);
            if (cancelled || Thread.currentThread().isInterrupted()) return List.of();
            long now = System.currentTimeMillis();
            for (String type : remaining) {
                scanned.put(type, found.stream().filter(detection -> type.equals(detection.type())).toList());
                scannedAt.put(type, now);
            }
            if (!content.isEmpty()) cacheEntry = new StructureFinderScanCache.Entry(packed, content, scanned, scannedAt, dismissed);
            fraction = 1;
            List<Detection> result = new ArrayList<>();
            for (String type : types) result.addAll(scanned.getOrDefault(type, List.of()));
            return List.copyOf(result);
        }

        private void scanTemplates(ChunkSnapshot chunk, Map<Block, List<Anchor>> viable, Set<String> remaining,
                                  Long2ObjectOpenHashMap<ChunkSnapshot> region, List<Detection> found) {
            ChunkCursor cursor = new ChunkCursor(chunk, viable, remaining, detection -> {
                // Keep a bounded per-task result list even for repetitive artificial builds.
                if (found.size() < 512) found.add(detection);
            }, playerY, discoveryFilter(index));
            SnapshotLookup lookup = new SnapshotLookup(region);
            long sliceStart = System.nanoTime();
            for (int operations = 0; !cancelled && !Thread.currentThread().isInterrupted(); operations++) {
                if ((operations & 31) == 0) {
                    fraction = chunkScanFraction(chunk.states.length, cursor.sectionIndex,
                            cursor.blockIndex, cursor.candidate != null);
                    long elapsed = System.nanoTime() - sliceStart;
                    if (elapsed >= WORKER_SLICE_NANOS) {
                        // Longer slices avoid constant short sleeps. The capped
                        // pool and a fixed rest still leave capacity for rendering.
                        LockSupport.parkNanos(WORKER_REST_NANOS);
                        sliceStart = System.nanoTime();
                    }
                }
                if (cursor.candidate != null) {
                    for (int sample = 0; sample < CANDIDATE_BATCH; sample++) {
                        if (cursor.candidate.step(lookup)) { cursor.candidate = null; break; }
                    }
                } else if (cursor.step()) {
                    fraction = 1;
                    return;
                }
            }
        }
    }

    static boolean matchesCachedContent(StructureFinderScanCache.Entry cached, Map<Long, String> content) {
        return cached != null && !cached.content().containsValue("missing") && cached.content().entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(content.get(entry.getKey())));
    }

    private static Map<Block, List<Anchor>> anchorsForTypes(Map<Block, List<Anchor>> index, Set<String> types) {
        Map<Block, List<Anchor>> filtered = new IdentityHashMap<>();
        if (types.isEmpty()) return filtered;
        for (var entry : index.entrySet()) {
            List<Anchor> group = entry.getValue().stream()
                    .filter(anchor -> types.contains(anchor.pattern.source.type())).toList();
            if (!group.isEmpty()) filtered.put(entry.getKey(), group);
        }
        return filtered;
    }

    /** A job owns its cursor and state cache; workers share only immutable snapshots. */
    private static final class ChunkCursor {
        final ChunkSnapshot chunk;
        final Map<Block, List<Anchor>> index;
        final Map<BlockState, List<Anchor>> stateCache = new IdentityHashMap<>();
        final Consumer<Detection> sink;
        final TriggerCache triggerCache;
        final int[] sections;
        final BlockPos[] markers;
        final StructureFinderScanPlan.Terrain terrain;
        final BitSet scannedMarkers = new BitSet();
        int markerIndex;
        int sectionIndex;
        int blockIndex;
        StructureFinderScanFilter.Builder triggerBuilder;
        StructureFinderScanFilter.Positions triggerPositions;
        Candidate candidate;

        ChunkCursor(ChunkSnapshot chunk, Map<Block, List<Anchor>> index, Set<String> types, Consumer<Detection> sink) {
            this(chunk, index, types, sink, Integer.MIN_VALUE);
        }

        ChunkCursor(ChunkSnapshot chunk, Map<Block, List<Anchor>> index, Set<String> types,
                    Consumer<Detection> sink, int playerY) {
            this(chunk, index, types, sink, playerY, discoveryFilter(index));
        }

        ChunkCursor(ChunkSnapshot chunk, Map<Block, List<Anchor>> index, Set<String> types,
                    Consumer<Detection> sink, int playerY, StructureFinderScanFilter filter) {
            this.chunk = chunk;
            this.index = index;
            this.sink = sink;
            triggerCache = chunk.triggers(filter);
            var sourceTerrain = chunk.terrain;
            terrain = playerY == Integer.MIN_VALUE || playerY == sourceTerrain.playerY() ? sourceTerrain
                    : new StructureFinderScanPlan.Terrain(sourceTerrain.minimumY(), sourceTerrain.sectionCount(),
                    sourceTerrain.surfaceY(), sourceTerrain.floorY(), sourceTerrain.seaLevel(), playerY, sourceTerrain.dimension());
            sections = StructureFinderScanPlan.sectionOrder(types, terrain);
            int[] rank = new int[sections.length];
            for (int i = 0; i < sections.length; i++) rank[sections[i]] = i;
            List<PrioritizedMarker> relevant = new ArrayList<>();
            for (BlockPos pos : chunk.markers) {
                int section = (pos.getY() - chunk.minimumY) >> 4;
                if (section < 0 || section >= rank.length) continue;
                Block actual = chunk.blockAt(pos.getX(), pos.getY(), pos.getZ());
                if (actual != null && index.containsKey(actual)
                        && triggerCache.filter.canTrigger(actual, pos.getX(), pos.getZ())) {
                    relevant.add(new PrioritizedMarker(pos, rank[section], markerPriority(pos, actual)));
                }
            }
            relevant.sort(Comparator.comparingInt(PrioritizedMarker::sectionRank)
                    .thenComparingInt(PrioritizedMarker::priority));
            markers = new BlockPos[relevant.size()];
            for (int i = 0; i < markers.length; i++) markers[i] = relevant.get(i).pos;
        }

        boolean step() {
            if (sectionIndex >= sections.length) return true;
            int currentSection = sections[sectionIndex];
            // Markers take their turn at the selected height, rather than making
            // every surface chest delay an ancient city or trial chamber.
            if (markerIndex < markers.length
                    && ((markers[markerIndex].getY() - chunk.minimumY) >> 4) == currentSection) {
                BlockPos pos = markers[markerIndex++];
                var states = chunk.states[currentSection];
                if (states == null) return false;
                BlockState state = states.get(pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15);
                List<Anchor> possible = possible(state);
                if (!possible.isEmpty()) {
                    candidate = new Candidate(possible, state.getBlock(), pos.getX(), pos.getY(), pos.getZ(), sink);
                    scannedMarkers.set(currentSection * 4096 + ((pos.getY() & 15) << 8)
                            + ((pos.getZ() & 15) << 4) + (pos.getX() & 15));
                }
                return false;
            }
            if (triggerPositions == null) {
                triggerPositions = triggerCache.sections.get(currentSection);
                if (triggerPositions == null) {
                    if (triggerBuilder == null) triggerBuilder = triggerCache.filter.builder(chunk.states[currentSection]);
                    if (!triggerBuilder.step(BLOCK_BATCH)) return false;
                    var built = triggerBuilder.finish();
                    triggerCache.sections.compareAndSet(currentSection, null, built);
                    triggerPositions = triggerCache.sections.get(currentSection);
                    triggerBuilder = null;
                }
            }
            while (blockIndex < 4096) {
                int found = triggerPositions.next(blockIndex);
                blockIndex = found == 4096 ? 4096 : found + 1;
                if (found == 4096) break;
                if (scannedMarkers.get(currentSection * 4096 + found)) continue;
                int x = found & 15;
                int z = found >> 4 & 15;
                int y = found >> 8;
                BlockState state = chunk.states[currentSection].get(x, y, z);
                List<Anchor> possible = possible(state);
                if (possible.isEmpty()) continue;
                candidate = new Candidate(possible, state.getBlock(), (ChunkPos.getX(chunk.packed) << 4) + x,
                        chunk.minimumY + currentSection * 16 + y, (ChunkPos.getZ(chunk.packed) << 4) + z, sink);
                break;
            }
            if (blockIndex == 4096) nextSection();
            return false;
        }

        private List<Anchor> possible(BlockState state) {
            return stateCache.computeIfAbsent(state, value -> index.getOrDefault(value.getBlock(), List.of()));
        }

        private record PrioritizedMarker(BlockPos pos, int sectionRank, int priority) {}

        private int markerPriority(BlockPos pos, Block actual) {
            int priority = Integer.MAX_VALUE;
            Set<String> types = new LinkedHashSet<>();
            for (Anchor anchor : index.get(actual)) types.add(anchor.pattern.source.type());
            for (String type : types) {
                priority = Math.min(priority, StructureFinderScanPlan.markerPriority(type,
                        pos.getY(), terrain));
            }
            return priority;
        }

        private void nextSection() {
            sectionIndex++;
            blockIndex = 0;
            triggerBuilder = null;
            triggerPositions = null;
        }
    }

    private record Sample(int x, int y, int z, Block block, List<Block> acceptedBlocks,
                          boolean villageAnchor) {}
    private record Transform(int[] x, int[] z) {}
    private record CompiledPattern(StructureFinderCatalog.Pattern source, List<Sample> samples,
                                   List<Transform> transforms) {}
    private record Anchor(CompiledPattern pattern, int sampleIndex, int required) {}

    /** Region palettes can prove a template impossible before trying its origins. */
    private static Map<Block, List<Anchor>> feasibleAnchors(Map<Block, List<Anchor>> index,
            Long2ObjectOpenHashMap<ChunkSnapshot> region) {
        Set<Block> available = new LinkedHashSet<>();
        for (ChunkSnapshot snapshot : region.values()) available.addAll(snapshot.materials());
        Map<CompiledPattern, Boolean> feasible = new IdentityHashMap<>();
        Map<Block, List<Anchor>> filtered = new IdentityHashMap<>();
        for (var entry : index.entrySet()) {
            if (!available.contains(entry.getKey())) continue;
            List<Anchor> retained = new ArrayList<>();
            for (Anchor anchor : entry.getValue()) {
                boolean possible = feasible.computeIfAbsent(anchor.pattern, pattern -> {
                    int availableSamples = 0;
                    for (Sample sample : pattern.samples) {
                        for (Block accepted : sample.acceptedBlocks) {
                            if (available.contains(accepted)) { availableSamples++; break; }
                        }
                    }
                    return availableSamples >= anchor.required;
                });
                if (possible) retained.add(anchor);
            }
            if (!retained.isEmpty()) filtered.put(entry.getKey(), List.copyOf(retained));
        }
        return filtered;
    }

    @FunctionalInterface
    interface BlockLookup {
        Block get(int x, int y, int z);
    }

    /** One sample per step, so a large group of anchor candidates cannot stall a frame. */
    private static final class Candidate {
        final List<Anchor> possible;
        final Block trigger;
        final int x, y, z;
        final Consumer<Detection> sink;
        int anchorIndex, transform, sampleIndex, matched, mismatched;
        int mossSamples;
        int originX, originY, originZ;
        long validatedTick;
        boolean started;

        Candidate(List<Anchor> possible, Block trigger, int x, int y, int z) {
            this(possible, trigger, x, y, z, null);
        }

        Candidate(List<Anchor> possible, Block trigger, int x, int y, int z, Consumer<Detection> sink) {
            this.possible = possible;
            this.trigger = trigger;
            this.x = x;
            this.y = y;
            this.z = z;
            this.sink = sink;
            validatedTick = scanTick;
        }

        boolean step(BlockLookup blocks) {
            if (anchorIndex >= possible.size()) return true;
            if (sink == null && validatedTick != scanTick) {
                validatedTick = scanTick;
                // Refresh both the chunk identity and the guaranteed trigger
                // match once on each resumed tick, not eight times per anchor.
                if (blocks.get(x, y, z) != trigger) return true;
            }
            Anchor anchor = possible.get(anchorIndex);
            List<Sample> samples = anchor.pattern.samples;
            boolean treasure = anchor.pattern.source.type().equals("buried_treasure");
            boolean monsterRoom = anchor.pattern.source.type().equals("monster_room");
            boolean desertPyramid = anchor.pattern.source.type().equals("desert_pyramid");
            if (treasure && ((x & 15) != 9 || (z & 15) != 9)) {
                nextTransform();
                return false;
            }
            Transform coordinates = anchor.pattern.transforms.get(transform);
            if (!started) {
                // The pyramid's central TNT is the only discovery anchor. Its
                // fixed trap geometry is mandatory at every similarity setting.
                if (desertPyramid && !StructureFinderDesertPyramid.matches(blocks, x, y, z)) {
                    nextTransform();
                    return false;
                }
                Sample origin = samples.get(anchor.sampleIndex);
                originX = x - coordinates.x[anchor.sampleIndex];
                originY = y - origin.y;
                originZ = z - coordinates.z[anchor.sampleIndex];
                matched = 1;
                started = true;
            }

            if (sampleIndex == anchor.sampleIndex) {
                // The cursor already read this sample, and resume validation
                // above keeps the guaranteed match valid across ticks.
                sampleIndex++;
                return false;
            }
            Sample sample = samples.get(sampleIndex);
            Block actual = blocks.get(originX + coordinates.x[sampleIndex],
                    originY + sample.y, originZ + coordinates.z[sampleIndex]);
            if (actual == null) {
                nextTransform();
                return false;
            }
            boolean matches = sample.acceptedBlocks.contains(actual);
            if (sampleIndex < anchor.sampleIndex && matches
                    && (!anchor.pattern.source.type().equals("fossil") || actual == sample.block)) {
                // A surviving earlier discovery anchor owns this exact origin
                // and transform. It will run the complete comparison itself;
                // do not repeat it for every other rare block in the piece.
                nextTransform();
                return false;
            }
            if (matches) {
                matched++;
                if (monsterRoom && actual == Blocks.MOSSY_COBBLESTONE) mossSamples++;
            } else mismatched++;
            sampleIndex++;
            int required = treasure ? samples.size() : anchor.required;
            if (mismatched > samples.size() - required) {
                nextTransform();
            } else if (sampleIndex == samples.size()) {
                if (!monsterRoom || mossSamples > 0) {
                    Detection detection = createDetection(anchor.pattern, originX, originY, originZ, transform,
                            (int) Math.round(matched * 100.0 / samples.size()));
                    if (sink != null) sink.accept(detection);
                    else {
                        RESULTS.add(detection, System.nanoTime() / 1_000_000);
                        detections = RESULTS.detections();
                    }
                }
                nextTransform();
            }
            return false;
        }

        private void nextTransform() {
            sampleIndex = matched = mismatched = mossSamples = 0;
            started = false;
            if (++transform == possible.get(anchorIndex).pattern.transforms.size()) {
                transform = 0;
                anchorIndex++;
            }
        }
    }

    static int rotateX(int x, int z, int transform) {
        if (transform >= 4) x = -x;
        return switch (transform & 3) { case 1 -> -z; case 2 -> -x; case 3 -> z; default -> x; };
    }

    static int rotateZ(int x, int z, int transform) {
        if (transform >= 4) x = -x;
        return switch (transform & 3) { case 1 -> x; case 2 -> -z; case 3 -> -x; default -> z; };
    }

    private static Detection createDetection(CompiledPattern pattern, int x, int y, int z, int transform, int percentage) {
        var source = pattern.source;
        int maxX = source.sizeX() - 1;
        int maxZ = source.sizeZ() - 1;
        int[] cornersX = {0, rotateX(maxX, 0, transform), rotateX(0, maxZ, transform), rotateX(maxX, maxZ, transform)};
        int[] cornersZ = {0, rotateZ(maxX, 0, transform), rotateZ(0, maxZ, transform), rotateZ(maxX, maxZ, transform)};
        int minX = 0, minZ = 0, boundX = 0, boundZ = 0;
        for (int i = 0; i < 4; i++) {
            minX = Math.min(minX, cornersX[i]); boundX = Math.max(boundX, cornersX[i]);
            minZ = Math.min(minZ, cornersZ[i]); boundZ = Math.max(boundZ, cornersZ[i]);
        }
        AABB bounds = new AABB(x + minX, y, z + minZ, x + boundX + 1, y + source.sizeY(), z + boundZ + 1);
        BlockPos position = source.type().equals("buried_treasure") || source.type().equals("monster_room")
                || source.type().equals("desert_pyramid")
                ? new BlockPos(x + rotateX(source.anchor().x(), source.anchor().z(), transform),
                        y + source.anchor().y(), z + rotateZ(source.anchor().x(), source.anchor().z(), transform))
                : new BlockPos(x, y, z);
        return new Detection(source.type(), position, bounds, percentage);
    }

    private static void sendNotifications(Minecraft client) {
        List<Detection> found = RESULTS.drainNotifications(System.nanoTime() / 1_000_000);
        if (found.isEmpty()) return;
        // One batch stays one bounded message. Extra detections remain available
        // through nearby-result pages without filling the chat history.
        MutableComponent message = Component.empty();
        int shown = Math.min(found.size(), MAX_NOTIFICATION_RESULTS);
        for (Detection detection : found.subList(0, shown)) {
            if (!message.getSiblings().isEmpty()) message.append(Component.literal("\n"));
            MutableComponent name = Component.translatable("structure_finder.structure." + detection.type())
                    .withColor(StructureFinderHighlight.typeColor(detection.type()));
            int features = RESULTS.featureCount(detection);
            if (features > 1) name.append(Component.translatable("chat.spark_fix.structure_finder.features",
                    Component.literal(Integer.toString(features))));
            message.append(Component.translatable("chat.spark_fix.structure_finder.found", name,
                    StructureFinderXaeroBridge.coordinateLink(detection),
                    Component.literal(Integer.toString(detection.similarity()))));
        }
        if (shown < found.size()) message.append(Component.literal("\n"))
                .append(Component.translatable("chat.spark_fix.structure_finder.more_results",
                        Component.literal(Integer.toString(found.size() - shown))));
        message.append(Component.literal(" ")).append(StructureFinderXaeroBridge.nearbyResultsLink());
        client.gui.chatListener().handleSystemMessage(message, false);
    }
}
