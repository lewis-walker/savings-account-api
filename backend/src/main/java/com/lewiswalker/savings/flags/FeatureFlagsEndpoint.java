package com.lewiswalker.savings.flags;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

/**
 * Reads and sets feature flags.
 *
 * <p>An actuator endpoint rather than a controller, so it is served on the
 * <b>management port</b> and not on the one customers reach. That separation is the
 * point of building it this way: a switch that changes how the service behaves has no
 * business sharing a port, a TLS certificate or an ingress rule with the account API.
 * In a real deployment the management port is bound to an internal interface, reachable
 * only from the operations network, behind SSO — and here it is simply not published to
 * the outside world by the customer-facing route.
 *
 * <p>It also stands in for something that is genuinely a separate system. Nobody
 * operates LaunchDarkly by calling their own service; they use LaunchDarkly's console,
 * which talks to LaunchDarkly, which pushes to every instance. The console beside this
 * repository is the same shape in miniature: its own app, on its own port, talking to
 * this.
 *
 * <p>TODO: authentication. Left open deliberately so the demo runs with one command,
 * and it is the first thing that would change — an endpoint that can turn off a
 * dependency is an endpoint that can cause an incident, so it wants SSO, an audit trail
 * of who changed what, and ideally four eyes on anything customer-visible.
 */
@Component
@Endpoint(id = "featureflags")
public class FeatureFlagsEndpoint {

    private final ConfiguredFeatureFlags flags;

    public FeatureFlagsEndpoint(ConfiguredFeatureFlags flags) {
        this.flags = flags;
    }

    public record FlagState(String key, boolean enabled, boolean defaultValue, String description) {}

    @ReadOperation
    public Map<String, List<FlagState>> flags() {
        return Map.of("flags", Arrays.stream(Feature.values())
                .map(feature -> new FlagState(
                        feature.key(),
                        flags.isEnabled(feature),
                        feature.defaultValue(),
                        describe(feature)))
                .toList());
    }

    @ReadOperation
    public FlagState flag(@Selector String key) {
        Feature feature = byKey(key);
        return new FlagState(feature.key(), flags.isEnabled(feature),
                feature.defaultValue(), describe(feature));
    }

    @WriteOperation
    public Map<String, Object> set(@Selector String key, boolean enabled) {
        Feature feature = byKey(key);
        flags.override(feature, enabled);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", feature.key());
        result.put("enabled", flags.isEnabled(feature));
        return result;
    }

    private static Feature byKey(String key) {
        return Arrays.stream(Feature.values())
                .filter(feature -> feature.key().equals(key))
                .findFirst()
                // Refused rather than defaulted: a typo should not become a silently
                // disabled feature.
                .orElseThrow(() -> new IllegalArgumentException("no such flag: " + key));
    }

    private static String describe(Feature feature) {
        return switch (feature) {
            case REDIS_CACHE -> "Serve reads from Redis. Operational kill switch: "
                    + "turn off to take a misbehaving cache out of the path immediately. "
                    + "Everything keeps working, from Postgres.";
        };
    }
}
