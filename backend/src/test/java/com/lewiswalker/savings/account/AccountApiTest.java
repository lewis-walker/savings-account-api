package com.lewiswalker.savings.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lewiswalker.savings.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The two endpoints the brief asks for, exercised the way a client would: a real token
 * from the real token endpoint, against the real demo customer directory.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountApiTest {

    private static final String ADA = "ada@example.test";
    private static final String GRACE = "grace@example.test";
    /** Due diligence deliberately left pending in the demo directory. */
    private static final String ALAN = "alan@example.test";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository repository;

    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach
    void clear() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("opening an account answers 201 with a Location and an NZ account number")
    void opensAnAccount() throws Exception {
        mockMvc.perform(openAccount(ADA, "{\"nickname\":\"Holiday fund\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.accountNumber").value(
                        org.hamcrest.Matchers.matchesPattern("\\d{2}-\\d{4}-\\d{7}-\\d{3}")))
                .andExpect(jsonPath("$.customerName").value("Ada Lovelace"))
                .andExpect(jsonPath("$.nickname").value("Holiday fund"))
                .andExpect(jsonPath("$.id").exists());
    }

    @Test
    @DisplayName("the nickname is optional")
    void nicknameIsOptional() throws Exception {
        mockMvc.perform(openAccount(ADA, "{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nickname").doesNotExist());
    }

    @Test
    @DisplayName("a short nickname is a 400 naming the field")
    void shortNicknameIsRejected() throws Exception {
        mockMvc.perform(openAccount(ADA, "{\"nickname\":\"abc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.errors[0].field").value("nickname"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    @DisplayName("an offensive nickname is a 422 that does not repeat it back")
    void offensiveNicknameIsRejected() throws Exception {
        String body = mockMvc.perform(openAccount(ADA, "{\"nickname\":\"my badword account\"}"))
                .andExpect(status().isUnprocessableContent())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("badword");
    }

    @Test
    @DisplayName("a customer name in the body is refused rather than silently ignored")
    void customerNameInBodyIsRefused() throws Exception {
        mockMvc.perform(openAccount(ADA,
                        "{\"nickname\":\"Holiday fund\",\"customerName\":\"Somebody Else\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the sixth account is a 409")
    void sixthAccountIsRefused() throws Exception {
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(openAccount(ADA, "{}")).andExpect(status().isCreated());
        }
        mockMvc.perform(openAccount(ADA, "{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.limit").value(5));
    }

    @Test
    @DisplayName("a customer whose due diligence is incomplete cannot open an account")
    void unverifiedCustomerIsRefused() throws Exception {
        String body = mockMvc.perform(openAccount(ALAN, "{}"))
                .andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();

        // The response must not say which stage verification is at - that is the bank's
        // assessment of a customer, not an API error detail.
        assertThat(body).doesNotContain("PENDING");
        assertThat(body).doesNotContain("EXPIRED");
    }

    @Test
    @DisplayName("a customer can read their own account")
    void readsOwnAccount() throws Exception {
        String id = idOf(mockMvc.perform(openAccount(ADA, "{\"nickname\":\"Holiday fund\"}"))
                .andReturn().getResponse().getContentAsString());

        mockMvc.perform(get("/accounts/" + id).header("Authorization", bearer(ADA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    @DisplayName("another customer's account is 404, not 403")
    void cannotReadSomeoneElsesAccount() throws Exception {
        String adasAccount = idOf(mockMvc.perform(openAccount(ADA, "{}"))
                .andReturn().getResponse().getContentAsString());

        // 403 would confirm the account exists, which is enough to walk the estate.
        mockMvc.perform(get("/accounts/" + adasAccount).header("Authorization", bearer(GRACE)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the list is scoped to the caller by the token, not by a parameter")
    void listIsScopedToCaller() throws Exception {
        mockMvc.perform(openAccount(ADA, "{}")).andExpect(status().isCreated());
        mockMvc.perform(openAccount(ADA, "{}")).andExpect(status().isCreated());
        mockMvc.perform(openAccount(GRACE, "{}")).andExpect(status().isCreated());

        mockMvc.perform(get("/accounts").header("Authorization", bearer(ADA)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/accounts").header("Authorization", bearer(GRACE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("no token, no account")
    void unauthenticatedIsRefused() throws Exception {
        mockMvc.perform(post("/accounts")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the response carries the caller's correlation id back")
    void correlationIdIsReturned() throws Exception {
        mockMvc.perform(openAccount(ADA, "{}").header("X-Correlation-Id", "trace-abc-123"))
                .andExpect(header().string("X-Correlation-Id", "trace-abc-123"));
    }

    private MockHttpServletRequestBuilder openAccount(String email, String body) throws Exception {
        return post("/accounts")
                .header("Authorization", bearer(email))
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

    private String idOf(String responseBody) throws Exception {
        JsonNode node = json.readTree(responseBody);
        return node.get("id").asText();
    }
}
