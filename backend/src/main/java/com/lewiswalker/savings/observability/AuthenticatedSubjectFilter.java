package com.lewiswalker.savings.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Copies the authenticated subject onto the request so the access log can find it.
 *
 * <p>This exists because of a timing subtlety worth knowing. The authentication lives
 * in {@code SecurityContextHolder}, which is a {@code ThreadLocal} that Spring Security
 * <em>clears in its own finally block</em> — the container pools threads, so leaving it
 * populated would hand the next request the previous caller's identity.
 *
 * <p>{@link AccessLogFilter} runs outside the security chain, deliberately, so that a
 * request refused by security is still recorded — a refused request is exactly the one
 * an auditor wants. But that means its finally block runs <em>after</em> Spring
 * Security's, by which point the context is empty, and every line reads
 * {@code customer=-} however authenticated the request was.
 *
 * <p>So this filter runs inside the chain, where the context is still populated, and
 * puts the subject somewhere request-scoped that outlives it.
 *
 * <p>Registered in {@code SecurityConfig} rather than annotated, because where it sits
 * in the chain is the entire point of it.
 */
public class AuthenticatedSubjectFilter extends OncePerRequestFilter {

    static final String SUBJECT_ATTRIBUTE = AuthenticatedSubjectFilter.class.getName() + ".subject";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            // Read on the way back out, not on the way in. Authentication happens
            // further down the chain than this filter sits, so on the way in the
            // context is empty and every access log line reads customer=-. Reading it
            // here catches it after the authentication filter has populated it and
            // before SecurityContextHolderFilter, which wraps this one, clears it.
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null
                    && authentication.isAuthenticated()
                    // Anonymous authentication is still "authenticated" as far as the
                    // interface is concerned, and its name is the literal
                    // "anonymousUser". Logging that as a customer would be worse than
                    // logging nothing at all.
                    && !(authentication instanceof AnonymousAuthenticationToken)) {
                request.setAttribute(SUBJECT_ATTRIBUTE, authentication.getName());
            }
        }
    }
}
