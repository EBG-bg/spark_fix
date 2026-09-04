package dev.codex.spark_fix.mixin.client;

import com.mojang.datafixers.util.Either;
import net.minecraft.client.waypoints.ClientWaypointManager;
import net.minecraft.world.waypoints.TrackedWaypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.UUID;

/**
 * Recovers from servers or proxies which send a waypoint UPDATE without a matching TRACK.
 * Vanilla dereferences the missing map entry and disconnects the client because the packet
 * is non-skippable. An UPDATE carries a complete TrackedWaypoint, so inserting it as the
 * initial value preserves the locator bar while restoring protocol state.
 */
@Mixin(ClientWaypointManager.class)
abstract class ClientWaypointManagerFixMixin {
    private static final Logger SPARK_FIX_LOGGER =
        LoggerFactory.getLogger("spark_fix/Waypoints");

    @Shadow
    @Final
    private Map<Either<UUID, String>, TrackedWaypoint> waypoints;

    @Inject(
        method = "updateWaypoint(Lnet/minecraft/world/waypoints/TrackedWaypoint;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private void sparkFix$recoverOrphanWaypointUpdate(
        TrackedWaypoint waypoint,
        CallbackInfo callbackInfo
    ) {
        Either<UUID, String> id = waypoint.id();
        TrackedWaypoint existing = this.waypoints.putIfAbsent(id, waypoint);
        if (existing == null) {
            SPARK_FIX_LOGGER.warn(
                "Recovered an orphan waypoint UPDATE for {} by treating it as TRACK; connection kept alive.",
                id
            );
            callbackInfo.cancel();
        }
    }
}