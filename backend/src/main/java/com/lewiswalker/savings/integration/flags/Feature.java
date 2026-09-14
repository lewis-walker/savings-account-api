package com.lewiswalker.savings.integration.flags;

/**
 * Every flag, with its fail-safe default. Enumerated rather than free-form strings: a
 * flag service returns the default for a key that does not exist, so a typo becomes a
 * silently disabled feature.
 */
public enum Feature {

    /**
     * Whether reads are served from Redis. An operational kill switch rather than a
     * release toggle: a misbehaving cache comes out of the path without a deployment,
     * where a configuration property would need a restart mid-incident.
     */
    REDIS_CACHE("redis-cache", true);

    private final String key;
    private final boolean defaultValue;

    Feature(String key, boolean defaultValue) {
        this.key = key;
        this.defaultValue = defaultValue;
    }

    /** The key as the flag service knows it. */
    public String key() {
        return key;
    }

    /**
     * What to assume when the flag service cannot be reached. Always the current
     * behaviour: otherwise an outage there enables something nobody decided to enable.
     */
    public boolean defaultValue() {
        return defaultValue;
    }
}
