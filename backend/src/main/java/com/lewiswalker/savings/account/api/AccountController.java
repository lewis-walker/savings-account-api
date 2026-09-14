package com.lewiswalker.savings.account.api;

import com.lewiswalker.savings.account.AccountView;
import com.lewiswalker.savings.account.AccountService;
import com.lewiswalker.savings.account.AccountNotFoundException;
import com.lewiswalker.savings.idempotency.IdempotencyExceptions;
import com.lewiswalker.savings.idempotency.IdempotencyKey;
import com.lewiswalker.savings.idempotency.IdempotencyRecord;
import com.lewiswalker.savings.idempotency.IdempotencyStore;
import com.lewiswalker.savings.idempotency.RequestFingerprint;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.Optional;
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

    public AccountController(AccountService accounts, IdempotencyStore idempotency) {
        this.accounts = accounts;
        this.idempotency = idempotency;
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

        if (idempotencyKey == null) {
            return created(accounts.open(customerId, request.nickname()));
        }
        String fingerprint = RequestFingerprint.of(customerId.toString(), request.nickname());
        Optional<IdempotencyRecord> existing =
                idempotency.claim(customerId, idempotencyKey, fingerprint);

        if (existing.isPresent()) {
            return replay(existing.get(), idempotencyKey, fingerprint, customerId);
        }

        AccountView account;
        try {
            account = accounts.open(customerId, request.nickname());
        } catch (RuntimeException e) {
            // Nothing committed, so a genuine retry should not be locked out.
            idempotency.release(customerId, idempotencyKey);
            throw e;
        }
        idempotency.complete(customerId, idempotencyKey, fingerprint, account.id());
        return created(account);
    }

    /** Rebuilt from the stored account id, so the record stays an identifier. */
    private ResponseEntity<AccountResponse> replay(IdempotencyRecord record, String key,
                                                   String fingerprint, UUID customerId) {
        if (!record.matches(fingerprint)) {
            throw new IdempotencyExceptions.KeyReused(key);
        }
        if (record.state() == IdempotencyRecord.State.IN_PROGRESS) {
            throw new IdempotencyExceptions.InProgress(key);
        }
        return accounts.findById(UUID.fromString(record.accountId()))
                .filter(account -> account.customerId().equals(customerId))
                .map(account -> ResponseEntity
                        .created(URI.create("/accounts/" + account.id()))
                        .body(AccountResponse.of(account)))
                // Unreachable - nothing deletes accounts - but better than replaying a 201.
                .orElseThrow(() -> new AccountNotFoundException(UUID.fromString(record.accountId())));
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
        return UUID.fromString(caller.getSubject());
    }
}
