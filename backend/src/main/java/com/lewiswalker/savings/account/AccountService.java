package com.lewiswalker.savings.account;

import com.lewiswalker.savings.platform.audit.AuditLog;
import com.lewiswalker.savings.platform.cache.CacheConfig;
import com.lewiswalker.savings.integration.customer.Customer;
import com.lewiswalker.savings.integration.customer.CustomerService;
import com.lewiswalker.savings.integration.customer.CustomerNotVerifiedException;
import com.lewiswalker.savings.integration.customer.UnknownCustomerException;
import com.lewiswalker.savings.integration.nickname.OffensiveNicknameChecker;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AccountService {

    public static final int ACCOUNTS_PER_CUSTOMER = 5;

    // Failing this many times means every slot is gone.
    private static final int MAX_SEQUENCE_NUMBER_FAILS = ACCOUNTS_PER_CUSTOMER;

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountWriter writer;
    private final AccountRepository repository;
    private final OffensiveNicknameChecker nicknameChecker;
    private final CustomerService customers;
    private final AuditLog auditLog;
    private final AccountCacheWarmer cacheWarmer;

    /** One transaction per attempt; see DECISIONS.md for why it is not @Transactional. */
    private final TransactionTemplate transaction;

    public AccountService(AccountWriter writer, AccountRepository repository,
                          OffensiveNicknameChecker nicknameChecker,
                          CustomerService customers, AuditLog auditLog,
                          AccountCacheWarmer cacheWarmer,
                          PlatformTransactionManager transactionManager) {
        this.writer = writer;
        this.repository = repository;
        this.nicknameChecker = nicknameChecker;
        this.customers = customers;
        this.auditLog = auditLog;
        this.cacheWarmer = cacheWarmer;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * @param customerId from the authenticated principal
     * @throws UnknownCustomerException     the master has no such customer
     * @throws CustomerNotVerifiedException due diligence is not complete
     */
    public AccountView open(UUID customerId, String nickname) {
        return open(customerId, nickname, null);
    }

    /**
     * @param clientReference the caller's name for this request, passed to the allocator so
     *                        a retry cannot draw a second account number. The request's
     *                        {@code Idempotency-Key} where there is one; null mints a
     *                        reference good for this call, which still covers the retries
     *                        below but not a retry of the request itself.
     */
    public AccountView open(UUID customerId, String nickname, String clientReference) {
        // Local and cheap before the remote lookup.
        nicknameChecker.check(nickname);

        // The name on the account comes from the verified record, never from the request.
        Customer customer = customers.findById(customerId).orElseThrow(() -> {
            auditLog.accountRefused(customerId, "unknown-customer");
            log.warn("token subject {} does not resolve to a customer", customerId);
            return new UnknownCustomerException(customerId);
        });

        if (!customer.mayOpenAccounts()) {
            auditLog.accountRefused(customerId,
                    "due-diligence-" + customer.dueDiligence().name().toLowerCase());
            throw new CustomerNotVerifiedException(customer.dueDiligence());
        }

        // Fixed before the loop: the point of it is that every attempt asks for the
        // same allocation.
        String reference = clientReference != null ? clientReference : UUID.randomUUID().toString();

        for (int attempt = 1; attempt <= MAX_SEQUENCE_NUMBER_FAILS; attempt++) {
            try {
                AccountView opened = transaction.execute(status ->
                        AccountView.of(writer.attemptOpen(customerId, customer.fullName(),
                                nickname, ACCOUNTS_PER_CUSTOMER, reference)));

                // execute() has returned, so the account is committed.
                auditLog.accountOpened(customerId, opened.id(), opened.sequenceNo());
                cacheWarmer.warm(opened);
                return opened;
            } catch (AccountCapReachedException e) {
                throw accountCapReached(customerId);
            } catch (SequenceContendedException e) {
                log.debug("sequence contended for customer {}, attempt {} of {}",
                        customerId, attempt, MAX_SEQUENCE_NUMBER_FAILS);
            }
        }
        log.warn("gave up opening an account for customer {} after {} contended attempts",
                customerId, MAX_SEQUENCE_NUMBER_FAILS);
        throw accountCapReached(customerId);
    }

    private AccountCapReachedException accountCapReached(UUID customerId) {
        auditLog.accountRefused(customerId, "account-limit-reached");
        return new AccountCapReachedException(customerId, ACCOUNTS_PER_CUSTOMER);
    }

    /**
     * Not scoped to a customer: the cache is keyed by account id alone, and the caller's
     * right to see the result is decided afterwards against the returned value.
     */
    @Cacheable(value = CacheConfig.ACCOUNTS, key = "#id",
            condition = "@featureFlags.redisCacheEnabled()",
            // #result is the value inside the Optional, so this is the spelling that
            // works. Without it, a lookup that finds nothing attempts a write the cache
            // refuses and logs a failure that is not one.
            unless = "#result == null")
    @Transactional(readOnly = true)
    public Optional<AccountView> findById(UUID id) {
        return repository.findById(id).map(AccountView::of);
    }

    /** Not cached: a per-customer list is invalidated by any opening. */
    @Transactional(readOnly = true)
    public List<AccountView> findForCustomer(UUID customerId) {
        return repository.findByCustomerIdOrderBySequenceNo(customerId).stream()
                .map(AccountView::of)
                .toList();
    }
}
