package com.lewiswalker.savings.integration.numbering;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class NzAccountNumberFormatTest {

    private final NzAccountNumberFormat format = new NzAccountNumberFormat();

    @Test
    @DisplayName("validates a published, genuinely valid New Zealand account number")
    void acceptsPublishedValidNumber() {
        // 01-0902-0068389-000, from the test suite of an independent NZ account
        // validator. Checking against a number computed by someone else is the only
        // way to know the weight table is right rather than merely self-consistent.
        assertThat(format.isValidUnderDefaultFactor("01", "0902", "00068389", "000")).isTrue();
    }

    @ParameterizedTest
    @DisplayName("altering a weighted digit invalidates the number")
    @CsvSource({
            "01, 0902, 00068388, 000",
            "01, 0902, 00068379, 000",
            "01, 0903, 00068389, 000",
    })
    void rejectsAlteredWeightedDigits(String bank, String branch, String base, String suffix) {
        assertThat(format.isValidUnderDefaultFactor(bank, branch, base, suffix)).isFalse();
    }

    @Test
    @DisplayName("altering the suffix does not, because the suffix is unweighted")
    void suffixIsNotCoveredByTheCheckDigit() {
        // A property of the scheme rather than a defect: the check digit protects the
        // account base, not the product code. Asserted so that nobody later "fixes" the
        // weight table to make a suffix change fail.
        assertThat(format.isValidUnderDefaultFactor("01", "0902", "00068389", "000")).isTrue();
        assertThat(format.isValidUnderDefaultFactor("01", "0902", "00068389", "001")).isTrue();
    }

    @Test
    @DisplayName("every issued number passes the modulus check")
    void issuedNumbersAreValid() {
        for (long seed = 1; seed < 2000; seed++) {
            String number = format.format(seed).orElse(null);
            if (number == null) {
                continue;   // not issuable; see the format contract
            }
            String[] parts = number.split("-");
            assertThat(parts).hasSize(4);
            assertThat(format.isValidUnderDefaultFactor(parts[0], parts[1], parts[2], parts[3]))
                    .as("issued number %s", number)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("issued numbers look like NZ account numbers")
    void issuedNumbersHaveNzShape() {
        String number = format.format(42).orElseThrow();
        assertThat(number).matches("\\d{2}-\\d{4}-\\d{7}-\\d{3}");
        assertThat(number).startsWith("99-0001-");
    }

    @Test
    @DisplayName("distinct sequence values give distinct numbers")
    void numbersDoNotRepeat() {
        Set<String> seen = new HashSet<>();
        for (long seed = 1; seed < 5000; seed++) {
            format.format(seed).ifPresent(number ->
                    assertThat(seen.add(number)).as("duplicate %s", number).isTrue());
        }
    }

    @Test
    @DisplayName("roughly one sequence value in eleven yields no issuable number")
    void someSequenceValuesAreNotIssuable() {
        long unissuable = java.util.stream.LongStream.range(0, 11_000)
                .filter(seed -> format.format(seed).isEmpty())
                .count();
        // The residue is 10 for about 1 in 11 values. Asserting the property rather
        // than an exact count, since the distribution is a consequence of the weights.
        assertThat(unissuable).isBetween(800L, 1200L);
    }

    @Test
    @DisplayName("the branch range refuses rather than wrapping when exhausted")
    void exhaustionIsLoud() {
        // Refusing matters more than the type: silently wrapping would reissue numbers
        // that are already in use, and the unique constraint would then reject perfectly
        // ordinary requests for reasons nobody could explain.
        assertThatThrownBy(() -> format.format(99_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exhausted");
    }
}
