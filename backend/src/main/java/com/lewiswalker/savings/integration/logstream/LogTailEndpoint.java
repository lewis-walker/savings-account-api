package com.lewiswalker.savings.integration.logstream;

import java.util.List;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.stereotype.Component;

/**
 * Tails the log over HTTP, on the management port.
 *
 * <p>Polling with a sequence cursor rather than server-sent events: actuator endpoints
 * are request/response and the management port runs its own context, so streaming would
 * mean a second web layer for a debugging convenience. In production these go to a log
 * aggregator and are read there; this exists so a reviewer can watch the system behave
 * without a terminal.
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
