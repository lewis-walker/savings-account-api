package com.lewiswalker.savings.account;

import com.lewiswalker.savings.nickname.OffensiveNicknameChecker;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    /** "A customer cannot create more than 5 accounts." */
    public static final int ACCOUNTS_PER_CUSTOMER = 5;

    /**
     * Contention retries. Bounded at the cap: a customer has at most five slots, so
     * after five losses there is genuinely nothing left to win, and an unbounded loop
     * against a full customer would just spin.
     */
    private static final int MAX_ATTEMPTS = ACCOUNTS_PER_CUSTOMER;

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountWriter writer;
    private final AccountRepository repository;
    private final OffensiveNicknameChecker nicknameChecker;

    public AccountService(AccountWriter writer, AccountRepository repository,
                          OffensiveNicknameChecker nicknameChecker) {
        this.writer = writer;
        this.repository = repository;
        this.nicknameChecker = nicknameChecker;
    }

    /**
     * Opens an account for the given customer.
     *
     * <p>Note there is no transaction on this method. The retry below only works
     * because each attempt gets its own; see {@link AccountWriter}.
     *
     * @param customerId from the authenticated principal, never from the request body
     */
    public Account open(UUID customerId, String customerName, String nickname) {
        // Before touching the database: a rejected nickname should cost nothing.
        nicknameChecker.check(nickname);

        SequenceContendedException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return writer.attemptOpen(customerId, customerName, nickname, ACCOUNTS_PER_CUSTOMER);
            } catch (SequenceContendedException e) {
                last = e;
                log.debug("sequence contended for customer {}, attempt {} of {}",
                        customerId, attempt, MAX_ATTEMPTS);
            }
        }
        // Every slot we tried was taken by someone else, and we never saw the cap
        // constraint. Concurrency this heavy on one customer is worth knowing about.
        log.warn("gave up opening an account for customer {} after {} contended attempts",
                customerId, MAX_ATTEMPTS);
        throw new AccountCapReachedException(customerId, ACCOUNTS_PER_CUSTOMER);
    }

    @Transactional(readOnly = true)
    public Optional<Account> findById(UUID id) {
        return repository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<Account> findForCustomer(UUID customerId) {
        return repository.findByCustomerIdOrderBySequenceNo(customerId);
    }
}
