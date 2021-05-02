# Logback Filters

[![Build Status](https://github.com/coditory/logback-filters/workflows/Build/badge.svg?branch=master)](https://github.com/coditory/logback-filters/actions?query=workflow%3ABuild+branch%3Amaster)
[![Coverage Status](https://coveralls.io/repos/github/coditory/logback-filters/badge.svg?branch=master)](https://coveralls.io/github/coditory/logback-filters?branch=master)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/com.coditory.common/logback-filters/badge.svg)](https://mvnrepository.com/artifact/com.coditory.common/logback-filters)

Additional [filters](http://logback.qos.ch/manual/filters.html) for [Logback](http://logback.qos.ch) - Java logging
framework.

## Installation

Add to your `build.gradle`:

```gradle
dependencies {
    implementation "com.coditory.logback:logback-filters:0.1.0"
}
```

## AggregatingTurboFilter

Logback filter that aggregates multiple logs into single one. This filter was extracted
from [Hermes (message broker)](https://github.com/allegro/hermes/blob/master/hermes-common/src/main/java/pl/allegro/tech/hermes/infrastructure/logback/AggregatingTurboFilter.java)
and enriched with new capabilities.

Filter options:

- `reportingIntervalMillis` - time interval in which logs are passed to output. Be default, interval is set up to `10s`.
- `aggregationMessageToken` - token in the log message that triggers aggregation. By default, token filtration is
  disabled.
- `aggregatedLogger` - list of loggers that have aggregated logs

Filter drawbacks:

- Aggregated logs are not reported in order
- Aggregated logs have the same thread name `aggregating-filter-%d-thread-0`. `%d` is replaced with filter index in case
  there are multiple `AggregatingTurboFilters` defined.

Sample configuration:

```xml
<configuration>
    <turboFilter class="com.coditory.logback.AggregatingTurboFilter">
        <reportingIntervalMillis>3000</reportingIntervalMillis>
        <aggregatedLogger>com.coditory.sandbox.Sandbox</aggregatedLogger>
    </turboFilter>

    <appender name="STDOUT" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} [%thread] %-5level %logger{10} - %msg%n</pattern>
        </encoder>
    </appender>

    <logger name="com.coditory" level="debug"/>

    <root level="info">
        <appender-ref ref="STDOUT"/>
    </root>
</configuration>
```

## Aggregate by log message

To aggregate over 3s logs from `com.coditory.sandbox.SomeClass` by log message (with resolved parameters) use following
configuration:

```xml
<turboFilter class="com.coditory.logback.AggregatingTurboFilter">
    <reportingIntervalMillis>3000</reportingIntervalMillis>
    <aggregatedLogger>com.coditory.sandbox.Sandbox</aggregatedLogger>
</turboFilter>
```

Example:

```java
@Slf4j
class Sandbox {
    public static void main(String[] args) throws InterruptedException {
        log.info("Hello {}", "James");
        log.info("Hello {}", "Harry");
        log.info("Hello {}", "James");
        log.info("Hello {}", "Mary");
        log.info("Hello {}", "Mary", new RuntimeException("error"));
        log.info("Hello {}", "Mary");
        Thread.sleep(5000);
    }
}
// Output:
// 16:50:39.632 [aggregating-filter-0-thread-0] INFO c.c.s.Sandbox - Hello Mary [occurrences=3]
// java.lang.RuntimeException: error
//   at com.coditory.sandbox.Sandbox.main(Sandbox.java:14)
// 16:50:39.638 [aggregating-filter-0-thread-0] INFO c.c.s.Sandbox - Hello James [occurrences=2]
// 16:50:39.638 [aggregating-filter-0-thread-0] INFO c.c.s.Sandbox - Hello Harry [occurrences=1]
```

## Aggregate by log template

To aggregate logs from `com.coditory.sandbox.SomeClass` by log message template (without resolved parameters):

```xml
<turboFilter class="com.coditory.logback.AggregatingTurboFilter">
    <aggregationKey>template</aggregationKey>
    <aggregatedLogger>com.coditory.sandbox.SomeClass</aggregatedLogger>
</turboFilter>
```

Example:

```java
@Slf4j
class Sandbox {
  public static void main(String[] args) throws InterruptedException {
    log.info("Hello {}", "Mary");
    log.info("Hello {}", "Mary", new RuntimeException("error"));
    log.info("Hello {}", "Mary");
    log.info("Hello {}", "James");
    log.info("Hello {}", "Harry");
    log.info("Hello {}", "James");
    Thread.sleep(5000);
  }
}
// Output:
// 16:49:50.244 [aggregating-filter-0-thread-0] INFO c.c.s.Sandbox - Hello Mary [occurrences=6]
// java.lang.RuntimeException: error
//    at com.coditory.sandbox.Sandbox.main(Sandbox.java:11)
```

## Aggregate log messages with aggregation token

To aggregate logs from `com.coditory.sandbox.SomeClass` with message containing `[aggregate]` use following
configuration:

```xml
<turboFilter class="com.coditory.logback.AggregatingTurboFilter">
    <reportingIntervalMillis>3000</reportingIntervalMillis>
    <aggregationMessageToken>[aggregate]</aggregationMessageToken>
    <aggregatedLogger>com.coditory.sandbox.SomeClass</aggregatedLogger>
</turboFilter>
```

Example:

```java
@Slf4j
class Sandbox {
  public static void main(String[] args) throws InterruptedException {
    log.info("Hello {}", "James");
    log.info("[aggregate] Hello {}", "Mary");
    log.info("[aggregate] Hello {}", "Mary");
    log.info("[aggregate] Hello {}", "Mary");
    log.info("Hello {}", "Mary");
    log.info("Hello {}", "Mary");
    Thread.sleep(5000);
  }
}
// Output:
// 16:48:39.281 [main] INFO c.c.s.Sandbox - Hello James
// 16:48:39.284 [main] INFO c.c.s.Sandbox - Hello Mary
// 16:48:39.285 [main] INFO c.c.s.Sandbox - Hello Mary
// 16:48:42.248 [aggregating-filter-0-thread-0] INFO c.c.s.Sandbox - Hello Mary [occurrences=3]
```
