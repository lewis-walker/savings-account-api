package com.lewiswalker.savings.account;

import java.util.UUID;

/**
 * No such account — or none belonging to the caller.
 *
 * <p>The two cases are deliberately the same exception, and answer 404 rather than 403.
 * A 403 would confirm the account exists, which lets someone walk the estate by
 * watching which identifiers answer which way. Whether an account exists is itself
 * information a bank does not give away.
 */
public class AccountNotFoundException extends RuntimeException {

    public AccountNotFoundException(UUID id) {
        super("no account " + id + " for this customer");
    }
}
