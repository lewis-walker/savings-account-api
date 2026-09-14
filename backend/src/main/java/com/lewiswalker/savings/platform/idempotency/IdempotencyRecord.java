package com.lewiswalker.savings.platform.idempotency;

import java.util.UUID;

/**
 * What is remembered about a request already seen: whether it is still running, a digest
 * of what it asked for, and the id of whatever it created.
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
