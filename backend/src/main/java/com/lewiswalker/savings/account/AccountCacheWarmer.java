package com.lewiswalker.savings.account;

import com.lewiswalker.savings.platform.cache.CacheConfig;
import com.lewiswalker.savings.integration.flags.FeatureFlags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;

/**
 * Puts a newly opened account into the cache. Must be called after the opening
 * transaction has committed.
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
        if (!features.redisCacheEnabled()) {
            return;
        }
        Cache cache = cacheManager.getCache(CacheConfig.ACCOUNTS);
        if (cache == null) {
            return;
        }

        // A failed warm must not fail a committed write
        try {
            cache.put(account.id(), account);
        } catch (RuntimeException e) {
            log.warn("could not warm the account cache; the next read will use the database", e);
        }
    }
}
