package com.coditory.logback;


import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.LongAdder;

import static com.coditory.logback.ThreadFactoryBuilder.threadFactoryBuilder;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * This class is an implementation of TurboFilter interface that allows messages of configured loggers
 * to be aggregated and logged only once with specified time interval.
 * Logged message contains a suffix "[occurrences=N]" with a number of logged events.
 * <p>
 * The logging part is a little bit tricky, as the TurboFilter interface does not support a way of logging out of the box,
 * it just tells whether a logged message should be passed further or not.
 * In order to logging be possible from the TurboFilter a small trick has to be applied, the logged message is enriched
 * with a custom Marker which is checked by the filter itself, so it won't filter it's own messages
 * and we manage to avoid recursion.
 * <p>
 * An instance of AggregatingTurboFilter starts it's own scheduled executor service with a single thread
 * that logs the messages asynchronously.
 * <p>
 * Example logback configuration:
 * <pre>{@code
 *  <configuration>
 *      ...
 *      <turboFilter class="com.coditory.logback.AggregatingTurboFilter">
 *          <reportingIntervalMillis>10000</reportingIntervalMillis>
 *          <aggregatedLogger>org.apache.zookeeper.ClientCnxn</aggregatedLogger>
 *          ...
 *      </turboFilter>
 *      ...
 * </configuration>
 * }</pre>
 */
public class AggregatingTurboFilter extends TurboFilter {
    static final Marker MARKER = MarkerFactory.getMarker("AggregatingTurboFilterMarker");
    private ScheduledExecutorService executorService;
    private String aggregationKey = "message";
    private String aggregationMessageToken = null;
    private final Map<Logger, LoggerAggregates> logAggregates = new ConcurrentHashMap<>();
    private final List<String> aggregatedLogger = new ArrayList<>();
    private long reportingIntervalMillis = 10_000;
    private static final LongAdder filterClassCounter = new LongAdder();

    @Override
    public void start() {
        super.start();
        if (!aggregatedLogger.isEmpty()) {
            ThreadFactory threadFactory = threadFactoryBuilder("aggregating-filter-thread-%d")
                    .build();
            filterClassCounter.increment();
            executorService = Executors.newSingleThreadScheduledExecutor(threadFactory);
            executorService.scheduleAtFixedRate(this::report, 0L, reportingIntervalMillis, MILLISECONDS);
        }
    }

    @Override
    public void stop() {
        executorService.shutdownNow();
        super.stop();
    }

    void report() {
        logAggregates.forEach((logger, loggerAggregates) -> log(logger, loggerAggregates.aggregates));
    }

    private void log(Logger logger, Map<LoggingEventKey, AggregateSummary> aggregates) {
        aggregates.keySet().forEach(entry ->
                aggregates.computeIfPresent(entry, (key, summary) -> {
                    log(logger, key, summary);
                    return null;
                })
        );
    }

    private void log(Logger logger, LoggingEventKey key, AggregateSummary summary) {
        logger.log(
                key.marker,
                Logger.FQCN,
                key.level,
                key.message + " [occurrences=" + summary.logsCount + "]",
                summary.lastParams,
                summary.lastException
        );
    }

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String logMessage, Object[] logParams, Throwable logException) {
        if (isNeutral(marker, logger, level, logMessage)) {
            return FilterReply.NEUTRAL;
        }
        String message = hasAggregationToken(logMessage)
                ? removeAggregationToken(logMessage)
                : logMessage;
        Throwable exception = logException != null
                ? logException
                : extractLastThrowableParam(logParams);
        Object[] parameters = logException == null && exception != null
                ? Arrays.copyOfRange(logParams, 0, logParams.length - 1)
                : logParams;
        boolean compareParams = !"template".equalsIgnoreCase(aggregationKey);
        LoggingEventKey loggingEventKey = new LoggingEventKey(message, parameters, level, getEnrichedMarker(marker), compareParams);
        logAggregates.computeIfAbsent(logger, l -> new LoggerAggregates())
                .aggregates.merge(
                loggingEventKey,
                new AggregateSummary(logException, parameters),
                (currentAggregate, emptyAggregate) -> currentAggregate.aggregate(exception, parameters)
        );
        return FilterReply.DENY;
    }

    private Throwable extractLastThrowableParam(Object[] params) {
        Object last = params == null || params.length == 0
                ? null
                : params[params.length - 1];
        return last instanceof Throwable
                ? (Throwable) last
                : null;

    }

    private boolean isNeutral(Marker marker, Logger logger, Level level, String message) {
        return (logger == null || level == null || message == null)
                || isAggregatedLog(marker)
                || (!aggregatedLogger.contains(logger.getName()) && !hasAggregationToken(message));
    }

    private boolean hasAggregationToken(String message) {
        return aggregationMessageToken != null
                && !aggregationMessageToken.isEmpty()
                && message.contains(aggregationMessageToken);
    }

    private String removeAggregationToken(String message) {
        String withoutToken = message.replace(aggregationMessageToken, "");
        return message.startsWith(aggregationMessageToken) || message.endsWith(aggregationMessageToken)
                ? withoutToken.trim()
                : withoutToken;
    }

    private boolean isAggregatedLog(Marker marker) {
        return marker != null
                && (marker.equals(MARKER)
                || marker.contains(MARKER));
    }

    private Marker getEnrichedMarker(Marker marker) {
        if (marker == null) {
            return MARKER;
        }
        marker.add(MARKER);
        return marker;
    }

    public void addAggregatedLogger(String logger) {
        this.aggregatedLogger.add(logger);
    }

    public long getReportingIntervalMillis() {
        return reportingIntervalMillis;
    }

    public void setReportingIntervalMillis(long reportingIntervalMillis) {
        this.reportingIntervalMillis = reportingIntervalMillis;
    }

    public String getAggregationKey() {
        return aggregationKey;
    }

    public void setAggregationKey(String aggregationKey) {
        this.aggregationKey = aggregationKey;
    }

    public String getAggregationMessageToken() {
        return aggregationMessageToken;
    }

    public void setAggregationMessageToken(String aggregationMessageToken) {
        this.aggregationMessageToken = aggregationMessageToken;
    }

    private static class LoggerAggregates {

        private final Map<LoggingEventKey, AggregateSummary> aggregates = new ConcurrentHashMap<>();
    }

    private static class AggregateSummary {
        private final int logsCount;
        private final Throwable lastException;
        private final Object[] lastParams;

        private AggregateSummary(Throwable lastException, Object[] lastParams) {
            this(1, lastException, lastParams);
        }

        private AggregateSummary(int logsCount, Throwable lastException, Object[] lastParams) {
            this.logsCount = logsCount;
            this.lastException = lastException;
            this.lastParams = lastParams;
        }

        private AggregateSummary aggregate(Throwable lastException, Object[] lastParams) {
            Throwable exception = lastException != null
                    ? lastException
                    : this.lastException;
            Object[] params = lastException != null || this.lastException == null
                    ? lastParams
                    : this.lastParams;
            return new AggregateSummary(this.logsCount + 1, exception, params);
        }
    }

    private static class LoggingEventKey {
        private final String message;
        private final int level;
        private final Marker marker;
        private final Object[] params;
        private final boolean compareParams;

        LoggingEventKey(String message, Object[] params, Level level, Marker marker, boolean compareParams) {
            this.message = message;
            this.params = params;
            this.level = Level.toLocationAwareLoggerInteger(level);
            this.marker = marker;
            this.compareParams = compareParams;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            LoggingEventKey that = (LoggingEventKey) o;
            return level == that.level &&
                    Objects.equals(message, that.message) &&
                    Objects.equals(marker, that.marker) &&
                    (!compareParams || Arrays.equals(params, that.params));
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(message, level, marker);
            if (compareParams) {
                result = 31 * result + Arrays.hashCode(params);
            }
            return result;
        }
    }
}
