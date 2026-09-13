package com.lewiswalker.savings.customer;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads the customer master.
 *
 * <p>The second integration boundary, alongside account number allocation. Customer
 * data belongs to another system — the CIF on the core, or a dedicated customer
 * service — and this API owns accounts and nothing else. The token tells us <em>who</em>
 * is asking; this tells us <em>what the bank knows about them</em>.
 *
 * <p><b>Only the write path consults it, and that is deliberate.</b>
 *
 * <p>Opening an account <b>fails closed</b>: there is no acceptable degraded mode for
 * opening an account for a customer whose due diligence cannot be confirmed, so if the
 * master is unreachable, no accounts are opened. That is the correct outcome, not a
 * limitation.
 *
 * <p>Reading an account does not call this at all. The account carries the customer
 * name <em>as at opening</em>, which is the audit fact worth displaying — the name the
 * account was opened under. The master stays authoritative for the customer's current
 * name, and drift between the two is expected rather than a defect. If this API ever
 * surfaced the current name, that read would call here and would fall back to the
 * stored snapshot when the master was unavailable: fail open on a read, fail closed on
 * a write. Today it does not, so it does not.
 *
 * <p>TODO: the real adapter. A short timeout, a circuit breaker so a slow customer
 * service cannot exhaust this one's threads, and a read-through cache — this lookup is
 * remote, happens on every request and changes rarely, which is what a cache is
 * actually for.
 */
public interface CustomerDirectory {

    /**
     * @return the customer, or empty if the master has no such record
     * @throws CustomerDirectoryUnavailableException if the master could not be reached
     */
    Optional<Customer> findById(UUID customerId);
}
