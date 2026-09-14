package com.lewiswalker.savings.customer;

import com.lewiswalker.savings.customer.Customer.DueDiligence;

/**
 * Due diligence is not complete. Carries the stage; the API response must not.
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
