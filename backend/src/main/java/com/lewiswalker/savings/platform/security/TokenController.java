package com.lewiswalker.savings.platform.security;

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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Gives the front end something to sign in against, and publishes the public key.
 *
 * <p><b>Scaffolding.</b> In production the token arrives from the enterprise identity
 * provider by way of the API gateway, and this service only ever validates one - see
 * {@link SecurityConfig}, which is the half that survives. The password here is a fixed
 * string shared by every demo identity, not a credential: it exists so the sign-in
 * screen has a form to fill in and so the endpoint is not an open token dispenser.
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
    private final byte[] demoPassword;

    // @Value rather than a properties record, unlike everything else that is configured:
    // this setting leaves with the class.
    public TokenController(JwtEncoder jwtEncoder, JwtKeys jwtKeys, SecurityProperties properties,
                           @Value("${security.demo.password:demo-password}") String demoPassword) {
        this.jwtEncoder = jwtEncoder;
        this.jwtKeys = jwtKeys;
        this.properties = properties;
        this.demoPassword = demoPassword.getBytes(StandardCharsets.UTF_8);
    }

    @PostMapping("/auth/token")
    public TokenResponse issue(@jakarta.validation.Valid @RequestBody TokenRequest request) {
        Optional<DemoIdentities.Identity> identity = DemoIdentities.byEmail(request.email());

        // Compared for an unknown email too, and in constant time. Returning early
        // would make an unknown email measurably faster than a wrong password, which
        // turns this endpoint into a way to find out who banks here.
        boolean passwordMatches = MessageDigest.isEqual(
                request.password().getBytes(StandardCharsets.UTF_8), demoPassword);
        if (identity.isEmpty() || !passwordMatches) {
            // One message for both failures, same reason.
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
                // The customer id and nothing else. Tokens ride in a header that
                // proxies log, so a name or email claim would undo the log hygiene.
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
