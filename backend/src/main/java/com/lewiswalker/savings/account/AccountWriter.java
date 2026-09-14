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

    static final String SLOT_CONSTRAINT = "account_open_slot_uq";

    private final AccountRepository repository;
    private final AccountNumberAllocator accountNumbers;

    public AccountWriter(AccountRepository repository, AccountNumberAllocator accountNumbers) {
        this.repository = repository;
        this.accountNumbers = accountNumbers;
    }

    /**
     * @param clientReference identifies this request to the allocator, unchanged across
     *                        the caller's retries
     * @throws AccountCapReachedException if the customer holds an open account in every slot
     * @throws SlotContendedException     if another request took the slot; caller may retry
     */
    public Account attemptOpen(UUID customerId, String customerName, String nickname, int cap,
                               String clientReference) {
        // Answers "full" before the expensive call to the external accountNumbers.allocate().
        // Only a proposal: the unique index decides who actually gets the slot.
        short slotNo = repository.nextFreeSlot(customerId, cap)
                .orElseThrow(() -> new AccountCapReachedException(customerId, cap));

        UUID id = UUID.randomUUID();

        Account account = new Account(
                id,
                accountNumbers.allocate(customerId, clientReference),
                customerId,
                customerName,
                nickname,
                slotNo,
                Instant.now());

        try {
            // saveAndFlush, so the constraint fires inside the try rather than at commit.
            return repository.saveAndFlush(account);
        } catch (DataIntegrityViolationException e) {
            if (SLOT_CONSTRAINT.equals(ConstraintNames.of(e))) {
                throw new SlotContendedException(customerId, slotNo, e);  // retryable
            }
            // account_slot_in_range has no branch: the finder only ever proposes 1..cap, so
            // the only way to trip it is a cap raised past the migration's. That is a
            // deployment fault, and it surfaces as one rather than as a cap message quoting
            // a limit the database does not honour.
            throw e;
        }
    }
}
