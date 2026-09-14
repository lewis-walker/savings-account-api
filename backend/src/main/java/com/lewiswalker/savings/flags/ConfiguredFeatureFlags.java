package com.lewiswalker.savings.flags;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * In-process stand-in for LaunchDarkly.
 *
 * <p>Values come from configuration and can be changed at runtime through
 * {@link #override}, so the switching behaviour can be exercised. What it lacks is the
 * streaming connection that pushes a change to every instance within a second or two of
 * someone moving a toggle, which is the operational reason to use a flag service rather
 * than a configuration property.
 *
 * <p>Evaluation is a map lookup and never throws — see the port for why that is a
 * requirement and not an optimisation.
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
        // The context is unused here because nothing in this demo targets by customer.
        // It is on the signature anyway: adding targeting later must not mean changing
        // every call site, and the call sites are the expensive part.
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
