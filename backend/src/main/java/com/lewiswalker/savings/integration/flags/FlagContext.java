package com.lewiswalker.savings.integration.flags;

import java.util.UUID;

/**
 * Who a flag is evaluated for - the thing that makes a flag different from a
 * configuration property.
 *
 * <p>The key is an opaque id. Everything here is sent to a third party and appears in
 * their dashboard.
 */
public record FlagContext(String key) {

    private static final FlagContext ANONYMOUS = new FlagContext("anonymous");

    public static FlagContext anonymous() {
        return ANONYMOUS;
    }

    public static FlagContext customer(UUID customerId) {
        return new FlagContext(customerId.toString());
    }
}
