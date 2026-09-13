package com.lewiswalker.savings.account;

import com.lewiswalker.savings.cache.CacheConfig;
import com.lewiswalker.savings.customer.Customer;
import com.lewiswalker.savings.customer.CustomerDirectory;
import com.lewiswalker.savings.customer.CustomerNotVerifiedException;
import com.lewiswalker.savings.customer.UnknownCustomerException;
import com.lewiswalker.savings.nickname.OffensiveNicknameChecker;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
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
    private final CustomerDirectory customers;

    public AccountService(AccountWriter writer, AccountRepository repository,
                          OffensiveNicknameChecker nicknameChecker,
                          CustomerDirectory customers) {
        this.writer = writer;
        this.repository = repository;
        this.nicknameChecker = nicknameChecker;
        this.customers = customers;
    }

    /**
     * Opens an account for the given customer.
     *
     * <p>Note there is no transaction on this method. The retry below only works
     * because each attempt gets its own; see {@link AccountWriter}.
     *
     * @param customerId from the authenticated principal, never from the request body
     * @throws UnknownCustomerException      the master has no such customer
     * @throws CustomerNotVerifiedException  due diligence is not complete
     */
    public AccountView open(UUID customerId, String nickname) {
        // Cheap and local first: a rejected nickname should not cost a remote call.
        // Safe to order it this way because the customer is the caller themselves, so
        // an early answer tells them nothing they do not already know about themselves.
        nicknameChecker.check(nickname);

        // The name on the account comes from the verified customer record, never from
        // the request. Under AML/CFT an account is opened for a customer whose identity
        // has already been established; a name asserted by the caller would be an
        // unverified claim written into a banking record.
        Customer customer = customers.findById(customerId)
                .orElseThrow(() -> new UnknownCustomerException(customerId));
        if (!customer.mayOpenAccounts()) {
            throw new CustomerNotVerifiedException(customer.dueDiligence());
        }

        SequenceContendedException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return AccountView.of(writer.attemptOpen(customerId, customer.fullName(),
                        nickname, ACCOUNTS_PER_CUSTOMER));
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

    /**
     * One account by id.
     *
     * <p>Cached because the brief asks for it. It is worth being honest that it buys
     * nothing: this is a primary-key lookup on a narrow table, which Postgres answers
     * from its own buffer pool in microseconds, and Redis adds a network hop to it.
     * See {@link CacheConfig} for the full argument, and {@link AccountCacheWarmer} for
     * the part that makes it <em>correct</em> rather than merely present.
     *
     * <p>Note the absence of an ownership check here. The cache is keyed by account id
     * alone, so one entry serves whoever asks; the caller's right to see it is decided
     * afterwards, against the returned value. Keying the cache per caller would be a
     * cache with a hit rate of nearly zero.
     */
    @Cacheable(value = CacheConfig.ACCOUNTS, key = "#id",
            // Evaluated on every call, so flipping the switch takes effect on the next
            // request rather than the next deployment. That is the entire point: a
            // configuration property would need a restart, which is exactly what nobody
            // wants during the incident that made them want the switch.
            condition = "@featureFlags.redisCacheEnabled()")
    @Transactional(readOnly = true)
    public Optional<AccountView> findById(UUID id) {
        return repository.findById(id).map(AccountView::of);
    }

    /**
     * Not cached, deliberately. A per-customer list is invalidated by any account
     * opening, and the customers who read it most are the ones whose lists change most.
     * A cache whose entries are evicted about as often as they are read is pure overhead
     * with an invalidation bug waiting in it.
     */
    @Transactional(readOnly = true)
    public List<AccountView> findForCustomer(UUID customerId) {
        return repository.findByCustomerIdOrderBySequenceNo(customerId).stream()
                .map(AccountView::of)
                .toList();
    }
}
