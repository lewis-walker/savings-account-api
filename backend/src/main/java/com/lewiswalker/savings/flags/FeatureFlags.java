package com.lewiswalker.savings.flags;

/**
 * Evaluates feature flags.
 *
 * <p>The third integration port, alongside the customer directory and account number
 * allocation. In this estate it would be backed by LaunchDarkly: the SDK holds a
 * streaming connection to the flag service, keeps an in-memory store up to date, and
 * evaluates locally — so {@link #isEnabled} is a map lookup, not a network call, and is
 * safe to put on a hot path.
 *
 * <p><b>Four things this interface exists to make non-negotiable.</b>
 *
 * <ol>
 *   <li><b>Evaluated per call, never cached in a field.</b> Reading a flag once at
 *       startup and keeping it turns a flag back into a configuration property that
 *       needs a restart, which defeats the entire purpose.
 *   <li><b>It cannot throw.</b> An implementation that propagates a failure from the
 *       flag service makes that service a hard dependency of every request — which is
 *       an absurd thing for a switch to be. Failures return {@link Feature#defaultValue}.
 *   <li><b>Evaluated against a context</b>, so targeting and progressive rollout are
 *       possible rather than needing a redesign later.
 *   <li><b>The context carries no personal data.</b> Everything in it goes to a third
 *       party and appears in their dashboard.
 * </ol>
 *
 * <p>TODO: the LaunchDarkly adapter. It needs the SDK key from a secret store rather
 * than configuration, an offline mode so local development and tests do not talk to a
 * SaaS service, and the evaluation events it emits are worth keeping — knowing which
 * variation a customer actually received is the only way to explain their behaviour
 * afterwards.
 */
public interface FeatureFlags {

    boolean isEnabled(Feature feature, FlagContext context);

    default boolean isEnabled(Feature feature) {
        return isEnabled(feature, FlagContext.anonymous());
    }

    /**
     * Named shorthand for the cache kill switch.
     *
     * <p>Exists because it is referenced from a SpEL {@code condition} on
     * {@code @Cacheable}, and the alternative spelling there is a
     * {@code T(...)} type expression that nobody reads twice. A method the compiler
     * checks beats a string the compiler does not.
     */
    default boolean redisCacheEnabled() {
        return isEnabled(Feature.REDIS_CACHE);
    }
}
