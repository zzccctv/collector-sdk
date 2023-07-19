package com.datapipeline.collector.failover;

import com.datapipeline.collector.metric.Config;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.metrics.Aggregation;
import io.opentelemetry.sdk.metrics.InstrumentType;
import io.opentelemetry.sdk.metrics.data.AggregationTemporality;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.metrics.export.*;
import io.opentelemetry.sdk.metrics.internal.export.MetricProducer;

import java.net.HttpURLConnection;
import java.net.URL;
import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class FailoverMetricReader implements MetricReader {
    private static final Logger logger = Logger.getLogger(FailoverMetricReader.class.getName());

    private MetricExporter exporter;
    private final long intervalNanos;
    private final ScheduledExecutorService scheduler;
    private final FailoverMetricReader.Scheduled scheduled;
    private final Object lock = new Object();

    private volatile MetricProducer metricProducer = MetricProducer.noop();
    private volatile ScheduledFuture<?> scheduledFuture;
    private final String[] endpoint;
    private final long timeout;
    private int index = -1;
    private final String hostname;
    private String localEndpoint;

    public static FailoverMetricReader create(Config config) {
        return builder(config).build();
    }


    public static FailoverMetricReaderBuilder builder(Config config) {
        return new FailoverMetricReaderBuilder(config);
    }

    FailoverMetricReader(
            Config config, String hostname, long intervalNanos, ScheduledExecutorService scheduler) {
        this.hostname = hostname;
        this.timeout = config.getTimeout();
        this.endpoint = config.getEndpoint().split(",");
        this.intervalNanos = intervalNanos;
        this.scheduler = scheduler;
        this.scheduled = new FailoverMetricReader.Scheduled();
        this.initExporter();
    }

    private void initExporter() {
        this.localEndpoint = hostname.concat(":4317");
        for (String et : endpoint) {
            if (et.startsWith(hostname)) {
                this.localEndpoint = et;
                break;
            }
        }
        this.exporter = OtlpGrpcMetricExporter.builder().setEndpoint(format(this.localEndpoint)).setTimeout(Duration.ofSeconds(timeout)).build();
    }

    @Override
    public void register(CollectionRegistration registration) {
        this.metricProducer = MetricProducer.asMetricProducer(registration);
        start();
    }

    @Override
    public CompletableResultCode forceFlush() {
        return scheduled.doRun();
    }

    @Override
    public AggregationTemporality getAggregationTemporality(InstrumentType instrumentType) {
        return exporter.getAggregationTemporality(instrumentType);
    }

    @Override
    public Aggregation getDefaultAggregation(InstrumentType instrumentType) {
        return exporter.getDefaultAggregation(instrumentType);
    }

    private void failover() {
        //访问本地的collector失败进行failover选择
        for (int i = 0; i < endpoint.length; i++) {
            if (index < i && !endpoint[i].equals(this.localEndpoint)) {
                exporter = OtlpGrpcMetricExporter.builder().setEndpoint(format(this.endpoint[i])).setTimeout(Duration.ofSeconds(timeout)).build();
                index = i;
                logger.log(Level.INFO, "failover to remote endpoint " + this.endpoint[i]);
                break;
            }
        }
    }

    @Override
    public CompletableResultCode shutdown() {
        CompletableResultCode result = new CompletableResultCode();
        ScheduledFuture<?> scheduledFuture = this.scheduledFuture;
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
        }
        scheduler.shutdown();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
            CompletableResultCode flushResult = scheduled.doRun();
            flushResult.join(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // force a shutdown if the export hasn't finished.
            scheduler.shutdownNow();
            // reset the interrupted status
            Thread.currentThread().interrupt();
        } finally {
            CompletableResultCode shutdownResult = scheduled.shutdown();
            shutdownResult.whenComplete(
                    () -> {
                        if (!shutdownResult.isSuccess()) {
                            result.fail();
                        } else {
                            result.succeed();
                        }
                    });
        }
        return result;
    }

    @Override
    public String toString() {
        return "FailoverMetricReader{"
                + "exporter="
                + exporter
                + ", intervalNanos="
                + intervalNanos
                + '}';
    }

    void start() {
        synchronized (lock) {
            if (scheduledFuture != null) {
                return;
            }
            scheduledFuture = scheduler.scheduleAtFixedRate(scheduled, intervalNanos, intervalNanos, TimeUnit.NANOSECONDS);
            scheduler.scheduleAtFixedRate(() -> {
                //endpoint与本机的endpoint不一致,探测本地的collector是否可用,
                if (index != -1) {
                    try {
                        HttpURLConnection connection = (HttpURLConnection) new URL(format(hostname.concat(":13133/status"))).openConnection();
                        connection.setRequestMethod("GET");
                        if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                            exporter = OtlpGrpcMetricExporter.builder().setEndpoint(format(this.localEndpoint)).setTimeout(Duration.ofSeconds(timeout)).build();
                            logger.log(Level.INFO, "access local endpoint " + this.localEndpoint);
                            index = -1;
                        } else {
                            logger.log(Level.SEVERE, "access local endpoint failed");
                        }
                        connection.disconnect();
                    } catch (Exception e) {
                        logger.log(Level.SEVERE, "access local endpoint failed");
                    }
                }
            }, 1, 10, TimeUnit.SECONDS);
        }
    }

    private final class Scheduled implements Runnable {
        private final AtomicBoolean exportAvailable = new AtomicBoolean(true);

        private Scheduled() {
        }

        @Override
        public void run() {
            // Ignore the CompletableResultCode from doRun() in order to keep run() asynchronous
            doRun();
        }

        // Runs a collect + export cycle.
        CompletableResultCode doRun() {
            CompletableResultCode flushResult = new CompletableResultCode();
            if (exportAvailable.compareAndSet(true, false)) {
                try {
                    Collection<MetricData> metricData = metricProducer.collectAllMetrics();
                    if (metricData.isEmpty()) {
                        logger.log(Level.FINE, "No metric data to export - skipping export.");
                        flushResult.succeed();
                        exportAvailable.set(true);
                    } else {
                        CompletableResultCode result = exporter.export(metricData);
                        result.whenComplete(
                                () -> {
                                    if (!result.isSuccess()) {
                                        failover();
                                        logger.log(Level.FINE, "Exporter failed");
                                    }
                                    flushResult.succeed();
                                    exportAvailable.set(true);
                                });
                    }
                } catch (Throwable t) {
                    exportAvailable.set(true);
                    logger.log(Level.WARNING, "Exporter threw an Exception", t);
                    flushResult.fail();
                }
            } else {
                logger.log(Level.FINE, "Exporter busy. Dropping metrics.");
                flushResult.fail();
            }
            return flushResult;
        }

        CompletableResultCode shutdown() {
            return exporter.shutdown();
        }
    }

    private String format(String hostname) {
        return String.format("http://%s", hostname);
    }
}
