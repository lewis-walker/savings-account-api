package com.lewiswalker.savings.account;

import java.time.Instant;
import java.util.UUID;

/**
 * An immutable snapshot, and the only form in which an account leaves
 * {@link AccountService}. The entity is mutable, session-bound and shaped like the
 * schema, so it is not what gets cached or returned.
 *
 * <p>Carries {@code customerId} and {@code sequenceNo}, which {@link
 * com.lewiswalker.savings.account.api.AccountResponse} does not.
 */
public record AccountView(
        UUID id,
        String accountNumber,
        UUID customerId,
        String customerName,
        String nickname,
        short sequenceNo,
        Instant openedAt) {

    public static AccountView of(Account account) {
        return new AccountView(
                account.getId(),
                account.getAccountNumber(),
                account.getCustomerId(),
                account.getCustomerName(),
                account.getNickname(),
                account.getSequenceNo(),
                account.getCreatedAt());
    }
}
