package com.lewiswalker.savings.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import com.lewiswalker.savings.observability.AuthenticatedSubjectFilter;

/**
 * The resource server configuration — the part of this package that is real.
 *
 * <p>In production this service validates tokens minted by the enterprise identity
 * provider and there is no token endpoint here at all. {@link TokenController} exists
 * so the demo runs standalone; this class is what would survive the swap.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    /**
     * The management port.
     *
     * <p>Separate chain, matched first, because the management port is a different
     * surface with a different threat model. It is bound to its own port, is not
     * published by the customer-facing route, and in a real deployment is reachable only
     * from the operations network behind SSO.
     *
     * <p>Open here so the demo runs with one command. That is a deliberate, documented
     * demo decision and the first thing that would change: an endpoint that can switch
     * off a dependency is an endpoint that can cause an incident, and it wants
     * authentication, an audit trail of who changed what, and four eyes on anything
     * customer-visible.
     */
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    SecurityFilterChain managementChain(HttpSecurity http) throws Exception {
        return http
                .securityMatcher(EndpointRequest.toAnyEndpoint())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                // CSRF protects against a browser attaching credentials it holds
                // ambiently — cookies, basic auth. A bearer token is not ambient: it
                // has to be attached by script that has read it, and same-origin policy
                // stops a hostile page doing that. No cookie carries authority here, so
                // there is nothing for CSRF to protect. This would change the moment a
                // refresh token arrived in a cookie: that endpoint would need it back.
                .csrf(AbstractHttpConfigurer::disable)

                // No session, no JSESSIONID, nothing to fixate. Every request proves
                // itself. This is also what makes horizontal scaling free.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        // Health is unauthenticated so container orchestration can
                        // reach it; detail is still withheld (show-details:
                        // when-authorized), so it reveals up or down and nothing more.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/token").permitAll()
                        // Default deny. A new endpoint is authenticated because nobody
                        // remembered to add it here, which is the right way round.
                        .anyRequest().authenticated())

                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))

                // Inside the chain, after the security context is established and
                // before Spring Security clears it, so the access log can name the
                // caller. See AuthenticatedSubjectFilter.
                .addFilterAfter(new AuthenticatedSubjectFilter(), SecurityContextHolderFilter.class)
                .build();
    }

    /**
     * Delegating, so hashes carry their algorithm as a prefix ({@code {bcrypt}...}).
     * That is what makes it possible to move to a stronger algorithm later and rehash
     * on next login, rather than needing every password at once.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
