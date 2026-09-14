package com.lewiswalker.savings.platform.security;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The people who exist in the demo.
 *
 * <p>Deliberately not a database table. The brief asks for a single table, and more to
 * the point a bank account API does not own customer credentials — identity lives in
 * the enterprise identity provider (Entra, Okta, Ping, ForgeRock) and this service is
 * a resource server that validates tokens it did not issue. An in-memory store is not
 * a shortcut here; it is the honest shape, with the real thing named.
 *
 * <p>These same ids seed the customer directory, so a token's subject always resolves
 * to a customer the directory has heard of.
 */
public final class DemoIdentities {

    public record Identity(UUID customerId, String email, String fullName) {}

    public static final Identity ADA = new Identity(
            UUID.fromString("11111111-1111-4111-8111-111111111111"),
            "ada@example.test", "Ada Lovelace");

    public static final Identity GRACE = new Identity(
            UUID.fromString("22222222-2222-4222-8222-222222222222"),
            "grace@example.test", "Grace Hopper");

    /** Due diligence is incomplete for this one; the customer service reads that from here. */
    public static final Identity ALAN = new Identity(
            UUID.fromString("33333333-3333-4333-8333-333333333333"),
            "alan@example.test", "Alan Turing");

    private static final List<Identity> ALL = List.of(ADA, GRACE, ALAN);

    private DemoIdentities() {}

    public static List<Identity> all() {
        return ALL;
    }

    public static Optional<Identity> byEmail(String email) {
        return ALL.stream().filter(i -> i.email().equalsIgnoreCase(email)).findFirst();
    }

    public static Optional<Identity> byCustomerId(UUID customerId) {
        return ALL.stream().filter(i -> i.customerId().equals(customerId)).findFirst();
    }
}
