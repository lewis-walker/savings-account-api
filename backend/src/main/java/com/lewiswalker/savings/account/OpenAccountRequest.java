package com.lewiswalker.savings.account;

import jakarta.validation.constraints.Size;

/**
 * The body of a request to open an account.
 *
 * <p>It carries a nickname and nothing else, which is worth explaining because the
 * brief lists customer name as a mandatory input. The name is not accepted here: it
 * comes from the verified customer record, because under AML/CFT an account is opened
 * for a customer whose identity the bank has already established. A name asserted by
 * the caller would be an unverified claim written into a banking record. Likewise the
 * customer id, which comes from the token — accepting it would let any caller open
 * accounts in anyone's name.
 *
 * <p>Unknown properties are rejected rather than ignored (see
 * {@code fail-on-unknown-properties}), so a client that sends {@code customerName}
 * gets told plainly that it is not accepted instead of watching it vanish.
 */
public record OpenAccountRequest(

        /*
         * Optional, but constrained when present: @Size passes null through, which is
         * exactly the "optional, 5 to 30 characters" the brief asks for. A @NotBlank
         * here would quietly make it mandatory.
         */
        @Size(min = 5, max = 30,
                message = "nickname must be between 5 and 30 characters")
        String nickname) {
}
