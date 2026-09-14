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
 * The signing key, generated at startup, so {@code docker compose up} needs no setup and
 * no private key is committed. The cost is that tokens do not survive a restart and a
 * second instance would have its own key. Production key management is in DECISIONS.md.
 */
@Configuration
public class JwtKeys {

    private static final int KEY_SIZE = 2048;

    private final RSAKey rsaKey;

    public JwtKeys() {
        KeyPair keyPair = generate();
        this.rsaKey = new RSAKey.Builder((RSAPublicKey) keyPair.getPublic())
                .privateKey((RSAPrivateKey) keyPair.getPrivate())
                // Present from the start: adding kid later is a migration, not a
                // rollover.
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
