package com.lewiswalker.savings.integration.customer;

import java.util.UUID;

public class UnknownCustomerException extends RuntimeException {
    public UnknownCustomerException(UUID customerId) {
        super("no customer record for " + customerId);
    }
}
