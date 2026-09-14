package com.lewiswalker.savings.account;

import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * One attempt at opening an account.
 *
 * <p>Not transactional itself. The caller opens the transaction with a
 * {@code TransactionTemplate}, because the retry in {@link AccountService} needs a new
 * one per attempt: Postgres aborts a transaction when a constraint fires, and every
 * statement after that fails until it rolls back.
 *
 * <p>This used to be {@code @Transactional(REQUIRES_NEW)} and existed as a separate bean
 * so the call crossed a Spring proxy — a call to {@code this.attemptOpen()} would have
 * gone straight to the method and the annotation would have been ignored without
 * warning. The template removes that problem rather than working around it. The class
 * stays because one attempt and the policy that repeats it are different jobs.
 */
@Component
public class AccountWriter {

    static final String CAP_CONSTRAINT = "account_within_cap";
    static final String SEQUENCE_CONSTRAINT = "account_customer_sequence_uq";

    private final AccountRepository repository;
    private final AccountNumberAllocator accountNumbers;
    private final ApplicationEventPublisher events;

    public AccountWriter(AccountRepository repository, AccountNumberAllocator accountNumbers,
                         ApplicationEventPublisher events) {
        this.repository = repository;
        this.accountNumbers = accountNumbers;
        this.events = events;
    }

    /**
     * @throws AccountCapReachedException if the customer is already full
     * @throws SequenceContendedException if another request took the slot; caller may retry
     */
    public Account attemptOpen(UUID customerId, String customerName, String nickname, int cap) {
        short sequenceNo = repository.nextSequenceNo(customerId);

        // Cheap pre-check purely so the common "customer is full" case returns a clean
        // answer without provoking a constraint. It is not the enforcement: a concurrent
        // request can still take the last slot between here and the insert, and the
        // unique index below is what resolves that — both requests compute the same
        // sequence number, one loses, and the loser retries and finds the customer full.
        // The CHECK bounds the series rather than resolving the race.
        if (sequenceNo > cap) {
            throw new AccountCapReachedException(customerId, cap);
        }

        // Minted before allocation so it can serve as the allocator's client reference.
        //
        // TODO: pass the request's Idempotency-Key down instead. It is stable across a
        // client's retries, where this id is fresh on every attempt. It changes nothing
        // for the local allocator, which ignores the reference because a sequence draw
        // inside this transaction leaves no partial state - it matters for a remote
        // allocator, where a lost response is exactly what the reference exists for.
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
            // saveAndFlush, not save: the constraint has to fire here, inside the try,
            // rather than at commit time after this method has already returned.
            Account saved = repository.saveAndFlush(account);

            // Published inside the transaction and delivered only if it commits - see
            // AccountCacheWarmer. Publishing here rather than from the service keeps the
            // event tied to the attempt that actually succeeded, so a contended attempt
            // that is about to be retried never announces an account.
            events.publishEvent(new AccountOpened(AccountView.of(saved)));
            return saved;
        } catch (DataIntegrityViolationException e) {
            String constraint = ConstraintNames.of(e);
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

}
