package com.lewiswalker.savings.account;

import java.util.UUID;

/**
 * Allocates an account number.
 *
 * <p>This is an interface with a single implementation, which is normally a smell.
 * It is here because in a real institution this call does not stay in this process.
 * Account numbers are drawn from a bank and branch range registered with Payments NZ
 * and allocated by the core banking platform — on most incumbent banks a mainframe,
 * reached over MQ or a REST facade in front of it. An identifier drawn from a range
 * the institution had to register is not something an edge service invents.
 *
 * <p>The seam matters because the failure modes on the other side of it are different
 * in kind, and they change the caller rather than the implementation:
 *
 * <ul>
 *   <li><b>Partial failure.</b> The call times out. Whether a number was allocated is
 *       unknown. Retrying blindly opens a second account — the exact outcome the
 *       five-account cap exists to prevent, arriving from a direction no database
 *       constraint can see. Hence {@code clientReference}: the core replays the
 *       original allocation rather than making a new one.
 *   <li><b>Latency.</b> Tens to hundreds of milliseconds, and where allocation runs
 *       through a batch window it is not synchronous at all. A real API may well
 *       answer 202 with a pending account rather than 201 with a finished one.
 *   <li><b>Source of truth.</b> Once the core allocates, the core holds the record and
 *       the local table is a projection of it, with everything that implies about
 *       drift and reconciliation.
 * </ul>
 *
 * <p>TODO: the core adapter. It needs a timeout shorter than the caller's patience, a
 * circuit breaker, and a reconciliation job for allocations whose response was lost.
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
