package com.lewiswalker.savings.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.ObjectMapper;
import com.lewiswalker.savings.TestcontainersConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("an unknown endpoint is refused rather than reported - default deny")
    void everythingIsAuthenticatedByDefault() throws Exception {
        // /accounts has no controller yet, and still answers 401 rather than 404,
        // because security runs first and the rule is deny by default. A new endpoint
        // is protected because nobody remembered to protect it.
        mockMvc.perform(get("/accounts")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("valid credentials mint a token whose subject is the customer id")
    void mintsAToken() throws Exception {
        Jwt decoded = jwtDecoder.decode(tokenFor("ada@example.test", "demo-password"));

        assertThat(decoded.getSubject()).isEqualTo("11111111-1111-4111-8111-111111111111");
        assertThat(decoded.getAudience()).contains("savings-account-api");
        assertThat(decoded.getIssuer().toString()).isEqualTo("https://savings-account-api.local");
        assertThat(decoded.getClaimAsString("scope")).contains("accounts:read");
    }

    @Test
    @DisplayName("the token carries no personal data")
    void tokenCarriesNoPii() throws Exception {
        Jwt decoded = jwtDecoder.decode(tokenFor("ada@example.test", "demo-password"));

        // Tokens ride in a header on every request and headers get logged by proxies.
        // A name or email claim would quietly undo the log hygiene work; the name
        // comes from the customer service instead.
        String claims = decoded.getClaims().toString();
        assertThat(claims).doesNotContain("Ada Lovelace");
        assertThat(claims).doesNotContain("ada@example.test");
    }

    @Test
    @DisplayName("a wrong password and an unknown email fail identically")
    void badCredentialsAreIndistinguishable() throws Exception {
        MvcResult wrongPassword = attempt("ada@example.test", "wrong");
        MvcResult unknownEmail = attempt("nobody@example.test", "demo-password");

        // Distinguishable answers turn a login endpoint into a way of finding out who
        // banks here.
        assertThat(wrongPassword.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknownEmail.getResponse().getStatus()).isEqualTo(401);
        assertThat(unknownEmail.getResponse().getErrorMessage())
                .isEqualTo(wrongPassword.getResponse().getErrorMessage());
    }

    @Test
    @DisplayName("the JWKS endpoint publishes the public key and only the public key")
    void jwksLeaksNoPrivateMaterial() throws Exception {
        String body = mockMvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("RSA");
        assertThat(body).contains("kid");
        // RSA private material rides in d, p, q, dp, dq and qi. Their absence is the
        // entire contract of this endpoint, so it is asserted rather than assumed.
        assertThat(body).doesNotContain("\"d\":");
        assertThat(body).doesNotContain("\"p\":");
        assertThat(body).doesNotContain("\"q\":");
        assertThat(body).doesNotContain("\"dp\":");
        assertThat(body).doesNotContain("\"qi\":");
    }

    @Test
    @DisplayName("a malformed token is rejected")
    void garbageTokenIsRejected() throws Exception {
        mockMvc.perform(get("/accounts").header("Authorization", "Bearer not.a.token"))
                .andExpect(status().isUnauthorized());
    }

    private MvcResult attempt(String email, String password) throws Exception {
        return mockMvc.perform(post("/auth/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)))
                .andReturn();
    }

    private String tokenFor(String email, String password) throws Exception {
        String body = attempt(email, password).getResponse().getContentAsString();
        return new ObjectMapper().readTree(body).get("access_token").asText();
    }
}
