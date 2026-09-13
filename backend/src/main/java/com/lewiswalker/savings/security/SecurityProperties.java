package com.lewiswalker.savings.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param issuer      who signs tokens; in production, the enterprise IdP's issuer URI
 * @param audience    this API. A token minted for another service must not work here.
 * @param accessTokenTtl short, because there is no revocation list. The window in which
 *                       a stolen token is useful is exactly this value.
 */
@ConfigurationProperties(prefix = "security.jwt")
public record SecurityProperties(String issuer, String audience, Duration accessTokenTtl) {

    public SecurityProperties {
        issuer = issuer == null ? "https://savings-account-api.local" : issuer;
        audience = audience == null ? "savings-account-api" : audience;
        accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(15) : accessTokenTtl;
    }
}
