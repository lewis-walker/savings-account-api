package com.lewiswalker.savings.security;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.nimbusds.jose.jwk.JWKSet;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Issues access tokens for the demo, and publishes the public key.
 *
 * <p><b>This endpoint would not exist in production.</b> It is the OAuth 2.0 resource
 * owner password credentials grant, which OAuth 2.1 removes precisely because handing
 * a password to the client application is the thing federated identity exists to stop.
 * It is here so the demo runs without an identity provider beside it, and it is the
 * first thing that would be deleted against a real IdP.
 *
 * <p>What would be kept is {@link SecurityConfig} — the resource server half.
 */
@RestController
public class TokenController {

    public record TokenRequest(
            @NotBlank(message = "email is required") String email,
            @NotBlank(message = "password is required") String password) {}

    public record TokenResponse(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("token_type") String tokenType,
            @JsonProperty("expires_in") long expiresInSeconds) {}

    private final JwtEncoder jwtEncoder;
    private final JwtKeys jwtKeys;
    private final SecurityProperties properties;
    private final PasswordEncoder passwordEncoder;
    private final String demoPasswordHash;

    public TokenController(JwtEncoder jwtEncoder, JwtKeys jwtKeys, SecurityProperties properties,
                           PasswordEncoder passwordEncoder,
                           @Value("${security.demo.password:demo-password}") String demoPassword) {
        this.jwtEncoder = jwtEncoder;
        this.jwtKeys = jwtKeys;
        this.properties = properties;
        this.passwordEncoder = passwordEncoder;
        // Hashed at startup rather than compared in plain text, so the demo does not
        // model something nobody should copy.
        this.demoPasswordHash = passwordEncoder.encode(demoPassword);
    }

    @PostMapping("/auth/token")
    public TokenResponse issue(@jakarta.validation.Valid @RequestBody TokenRequest request) {
        Optional<DemoIdentities.Identity> identity = DemoIdentities.byEmail(request.email());

        // Always do the hash comparison, even when the email is unknown. Returning
        // early would make an unknown email measurably faster than a known one with a
        // wrong password, which turns this endpoint into a way to find out who banks
        // here. The answer is the same either way, and so is the time it takes.
        boolean passwordMatches = passwordEncoder.matches(request.password(), demoPasswordHash);
        if (identity.isEmpty() || !passwordMatches) {
            // One message for both failures, for the same reason.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }

        return new TokenResponse(mint(identity.get().customerId()), "Bearer",
                properties.accessTokenTtl().toSeconds());
    }

    /** The public half of the signing key. Where a client would fetch keys to verify. */
    @GetMapping("/.well-known/jwks.json")
    public Map<String, Object> jwks() {
        JWKSet publicKeys = jwtKeys.publicJwkSet();
        return publicKeys.toJSONObject();
    }

    private String mint(UUID customerId) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(java.util.List.of(properties.audience()))
                // The subject is the customer id and nothing else. No name, no email:
                // tokens ride in a header on every request and headers get logged by
                // proxies, so a PII claim would quietly undo the log hygiene work.
                // The name comes from the customer service, which is where it lives.
                .subject(customerId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTokenTtl()))
                .id(UUID.randomUUID().toString())
                .claim("scope", "accounts:read accounts:write")
                .build();

        JwsHeader header = JwsHeader.with(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.RS256)
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
