package com.lewiswalker.savings.platform.observability;

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
 * <p>{@link AccessLogFilter} sits outside the security chain deliberately, so a request
 * security refuses is still recorded. That puts its finally block after Spring Security
 * has cleared the {@code SecurityContextHolder} ThreadLocal, and every line would read
 * {@code customer=-}. This filter runs inside the chain, where the context is still
 * populated, and puts the subject somewhere request-scoped that outlives it.
 *
 * <p>Registered in {@code SecurityConfig} rather than annotated: its position in the
 * chain is the whole point.
 */
public class AuthenticatedSubjectFilter extends OncePerRequestFilter {

    static final String SUBJECT_ATTRIBUTE = AuthenticatedSubjectFilter.class.getName() + ".subject";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            // On the way back out: on the way in, authentication has not happened yet.
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication != null
                    && authentication.isAuthenticated()
                    // Anonymous authentication is still isAuthenticated(), with the
                    // literal name "anonymousUser".
                    && !(authentication instanceof AnonymousAuthenticationToken)) {
                request.setAttribute(SUBJECT_ATTRIBUTE, authentication.getName());
            }
        }
    }
}
