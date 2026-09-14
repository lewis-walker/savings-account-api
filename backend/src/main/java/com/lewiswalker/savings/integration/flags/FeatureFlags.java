package com.lewiswalker.savings.integration.flags;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The cache kill switch: one flag, flippable while the service runs.
 */
@Component("featureFlags")
public class FeatureFlags {

    static final String REDIS_CACHE = "redis-cache";
    static final boolean REDIS_CACHE_DEFAULT = true;
    static final String REDIS_CACHE_DESCRIPTION =
            "Serve reads from Redis. Operational kill switch: turn off to take a "
                    + "misbehaving cache out of the path immediately. Everything keeps "
                    + "working, from Postgres.";

    private final AtomicBoolean redisCache;

    FeatureFlags(@Value("${features.redis-cache:" + REDIS_CACHE_DEFAULT + "}") boolean redisCache) {
        this.redisCache = new AtomicBoolean(redisCache);
    }

    public boolean redisCacheEnabled() {
        return redisCache.get();
    }

    void setRedisCacheEnabled(boolean enabled) {
        redisCache.set(enabled);
    }
}
