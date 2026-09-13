package com.lewiswalker.savings.flags;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Local flag values, keyed as the flag service keys them.
 *
 * <p>Configuration is the <em>stand-in</em> for the flag service, not the mechanism. It
 * is here so the demo can be driven from an environment variable; a real deployment
 * takes values from LaunchDarkly and this map stays empty.
 */
@ConfigurationProperties(prefix = "features")
public record FeatureFlagProperties(Map<String, Boolean> enabled) {

    public FeatureFlagProperties {
        enabled = enabled == null ? Map.of() : Map.copyOf(enabled);
    }
}
