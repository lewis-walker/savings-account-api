package com.lewiswalker.savings.idempotency;

/** The three ways an idempotent request can fail before it reaches the service. */
public final class IdempotencyExceptions {

    private IdempotencyExceptions() {}

    /**
     * The same key arrived with a different request body.
     *
     * <p>A retry repeats its request; a key reused with different content is a client
     * defect. Answering it with the first request's result would silently discard what
     * the caller actually asked for, so it is refused instead.
     */
    public static class KeyReused extends RuntimeException {
        public KeyReused(String key) {
            super("idempotency key " + key + " was already used for a different request");
        }
    }

    /**
     * The original request with this key is still running.
     *
     * <p>Two requests carrying the same key at the same time is what happens when a client
     * retries before the first attempt has answered. The second is refused rather than
     * queued: holding a request thread waiting for another request to finish is how a
     * retry storm turns into thread exhaustion.
     */
    public static class InProgress extends RuntimeException {
        public InProgress(String key) {
            super("a request with idempotency key " + key + " is already in progress");
        }
    }

    /**
     * The store could not be reached.
     *
     * <p>This <b>fails the request</b>, and that is the decision worth defending. The same
     * Redis backs the read cache, where a failure is swallowed and the request continues —
     * because a cache is a latency optimisation and the correct answer is still available
     * from Postgres.
     *
     * <p>This is not that. The idempotency store is a correctness control: it is the only
     * thing standing between a retried request and a second account, against a cap of five.
     * Degrading it silently would reinstate exactly the defect it exists to prevent, at the
     * moment it is most likely to occur — a caller retries because something was already
     * unwell.
     *
     * <p>One store, two failure policies, because the two uses are not the same kind of
     * thing. If that trade is unacceptable, the answer is to move this to Postgres and put
     * it in the opening transaction, not to make the control best-effort.
     */
    public static class StoreUnavailable extends RuntimeException {
        public StoreUnavailable(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
