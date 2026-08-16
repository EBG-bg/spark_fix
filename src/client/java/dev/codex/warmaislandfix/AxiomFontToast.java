package dev.codex.warmaislandfix;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/** Shows the same top-right vanilla error toast style used by Chat Patches. */
public final class AxiomFontToast {
    private static final AtomicBoolean RECOVERY_TOAST_SHOWN = new AtomicBoolean();
    private static final AtomicBoolean FAILURE_TOAST_SHOWN = new AtomicBoolean();

    private AxiomFontToast() {
    }

    public static void showRecovery(int requestedSize, int recoveredSize, boolean minimal) {
        if (!RECOVERY_TOAST_SHOWN.compareAndSet(false, true)) {
            return;
        }

        String detail = minimal
            ? "中文字体图集过大，已启用基础字体；部分中文可能显示为方框。"
            : "中文字体图集过大，字体已从 " + requestedSize + "px 限制为 "
                + recoveredSize + "px，Axiom 可继续使用。";
        show("Axiom 字体已安全降级", detail);
    }

    public static void showFailure() {
        if (FAILURE_TOAST_SHOWN.compareAndSet(false, true)) {
            show("Axiom 字体恢复失败", "已记录详细错误；请临时切换英文或禁用 Axiom。 ");
        }
    }

    private static void show(String title, String message) {
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.execute(() -> SystemToast.add(
            minecraft.gui.toastManager(),
            SystemToast.SystemToastId.PACK_LOAD_FAILURE,
            Component.literal(title),
            Component.literal(message)
        ));
    }
}
