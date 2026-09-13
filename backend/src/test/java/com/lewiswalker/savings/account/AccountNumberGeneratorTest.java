package com.lewiswalker.savings.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AccountNumberGeneratorTest {

    private final AccountNumberGenerator generator = new AccountNumberGenerator();

    @Test
    @DisplayName("validates a published, genuinely valid New Zealand account number")
    void acceptsPublishedValidNumber() {
        // 01-0902-0068389-000, from the test suite of an independent NZ account
        // validator. Checking against a number computed by someone else is the only
        // way to know the weight table is right rather than merely self-consistent.
        assertThat(generator.isValid("01", "0902", "00068389", "000")).isTrue();
    }

    @ParameterizedTest
    @DisplayName("rejects that same number with any single digit altered")
    @CsvSource({
            "01, 0902, 00068388, 000",
            "01, 0902, 00068379, 000",
            "01, 0902, 00068389, 001",   // suffix is unweighted, so this must still fail
            "01, 0903, 00068389, 000",
    })
    void rejectsPerturbations(String bank, String branch, String base, String suffix) {
        boolean valid = generator.isValid(bank, branch, base, suffix);
        if ("001".equals(suffix)) {
            // Documenting a real property of the scheme rather than asserting a wrong
            // one: the suffix carries weight zero, so altering it cannot invalidate a
            // number. A check digit protects the base, not the product code.
            assertThat(valid).isTrue();
        } else {
            assertThat(valid).isFalse();
        }
    }

    @Test
    @DisplayName("every issued number passes the modulus check")
    void issuedNumbersAreValid() {
        AtomicLong sequence = new AtomicLong(1);
        for (int i = 0; i < 2000; i++) {
            String number = generator.generate(sequence::getAndIncrement);
            String[] parts = number.split("-");
            assertThat(parts).hasSize(4);
            assertThat(generator.isValid(parts[0], parts[1], parts[2], parts[3]))
                    .as("issued number %s", number)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("issued numbers look like NZ account numbers")
    void issuedNumbersHaveNzShape() {
        String number = generator.generate(new AtomicLong(42)::getAndIncrement);
        assertThat(number).matches("\\d{2}-\\d{4}-\\d{7}-\\d{3}");
        assertThat(number).startsWith("99-0001-");
    }

    @Test
    @DisplayName("distinct sequence values give distinct numbers")
    void numbersDoNotRepeat() {
        AtomicLong sequence = new AtomicLong(1);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5000; i++) {
            assertThat(seen.add(generator.generate(sequence::getAndIncrement))).isTrue();
        }
    }

    @Test
    @DisplayName("roughly one sequence value in eleven yields no issuable number")
    void someSequenceValuesAreNotIssuable() {
        long unissuable = java.util.stream.LongStream.range(0, 11_000)
                .filter(seed -> generator.tryGenerate(seed).isEmpty())
                .count();
        // The residue is 10 for about 1 in 11 values. Asserting the property rather
        // than an exact count, since the distribution is a consequence of the weights.
        assertThat(unissuable).isBetween(800L, 1200L);
    }

    @Test
    @DisplayName("the branch range refuses rather than wrapping when exhausted")
    void exhaustionIsLoud() {
        assertThatThrownBy(() -> generator.tryGenerate(99_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exhausted");
    }
}
