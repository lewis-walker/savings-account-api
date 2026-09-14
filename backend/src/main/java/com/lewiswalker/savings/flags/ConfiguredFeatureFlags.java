package com.lewiswalker.savings.flags;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stand-in for LaunchDarkly: values from configuration, changeable at runtime through
 * {@link #override}. What it lacks is the streaming connection that pushes a change to
 * every instance, which is the operational reason to use a flag service at all.
 */
@Component("featureFlags")
public class ConfiguredFeatureFlags implements FeatureFlags {

    private static final Logger log = LoggerFactory.getLogger(ConfiguredFeatureFlags.class);

    private final Map<Feature, Boolean> values = new ConcurrentHashMap<>();

    public ConfiguredFeatureFlags(FeatureFlagProperties properties) {
        for (Feature feature : Feature.values()) {
            Boolean configured = properties.enabled().get(feature.key());
            values.put(feature, configured != null ? configured : feature.defaultValue());
        }
        log.info("feature flags at startup: {}", values);
    }

    @Override
    public boolean isEnabled(Feature feature, FlagContext context) {
        // Unused: nothing here targets by customer. On the signature anyway, so
        // adding targeting later does not mean touching every call site.
        return values.getOrDefault(feature, feature.defaultValue());
    }

    /**
     * Changes a flag without a restart.
     *
     * <p>Stands in for someone moving a toggle in a dashboard. In production nothing
     * calls this — the SDK updates its own store from the streaming connection.
     */
    public void override(Feature feature, boolean value) {
        log.info("feature flag {} set to {}", feature.key(), value);
        values.put(feature, value);
    }
}
