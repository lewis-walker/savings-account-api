package com.lewiswalker.savings.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;

/**
 * Swallows cache failures, so Redis being unwell does not make it a required dependency.
 * Spring's default handler rethrows.
 *
 * <p>Warn rather than error, and the summary rather than the stack: these fire once per
 * cache operation, so an outage would otherwise flood the log with the same trace.
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
        // The write succeeded; only the cache update failed. Next read goes to the
        // source, which is correct, just slower.
        log.warn("cache write failed on '{}': {}", cache.getName(), summarise(exception));
        log.debug("cache write failure detail", exception);
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        // Louder than the others: a failed eviction leaves a stale entry until the
        // TTL expires. Survivable only because every cache here has a short one.
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
