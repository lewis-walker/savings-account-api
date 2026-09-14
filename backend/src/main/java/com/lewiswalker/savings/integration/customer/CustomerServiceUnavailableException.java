package com.lewiswalker.savings.integration.customer;

/**
 * The customer master could not be reached. This exception implies
 * it is worth retrying
 **/
public class CustomerServiceUnavailableException extends RuntimeException {
    public CustomerServiceUnavailableException(String message) {
        super(message);
    }
}
