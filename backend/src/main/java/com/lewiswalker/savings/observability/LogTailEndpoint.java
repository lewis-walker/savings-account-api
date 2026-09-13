package com.lewiswalker.savings.observability;

import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.stereotype.Component;

/**
 * Tails the log over HTTP, on the management port.
 *
 * <p>Polling with a sequence cursor rather than server-sent events, and that is worth
 * being straight about. SSE would be a nicer transport, but actuator endpoints are
 * request/response and the management port runs its own context, so streaming from here
 * would mean building a second web layer for a debugging convenience.
 *
 * <p>The deeper reason not to invest further: in production nobody tails a service's own
 * logs over HTTP. They are shipped to Splunk or an ELK stack and read there, with
 * retention, access control and search across every instance. This exists so a reviewer
 * can watch the system behave — correlation ids appearing, a cache falling through when
 * Redis stops — without needing a terminal and {@code docker compose logs}.
 */
@Component
@Endpoint(id = "logtail")
public class LogTailEndpoint {

    private final LogTail logTail;

    public LogTailEndpoint(LogTail logTail) {
        this.logTail = logTail;
    }

    @ReadOperation
    public Map<String, Object> tail() {
        return since(0);
    }

    @ReadOperation
    public Map<String, Object> since(@Selector long after) {
        List<LogTail.Entry> entries = logTail.since(after);
        return Map.of(
                "entries", entries,
                // Returned rather than inferred from the last entry, so a poll that
                // finds nothing still moves the cursor forward correctly.
                "cursor", logTail.latestSequence());
    }
}
