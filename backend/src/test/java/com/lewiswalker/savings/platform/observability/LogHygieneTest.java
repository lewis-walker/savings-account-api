package com.lewiswalker.savings.platform.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.lewiswalker.savings.TestcontainersConfiguration;
import com.lewiswalker.savings.support.StubCustomerService;
import com.lewiswalker.savings.account.AccountView;
import com.lewiswalker.savings.account.AccountCapReachedException;
import com.lewiswalker.savings.account.AccountService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Asserts that customer data does not reach the logs.
 *
 * <p>A README promising log hygiene is worth nothing six months later, when someone
 * adds a helpful {@code log.info("opening {}", request)} during a debugging session.
 * This is the same claim as an executable control.
 *
 * <p>Everything is captured at DEBUG on the root logger, so the assertion covers not
 * only our own logging but Hibernate's and Spring's — which is where the real risk
 * sits, since Hibernate will happily print bind parameters if configured to.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, StubCustomerService.class})
class LogHygieneTest {

    /**
     * The name now arrives from the customer directory rather than the request, so it
     * is taken from the stub that supplies it — asserting on a hard-coded copy would
     * silently stop testing anything the moment the stub changed.
     */
    private static final String CUSTOMER_NAME = StubCustomerService.ANY_CUSTOMER_NAME;
    private static final String NICKNAME = "Sapphire holiday fund";

    @Autowired
    private AccountService accountService;

    private Logger root;
    private ListAppender<ILoggingEvent> captured;
    private Level originalLevel;

    @BeforeEach
    void captureLogs() {
        root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        originalLevel = root.getLevel();
        // Root at DEBUG is the incident scenario: someone turns logging up in
        // production to chase a problem. The application's own loggers go to TRACE,
        // because our code has no excuse at any level. Third-party loggers keep
        // whatever application.yaml pins them to - which is the control under test.
        root.setLevel(Level.DEBUG);
        ((Logger) LoggerFactory.getLogger("com.lewiswalker.savings")).setLevel(Level.TRACE);
        captured = new ListAppender<>();
        captured.start();
        root.addAppender(captured);
    }

    @AfterEach
    void releaseLogs() {
        root.detachAppender(captured);
        root.setLevel(originalLevel);
        ((Logger) LoggerFactory.getLogger("com.lewiswalker.savings")).setLevel(null);
    }

    @Test
    @DisplayName("opening an account logs no customer name, nickname or account number")
    void openingAnAccountLeaksNothing() {
        UUID customerId = UUID.randomUUID();

        AccountView account = accountService.open(customerId, NICKNAME);

        assertNothingLoggedContains(CUSTOMER_NAME, NICKNAME, account.accountNumber());
    }

    @Test
    @DisplayName("the refusal path logs no customer data either")
    void refusalLeaksNothing() {
        UUID customerId = UUID.randomUUID();
        String accountNumber = null;
        for (int i = 0; i < AccountService.ACCOUNTS_PER_CUSTOMER; i++) {
            accountNumber = accountService.open(customerId, NICKNAME).accountNumber();
        }
        try {
            accountService.open(customerId, NICKNAME);
        } catch (AccountCapReachedException expected) {
            // the exception path is exactly where careless logging tends to appear
        }

        assertNothingLoggedContains(CUSTOMER_NAME, NICKNAME, accountNumber);
    }

    private void assertNothingLoggedContains(String... forbidden) {
        List<ILoggingEvent> events = List.copyOf(captured.list);

        // Guards against the test passing because nothing was logged at all.
        assertThat(events).as("log events captured").isNotEmpty();

        for (ILoggingEvent event : events) {
            String line = event.getFormattedMessage();
            String thrown = event.getThrowableProxy() == null
                    ? "" : String.valueOf(event.getThrowableProxy().getMessage());
            for (String secret : forbidden) {
                if (secret == null) {
                    continue;
                }
                assertThat(line).as("log message from %s", event.getLoggerName()).doesNotContain(secret);
                assertThat(thrown).as("throwable from %s", event.getLoggerName()).doesNotContain(secret);
            }
        }
    }
}
