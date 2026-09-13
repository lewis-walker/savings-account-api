package com.lewiswalker.savings.customer;

import com.lewiswalker.savings.security.DemoIdentities;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
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

    @Override
    public Optional<Customer> findById(UUID customerId) {
        return Optional.ofNullable(customers.get(customerId));
    }
}
