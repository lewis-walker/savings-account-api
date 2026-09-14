package com.lewiswalker.savings.web;

import com.lewiswalker.savings.account.AccountCapReachedException;
import com.lewiswalker.savings.account.AccountNotFoundException;
import com.lewiswalker.savings.account.ConstraintNames;
import com.lewiswalker.savings.account.numbering.AccountNumberAllocationException;
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
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every failure into {@code application/problem+json} (RFC 9457).
 *
 * <p>Nothing internal reaches the caller, and every response carries the correlation id.
 * See DECISIONS.md.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final String BASE = "https://savings-account-api.local/problems/";
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AccountCapReachedException.class)
    ProblemDetail accountCapReached(AccountCapReachedException e) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "account-limit-reached",
                "Account limit reached",
                "A customer may hold at most %d savings accounts.".formatted(e.getCap()));
        problem.setProperty("limit", e.getCap());
        return problem;
    }

    @ExceptionHandler(OffensiveNicknameException.class)
    ProblemDetail offensiveNickname(OffensiveNicknameException e) {
        // The nickname is not echoed back.
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "nickname-not-acceptable",
                "Nickname not acceptable",
                "That nickname cannot be used. Please choose another.");
    }

    @ExceptionHandler(CustomerNotVerifiedException.class)
    ProblemDetail customerNotVerified(CustomerNotVerifiedException e) {
        return cannotOpenAccount();
    }

    @ExceptionHandler(UnknownCustomerException.class)
    ProblemDetail unknownCustomer(UnknownCustomerException e) {
        // Same answer as an unverified customer, so the two cannot be told apart from
        // outside. AccountService has already recorded it.
        return cannotOpenAccount();
    }

    @ExceptionHandler(IdempotencyExceptions.KeyReused.class)
    ProblemDetail idempotencyKeyReused(IdempotencyExceptions.KeyReused e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "idempotency-key-reused",
                "Idempotency key reused",
                "This Idempotency-Key was already used for a different request. "
                        + "Use a new key, or resend the original request unchanged.");
    }

    @ExceptionHandler(IdempotencyExceptions.InProgress.class)
    ProblemDetail idempotencyInProgress(IdempotencyExceptions.InProgress e) {
        // Retryable, unlike the other 409 here: this one resolves on its own.
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "request-in-progress",
                "Request already in progress",
                "An identical request is still being processed. Please try again shortly.");
        problem.setProperty("retryable", true);
        return problem;
    }

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail accountNotFound(AccountNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "account-not-found", "Account not found",
                "No such account.");
    }

    @ExceptionHandler({AccountNumberAllocationException.class,
            CustomerDirectoryUnavailableException.class,
            IdempotencyExceptions.StoreUnavailable.class})
    ProblemDetail dependencyUnavailable(RuntimeException e) {
        log.error("a dependency was unavailable", e);
        return temporarilyUnavailable();
    }

    /**
     * A constraint fired that validation should have prevented.
     *
     * <p>The exception is never logged: a Postgres constraint violation carries
     * {@code Detail: Failing row contains (...)}, every column included.
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
        // TransactionException, not just DataAccessException: failing to get a connection
        // arrives as CannotCreateTransactionException, which is a sibling rather than a
        // subclass. The message is not passed through - it can carry SQL and row values.
        log.error("database access failed", e);
        return temporarilyUnavailable();
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException e, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-failed",
                "Validation failed", "One or more fields were not acceptable.");
        problem.setProperty("errors", e.getBindingResult().getFieldErrors().stream()
                .map(ApiExceptionHandler::describe)
                .toList());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    /**
     * The same document for a rejected header as for a rejected body field.
     *
     * <p>One constraint anywhere on a handler method routes that method's whole
     * validation through this exception rather than {@link MethodArgumentNotValidException},
     * so annotating the header brought {@code @Valid @RequestBody} here too. Both are
     * handled: a constrained parameter names itself, a body names its fields.
     */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException e, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-failed",
                "Validation failed", "One or more fields were not acceptable.");
        problem.setProperty("errors", e.getParameterValidationResults().stream()
                .flatMap(ApiExceptionHandler::describe)
                .toList());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    private static Stream<Map<String, String>> describe(ParameterValidationResult result) {
        if (result instanceof ParameterErrors body) {
            return body.getFieldErrors().stream().map(ApiExceptionHandler::describe);
        }
        return result.getResolvableErrors().stream()
                .map(error -> Map.of("field", nameOf(result.getMethodParameter()),
                        "message", String.valueOf(error.getDefaultMessage())));
    }

    /** The header as the caller spelled it, rather than the Java parameter name. */
    private static String nameOf(MethodParameter parameter) {
        RequestHeader header = parameter.getParameterAnnotation(RequestHeader.class);
        if (header != null && !header.value().isEmpty()) {
            return header.value();
        }
        return Optional.ofNullable(parameter.getParameterName()).orElse("request");
    }

    /**
     * Stamps the correlation id onto responses this class did not build — everything the
     * base class handles, including the 401 from the token endpoint.
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

    private ProblemDetail temporarilyUnavailable() {
        ProblemDetail problem = problem(HttpStatus.SERVICE_UNAVAILABLE, "temporarily-unavailable",
                "Temporarily unavailable",
                "This service is temporarily unable to complete your request. Please try again.");
        problem.setProperty("retryable", true);
        return problem;
    }

    private ProblemDetail cannotOpenAccount() {
        // The verification stage is deliberately not disclosed.
        return problem(HttpStatus.FORBIDDEN, "customer-not-verified", "Account cannot be opened",
                "This customer is not currently able to open accounts. Please contact us.");
    }

    /** The field and our own message. The rejected value is not reflected back. */
    private static Map<String, String> describe(FieldError error) {
        return Map.of("field", error.getField(),
                "message", String.valueOf(error.getDefaultMessage()));
    }

    private ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(BASE + type));
        problem.setTitle(title);
        problem.setDetail(detail);
        correlationId().ifPresent(id -> problem.setProperty("correlationId", id));
        return problem;
    }

    private static Optional<String> correlationId() {
        return Optional.ofNullable(MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}
