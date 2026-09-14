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
 * Gives every request a correlation id, puts it on every log line the request
 * produces, and returns it to the caller.
 *
 * <p>Ordered first so that nothing — including an authentication failure inside the
 * security chain — can log before the id exists. A log line without a correlation id
 * is the one you need during an incident.
 *
 * <h2>Where the id comes from</h2>
 *
 * <p>Normally from the gateway. The edge sees every request, including those it rejects
 * before they reach this service, so only an id issued there can join the whole picture
 * together — an id minted here would cover just the subset that got through. In this
 * stack nginx sets it from its own {@code $request_id}, and a browser is an untrusted
 * client whose header nginx overwrites.
 *
 * <p>One is still generated here when the header is absent, so the service is never
 * without a correlation id: called directly in development, reached by something that
 * bypassed the gateway, or behind a gateway someone forgot to configure. A log line
 * with no id is the one you will want.
 *
 * <h2>Why the inbound header is still not trusted</h2>
 *
 * "It comes from the gateway" is an assumption about network topology, and topology
 * changes. The header is attacker-controlled input on its way into a log file, which is
 * CWE-117 (log injection): a carriage return inside it lets a caller forge whole log
 * entries. Where logs are evidence — and in a bank they are — a forged entry is worse
 * than a missing one.
 *
 * <p>So the header is not escaped or trimmed, it is <em>validated and replaced</em>.
 * Anything that is not a short run of alphanumerics and hyphens is discarded and we
 * mint our own. Sanitising attacker input and then logging it invites an argument
 * about whether the sanitiser is complete; refusing it does not.
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
            // MDC is a ThreadLocal and the container pools threads. Without this, the
            // next request served by this thread inherits the previous request's id —
            // which is not a missing correlation, it is a wrong one.
            MDC.remove(MDC_KEY);
        }
    }

    static String accept(String supplied) {
        return supplied != null && ACCEPTABLE.matcher(supplied).matches()
                ? supplied
                : UUID.randomUUID().toString();
    }
}
