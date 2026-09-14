package com.lewiswalker.savings.integration.nickname;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

/** Plain unit test — the checker has no reason to need a Spring context or a database. */
class OffensiveNicknameCheckerTest {

    private final OffensiveNicknameChecker checker =
            new ResourceOffensiveNicknameChecker(new ClassPathResource("offensive-nicknames.txt"));

    @ParameterizedTest
    @DisplayName("blocked words are caught through casing, spacing, punctuation and leetspeak")
    @ValueSource(strings = {
            "badword",
            "BadWord",
            "B a d w o r d",
            "b.a.d.w.o.r.d",
            "b4dw0rd",
            "my badword account",
            "  OFFENSIVE  ",
    })
    void rejectsEvasions(String nickname) {
        assertThatThrownBy(() -> checker.check(nickname))
                .isInstanceOf(OffensiveNicknameException.class);
    }

    @ParameterizedTest
    @DisplayName("ordinary nicknames pass")
    @ValueSource(strings = {
            "Holiday fund",
            "House deposit",
            "Rainy day",
            "Emergency savings",
    })
    void acceptsOrdinaryNicknames(String nickname) {
        assertThatCode(() -> checker.check(nickname)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("an absent nickname is not an offensive one")
    void absentNicknameIsFine() {
        assertThatCode(() -> checker.check(null)).doesNotThrowAnyException();
        assertThatCode(() -> checker.check("   ")).doesNotThrowAnyException();
    }
}
