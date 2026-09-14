package com.lewiswalker.savings.integration.customer;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads the customer master - an integration point
 *
 * <p>TODO: the real adapter needs a short timeout and a circuit breaker.
 */
public interface CustomerService {

    /**
     * @return the customer, or empty if the master has no such record
     * @throws CustomerDirectoryUnavailableException if the master could not be reached
     */
    Optional<Customer> findById(UUID customerId);
}
