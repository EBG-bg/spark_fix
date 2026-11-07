package dev.codex.spark_fix;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

/** Client-thread retry bookkeeping. Only due retries are visited during a tick. */
final class StructureFinderFailures {
    private static final int MAX_FAILURES = 129 * 129;
    private static final int MAX_ATTEMPTS = 3;
    private final Map<Long, Failure> failures = new LinkedHashMap<>();
    private final TreeMap<Long, LinkedHashSet<Long>> due = new TreeMap<>();
    private int deferred;
    private String recentReason;

    record Failure(int attempts, long revision, long retryAt, String reason) {}

    /** Returns an evicted coordinate if the maximum loaded-area size was exceeded. */
    Long failed(long chunk, long revision, Throwable cause, long now) {
        Failure old = failures.get(chunk);
        int attempts = old == null || old.revision() != revision ? 1 : old.attempts() + 1;
        remove(chunk);
        long retryAt = attempts >= MAX_ATTEMPTS ? Long.MAX_VALUE
                : now + TimeUnit.SECONDS.toNanos(attempts == 1 ? 1 : 3);
        String message = cause.getMessage();
        String reason = cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        if (reason.length() > 256) reason = reason.substring(0, 256);
        recentReason = reason;
        Long evicted = null;
        if (failures.size() == MAX_FAILURES) {
            evicted = failures.keySet().iterator().next();
            remove(evicted);
        }
        failures.put(chunk, new Failure(attempts, revision, retryAt, reason));
        if (retryAt == Long.MAX_VALUE) deferred++;
        else due.computeIfAbsent(retryAt, ignored -> new LinkedHashSet<>()).add(chunk);
        return evicted;
    }

    boolean blocked(long chunk) {
        Failure failure = failures.get(chunk);
        return failure != null && failure.retryAt() != 0;
    }

    Long pollDue(long now) {
        var first = due.firstEntry();
        if (first == null || first.getKey() > now) return null;
        long chunk = first.getValue().iterator().next();
        first.getValue().remove(chunk);
        if (first.getValue().isEmpty()) due.pollFirstEntry();
        Failure failure = failures.get(chunk);
        failures.put(chunk, new Failure(failure.attempts(), failure.revision(), 0, failure.reason()));
        return chunk;
    }

    boolean remove(long chunk) {
        Failure failure = failures.remove(chunk);
        if (failure == null) return false;
        if (failure.retryAt() == Long.MAX_VALUE) deferred--;
        else if (failure.retryAt() != 0) {
            var chunks = due.get(failure.retryAt());
            if (chunks != null) {
                chunks.remove(chunk);
                if (chunks.isEmpty()) due.remove(failure.retryAt());
            }
        }
        if (failures.isEmpty()) recentReason = null;
        return true;
    }

    int count() { return failures.size(); }
    int deferred() { return deferred; }
    int pending() { return failures.size() - deferred; }
    String recentReason() { return recentReason; }
    int attempts(long chunk) { return failures.get(chunk).attempts(); }

    void clear() {
        failures.clear();
        due.clear();
        deferred = 0;
        recentReason = null;
    }
}
