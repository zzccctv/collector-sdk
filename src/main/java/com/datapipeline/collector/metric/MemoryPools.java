package com.datapipeline.collector.metric;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;

import java.lang.management.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public class MemoryPools implements MBeanObservers {

    private static final AttributeKey<String> TYPE_KEY = AttributeKey.stringKey("type");
    private static final AttributeKey<String> POOL_KEY = AttributeKey.stringKey("pool");

    private static final String HEAP = "heap";
    private static final String NON_HEAP = "non_heap";

    @Override
    public void registerObservers(SdkMeterProvider sdk) {
        registerObservers(sdk, ManagementFactory.getMemoryPoolMXBeans());
    }

    private void registerObservers(SdkMeterProvider sdk, List<MemoryPoolMXBean> poolBeans) {
        Meter meter = sdk.meterBuilder(INSTRUMENTATION_NAME).build();
        meter
                .upDownCounterBuilder("process.runtime.jvm.memory.usage")
                .setDescription("Measure of memory used")
                .setUnit("By")
                .buildWithCallback(callback(poolBeans, MemoryPoolMXBean::getUsage, MemoryUsage::getUsed));

        meter
                .upDownCounterBuilder("process.runtime.jvm.memory.init")
                .setDescription("Measure of initial memory requested")
                .setUnit("By")
                .buildWithCallback(callback(poolBeans, MemoryPoolMXBean::getUsage, MemoryUsage::getInit));

        meter
                .upDownCounterBuilder("process.runtime.jvm.memory.committed")
                .setDescription("Measure of memory committed")
                .setUnit("By")
                .buildWithCallback(
                        callback(poolBeans, MemoryPoolMXBean::getUsage, MemoryUsage::getCommitted));

        meter
                .upDownCounterBuilder("process.runtime.jvm.memory.limit")
                .setDescription("Measure of max obtainable memory")
                .setUnit("By")
                .buildWithCallback(callback(poolBeans, MemoryPoolMXBean::getUsage, MemoryUsage::getMax));

        meter
                .upDownCounterBuilder("process.runtime.jvm.memory.usage_after_last_gc")
                .setDescription(
                        "Measure of memory used after the most recent garbage collection event on this pool")
                .setUnit("By")
                .buildWithCallback(
                        callback(poolBeans, MemoryPoolMXBean::getCollectionUsage, MemoryUsage::getUsed));
    }

    private Consumer<ObservableLongMeasurement> callback(
            List<MemoryPoolMXBean> poolBeans,
            Function<MemoryPoolMXBean, MemoryUsage> memoryUsageExtractor,
            Function<MemoryUsage, Long> valueExtractor) {
        List<Attributes> attributeSets = new ArrayList<>(poolBeans.size());
        for (MemoryPoolMXBean pool : poolBeans) {
            attributeSets.add(
                    Attributes.builder()
                            .put(POOL_KEY, pool.getName())
                            .put(TYPE_KEY, memoryType(pool.getType()))
                            .build());
        }

        return measurement -> {
            for (int i = 0; i < poolBeans.size(); i++) {
                Attributes attributes = attributeSets.get(i);
                MemoryUsage memoryUsage = memoryUsageExtractor.apply(poolBeans.get(i));
                if (memoryUsage == null) {
                    // JVM may return null in special cases for MemoryPoolMXBean.getUsage() and
                    // MemoryPoolMXBean.getCollectionUsage()
                    continue;
                }
                long value = valueExtractor.apply(memoryUsage);
                if (value != -1) {
                    measurement.record(value, attributes);
                }
            }
        };
    }

    private String memoryType(MemoryType memoryType) {
        switch (memoryType) {
            case HEAP:
                return HEAP;
            case NON_HEAP:
                return NON_HEAP;
        }
        return "unknown";
    }
}
