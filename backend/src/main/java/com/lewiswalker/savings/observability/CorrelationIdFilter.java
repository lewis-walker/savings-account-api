package com.lewiswalker.savings.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a correlation id, puts it on every log line the request produces,
 * and returns it to the caller. Ordered first, so nothing - including an authentication
 * failure inside the security chain - can log before the id exists.
 *
 * <p>The id normally comes from the gateway, which sees requests this service never
 * will; one is generated here when the header is absent. The inbound value is validated
 * and replaced rather than trusted: it is attacker-controlled input on its way into a
 * log file (CWE-117), and a carriage return in it forges entries.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    /** Deliberately narrow: a UUID, a trace id, or a short opaque token. Nothing else. */
    private static final Pattern ACCEPTABLE = Pattern.compile("[A-Za-z0-9-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = accept(request.getHeader(HEADER));
        MDC.put(MDC_KEY, correlationId);
        // Set before the chain runs, so the caller still gets the id on an error response.
        response.setHeader(HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            // MDC is a ThreadLocal and the container pools threads: left set, the
            // next request on this thread gets the wrong id rather than none.
            MDC.remove(MDC_KEY);
        }
    }

    static String accept(String supplied) {
        return supplied != null && ACCEPTABLE.matcher(supplied).matches()
                ? supplied
                : UUID.randomUUID().toString();
    }
}
