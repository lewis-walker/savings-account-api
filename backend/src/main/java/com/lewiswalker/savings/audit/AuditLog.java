package com.lewiswalker.savings.audit;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Records what happened to accounts, for people whose job is to ask later.
 *
 * <p>A separate, named logger rather than {@code log.warn} inside a service, and the
 * separation is the point. Audit events have a different audience (compliance, fraud,
 * customer operations), a different retention period — five years for AML/CFT records,
 * seven for tax — and a different sink. Routing them to their own destination is a
 * logback appender away only if they are distinguishable in the first place, which they
 * are not once they are mixed into application logging at whatever level seemed right
 * that day.
 *
 * <p>It also means an engineer turning application logging down during an incident
 * cannot accidentally switch off the audit trail. That is a real failure mode and a bad
 * one to explain afterwards.
 *
 * <h2>What goes in, and what does not</h2>
 *
 * <p>Identifiers, outcomes and reasons. No customer name, no nickname, no account
 * number — an audit record needs to say <em>who did what and what the answer was</em>,
 * and an opaque id says who perfectly well while meaning nothing to anyone reading the
 * log without database access.
 *
 * <p>The refusal reason <em>is</em> recorded, even though the API deliberately withholds
 * it from the caller. Different audiences: telling a customer which stage of
 * verification they are stuck at is the bank's assessment of them and belongs in a
 * conversation with a person; not telling compliance why an opening was refused defeats
 * the purpose of having a record at all.
 *
 * <p>Fields are written as {@code key=value} so they parse without a regular expression
 * per message. The correlation id is not repeated here — it is already on every line
 * from the MDC.
 *
 * <p>TODO: in a real system these are domain events on a durable stream, not log lines.
 * A log line can be lost when a container is killed, and "we think it was probably
 * recorded" is not a position to take in front of a regulator. The shape here is right;
 * the transport is not.
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
