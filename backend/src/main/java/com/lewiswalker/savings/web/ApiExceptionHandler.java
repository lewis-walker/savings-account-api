package com.lewiswalker.savings.web;

import com.lewiswalker.savings.account.AccountCapReachedException;
import com.lewiswalker.savings.account.AccountNotFoundException;
import com.lewiswalker.savings.account.numbering.AccountNumberAllocationException;
import com.lewiswalker.savings.account.ConstraintNames;
import com.lewiswalker.savings.customer.CustomerDirectoryUnavailableException;
import com.lewiswalker.savings.customer.CustomerNotVerifiedException;
import com.lewiswalker.savings.customer.UnknownCustomerException;
import com.lewiswalker.savings.idempotency.IdempotencyExceptions;
import com.lewiswalker.savings.nickname.OffensiveNicknameException;
import com.lewiswalker.savings.observability.CorrelationIdFilter;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.transaction.TransactionException;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every failure into {@code application/problem+json} (RFC 9457, which obsoleted
 * RFC 7807), so the client parses one shape for validation errors, refusals and outages
 * alike.
 *
 * <p>Two rules matter more than the format. Nothing internal reaches the caller: a
 * database exception's message can carry SQL, schema names and the values being written.
 * And every problem document carries the correlation id, so a reference a customer quotes
 * leads to the log line without the response having carried anything sensitive.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String BASE = "https://savings-account-api.local/problems/";
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AccountCapReachedException.class)
    ProblemDetail accountCapReached(AccountCapReachedException e) {
        // 409 rather than 400: the request was well formed, and it conflicts with the
        // current state of the customer's holdings. The same request would have
        // succeeded earlier and may succeed again if an account is closed.
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "account-limit-reached",
                "Account limit reached",
                "A customer may hold at most %d savings accounts.".formatted(e.getCap()));
        problem.setProperty("limit", e.getCap());
        return problem;
    }

    @ExceptionHandler(OffensiveNicknameException.class)
    ProblemDetail offensiveNickname(OffensiveNicknameException e) {
        // 422 rather than 400: syntactically valid and semantically refused. The
        // nickname itself is never echoed back - repeating it serves no purpose and
        // puts caller-supplied text into another response.
        //
        // UNPROCESSABLE_CONTENT, not UNPROCESSABLE_ENTITY: RFC 9110 renamed 422 and
        // Spring Framework 7 deprecated the old constant. Same status code.
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "nickname-not-acceptable",
                "Nickname not acceptable",
                "That nickname cannot be used. Please choose another.");
    }

    @ExceptionHandler(CustomerNotVerifiedException.class)
    ProblemDetail customerNotVerified(CustomerNotVerifiedException e) {
        // The status - pending, expired - is deliberately not in the response. What
        // the bank believes about a customer's verification belongs in a channel with
        // a person in it.
        return problem(HttpStatus.FORBIDDEN, "customer-not-verified",
                "Account cannot be opened",
                "This customer is not currently able to open accounts. Please contact us.");
    }

    @ExceptionHandler(UnknownCustomerException.class)
    ProblemDetail unknownCustomer(UnknownCustomerException e) {
        // Not logged here. AccountService already recorded it at the point the
        // decision was made, and logging it again on the way out would produce two
        // records of one event - which is how counts stop matching.
        return problem(HttpStatus.FORBIDDEN, "customer-not-verified",
                "Account cannot be opened",
                "This customer is not currently able to open accounts. Please contact us.");
    }

    @ExceptionHandler(IdempotencyExceptions.KeyReused.class)
    ProblemDetail idempotencyKeyReused(IdempotencyExceptions.KeyReused e) {
        // 422: the key is syntactically fine and semantically refused. Not 409 - there is
        // no conflicting state, the caller has simply reused a key for something else.
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reused",
                "Idempotency key reused",
                "This Idempotency-Key was already used for a different request. "
                        + "Use a new key, or resend the original request unchanged.");
    }

    @ExceptionHandler(IdempotencyExceptions.InProgress.class)
    ProblemDetail idempotencyInProgress(IdempotencyExceptions.InProgress e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "request-in-progress",
                "Request already in progress",
                "An identical request is still being processed. Please try again shortly.");
        // Retryable, unlike the other 409 in this API. The account limit is a permanent
        // refusal; this one resolves on its own within moments.
        problem.setProperty("retryable", true);
        return problem;
    }

    @ExceptionHandler(IdempotencyExceptions.StoreUnavailable.class)
    ProblemDetail idempotencyStoreUnavailable(IdempotencyExceptions.StoreUnavailable e) {
        log.error("the idempotency store was unavailable", e);
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, "temporarily-unavailable",
                "Temporarily unavailable",
                "This service is temporarily unable to complete your request. Please try again.");
        problem.setProperty("retryable", true);
        return problem;
    }

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail accountNotFound(AccountNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "account-not-found",
                "Account not found",
                "No such account.");
    }

    @ExceptionHandler({AccountNumberAllocationException.class,
            CustomerDirectoryUnavailableException.class})
    ProblemDetail upstreamUnavailable(RuntimeException e) {
        log.error("an upstream dependency was unavailable", e);
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, "temporarily-unavailable",
                "Temporarily unavailable",
                "This service is temporarily unable to complete your request. Please try again.");
        problem.setProperty("retryable", true);
        return problem;
    }

    /**
     * A constraint fired that nothing upstream expected.
     *
     * <p>Separate from the outage case for two reasons. It is not an outage, so reporting
     * it as a retryable 503 tells the caller to repeat a request that will fail
     * identically every time.
     *
     * <p>And the exception must not be logged. A Postgres constraint violation carries
     * {@code Detail: Failing row contains (...)} — every column of the row, customer name
     * and nickname included — so handing it to a logger puts customer data into the log,
     * into the in-memory tail, and onto the operations console, past every other control
     * in this service. Only the constraint name is recorded here. The full detail is
     * already in the database server's own log, which is where it belongs.
     *
     * <p>Reaching this handler is a defect: validation should have refused the request at
     * the edge. It is reported as a 500 rather than dressed up as something the caller did
     * wrong, and logged at error so that somebody notices.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail constraintViolated(DataIntegrityViolationException e) {
        String constraint = ConstraintNames.of(e);
        log.error("a database constraint was violated that validation should have prevented: {}",
                constraint == null ? "unknown constraint" : constraint);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "request-could-not-be-completed",
                "Request could not be completed",
                "This request could not be completed. Please contact us if it continues.");
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    ProblemDetail databaseUnavailable(RuntimeException e) {
        // Both, and the second is the one that actually fires when Postgres is gone.
        // Failing to get a connection surfaces as CannotCreateTransactionException,
        // which extends TransactionException - a sibling of DataAccessException, not a
        // subclass. Handling only DataAccessException looks right, passes every test
        // that uses a working database, and produces a bare 500 the first time the
        // database is actually down. Found by stopping the container, not by reading.
        //
        // Anything reaching here was not a violation the service understood, so from
        // the caller's point of view it is infrastructure: 503, not 500. The message is not
        // passed through: it can carry SQL, schema names and the values being written.
        log.error("database access failed", e);
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, "temporarily-unavailable",
                "Temporarily unavailable",
                "This service is temporarily unable to complete your request. Please try again.");
        problem.setProperty("retryable", true);
        return problem;
    }

    /**
     * Field-level validation, in the same envelope as everything else.
     *
     * <p>Overriding the inherited handler rather than adding a second one, because
     * Spring already answers this with a ProblemDetail and the only thing missing is
     * which fields were wrong.
     */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-failed",
                "Validation failed", "One or more fields were not acceptable.");
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(this::describe)
                .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    private Map<String, String> describe(FieldError error) {
        // The field name and our own message only. The rejected value is not echoed:
        // it is caller-supplied content, and there is no reason to reflect it back.
        return Map.of(
                "field", error.getField(),
                "message", String.valueOf(error.getDefaultMessage()));
    }

    /**
     * Stamps the correlation id onto responses this class did not build.
     *
     * <p>Everything the base class handles — a malformed body, an unreadable request, an
     * unsupported method, and every {@code ResponseStatusException}, which includes the
     * 401 from the token endpoint — produces its own ProblemDetail and never passes
     * through {@link #problem}. Without this, the first error a reviewer triggers comes
     * back without the reference the README tells them to search for.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {

        ResponseEntity<Object> response =
                super.handleExceptionInternal(exception, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            correlationId().ifPresent(id -> problem.setProperty("correlationId", id));
        }
        return response;
    }

    private static Optional<String> correlationId() {
        return Optional.ofNullable(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    private ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(BASE + type));
        problem.setTitle(title);
        problem.setDetail(detail);
        // The bridge between a customer saying "I got an error" and the log line that
        // says why.
        correlationId().ifPresent(id -> problem.setProperty("correlationId", id));
        return problem;
    }
}
