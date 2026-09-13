package com.lewiswalker.savings.account;

import org.springframework.stereotype.Component;

/**
 * Turns a sequence value into a customer-facing account number.
 *
 * <p>Format is {@code BB-SSSSSSSSS-C}: a fixed institution prefix, nine digits from
 * the database sequence, and a Luhn check digit so a mistyped number is rejected
 * before it reaches the database.
 *
 * <p>TODO: a real institution allocates from a registered BSB/branch range and the
 * format is dictated by the domestic clearing scheme (NZ uses bank-branch-account-suffix).
 * The prefix here is a placeholder; the shape is the point.
 */
@Component
public class AccountNumberGenerator {

    private static final String INSTITUTION_PREFIX = "88";
    private static final int BODY_DIGITS = 9;

    public String generate(long seed) {
        if (seed < 0) {
            throw new IllegalArgumentException("account number seed must not be negative");
        }
        String body = "%0" + BODY_DIGITS + "d";
        body = body.formatted(seed % (long) Math.pow(10, BODY_DIGITS));
        String withoutCheck = INSTITUTION_PREFIX + body;
        return "%s-%s-%d".formatted(INSTITUTION_PREFIX, body, luhnCheckDigit(withoutCheck));
    }

    private static int luhnCheckDigit(String digits) {
        int sum = 0;
        boolean doubling = true;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubling) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubling = !doubling;
        }
        return (10 - (sum % 10)) % 10;
    }
}
