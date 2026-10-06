package dev.codex.spark_fix;

import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Groups recognised pieces and batches local notifications without accessing a world. */
final class StructureFinderResults {
    private static final int MAX_DETECTIONS = 256;
    private static final int MAX_PIECES_PER_GROUP = 512;
    // Client evidence has no structure-start ID. Tolerate gaps between sparse
    // real pieces, but bound large structures so nearby sites cannot chain forever.
    private record Grouping(double horizontalGap, double verticalGap,
                            double horizontalSpan, double verticalSpan) {}
    private static final Grouping COMPACT = new Grouping(4, 4, 96, 96);
    private static final Grouping CITY = new Grouping(112, 24, 256, 64);
    private static final Grouping FORTRESS = new Grouping(96, 32, 320, 128);
    private static final Grouping BASTION = new Grouping(32, 24, 192, 160);
    private static final Grouping END_CITY = new Grouping(48, 48, 384, 256);
    private static final Grouping VILLAGE = new Grouping(48, 24, 256, 160);
    private static final Grouping MANSION = new Grouping(24, 16, 128, 96);
    private static final Grouping MONUMENT = new Grouping(24, 16, 80, 64);
    private static final Grouping STRONGHOLD = new Grouping(48, 24, 256, 160);
    private static final Grouping TRAIL_RUINS = new Grouping(24, 16, 192, 128);
    private static final Grouping TRIAL_CHAMBERS = new Grouping(32, 24, 256, 128);
    private static final Grouping OCEAN_RUINS = new Grouping(32, 16, 64, 64);
    private static final Grouping OUTPOST = new Grouping(24, 12, 192, 64);
    private static final Grouping IGLOO = new Grouping(4, 48, 32, 80);
    private static final Grouping MINE = new Grouping(16, 8, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY);
    private static final long HIGHLIGHT_LIFETIME_MILLIS = 60 * 60 * 1_000;
    private static final long NOTIFICATION_INTERVAL_MILLIS = 2_000;
    private final List<Group> groups = new ArrayList<>();
    private List<StructureFinder.Detection> snapshot = List.of();
    private volatile List<HighlightGroup> highlightSnapshot = List.of();
    private long notifyAt = -1;
    private long expireAt = Long.MAX_VALUE;
    private long discoverySequence;

    List<StructureFinder.Detection> detections() { return snapshot; }

    /** Actual matched pieces, never the empty volume of a group's enclosing box. */
    List<HighlightGroup> highlightGroups() { return highlightSnapshot; }

    int featureCount(StructureFinder.Detection detection) {
        for (Group group : groups) {
            if (group.visible && (sameCore(group.detection, detection)
                    || group.activePieces.values().stream().anyMatch(piece -> sameCore(piece, detection)))) {
                return group.activePieces.size();
            }
        }
        return 1;
    }

    record HighlightGroup(long id, StructureFinder.Detection detection, List<StructureFinder.Detection> pieces) {}

    List<StructureFinder.Detection> rememberedPieces(StructureFinder.Detection detection) {
        for (Group group : groups) {
            if (sameCore(group.detection, detection)
                    || group.pieces.values().stream().anyMatch(piece -> sameCore(piece, detection))) {
                return List.copyOf(group.pieces.values());
            }
        }
        return List.of();
    }

    void clear() {
        groups.clear();
        snapshot = List.of();
        highlightSnapshot = List.of();
        notifyAt = -1;
        expireAt = Long.MAX_VALUE;
        discoverySequence = 0;
    }

    /** Revalidate matches after a threshold change without losing their history. */
    void clearMatches() {
        for (Group group : groups) {
            group.visible = false;
            group.activePieces.clear();
        }
        snapshot = List.of();
        highlightSnapshot = List.of();
        notifyAt = -1;
    }

    void retainTypes(Set<String> types) {
        if (groups.removeIf(group -> !types.contains(group.detection.type()))) updateSnapshot();
        if (groups.stream().noneMatch(StructureFinderResults::pending)) notifyAt = -1;
    }

    /** Expired bounds remain remembered, so an unchanged rescan cannot revive them. */
    void expire(long nowMillis) {
        if (nowMillis < expireAt) return;
        boolean changed = false;
        expireAt = Long.MAX_VALUE;
        for (Group group : groups) {
            if (group.dismissed) continue;
            long deadline = group.firstDetectedAt + HIGHLIGHT_LIFETIME_MILLIS;
            if (nowMillis >= deadline) {
                changed |= group.visible;
                group.dismissed = true;
                group.visible = false;
                group.announced = true;
                group.activePieces.clear();
            } else expireAt = Math.min(expireAt, deadline);
        }
        if (changed) updateSnapshot();
        if (groups.stream().noneMatch(StructureFinderResults::pending)) notifyAt = -1;
    }

    void add(StructureFinder.Detection detection, long nowMillis) {
        expire(nowMillis);
        PieceKey key = PieceKey.of(detection);
        // The usual rescan is a no-op. Do not copy all stored pieces, rebuild
        // snapshots or walk every corridor for the same successful match.
        for (Group group : groups) {
            var old = group.pieces.get(key);
            if (old == null) continue;
            if (group.dismissed) return;
            var active = group.activePieces.get(key);
            if (group.visible && active != null && active.similarity() >= detection.similarity()) return;
            break;
        }
        boolean announced = false;
        boolean dismissed = false;
        long firstDetectedAt = nowMillis;
        long sequence = discoverySequence;
        Map<PieceKey, StructureFinder.Detection> pieces = new LinkedHashMap<>();
        Map<PieceKey, StructureFinder.Detection> activePieces = new LinkedHashMap<>();
        putPiece(pieces, detection);
        putPiece(activePieces, detection);
        List<Group> connected = new ArrayList<>();
        AABB combinedBounds = detection.bounds();
        // Existing groups are already connected components. Only the newly
        // added real piece can bridge them; compare it to each member once.
        // An aggregate box is allowed only as a cheap rejection, never proof
        // of adjacency. This avoids growing piece-by-piece Cartesian searches.
        for (Group group : groups) {
            if (!couldConnect(detection, group) || unionSize(pieces, group.pieces) > MAX_PIECES_PER_GROUP
                    || group.pieces.values().stream().noneMatch(piece -> near(detection, piece))
                    || !canCombineBounds(detection.type(), combinedBounds, group.rememberedBounds)) continue;
            connected.add(group);
            combinedBounds = combinedBounds.minmax(group.rememberedBounds);
            for (var piece : group.pieces.values()) putPiece(pieces, piece);
            for (var piece : group.activePieces.values()) putPiece(activePieces, piece);
            announced |= group.announced;
            dismissed |= group.dismissed;
            firstDetectedAt = Math.min(firstDetectedAt, group.firstDetectedAt);
            sequence = Math.min(sequence, group.sequence);
        }
        if (connected.size() == 1) {
            Group old = connected.getFirst();
            if (old.pieces.equals(pieces) && old.activePieces.equals(activePieces)
                    && (old.visible || dismissed)) return;
        }
        groups.removeAll(connected);
        if (!dismissed && groups.stream().filter(group -> group.visible).count() >= MAX_DETECTIONS) {
            Group oldest = groups.stream().filter(group -> group.visible)
                    .min(java.util.Comparator.comparingLong((Group group) -> group.firstDetectedAt)
                            .thenComparingLong(group -> group.sequence)).orElseThrow();
            oldest.visible = false;
            oldest.dismissed = true;
            oldest.announced = true;
            oldest.activePieces.clear();
        }
        if (connected.isEmpty()) discoverySequence++;
        // Keep the earliest group's representative stable for existing chat links.
        StructureFinder.Detection representative = connected.stream()
                .min(java.util.Comparator.comparingLong(group -> group.sequence))
                .map(group -> group.detection)
                .filter(old -> activePieces.values().stream().anyMatch(piece -> sameCore(old, piece)))
                .orElse(detection);
        AABB bounds = detection.bounds();
        int strongest = detection.similarity();
        for (var piece : activePieces.values()) {
            bounds = bounds.minmax(piece.bounds());
            strongest = Math.max(strongest, piece.similarity());
        }
        StructureFinder.Detection merged = new StructureFinder.Detection(detection.type(), representative.pos(),
                bounds, strongest);
        groups.add(new Group(merged, pieces, activePieces, announced, dismissed, firstDetectedAt, sequence));
        if (!dismissed) expireAt = Math.min(expireAt, firstDetectedAt + HIGHLIGHT_LIFETIME_MILLIS);
        if (!announced && !dismissed && notifyAt < 0) notifyAt = nowMillis + NOTIFICATION_INTERVAL_MILLIS;
        updateSnapshot();
    }

    /** Hides one detected group while retaining its bounds for future rescans. */
    boolean dismiss(StructureFinder.Detection detection) {
        if (detection == null) return false;
        // An old token names a real piece or its stable representative. A large
        // enclosing box must never dismiss a separate group inside its empty space.
        for (Group group : groups) {
            if (sameCore(group.detection, detection)
                    || group.pieces.values().stream().anyMatch(piece -> sameCore(piece, detection))) {
                return !group.dismissed && dismissGroup(group);
            }
        }
        return false;
    }

    /** Suppress every remembered result, including matches awaiting revalidation. */
    int dismissAll() {
        int visible = 0;
        for (Group group : groups) {
            if (group.visible) visible++;
            group.dismissed = true;
            group.visible = false;
            group.activePieces.clear();
            group.announced = true;
        }
        notifyAt = -1;
        expireAt = Long.MAX_VALUE;
        updateSnapshot();
        return visible;
    }

    private boolean dismissGroup(Group group) {
        group.dismissed = true;
        group.visible = false;
        group.activePieces.clear();
        // A dismissed group must never leak through the pending notification batch.
        group.announced = true;
        updateSnapshot();
        if (groups.stream().noneMatch(StructureFinderResults::pending)) {
            notifyAt = -1;
        }
        return true;
    }

    /** One call returns every new group in this batch; the caller sends one message. */
    List<StructureFinder.Detection> drainNotifications(long nowMillis) {
        expire(nowMillis);
        if (notifyAt < 0 || nowMillis < notifyAt) return List.of();
        List<StructureFinder.Detection> pending = new ArrayList<>();
        for (Group group : groups) {
            if (!pending(group)) continue;
            pending.add(group.detection);
            group.announced = true;
        }
        notifyAt = -1;
        return List.copyOf(pending);
    }

    private static boolean near(StructureFinder.Detection first, StructureFinder.Detection second) {
        if (!first.type().equals(second.type())) return false;
        // A chest or spawner is an independently actionable entity even when
        // the surrounding rooms overlap or almost touch.
        if (first.type().equals("buried_treasure") || first.type().equals("monster_room")) {
            return first.pos().equals(second.pos());
        }
        // Mineshaft signatures cover two supports, not a whole network of tunnels.
        // Keep nearby corridors together, including adjacent vertical levels.
        return nearBounds(first.type(), first.bounds(), second.bounds());
    }

    private static boolean couldConnect(StructureFinder.Detection detection, Group group) {
        if (!detection.type().equals(group.detection.type())) return false;
        if (detection.type().equals("buried_treasure") || detection.type().equals("monster_room")) {
            return detection.pos().equals(group.detection.pos());
        }
        return nearBounds(detection.type(), detection.bounds(), group.rememberedBounds);
    }

    private static boolean nearBounds(String type, AABB first, AABB bounds) {
        Grouping grouping = grouping(type);
        if (grouping == MINE || grouping == COMPACT) {
            return first.intersects(bounds.minX - grouping.horizontalGap, bounds.minY - grouping.verticalGap,
                    bounds.minZ - grouping.horizontalGap, bounds.maxX + grouping.horizontalGap,
                    bounds.maxY + grouping.verticalGap, bounds.maxZ + grouping.horizontalGap);
        }
        double x = axisGap(first.minX, first.maxX, bounds.minX, bounds.maxX);
        double z = axisGap(first.minZ, first.maxZ, bounds.minZ, bounds.maxZ);
        double y = axisGap(first.minY, first.maxY, bounds.minY, bounds.maxY);
        return x * x + z * z <= grouping.horizontalGap * grouping.horizontalGap && y <= grouping.verticalGap;
    }

    private static double axisGap(double firstMin, double firstMax, double secondMin, double secondMax) {
        return Math.max(0, Math.max(firstMin - secondMax, secondMin - firstMax));
    }

    private static boolean canCombineBounds(String type, AABB first, AABB second) {
        Grouping grouping = grouping(type);
        AABB combined = first.minmax(second);
        return combined.maxX - combined.minX <= grouping.horizontalSpan
                && combined.maxZ - combined.minZ <= grouping.horizontalSpan
                && combined.maxY - combined.minY <= grouping.verticalSpan;
    }

    private static Grouping grouping(String type) {
        return switch (type) {
            case "ancient_city" -> CITY;
            case "fortress" -> FORTRESS;
            case "bastion_remnant" -> BASTION;
            case "end_city" -> END_CITY;
            case "village" -> VILLAGE;
            case "mansion" -> MANSION;
            case "monument" -> MONUMENT;
            case "stronghold" -> STRONGHOLD;
            case "trail_ruins" -> TRAIL_RUINS;
            case "trial_chambers" -> TRIAL_CHAMBERS;
            case "underwater_ruin" -> OCEAN_RUINS;
            case "pillager_outpost" -> OUTPOST;
            case "igloo" -> IGLOO;
            case "mineshaft" -> MINE;
            default -> COMPACT;
        };
    }

    private void updateSnapshot() {
        snapshot = groups.stream()
                .filter(group -> group.visible)
                .map(group -> group.detection)
                .toList();
        highlightSnapshot = groups.stream().filter(group -> group.visible)
                .map(group -> new HighlightGroup(group.sequence + 1, group.detection,
                        List.copyOf(group.activePieces.values())))
                .toList();
    }

    private static int unionSize(Map<PieceKey, StructureFinder.Detection> first,
                                 Map<PieceKey, StructureFinder.Detection> second) {
        int size = first.size();
        for (PieceKey key : second.keySet()) if (!first.containsKey(key) && ++size > MAX_PIECES_PER_GROUP) break;
        return size;
    }

    private static boolean sameCore(StructureFinder.Detection first, StructureFinder.Detection second) {
        return first.type().equals(second.type()) && first.pos().equals(second.pos());
    }

    private static void putPiece(Map<PieceKey, StructureFinder.Detection> pieces,
                                 StructureFinder.Detection detection) {
        pieces.merge(PieceKey.of(detection), detection,
                (old, fresh) -> old.similarity() >= fresh.similarity() ? old : fresh);
    }

    private record PieceKey(String type, net.minecraft.core.BlockPos pos, AABB bounds) {
        static PieceKey of(StructureFinder.Detection detection) {
            boolean core = detection.type().equals("buried_treasure") || detection.type().equals("monster_room");
            return new PieceKey(detection.type(), detection.pos(), core ? null : detection.bounds());
        }
    }

    private static boolean pending(Group group) {
        return group.visible && !group.announced;
    }

    private static final class Group {
        final StructureFinder.Detection detection;
        final Map<PieceKey, StructureFinder.Detection> pieces;
        final Map<PieceKey, StructureFinder.Detection> activePieces;
        final AABB rememberedBounds;
        boolean announced;
        boolean dismissed;
        boolean visible;
        final long firstDetectedAt;
        final long sequence;

        Group(StructureFinder.Detection detection, Map<PieceKey, StructureFinder.Detection> pieces,
              Map<PieceKey, StructureFinder.Detection> activePieces, boolean announced, boolean dismissed,
              long firstDetectedAt, long sequence) {
            this.detection = detection;
            this.pieces = pieces;
            this.activePieces = activePieces;
            if (dismissed) this.activePieces.clear();
            AABB retained = detection.bounds();
            for (var piece : pieces.values()) retained = retained.minmax(piece.bounds());
            this.rememberedBounds = retained;
            this.announced = announced;
            this.dismissed = dismissed;
            this.visible = !dismissed;
            this.firstDetectedAt = firstDetectedAt;
            this.sequence = sequence;
        }
    }
}
