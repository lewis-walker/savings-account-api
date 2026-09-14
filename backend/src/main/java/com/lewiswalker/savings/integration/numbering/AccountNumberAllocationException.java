package com.lewiswalker.savings.integration.numbering;

/**
 * No account number could be allocated. The one failure the allocator port retries;
 * when the retries are exhausted it surfaces as a 503.
 */
public class AccountNumberAllocationException extends RuntimeException {

    public AccountNumberAllocationException(String message, Throwable cause) {
        super(message, cause);
    }

    public AccountNumberAllocationException(String message) {
        super(message);
    }
}
