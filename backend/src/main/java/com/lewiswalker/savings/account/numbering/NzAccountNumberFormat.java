package com.lewiswalker.savings.account.numbering;

import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The New Zealand account number format, {@code BB-bbbb-AAAAAAA-SSS}, with the standard
 * modulus-11 check. The weight table below is the default factor, verified against a
 * published valid number (01-0902-0068389-000, whose weighted sum is 176 = 16 x 11).
 * Bank 99 is deliberately unregistered.
 *
 * <p>TODO: other banks use different weights and a modulus of 10, and bank 31 skips the
 * check. Only the default factor is implemented, which is the one our own numbers use.
 */
@Component
public class NzAccountNumberFormat {

    /** Not a registered NZ bank ID. See the class note. */
    static final String BANK_ID = "99";
    static final String BRANCH = "0001";

    /** Suffix denotes the product. Fixed, because this API only opens savings accounts. */
    static final String SUFFIX = "030";

    /**
     * The default NZ weight factor, one weight per digit of the 18-character check
     * string: bank(2) + branch(4) + account base left-padded to 8 + suffix padded to 4.
     */
    private static final int[] WEIGHTS = {
            0, 0,              // bank
            6, 3, 7, 9,        // branch
            0, 0, 10, 5, 8, 4, 2, 1,   // account base, left-padded to 8
            0, 0, 0, 0,        // suffix
    };
    private static final int MODULUS = 11;

    /** The default weight factor only applies below this; above it the branch digits drop out. */
    private static final int MAX_BASE_EXCLUSIVE = 990_000;

    /** Five digits of body, leaving the sixth to be solved as the check digit. */
    private static final long MAX_BODY_EXCLUSIVE = 99_000;

    /** @return the account number, or empty when the checksum solves to 10 */
    public Optional<String> format(long seed) {
        if (seed < 0) {
            throw new IllegalArgumentException("sequence value must not be negative");
        }
        if (seed >= MAX_BODY_EXCLUSIVE) {
            throw new IllegalStateException(
                    "account number range for branch %s-%s is exhausted at %d numbers"
                            .formatted(BANK_ID, BRANCH, MAX_BODY_EXCLUSIVE));
        }

        String body = "%05d".formatted(seed);

        // Position of the check digit carries weight 1, so the residue solves directly.
        int weightedSum = weightedSum(BANK_ID, BRANCH, "0" + body + "0", SUFFIX);
        int checkDigit = (MODULUS - (weightedSum % MODULUS)) % MODULUS;
        if (checkDigit == 10) {
            return Optional.empty();
        }

        String base = "0" + body + checkDigit;
        return Optional.of("%s-%s-%s-%s".formatted(BANK_ID, BRANCH, base, SUFFIX));
    }

    /** Whether the number is valid <em>under the default weight factor</em>. */
    public boolean isValidUnderDefaultFactor(String bank, String branch, String base,
                                             String suffix) {
        if (Integer.parseInt(base) >= MAX_BASE_EXCLUSIVE) {
            return false;   // a different factor applies above this
        }
        return weightedSum(bank, branch, base, suffix) % MODULUS == 0;
    }

    private static int weightedSum(String bank, String branch, String base, String suffix) {
        String checkString = pad(bank, 2) + pad(branch, 4) + pad(base, 8) + pad(suffix, 4);
        if (checkString.length() != WEIGHTS.length) {
            throw new IllegalArgumentException("account number does not fit the NZ format");
        }
        int sum = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            sum += WEIGHTS[i] * (checkString.charAt(i) - '0');
        }
        return sum;
    }

    private static String pad(String value, int width) {
        return "0".repeat(Math.max(0, width - value.length())) + value;
    }
}
