package com.lewiswalker.savings.idempotency;

/**
 * What is remembered about a request that has been seen before.
 *
 * @param state       IN_PROGRESS while the original request is still running, COMPLETED once
 *                    it has finished and its response is worth replaying
 * @param fingerprint a digest of the request body. A key reused with different content is a
 *                    client defect, not a retry, and must not be answered with the first
 *                    request's result
 * @param accountId   the account the original request created; null while in progress
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
