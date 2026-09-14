package com.lewiswalker.savings.integration.flags;

import static org.assertj.core.api.Assertions.assertThat;

import com.lewiswalker.savings.TestcontainersConfiguration;
import com.lewiswalker.savings.account.AccountRepository;
import com.lewiswalker.savings.account.AccountService;
import com.lewiswalker.savings.account.AccountView;
import com.lewiswalker.savings.platform.cache.CacheConfig;
import com.lewiswalker.savings.platform.security.DemoIdentities;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;

/**
 * The cache kill switch, exercised rather than described.
 *
 * <p>What is being proved is not that a boolean can be read. It is that the flag is
 * evaluated on <em>every call</em>, so moving it changes behaviour on the next request
 * with no restart, no redeployment and no configuration change. A flag read once into a
 * field would pass a test that only checked the "off" case at startup, and would be
 * useless at 2am.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class CacheKillSwitchTest {

    private static final UUID ADA =
            DemoIdentities.byEmail("ada@example.test").orElseThrow().customerId();

    @Autowired private AccountService accounts;
    @Autowired private AccountRepository repository;
    @Autowired private CacheManager cacheManager;
    @Autowired private ConfiguredFeatureFlags flags;

    @BeforeEach
    void clear() {
        repository.deleteAll();
        cacheManager.getCache(CacheConfig.CUSTOMERS).evict(ADA);
    }

    @AfterEach
    void restore() {
        flags.override(Feature.REDIS_CACHE, true);
    }

    @Test
    @DisplayName("with the switch off nothing is written to the cache")
    void switchOffStopsWrites() {
        flags.override(Feature.REDIS_CACHE, false);

        AccountView opened = accounts.open(ADA, "Holiday fund");

        assertThat(cacheManager.getCache(CacheConfig.ACCOUNTS).get(opened.id()))
                .as("account entry with the cache switched off")
                .isNull();
        assertThat(cacheManager.getCache(CacheConfig.CUSTOMERS).get(ADA))
                .as("customer entry with the cache switched off")
                .isNull();
    }

    @Test
    @DisplayName("with the switch off everything still works, just from Postgres")
    void switchOffKeepsTheServiceWorking() {
        flags.override(Feature.REDIS_CACHE, false);

        AccountView opened = accounts.open(ADA, "House deposit");

        assertThat(accounts.findById(opened.id())).isPresent();
        assertThat(accounts.findForCustomer(ADA)).hasSize(1);
    }

    @Test
    @DisplayName("flipping the switch takes effect on the next call, with no restart")
    void switchTakesEffectImmediately() {
        flags.override(Feature.REDIS_CACHE, false);
        AccountView whileOff = accounts.open(ADA, "Rainy day");
        assertThat(cacheManager.getCache(CacheConfig.ACCOUNTS).get(whileOff.id())).isNull();

        // Same process, same beans, nothing restarted - as though someone had just
        // moved a toggle in a dashboard.
        flags.override(Feature.REDIS_CACHE, true);

        accounts.findById(whileOff.id());

        assertThat(awaitEntry(whileOff.id()))
                .as("cached on the very next read after the switch went back on")
                .isNotNull();
    }

    @Test
    @DisplayName("a read served while the cache was off still returns the right answer")
    void answersAreIdenticalEitherWay() {
        AccountView opened = accounts.open(ADA, "Emergency fund");
        AccountView cached = accounts.findById(opened.id()).orElseThrow();

        flags.override(Feature.REDIS_CACHE, false);
        AccountView uncached = accounts.findById(opened.id()).orElseThrow();

        // The cache is on the latency path and never the correctness path. If these
        // ever differ, the cache is a bug rather than an optimisation.
        assertThat(uncached).isEqualTo(cached);
    }

    private Object awaitEntry(UUID id) {
        for (int attempt = 0; attempt < 40; attempt++) {
            var found = cacheManager.getCache(CacheConfig.ACCOUNTS).get(id);
            if (found != null) {
                return found;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return cacheManager.getCache(CacheConfig.ACCOUNTS).get(id);
    }
}
