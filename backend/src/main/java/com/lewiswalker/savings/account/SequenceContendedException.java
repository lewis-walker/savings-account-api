package com.lewiswalker.savings.account;

import java.util.UUID;

/**
 * Another request claimed this customer's next sequence number first.
 *
 * <p>Transient and internal — it never reaches a client. {@link AccountService}
 * catches it and tries again with a freshly read sequence number.
 */
public class SequenceContendedException extends RuntimeException {

    public SequenceContendedException(UUID customerId, short sequenceNo, Throwable cause) {
        super("sequence %d for customer %s was taken concurrently".formatted(sequenceNo, customerId), cause);
    }
}
