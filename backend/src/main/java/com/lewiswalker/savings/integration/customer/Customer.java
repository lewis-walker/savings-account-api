package com.lewiswalker.savings.integration.customer;

import java.util.UUID;

public record Customer(UUID id, String fullName, DueDiligence dueDiligence) {

    /**
     * Customer due diligence under the AML/CFT Act 2009.
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
