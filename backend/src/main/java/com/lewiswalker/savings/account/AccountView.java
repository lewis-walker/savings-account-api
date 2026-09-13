package com.lewiswalker.savings.account;

import java.time.Instant;
import java.util.UUID;

/**
 * An immutable snapshot of an account, and the only form in which one leaves
 * {@link AccountService}.
 *
 * <p>The entity stops at the service boundary deliberately. {@link Account} is a
 * managed JPA object: mutable, attached to a persistence context, with a shape that is
 * the database schema. Handing that to a cache is a familiar mistake — you end up
 * serialising something whose identity belongs to a session that has since closed, and
 * a column rename silently invalidates every entry written by the previous deployment.
 *
 * <p>A record has none of those properties, which is exactly why it is the thing that
 * gets cached.
 *
 * <p>Note this is not the API response. It carries {@code customerId} so the ownership
 * check can be made against a cached value without going back to the database, and
 * {@code sequenceNo} and {@code version}, neither of which any caller has business
 * seeing. {@link AccountResponse} is the wire format; this is the internal one.
 */
public record AccountView(
        UUID id,
        String accountNumber,
        UUID customerId,
        String customerName,
        String nickname,
        short sequenceNo,
        long version,
        Instant openedAt) {

    public static AccountView of(Account account) {
        return new AccountView(
                account.getId(),
                account.getAccountNumber(),
                account.getCustomerId(),
                account.getCustomerName(),
                account.getNickname(),
                account.getSequenceNo(),
                account.getVersion(),
                account.getCreatedAt());
    }
}
