package dev.codex.spark_fix;

import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Deletes Xaero's automatic deathpoints from exactly one world/dimension. */
public final class XaeroDeathpointCleaner {
    private XaeroDeathpointCleaner() {
    }

    public static boolean hasDeathpoints(MinimapWorld world) {
        if (world == null) return false;
        for (WaypointSet set : world.getIterableWaypointSets()) {
            for (Waypoint waypoint : set.getWaypoints()) {
                if (isAutomaticDeathpoint(waypoint)) return true;
            }
        }
        return false;
    }

    public static int delete(MinimapWorld world, WorldSaver saver) throws IOException {
        if (world == null) return 0;
        Set<Waypoint> deathpoints = Collections.newSetFromMap(new IdentityHashMap<>());
        List<SetSnapshot> affectedSets = new ArrayList<>();
        for (WaypointSet set : world.getIterableWaypointSets()) {
            List<Waypoint> before = new ArrayList<>();
            boolean affected = false;
            for (Waypoint waypoint : set.getWaypoints()) {
                before.add(waypoint);
                if (isAutomaticDeathpoint(waypoint)) {
                    deathpoints.add(waypoint);
                    affected = true;
                }
            }
            if (affected) affectedSets.add(new SetSnapshot(set, before));
        }
        if (deathpoints.isEmpty()) return 0;

        for (SetSnapshot snapshot : affectedSets) {
            snapshot.set().removeAll(deathpoints);
        }
        try {
            saver.save(world);
        } catch (IOException | RuntimeException exception) {
            for (SetSnapshot snapshot : affectedSets) {
                snapshot.set().clear();
                snapshot.set().addAll(snapshot.waypoints());
            }
            throw exception;
        }
        return deathpoints.size();
    }

    private static boolean isAutomaticDeathpoint(Waypoint waypoint) {
        WaypointPurpose purpose = waypoint.getPurpose();
        return !waypoint.isThirdParty() && purpose != null && purpose.isDeath();
    }

    private record SetSnapshot(WaypointSet set, List<Waypoint> waypoints) {
    }

    @FunctionalInterface
    public interface WorldSaver {
        void save(MinimapWorld world) throws IOException;
    }
}
