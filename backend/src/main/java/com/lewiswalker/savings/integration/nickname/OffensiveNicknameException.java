package com.lewiswalker.savings.integration.nickname;

/** The supplied nickname matched the blocked list. */
public class OffensiveNicknameException extends RuntimeException {

    public OffensiveNicknameException() {
        super("nickname is not acceptable");
    }
}
