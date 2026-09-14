package com.lewiswalker.savings.support;

import com.lewiswalker.savings.integration.customer.Customer;
import com.lewiswalker.savings.integration.customer.CustomerService;
import java.util.Optional;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * A customer master that knows everybody.
 *
 * <p>Tests about the account cap should fail when the cap is wrong, not when a randomly
 * generated customer id is not in the demo seed. Substituting the port keeps each test
 * about one thing, which is most of what the port is for.
 */
@TestConfiguration
public class StubCustomerService {

    /** Distinctive enough that finding it in a log file cannot be a coincidence. */
    public static final String ANY_CUSTOMER_NAME = "Wilhelmina Featherstonehaugh";

    @Bean
    @Primary
    CustomerService stubCustomerService() {
        return customerId -> Optional.of(
                new Customer(customerId, ANY_CUSTOMER_NAME, Customer.DueDiligence.COMPLETE));
    }
}
