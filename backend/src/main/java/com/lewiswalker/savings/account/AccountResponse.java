package com.lewiswalker.savings.account;

import java.time.Instant;
import java.util.UUID;

/**
 * An account, as returned to a caller.
 *
 * <p>A deliberate projection rather than the entity. Serialising an entity directly
 * couples the wire format to the schema, so a column rename becomes a breaking API
 * change and a column added for internal use is published to the world by accident.
 * {@code sequenceNo} is a case in point: the database needs it to enforce the account
 * cap, and no caller has any business knowing it.
 */
public record AccountResponse(
        UUID id,
        String accountNumber,

        /**
         * The name the account was opened under — a snapshot of the verified record at
         * that moment, which is the audit fact. The customer master stays authoritative
         * for the customer's current name, and drift between them is expected.
         */
        String customerName,

        String nickname,
        Instant openedAt) {

    public static AccountResponse of(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getCustomerName(),
                account.getNickname(),
                account.getCreatedAt());
    }
}
