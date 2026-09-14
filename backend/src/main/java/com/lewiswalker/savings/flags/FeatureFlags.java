package com.lewiswalker.savings.flags;

/**
 * Evaluates feature flags. Backed by LaunchDarkly in production, whose SDK evaluates
 * locally against a streamed store, so this is a map lookup and safe on a hot path.
 *
 * <p>Four constraints on any implementation. Evaluate on every call and never cache into
 * a field, or the flag becomes a property needing a restart. Never throw, because a
 * switch must not be able to fail a request. Evaluate against a context, so targeting is
 * possible later without changing call sites. And put no personal data in that context,
 * because all of it is sent to a third party.
 *
 * <p>TODO: the LaunchDarkly adapter needs its SDK key from a secret store, an offline
 * mode for tests, and its evaluation events retained.
 */
public interface FeatureFlags {

    boolean isEnabled(Feature feature, FlagContext context);

    default boolean isEnabled(Feature feature) {
        return isEnabled(feature, FlagContext.anonymous());
    }

    /**
     * Named shorthand for the cache kill switch, because the SpEL {@code condition} on
     * {@code @Cacheable} would otherwise carry an unchecked {@code T(...)} expression.
     */
    default boolean redisCacheEnabled() {
        return isEnabled(Feature.REDIS_CACHE);
    }
}
