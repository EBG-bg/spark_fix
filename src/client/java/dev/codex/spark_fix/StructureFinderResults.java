package dev.codex.spark_fix;

import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Groups recognised pieces and batches local notifications without accessing a world. */
final class StructureFinderResults {
    private static final int MAX_DETECTIONS = 256;
    private static final long HIGHLIGHT_LIFETIME_MILLIS = 60 * 60 * 1_000;
    private static final long NOTIFICATION_INTERVAL_MILLIS = 2_000;
    private final List<Group> groups = new ArrayList<>();
    private List<StructureFinder.Detection> snapshot = List.of();
    private long notifyAt = -1;
    private long expireAt = Long.MAX_VALUE;
    private long discoverySequence;

    List<StructureFinder.Detection> detections() { return snapshot; }

    void clear() {
        groups.clear();
        snapshot = List.of();
        notifyAt = -1;
        expireAt = Long.MAX_VALUE;
        discoverySequence = 0;
    }

    /** Revalidate matches after a threshold change without losing their history. */
    void clearMatches() {
        for (Group group : groups) group.visible = false;
        snapshot = List.of();
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
            } else expireAt = Math.min(expireAt, deadline);
        }
        if (changed) updateSnapshot();
        if (groups.stream().noneMatch(StructureFinderResults::pending)) notifyAt = -1;
    }

    void add(StructureFinder.Detection detection, long nowMillis) {
        expire(nowMillis);
        StructureFinder.Detection merged = detection;
        boolean announced = false;
        boolean dismissed = false;
        long firstDetectedAt = nowMillis;
        long sequence = discoverySequence;
        List<Group> connected = new ArrayList<>();
        // Continue after a bridge joins two previously separate groups. Returning
        // after the first neighbour leaves overlapping groups and duplicate notices.
        boolean expanded;
        do {
            expanded = false;
            for (Group group : groups) {
                if (connected.contains(group) || !near(merged, group.detection)) continue;
                connected.add(group);
                StructureFinder.Detection old = group.detection;
                merged = new StructureFinder.Detection(old.type(), old.pos(),
                        old.bounds().minmax(merged.bounds()), Math.max(old.similarity(), merged.similarity()));
                announced |= group.announced;
                dismissed |= group.dismissed;
                firstDetectedAt = Math.min(firstDetectedAt, group.firstDetectedAt);
                sequence = Math.min(sequence, group.sequence);
                expanded = true;
            }
        } while (expanded);
        if (connected.size() == 1 && connected.getFirst().detection.equals(merged)
                && (connected.getFirst().visible || dismissed)) return;
        groups.removeAll(connected);
        if (!dismissed && groups.stream().filter(group -> group.visible).count() >= MAX_DETECTIONS) {
            Group oldest = groups.stream().filter(group -> group.visible)
                    .min(java.util.Comparator.comparingLong((Group group) -> group.firstDetectedAt)
                            .thenComparingLong(group -> group.sequence)).orElseThrow();
            oldest.visible = false;
            oldest.dismissed = true;
            oldest.announced = true;
        }
        if (connected.isEmpty()) discoverySequence++;
        groups.add(new Group(merged, announced, dismissed, firstDetectedAt, sequence));
        if (!dismissed) expireAt = Math.min(expireAt, firstDetectedAt + HIGHLIGHT_LIFETIME_MILLIS);
        if (!announced && !dismissed && notifyAt < 0) notifyAt = nowMillis + NOTIFICATION_INTERVAL_MILLIS;
        updateSnapshot();
    }

    /** Hides one detected group while retaining its bounds for future rescans. */
    boolean dismiss(StructureFinder.Detection detection) {
        if (detection == null) return false;
        // Prefer an exact token match when adjacent same-type groups overlap.
        for (Group group : groups) {
            if (group.detection.equals(detection)) {
                return !group.dismissed && dismissGroup(group);
            }
        }
        for (Group group : groups) {
            if (!near(detection, group.detection)) continue;
            return !group.dismissed && dismissGroup(group);
        }
        return false;
    }

    private boolean dismissGroup(Group group) {
        group.dismissed = true;
        group.visible = false;
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
        // Mineshaft signatures cover two supports, not a whole network of tunnels.
        // Keep nearby corridors together, including adjacent vertical levels.
        double horizontal = first.type().equals("mineshaft") ? 16 : 4;
        double vertical = first.type().equals("mineshaft") ? 8 : 4;
        AABB bounds = second.bounds();
        return first.bounds().intersects(bounds.minX - horizontal, bounds.minY - vertical,
                bounds.minZ - horizontal, bounds.maxX + horizontal,
                bounds.maxY + vertical, bounds.maxZ + horizontal);
    }

    private void updateSnapshot() {
        snapshot = groups.stream()
                .filter(group -> group.visible)
                .map(group -> group.detection)
                .toList();
    }

    private static boolean pending(Group group) {
        return group.visible && !group.announced;
    }

    private static final class Group {
        final StructureFinder.Detection detection;
        boolean announced;
        boolean dismissed;
        boolean visible;
        final long firstDetectedAt;
        final long sequence;

        Group(StructureFinder.Detection detection, boolean announced, boolean dismissed,
              long firstDetectedAt, long sequence) {
            this.detection = detection;
            this.announced = announced;
            this.dismissed = dismissed;
            this.visible = !dismissed;
            this.firstDetectedAt = firstDetectedAt;
            this.sequence = sequence;
        }
    }
}
