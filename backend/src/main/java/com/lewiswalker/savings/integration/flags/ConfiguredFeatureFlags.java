package com.lewiswalker.savings.integration.flags;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// Stand-in for LaunchDarkly: values from configuration, changeable at runtime
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
    public boolean isEnabled(Feature feature) {
        return values.getOrDefault(feature, feature.defaultValue());
    }

    /**
     * Changes a flag without a restart.
     */
    public void override(Feature feature, boolean value) {
        log.info("feature flag {} set to {}", feature.key(), value);
        values.put(feature, value);
    }
}
