package com.lewiswalker.savings.customer;

import com.lewiswalker.savings.security.DemoIdentities;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.lewiswalker.savings.cache.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.stereotype.Component;

/**
 * In-process stand-in for the customer master.
 *
 * <p>Seeded from the same identities the demo token endpoint authenticates, so a
 * token's subject always resolves to a customer this directory has heard of.
 *
 * <p>One customer is deliberately left with incomplete due diligence, so the refusal
 * path is reachable in the running system rather than only in a test. A demo where
 * every path succeeds does not show you much.
 */
@Component
public class DemoCustomerDirectory implements CustomerDirectory {

    private final Map<UUID, Customer> customers;

    public DemoCustomerDirectory() {
        this.customers = DemoIdentities.all().stream()
                .map(identity -> new Customer(
                        identity.customerId(),
                        identity.fullName(),
                        // Alan Turing's due diligence is pending: log in as him to see
                        // an account opening correctly refused.
                        "alan@example.test".equals(identity.email())
                                ? Customer.DueDiligence.PENDING
                                : Customer.DueDiligence.COMPLETE))
                .collect(Collectors.toUnmodifiableMap(Customer::id, Function.identity()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The policy is here rather than on the port because how hard to try is a
     * property of the transport, not of the question being asked.
     *
     * <p>It cannot fire against this adapter — an in-process map does not fail
     * transiently — and it is annotated anyway, because this is the seam the real
     * adapter drops into and the policy is part of what that seam is for. What it
     * retries is the point: only unavailability. An unknown customer is a final answer
     * and retrying it would delay a definite no.
     */
    @Cacheable(value = CacheConfig.CUSTOMERS, key = "#customerId",
            condition = "@featureFlags.redisCacheEnabled()",
            // See AccountService#findById: without this, a lookup that finds nothing
            // attempts a write the cache refuses, and logs a failure that is not one.
            unless = "#result == null")
    @Retryable(
            includes = CustomerDirectoryUnavailableException.class,
            maxRetries = 2,
            delay = 100,
            jitter = 50,
            multiplier = 2.0,
            maxDelay = 500,
            // A ceiling on the whole affair. Without it a policy can quietly outlast
            // the caller's own timeout, and the work is thrown away by someone who has
            // already given up.
            timeout = 2000)
    @Override
    public Optional<Customer> findById(UUID customerId) {
        return Optional.ofNullable(customers.get(customerId));
    }
}
