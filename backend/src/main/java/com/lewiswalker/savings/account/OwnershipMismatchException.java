package com.lewiswalker.savings.account;

import java.util.UUID;

/**
 * An account was resolved for a customer who does not own it.
 *
 * <p>Not a missing account, and not an authorisation decision: both of those have
 * ordinary answers. This is an invariant failing. The id came from an idempotency entry
 * namespaced by the customer, or from the write that had just created it, so it cannot
 * belong to anyone else unless the namespacing or the write is wrong.
 *
 * <p>Refused rather than answered, because the alternative is returning one customer's
 * account to another.
 */
public class OwnershipMismatchException extends RuntimeException {

    public OwnershipMismatchException(UUID accountId) {
        super("account " + accountId + " was resolved for a customer who does not own it");
    }
}
