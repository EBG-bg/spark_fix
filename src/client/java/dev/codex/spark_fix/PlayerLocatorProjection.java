package dev.codex.spark_fix;

import net.minecraft.world.phys.Vec3;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

/** Per-packet marker origins, accessed under Player Locator Plus's existing positions lock. */
public final class PlayerLocatorProjection {
    private final Map<Object, Vec3> origins = new IdentityHashMap<>();

    public void updated(Object marker, Vec3 origin) {
        this.origins.put(marker, origin);
    }

    public Vec3 origin(Object marker) {
        return this.origins.get(marker);
    }

    public void retain(Collection<?> markers) {
        // Marker records have value equality, but an origin belongs to a received instance.
        Map<Object, Boolean> retained = new IdentityHashMap<>();
        for (Object marker : markers) retained.put(marker, Boolean.TRUE);
        this.origins.keySet().removeIf(marker -> !retained.containsKey(marker));
    }

    public void clear() {
        this.origins.clear();
    }
}
