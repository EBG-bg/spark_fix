package com.adofaigo.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.Locale;

/** Launch feedback above the hotbar without replacing server action-bar messages. */
public final class AdofoigoLaunchHud {
    private AdofoigoLaunchHud() { }

    public static void register() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath("spark_fix", "adofaigo_launch"), (graphics, delta) -> {
                    Minecraft client = Minecraft.getInstance();
                    if (client.player == null) return;
                    SteamLauncher.LaunchStatus status = SteamLauncher.status();
                    long now = System.nanoTime();
                    Component message;
                    int color = 0xFFFFFFFF;
                    switch (status.stage()) {
                        case IDLE -> { return; }
                        case COUNTDOWN -> message = Component.translatable("config.spark_fix.adofaigo_countdown",
                                String.format(Locale.ROOT, "%.1f", Math.max(0, (status.deadlineNanos() - now) / 1_000_000_000.0)));
                        case WAITING -> message = Component.translatable("config.spark_fix.adofaigo_waiting");
                        default -> {
                            if (now >= status.deadlineNanos()) return;
                            String key = switch (status.stage()) {
                                case STARTED -> "started";
                                case TIMED_OUT -> "timeout";
                                case UNSUPPORTED -> "unsupported";
                                default -> "failed";
                            };
                            message = Component.translatable("config.spark_fix.adofaigo_" + key);
                            int alpha = (int) (255 * Math.clamp((status.deadlineNanos() - now) / 500_000_000.0, 0, 1));
                            color = (alpha << 24) | (status.stage() == SteamLauncher.Stage.STARTED ? 0x55FF55 : 0xFF7070);
                        }
                    }
                    var lines = client.font.split(message, Math.max(1, graphics.guiWidth() - 24));
                    int y = Math.max(4, graphics.guiHeight() - 92 - (lines.size() - 1) * (client.font.lineHeight + 2));
                    for (var line : lines) {
                        graphics.text(client.font, line, (graphics.guiWidth() - client.font.width(line)) / 2, y, color);
                        y += client.font.lineHeight + 2;
                    }
                });
    }
}
