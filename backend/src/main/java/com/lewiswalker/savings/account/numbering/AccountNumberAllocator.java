package com.lewiswalker.savings.account.numbering;

import java.util.UUID;

/**
 * Allocates an account number.
 *
 * <p>An interface with one implementation. In a real institution the number comes from
 * the core banking platform, out of a range registered with Payments NZ, so the call
 * leaves this process.
 *
 * <p>{@code clientReference} exists for that case: a timed-out allocation may have
 * succeeded, so a retry has to be recognisable as a repeat rather than allocating a
 * second number. The local implementation ignores it.
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
