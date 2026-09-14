package com.lewiswalker.savings.account;

import org.hibernate.exception.ConstraintViolationException;

/**
 * Reads which database constraint a failure came from.
 *
 * <p>Shared because two places need it and they need it for opposite reasons:
 * {@link AccountWriter} to decide whether to retry or refuse, and the exception handler to
 * report a failure <em>without</em> the exception it came from. A Postgres constraint
 * violation message includes {@code Detail: Failing row contains (...)} — every column
 * value, customer name included — so the name is the only part of it fit to be logged.
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
