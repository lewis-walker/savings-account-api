package com.lewiswalker.savings.customer;

/**
 * The customer master could not be reached.
 *
 * <p>Deliberately distinct from "no such customer". Absent means the bank has no record
 * and the answer will not change; unavailable means we do not know, and the caller may
 * usefully try again. Collapsing the two would let a network blip look like a customer
 * who does not exist.
 */
public class CustomerDirectoryUnavailableException extends RuntimeException {

    public CustomerDirectoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public CustomerDirectoryUnavailableException(String message) {
        super(message);
    }
}
