package com.lewiswalker.savings.audit;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records what happened to accounts, for the people whose job is to ask later.
 *
 * <p>A separate named logger, so audit events can be routed and retained on their own —
 * five years for AML/CFT records, seven for tax — and so that turning application logging
 * down during an incident cannot switch the audit trail off.
 *
 * <p>Identifiers and outcomes only: no name, nickname or account number. Refusal reasons
 * are recorded even though the API withholds them from the caller, because compliance
 * needs them and a customer should hear it from a person.
 *
 * <p>Fields are {@code key=value} and reasons are stable codes, so anything counting on
 * them survives a reworded message.
 *
 * <p>TODO: these belong on a durable stream. A log line can be lost when a container is
 * killed.
 */
@Component
public class AuditLog {

    private static final Logger audit = LoggerFactory.getLogger("audit");

    public void accountOpened(UUID customerId, UUID accountId, short sequenceNo) {
        audit.info("event=account.opened customer={} account={} holdings={}",
                customerId, accountId, sequenceNo);
    }

    /**
     * An opening that was refused, and why.
     *
     * @param reason a stable code, not a sentence. Messages get reworded; anything
     *               counting or alerting on them then quietly stops working.
     */
    public void accountRefused(UUID customerId, String reason) {
        audit.info("event=account.refused customer={} reason={}", customerId, reason);
    }
}
