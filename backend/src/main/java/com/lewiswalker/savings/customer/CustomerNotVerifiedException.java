package com.lewiswalker.savings.customer;

import com.lewiswalker.savings.customer.Customer.DueDiligence;

/**
 * The customer's due diligence is not complete, so no account may be opened.
 *
 * <p>Carries the status but the API response must not: telling a caller whether
 * verification is pending or expired is information about the bank's assessment of a
 * customer, and it belongs in a channel with a person in it, not an error body.
 */
public class CustomerNotVerifiedException extends RuntimeException {

    private final DueDiligence dueDiligence;

    public CustomerNotVerifiedException(DueDiligence dueDiligence) {
        super("customer due diligence is " + dueDiligence);
        this.dueDiligence = dueDiligence;
    }

    public DueDiligence getDueDiligence() {
        return dueDiligence;
    }
}
