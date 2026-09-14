package com.lewiswalker.savings.account;

import java.util.UUID;

/**
 * Allocates an account number.
 *
 * <p>An interface with one implementation, which is normally a smell. It is here because
 * in a real institution this call leaves the process: numbers come from a bank and branch
 * range registered with Payments NZ, allocated by the core banking platform. The
 * registered prefix in the format is itself the evidence that an edge service cannot
 * invent one.
 *
 * <p>The seam exists because the failure modes across it change the caller rather than the
 * implementation. A timed-out allocation leaves it unknown whether a number was issued, so
 * a blind retry opens a second account — hence {@code clientReference}, which lets the far
 * side replay the original. Allocation may also not be synchronous, in which case this
 * endpoint answers 202 with a pending account. And once the core allocates, it holds the
 * record and the local table is a projection of it.
 *
 * <p>TODO: the core adapter needs a timeout shorter than the caller's patience, a circuit
 * breaker, and a reconciliation job for allocations whose response was lost.
 */
public interface AccountNumberAllocator {

    /**
     * @param customerId     the verified customer the account belongs to
     * @param clientReference stable across retries of the same logical request, so an
     *                        allocation is never duplicated by a retry
     * @throws AccountNumberAllocationException if no number could be allocated
     */
    String allocate(UUID customerId, String clientReference);
}
