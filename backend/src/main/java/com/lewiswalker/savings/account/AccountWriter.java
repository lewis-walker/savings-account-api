package com.lewiswalker.savings.account;

import com.lewiswalker.savings.integration.numbering.AccountNumberAllocator;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * One attempt at opening an account.
 *
 * <p>Not transactional: {@link AccountService} opens one per attempt, because Postgres
 * aborts a transaction when a constraint fires and the retry needs a fresh one.
 */
@Component
public class AccountWriter {

    static final String CAP_CONSTRAINT = "account_within_cap";
    static final String SEQUENCE_CONSTRAINT = "account_customer_sequence_uq";

    private final AccountRepository repository;
    private final AccountNumberAllocator accountNumbers;

    public AccountWriter(AccountRepository repository, AccountNumberAllocator accountNumbers) {
        this.repository = repository;
        this.accountNumbers = accountNumbers;
    }

    /**
     * @throws AccountCapReachedException if the customer is already full
     * @throws SequenceContendedException if another request took the slot; caller may retry
     */
    public Account attemptOpen(UUID customerId, String customerName, String nickname, int cap) {
        short sequenceNo = repository.nextSequenceNo(customerId);

        // A clean answer for the common case, not the enforcement: the unique index
        // resolves the race, the CHECK bounds the series.
        if (sequenceNo > cap) {
            throw new AccountCapReachedException(customerId, cap);
        }

        // TODO: use the request's Idempotency-Key as the client reference instead. It is
        // stable across retries, where this id is not. Matters only for a remote allocator.
        UUID id = UUID.randomUUID();

        Account account = new Account(
                id,
                accountNumbers.allocate(customerId, id.toString()),
                customerId,
                customerName,
                nickname,
                sequenceNo,
                Instant.now());

        try {
            // saveAndFlush, so the constraint fires inside the try rather than at commit.
            return repository.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            String constraint = ConstraintNames.of(e);
            if (CAP_CONSTRAINT.equals(constraint)) {
                throw new AccountCapReachedException(customerId, cap);   // permanent
            }
            if (SEQUENCE_CONSTRAINT.equals(constraint)) {
                throw new SequenceContendedException(customerId, sequenceNo, e);  // retryable
            }
            throw e;
        }
    }
}
