package com.datapipeline.collector.failover;

import com.datapipeline.collector.metric.Config;
import io.opentelemetry.sdk.internal.DaemonThreadFactory;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class FailoverMetricReaderBuilder {
    static final long DEFAULT_SCHEDULE_DELAY_MINUTES = 1;

    private final Config config;
    private long intervalNanos = TimeUnit.MINUTES.toNanos(DEFAULT_SCHEDULE_DELAY_MINUTES);
    private String hostname;
    private ScheduledExecutorService executor;

    FailoverMetricReaderBuilder(Config config) {
        this.config = config;
    }

    /**
     * Sets the interval of reads. If unset, defaults to {@value DEFAULT_SCHEDULE_DELAY_MINUTES}min.
     */
    public FailoverMetricReaderBuilder setInterval(long interval, TimeUnit unit) {
        intervalNanos = unit.toNanos(interval);
        return this;
    }

    public FailoverMetricReaderBuilder setHostname(String hostname) {
        this.hostname = hostname;
        return this;
    }

    /**
     * Sets the interval of reads. If unset, defaults to {@value DEFAULT_SCHEDULE_DELAY_MINUTES}min.
     */
    public FailoverMetricReaderBuilder setInterval(Duration interval) {
        return setInterval(interval.toNanos(), TimeUnit.NANOSECONDS);
    }

    /**
     * Sets the {@link ScheduledExecutorService} to schedule reads on.
     */
    public FailoverMetricReaderBuilder setExecutor(ScheduledExecutorService executor) {
        this.executor = executor;
        return this;
    }

    /**
     * Build a {@link FailoverMetricReader} with the configuration of this builder.
     */
    public FailoverMetricReader build() {
        ScheduledExecutorService executor = this.executor;
        if (executor == null) {
            executor =
                    Executors.newScheduledThreadPool(2, new DaemonThreadFactory("FailoverMetricReader"));
        }
        return new FailoverMetricReader(config, hostname, intervalNanos, executor);
    }
}
