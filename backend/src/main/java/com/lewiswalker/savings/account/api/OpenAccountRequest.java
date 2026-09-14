package com.lewiswalker.savings.account.api;

/**
 * The body of a request to open an account: a nickname and nothing else. The customer's
 * name and id come from the verified record and the token; see DECISIONS.md. Unknown
 * properties are rejected, so a client sending {@code customerName} is told so.
 */
public record OpenAccountRequest(

        // Null passes, so the nickname stays optional. Not @Size - see NicknameLength.
        @NicknameLength(min = 5, max = 30,
                message = "must be between 5 and 30 characters")
        String nickname) {
}
