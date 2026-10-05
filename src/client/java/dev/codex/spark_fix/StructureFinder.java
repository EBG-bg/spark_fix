package dev.codex.spark_fix;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Recognises vanilla pieces using only block data already received by the client. */
public final class StructureFinder {
    // Keep the work bounded per frame while allowing a loaded area to finish
    // in a reasonable time on a stationary client.
    private static final long TICK_BUDGET_NANOS = 3_000_000;
    private static final int MAX_OPERATIONS = 16_384;
    private static final int RESCAN_DELAY_TICKS = 400;
    private static final Set<Long> LOADED_CHUNKS = new LinkedHashSet<>();
    private static final Set<Long> NEW_CHUNKS = new LinkedHashSet<>();
    // Chunk packets and scanning both run on the client thread. Refresh this
    // cache every tick rather than keeping stale chunk objects across packets.
    private static final Long2ObjectOpenHashMap<LevelChunk> TICK_CHUNKS = new Long2ObjectOpenHashMap<>();
    private static final StructureFinderResults RESULTS = new StructureFinderResults();
    private static Set<String> selected = Set.of();
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
    private static Map<Block, List<Anchor>> pendingIndex;
    private static int indexCompileIndex;
    private static boolean indexDirty = true;
    private static int similarity = 85;
    private static List<Long> pass = new ArrayList<>();
    private static final Set<Long> scheduledChunks = new LinkedHashSet<>();
    private static final Set<Long> completedChunks = new LinkedHashSet<>();
    private static final Set<Long> priorityQueued = new LinkedHashSet<>();
    private static final ArrayDeque<Long> priorityChunks = new ArrayDeque<>();
    private static int passIndex;
    private static boolean passStarted;
    private static PassPreparation passPreparation;
    private static NewChunkPreparation newChunkPreparation;
    private static int priorityRadius = 1;
    private static long scanTick;
    private static int rescanDelay;
    private static boolean chunksChanged;
    private static ChunkCursor cursor;
    private static Candidate candidate;
    private static final BlockLookup LIVE_BLOCKS = (x, y, z) -> blockAt(world, x, y, z);

    private StructureFinder() {}

    public record Detection(String type, BlockPos pos, AABB bounds, int similarity) {}

    public static void register() {
        if (registered) return;
        registered = true;
        StructureFinderXaeroBridge.register();
        selected = validTypes(SparkFixConfig.selectedStructureTypes());
        similarity = SparkFixConfig.structureFinderSimilarity();
        ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> {
            changeWorld(level);
            LOADED_CHUNKS.add(chunk.getPos().pack());
            NEW_CHUNKS.add(chunk.getPos().pack());
            chunksChanged = true;
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> {
            if (level == world) forgetChunk(chunk.getPos().pack());
        });
        ClientPlayConnectionEvents.DISCONNECT.register((connection, client) -> changeWorld(null));
        ClientTickEvents.END_CLIENT_TICK.register(StructureFinder::tick);
        StructureFinderHighlight.register();
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

    /**
     * Hides a detected structure without deleting its remembered group. The
     * retained bounds prevent a rescan from immediately restoring the same
     * highlight; changing the selected structure types clears that history.
     */
    public static boolean dismissHighlight(Detection detection) {
        if (!RESULTS.dismiss(detection)) return false;
        detections = RESULTS.detections();
        return true;
    }

    public static int scannedChunks() { return completedChunks.size(); }
    public static int scanTotalChunks() { return scheduledChunks.size(); }
    public static boolean templatesLoading() { return registered && !selected.isEmpty() && !templatesReady && loadError == null; }
    public static String templateError() { return loadError; }

    private static void changeWorld(ClientLevel level) {
        if (world == level) return;
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
        pass = new ArrayList<>();
        scheduledChunks.clear();
        completedChunks.clear();
        priorityQueued.clear();
        priorityChunks.clear();
        NEW_CHUNKS.clear();
        passIndex = 0;
        passStarted = false;
        passPreparation = null;
        newChunkPreparation = null;
        cursor = null;
        candidate = null;
        rescanDelay = 0;
    }

    private static void tick(Minecraft client) {
        if (client.level != world) changeWorld(client.level);
        if (world == null || client.player == null || client.isPaused() || selected.isEmpty()) return;
        RESULTS.expire(System.nanoTime() / 1_000_000);
        detections = RESULTS.detections();
        sendNotifications(client);
        scanTick++;
        TICK_CHUNKS.clear();
        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
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
        if (!passStarted || (passPreparation == null && newChunkPreparation == null && NEW_CHUNKS.isEmpty()
                && passIndex >= pass.size() && cursor == null && priorityChunks.isEmpty())) {
            if (passStarted && !chunksChanged && rescanDelay-- > 0) return;
            beginPass(client);
        }
        for (int operations = 0; operations < MAX_OPERATIONS; operations++) {
            if ((operations & 31) == 0 && System.nanoTime() >= deadline) break;
            if (passPreparation != null) {
                if (passPreparation.step()) {
                    passPreparation = null;
                    chunksChanged = !NEW_CHUNKS.isEmpty();
                    if (pass.isEmpty()) rescanDelay = RESCAN_DELAY_TICKS;
                }
                continue;
            }
            if (newChunkPreparation == null && !NEW_CHUNKS.isEmpty()) {
                long arrived = NEW_CHUNKS.iterator().next();
                NEW_CHUNKS.remove(arrived);
                newChunkPreparation = new NewChunkPreparation(arrived);
            }
            if (newChunkPreparation != null) {
                if (newChunkPreparation.step()) newChunkPreparation = null;
                continue;
            }
            chunksChanged = false;
            if (candidate != null) {
                if (candidate.step(LIVE_BLOCKS)) candidate = null;
                continue;
            }
            if (cursor == null) {
                Long packed;
                if (!priorityChunks.isEmpty()) {
                    packed = priorityChunks.removeFirst();
                    priorityQueued.remove(packed);
                    if (!scheduledChunks.contains(packed)) continue;
                    // A newly received neighbouring chunk may complete a
                    // piece whose earlier candidate lacked loaded samples.
                    completedChunks.remove(packed);
                } else if (passIndex < pass.size()) {
                    packed = pass.get(passIndex++);
                    if (!scheduledChunks.contains(packed) || completedChunks.contains(packed)) continue;
                } else {
                    rescanDelay = RESCAN_DELAY_TICKS;
                    break;
                }
                ChunkPos pos = ChunkPos.unpack(packed);
                LevelChunk chunk = loadedChunk(world, pos.x(), pos.z());
                if (chunk != null) cursor = new ChunkCursor(chunk);
                else forgetChunk(packed);
                continue;
            }
            if (loadedChunk(world, cursor.chunk.getPos().x(), cursor.chunk.getPos().z()) != cursor.chunk) {
                long packed = cursor.chunk.getPos().pack();
                if (loadedChunk(world, cursor.chunk.getPos().x(), cursor.chunk.getPos().z()) == null) forgetChunk(packed);
                cursor = null;
                continue;
            }
            if (cursor.step()) {
                completedChunks.add(cursor.chunk.getPos().pack());
                cursor = null;
                if (passIndex >= pass.size() && priorityChunks.isEmpty() && NEW_CHUNKS.isEmpty()) {
                    rescanDelay = RESCAN_DELAY_TICKS;
                }
            }
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
            if (pattern != null) compiledPatterns.add(pattern);
        }
        templatesReady = compileIndex == rawPatterns.size();
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
        List<Transform> transforms = new ArrayList<>(8);
        for (int rotation = 0; rotation < 8; rotation++) {
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
            String id = pattern.samples().get(index).blockId();
            return featurePriority(id) + (pattern.type().equals("village")
                    && StructureFinderCatalog.isVillageAnchor(id) ? 1000 : 0);
        }).reversed());
        return Arrays.stream(indices).mapToInt(Integer::intValue).toArray();
    }

    static int discoveryAnchorCount(StructureFinderCatalog.Pattern pattern, int percentage) {
        int count = pattern.samples().size();
        int required = (count * percentage + 99) / 100;
        // If at most N samples may be missing, N+1 discovery positions
        // guarantee a surviving anchor. Repeated rare positions are preferable
        // to an arbitrary backup plank that triggers throughout ordinary builds.
        int origins = count - required + 1;
        if (pattern.type().equals("village")) {
            int markers = (int) pattern.samples().stream()
                    .filter(sample -> StructureFinderCatalog.isVillageAnchor(sample.blockId())).count();
            origins = Math.min(origins, markers);
        }
        return origins;
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
            indexCompileIndex = 0;
            priorityRadius = 1;
            resetPass();
        }
        // One fingerprint is small (at most 24 samples). Resume between them
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
            int required = (pattern.samples.size() * similarity + 99) / 100;
            priorityRadius = Math.max(priorityRadius, Math.min(4,
                    (Math.max(pattern.source.sizeX(), pattern.source.sizeZ()) + 14) / 16));
            for (int origin = 0; origin < originCount; origin++) {
                Sample sample = pattern.samples.get(origin);
                Anchor anchor = new Anchor(pattern, origin, required);
                for (Block block : sample.acceptedBlocks) {
                    pendingIndex.computeIfAbsent(block, ignored -> new ArrayList<>()).add(anchor);
                }
            }
        }
        if (indexCompileIndex < compiledPatterns.size()) return false;
        anchors = pendingIndex;
        pendingIndex = null;
        indexDirty = false;
        return true;
    }

    private static void beginPass(Minecraft client) {
        ChunkPos center = client.player.chunkPosition();
        pass = new ArrayList<>();
        scheduledChunks.clear();
        completedChunks.clear();
        priorityChunks.clear();
        priorityQueued.clear();
        NEW_CHUNKS.clear();
        passIndex = 0;
        passStarted = true;
        chunksChanged = false;
        passPreparation = new PassPreparation(center,
                Math.clamp(client.options.renderDistance().get() + 3, 5, 64));
    }

    private static void forgetChunk(long packed) {
        LOADED_CHUNKS.remove(packed);
        NEW_CHUNKS.remove(packed);
        scheduledChunks.remove(packed);
        completedChunks.remove(packed);
        priorityQueued.remove(packed);
        TICK_CHUNKS.remove(packed);
        if (cursor != null && cursor.chunk.getPos().pack() == packed) {
            cursor = null;
            candidate = null;
        }
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
                if (scheduledChunks.add(packed)) pass.add(packed);
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
            if (LOADED_CHUNKS.contains(packed) && priorityQueued.add(packed)) {
                priorityChunks.addLast(packed);
                if (scheduledChunks.add(packed)) pass.add(packed);
            }
            if (++offset == Math.max(1, ring * 8)) {
                ring++;
                offset = 0;
            }
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

    private static final class ChunkCursor {
        final LevelChunk chunk;
        int sectionIndex;
        int blockIndex;
        boolean sectionChecked;

        ChunkCursor(LevelChunk chunk) { this.chunk = chunk; }

        boolean step() {
            LevelChunkSection[] sections = chunk.getSections();
            if (sectionIndex >= sections.length) return true;
            LevelChunkSection section = sections[sectionIndex];
            if (!sectionChecked) {
                sectionChecked = true;
                if (section.hasOnlyAir() || !section.maybeHas(state -> anchors.containsKey(state.getBlock()))) {
                    nextSection();
                    return false;
                }
            }
            int x = blockIndex & 15;
            int z = blockIndex >> 4 & 15;
            int y = blockIndex >> 8;
            Block trigger = section.getBlockState(x, y, z).getBlock();
            List<Anchor> possible = anchors.get(trigger);
            if (possible != null) {
                candidate = new Candidate(possible, trigger, (chunk.getPos().x() << 4) + x,
                        (chunk.getSectionYFromSectionIndex(sectionIndex) << 4) + y,
                        (chunk.getPos().z() << 4) + z);
            }
            if (++blockIndex == 4096) nextSection();
            return false;
        }

        private void nextSection() {
            sectionIndex++;
            blockIndex = 0;
            sectionChecked = false;
        }
    }

    private record Sample(int x, int y, int z, Block block, List<Block> acceptedBlocks,
                          boolean villageAnchor) {}
    private record Transform(int[] x, int[] z) {}
    private record CompiledPattern(StructureFinderCatalog.Pattern source, List<Sample> samples,
                                   List<Transform> transforms) {}
    private record Anchor(CompiledPattern pattern, int sampleIndex, int required) {}

    @FunctionalInterface
    interface BlockLookup {
        Block get(int x, int y, int z);
    }

    /** One sample per step, so a large group of anchor candidates cannot stall a frame. */
    private static final class Candidate {
        final List<Anchor> possible;
        final Block trigger;
        final int x, y, z;
        int anchorIndex, transform, sampleIndex, matched, mismatched;
        int originX, originY, originZ;
        long validatedTick;
        boolean started;

        Candidate(List<Anchor> possible, Block trigger, int x, int y, int z) {
            this.possible = possible;
            this.trigger = trigger;
            this.x = x;
            this.y = y;
            this.z = z;
            validatedTick = scanTick;
        }

        boolean step(BlockLookup blocks) {
            if (anchorIndex >= possible.size()) return true;
            if (validatedTick != scanTick) {
                validatedTick = scanTick;
                // Refresh both the chunk identity and the guaranteed trigger
                // match once on each resumed tick, not eight times per anchor.
                if (blocks.get(x, y, z) != trigger) return true;
            }
            Anchor anchor = possible.get(anchorIndex);
            List<Sample> samples = anchor.pattern.samples;
            Transform coordinates = anchor.pattern.transforms.get(transform);
            if (!started) {
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
            if (sampleIndex < anchor.sampleIndex && matches) {
                // A surviving earlier discovery anchor owns this exact origin
                // and transform. It will run the complete comparison itself;
                // do not repeat it for every other rare block in the piece.
                nextTransform();
                return false;
            }
            if (matches) {
                matched++;
            } else mismatched++;
            sampleIndex++;
            if (mismatched > samples.size() - anchor.required) {
                nextTransform();
            } else if (sampleIndex == samples.size()) {
                recordDetection(anchor.pattern, originX, originY, originZ, transform,
                        (int) Math.round(matched * 100.0 / samples.size()));
                nextTransform();
            }
            return false;
        }

        private void nextTransform() {
            sampleIndex = matched = mismatched = 0;
            started = false;
            if (++transform == 8) {
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

    private static void recordDetection(CompiledPattern pattern, int x, int y, int z, int transform, int percentage) {
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
        RESULTS.add(new Detection(source.type(), new BlockPos(x, y, z), bounds, percentage),
                System.nanoTime() / 1_000_000);
        detections = RESULTS.detections();
    }

    private static void sendNotifications(Minecraft client) {
        List<Detection> found = RESULTS.drainNotifications(System.nanoTime() / 1_000_000);
        if (found.isEmpty()) return;
        Detection first = found.getFirst();
        Component name = Component.translatable("structure_finder.structure." + first.type());
        Component coordinates = StructureFinderXaeroBridge.coordinateLink(first);
        Component message;
        if (found.size() == 1) {
            message = Component.translatable("chat.spark_fix.structure_finder.found", name,
                    coordinates, first.similarity());
        } else {
            Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            for (Detection detection : found) counts.merge(detection.type(), 1, Integer::sum);
            MutableComponent names = Component.empty();
            for (var entry : counts.entrySet()) {
                if (!names.getSiblings().isEmpty()) names.append(Component.translatable("chat.spark_fix.structure_finder.separator"));
                names.append(Component.translatable("chat.spark_fix.structure_finder.batch_type",
                        Component.translatable("structure_finder.structure." + entry.getKey()), entry.getValue()));
            }
            message = Component.translatable("chat.spark_fix.structure_finder.batch", names,
                    coordinates);
        }
        client.gui.chatListener().handleSystemMessage(message, false);
    }
}
