package com.lewiswalker.savings.integration.customer;

import com.lewiswalker.savings.integration.customer.Customer.DueDiligence;

/**
 * Due diligence is not complete. The stage reaches the message, which is logged; the
 * response tells the caller only that the account cannot be opened.
 */
public class CustomerNotVerifiedException extends RuntimeException {

    public CustomerNotVerifiedException(DueDiligence dueDiligence) {
        super("customer due diligence is " + dueDiligence);
    }
}
