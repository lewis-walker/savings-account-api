package com.lewiswalker.savings.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * A database constraint violation must not carry the failing row into the logs.
 *
 * <p>Postgres reports a violated constraint with {@code Detail: Failing row contains (...)}
 * listing every column — customer name and nickname included. Logging that exception puts
 * customer data into the application log, from there into the in-memory tail, and from
 * there onto an unauthenticated operations console, past every other control in this
 * service.
 *
 * <p>No Spring context: the handler has no collaborators, and the control being tested is
 * what it passes to the logger.
 */
class ApiExceptionHandlerTest {

    private static final String CUSTOMER_NAME = "Wilhelmina Featherstonehaugh";
    private static final String NICKNAME = "Sapphire holiday fund";
    private static final String ACCOUNT_NUMBER = "99-0001-0000117-030";

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    private Logger logger;
    private ListAppender<ILoggingEvent> captured;

    @BeforeEach
    void capture() {
        logger = (Logger) LoggerFactory.getLogger(ApiExceptionHandler.class);
        logger.setLevel(Level.TRACE);
        captured = new ListAppender<>();
        captured.start();
        logger.addAppender(captured);
    }

    @AfterEach
    void release() {
        logger.detachAppender(captured);
        logger.setLevel(null);
    }

    /** Shaped like the real thing, including the Detail line Postgres appends. */
    private static DataIntegrityViolationException violationCarryingCustomerData() {
        String message = "ERROR: new row for relation \"account\" violates check constraint "
                + "\"account_nickname_length\"\n  Detail: Failing row contains "
                + "(1e6f, " + ACCOUNT_NUMBER + ", 8c21, " + CUSTOMER_NAME + ", " + NICKNAME
                + ", 1, 0, 2026-09-14, 2026-09-14).";
        ConstraintViolationException cause = new ConstraintViolationException(
                message, new SQLException(message), "account_nickname_length");
        return new DataIntegrityViolationException("could not execute statement", cause);
    }

    @Test
    @DisplayName("the failing row never reaches the log — only the constraint name does")
    void constraintViolationDoesNotLogTheRow() {
        handler.constraintViolated(violationCarryingCustomerData());

        assertThat(captured.list).as("something was logged").isNotEmpty();
        for (ILoggingEvent event : captured.list) {
            String line = event.getFormattedMessage();
            assertThat(line).doesNotContain(CUSTOMER_NAME);
            assertThat(line).doesNotContain(NICKNAME);
            assertThat(line).doesNotContain(ACCOUNT_NUMBER);
            assertThat(line).doesNotContain("Failing row");
            // The exception itself must not be attached either: an appender that renders
            // throwables would print the same message the assertions above exclude.
            assertThat(event.getThrowableProxy())
                    .as("no throwable attached to the log event")
                    .isNull();
        }
        assertThat(captured.list.get(0).getFormattedMessage())
                .as("the constraint name is what makes the line useful")
                .contains("account_nickname_length");
    }

    @Test
    @DisplayName("the caller is told nothing about the constraint either")
    void theResponseDisclosesNothing() {
        ProblemDetail problem = handler.constraintViolated(violationCarryingCustomerData());

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        String body = problem.toString();
        assertThat(body).doesNotContain("account_nickname_length");
        assertThat(body).doesNotContain(CUSTOMER_NAME);
        // Not a retryable 503. The request will fail identically every time, and telling
        // the caller to try again would be wrong as well as useless.
        assertThat(problem.getProperties() == null
                || !problem.getProperties().containsKey("retryable")).isTrue();
    }

    @Test
    @DisplayName("an unnamed constraint still logs something useful")
    void unknownConstraintIsStillLogged() {
        handler.constraintViolated(new DataIntegrityViolationException("no cause chain here"));

        assertThat(captured.list).isNotEmpty();
        assertThat(captured.list.get(0).getFormattedMessage()).contains("unknown constraint");
    }
}
