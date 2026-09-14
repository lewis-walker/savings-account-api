package com.lewiswalker.savings.platform.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One line per request: what was asked, what was answered, how long it took, who asked.
 *
 * <p>Ordered ahead of the security chain so a request refused before it reaches a
 * controller is still recorded.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("access");

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            // In a finally, so a request that blows up is still recorded.
            long millis = (System.nanoTime() - startedAt) / 1_000_000;
            log.info("{} {} {} {}ms customer={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    millis,
                    currentSubject(request));
        }
    }

    /**
     * The token's subject: the sub claim, which JwtKeys refuses unless it is a customer id.
     * So no PII
     */
    private static String currentSubject(HttpServletRequest request) {
        Object subject = request.getAttribute(AuthenticatedSubjectFilter.SUBJECT_ATTRIBUTE);
        return subject == null ? "-" : subject.toString();
    }
}
