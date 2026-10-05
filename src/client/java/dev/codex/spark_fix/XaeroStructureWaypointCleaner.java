package dev.codex.spark_fix;

import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Removes only waypoints created by Structure Finder. */
public final class XaeroStructureWaypointCleaner {
    public static final String INITIALS = "SF";

    private XaeroStructureWaypointCleaner() {
    }

    public static boolean hasWaypoints(MinimapWorld world) {
        if (world == null) return false;
        for (WaypointSet set : world.getIterableWaypointSets()) {
            for (Waypoint waypoint : set.getWaypoints()) {
                if (isStructureWaypoint(waypoint)) return true;
            }
        }
        return false;
    }

    public static int delete(MinimapWorld world, WorldSaver saver) throws IOException {
        if (world == null) return 0;
        List<SetSnapshot> affected = new ArrayList<>();
        int count = 0;
        for (WaypointSet set : world.getIterableWaypointSets()) {
            List<Waypoint> before = new ArrayList<>();
            List<Waypoint> remove = new ArrayList<>();
            for (Waypoint waypoint : set.getWaypoints()) {
                before.add(waypoint);
                if (isStructureWaypoint(waypoint)) remove.add(waypoint);
            }
            if (!remove.isEmpty()) {
                affected.add(new SetSnapshot(set, before, remove));
                count += remove.size();
            }
        }
        if (count == 0) return 0;
        for (SetSnapshot snapshot : affected) snapshot.set().removeAll(snapshot.remove());
        try {
            saver.save(world);
        } catch (IOException | RuntimeException exception) {
            for (SetSnapshot snapshot : affected) {
                snapshot.set().clear();
                snapshot.set().addAll(snapshot.before());
            }
            throw exception;
        }
        return count;
    }

    public static boolean isStructureWaypoint(Waypoint waypoint) {
        if (waypoint == null || waypoint.isThirdParty()) return false;
        if (waypoint.getPurpose() != null && waypoint.getPurpose().isDeath()) return false;
        if (!INITIALS.equalsIgnoreCase(waypoint.getSymbol())) return false;
        String name = waypoint.getName();
        return StructureFinderCatalog.entries().stream()
                .anyMatch(entry -> entry.zhName().equals(name) || entry.enName().equals(name));
    }

    private record SetSnapshot(WaypointSet set, List<Waypoint> before, List<Waypoint> remove) {
    }

    @FunctionalInterface
    public interface WorldSaver {
        void save(MinimapWorld world) throws IOException;
    }
}
