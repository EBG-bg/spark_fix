package dev.codex.spark_fix;

import java.util.function.BooleanSupplier;

/** Makes the printer's existing scan time budget cover filtering inside IteratorManager.next(). */
public final class PrinterScanBudget {
    private static final ThreadLocal<Budget> ACTIVE = new ThreadLocal<>();
    private static final ScanYield YIELD = new ScanYield();
    private static final int DEFAULT_TIME_LIMIT_MS = 8;

    private PrinterScanBudget() { }

    public static boolean run(int timeLimitMs, BooleanSupplier phase) {
        Budget previous = ACTIVE.get();
        int limit = timeLimitMs > 0 ? timeLimitMs : DEFAULT_TIME_LIMIT_MS;
        ACTIVE.set(new Budget(System.nanoTime() + limit * 1_000_000L));
        try {
            return phase.getAsBoolean();
        } catch (ScanYield yield) {
            // Module uses true for a paused scan. Do not invoke its completion
            // callback or clear its iterator; the next tick resumes this phase.
            return true;
        } finally {
            if (previous == null) ACTIVE.remove();
            else ACTIVE.set(previous);
        }
    }

    public static void checkpoint() {
        Budget budget = ACTIVE.get();
        // Bound the inner filter loop without reading the clock for every
        // coordinate. Check before consuming the next candidate to retain it.
        if (budget != null && (++budget.candidates & 63) == 0
                && System.nanoTime() - budget.deadline >= 0) {
            throw YIELD;
        }
    }

    private static final class Budget {
        private final long deadline;
        private int candidates;

        private Budget(long deadline) {
            this.deadline = deadline;
        }
    }

    private static final class ScanYield extends RuntimeException {
        private ScanYield() {
            super(null, null, false, false);
        }
    }
}
