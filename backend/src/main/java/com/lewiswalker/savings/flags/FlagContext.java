package com.lewiswalker.savings.flags;

import java.util.UUID;

/**
 * Who a flag is being evaluated for.
 *
 * <p>This is the thing that makes a feature flag different from a configuration
 * property, and it is the part people leave out. A property has one value for the whole
 * deployment. A flag is evaluated per request against a context, so the same build can
 * have a feature on for internal staff, on for two percent of customers, and off for
 * everyone else — which is what makes a progressive rollout and an instant, targeted
 * rollback possible.
 *
 * <p>The key is the customer id and not a name or an email. A flag service is a third
 * party: whatever is put in a context is sent to it, indexed by it, and shown in its
 * dashboard. Sending personal data to a SaaS vendor because it was convenient for
 * targeting is a real way banks end up in trouble, and an opaque identifier targets just
 * as precisely.
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
