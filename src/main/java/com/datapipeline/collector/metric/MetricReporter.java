package com.datapipeline.collector.metric;

import com.datapipeline.collector.failover.FailoverMetricReader;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.*;
import io.opentelemetry.sdk.internal.AttributesMap;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricReader;
import io.opentelemetry.sdk.resources.Resource;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class MetricReporter {

    private SdkMeterProvider sdk;
    private final Map<String, LongCounter> longCounterMap = new ConcurrentHashMap<>();
    private final Map<String, DoubleCounter> doubleCounterMap = new ConcurrentHashMap<>();
    private final Map<String, DoubleHistogram> doubleHistogramMap = new ConcurrentHashMap<>();
    private final Map<String, DoubleUpDownCounter> doubleUpDownCounterMap = new ConcurrentHashMap<>();


    private final Config config;

    public MetricReporter(Config config) {
        this.config = config;
    }

    private final List<MBeanObservers> jvmMetrics = new ArrayList<MBeanObservers>() {{
        add(new CPU());
        add(new BufferPools());
        add(new Classes());
        add(new GarbageCollector());
        add(new MemoryPools());
        add(new Threads());
    }};

    /**
     * 初始化SDK及相关默认指标
     */
    public void initEnv() {
        String hostname;
        try {
            hostname = System.getenv("COLLECTOR_SDK_IP");
            if (hostname == null) {
                hostname = InetAddress.getLocalHost().getHostAddress();
            }
        } catch (UnknownHostException e) {
            hostname = "127.0.0.1";
            e.printStackTrace();
        }
        Attributes attributes = Attributes.builder().put("instance", config.getInstance())
                .put("namespace", config.getNamespace())
                .put("service", config.getService())
                .put("hostname", hostname)
                .put("appName", config.getAppName())
                .build();
        Resource resource = Resource.create(attributes);
        //MetricExporter exporter = OtlpGrpcMetricExporter.builder().setEndpoint("http://".concat(config.getEndpoint())).setTimeout(Duration.ofSeconds(config.getTimeout())).build();
        //MetricReader reader = PeriodicMetricReader.builder(exporter).setInterval(config.getInterval(), TimeUnit.SECONDS).build();
        MetricReader reader = FailoverMetricReader.builder(config).setHostname(hostname).setInterval(config.getInterval(), TimeUnit.SECONDS).build();
        sdk = SdkMeterProvider.builder().setResource(resource).registerMetricReader(reader).build();
        for (MBeanObservers mBeanObservers : jvmMetrics) {
            mBeanObservers.registerObservers(sdk);
        }
    }

    public void recordCounter(String tag, String metric, String unit, String description, String[] attributes) {
        longCounterMap.computeIfAbsent(tag.concat(metric), name -> {
            Meter meter = sdk.meterBuilder(tag).build();
            return meter.counterBuilder(metric).setUnit(unit).setDescription(description).build();
        }).add(1, buildAttribute(attributes));
    }

    public void recordCounter(String tag, String metric) {
        longCounterMap.computeIfAbsent(tag.concat(metric), name -> {
            Meter meter = sdk.meterBuilder(tag).build();
            return meter.counterBuilder(metric).build();
        }).add(1);
    }

    public void recordCounter(String tag, String metric, double value, String unit, String description, String[] attributes) {
        doubleCounterMap.computeIfAbsent(tag.concat(metric), name -> {
            Meter meter = sdk.meterBuilder(tag).build();
            return meter.counterBuilder(metric).setUnit(unit).setDescription(description).ofDoubles().build();
        }).add(value, buildAttribute(attributes));
    }

    public void recordCounter(String tag, String metric, double value) {
        doubleCounterMap.computeIfAbsent(tag.concat(metric), name -> {
            Meter meter = sdk.meterBuilder(tag).build();
            return meter.counterBuilder(metric).ofDoubles().build();
        }).add(value);
    }

    public void recordHistogram(String tag, String metric, double value, String unit, String description, String[] attributes) {
        doubleHistogramMap.computeIfAbsent(tag.concat(metric), name -> {
            Meter meter = sdk.meterBuilder(tag).build();
            return meter.histogramBuilder(metric).setUnit(unit).setDescription(description).build();
        }).record(value, buildAttribute(attributes));
    }

    public void recordHistogram(String tag, String metric, double value) {
        doubleHistogramMap.computeIfAbsent(tag.concat(metric), name -> {
            Meter meter = sdk.meterBuilder(tag).build();
            return meter.histogramBuilder(metric).build();
        }).record(value);
    }

    public ObservableDoubleGauge recordGauge(String tag, String metric, Consumer<ObservableDoubleMeasurement> consumer, String unit, String description) {
        return sdk.meterBuilder(tag).build().gaugeBuilder(metric).setUnit(unit).setDescription(description).buildWithCallback(consumer);
    }

    public ObservableDoubleGauge recordGauge(String tag, String metric, Consumer<ObservableDoubleMeasurement> consumer) {
        return sdk.meterBuilder(tag).build().gaugeBuilder(metric).buildWithCallback(consumer);
    }

    public void recordUpDownCounter(String tag, String metric, double value, String unit, String description, String[] attributes) {
        doubleUpDownCounterMap.computeIfAbsent(tag.concat(metric), name -> {
            Meter meter = sdk.meterBuilder(tag).build();
            return meter.upDownCounterBuilder(metric).setUnit(unit).setDescription(description).ofDoubles().build();
        }).add(value, buildAttribute(attributes));
    }

    public void recordUpDownCounter(String tag, String metric, double value) {
        doubleUpDownCounterMap.computeIfAbsent(tag.concat(metric), name -> {
            Meter meter = sdk.meterBuilder(tag).build();
            return meter.upDownCounterBuilder(metric).ofDoubles().build();
        }).add(value);
    }

    private AttributesMap buildAttribute(String[] attributes) {
        AttributesMap attributesMap = AttributesMap.create(attributes.length, Integer.MAX_VALUE);
        for (String attr : attributes) {
            String[] str = attr.split("=");
            attributesMap.put(AttributeKey.stringKey(str[0]), str[1]);
        }
        return attributesMap;
    }
}
