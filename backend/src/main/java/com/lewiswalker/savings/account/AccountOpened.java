package com.lewiswalker.savings.account;

/** Published inside the opening transaction; delivered only if it commits. */
public record AccountOpened(AccountView account) {
}
