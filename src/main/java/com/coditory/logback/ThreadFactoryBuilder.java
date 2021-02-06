package com.coditory.logback;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

import static com.coditory.logback.Preconditions.checkArgument;
import static java.lang.Thread.MAX_PRIORITY;
import static java.lang.Thread.MIN_PRIORITY;

class ThreadFactoryBuilder {
    public static ThreadFactoryBuilder threadFactoryBuilder(String nameFormat) {
        return new ThreadFactoryBuilder()
                .withNameFormat(nameFormat);
    }

    private String nameFormat = null;
    private Boolean daemon = null;
    private Integer priority = null;
    private UncaughtExceptionHandler uncaughtExceptionHandler = null;
    private ThreadFactory backingThreadFactory = null;

    public ThreadFactoryBuilder withNameFormat(String nameFormat) {
        checkNameFormat(nameFormat);
        this.nameFormat = nameFormat;
        return this;
    }

    public ThreadFactoryBuilder withDaemonThreads(boolean daemon) {
        this.daemon = daemon;
        return this;
    }

    public ThreadFactoryBuilder withPriority(int priority) {
        checkArgument(priority >= MIN_PRIORITY, "Expected: thread priority (%s) >= %s", priority, MIN_PRIORITY);
        checkArgument(priority <= MAX_PRIORITY, "Expected: thread priority (%s) <= %s", priority, MAX_PRIORITY);
        this.priority = priority;
        return this;
    }

    public ThreadFactoryBuilder withUncaughtExceptionHandler(UncaughtExceptionHandler uncaughtExceptionHandler) {
        this.uncaughtExceptionHandler = Preconditions.checkNotNull(uncaughtExceptionHandler, "Expected: uncaughtExceptionHandler != null");
        return this;
    }

    public ThreadFactoryBuilder withBackingThreadFactory(ThreadFactory backingThreadFactory) {
        this.backingThreadFactory = Preconditions.checkNotNull(backingThreadFactory, "Expected: backingThreadFactory != null");
        return this;
    }

    private void checkNameFormat(String format) {
        // fail fast if the format is bad or null
        format(format, 0);
    }

    private String format(String format, Object... args) {
        return String.format(Locale.ROOT, format, args);
    }

    ThreadFactory build() {
        return new InstrumentedThreadFactory(nameFormat, daemon, priority, uncaughtExceptionHandler, backingThreadFactory);
    }

    private static class InstrumentedThreadFactory implements ThreadFactory {
        private final String nameFormat;
        private final Boolean daemon;
        private final Integer priority;
        private final UncaughtExceptionHandler uncaughtExceptionHandler;
        private final ThreadFactory backingThreadFactory;
        private final AtomicLong count = new AtomicLong(0);

        private InstrumentedThreadFactory(
                String nameFormat,
                Boolean daemon,
                Integer priority,
                UncaughtExceptionHandler uncaughtExceptionHandler,
                ThreadFactory backingThreadFactory) {
            this.nameFormat = nameFormat;
            this.daemon = daemon;
            this.priority = priority;
            this.uncaughtExceptionHandler = uncaughtExceptionHandler;
            this.backingThreadFactory = backingThreadFactory != null
                    ? backingThreadFactory
                    : Executors.defaultThreadFactory();
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = backingThreadFactory.newThread(runnable);
            if (nameFormat != null) {
                thread.setName(format(nameFormat, count.getAndIncrement()));
            }
            if (daemon != null) {
                thread.setDaemon(daemon);
            }
            if (priority != null) {
                thread.setPriority(priority);
            }
            if (uncaughtExceptionHandler != null) {
                thread.setUncaughtExceptionHandler(uncaughtExceptionHandler);
            }
            return thread;
        }

        private String format(String format, Object... args) {
            return String.format(Locale.ROOT, format, args);
        }
    }
}
