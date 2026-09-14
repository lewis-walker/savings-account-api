package com.lewiswalker.savings.idempotency;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param retention how long a key is remembered: long enough to cover any realistic
 *                  retry, short enough that the store does not accumulate
 */
@ConfigurationProperties(prefix = "idempotency")
public record IdempotencyProperties(Duration retention) {

    public IdempotencyProperties {
        retention = retention == null ? Duration.ofHours(24) : retention;
    }
}
