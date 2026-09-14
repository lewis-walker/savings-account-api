package com.lewiswalker.savings.account;

import java.util.UUID;

/**
 * No such account — or none belonging to the caller.
 *
 * <p>The two cases are deliberately the same Exception and answer 404 rather than 403.
 * A 403 would confirm the account exists, which lets someone hunt for existing accounts.
 */
public class AccountNotFoundException extends RuntimeException {
    public AccountNotFoundException(UUID id) {
        super("no account " + id + " for this customer");
    }
}
