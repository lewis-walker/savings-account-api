package com.lewiswalker.savings.account;

import org.hibernate.exception.ConstraintViolationException;

/**
 * Reads which database constraint a failure came from. Shared because the writer needs
 * it to decide whether to retry, and the exception handler needs it to report a failure
 * without the exception, whose message carries the whole failing row.
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
