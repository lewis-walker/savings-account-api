package com.lewiswalker.savings.account;

import java.util.UUID;

/**
 * Another request took the slot this one was offered.
 *
 * <p>Transient and internal — it never reaches a client. {@link AccountService}
 * catches it and asks for a slot again, which now sees the winner's committed row.
 */
public class SlotContendedException extends RuntimeException {

    public SlotContendedException(UUID customerId, short slotNo, Throwable cause) {
        super("slot %d for customer %s was taken concurrently".formatted(slotNo, customerId), cause);
    }
}
