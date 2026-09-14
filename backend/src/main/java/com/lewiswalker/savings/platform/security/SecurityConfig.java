package com.lewiswalker.savings.platform.security;

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
import com.lewiswalker.savings.platform.observability.AuthenticatedSubjectFilter;

/**
 * The resource server configuration - the part of this package that is real. In
 * production the tokens come from the enterprise identity provider and
 * {@link TokenController} does not exist; this class is what survives the swap.
 */
@Configuration
@EnableWebSecurity
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
                // Nothing here carries ambient authority - no cookie, no basic auth -
                // so there is nothing for CSRF to protect. A refresh token in a cookie
                // would need it back on that endpoint.
                .csrf(AbstractHttpConfigurer::disable)

                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        // No actuator rule: actuator is on the management port and
                        // managementChain matches it there. A permitAll here would be a
                        // dead rule that reads like a live one.
                        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/token").permitAll()
                        // Default deny: a new endpoint is authenticated by omission.
                        .anyRequest().authenticated())

                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))

                // Position is load-bearing; see AuthenticatedSubjectFilter.
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
