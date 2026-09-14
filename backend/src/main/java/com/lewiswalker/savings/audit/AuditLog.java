package com.lewiswalker.savings.audit;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records openings and refusals for compliance, on its own named logger so it can be
 * routed and retained separately and cannot be turned off by lowering application
 * logging. Identifiers and outcomes only.
 *
 * <p>TODO: these belong on a durable stream; a log line can be lost when a container is
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
     * An account resolved for a customer who does not own it.
     *
     * <p>At error, unlike the rest of this class: the others record things that happen,
     * this records something that cannot. Both ids, because without the actual owner the
     * line says an invariant broke and gives nobody a way to find out how.
     */
    public void ownershipMismatch(UUID expectedCustomerId, UUID actualCustomerId, UUID accountId) {
        audit.error("event=account.ownership-mismatch expected-customer={} actual-customer={} account={}",
                expectedCustomerId, actualCustomerId, accountId);
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
