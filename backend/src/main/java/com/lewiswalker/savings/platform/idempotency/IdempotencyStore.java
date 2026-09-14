package com.lewiswalker.savings.platform.idempotency;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.regex.Pattern;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs an operation at most once per idempotency key, and remembers the answer.
 *
 * <p>{@link #performOnce} is the whole public surface. The primitives underneath have to be
 * called in one order - claim, then perform, then complete or release - and a caller
 * that gets it wrong performs the operation twice, which is the failure this exists to
 * stop - here, a second account against a cap of five.
 * They are package-private so the order cannot be reassembled elsewhere, and so the
 * record it keeps stays an implementation detail.
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

    private static final Logger log = LoggerFactory.getLogger(IdempotencyStore.class);

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

    static boolean isAcceptable(String key) {
        return key != null && ACCEPTABLE.matcher(key).matches();
    }

    /**
     * Performs the operation, or returns the answer an earlier identical request got.
     *
     * <p>Both paths end in {@code recall}, so a replayed answer and a first one are built
     * by the same code and cannot drift apart. That costs one read of a row this request
     * just committed, which the write-through cache has already warmed.
     *
     * @param perform does the work and returns the id of what it created
     * @param recall  turns that id back into the answer, and owns what absence means
     */
    public <T> T performOnce(UUID customerId, String key, String fingerprint,
                             Supplier<UUID> perform, Function<UUID, T> recall) {
        if (key == null) {
            return recall.apply(perform.get());
        }

        Optional<IdempotencyRecord> existing = claim(customerId, key, fingerprint);
        if (existing.isPresent()) {
            return recall.apply(previousResultId(customerId, existing.get(), fingerprint, key));
        }

        UUID id;
        try {
            id = perform.get();
        } catch (RuntimeException e) {
            // Nothing committed, so a genuine retry should not be locked out.
            release(customerId, key);
            throw e;
        }
        complete(customerId, key, fingerprint, id);
        return recall.apply(id);
    }

    /** The id an earlier request created, once this one is established as the same request. */
    private static UUID previousResultId(UUID customerId, IdempotencyRecord record,
                                         String fingerprint, String key) {
        if (!record.matches(fingerprint)) {
            throw new IdempotencyExceptions.KeyReused(key);
        }
        if (record.state() == IdempotencyRecord.State.IN_PROGRESS) {
            throw new IdempotencyExceptions.InProgress(key);
        }
        if (record.resultId() == null) {
            // A completed entry names what it created. One that does not cannot be
            // replayed, and a null here reaches the cache as a null key, which fails
            // somewhere that has no idea what it is talking about.
            //
            // The customer, not the key: the key is client-supplied, and this is the one
            // path that reaches here without the constraint having run.
            log.error("idempotency entry for customer {} is complete but names no result", customerId);
            throw new IdempotencyExceptions.CorruptRecord(
                    "a completed idempotency entry names no result");
        }
        return record.resultId();
    }

    /**
     * A single {@code SET NX} rather than a read then a write: two concurrent requests
     * with the same key must not both see nothing there and both proceed.
     *
     * @return empty if the claim succeeded; otherwise the record that already exists
     */
    Optional<IdempotencyRecord> claim(UUID customerId, String key, String fingerprint) {
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
                // safe reading; the alternative performs the operation twice.
                return Optional.of(IdempotencyRecord.inProgress(fingerprint));
            }
            return Optional.of(read(existing));
        } catch (RuntimeException e) {
            throw new IdempotencyExceptions.StoreUnavailable(
                    "could not reach the idempotency store", e);
        }
    }

    /** Records the outcome, so a later retry of the same request is answered rather than repeated. */
    void complete(UUID customerId, String key, String fingerprint, UUID resultId) {
        try {
            redis.opsForValue().set(
                    keyFor(customerId, key),
                    write(IdempotencyRecord.inProgress(fingerprint).completedWith(resultId)),
                    retention);
        } catch (RuntimeException e) {
            // The operation is committed. Failing now would tell the caller it did not
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
    void release(UUID customerId, String key) {
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
