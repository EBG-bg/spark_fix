package dev.codex.spark_fix;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Persistent completion records; callers verify content signatures before reusing them. */
final class StructureFinderScanCache implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("spark_fix/structure-cache");
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final int FORMAT = 1;
    private static final int MAX_RECORDS = 100_000;
    private static final int MAX_LINE_CHARS = 1_048_576;
    private static final long MAX_BYTES = 64L * 1024 * 1024;
    private static final long SAVE_DELAY_SECONDS = 5;
    private static final ScheduledExecutorService IO = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "spark_fix-structure-cache");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    record Entry(long chunk, Map<Long, String> content,
                 Map<String, List<StructureFinder.Detection>> scanned,
                 Map<String, Long> scannedAt, Set<String> dismissed) {
        Entry(long chunk, Map<Long, String> content, Map<String, List<StructureFinder.Detection>> scanned) {
            this(chunk, content, scanned, Map.of(), Set.of());
        }

        Entry {
            Objects.requireNonNull(content);
            Objects.requireNonNull(scanned);
            Objects.requireNonNull(scannedAt);
            Objects.requireNonNull(dismissed);
            if (content.isEmpty() || content.size() > 121 || scanned.size() > 256) {
                throw new IllegalArgumentException("Invalid structure cache record size");
            }
            var signatures = new LinkedHashMap<Long, String>();
            content.forEach((coordinate, signature) -> {
                if (coordinate == null || signature == null || signature.isEmpty() || signature.length() > 256) {
                    throw new IllegalArgumentException("Invalid chunk signature");
                }
                signatures.put(coordinate, signature);
            });
            var types = new LinkedHashMap<String, List<StructureFinder.Detection>>();
            scanned.forEach((type, detections) -> {
                if (type == null || type.isEmpty() || type.length() > 128 || detections == null
                        || detections.size() > 4096) {
                    throw new IllegalArgumentException("Invalid cached structure type");
                }
                var copy = new ArrayList<StructureFinder.Detection>(detections.size());
                for (var detection : detections) {
                    if (detection == null || !type.equals(detection.type()) || detection.pos() == null
                            || detection.bounds() == null || detection.similarity() < 0 || detection.similarity() > 100
                            || !validBounds(detection.bounds())) {
                        throw new IllegalArgumentException("Invalid cached structure detection");
                    }
                    copy.add(new StructureFinder.Detection(type, detection.pos().immutable(),
                            detection.bounds(), detection.similarity()));
                }
                types.put(type, List.copyOf(copy));
            });
            var times = new LinkedHashMap<String, Long>();
            long now = System.currentTimeMillis();
            for (String type : types.keySet()) {
                long timestamp = scannedAt.getOrDefault(type, now);
                if (timestamp < 0) throw new IllegalArgumentException("Invalid scan timestamp");
                times.put(type, timestamp);
            }
            var validDismissals = new HashSet<String>();
            for (var detections : types.values()) {
                for (var detection : detections) {
                    String key = detectionKey(detection);
                    if (dismissed.contains(key)) validDismissals.add(key);
                }
            }
            content = Map.copyOf(signatures);
            scanned = Map.copyOf(types);
            scannedAt = Map.copyOf(times);
            dismissed = Set.copyOf(validDismissals);
        }
    }

    private final Path file;
    private final String rules;
    private final ScheduledExecutorService io = IO;
    private final LinkedHashMap<Long, Entry> entries = new LinkedHashMap<>(128, 0.75F, true);
    private final Set<Long> removedDuringLoad = new HashSet<>();
    private final Set<String> dismissedDuringLoad = new HashSet<>();
    private ScheduledFuture<?> pendingSave;
    private volatile boolean ready;
    private boolean closed;
    private long revision;
    private long savedRevision;

    StructureFinderScanCache(Path file, String rules) {
        this.file = Objects.requireNonNull(file).toAbsolutePath().normalize();
        this.rules = Objects.requireNonNull(rules);
        if (rules.length() > 4096) throw new IllegalArgumentException("Invalid cache rules identifier");
        io.execute(this::load);
    }

    boolean ready() {
        return ready;
    }

    synchronized Entry get(long chunk) {
        return entries.get(chunk);
    }

    synchronized void put(Entry entry) {
        if (closed) return;
        Entry existing = entries.get(entry.chunk());
        // A scan may finish after the player has dismissed one of its cached pieces.
        if (existing != null && !existing.dismissed().isEmpty()) {
            var dismissed = new HashSet<>(entry.dismissed());
            dismissed.addAll(existing.dismissed());
            entry = new Entry(entry.chunk(), entry.content(), entry.scanned(), entry.scannedAt(), dismissed);
        }
        Entry previous = entries.put(entry.chunk(), entry);
        if (!entry.equals(previous)) {
            trim(entries);
            revision++;
            scheduleSave();
        }
    }

    synchronized void remove(long chunk) {
        if (closed) return;
        boolean removed = entries.remove(chunk) != null;
        if (!ready) removed |= removedDuringLoad.add(chunk);
        if (removed) {
            revision++;
            scheduleSave();
        }
    }

    synchronized void dismiss(Collection<StructureFinder.Detection> detections) {
        if (closed || detections.isEmpty()) return;
        var keys = new HashSet<String>();
        for (var detection : detections) keys.add(detectionKey(detection));
        boolean changed = !ready && dismissedDuringLoad.addAll(keys);
        for (var slot : entries.entrySet()) {
            Entry entry = slot.getValue();
            var dismissed = new HashSet<>(entry.dismissed());
            for (var cached : entry.scanned().values()) {
                for (var detection : cached) {
                    String key = detectionKey(detection);
                    if (keys.contains(key)) dismissed.add(key);
                }
            }
            if (!dismissed.equals(entry.dismissed())) {
                slot.setValue(new Entry(entry.chunk(), entry.content(), entry.scanned(), entry.scannedAt(), dismissed));
                changed = true;
            }
        }
        if (changed) {
            revision++;
            scheduleSave();
        }
    }

    static String detectionKey(StructureFinder.Detection detection) {
        BlockPos pos = detection.pos();
        return detection.type() + ':' + pos.getX() + ',' + pos.getY() + ',' + pos.getZ();
    }

    CompletableFuture<Void> flushAsync() {
        synchronized (this) {
            if (closed) return CompletableFuture.completedFuture(null);
            if (pendingSave != null) pendingSave.cancel(false);
            pendingSave = null;
            return CompletableFuture.runAsync(this::save, io);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (pendingSave != null) pendingSave.cancel(false);
        pendingSave = null;
        io.execute(this::save);
    }

    static void shutdown() {
        IO.shutdown();
        try {
            if (!IO.awaitTermination(2, TimeUnit.SECONDS)) {
                LOGGER.warn("Structure Finder scan cache is still saving during client shutdown");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void scheduleSave() {
        if (pendingSave == null || pendingSave.isDone()) {
            pendingSave = io.schedule(this::save, SAVE_DELAY_SECONDS, TimeUnit.SECONDS);
        }
    }

    private void load() {
        var loaded = new LinkedHashMap<Long, Entry>(128, 0.75F, true);
        if (Files.isRegularFile(file)) {
            try (var input = new LimitedInputStream(new GZIPInputStream(Files.newInputStream(file)));
                 var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String headerLine = readLine(reader);
                JsonObject header = JsonParser.parseString(headerLine == null ? "{}" : headerLine).getAsJsonObject();
                if (header.has("format") && header.get("format").getAsInt() == FORMAT
                        && header.has("rules") && rules.equals(header.get("rules").getAsString())) {
                    String line;
                    int malformed = 0;
                    while ((line = readLine(reader)) != null) {
                        try {
                            Entry entry = decode(JsonParser.parseString(line).getAsJsonObject());
                            loaded.put(entry.chunk(), entry);
                            trim(loaded);
                        } catch (RuntimeException exception) {
                            malformed++;
                        }
                    }
                    if (malformed != 0) LOGGER.warn("Ignored {} invalid Structure Finder cache records", malformed);
                }
            } catch (IOException | RuntimeException exception) {
                LOGGER.warn("Could not fully read Structure Finder scan cache {}; valid records remain available",
                        file.getFileName(), exception);
            }
        }
        synchronized (this) {
            // Records produced during asynchronous loading take precedence over the file.
            removedDuringLoad.forEach(loaded::remove);
            removedDuringLoad.clear();
            loaded.putAll(entries);
            if (!dismissedDuringLoad.isEmpty()) {
                for (var slot : loaded.entrySet()) {
                    Entry entry = slot.getValue();
                    var dismissed = new HashSet<>(entry.dismissed());
                    dismissed.addAll(dismissedDuringLoad);
                    slot.setValue(new Entry(entry.chunk(), entry.content(), entry.scanned(), entry.scannedAt(), dismissed));
                }
                dismissedDuringLoad.clear();
            }
            entries.clear();
            entries.putAll(loaded);
            trim(entries);
            ready = true;
        }
    }

    private void save() {
        List<Entry> snapshot;
        long savingRevision;
        synchronized (this) {
            pendingSave = null;
            if (revision == savedRevision) return;
            snapshot = new ArrayList<>(entries.values());
            savingRevision = revision;
        }
        Path temporary = null;
        try {
            Files.createDirectories(file.getParent());
            temporary = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
            var header = new JsonObject();
            header.addProperty("format", FORMAT);
            header.addProperty("rules", rules);
            String headerLine = GSON.toJson(header) + '\n';
            long remaining = MAX_BYTES - headerLine.getBytes(StandardCharsets.UTF_8).length;
            var lines = new ArrayList<String>(snapshot.size());
            // The map is ordered by use, so retain the most recently used chunks at the size limit.
            for (int i = snapshot.size() - 1; i >= 0; i--) {
                String line = GSON.toJson(encode(snapshot.get(i))) + '\n';
                int length = line.getBytes(StandardCharsets.UTF_8).length;
                if (line.length() > MAX_LINE_CHARS || length > remaining) continue;
                remaining -= length;
                lines.add(line);
            }
            try (var writer = new BufferedWriter(new OutputStreamWriter(
                    new GZIPOutputStream(Files.newOutputStream(temporary)), StandardCharsets.UTF_8))) {
                writer.write(headerLine);
                for (int i = lines.size() - 1; i >= 0; i--) writer.write(lines.get(i));
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
            synchronized (this) {
                savedRevision = savingRevision;
                if (!closed && revision != savedRevision) scheduleSave();
            }
        } catch (IOException | RuntimeException exception) {
            LOGGER.warn("Could not save Structure Finder scan cache {}; preserving the previous file",
                    file.getFileName(), exception);
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException exception) {
                    LOGGER.debug("Could not remove Structure Finder cache temporary file", exception);
                }
            }
        }
    }

    private static JsonObject encode(Entry entry) {
        var object = new JsonObject();
        object.addProperty("chunk", entry.chunk());
        var content = new JsonObject();
        entry.content().forEach((chunk, signature) -> content.addProperty(Long.toString(chunk), signature));
        object.add("content", content);
        var types = new JsonObject();
        entry.scanned().forEach((type, detections) -> {
            var items = new JsonArray();
            for (var detection : detections) {
                var item = new JsonArray();
                item.add(detection.pos().getX());
                item.add(detection.pos().getY());
                item.add(detection.pos().getZ());
                item.add(detection.bounds().minX);
                item.add(detection.bounds().minY);
                item.add(detection.bounds().minZ);
                item.add(detection.bounds().maxX);
                item.add(detection.bounds().maxY);
                item.add(detection.bounds().maxZ);
                item.add(detection.similarity());
                items.add(item);
            }
            types.add(type, items);
        });
        object.add("scanned", types);
        var scannedAt = new JsonObject();
        entry.scannedAt().forEach(scannedAt::addProperty);
        object.add("scannedAt", scannedAt);
        var dismissed = new JsonArray();
        entry.dismissed().forEach(dismissed::add);
        object.add("dismissed", dismissed);
        return object;
    }

    private static Entry decode(JsonObject object) {
        long chunk = object.get("chunk").getAsLong();
        var content = new LinkedHashMap<Long, String>();
        if (object.getAsJsonObject("content").size() > 121
                || object.getAsJsonObject("scanned").size() > 256) {
            throw new IllegalArgumentException("Invalid cache record size");
        }
        for (var signature : object.getAsJsonObject("content").entrySet()) {
            content.put(Long.parseLong(signature.getKey()), signature.getValue().getAsString());
        }
        var scanned = new LinkedHashMap<String, List<StructureFinder.Detection>>();
        for (var type : object.getAsJsonObject("scanned").entrySet()) {
            var detections = new ArrayList<StructureFinder.Detection>();
            if (type.getValue().getAsJsonArray().size() > 4096) {
                throw new IllegalArgumentException("Too many cached detections");
            }
            for (JsonElement element : type.getValue().getAsJsonArray()) {
                var item = element.getAsJsonArray();
                if (item.size() != 10) throw new IllegalArgumentException("Invalid detection fields");
                var pos = new BlockPos(item.get(0).getAsInt(), item.get(1).getAsInt(), item.get(2).getAsInt());
                double minX = item.get(3).getAsDouble();
                double minY = item.get(4).getAsDouble();
                double minZ = item.get(5).getAsDouble();
                double maxX = item.get(6).getAsDouble();
                double maxY = item.get(7).getAsDouble();
                double maxZ = item.get(8).getAsDouble();
                if (minX > maxX || minY > maxY || minZ > maxZ) {
                    throw new IllegalArgumentException("Invalid detection bounds");
                }
                var bounds = new AABB(minX, minY, minZ, maxX, maxY, maxZ);
                detections.add(new StructureFinder.Detection(type.getKey(), pos, bounds, item.get(9).getAsInt()));
            }
            scanned.put(type.getKey(), detections);
        }
        var times = new LinkedHashMap<String, Long>();
        if (object.has("scannedAt")) {
            for (var time : object.getAsJsonObject("scannedAt").entrySet()) {
                times.put(time.getKey(), time.getValue().getAsLong());
            }
        }
        var dismissed = new HashSet<String>();
        if (object.has("dismissed")) {
            for (var value : object.getAsJsonArray("dismissed")) dismissed.add(value.getAsString());
        }
        return new Entry(chunk, content, scanned, times, dismissed);
    }

    private static boolean validBounds(AABB bounds) {
        return Double.isFinite(bounds.minX) && Double.isFinite(bounds.minY) && Double.isFinite(bounds.minZ)
                && Double.isFinite(bounds.maxX) && Double.isFinite(bounds.maxY) && Double.isFinite(bounds.maxZ);
    }

    private static void trim(LinkedHashMap<Long, Entry> entries) {
        while (entries.size() > MAX_RECORDS) entries.remove(entries.keySet().iterator().next());
    }

    private static String readLine(BufferedReader reader) throws IOException {
        var line = new StringBuilder();
        int character;
        while ((character = reader.read()) != -1 && character != '\n') {
            if (line.length() >= MAX_LINE_CHARS) throw new IOException("Structure cache line exceeds limit");
            if (character != '\r') line.append((char) character);
        }
        return character == -1 && line.isEmpty() ? null : line.toString();
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long count;

        LimitedInputStream(InputStream input) {
            super(input);
        }

        @Override
        public int read() throws IOException {
            int value = in.read();
            if (value != -1 && ++count > MAX_BYTES) throw new IOException("Structure cache exceeds limit");
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = in.read(buffer, offset, (int) Math.min(length, MAX_BYTES - count + 1));
            if (read > 0 && (count += read) > MAX_BYTES) throw new IOException("Structure cache exceeds limit");
            return read;
        }
    }
}
