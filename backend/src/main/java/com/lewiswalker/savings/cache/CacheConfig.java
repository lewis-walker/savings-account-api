package com.lewiswalker.savings.cache;

import com.lewiswalker.savings.account.AccountView;
import com.lewiswalker.savings.customer.Customer;
import java.time.Duration;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two caches with different justifications. The customer lookup is remote, happens on
 * every account opening and changes rarely. The account read is cached because the brief
 * asks; a primary-key lookup Postgres answers from its buffer pool does not need one.
 * See DECISIONS.md.
 *
 * <p>Typed serializers rather than polymorphic JSON with default typing, so a tampered
 * entry fails to deserialize rather than instantiating whatever class it names. Null
 * values are not cached: absence is cheap to re-derive, and caching it would let anyone
 * probing for identifiers fill the cache.
 *
 * <p>The declared type is taken at its word. This named the JPA entity while the code was
 * writing {@code AccountView} records, and because the field names line up, Jackson built
 * entities out of them without error.
 */
@Configuration
@EnableCaching(proxyTargetClass = true)
public class CacheConfig implements CachingConfigurer {

    public static final String CUSTOMERS = "customers";
    public static final String ACCOUNTS = "accounts";

    /**
     * Short. Every TTL here is also the worst case for how long a stale entry can
     * survive a failed eviction, which is the number that actually matters.
     */
    private static final Duration CUSTOMER_TTL = Duration.ofMinutes(5);
    private static final Duration ACCOUNT_TTL = Duration.ofMinutes(2);

    @Bean
    RedisCacheManagerBuilderCustomizer cacheConfiguration() {
        return builder -> builder
                .withCacheConfiguration(CUSTOMERS, typed(Customer.class, CUSTOMER_TTL))
                .withCacheConfiguration(ACCOUNTS, typed(AccountView.class, ACCOUNT_TTL));
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandling();
    }

    private static <T> RedisCacheConfiguration typed(Class<T> type, Duration ttl) {
        JsonMapper mapper = JsonMapper.builder()
                // Cache entries outlive deployments. A field added to a record must not
                // make every entry written by the previous version unreadable.
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                .serializeValuesWith(SerializationPair.fromSerializer(
                        new JacksonJsonRedisSerializer<>(mapper, type)));
    }
}
