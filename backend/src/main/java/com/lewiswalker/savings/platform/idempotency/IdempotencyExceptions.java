package com.lewiswalker.savings.platform.idempotency;

/** The three ways an idempotent request can fail before it reaches the service. */
public final class IdempotencyExceptions {

    private IdempotencyExceptions() {}

    /**
     * The same key arrived with a different request body. Refused rather than answered
     * with the first result, which would discard what this caller actually asked for.
     */
    public static class KeyReused extends RuntimeException {
        public KeyReused(String key) {
            super("idempotency key " + key + " was already used for a different request");
        }
    }

    /**
     * The original request with this key is still running. Refused rather than queued:
     * holding a request thread waiting for another is how a retry storm exhausts them.
     */
    public static class InProgress extends RuntimeException {
        public InProgress(String key) {
            super("a request with idempotency key " + key + " is already in progress");
        }
    }

    /**
     * A stored entry cannot be read back as a request that completed.
     */
    public static class CorruptRecord extends RuntimeException {
        public CorruptRecord(String message) {
            super(message);
        }
    }

    /**
     * The store could not be reached, and the request fails.
     */
    public static class StoreUnavailable extends RuntimeException {
        public StoreUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
