package com.lewiswalker.savings.platform.cache;

import com.lewiswalker.savings.account.AccountView;
import com.lewiswalker.savings.integration.customer.Customer;
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
 * Two caches: the customer lookup, which is remote and stable, and the account read,
 * which is here because the brief asks. See DECISIONS.md.
 *
 * <p>Typed serializers rather than polymorphic JSON, and nulls are not cached. The
 * declared type is taken at its word - naming the entity here while the code writes
 * AccountView records builds entities out of them, silently.
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
                // Entries outlive deployments: a new field must not make every entry
                // written by the previous version unreadable.
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        return RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(ttl)
                .disableCachingNullValues()
                .serializeValuesWith(SerializationPair.fromSerializer(
                        new JacksonJsonRedisSerializer<>(mapper, type)));
    }
}
