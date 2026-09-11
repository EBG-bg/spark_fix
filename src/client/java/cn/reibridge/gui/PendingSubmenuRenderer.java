package cn.reibridge.gui;

import me.shedaniel.rei.api.client.gui.compat.GuiGraphics;

import java.util.ArrayDeque;
import java.util.Deque;

public final class PendingSubmenuRenderer {
    private static final ThreadLocal<Deque<PendingSubmenu>> PENDING =
            ThreadLocal.withInitial(ArrayDeque::new);

    private PendingSubmenuRenderer() {
    }

    public static void defer(GuiGraphics graphics, Runnable render) {
        PENDING.get().addLast(new PendingSubmenu(graphics, render));
    }

    public static void renderPending() {
        Deque<PendingSubmenu> pending = PENDING.get();
        while (!pending.isEmpty()) {
            PendingSubmenu submenu = pending.removeFirst();
            submenu.graphics().withFreshScissorStack(submenu.render());
        }
        PENDING.remove();
    }

    private record PendingSubmenu(GuiGraphics graphics, Runnable render) {
    }
}
