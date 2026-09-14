package com.lewiswalker.savings.integration.nickname;

public interface OffensiveNicknameChecker {

    /** @throws OffensiveNicknameException if the nickname is not acceptable. */
    void check(String nickname);
}
