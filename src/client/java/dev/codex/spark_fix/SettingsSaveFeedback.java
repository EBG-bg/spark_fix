package dev.codex.spark_fix;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/** Shared failure feedback for the ordinary and optional settings layouts. */
final class SettingsSaveFeedback {
    private static final SystemToast.SystemToastId SAVE_FAILURE_TOAST = new SystemToast.SystemToastId();

    private SettingsSaveFeedback() { }

    static boolean saveNow() {
        if (SparkFixConfig.trySave()) return true;
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.gui != null && client.gui.toastManager() != null) {
            SystemToast.addOrUpdate(client.gui.toastManager(), SAVE_FAILURE_TOAST,
                    Component.translatable("config.spark_fix.save_failed"),
                    Component.translatable("config.spark_fix.save_failed_toast"));
        }
        return false;
    }

    static void showFailure(Screen editor, List<Component> failures, Runnable retry) {
        Component parts = Component.empty();
        for (int i = 0; i < failures.size(); i++) {
            if (i != 0) parts = parts.copy().append(", ");
            parts = parts.copy().append(failures.get(i));
        }
        Minecraft client = Minecraft.getInstance();
        client.setScreenAndShow(new ConfirmScreen(again -> {
            client.setScreenAndShow(editor);
            if (again) retry.run();
        }, Component.translatable("config.spark_fix.save_failed"),
                Component.translatable("config.spark_fix.save_failed_message", parts),
                Component.translatable("config.spark_fix.litematica_save_retry"),
                Component.translatable("config.spark_fix.litematica_save_back")));
    }

    static void showFailure(Screen editor, Runnable retry) {
        showFailure(editor, List.of(Component.translatable("config.spark_fix.title")), retry);
    }
}
