package com.lewiswalker.savings.account;

import com.lewiswalker.savings.idempotency.IdempotencyExceptions;
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
     * Opens a savings account for the authenticated customer.
     *
     * <p>Answers 201 with a Location header. Not 202: the account is committed before this
     * returns. That distinction is what lets the optimistic front end stay honest — it
     * shows a pending row because the row is pending, and the server never claims a
     * durability it does not have. Were account numbers allocated by a core banking
     * platform over a batch window, this would become 202 and a pending resource, and the
     * front end would not have to change.
     *
     * <h2>{@code Idempotency-Key}</h2>
     *
     * <p>Optional, and honoured when present. A client that retries after a timeout cannot
     * otherwise tell the server "this is the same request I already sent", and against a
     * cap of five accounts a lost response would silently cost the customer a slot.
     *
     * <p>Optional rather than required so the API can be exercised with curl without
     * ceremony. A production API would require it on every unsafe method, because the
     * protection is only worth what the least careful client does.
     */
    @PostMapping
    public ResponseEntity<AccountResponse> open(
            @AuthenticationPrincipal Jwt caller,
            @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody OpenAccountRequest request) {

        UUID customerId = customerId(caller);

        if (idempotencyKey == null) {
            return created(accounts.open(customerId, request.nickname()));
        }
        if (!IdempotencyStore.isAcceptable(idempotencyKey)) {
            // Refused rather than ignored. Silently dropping a malformed key would leave
            // the caller believing they had protection they do not have, which is worse
            // than not offering it.
            throw new IdempotencyExceptions.KeyReused(
                    "malformed; expected 8-128 characters of [A-Za-z0-9_-]");
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
            // Give the key back: the attempt is local and transactional, so nothing was
            // committed and a genuine retry should be allowed through. See
            // IdempotencyStore#release for why this reasoning does not survive a remote
            // allocator.
            idempotency.release(customerId, idempotencyKey);
            throw e;
        }
        idempotency.complete(customerId, idempotencyKey, fingerprint, account.id());
        return created(account);
    }

    /**
     * Answers a request the server has seen before.
     *
     * <p>The original response is reconstructed from the stored account rather than cached
     * whole. That keeps the stored record to an identifier, and means a replay reflects the
     * account as it now is rather than as it was — which for an append-only resource is the
     * same thing, and for anything mutable would need saying out loud.
     */
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
                // The record points at an account that is gone. Nothing deletes accounts,
                // so this should be unreachable; refusing is still better than replaying a
                // 201 for something that does not exist.
                .orElseThrow(() -> new AccountNotFoundException(UUID.fromString(record.accountId())));
    }

    private static ResponseEntity<AccountResponse> created(AccountView account) {
        return ResponseEntity
                .created(URI.create("/accounts/" + account.id()))
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
                .filter(account -> account.customerId().equals(customerId(caller)))
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
