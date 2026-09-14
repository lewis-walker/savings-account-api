package com.lewiswalker.savings.customer;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads the customer master - the second integration point, after account-number
 * allocation.
 *
 * <p>Only the write path consults it, and it fails closed: no account is opened for a
 * customer whose due diligence cannot be confirmed. Reads use the name stored on the
 * account, which is the name it was opened under.
 *
 * <p>TODO: the real adapter needs a short timeout and a circuit breaker.
 */
public interface CustomerDirectory {

    /**
     * @return the customer, or empty if the master has no such record
     * @throws CustomerDirectoryUnavailableException if the master could not be reached
     */
    Optional<Customer> findById(UUID customerId);
}
