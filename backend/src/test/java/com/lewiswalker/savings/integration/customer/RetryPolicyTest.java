package com.lewiswalker.savings.integration.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lewiswalker.savings.ResilienceConfig;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.resilience.annotation.Retryable;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/**
 * The retry policy, proved rather than asserted in a comment.
 *
 * <p>No database and no web layer — this is about the proxy and the policy, so the
 * context holds only what that needs.
 */
@SpringJUnitConfig(classes = {ResilienceConfig.class, RetryPolicyTest.Config.class})
class RetryPolicyTest {

    @Autowired
    private CustomerDirectory directory;

    @BeforeEach
    void reset() {
        Flaky.attempts.set(0);
        Flaky.failuresBeforeSuccess = 0;
        Flaky.permanentFailure = false;
    }

    @Test
    @DisplayName("a transient failure is retried and the call succeeds")
    void retriesTransientFailure() {
        Flaky.failuresBeforeSuccess = 2;

        long startedAt = System.nanoTime();
        Optional<Customer> customer = directory.findById(UUID.randomUUID());
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(customer).isPresent();
        assertThat(Flaky.attempts).hasValue(3);   // the original plus two retries
        // It backed off rather than hammering: two waits of at least the base delay.
        assertThat(elapsedMillis).as("elapsed across two backoffs").isGreaterThanOrEqualTo(40L);
    }

    @Test
    @DisplayName("retries are bounded — it gives up rather than trying forever")
    void givesUpAfterMaxRetries() {
        Flaky.failuresBeforeSuccess = 99;

        // The specific type, not Throwable - assertThatThrownBy already guarantees a
        // Throwable, so that assertion was a tautology. This pins the type the 503
        // mapping in ApiExceptionHandler depends on, which nothing else in the suite does.
        assertThatThrownBy(() -> directory.findById(UUID.randomUUID()))
                .isInstanceOf(CustomerDirectoryUnavailableException.class);

        // One original attempt plus maxRetries. Unbounded retry against a dependency
        // that is genuinely down is how one outage becomes two.
        assertThat(Flaky.attempts).hasValue(3);
    }

    @Test
    @DisplayName("a permanent failure is not retried at all")
    void doesNotRetryPermanentFailure() {
        Flaky.permanentFailure = true;

        assertThatThrownBy(() -> directory.findById(UUID.randomUUID()))
                .isInstanceOf(UnknownCustomerException.class);

        // The single most common way retry is got wrong. The bank has no such customer
        // and will still have no such customer in two hundred milliseconds; retrying
        // spends the budget and delays an answer that was already final.
        assertThat(Flaky.attempts).as("attempts for a permanent failure").hasValue(1);
    }

    @Configuration
    static class Config {
        /**
         * Note the return type is the concrete class, not the interface, and that is
         * not incidental. Spring decides whether a bean needs a retry proxy by looking at
         * the bean's type — for an @Bean method that is the <em>declared return
         * type</em>. Declare it as CustomerDirectory and Spring inspects the interface,
         * finds no @Retryable on it, and quietly creates no proxy: the annotation is
         * simply ignored and the method runs once. Nothing fails, nothing warns.
         *
         * <p>The production adapters are @Component classes, so their concrete type is
         * always known and the question does not arise. It arises here, which is why it
         * is worth a comment rather than a silent fix.
         */
        @Bean
        Flaky customerDirectory() {
            return new Flaky();
        }
    }

    /** Fails on demand, counting how many times it was actually called. */
    static class Flaky implements CustomerDirectory {

        static final AtomicInteger attempts = new AtomicInteger();
        static volatile int failuresBeforeSuccess = 0;
        static volatile boolean permanentFailure = false;

        @Retryable(
                includes = CustomerDirectoryUnavailableException.class,
                maxRetries = 2, delay = 30, jitter = 10, multiplier = 2.0, maxDelay = 200,
                timeout = 2000)
        @Override
        public Optional<Customer> findById(UUID customerId) {
            int attempt = attempts.incrementAndGet();
            if (permanentFailure) {
                throw new UnknownCustomerException(customerId);
            }
            if (attempt <= failuresBeforeSuccess) {
                throw new CustomerDirectoryUnavailableException("simulated outage");
            }
            return Optional.of(new Customer(customerId, "Test Person",
                    Customer.DueDiligence.COMPLETE));
        }
    }
}
