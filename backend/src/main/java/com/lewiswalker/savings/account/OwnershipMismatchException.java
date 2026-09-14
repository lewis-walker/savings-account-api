package com.lewiswalker.savings.account;

import java.util.UUID;

/**
 * This is an invariant failing. The id comes from an idempotency entry
 * namespaced by the customer, or from a write that has just created it, so it's
 * a real 'should-never-happen' error.
 */
public class OwnershipMismatchException extends RuntimeException {

    public OwnershipMismatchException(UUID accountId) {
        super("account " + accountId + " was resolved for a customer who does not own it");
    }
}
