package dev.codex.spark_fix.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Minecraft 26.2 can refresh the client chat UI from the integrated-server
 * thread while a world is being closed. Deferred font glyph baking then calls
 * RenderSystem from the wrong thread, kills the server thread, and leaves the
 * render thread waiting forever in IntegratedServer.halt().
 */
@Mixin(IntegratedServer.class)
abstract class IntegratedServerThreadFixMixin {
    @Redirect(
        method = "updatePermissionAndChatAbilities",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;refreshChatAbilities()V"
        )
    )
    private void sparkFix$refreshChatOnRenderThread(LocalPlayer player) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.isSameThread()) {
            player.refreshChatAbilities();
            return;
        }

        minecraft.execute(() -> {
            // Do not operate on a player which was already detached while the
            // integrated server was finishing its shutdown.
            if (minecraft.player == player) {
                player.refreshChatAbilities();
            }
        });
    }
}
