package dev.codex.warmaislandfix;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;

/**
 * Bridges Chat Patches without adding a hard compile-time or runtime dependency on it.
 *
 * <p>Chat Patches marks its fallback component codec with a thread-local flag. While that
 * codec is active, registry-backed holder sets must be allowed to retain their tag key even
 * if the text component came from a different registry owner (for example a saved server
 * chat message containing an item component with a damage-type tag).</p>
 */
public final class ChatPatchesCodecSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger("warmaislandfix/ChatPatches");
    private static final boolean CHAT_PATCHES_LOADED =
        FabricLoader.getInstance().isModLoaded("chatpatches");

    private static volatile boolean lookupAttempted;
    private static volatile ThreadLocal<?> safeCodec;

    private ChatPatchesCodecSupport() {
    }

    /**
     * Keeps vanilla's result unless Chat Patches is currently using its deliberately
     * registry-tolerant codec. This narrow scope preserves the complete rich chat component
     * while leaving every other HolderSet serialization check unchanged.
     */
    public static boolean allowChatPatchesUnsafeSerialization(boolean vanillaResult) {
        if (vanillaResult || !CHAT_PATCHES_LOADED) {
            return vanillaResult;
        }

        ThreadLocal<?> flag = getSafeCodecFlag();
        return flag != null && Boolean.FALSE.equals(flag.get());
    }

    private static ThreadLocal<?> getSafeCodecFlag() {
        ThreadLocal<?> cached = safeCodec;
        if (cached != null || lookupAttempted) {
            return cached;
        }

        synchronized (ChatPatchesCodecSupport.class) {
            if (safeCodec != null || lookupAttempted) {
                return safeCodec;
            }

            lookupAttempted = true;
            try {
                Class<?> textUtil = Class.forName(
                    "obro1961.chatpatches.util.TextUtil",
                    false,
                    ChatPatchesCodecSupport.class.getClassLoader()
                );
                Field field = textUtil.getField("safeCodec");
                Object value = field.get(null);
                if (value instanceof ThreadLocal<?> threadLocal) {
                    safeCodec = threadLocal;
                } else {
                    LOGGER.warn("Chat Patches TextUtil.safeCodec is not a ThreadLocal; compatibility patch is inactive.");
                }
            } catch (ReflectiveOperationException | LinkageError exception) {
                LOGGER.warn("Could not access Chat Patches' codec scope; compatibility patch is inactive.", exception);
            }

            return safeCodec;
        }
    }
}