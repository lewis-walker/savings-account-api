package com.lewiswalker.savings.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * Swallows cache failures so the application survives Redis being unwell.
 *
 * <p>This is the single most important class in the package, and its absence is the
 * most common way a cache turns an optional dependency into a mandatory one. Spring's
 * default is {@code SimpleCacheErrorHandler}, which <b>rethrows</b>: Redis goes down,
 * every cached read throws, and an application that would have worked perfectly well
 * against Postgres returns 500s instead. The cache was supposed to make things faster
 * and it has made them unavailable.
 *
 * <p>So every failure here falls through to the real source. The cache sits on the
 * latency path and never on the correctness path — that is the whole contract, and this
 * is where it is enforced rather than asserted.
 *
 * <p>Logged at warn, not error: a cache miss caused by an outage is degraded service,
 * not a failure, and paging someone at three in the morning for something the system is
 * already handling correctly is how alerts get ignored. A sustained rate of these is
 * worth an alert; any individual one is not.
 *
 * <p><b>And the summary rather than the stack trace.</b> These fire once per cache
 * operation, so during an outage that is once or twice per request across the whole
 * fleet. Three requests against a stopped Redis produced 344 stack frames here — at real
 * traffic that is a log flood that costs money in an aggregator and buries everything
 * worth reading at precisely the moment somebody is reading logs. The stack is available
 * at debug for anyone who needs it; the message and the cache name are what identify the
 * problem, and they are always there.
 */
public class CacheErrorHandling implements CacheErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(CacheErrorHandling.class);

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        log.warn("cache read failed on '{}', falling through to the source: {}",
                cache.getName(), summarise(exception));
        log.debug("cache read failure detail", exception);
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        // The write already succeeded; only the cache update failed. The entry will be
        // read from the source next time, which is correct, just slower.
        log.warn("cache write failed on '{}': {}", cache.getName(), summarise(exception));
        log.debug("cache write failure detail", exception);
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        // Worth more attention than the others: a failed eviction can leave a stale
        // entry until its TTL expires. It is survivable here only because every cache
        // in this application has a short TTL. An eviction-based cache with no TTL
        // would be genuinely wrong to swallow.
        log.warn("cache eviction failed on '{}' - entry may be stale until it expires: {}",
                cache.getName(), summarise(exception));
        log.debug("cache eviction failure detail", exception);
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        log.warn("cache clear failed on '{}': {}", cache.getName(), summarise(exception));
        log.debug("cache clear failure detail", exception);
    }

    /**
     * The root cause in one line.
     *
     * <p>The outermost exception is usually a Spring wrapper saying little; the cause at
     * the bottom is the one that says "connection refused", which is the entire content
     * of the message.
     */
    private static String summarise(Throwable exception) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
