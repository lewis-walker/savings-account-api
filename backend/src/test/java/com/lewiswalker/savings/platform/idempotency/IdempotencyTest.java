package com.lewiswalker.savings.platform.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lewiswalker.savings.TestcontainersConfiguration;
import com.lewiswalker.savings.platform.security.DemoIdentities;
import com.lewiswalker.savings.account.AccountRepository;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.ObjectMapper;

/**
 * A retried request must be a retry, not a second account.
 *
 * <p>The account limit is what makes this more than housekeeping. A lost response followed
 * by a retry would silently consume one of a customer's five slots, and they would have no
 * way to tell that is what happened.
 *
 * <p>Keys are generated per test rather than fixed. A fixed key would be remembered for the
 * retention period, so the second run of the suite would exercise a replay path the first
 * run did not — a test that passes or fails depending on when it was last run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class IdempotencyTest {

    private static final String ADA = DemoIdentities.ADA.email();
    private static final String GRACE = DemoIdentities.GRACE.email();
    private static final UUID ADA_ID =
            com.lewiswalker.savings.platform.security.DemoIdentities.byEmail(ADA).orElseThrow().customerId();

    @Autowired private MockMvc mockMvc;
    @Autowired private AccountRepository repository;
    @Autowired private IdempotencyStore store;
    @Autowired private org.springframework.data.redis.core.StringRedisTemplate redis;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void clear() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("the same key twice returns the original account and opens only one")
    void replayReturnsTheOriginalAccount() throws Exception {
        String key = freshKey();

        String first = mockMvc.perform(open(key, "{\"nickname\":\"Holiday fund\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(open(key, "{\"nickname\":\"Holiday fund\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // Identical response, not merely an identical status. Before this was
        // implemented, both calls succeeded and returned two different accounts.
        assertThat(idOf(second)).isEqualTo(idOf(first));
        assertThat(second).isEqualTo(first);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a retry does not consume one of the customer's five slots")
    void retryDoesNotConsumeASlot() throws Exception {
        String[] keys = new String[5];
        for (int i = 0; i < 5; i++) {
            keys[i] = freshKey();
            mockMvc.perform(open(keys[i], "{}")).andExpect(status().isCreated());
        }

        // The customer is now full. A retry of the last request must be answered from the
        // record, not refused with 409 - and must certainly not be a sixth attempt.
        mockMvc.perform(open(keys[4], "{}")).andExpect(status().isCreated());

        assertThat(repository.count()).isEqualTo(5);
    }

    @Test
    @DisplayName("the same key with a different body is refused rather than answered")
    void reusingAKeyForADifferentRequestIsRefused() throws Exception {
        String key = freshKey();
        mockMvc.perform(open(key, "{\"nickname\":\"Holiday fund\"}")).andExpect(status().isCreated());

        // Answering this with the first account would silently discard what the caller
        // actually asked for.
        mockMvc.perform(open(key, "{\"nickname\":\"House deposit\"}"))
                .andExpect(status().isUnprocessableContent());

        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a malformed key is refused as a validation failure, and said to be one")
    void malformedKeyIsRefused() throws Exception {
        // Ignoring it would leave the caller believing they have protection they do not.
        //
        // The body is asserted, not just the status. This previously answered 422
        // "Idempotency key reused" for a key never used before, telling the caller to
        // pick a new one - which, generated the same way, fails the same. A test that
        // read only the status code passed throughout.
        mockMvc.perform(open("short", "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"))
                .andExpect(jsonPath("$.errors[0].message").value(containsString("8 to 128")))
                .andExpect(jsonPath("$.detail").value(not(containsString("reused"))));
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("without a key the endpoint behaves exactly as before")
    void theHeaderIsOptional() throws Exception {
        mockMvc.perform(post("/accounts")
                        .header("Authorization", bearer(ADA))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nickname\":\"Holiday fund\"}"))
                .andExpect(status().isCreated());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("a key pointing at another customer's account is refused and recorded, not answered")
    void ownershipMismatchIsRefused() throws Exception {
        // Structurally impossible: the entry is namespaced by customer, so the id under
        // Ada's key was put there by Ada. Forced through the store because the defence is
        // against the namespacing being wrong, and a defence nothing exercises is a
        // comment. Answering it would hand Ada an account belonging to Grace.
        Logger auditLogger = (Logger) LoggerFactory.getLogger("audit");
        ListAppender<ILoggingEvent> captured = new ListAppender<>();
        captured.start();
        auditLogger.addAppender(captured);
        try {
            String graceAccount = mockMvc.perform(post("/accounts")
                            .header("Authorization", bearer(GRACE))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"nickname\":\"Grace's money\"}"))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            UUID graceAccountId = UUID.fromString(json.readTree(graceAccount).get("id").asString());

            String key = freshKey();
            store.complete(ADA_ID, key, RequestFingerprint.of(ADA_ID.toString(), "Holiday fund"),
                    graceAccountId);

            mockMvc.perform(open(key, "{\"nickname\":\"Holiday fund\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.detail").value(not(containsString(graceAccountId.toString()))));

            assertThat(captured.list).anySatisfy(event -> {
                assertThat(event.getFormattedMessage()).contains("event=account.ownership-mismatch");
                assertThat(event.getFormattedMessage()).contains(graceAccountId.toString());
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            });
        } finally {
            auditLogger.detachAppender(captured);
        }
    }

    @Test
    @DisplayName("an entry that names no result is refused as a problem document, not a raw 500")
    void corruptRecordIsRefusedInTheApiContract() throws Exception {
        // Written raw, because nothing in the store can produce this: complete() only
        // takes a real id. A partial write, a tampered entry or an older shape can.
        String key = freshKey();
        redis.opsForValue().set("idempotency:" + ADA_ID + ":" + key,
                "{\"state\":\"COMPLETED\",\"fingerprint\":\""
                        + RequestFingerprint.of(ADA_ID.toString(), "Holiday fund") + "\"}");

        mockMvc.perform(open(key, "{\"nickname\":\"Holiday fund\"}"))
                .andExpect(status().isInternalServerError())
                // The point of the test: inside the API's own contract, with something to
                // quote at support. A null reaching the cache leaves neither.
                .andExpect(jsonPath("$.type").value(containsString("request-failed")))
                .andExpect(jsonPath("$.correlationId").isNotEmpty());
        assertThat(repository.count()).isZero();
    }

    @Test
    @DisplayName("only one of sixteen simultaneous claims on a key wins")
    void concurrentClaimsResolveToOne() throws Exception {
        UUID customerId = UUID.randomUUID();
        String key = freshKey();
        String fingerprint = RequestFingerprint.of(customerId.toString(), "Holiday fund");

        // At the store rather than through MockMvc, which is not thread-safe. What is
        // being proved is the claim itself: a read followed by a write would let two
        // callers both see nothing and both proceed, which is the whole failure this
        // prevents. SET NX makes exactly one of them win.
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger claimed = new AtomicInteger();
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            var running = java.util.stream.IntStream.range(0, 16)
                    .mapToObj(i -> pool.submit(() -> {
                        try {
                            release.await();
                            if (store.claim(customerId, key, fingerprint).isEmpty()) {
                                claimed.incrementAndGet();
                            }
                        } catch (Exception e) {
                            Thread.currentThread().interrupt();
                        }
                    }))
                    .toList();
            release.countDown();
            for (Future<?> f : running) {
                f.get(30, TimeUnit.SECONDS);
            }
        }

        assertThat(claimed.get()).isEqualTo(1);
    }

    private static String freshKey() {
        return "test-" + UUID.randomUUID();
    }

    private MockHttpServletRequestBuilder open(String key, String body) throws Exception {
        return post("/accounts")
                .header("Authorization", bearer(ADA))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String bearer(String email) throws Exception {
        String body = mockMvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"demo-password\"}".formatted(email)))
                .andReturn().getResponse().getContentAsString();
        return "Bearer " + json.readTree(body).get("access_token").asText();
    }

    private String idOf(String body) {
        return json.readTree(body).get("id").asText();
    }
}
