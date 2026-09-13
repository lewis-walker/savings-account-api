package com.lewiswalker.savings.web;

import com.lewiswalker.savings.account.AccountCapReachedException;
import com.lewiswalker.savings.account.AccountNotFoundException;
import com.lewiswalker.savings.account.AccountNumberAllocationException;
import com.lewiswalker.savings.customer.CustomerDirectoryUnavailableException;
import com.lewiswalker.savings.customer.CustomerNotVerifiedException;
import com.lewiswalker.savings.customer.UnknownCustomerException;
import com.lewiswalker.savings.nickname.OffensiveNicknameException;
import com.lewiswalker.savings.observability.CorrelationIdFilter;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataAccessException;
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
 * Turns every failure into RFC 7807 {@code application/problem+json}.
 *
 * <p>One shape for every error means the frontend parses one thing. Field-level
 * validation arrives in the same envelope as a refused nickname and a database outage,
 * so there is no bespoke branch per failure mode.
 *
 * <h2>Two rules that matter more than the shape</h2>
 *
 * <p><b>Nothing internal reaches the caller.</b> A database exception's message can
 * carry schema names, SQL, and the values that were being written. Every handler here
 * emits a fixed, safe string; the real exception goes to the log.
 *
 * <p><b>The correlation id is the bridge.</b> It is on the problem response and on the
 * log line, so a customer quoting their reference lets support find the exact failure
 * without the response ever having carried anything sensitive. That is the whole reason
 * the correlation id exists, rather than being decoration.
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
        // A validly signed token whose subject is not a customer. Configuration or
        // lifecycle, not user error, so it is logged at warn - nobody will notice this
        // from the response alone.
        log.warn("token subject does not resolve to a customer", e);
        return problem(HttpStatus.FORBIDDEN, "customer-not-verified",
                "Account cannot be opened",
                "This customer is not currently able to open accounts. Please contact us.");
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
        // the caller's point of view it is infrastructure: 503, not 500. The message is
        // emphatically not passed through - it can carry SQL, schema names and the
        // values being written.
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

    private ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(BASE + type));
        problem.setTitle(title);
        problem.setDetail(detail);
        // The bridge between a customer saying "I got an error" and the log line that
        // says why.
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
        return problem;
    }
}
