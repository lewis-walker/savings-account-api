package com.lewiswalker.savings.platform.idempotency;

import java.util.UUID;

/**
 * What is remembered about a request already seen: whether it is still running, a digest
 * of what it asked for, and the id of whatever it created.
 *
 * <p>This is a stored wire format with a retention-length lifetime, so renaming a field
 * is a data change: entries written by the previous version are still in Redis. Bounded
 * here by the retention, and by these being safe to lose - an unreadable entry fails
 * closed. A rolling deployment that could not afford that would read both names for one
 * release.
 */
record IdempotencyRecord(State state, String fingerprint, UUID resultId) {

    enum State { IN_PROGRESS, COMPLETED }

    static IdempotencyRecord inProgress(String fingerprint) {
        return new IdempotencyRecord(State.IN_PROGRESS, fingerprint, null);
    }

    IdempotencyRecord completedWith(UUID resultId) {
        return new IdempotencyRecord(State.COMPLETED, fingerprint, resultId);
    }

    boolean matches(String otherFingerprint) {
        return fingerprint.equals(otherFingerprint);
    }
}
