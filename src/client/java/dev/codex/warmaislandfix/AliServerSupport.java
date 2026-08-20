package dev.codex.warmaislandfix;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Detects whether the connected server accepts ALI's loot-data request packet
 * without creating a compile-time dependency on Advanced Loot Info.
 */
public final class AliServerSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger("warmaislandfix");
    private static final AtomicBoolean WARNING_LOGGED = new AtomicBoolean();
    private static final String REQUEST_MESSAGE_CLASS = "com.yanny.ali.network.RequestLootDataMessage";

    private AliServerSupport() {
    }

    /**
     * ALI otherwise waits on the render thread for three thirty-second request
     * timeouts. A plugin server has no Fabric ALI channel, so there is no valid
     * request to wait for.
     */
    public static boolean canRequestLootData() {
        try {
            ClassLoader loader = AliServerSupport.class.getClassLoader();
            Class<?> messageClass = Class.forName(REQUEST_MESSAGE_CLASS, false, loader);
            Field typeField = messageClass.getField("TYPE");
            Object type = typeField.get(null);
            if (type instanceof CustomPacketPayload.Type<?> payloadType) {
                return ClientPlayNetworking.canSend(payloadType);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            LOGGER.debug("Could not check the ALI loot-data channel; treating it as unavailable.", exception);
        }

        return false;
    }

    public static void logUnavailableChannel() {
        if (WARNING_LOGGED.compareAndSet(false, true)) {
            LOGGER.warn("The connected server does not support ALI loot-data requests. "
                + "warmaislandfix is registering ALI with empty server data to prevent JEI from freezing for 90 seconds.");
        }
    }
}
