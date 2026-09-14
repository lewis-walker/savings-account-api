package com.lewiswalker.savings.integration.customer;

import java.util.UUID;

/**
 * A customer, as the customer master knows them. Read-only: another system owns it.
 */
public record Customer(UUID id, String fullName, DueDiligence dueDiligence) {

    /**
     * Customer due diligence under the AML/CFT Act 2009.
     *
     * <p>An account cannot be opened for a customer whose due diligence is not
     * complete. This is not a nicety — it is the control the legislation requires, and
     * it is the reason account opening is a conversation with the customer master
     * rather than an insert.
     */
    public enum DueDiligence {
        /** Verified. An account may be opened. */
        COMPLETE,
        /** Started, not finished. No account. */
        PENDING,
        /** Was complete; periodic re-verification is overdue. No new accounts. */
        EXPIRED
    }

    public boolean mayOpenAccounts() {
        return dueDiligence == DueDiligence.COMPLETE;
    }
}
