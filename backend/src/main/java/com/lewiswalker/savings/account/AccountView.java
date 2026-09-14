package com.lewiswalker.savings.account;

import java.time.Instant;
import java.util.UUID;

/**
 * An immutable snapshot, and the only form in which an account leaves
 * {@link AccountService}.
 *
 * <p>Carries {@code customerId} and {@code slotNo}, which {@link
 * com.lewiswalker.savings.account.api.AccountResponse} does not.
 */
public record AccountView(
        UUID id,
        String accountNumber,
        UUID customerId,
        String customerName,
        String nickname,
        short slotNo,
        Instant openedAt) {

    public static AccountView of(Account account) {
        return new AccountView(
                account.getId(),
                account.getAccountNumber(),
                account.getCustomerId(),
                account.getCustomerName(),
                account.getNickname(),
                account.getSlotNo(),
                account.getCreatedAt());
    }
}
