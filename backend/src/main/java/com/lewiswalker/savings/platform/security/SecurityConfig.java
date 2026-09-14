package com.lewiswalker.savings.platform.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import com.lewiswalker.savings.platform.observability.AuthenticatedSubjectFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * The management port.
     * <p>Open here so the demo runs with one command.
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
                // We don't have a cookie, or basic auth -
                // so there is nothing for CSRF to protect. A refresh token in a cookie
                // would need it back on that endpoint.
                .csrf(AbstractHttpConfigurer::disable)

                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .authorizeHttpRequests(auth -> auth
                        // No actuator rule: actuator is on the management port and
                        // managementChain matches it there.
                        .requestMatchers(HttpMethod.GET, "/.well-known/jwks.json").permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/token").permitAll()
                        // Default deny.
                        .anyRequest().authenticated())

                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))

                // Position is load-bearing; see AuthenticatedSubjectFilter.
                .addFilterAfter(new AuthenticatedSubjectFilter(), SecurityContextHolderFilter.class)
                .build();
    }

}
