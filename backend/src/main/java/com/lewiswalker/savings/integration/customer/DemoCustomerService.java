package com.lewiswalker.savings.integration.customer;

import com.lewiswalker.savings.platform.security.DemoIdentities;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.lewiswalker.savings.platform.cache.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * Stand-in for the customer master, seeded from the same identities the demo token
 * endpoint authenticates. One customer has incomplete due diligence, so the refusal path
 * is reachable in the running system.
 */
@Component
public class DemoCustomerService implements CustomerService {

    private final Map<UUID, Customer> customers;

    public DemoCustomerService() {
        this.customers = DemoIdentities.all().stream()
                .map(identity -> new Customer(
                        identity.customerId(),
                        identity.fullName(),
                        // Alan Turing's due diligence is pending: log in as him to see
                        // an account opening correctly refused.
                        DemoIdentities.ALAN.equals(identity)
                                ? Customer.DueDiligence.PENDING
                                : Customer.DueDiligence.COMPLETE))
                .collect(Collectors.toUnmodifiableMap(Customer::id, Function.identity()));
    }

    /**
     * {@inheritDoc}
     *
     * This is just a mock implementation, but it's a good example of an integration
     * where it's worth retrying if there's (potentially temporary) unavailability.
     */
    @Cacheable(value = CacheConfig.CUSTOMERS, key = "#customerId",
            condition = "@featureFlags.redisCacheEnabled()",
            // See AccountService#findById: without this, a lookup that finds nothing
            // attempts a write the cache refuses, and logs a failure that is not one.
            unless = "#result == null")
    @Retryable(
            includes = CustomerServiceUnavailableException.class,
            maxRetries = 2,
            delay = 100,
            jitter = 50,
            multiplier = 2.0,
            maxDelay = 500,
            timeout = 2000)
    @Override
    public Optional<Customer> findById(UUID customerId) {
        return Optional.ofNullable(customers.get(customerId));
    }
}
