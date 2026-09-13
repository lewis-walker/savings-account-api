package com.lewiswalker.savings.account;

import com.lewiswalker.savings.cache.CacheConfig;
import com.lewiswalker.savings.flags.FeatureFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Puts a newly opened account into the cache, after the transaction commits.
 *
 * <h2>Why after commit, and not on the write method</h2>
 *
 * <p>The obvious implementation is {@code @CachePut} or {@code @CacheEvict} on the
 * method that writes. Both run while the transaction is still open, and that is a
 * correctness bug rather than a style preference:
 *
 * <ul>
 *   <li>A {@code @CachePut} before commit publishes a value that may never exist. If
 *       the transaction rolls back, the cache is now serving an account the database
 *       does not have.
 *   <li>A {@code @CacheEvict} before commit opens a window between the eviction and the
 *       commit in which a concurrent read repopulates the cache from the pre-commit
 *       snapshot — pinning stale data until the TTL expires.
 * </ul>
 *
 * <p>{@code AFTER_COMMIT} has neither problem. The value written here is the committed
 * value, so create-then-read is consistent <em>by construction</em> rather than by the
 * eviction happening to win a race. In a bank, opening an account and then not seeing it
 * is not a slow cache, it is a wrong answer.
 *
 * <h2>Why the try/catch</h2>
 *
 * <p>{@link com.lewiswalker.savings.cache.CacheErrorHandling} covers failures inside
 * Spring's caching interceptor — the {@code @Cacheable} path. This is a direct call to
 * the cache API and is not intercepted, so a Redis outage here would throw into the
 * transaction synchronisation callback. The account is already committed at this point;
 * losing the cache warm is not worth turning a successful write into an error.
 */
@Component
public class AccountCacheWarmer {

    private static final Logger log = LoggerFactory.getLogger(AccountCacheWarmer.class);

    private final CacheManager cacheManager;
    private final FeatureFlags features;

    public AccountCacheWarmer(CacheManager cacheManager, FeatureFlags features) {
        this.cacheManager = cacheManager;
        this.features = features;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAccountOpened(AccountOpened event) {
        // The kill switch has to cover writes as well as reads. Switching the cache off
        // while still populating it would leave entries nobody reads, quietly going
        // stale, ready to be served the moment someone switches it back on.
        if (!features.redisCacheEnabled()) {
            return;
        }
        Cache cache = cacheManager.getCache(CacheConfig.ACCOUNTS);
        if (cache == null) {
            return;
        }
        try {
            cache.put(event.account().id(), event.account());
        } catch (RuntimeException e) {
            log.warn("could not warm the account cache; the next read will use the database", e);
        }
    }
}
