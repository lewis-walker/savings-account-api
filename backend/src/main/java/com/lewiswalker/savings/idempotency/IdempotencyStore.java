package com.lewiswalker.savings.idempotency;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Remembers which requests have already been made, so a retry is a retry.
 *
 * <p>In Redis rather than Postgres: the entries are short-lived, keyed and shared across
 * instances, and the brief asks for the account details in a single table.
 *
 * <p>Keys are scoped to the customer - {@code idempotency:<customerId>:<key>} - so one
 * caller's key cannot collide with, or be used to probe for, another's. A client-chosen
 * value in a shared namespace is a way to find out what other people have been doing.
 */
@Component
public class IdempotencyStore {

    /** Client-supplied, and it becomes part of a Redis key: a UUID, a ULID, or nothing. */
    private static final Pattern ACCEPTABLE = Pattern.compile("[A-Za-z0-9_-]{8,128}");

    private static final String PREFIX = "idempotency:";

    private final StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper();
    private final Duration retention;

    public IdempotencyStore(StringRedisTemplate redis, IdempotencyProperties properties) {
        this.redis = redis;
        this.retention = properties.retention();
    }

    public static boolean isAcceptable(String key) {
        return key != null && ACCEPTABLE.matcher(key).matches();
    }

    /**
     * Claims the key for this request, or reports what is already there. A single
     * {@code SET NX} rather than a read then a write: two concurrent requests with the
     * same key must not both see nothing there and both proceed.
     *
     * @return empty if the claim succeeded; otherwise the record that already exists
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
                // Expired between the failed claim and this read. "In progress" is the
                // safe reading; the alternative opens a second account.
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
            // The account is committed. Failing now would tell the caller it did not
            // happen, which is worse than losing replay protection for one key.
            throw new IdempotencyExceptions.StoreUnavailable(
                    "could not record the outcome of an idempotent request", e);
        }
    }

    /**
     * Gives the key back after a failed attempt, so a genuine retry is not locked out.
     * Safe only because the attempt is local and transactional: against a remote
     * allocator a failure can mean the far side succeeded and the response was lost, and
     * the claim would want reconciling rather than releasing.
     */
    public void release(UUID customerId, String key) {
        try {
            redis.delete(keyFor(customerId, key));
        } catch (RuntimeException e) {
            // Best effort; the entry expires on its own, and until then a retry is
            // told the request is in progress.
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
