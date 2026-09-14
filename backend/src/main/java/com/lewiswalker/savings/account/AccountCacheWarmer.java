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
 * Puts a newly opened account into the cache once the transaction has committed.
 *
 * <p>After commit rather than {@code @CachePut} on the write: a value written before
 * commit may never exist, and an eviction before commit can be repopulated from the
 * pre-commit snapshot. See DECISIONS.md.
 *
 * <p>The try/catch is needed because this calls the cache directly rather than through
 * Spring's interceptor, so {@code CacheErrorHandling} does not cover it. The account is
 * already committed; a failed cache write must not fail the request.
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
        // The kill switch covers writes too: populating a cache nobody reads would leave
        // entries to go stale and be served when it is switched back on.
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
