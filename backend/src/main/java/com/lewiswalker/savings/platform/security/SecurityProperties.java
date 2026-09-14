package com.lewiswalker.savings.platform.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param issuer      who signs tokens; in real life, the enterprise IdP's issuer URI
 * @param audience    this API. A token minted for another service must not work here.
 * @param accessTokenTtl short, because there is no revocation list.
 */
@ConfigurationProperties(prefix = "security.jwt")
public record SecurityProperties(String issuer, String audience, Duration accessTokenTtl) {

    public SecurityProperties {
        issuer = issuer == null ? "https://savings-account-api.local" : issuer;
        audience = audience == null ? "savings-account-api" : audience;
        accessTokenTtl = accessTokenTtl == null ? Duration.ofMinutes(15) : accessTokenTtl;
    }
}
