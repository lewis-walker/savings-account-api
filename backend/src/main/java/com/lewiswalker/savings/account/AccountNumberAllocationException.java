package com.lewiswalker.savings.account;

/**
 * No account number could be allocated.
 *
 * <p>Distinct from a validation failure: the request was fine and the allocator could
 * not answer. Against a core banking platform this is the timeout case, where whether
 * an allocation happened is genuinely unknown, so it maps to a 503 and never to a
 * silent retry.
 */
public class AccountNumberAllocationException extends RuntimeException {

    public AccountNumberAllocationException(String message, Throwable cause) {
        super(message, cause);
    }

    public AccountNumberAllocationException(String message) {
        super(message);
    }
}
