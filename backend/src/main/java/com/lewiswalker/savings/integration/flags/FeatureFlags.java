package com.lewiswalker.savings.integration.flags;

/**
 * Evaluates feature flags. Backed by LaunchDarkly in production, whose SDK evaluates
 * locally against a streamed store, so this is a map lookup and safe on a hot path.
 *
 * <p>Two constraints on any implementation. Evaluate on every call and never cache into
 * a field, or the flag becomes a property needing a restart. And never throw, because a
 * switch must not be able to fail the request it is switching.
 *
 * <p>A real flag service also evaluates per user, so a flag can be turned on for some
 * people and not others. Nothing here needs that - a kill switch is off for everyone or
 * on for everyone - so it is not modelled. See DECISIONS.md.
 */
public interface FeatureFlags {

    boolean isEnabled(Feature feature);

    /**
     * Named shorthand for the cache kill switch, because the SpEL {@code condition} on
     * {@code @Cacheable} would otherwise carry an unchecked {@code T(...)} expression.
     */
    default boolean redisCacheEnabled() {
        return isEnabled(Feature.REDIS_CACHE);
    }
}
