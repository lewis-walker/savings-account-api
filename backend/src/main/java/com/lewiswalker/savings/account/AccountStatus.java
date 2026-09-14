package com.lewiswalker.savings.account;

/**
 * Where an account sits in its lifecycle.
 *
 * <p>Only {@link #OPEN} occupies one of the customer's five slots, which is what the
 * partial unique index in V1__account.sql keys on. Nothing in this service moves an
 * account to {@link #CLOSED} — closing is not in scope — but the cap is enforced in
 * terms of this column so that adding it later is a new endpoint rather than a new
 * strategy for the cap.
 */
public enum AccountStatus {
    OPEN,
    CLOSED
}
