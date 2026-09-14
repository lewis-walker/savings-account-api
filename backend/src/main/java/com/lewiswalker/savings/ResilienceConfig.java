package com.lewiswalker.savings;

import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;

/**
 * Enables {@code @Retryable}, which Spring Framework 7 carries in core rather than in a
 * separate library.
 *
 * <p>{@code proxyTargetClass = true} is required, not preferred. Every adapter implements
 * a port, and with the default JDK proxy the interceptor resolves the policy from the
 * interface method, which carries no annotation — so a proxy is created and adds nothing,
 * with no error. CGLIB subclasses the concrete class instead.
 *
 * <p>Where retry is applied, and the places it is deliberately not: DECISIONS.md.
 */
@Configuration
@EnableResilientMethods(proxyTargetClass = true)
public class ResilienceConfig {
}
