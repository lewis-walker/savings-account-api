package com.lewiswalker.savings.integration.customer;

/**
 * The customer master could not be reached. Distinct from "no such customer": absent is
 * a final answer, unavailable is not.
 */
public class CustomerServiceUnavailableException extends RuntimeException {
    public CustomerServiceUnavailableException(String message) {
        super(message);
    }
}
