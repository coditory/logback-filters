package com.coditory.logback

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.Appender
import ch.qos.logback.core.spi.FilterReply
import org.slf4j.LoggerFactory
import org.slf4j.Marker
import org.slf4j.MarkerFactory
import spock.lang.Specification
import spock.util.concurrent.BlockingVariable

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.regex.Matcher

class AggregatingTurboFilterTest extends Specification {
    Logger aggregatedLogger = (Logger) LoggerFactory.getLogger("aggregated-logger")
    AggregatingTurboFilter filter = createFilter(aggregatedLogger.name)
    List<LoggingEvent> capturedEvents = []
    Appender appender = Mock(Appender) {
        doAppend(_) >> { LoggingEvent event -> capturedEvents << event }
    }

    void setup() {
        aggregatedLogger.addAppender(appender)
    }

    def "should pass through logs without when AggregatingTurboFilter is disabled"() {
        given:
            Logger notAggregatedLogger = (Logger) LoggerFactory.getLogger("not-aggregated-logger")
        when:
            FilterReply reply = filter.decide(null, notAggregatedLogger, Level.ERROR, "Some problem", null, null)
        then:
            reply == FilterReply.NEUTRAL
    }

    def "should report aggregated logs for configured logger"() {
        given:
            AggregatingTurboFilter filter = createStartedFilter(aggregatedLogger.name, 50)
            BlockingVariable<Boolean> appenderCalled = new BlockingVariable<Boolean>(200, TimeUnit.MILLISECONDS)
            LoggingEvent capturedEvent = null
            Appender appender = [doAppend: { event ->
                capturedEvent = event
                appenderCalled.set(true)
            }] as Appender
            aggregatedLogger.addAppender(appender)

        when:
            FilterReply reply1 = filter.decide(null, aggregatedLogger, Level.ERROR, "Some problem", null, null)
            FilterReply reply2 = filter.decide(null, aggregatedLogger, Level.ERROR, "Some problem", null, null)

        then: "the messages are denied by the filter"
            reply1 == FilterReply.DENY
            reply2 == FilterReply.DENY

        and: "later an aggregate is logged asynchronously by another thread"
            appenderCalled.get()
            capturedEvent.level == Level.ERROR
            capturedEvent.message == "Some problem [occurrences=2]"

        cleanup:
            filter.stop()
    }

    def "should report aggregated logs grouped by params"() {
        when:
            5.times {
                filter.decide(null, aggregatedLogger, Level.ERROR, "Hello {}", (Object[]) ["James"], null)
            }
            2.times {
                filter.decide(null, aggregatedLogger, Level.ERROR, "Hello {}", (Object[]) ["Mary"], null)
            }

        and:
            filter.report()

        then:
            capturedEvents.size() == 2
            capturedEvents.count {
                it.message == "Hello {} [occurrences=5]" && it.argumentArray[0] == "James"
            } == 1
            capturedEvents.count {
                it.message == "Hello {} [occurrences=2]" && it.argumentArray[0] == "Mary"
            } == 1
    }

    def "should group logs by log level"() {
        when:
            filter.decide(null, aggregatedLogger, Level.WARN, "Some problem", null, null)
            filter.decide(null, aggregatedLogger, Level.ERROR, "Some problem", null, null)

        and:
            filter.report()

        then:
            capturedEvents.size() == 2
            capturedEvents.count { it.message == "Some problem [occurrences=1]" } == 2
            capturedEvents.count { it.level == Level.WARN } == 1
            capturedEvents.count { it.level == Level.ERROR } == 1
    }

    def "should group logs by template and log with last parameters"() {
        given:
            filter.setAggregationKey("template")
        when:
            5.times {
                filter.decide(null, aggregatedLogger, Level.ERROR, "Hello {}", (Object[]) ["James"], null)
            }
            2.times {
                filter.decide(null, aggregatedLogger, Level.ERROR, "Hello {}", (Object[]) ["Mary"], null)
            }

        and:
            filter.report()

        then:
            capturedEvents.size() == 1
            capturedEvents.count {
                it.message == "Hello {} [occurrences=7]" && it.argumentArray[0] == "Mary"
            } == 1
    }

    def "should group logs by template and use last exception with matching params"() {
        given:
            filter.setAggregationKey("template")
        when:
            filter.decide(null, aggregatedLogger, Level.ERROR, "Some problem", (Object[]) [new RuntimeException("problem!")], null)
            filter.decide(null, aggregatedLogger, Level.ERROR, "Some problem", (Object[]) [new RuntimeException("another problem!")], null)
            filter.decide(null, aggregatedLogger, Level.ERROR, "An error caused by {}", (Object[]) ["James1", new RuntimeException("abort 1!")], null)
            filter.decide(null, aggregatedLogger, Level.ERROR, "An error caused by {}", (Object[]) ["James2"], new RuntimeException("abort 2!"))
            filter.decide(null, aggregatedLogger, Level.ERROR, "An error caused by {}", (Object[]) ["James3"], null)

        and:
            filter.report()

        then:
            capturedEvents.size() == 2
            capturedEvents.count {
                it.message == "Some problem [occurrences=2]" && it.argumentArray.size() == 0 &&
                        it.throwableProxy.message == "another problem!"
            } == 1
            capturedEvents.count {
                it.message == "An error caused by {} [occurrences=3]" &&
                        it.argumentArray[0] == "James2" && it.throwableProxy.message == "abort 2!"
            } == 1

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
            capturedEvents.size() == 2
            capturedEvents.count { it.message == "Some problem [occurrences=1]" } == 2
            capturedEvents.count { it.marker == myMarker } == 1
            capturedEvents.count { it.marker == AggregatingTurboFilter.MARKER } == 1
    }

    def "should log last exception"() {
        when:
            filter.decide(null, aggregatedLogger, Level.ERROR, "Some problem", null, new RuntimeException("forgotten exception"))
            filter.decide(null, aggregatedLogger, Level.ERROR, "Some problem", null, new RuntimeException("saved exception"))
            filter.decide(null, aggregatedLogger, Level.ERROR, "Some problem", null, null)

        and:
            filter.report()

        then:
            1 * appender.doAppend(_) >> { LoggingEvent event ->
                assert event.level == Level.ERROR
                assert event.message == "Some problem [occurrences=3]"
                assert event.throwableProxy.message == "saved exception"
            }
    }

    def "should log an exception from info level"() {
        when:
            filter.decide(null, aggregatedLogger, Level.INFO, "Some problem", null, new RuntimeException("some exception"))

        and:
            filter.report()

        then:
            1 * appender.doAppend(_) >> { LoggingEvent event ->
                assert event.level == Level.INFO
                assert event.message == "Some problem [occurrences=1]"
                assert event.throwableProxy.message == "some exception"
            }
    }

    def "should not report when there are no logs"() {
        when:
            filter.decide(null, aggregatedLogger, Level.ERROR, "Some problem", null, null)

        and:
            filter.report()

        then:
            1 * appender.doAppend(_) >> { LoggingEvent event ->
                assert event.level == Level.ERROR
                assert event.message == "Some problem [occurrences=1]"
            }

        when:
            filter.report()

        then:
            0 * appender.doAppend(_)
    }

    def "should group logs by full message and use last exception"() {
        when:
            filter.decide(null, aggregatedLogger, Level.ERROR, "Some problem", (Object[]) [new RuntimeException("problem!")], null)
            filter.decide(null, aggregatedLogger, Level.ERROR, "Some problem", (Object[]) [new RuntimeException("another problem!")], null)
            filter.decide(null, aggregatedLogger, Level.ERROR, "An error caused by {}", (Object[]) ["James", new RuntimeException("abort 1!")], null)
            filter.decide(null, aggregatedLogger, Level.ERROR, "An error caused by {}", (Object[]) ["James"], new RuntimeException("abort 2!"))
            filter.decide(null, aggregatedLogger, Level.ERROR, "An error caused by {}", (Object[]) ["James"], null)

        and:
            filter.report()

        then:
            capturedEvents.size() == 2
            capturedEvents.count {
                it.message == "Some problem [occurrences=2]" && it.argumentArray.size() == 0 &&
                        it.throwableProxy.message == "another problem!"
            } == 1
            capturedEvents.count {
                it.message == "An error caused by {} [occurrences=3]" &&
                        it.argumentArray[0] == "James" && it.throwableProxy.message == "abort 2!"
            } == 1

    }

    def "should allow multithreaded access"() {
        given:
            int threadsCount = 5
            CountDownLatch latch = new CountDownLatch(threadsCount)
            int logsPerThread = 1_000
            ExecutorService executor = Executors.newFixedThreadPool(threadsCount)
            Appender appender = Mock(Appender)
            aggregatedLogger.addAppender(appender)

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

        then:
            latch.await(2, TimeUnit.SECONDS)

        and:
            filter.report()

        then:
            1 * appender.doAppend(_) >> { LoggingEvent event ->
                assert event.message == "An error [occurrences=${threadsCount * logsPerThread}]"
            }
    }

    def "should allow multithreaded access while reporting simultaneously"() {
        given:
            AtomicInteger loggerCalls = new AtomicInteger()
            AtomicInteger count = new AtomicInteger()
            String countRegexp = /An error \[occurrences\=(\d+)\]/

            int threadsCount = 5
            CountDownLatch latch = new CountDownLatch(threadsCount)
            int logsPerThread = 10_000
            ExecutorService executor = Executors.newFixedThreadPool(threadsCount)

            AggregatingTurboFilter filter = createFilter(aggregatedLogger.name)
            filter.setReportingIntervalMillis(10) // small reporting interval
            filter.start()

            Appender appender = [doAppend: { LoggingEvent event ->
                loggerCalls.incrementAndGet()
                Matcher matcher = (event.message =~ countRegexp)
                // we need to parse the number of occurrences
                assert matcher.matches()
                count.addAndGet(matcher.group(1) as Integer)
            }] as Appender
            aggregatedLogger.addAppender(appender)

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
            loggerCalls.get() > 1
            count.get() == threadsCount * logsPerThread

        cleanup:
            filter.stop()
    }

    AggregatingTurboFilter createFilter(String loggerName) {
        AggregatingTurboFilter filter = new AggregatingTurboFilter()
        filter.addAggregatedLogger(loggerName)
        return filter
    }

    AggregatingTurboFilter createStartedFilter(String loggerName, int reportingInterval) {
        AggregatingTurboFilter filter = createFilter(loggerName)
        filter.reportingIntervalMillis = reportingInterval
        filter.start()
        return filter
    }
}
