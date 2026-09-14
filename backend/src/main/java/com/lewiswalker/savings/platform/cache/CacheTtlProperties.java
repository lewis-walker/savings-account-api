package com.lewiswalker.savings.platform.cache;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long each cache keeps an entry.
 *
 * <p>Per cache, because the two hold different things: a customer changes rarely, an
 * account is read far more often than it changes. Spring Boot's own
 * {@code spring.cache.redis.time-to-live} is one value for every cache.
 *
 * <p>Each value is also the worst case for how long a stale entry survives a failed
 * eviction.
 *
 * @param customers the customer lookup, a remote call behind a stable answer
 * @param accounts  the account read
 */
@ConfigurationProperties(prefix = "cache.ttl")
public record CacheTtlProperties(Duration customers, Duration accounts) {

    public CacheTtlProperties {
        customers = customers == null ? Duration.ofMinutes(5) : customers;
        accounts = accounts == null ? Duration.ofMinutes(2) : accounts;
    }
}
