package com.lewiswalker.savings.customer;

import java.util.UUID;

/**
 * The token's subject is not a customer the master has heard of.
 *
 * <p>In practice this means a validly signed token for someone who has since been
 * removed, or a token from an issuer whose subjects do not map to customers here.
 * Either way it is a configuration or lifecycle problem, not a user error.
 */
public class UnknownCustomerException extends RuntimeException {

    public UnknownCustomerException(UUID customerId) {
        super("no customer record for " + customerId);
    }
}
