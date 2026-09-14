package com.lewiswalker.savings.account.api;

import com.lewiswalker.savings.account.AccountView;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String accountNumber,

        // As at opening. The customer master stays authoritative for the current name.
        String customerName,

        /** What the customer called it, or null. Absent means they did not name it. */
        String nickname,

        /**
         * What to call the account on screen. The nickname where there is one, otherwise a
         * name built from the account's slot, so their five accounts are distinguishable
         * rather than five rows all reading the same.
         *
         * <p>The slot is unique among a customer's open accounts, which is the set in
         * front of them, so no two rows on screen share a name.
         *
         * <p>Derived, never stored: nickname stays the customer's word and this stays
         * ours, which keeps a caller able to tell them apart. Computed here so every
         * client - web, mobile, a statement run - names an account the same way.
         */
        String displayName,

        Instant openedAt) {

    public static AccountResponse of(AccountView account) {
        return new AccountResponse(
                account.id(),
                account.accountNumber(),
                account.customerName(),
                account.nickname(),
                account.nickname() != null
                        ? account.nickname()
                        : "Savings account " + account.slotNo(),
                account.openedAt());
    }
}
