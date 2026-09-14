package com.lewiswalker.savings.integration.logstream;

import com.lewiswalker.savings.platform.observability.CorrelationIdFilter;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.AppenderBase;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Keeps the last few hundred log lines in memory so they can be tailed over HTTP.
 *
 * <p>Streaming application logs to a browser is only safe because of what is not in
 * them: {@code LogHygieneTest} covers the application's own logging and Hibernate's
 * entity printing, {@code ApiExceptionHandlerTest} the constraint-violation path.
 *
 * <p>A ring buffer with a hard cap, so a service in a retry storm cannot turn its own
 * logging into a memory leak. Dropping old lines is correct here: this is a live tail,
 * not a record. The record belongs in a log aggregator.
 */
@Component
public class LogTail {

    /** Small enough that it cannot matter, large enough to see what just happened. */
    private static final int CAPACITY = 500;

    public record Entry(long sequence, Instant at, String level, String logger,
                        String correlationId, String message) {}

    private final Deque<Entry> entries = new ArrayDeque<>(CAPACITY);
    private final AtomicLong sequence = new AtomicLong();

    @PostConstruct
    void attachToRootLogger() {
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        Appender appender = new Appender();
        appender.setContext(root.getLoggerContext());
        appender.start();
        root.addAppender(appender);
    }

    /**
     * Everything after the given sequence number.
     *
     * <p>A sequence rather than a timestamp, because timestamps collide at millisecond
     * resolution and a tail that duplicates lines is worse than one that drops them.
     *
     * <p>At most once, not exactly once: the endpoint reads the entries and then reads the
     * cursor, so a line recorded between those two calls falls inside the cursor without
     * having been returned. Acceptable for a live tail somebody is watching, and not
     * acceptable for anything that needs every line — which is what the log aggregator is
     * for.
     */
    public List<Entry> since(long after) {
        synchronized (entries) {
            List<Entry> result = new ArrayList<>();
            for (Entry entry : entries) {
                if (entry.sequence() > after) {
                    result.add(entry);
                }
            }
            return result;
        }
    }

    public long latestSequence() {
        return sequence.get();
    }

    private void record(ILoggingEvent event) {
        IThrowableProxy thrown = event.getThrowableProxy();
        String message = event.getFormattedMessage();
        if (thrown != null) {
            // Cause and message, never the stack: forty frames a line is unreadable.
            message = message + " | " + thrown.getClassName() + ": " + thrown.getMessage();
        }
        Entry entry = new Entry(
                sequence.incrementAndGet(),
                Instant.ofEpochMilli(event.getTimeStamp()),
                event.getLevel().toString(),
                shorten(event.getLoggerName()),
                event.getMDCPropertyMap().get(CorrelationIdFilter.MDC_KEY),
                message);
        synchronized (entries) {
            if (entries.size() >= CAPACITY) {
                entries.removeFirst();
            }
            entries.addLast(entry);
        }
    }

    /** com.lewiswalker.savings.account.AccountService -> c.l.s.account.AccountService */
    private static String shorten(String logger) {
        String[] parts = logger.split("\\.");
        if (parts.length <= 3) {
            return logger;
        }
        StringBuilder shortened = new StringBuilder();
        for (int i = 0; i < parts.length - 2; i++) {
            shortened.append(parts[i].charAt(0)).append('.');
        }
        return shortened.append(parts[parts.length - 2]).append('.')
                .append(parts[parts.length - 1]).toString();
    }

    private final class Appender extends AppenderBase<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent event) {
            record(event);
        }
    }
}
