package cn.reibridge.recipe;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** Runs on the recipe writer's executor so a cleanup cannot race a manifest commit. */
public final class RecipeCacheMaintenance {
    private static final Pattern DIRECTORY_NAME = Pattern.compile("[0-9a-f]{32}\\.v2\\.json\\.parts");
    private static final Pattern PART_NAME = Pattern.compile("[0-9a-f]{64}\\.jsonl\\.gz");
    private static final long MAX_MANIFEST_BYTES = 64L * 1024 * 1024;

    public record Stats(long totalBytes, long reclaimableBytes, int reclaimableFiles,
                        int skippedServers, List<String> problems) {
        public Stats { problems = List.copyOf(problems); }
    }

    public record Result(Stats before, long reclaimedBytes, int deletedFiles, List<String> problems) {
        public Result { problems = List.copyOf(problems); }
    }

    private record Candidate(Path file, long bytes) { }
    private record ManifestStamp(Path file, long size, java.nio.file.attribute.FileTime modified) { }
    private record ServerPlan(Path directory, List<Candidate> candidates, List<ManifestStamp> manifests) { }
    private record Plan(Path root, Stats stats, List<ServerPlan> servers) { }

    private RecipeCacheMaintenance() { }

    public static Stats inspect(Path root) {
        return inspect(root, SharedConstants.getCurrentVersion().id());
    }

    public static Stats inspect(Path root, String minecraftVersion) {
        return plan(root, minecraftVersion).stats();
    }

    public static Result clean(Path root) {
        return clean(root, SharedConstants.getCurrentVersion().id());
    }

    public static Result clean(Path root, String minecraftVersion) {
        Plan plan = plan(root, minecraftVersion);
        List<String> problems = new ArrayList<>(plan.stats().problems());
        long reclaimed = 0;
        int deleted = 0;
        for (ServerPlan server : plan.servers()) {
            try {
                // The game's writer is serialized with this operation. A manual external edit
                // between the scan and deletion still cancels this server's cleanup.
                requireDirectory(plan.root());
                requireDirectory(server.directory());
                for (ManifestStamp stamp : server.manifests()) {
                    BasicFileAttributes current = regularFile(stamp.file());
                    if (current.size() != stamp.size() || !current.lastModifiedTime().equals(stamp.modified())) {
                        throw new IOException("manifest changed during cleanup: " + stamp.file().getFileName());
                    }
                }
                for (Candidate candidate : server.candidates()) {
                    requireDirectory(server.directory());
                    Path file = candidate.file();
                    if (!file.getParent().equals(server.directory())
                            || !PART_NAME.matcher(file.getFileName().toString()).matches()) {
                        throw new IOException("part is outside its cache directory: " + file);
                    }
                    BasicFileAttributes current = regularFile(file);
                    if (current.size() != candidate.bytes()) throw new IOException("part changed during cleanup: " + file.getFileName());
                    Files.delete(file);
                    reclaimed += current.size();
                    deleted++;
                }
            } catch (IOException | RuntimeException exception) {
                problems.add(problem(server.directory(), exception));
            }
        }
        return new Result(plan.stats(), reclaimed, deleted, problems);
    }

    private static Plan plan(Path requestedRoot, String minecraftVersion) {
        Path root = requestedRoot.toAbsolutePath().normalize();
        List<String> problems = new ArrayList<>();
        List<ServerPlan> servers = new ArrayList<>();
        long total = 0, reclaimable = 0;
        int reclaimableFiles = 0, skipped = 0;
        try {
            if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
                return new Plan(root, new Stats(0, 0, 0, 0, List.of()), List.of());
            }
            requireDirectory(root);
            List<Path> children;
            try (var stream = Files.list(root)) { children = stream.toList(); }
            for (Path child : children) {
                if (Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(child)) {
                    total += Files.size(child);
                    continue;
                }
                if (!DIRECTORY_NAME.matcher(child.getFileName().toString()).matches()) continue;
                try {
                    requireDirectory(child);
                    List<Candidate> parts = new ArrayList<>();
                    try (var files = Files.list(child)) {
                        for (Path file : files.toList()) {
                            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) continue;
                            BasicFileAttributes attributes = regularFile(file);
                            total += attributes.size();
                            if (PART_NAME.matcher(file.getFileName().toString()).matches()) {
                                parts.add(new Candidate(file, attributes.size()));
                            }
                        }
                    }
                    String manifestName = child.getFileName().toString().substring(0,
                            child.getFileName().toString().length() - ".parts".length());
                    Path current = root.resolve(manifestName);
                    List<Path> manifests = new ArrayList<>();
                    manifests.add(current);
                    for (Path sibling : children) {
                        if (sibling.getFileName().toString().startsWith(manifestName + ".rejected-")) manifests.add(sibling);
                    }
                    Set<String> retained = new HashSet<>();
                    List<ManifestStamp> stamps = new ArrayList<>();
                    for (Path manifest : manifests) {
                        BasicFileAttributes attributes = regularFile(manifest);
                        retained.addAll(readReferences(manifest, minecraftVersion, attributes.size()));
                        stamps.add(new ManifestStamp(manifest, attributes.size(), attributes.lastModifiedTime()));
                    }
                    for (String reference : retained) regularFile(child.resolve(reference));
                    List<Candidate> unused = parts.stream()
                            .filter(part -> !retained.contains(part.file().getFileName().toString())).toList();
                    reclaimable += unused.stream().mapToLong(Candidate::bytes).sum();
                    reclaimableFiles += unused.size();
                    servers.add(new ServerPlan(child, unused, List.copyOf(stamps)));
                } catch (IOException | RuntimeException exception) {
                    skipped++;
                    problems.add(problem(child, exception));
                }
            }
        } catch (IOException | RuntimeException exception) {
            problems.add(problem(root, exception));
        }
        return new Plan(root, new Stats(total, reclaimable, reclaimableFiles, skipped, problems), List.copyOf(servers));
    }

    private static Set<String> readReferences(Path file, String minecraftVersion, long size) throws IOException {
        if (size > MAX_MANIFEST_BYTES) throw new IOException("manifest is too large: " + file.getFileName());
        JsonObject root;
        try {
            root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.get("format").isJsonPrimitive() || !root.getAsJsonPrimitive("format").isNumber()
                    || !"2".equals(root.get("format").getAsString())
                    || !root.get("minecraft").isJsonPrimitive() || !root.getAsJsonPrimitive("minecraft").isString()
                    || !minecraftVersion.equals(root.get("minecraft").getAsString())
                    || !root.get("parts").isJsonArray()) {
                throw new IOException("unsupported or invalid manifest: " + file.getFileName());
            }
            Set<String> references = new HashSet<>();
            for (JsonElement element : root.getAsJsonArray("parts")) {
                JsonObject part = element.getAsJsonObject();
                if (!part.get("file").isJsonPrimitive() || !part.getAsJsonPrimitive("file").isString()
                        || !PART_NAME.matcher(part.get("file").getAsString()).matches()
                        || !part.get("count").isJsonPrimitive() || !part.getAsJsonPrimitive("count").isNumber()
                        || !part.get("count").getAsString().matches("[0-9]+")
                        || part.get("count").getAsLong() > Integer.MAX_VALUE) {
                    throw new IOException("invalid part reference: " + file.getFileName());
                }
                references.add(part.get("file").getAsString());
            }
            return references;
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException exception) {
            throw new IOException("invalid manifest: " + file.getFileName(), exception);
        }
    }

    private static void requireDirectory(Path directory) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || Files.isSymbolicLink(directory)
                || !directory.toRealPath().equals(directory.toAbsolutePath().normalize())) {
            throw new IOException("cache directory is not an ordinary local directory: " + directory);
        }
    }

    private static BasicFileAttributes regularFile(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || Files.isSymbolicLink(file)
                || !file.toRealPath().equals(file.toAbsolutePath().normalize())) {
            throw new IOException("cache manifest or part is not an ordinary local file: " + file);
        }
        return attributes;
    }

    private static String problem(Path path, Exception exception) {
        return path.getFileName() + ": " + exception.getClass().getSimpleName() + ": " + exception.getMessage();
    }
}
