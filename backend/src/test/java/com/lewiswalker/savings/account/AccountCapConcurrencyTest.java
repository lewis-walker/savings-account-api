package com.lewiswalker.savings.account;

import static org.assertj.core.api.Assertions.assertThat;

import com.lewiswalker.savings.TestcontainersConfiguration;
import com.lewiswalker.savings.support.StubCustomerDirectory;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The account cap is the one rule in the brief that is a concurrency problem rather
 * than a validation problem, so it gets tested as one.
 *
 * <p>An implementation that counts rows and then inserts passes any sequential test
 * and still lets a customer end up with six accounts in production. The only way to
 * tell the two apart is to make the requests actually overlap.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, StubCustomerDirectory.class})
class AccountCapConcurrencyTest {

    private static final int CONCURRENT_REQUESTS = 16;

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository repository;

    @BeforeEach
    void clear() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("sixteen simultaneous requests for one customer open exactly five accounts")
    void concurrentRequestsCannotExceedTheCap() throws Exception {
        UUID customerId = UUID.randomUUID();

        // Every thread parks on this latch so they contend for real rather than
        // trickling in one at a time and quietly passing.
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger opened = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        AtomicInteger otherFailures = new AtomicInteger();

        try (ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_REQUESTS)) {
            List<? extends Future<?>> running = java.util.stream.IntStream.range(0, CONCURRENT_REQUESTS)
                    .mapToObj(i -> pool.submit(() -> {
                        try {
                            release.await();
                            accountService.open(customerId, null);
                            opened.incrementAndGet();
                        } catch (AccountCapReachedException e) {
                            refused.incrementAndGet();
                        } catch (Exception e) {
                            otherFailures.incrementAndGet();
                        }
                    }))
                    .toList();

            release.countDown();
            for (Future<?> f : running) {
                f.get(60, TimeUnit.SECONDS);
            }
        }

        // Nothing should have escaped as an unexpected error. A raw constraint
        // violation leaking out here would mean the service is not distinguishing
        // "customer is full" from "someone beat me to that slot".
        assertThat(otherFailures.get())
                .as("requests failing for a reason other than the cap")
                .isZero();

        assertThat(opened.get()).isEqualTo(AccountService.ACCOUNTS_PER_CUSTOMER);
        assertThat(refused.get()).isEqualTo(CONCURRENT_REQUESTS - AccountService.ACCOUNTS_PER_CUSTOMER);

        List<Account> persisted = repository.findByCustomerIdOrderBySequenceNo(customerId);
        assertThat(persisted).hasSize(AccountService.ACCOUNTS_PER_CUSTOMER);
        assertThat(persisted).extracting(Account::getSequenceNo)
                .as("the 1..5 series is dense, with no gaps burned by lost races")
                .containsExactly((short) 1, (short) 2, (short) 3, (short) 4, (short) 5);
        assertThat(persisted).extracting(Account::getAccountNumber)
                .as("account numbers are unique")
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("a sixth sequential request is refused")
    void sixthAccountIsRefused() {
        UUID customerId = UUID.randomUUID();
        for (int i = 0; i < AccountService.ACCOUNTS_PER_CUSTOMER; i++) {
            accountService.open(customerId, null);
        }

        assertThat(repository.findByCustomerIdOrderBySequenceNo(customerId)).hasSize(5);

        org.junit.jupiter.api.Assertions.assertThrows(
                AccountCapReachedException.class,
                () -> accountService.open(customerId, null));
    }

    @Test
    @DisplayName("the cap is per customer, not global")
    void capIsPerCustomer() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        for (int i = 0; i < AccountService.ACCOUNTS_PER_CUSTOMER; i++) {
            accountService.open(first, null);
        }

        AccountView other = accountService.open(second, null);

        assertThat(other.sequenceNo()).isEqualTo((short) 1);
        assertThat(repository.findByCustomerIdOrderBySequenceNo(second)).hasSize(1);
    }
}
