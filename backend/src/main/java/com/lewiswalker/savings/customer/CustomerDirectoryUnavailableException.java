package com.lewiswalker.savings.customer;

/**
 * The customer master could not be reached. Distinct from "no such customer": absent is
 * a final answer, unavailable is not.
 */
public class CustomerDirectoryUnavailableException extends RuntimeException {

    public CustomerDirectoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public CustomerDirectoryUnavailableException(String message) {
        super(message);
    }
}
