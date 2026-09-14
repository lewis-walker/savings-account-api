package com.lewiswalker.savings.account;

import java.util.UUID;

public class AccountCapReachedException extends RuntimeException {

    private final UUID customerId;
    private final int cap;

    public AccountCapReachedException(UUID customerId, int cap) {
        super("customer %s already holds the maximum of %d accounts".formatted(customerId, cap));
        this.customerId = customerId;
        this.cap = cap;
    }

    public UUID getCustomerId() { return customerId; }
    public int getCap() { return cap; }
}
