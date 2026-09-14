package com.lewiswalker.savings.idempotency;

/**
 * What is remembered about a request already seen: whether it is still running, a digest
 * of its body, and the account it created.
 */
public record IdempotencyRecord(State state, String fingerprint, String accountId) {

    public enum State { IN_PROGRESS, COMPLETED }

    public static IdempotencyRecord inProgress(String fingerprint) {
        return new IdempotencyRecord(State.IN_PROGRESS, fingerprint, null);
    }

    public IdempotencyRecord completedWith(String accountId) {
        return new IdempotencyRecord(State.COMPLETED, fingerprint, accountId);
    }

    public boolean matches(String otherFingerprint) {
        return fingerprint.equals(otherFingerprint);
    }
}
