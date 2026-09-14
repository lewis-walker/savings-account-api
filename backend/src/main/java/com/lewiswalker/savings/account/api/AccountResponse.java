package com.lewiswalker.savings.account.api;

import com.lewiswalker.savings.account.AccountView;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String accountNumber,

        // As at opening. The customer master stays authoritative for the current name.
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
