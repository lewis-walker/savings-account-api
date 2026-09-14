package com.lewiswalker.savings.account;

import org.hibernate.exception.ConstraintViolationException;

/**
 * Reads which database constraint a failure came from.
 *
 * <p>Two callers want it for different reasons. The writer decides from it whether the
 * failure is retryable. The handler logs it instead of the exception, whose message
 * quotes the failing row — customer name and all (PII).
 */
public final class ConstraintNames {

    private ConstraintNames() {}

    /** @return the constraint that fired, or null if the cause chain does not name one */
    public static String of(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return violation.getConstraintName();
            }
        }
        return null;
    }
}
