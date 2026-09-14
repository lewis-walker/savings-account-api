package com.lewiswalker.savings.account.api;

import com.lewiswalker.savings.account.AccountNotFoundException;
import com.lewiswalker.savings.account.AccountService;
import com.lewiswalker.savings.account.AccountView;
import com.lewiswalker.savings.idempotency.IdempotencyExceptions;
import com.lewiswalker.savings.idempotency.IdempotencyRecord;
import com.lewiswalker.savings.idempotency.IdempotencyStore;
import com.lewiswalker.savings.idempotency.RequestFingerprint;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Opens an account at most once per idempotency key.
 *
 * <p>Here rather than in a filter. A filter would have to store and replay the response
 * body, which means buffering every response through a wrapper; this stores the account
 * id and rebuilds the answer, so the record stays an identifier. A filter also sees only
 * raw bytes, so the fingerprint would cover whitespace, and it would need ordering after
 * authentication for the customer scope. Three costs to move one call out of a
 * controller.
 *
 * <p>Concrete rather than a generic template, because there is one idempotent endpoint.
 * A second one is where the mechanism gets generalised, from two examples rather than a
 * guess.
 */
@Component
class IdempotentAccountOpening {

    private final AccountService accounts;
    private final IdempotencyStore idempotency;

    IdempotentAccountOpening(AccountService accounts, IdempotencyStore idempotency) {
        this.accounts = accounts;
        this.idempotency = idempotency;
    }

    AccountView open(UUID customerId, String nickname, String key) {
        if (key == null) {
            return accounts.open(customerId, nickname);
        }

        String fingerprint = RequestFingerprint.of(customerId.toString(), nickname);
        Optional<IdempotencyRecord> existing = idempotency.claim(customerId, key, fingerprint);
        if (existing.isPresent()) {
            return replay(existing.get(), key, fingerprint, customerId);
        }

        AccountView account;
        try {
            account = accounts.open(customerId, nickname);
        } catch (RuntimeException e) {
            // Nothing committed, so a genuine retry should not be locked out.
            idempotency.release(customerId, key);
            throw e;
        }
        idempotency.complete(customerId, key, fingerprint, account.id());
        return account;
    }

    private AccountView replay(IdempotencyRecord record, String key, String fingerprint,
                               UUID customerId) {
        if (!record.matches(fingerprint)) {
            throw new IdempotencyExceptions.KeyReused(key);
        }
        if (record.state() == IdempotencyRecord.State.IN_PROGRESS) {
            throw new IdempotencyExceptions.InProgress(key);
        }
        UUID accountId = UUID.fromString(record.accountId());
        return accounts.findById(accountId)
                .filter(account -> account.customerId().equals(customerId))
                // Unreachable - nothing deletes accounts - but better than replaying a 201.
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }
}
