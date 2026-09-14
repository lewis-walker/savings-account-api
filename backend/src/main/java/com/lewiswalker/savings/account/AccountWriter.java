package com.lewiswalker.savings.account;

import com.lewiswalker.savings.integration.numbering.AccountNumberAllocator;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * One attempt at opening an account.
 *
 * <p>Not transactional: {@link AccountService} opens one transaction per attempt, because Postgres
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
     * @param clientReference identifies this request to the allocator, unchanged across
     *                        the caller's retries
     * @throws AccountCapReachedException if the customer is already full
     * @throws SequenceContendedException if another request took the slot; caller may retry
     */
    public Account attemptOpen(UUID customerId, String customerName, String nickname, int cap,
                               String clientReference) {
        short sequenceNo = repository.nextSequenceNo(customerId);

        // A quick answer for the common case, The unique index prevents race conditions.
        // This prevents the expensive call to the external accountNumbers.allocate().
        if (sequenceNo > cap) {
            throw new AccountCapReachedException(customerId, cap);
        }

        UUID id = UUID.randomUUID();

        Account account = new Account(
                id,
                accountNumbers.allocate(customerId, clientReference),
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
