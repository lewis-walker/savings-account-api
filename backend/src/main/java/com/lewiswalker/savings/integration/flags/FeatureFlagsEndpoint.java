package com.lewiswalker.savings.integration.flags;

import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

/**
 * Reads and flips the flag, on the management port.
 * Purely throw-away. No authentication, no audit trail, no nothing.
 */
@Component
@Endpoint(id = "featureflags")
public class FeatureFlagsEndpoint {

    public record FlagState(String key, boolean enabled, boolean defaultValue, String description) {}

    private final FeatureFlags flags;

    public FeatureFlagsEndpoint(FeatureFlags flags) {
        this.flags = flags;
    }

    @ReadOperation
    public Map<String, List<FlagState>> flags() {
        return Map.of("flags", List.of(state()));
    }

    @ReadOperation
    public FlagState flag(@Selector String key) {
        return state(key);
    }

    @WriteOperation
    public FlagState set(@Selector String key, boolean enabled) {
        state(key);
        flags.setRedisCacheEnabled(enabled);
        return state();
    }

    /** Refused rather than defaulted: a typo should not read as a disabled feature. */
    private FlagState state(String key) {
        if (!FeatureFlags.REDIS_CACHE.equals(key)) {
            throw new IllegalArgumentException("no such flag: " + key);
        }
        return state();
    }

    private FlagState state() {
        return new FlagState(FeatureFlags.REDIS_CACHE, flags.redisCacheEnabled(),
                FeatureFlags.REDIS_CACHE_DEFAULT, FeatureFlags.REDIS_CACHE_DESCRIPTION);
    }
}
