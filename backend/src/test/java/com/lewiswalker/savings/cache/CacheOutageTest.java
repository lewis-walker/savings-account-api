package com.lewiswalker.savings.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.lewiswalker.savings.TestcontainersConfiguration;
import com.lewiswalker.savings.account.AccountRepository;
import com.lewiswalker.savings.account.AccountService;
import com.lewiswalker.savings.account.AccountView;
import com.lewiswalker.savings.security.DemoIdentities;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.GenericContainer;

/**
 * Redis dies; the application does not.
 *
 * <p>This is the test that justifies {@link CacheErrorHandling}, and the failure it
 * guards against is a common one. Spring's default cache error handler rethrows, so an
 * application that would have served every request perfectly well from Postgres instead
 * returns 500s the moment Redis becomes unavailable. A cache introduced to make things
 * faster has made them unavailable — and the outage is in a dependency nobody listed as
 * critical, because nobody thought it was.
 *
 * <p>The container is stopped for real rather than mocked, so what is proved is the
 * behaviour of the actual client, timeouts and error handler together.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
// The container does not come back, so this context must not be reused.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CacheOutageTest {

    private static final UUID ADA =
            DemoIdentities.byEmail("ada@example.test").orElseThrow().customerId();

    @Autowired private AccountService accounts;
    @Autowired private AccountRepository repository;
    @Autowired private GenericContainer<?> redisContainer;

    @Test
    @DisplayName("accounts can still be opened and read with Redis stopped")
    void survivesRedisOutage() {
        repository.deleteAll();

        // Prove the system works before taking anything away, so a failure afterwards
        // is attributable to the outage and not to the fixture.
        AccountView before = accounts.open(ADA, "Holiday fund");
        assertThat(accounts.findById(before.id())).isPresent();

        redisContainer.stop();

        // A write: the customer lookup, the cache warm and the database write all
        // happen with the cache gone.
        AccountView during = accounts.open(ADA, "Rainy day");
        assertThat(during.accountNumber()).isNotBlank();
        assertThat(during.customerName()).isEqualTo("Ada Lovelace");

        // A read: the cache lookup fails and falls through to Postgres, which is the
        // whole contract - the cache is on the latency path, never the correctness path.
        assertThat(accounts.findById(during.id()))
                .as("read with no cache available")
                .isPresent();
        assertThat(accounts.findById(before.id()))
                .as("an entry that was cached before the outage")
                .isPresent();

        // And the business rules are untouched: nothing about the cap depended on Redis.
        assertThat(accounts.findForCustomer(ADA)).hasSize(2);
    }
}
