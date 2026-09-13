package com.lewiswalker.savings.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One line per request: what was asked, what was answered, how long it took, and who
 * asked.
 *
 * <p>Without this the correlation id is decoration. A successful request logs nothing
 * on its own, so an id that is faithfully placed in the MDC has nowhere to appear, and
 * a support reference that matches no log line is worse than useless. This is the line
 * it matches.
 *
 * <p>In a bank it is also an audit record: who did what, when, and what the system
 * said. That shapes what goes in it.
 *
 * <h2>What is deliberately absent</h2>
 *
 * <ul>
 *   <li><b>The query string.</b> Excluded outright rather than filtered. Query
 *       parameters are where personal data ends up by accident, and once a value is in
 *       an access log it is also in every log aggregator downstream.
 *   <li><b>Headers and bodies.</b> The authorization header is a bearer token, and a
 *       request body is the customer's own words.
 *   <li><b>The customer's name.</b> The subject claim identifies the actor well enough
 *       for audit, and an opaque identifier means nothing to anyone reading the log
 *       without database access. That is the property worth having.
 * </ul>
 *
 * <p>Ordered immediately after {@link CorrelationIdFilter} so the id is already in the
 * MDC, and before everything else so a request rejected by the security chain is still
 * recorded — a refused request is precisely the one an auditor wants to see.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("access");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            // In a finally block so a request that blows up is still recorded. An
            // access log with the failures missing from it is an access log that
            // cannot be trusted.
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
     * The token's subject, or a dash. Never a name, never an email.
     *
     * <p>Read from a request attribute rather than from {@code SecurityContextHolder},
     * which by this point has been cleared — see {@link AuthenticatedSubjectFilter} for
     * why, because the empty-looking alternative is a silent bug rather than a loud one.
     */
    private static String currentSubject(HttpServletRequest request) {
        Object subject = request.getAttribute(AuthenticatedSubjectFilter.SUBJECT_ATTRIBUTE);
        return subject == null ? "-" : subject.toString();
    }
}
