package com.lewiswalker.savings.integration.logstream;

import com.lewiswalker.savings.platform.observability.CorrelationIdFilter;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.pattern.Abbreviator;
import ch.qos.logback.classic.pattern.TargetLengthBasedClassNameAbbreviator;
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
 */
@Component
public class LogTail {
    // Magic numbers but I don't want to over-engineer a mock thing!
    private static final Abbreviator ABBREVIATOR = new TargetLengthBasedClassNameAbbreviator(39);

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
                ABBREVIATOR.abbreviate(event.getLoggerName()),
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

    private final class Appender extends AppenderBase<ILoggingEvent> {
        @Override
        protected void append(ILoggingEvent event) {
            record(event);
        }
    }
}
