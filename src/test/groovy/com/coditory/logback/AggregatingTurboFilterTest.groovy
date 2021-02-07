package com.coditory.logback

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.core.spi.FilterReply
import com.coditory.logback.base.CapturingAppender
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import org.slf4j.MarkerFactory
import spock.lang.Retry
import spock.lang.Specification

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AggregatingTurboFilterTest extends Specification {
    Logger aggregatedLogger = (Logger) LoggerFactory.getLogger("aggregated-logger")
    AggregatingTurboFilter filter = createFilter(aggregatedLogger.name)
    CapturingAppender appender = new CapturingAppender()

    void setup() {
        aggregatedLogger.addAppender(appender)
    }

    def "should pass through logs that do not use aggregation"() {
        given:
            Logger notAggregatedLogger = (Logger) LoggerFactory.getLogger("not-aggregated-logger")
            notAggregatedLogger.addAppender(appender)
        when:
            FilterReply reply = filterLog(notAggregatedLogger, Level.ERROR, "Some problem")
        then:
            reply == FilterReply.NEUTRAL
    }

    def "should activate log aggregation on message token"() {
        given:
            Logger notAggregatedLogger = (Logger) LoggerFactory.getLogger("not-aggregated-logger")
            notAggregatedLogger.addAppender(appender)
            filter.addAggregatedLogger(notAggregatedLogger.name)
            filter.setAggregationMessageToken("[aggregate]")
        when:
            5.times { filterLog(notAggregatedLogger, Level.INFO, "Hello {}", ["James"]) }
            2.times { filterLog(notAggregatedLogger, Level.INFO, "[aggregate] Hello {}", ["Mary"]) }

        and:
            filter.report()

        then:
            appender.capturedEvents() == 1
            appender.countCapturedEvents("Hello {} [occurrences=2]", ["Mary"]) == 1
    }

    def "should activate log aggregation on message token from unregistered logger"() {
        given:
            Logger notAggregatedLogger = (Logger) LoggerFactory.getLogger("not-aggregated-logger")
            notAggregatedLogger.addAppender(appender)
            filter.setAggregationMessageToken("[aggregate]")
        when:
            5.times { filterLog(notAggregatedLogger, Level.INFO, "Hello {}", ["James"]) }
            2.times { filterLog(notAggregatedLogger, Level.INFO, "[aggregate] Hello {}", ["Mary"]) }

        and:
            filter.report()

        then:
            appender.capturedEvents() == 0
    }

    @Retry
    def "should report aggregated logs for configured logger"() {
        given:
            Logger aggregatedLogger = (Logger) LoggerFactory.getLogger("another-aggregated-logger")
            CapturingAppender appender = new CapturingAppender()
            AggregatingTurboFilter filter = createStartedFilter(aggregatedLogger.name, 50)
            aggregatedLogger.addAppender(appender)

        when:
            FilterReply reply1 = filter.decide(null, aggregatedLogger, Level.ERROR, "Some problem", null, null)
            FilterReply reply2 = filter.decide(null, aggregatedLogger, Level.ERROR, "Some problem", null, null)

        then: "the messages are denied by the filter"
            reply1 == FilterReply.DENY
            reply2 == FilterReply.DENY

        and: "later an aggregate is logged asynchronously by another thread"
            appender.waitForFirstLog()
            appender.capturedEvents() == 1
            appender.countCapturedEvents(Level.ERROR, "Some problem [occurrences=2]") == 1

        cleanup:
            filter.stop()
    }

    def "should report aggregated logs grouped by params"() {
        when:
            5.times { filterLog(Level.ERROR, "Hello {}", ["James"]) }
            2.times { filterLog(Level.ERROR, "Hello {}", ["Mary"]) }

        and:
            filter.report()

        then:
            appender.capturedEvents() == 2
            appender.countCapturedEvents("Hello {} [occurrences=5]", ["James"]) == 1
            appender.countCapturedEvents("Hello {} [occurrences=2]", ["Mary"]) == 1
    }

    def "should group logs by log level"() {
        when:
            filterLog(Level.WARN, "Some problem")
            filterLog(Level.ERROR, "Some problem")

        and:
            filter.report()

        then:
            appender.capturedEvents() == 2
            appender.countCapturedEvents("Some problem [occurrences=1]") == 2
            appender.countCapturedEvents(Level.WARN) == 1
            appender.countCapturedEvents(Level.ERROR) == 1
    }

    def "should group logs by template and log with last parameters"() {
        given:
            filter.setAggregationKey("template")
        when:
            5.times { filterLog(Level.ERROR, "Hello {}", ["James"]) }
            2.times { filterLog(Level.ERROR, "Hello {}", ["Mary"]) }

        and:
            filter.report()

        then:
            appender.capturedEvents() == 1
            appender.countCapturedEvents("Hello {} [occurrences=7]", ["Mary"]) == 1
    }

    def "should group logs by template and use last exception with matching params"() {
        given:
            filter.setAggregationKey("template")
        when:
            filterLog(Level.ERROR, "Some problem", [new RuntimeException("problem!")])
            filterLog(Level.ERROR, "Some problem", [new RuntimeException("another problem!")])
            filterLog(Level.ERROR, "An error caused by {}", ["James1", new RuntimeException("abort 1!")])
            filterLog(Level.ERROR, "An error caused by {}", ["James2"], new RuntimeException("abort 2!"))
            filterLog(Level.ERROR, "An error caused by {}", ["James3"])

        and:
            filter.report()

        then:
            appender.capturedEvents() == 2
            appender.countCapturedEvents("Some problem [occurrences=2]", [], "another problem!") == 1
            appender.countCapturedEvents("An error caused by {} [occurrences=3]", ["James2"], "abort 2!") == 1
    }

    def "should group logs by marker"() {
        given:
            Marker myMarker = MarkerFactory.getMarker("abc")

        when:
            filter.decide(myMarker, aggregatedLogger, Level.WARN, "Some problem", null, null)
            filter.decide(null, aggregatedLogger, Level.WARN, "Some problem", null, null)

        and:
            filter.report()

        then:
            appender.capturedEvents() == 2
            appender.countCapturedEvents("Some problem [occurrences=1]") == 2
            appender.countCapturedEvents(myMarker) == 1
            appender.countCapturedEvents(AggregatingTurboFilter.MARKER) == 1
    }

    def "should log last exception"() {
        when:
            filterLog(Level.ERROR, "Some problem", null, new RuntimeException("forgotten exception"))
            filterLog(Level.ERROR, "Some problem", null, new RuntimeException("saved exception"))
            filterLog(Level.ERROR, "Some problem")

        and:
            filter.report()

        then:
            appender.capturedEvents() == 1
            appender.countCapturedEvents(Level.ERROR, "Some problem [occurrences=3]", null, "saved exception") == 1
    }

    def "should log an exception from info level"() {
        when:
            filterLog(Level.INFO, "Some problem", null, new RuntimeException("some exception"))

        and:
            filter.report()

        then:
            appender.capturedEvents() == 1
            appender.countCapturedEvents(Level.INFO, "Some problem [occurrences=1]", null, "some exception") == 1
    }

    def "should not report when there are no logs"() {
        when:
            filterLog(Level.ERROR, "Some problem")

        and:
            filter.report()

        then:
            appender.capturedEvents() == 1
            appender.countCapturedEvents(Level.ERROR, "Some problem [occurrences=1]") == 1
            appender.reset()

        when:
            filter.report()

        then:
            appender.capturedEvents() == 0
    }

    def "should group logs by full message and use last exception"() {
        when:
            filterLog(Level.ERROR, "Some problem", [new RuntimeException("problem!")])
            filterLog(Level.ERROR, "Some problem", [new RuntimeException("another problem!")])
            filterLog(Level.ERROR, "An error caused by {}", ["James", new RuntimeException("abort 1!")])
            filterLog(Level.ERROR, "An error caused by {}", ["James"], new RuntimeException("abort 2!"))
            filterLog(Level.ERROR, "An error caused by {}", ["James"])

        and:
            filter.report()

        then:
            appender.capturedEvents() == 2
            appender.countCapturedEvents("Some problem [occurrences=2]", [], "another problem!") == 1
            appender.countCapturedEvents("An error caused by {} [occurrences=3]", ["James"], "abort 2!") == 1
    }

    def "should allow multithreaded access"() {
        given:
            int threadsCount = 5
            CountDownLatch latch = new CountDownLatch(threadsCount)
            int logsPerThread = 1_000
            ExecutorService executor = Executors.newFixedThreadPool(threadsCount)

        when:
            threadsCount.times {
                executor.submit {
                    logsPerThread.times { filterLog(Level.ERROR, "An error") }
                    latch.countDown()
                }
            }

        then:
            latch.await(2, TimeUnit.SECONDS)

        and:
            filter.report()

        then:
            appender.capturedEvents() == 1
            appender.countCapturedEvents("An error [occurrences=${threadsCount * logsPerThread}]") == 1
    }

    def "should allow multithreaded access while reporting simultaneously"() {
        given:
            int threadsCount = 5
            CountDownLatch latch = new CountDownLatch(threadsCount)
            int logsPerThread = 10_000
            ExecutorService executor = Executors.newFixedThreadPool(threadsCount)

            AggregatingTurboFilter filter = createFilter(aggregatedLogger.name)
            filter.setReportingIntervalMillis(10) // small reporting interval
            filter.start()

        when:
            threadsCount.times {
                executor.submit(
                        {
                            logsPerThread.times {
                                filter.decide(null, aggregatedLogger, Level.ERROR, "An error", null, null)
                            }
                            latch.countDown()
                        }
                )
            }

        then: "we wait for all threads to stop logging"
            latch.await(5, TimeUnit.SECONDS)

        and: "make sure all messages are flushed"
            filter.report()

        then:
            appender.getLoggerCalls() > 1
            appender.getOccurrences() == threadsCount * logsPerThread

        cleanup:
            filter.stop()
    }

    private filterLog(Level level, String message, List<Object> params = null, Throwable exception = null) {
        filterLog(aggregatedLogger, level, message, params, exception)
    }

    private filterLog(Logger logger, Level level, String message, List<Object> params = null, Throwable exception = null) {
        filter.decide(null, logger, level, message, params?.toArray(), exception)
    }

    private AggregatingTurboFilter createFilter(String loggerName) {
        AggregatingTurboFilter filter = new AggregatingTurboFilter()
        filter.addAggregatedLogger(loggerName)
        return filter
    }

    private AggregatingTurboFilter createStartedFilter(String loggerName, int reportingInterval) {
        AggregatingTurboFilter filter = createFilter(loggerName)
        filter.reportingIntervalMillis = reportingInterval
        filter.start()
        return filter
    }
}
