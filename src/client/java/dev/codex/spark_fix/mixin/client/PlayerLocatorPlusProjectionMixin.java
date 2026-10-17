package dev.codex.spark_fix.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import dev.codex.spark_fix.PlayerLocatorProjection;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;

/** Keeps PLP's existing rendering while fixing the origins of unloaded-player markers. */
@Pseudo
@Mixin(targets = "sh.sit.plp.PlayerLocatorPlusClient", remap = false)
abstract class PlayerLocatorPlusProjectionMixin {
    @Shadow @Final private static Map<UUID, ?> relativePositions;
    @Unique private static final PlayerLocatorProjection sparkFix$projection = new PlayerLocatorProjection();

    @WrapOperation(
            method = "onInitializeClient$lambda$0(Lsh/sit/plp/network/PlayerLocationsS2CPayload;Lnet/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking$Context;)V",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;compute(Ljava/lang/Object;Ljava/util/function/BiFunction;)Ljava/lang/Object;"),
            remap = false
    )
    private static Object sparkFix$rememberUpdatedMarker(
            Map<?, ?> positions, Object id, BiFunction<?, ?, ?> update, Operation<Object> original
    ) {
        Object marker = original.call(positions, id, update);
        var player = Minecraft.getInstance().player;
        sparkFix$projection.updated(marker, player == null ? Vec3.ZERO : player.position());
        return marker;
    }

    @Inject(
            method = "onInitializeClient$lambda$0(Lsh/sit/plp/network/PlayerLocationsS2CPayload;Lnet/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking$Context;)V",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/locks/ReentrantLock;unlock()V"),
            remap = false
    )
    private static void sparkFix$forgetRemovedMarkers(CallbackInfo callbackInfo) {
        sparkFix$projection.retain(relativePositions.values());
    }

    @Inject(
            method = "onInitializeClient$lambda$1(Lnet/minecraft/client/multiplayer/ClientPacketListener;Lnet/minecraft/client/Minecraft;)V",
            at = @At(value = "INVOKE", target = "Ljava/util/concurrent/locks/ReentrantLock;unlock()V"),
            remap = false
    )
    private static void sparkFix$clearProjectionOrigins(CallbackInfo callbackInfo) {
        sparkFix$projection.clear();
    }

    @WrapOperation(
            method = "render(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(value = "INVOKE", target = "Lsh/sit/plp/network/RelativePlayerLocation;getDirection()Lorg/joml/Vector3f;"),
            remap = false
    )
    private Vector3f sparkFix$selectMarkerOrigin(
            @Coerce Object marker, Operation<Vector3f> original,
            @Share("sparkFix$markerOrigin") LocalRef<Vec3> origin
    ) {
        origin.set(sparkFix$projection.origin(marker));
        return original.call(marker);
    }

    @WrapOperation(
            method = "render(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;add(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;"),
            remap = false
    )
    private Vec3 sparkFix$projectFromMarkerOrigin(
            Vec3 sharedOrigin, Vec3 directionAndDistance, Operation<Vec3> original,
            GuiGraphicsExtractor context, DeltaTracker tickCounter,
            @Share("sparkFix$markerOrigin") LocalRef<Vec3> origin
    ) {
        Vec3 markerOrigin = origin.get();
        if (markerOrigin == null) {
            // Vanilla directions were computed from this frame's camera, rather than a packet.
            // The existing subtract(playerPosition) must cancel this same interpolated origin.
            markerOrigin = Minecraft.getInstance().player.getPosition(tickCounter.getGameTimeDeltaPartialTick(false));
        }
        return original.call(markerOrigin, directionAndDistance);
    }

    /**
     * PLP calculates every marker's horizontal angle from the local player's view vector. In
     * front third-person that vector still follows the player, while the HUD is projected from
     * the camera looking back at the player. Vanilla waypoints are converted to temporary PLP
     * markers from the main-camera yaw as well, so they must use the same camera frame here.
     */
    @WrapOperation(
            method = "render(Lnet/minecraft/client/gui/GuiGraphicsExtractor;Lnet/minecraft/client/DeltaTracker;)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;getViewVector(F)Lnet/minecraft/world/phys/Vec3;"
            ),
            remap = false
    )
    private Vec3 sparkFix$useMainCameraViewVector(
            LocalPlayer player, float partialTick, Operation<Vec3> original
    ) {
        var minecraft = Minecraft.getInstance();
        var gameRenderer = minecraft.gameRenderer;
        if (gameRenderer == null || gameRenderer.mainCamera() == null) {
            return original.call(player, partialTick);
        }
        return new Vec3(gameRenderer.mainCamera().forwardVector());
    }
}
