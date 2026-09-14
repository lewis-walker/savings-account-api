package com.lewiswalker.savings.account.api;

import com.lewiswalker.savings.account.AccountView;
import java.time.Instant;
import java.util.UUID;

/**
 * An account as returned to a caller: a projection, not the entity, so the wire format
 * is not the schema. {@code sequenceNo} is the example - the database needs it, callers
 * have no business with it.
 */
public record AccountResponse(
        UUID id,
        String accountNumber,

        /** As at opening. The customer master stays authoritative for the current name. */
        String customerName,

        String nickname,
        Instant openedAt) {

    public static AccountResponse of(AccountView account) {
        return new AccountResponse(
                account.id(),
                account.accountNumber(),
                account.customerName(),
                account.nickname(),
                account.openedAt());
    }
}
