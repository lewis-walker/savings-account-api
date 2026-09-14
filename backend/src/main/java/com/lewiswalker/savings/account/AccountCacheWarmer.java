package com.lewiswalker.savings.account;

import com.lewiswalker.savings.cache.CacheConfig;
import com.lewiswalker.savings.flags.FeatureFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * Puts a newly opened account into the cache.
 *
 * <p>Must be called once the opening transaction has committed. A value written before
 * commit may never exist, and an eviction before commit can be repopulated from the
 * pre-commit snapshot. {@link AccountService} calls this after its
 * {@code transaction.execute(...)} returns, which is that point.
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

    public void warm(AccountView account) {
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
            cache.put(account.id(), account);
        } catch (RuntimeException e) {
            log.warn("could not warm the account cache; the next read will use the database", e);
        }
    }
}
