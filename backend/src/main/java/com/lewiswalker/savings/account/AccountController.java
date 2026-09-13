package com.lewiswalker.savings.account;

import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    /**
     * Opens a savings account for the authenticated customer.
     *
     * <p>Answers 201 with a Location header. Not 202: the account is committed before
     * this returns. That distinction is the whole reason the optimistic frontend is
     * honest — it shows a pending row because the row is pending, and the server never
     * claims a durability it does not have. Were account numbers allocated by a core
     * banking platform over a batch window, this would become 202 and a pending
     * resource, and the frontend would not have to change.
     */
    @PostMapping
    public ResponseEntity<AccountResponse> open(@AuthenticationPrincipal Jwt caller,
                                                @Valid @RequestBody OpenAccountRequest request) {
        Account account = accounts.open(customerId(caller), request.nickname());
        return ResponseEntity
                .created(URI.create("/accounts/" + account.getId()))
                .body(AccountResponse.of(account));
    }

    /**
     * One account belonging to the authenticated customer.
     *
     * <p>The ownership check is not a nicety. Without it this is a textbook insecure
     * direct object reference: any authenticated customer could read any account by
     * guessing an identifier. The identifier is a UUID, which makes guessing
     * impractical, but "hard to guess" is not an authorisation control.
     */
    @GetMapping("/{id}")
    public AccountResponse get(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return accounts.findById(id)
                .filter(account -> account.getCustomerId().equals(customerId(caller)))
                .map(AccountResponse::of)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }

    /**
     * Every account belonging to the authenticated customer.
     *
     * <p>Beyond the two operations the brief names. It is here because the cap of five
     * accounts is not demonstrable without it, and because a customer being unable to
     * see their own accounts would be a strange product. No identifier is accepted:
     * the list is scoped by the token, so there is nothing here to enumerate.
     */
    @GetMapping
    public List<AccountResponse> list(@AuthenticationPrincipal Jwt caller) {
        return accounts.findForCustomer(customerId(caller)).stream()
                .map(AccountResponse::of)
                .toList();
    }

    /** The subject claim, which is the only place customer identity comes from. */
    private static UUID customerId(Jwt caller) {
        return UUID.fromString(caller.getSubject());
    }
}
