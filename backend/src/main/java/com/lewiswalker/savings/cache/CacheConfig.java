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
 * Two caches, with very different justifications.
 *
 * <h2>customers — earns its keep</h2>
 *
 * A lookup against another system, over a network, on every account opening, for data
 * that changes rarely. Remote, hot, and stable is what a cache is actually for.
 *
 * <h2>accounts — does not</h2>
 *
 * The brief asks for the GET to be cached, so it is. It should be said plainly that it
 * buys nothing: this is a primary-key lookup on a narrow table, which Postgres serves
 * from its own buffer pool in microseconds. Putting Redis in front adds a network hop,
 * a serialisation cost, an invalidation problem and a new failure mode, in order to
 * speed up something that was never slow.
 *
 * <p>It is built correctly anyway, because the interesting part is what "correctly"
 * costs — see {@code AccountCacheWarmer} for the read-after-write rule, and
 * {@link CacheErrorHandling} for why Redis being down is not this application's problem.
 *
 * <h2>Two choices worth defending</h2>
 *
 * <p><b>Typed serializers, not polymorphic JSON.</b> The convenient option is a generic
 * serializer with default typing switched on, which writes a Java class name into every
 * cache entry and instantiates whatever it reads back. That is a deserialization gadget
 * waiting for someone with write access to the cache. Each cache here declares the one
 * type it holds, so a tampered entry fails to deserialize rather than executing.
 *
 * <p>The cost of that choice, learned the hard way: the declared type is taken at its
 * word. This originally named the JPA entity here while the cache was being written
 * AccountView records, and because the field names line up, Jackson quietly built
 * entities out of them. No error, wrong type. A typed serializer removes the gadget
 * problem; it does not remove the obligation to name the right type.
 *
 * <p><b>Null values are not cached.</b> Absence is cheap to re-derive and caching it
 * lets anyone probing for identifiers fill the cache with negative entries. It also
 * keeps the typed serializers honest, since Spring's null marker is not of the declared
 * type.
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
