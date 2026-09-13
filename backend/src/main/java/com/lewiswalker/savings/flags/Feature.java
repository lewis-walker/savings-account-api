package com.lewiswalker.savings.flags;

/**
 * Every flag in the system, with what it is for and when it should be gone.
 *
 * <p>Enumerated rather than free-form strings on purpose. A flag service will happily
 * accept any key you ask it about and return the default for one that does not exist,
 * so a typo becomes a silently disabled feature. More importantly, flags are technical
 * debt: each one is a branch in production that somebody has to delete. Keeping them in
 * one list with an owner and an expectation of removal is the difference between a
 * handful of deliberate switches and two hundred nobody dares touch.
 */
public enum Feature {

    /**
     * Whether reads are served from Redis.
     *
     * <p>An <b>operational kill switch</b> rather than a release toggle: it exists so a
     * misbehaving cache can be taken out of the path in seconds, by someone on a bridge
     * call at 2am, without a deployment and without a code change. That is the case
     * where flags genuinely earn their keep — a configuration property would need a
     * restart, and a restart is the last thing anyone wants mid-incident.
     *
     * <p>Kill switches are the one category of flag that is <em>not</em> debt. It stays.
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
     * What to assume when the flag service cannot be reached.
     *
     * <p>Chosen so the system behaves as normal when the flag service is down. A flag
     * whose fail-safe default is the *new* behaviour means an outage in the flag service
     * silently enables something nobody has decided to enable.
     */
    public boolean defaultValue() {
        return defaultValue;
    }
}
