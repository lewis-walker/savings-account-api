package com.lewiswalker.savings.account;

import java.time.Instant;
import java.util.UUID;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One attempt at opening an account, in its own transaction.
 *
 * <p>Separate from {@link AccountService} for a reason that is easy to get wrong.
 * Postgres aborts the whole transaction when a constraint fires — every subsequent
 * statement on that connection fails with "current transaction is aborted" until it
 * rolls back. So a retry cannot happen inside the transaction that just failed; it
 * needs a fresh one. That means the retry loop has to sit outside the transactional
 * boundary, and {@code REQUIRES_NEW} has to be crossed through a real proxy — calling
 * a {@code @Transactional} method on {@code this} goes straight to the method and
 * silently does nothing. Two beans, so the proxy is unavoidable.
 */
@Component
public class AccountWriter {

    static final String CAP_CONSTRAINT = "account_within_cap";
    static final String SEQUENCE_CONSTRAINT = "account_customer_sequence_uq";

    private final AccountRepository repository;
    private final AccountNumberGenerator accountNumbers;

    public AccountWriter(AccountRepository repository, AccountNumberGenerator accountNumbers) {
        this.repository = repository;
        this.accountNumbers = accountNumbers;
    }

    /**
     * @throws AccountCapReachedException if the customer is already full
     * @throws SequenceContendedException if another request took the slot; caller may retry
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Account attemptOpen(UUID customerId, String customerName, String nickname, int cap) {
        short sequenceNo = repository.nextSequenceNo(customerId);

        // Cheap pre-check purely so the common "customer is full" case returns a clean
        // answer without provoking a constraint. It is not the enforcement — a
        // concurrent request can still take the last slot between here and the insert,
        // which is what the CHECK below is for.
        if (sequenceNo > cap) {
            throw new AccountCapReachedException(customerId, cap);
        }

        Account account = new Account(
                UUID.randomUUID(),
                accountNumbers.generate(repository::nextAccountNumberSeed),
                customerId,
                customerName,
                nickname,
                sequenceNo,
                Instant.now());

        try {
            // saveAndFlush, not save: the constraint has to fire here, inside the try,
            // rather than at commit time after this method has already returned.
            return repository.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            String constraint = constraintNameOf(e);
            if (CAP_CONSTRAINT.equals(constraint)) {
                // Lost the race for the last slot. Permanent — the customer is full.
                throw new AccountCapReachedException(customerId, cap);
            }
            if (SEQUENCE_CONSTRAINT.equals(constraint)) {
                // Someone else took this sequence number. Transient; worth another go.
                throw new SequenceContendedException(customerId, sequenceNo, e);
            }
            throw e;
        }
    }

    /**
     * Which constraint actually fired.
     *
     * <p>Treating every {@code DataIntegrityViolationException} the same is how a
     * permanent failure ends up being retried until the retry budget runs out, and the
     * caller gets a timeout instead of "you already have five accounts".
     */
    static String constraintNameOf(DataIntegrityViolationException e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }
        return null;
    }
}
