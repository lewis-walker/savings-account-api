package com.lewiswalker.savings.integration.flags;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The cache kill switch: one flag, flippable while the service runs.
 *
 * <p>A flag rather than a configuration property because the point is to take a
 * misbehaving cache out of the path without a deployment, and a property needs a
 * restart - which is the last thing anyone wants mid-incident.
 *
 * <p>Read on every call and never held in a field by a caller, or it becomes the
 * property it exists not to be. Defaults to the current behaviour, so a flag service
 * that cannot be reached does not turn something on that nobody decided to turn on.
 *
 * <p>A real deployment uses a flag service - LaunchDarkly and the like - which streams
 * changes to every instance rather than one at a time. See DECISIONS.md; the shape of
 * that is not modelled here.
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
