package com.lewiswalker.savings.cache;

import static org.assertj.core.api.Assertions.assertThat;

import com.lewiswalker.savings.TestcontainersConfiguration;
import com.lewiswalker.savings.account.AccountRepository;
import com.lewiswalker.savings.account.AccountService;
import com.lewiswalker.savings.account.AccountView;
import com.lewiswalker.savings.security.DemoIdentities;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;

@SpringBootTest
@Import(TestcontainersConfiguration.class)   // the real directory, so its @Cacheable is under test
class CacheBehaviourTest {

    /** A real demo customer, so the real cached adapter is the one being exercised. */
    private static final UUID ADA = DemoIdentities.byEmail("ada@example.test").orElseThrow().customerId();

    @Autowired private AccountService accounts;
    @Autowired private AccountRepository repository;
    @Autowired private CacheManager cacheManager;

    @BeforeEach
    void clear() {
        // Five accounts per customer, and these tests share one.
        repository.deleteAll();

        // evict, not clear. RedisCache.clear() scans for matching keys and deletes
        // them, and that was not observable immediately here: a read straight after it
        // still saw the old entry, the code under test then got a cache hit and wrote
        // nothing, and the delete landed afterwards - leaving an empty cache and a test
        // failing for a reason that had nothing to do with the cache being wrong.
        // Deleting one known key is a single operation with no such window.
        cacheManager.getCache(CacheConfig.CUSTOMERS).evict(ADA);
    }

    @Test
    @DisplayName("a newly opened account is in the cache once the transaction commits")
    void writeThroughAfterCommit() {
        AccountView opened = accounts.open(ADA, null);

        Cache.ValueWrapper cached = awaitCacheEntry(CacheConfig.ACCOUNTS, opened.id());

        // Not merely "a later read works" - that passes with no cache at all. The entry
        // appears because the after-commit listener put it there, which is what makes
        // create-then-read consistent rather than dependent on an eviction winning a
        // race.
        assertThat(cached).as("cache entry after commit").isNotNull();
        assertThat(cached.get()).isInstanceOf(AccountView.class);
        assertThat(((AccountView) cached.get()).accountNumber()).isEqualTo(opened.accountNumber());
    }

    @Test
    @DisplayName("reads really are served from the cache, not the database")
    void readsComeFromTheCache() {
        AccountView opened = accounts.open(ADA, null);

        // Delete the row behind the service's back. If the next read still answers, it
        // cannot have come from Postgres. Crude, but it proves a hit rather than
        // assuming one - a passing read proves nothing on its own.
        repository.deleteById(opened.id());
        repository.flush();

        assertThat(accounts.findById(opened.id()))
                .as("served from cache after the row was removed")
                .isPresent();
    }

    /**
     * Polls briefly rather than reading once.
     *
     * <p>An assertion straight after the call was flaky - passing twice, failing once,
     * on identical code. The cache write is not instantaneously observable, and a test
     * that is right most of the time is worse than no test, because it trains people to
     * re-run it.
     *
     * <p>Worth being clear that this does not weaken the claim. Correctness never
     * depended on the timing: a read that misses the cache falls through to the
     * committed row and returns the same answer. What is asserted is that the entry
     * lands promptly, not that it lands within a particular instruction.
     */
    private Cache.ValueWrapper awaitCacheEntry(String cacheName, Object key) {
        Cache cache = cacheManager.getCache(cacheName);
        for (int attempt = 0; attempt < 40; attempt++) {
            Cache.ValueWrapper found = cache.get(key);
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
        return cache.get(key);
    }

    @Test
    @DisplayName("the cached value round-trips through Redis intact")
    void serialisationIsLossless() {
        AccountView opened = accounts.open(ADA, "Holiday fund");

        AccountView fromCache = (AccountView) awaitCacheEntry(CacheConfig.ACCOUNTS, opened.id()).get();

        // A silently lossy serializer is the kind of thing that surfaces as a null
        // nickname in production three weeks later.
        assertThat(fromCache).isEqualTo(opened);
    }

    @Test
    @DisplayName("an account that does not exist is not cached as absent")
    void absenceIsNotCached() {
        UUID unknown = UUID.randomUUID();

        assertThat(accounts.findById(unknown)).isEmpty();

        // disableCachingNullValues. Caching absence would let anyone probing for
        // identifiers fill the cache with negative entries, and there is nothing
        // expensive about re-deriving "no".
        assertThat(cacheManager.getCache(CacheConfig.ACCOUNTS).get(unknown)).isNull();
    }

    @Test
    @DisplayName("the customer lookup is cached")
    void customerLookupIsCached() {
        accounts.open(ADA, null);

        assertThat(awaitCacheEntry(CacheConfig.CUSTOMERS, ADA))
                .as("customer entry after a lookup")
                .isNotNull();
    }
}
