package com.lewiswalker.savings.idempotency;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Remembers which requests have already been made, so a retry is a retry.
 *
 * <p>In Redis rather than Postgres deliberately. The entries are short-lived, keyed, and
 * shared across instances, which is what Redis is for; and the brief asks for the account
 * details in a single table, which this is not part of.
 *
 * <p><b>Keys are scoped to the customer.</b> The stored key is
 * {@code idempotency:<customerId>:<key>}, so one caller's key cannot collide with — or be
 * used to probe for — another's. A client-chosen value in a shared namespace is a way to
 * find out what other people have been doing.
 */
@Component
public class IdempotencyStore {

    /**
     * What an acceptable key looks like.
     *
     * <p>Client-supplied, so validated rather than trusted: it becomes part of a Redis key
     * and appears in error messages. Deliberately narrow — a UUID, a ULID, or a short
     * opaque token, and nothing else.
     */
    private static final Pattern ACCEPTABLE = Pattern.compile("[A-Za-z0-9_-]{8,128}");

    private static final String PREFIX = "idempotency:";

    private final StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper();
    private final Duration retention;

    public IdempotencyStore(StringRedisTemplate redis,
                            @Value("${idempotency.retention:24h}") Duration retention) {
        this.redis = redis;
        this.retention = retention;
    }

    public static boolean isAcceptable(String key) {
        return key != null && ACCEPTABLE.matcher(key).matches();
    }

    /**
     * Claims the key for this request, or reports what is already there.
     *
     * <p>A single {@code SET ... NX} rather than a read followed by a write: two concurrent
     * requests carrying the same key must not both see "nothing there" and both proceed,
     * which is the very failure this class exists to prevent. Exactly one claim wins.
     *
     * @return empty if the claim succeeded and the caller may proceed; otherwise the record
     *         that already exists
     */
    public Optional<IdempotencyRecord> claim(UUID customerId, String key, String fingerprint) {
        String redisKey = keyFor(customerId, key);
        try {
            Boolean claimed = redis.opsForValue().setIfAbsent(
                    redisKey, write(IdempotencyRecord.inProgress(fingerprint)), retention);
            if (Boolean.TRUE.equals(claimed)) {
                return Optional.empty();
            }
            String existing = redis.opsForValue().get(redisKey);
            if (existing == null) {
                // It expired between the failed claim and this read. Treating that as
                // "in progress" is the safe reading: the alternative is to proceed, and
                // proceeding is the outcome that opens a second account.
                return Optional.of(IdempotencyRecord.inProgress(fingerprint));
            }
            return Optional.of(read(existing));
        } catch (RuntimeException e) {
            throw new IdempotencyExceptions.StoreUnavailable(
                    "could not reach the idempotency store", e);
        }
    }

    /** Records the outcome, so a later retry of the same request is answered rather than repeated. */
    public void complete(UUID customerId, String key, String fingerprint, UUID accountId) {
        try {
            redis.opsForValue().set(
                    keyFor(customerId, key),
                    write(IdempotencyRecord.inProgress(fingerprint).completedWith(accountId.toString())),
                    retention);
        } catch (RuntimeException e) {
            // The account is already committed. Failing the response now would tell the
            // caller their request did not happen, which is worse than losing the replay
            // protection for this one key.
            throw new IdempotencyExceptions.StoreUnavailable(
                    "could not record the outcome of an idempotent request", e);
        }
    }

    /**
     * Gives the key back after a failed attempt, so a genuine retry is not locked out.
     *
     * <p>Safe here only because the attempt is local and transactional: if it threw,
     * nothing was committed. It would <em>not</em> be safe against a remote allocator,
     * where a failure can mean the far side succeeded and the response was lost. That case
     * wants the claim left in place and reconciled, not released.
     */
    public void release(UUID customerId, String key) {
        try {
            redis.delete(keyFor(customerId, key));
        } catch (RuntimeException e) {
            // Best effort. The entry expires on its own, and until then a retry is told
            // the request is in progress, which is inconvenient rather than wrong.
        }
    }

    private static String keyFor(UUID customerId, String key) {
        return PREFIX + customerId + ":" + key;
    }

    private String write(IdempotencyRecord record) {
        return json.writeValueAsString(record);
    }

    private IdempotencyRecord read(String value) {
        return json.readValue(value, IdempotencyRecord.class);
    }
}
