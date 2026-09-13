package com.lewiswalker.savings.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * The signing key, generated at startup.
 *
 * <p>Generated rather than configured so that {@code docker compose up} works with
 * nothing to set up, and so that no private key is ever committed. A demo key in a
 * repository is still a private key in a repository: a static analysis finding, and a
 * bad look regardless of the label on it.
 *
 * <p>What this costs, plainly: the key changes on restart, so tokens do not survive
 * one, and running more than one instance would give each its own key. Both are fine
 * for a demo and neither is fine for production.
 *
 * <p><b>How this is really done.</b> The private key lives in an HSM or a KMS and never
 * leaves it — you hand the payload over and get a signature back, so the key material
 * is never in application memory at all. Rotation works through the {@code kid} header:
 * the JWKS endpoint publishes both the outgoing and incoming keys during a rollover, so
 * tokens signed by the old key keep verifying while new tokens use the new one, and the
 * old key is withdrawn only after the longest possible token lifetime has elapsed.
 * Deliberately not built — see DECISIONS.md on building the minimum.
 */
@Configuration
public class JwtKeys {

    private static final int KEY_SIZE = 2048;

    private final RSAKey rsaKey;

    public JwtKeys() {
        KeyPair keyPair = generate();
        this.rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                // Present from the start, because a rotation story that has to invent
                // key identifiers later is a migration rather than a rollover.
                .keyID(UUID.randomUUID().toString())
                .build();
    }

    /** Only the public half. Whatever else changes, the private key never goes out this door. */
    public JWKSet publicJwkSet() {
        return new JWKSet(rsaKey.toPublicJWK());
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(rsaKey)));
    }

    /**
     * Verifies signature, expiry, issuer and audience.
     *
     * <p>Signature and expiry are the defaults. Issuer and audience are not, and both
     * matter: without an audience check this API will happily accept a valid token
     * that was minted for an entirely different service in the estate, which is how
     * one compromised service becomes several.
     */
    @Bean
    JwtDecoder jwtDecoder(SecurityProperties properties) {
        NimbusJwtDecoder decoder;
        try {
            decoder = NimbusJwtDecoder.withPublicKey(rsaKey.toRSAPublicKey()).build();
        } catch (JOSEException e) {
            throw new IllegalStateException("could not derive the public key", e);
        }
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuer()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        audience -> audience != null && audience.contains(properties.audience()))));
        return decoder;
    }

    private static KeyPair generate() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA is not available in this JVM", e);
        }
    }
}
