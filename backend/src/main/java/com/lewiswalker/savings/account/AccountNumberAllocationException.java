package com.lewiswalker.savings.account;

/**
 * No account number could be allocated.
 *
 * <p>Distinct from a validation failure: the request was fine and the allocator could
 * not answer. Against a core banking platform this is the timeout case, where whether an
 * allocation happened is genuinely unknown.
 *
 * <p>It is the one failure the allocator port retries — see the {@code @Retryable} on
 * {@link LocalSequenceAccountNumberAllocator}. That is safe for the local adapter because
 * a sequence draw sits inside the caller's transaction and leaves no partial state; it is
 * <em>not</em> safe for a remote one, which is what the port's client reference is for.
 * When the retries are exhausted it surfaces as a 503.
 */
public class AccountNumberAllocationException extends RuntimeException {

    public AccountNumberAllocationException(String message, Throwable cause) {
        super(message, cause);
    }

    public AccountNumberAllocationException(String message) {
        super(message);
    }
}
