package com.lewiswalker.savings;

import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.EnableResilientMethods;

/**
 * Enables {@code @Retryable}, which Spring Framework 7 carries in core rather than in a
 * separate library.
 *
 * <p>Where retry is applied, and the places it is deliberately not: DECISIONS.md.
 */
@Configuration
@EnableResilientMethods(proxyTargetClass = true)
public class ResilienceConfig {
}
