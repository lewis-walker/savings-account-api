package com.lewiswalker.savings.account.api;

import com.lewiswalker.savings.account.AccountView;
import com.lewiswalker.savings.account.AccountService;
import com.lewiswalker.savings.account.AccountNotFoundException;
import com.lewiswalker.savings.account.OwnershipMismatchException;
import com.lewiswalker.savings.platform.audit.AuditLog;
import com.lewiswalker.savings.platform.idempotency.IdempotencyKey;
import com.lewiswalker.savings.platform.idempotency.IdempotencyStore;
import com.lewiswalker.savings.platform.idempotency.RequestFingerprint;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final AccountService accounts;
    private final IdempotencyStore idempotency;
    private final AuditLog auditLog;

    public AccountController(AccountService accounts, IdempotencyStore idempotency,
                             AuditLog auditLog) {
        this.accounts = accounts;
        this.idempotency = idempotency;
        this.auditLog = auditLog;
    }

    /**
     * 201 rather than 202: the account is committed before this returns.
     *
     * <p>{@code Idempotency-Key} is optional and honoured when present. A production API
     * would require it on unsafe methods.
     */
    @PostMapping
    public ResponseEntity<AccountResponse> open(
            @AuthenticationPrincipal Jwt caller,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false)
            @IdempotencyKey String idempotencyKey,
            @Valid @RequestBody OpenAccountRequest request) {
        UUID customerId = customerId(caller);
        return created(idempotency.performOnce(customerId, idempotencyKey,
                RequestFingerprint.of(customerId.toString(), request.nickname()),
                () -> accounts.open(customerId, request.nickname()).id(),
                id -> requireOwnedAccount(customerId, id)));
    }

    /**
     * The account behind an id this endpoint produced or replayed.
     *
     * <p>Deliberately not the quiet filter that {@link #get} uses. There, a mismatch is a
     * caller asking about an account that is not theirs, which is expected and answered
     * with a 404. Here the id came from an idempotency entry namespaced by this customer,
     * or from the write that had just created it, so a mismatch is an invariant failing:
     * recorded, and refused rather than answered.
     */
    private AccountView requireOwnedAccount(UUID customerId, UUID accountId) {
        AccountView account = accounts.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        if (!account.customerId().equals(customerId)) {
            auditLog.ownershipMismatch(customerId, account.customerId(), accountId);
            throw new OwnershipMismatchException(accountId);
        }
        return account;
    }

    private static ResponseEntity<AccountResponse> created(AccountView account) {
        return ResponseEntity
                .created(URI.create("/accounts/" + account.id()))
                .body(AccountResponse.of(account));
    }

    /** 404 rather than 403 for someone else's account: 403 confirms it exists. */
    @GetMapping("/{id}")
    public AccountResponse get(@AuthenticationPrincipal Jwt caller, @PathVariable UUID id) {
        return accounts.findById(id)
                .filter(account -> account.customerId().equals(customerId(caller)))
                .map(AccountResponse::of)
                .orElseThrow(() -> new AccountNotFoundException(id));
    }

    /** Beyond the brief's two operations; scoped by the token, so nothing to enumerate. */
    @GetMapping
    public List<AccountResponse> list(@AuthenticationPrincipal Jwt caller) {
        return accounts.findForCustomer(customerId(caller)).stream()
                .map(AccountResponse::of)
                .toList();
    }

    /** The only place customer identity comes from. */
    private static UUID customerId(Jwt caller) {
        return UUID.fromString(Objects.requireNonNull(caller.getSubject()));
    }
}
