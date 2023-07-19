package com.datapipeline.collector.metric;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

public class BufferPools implements MBeanObservers {

    final AttributeKey<String> POOL_KEY = AttributeKey.stringKey("pool");

    @Override
    public void registerObservers(SdkMeterProvider sdk) {
        List<BufferPoolMXBean> bufferBeans =
                ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);
        registerObservers(sdk, bufferBeans);
    }

    private void registerObservers(SdkMeterProvider sdk, List<BufferPoolMXBean> bufferBeans) {
        Meter meter = sdk.meterBuilder(INSTRUMENTATION_NAME).build();
        meter
                .upDownCounterBuilder("process.runtime.jvm.buffer.usage")
                .setDescription("Memory that the Java virtual machine is using for this buffer pool")
                .setUnit("By")
                .buildWithCallback(callback(bufferBeans, BufferPoolMXBean::getMemoryUsed));

        meter
                .upDownCounterBuilder("process.runtime.jvm.buffer.limit")
                .setDescription("Total capacity of the buffers in this pool")
                .setUnit("By")
                .buildWithCallback(callback(bufferBeans, BufferPoolMXBean::getTotalCapacity));

        meter
                .upDownCounterBuilder("process.runtime.jvm.buffer.count")
                .setDescription("The number of buffers in the pool")
                .setUnit("{buffers}")
                .buildWithCallback(callback(bufferBeans, BufferPoolMXBean::getCount));
    }

    private Consumer<ObservableLongMeasurement> callback(
            List<BufferPoolMXBean> bufferPools, Function<BufferPoolMXBean, Long> extractor) {
        List<Attributes> attributeSets = new ArrayList<>(bufferPools.size());
        for (BufferPoolMXBean pool : bufferPools) {
            attributeSets.add(Attributes.builder().put(POOL_KEY, pool.getName()).build());
        }
        return measurement -> {
            for (int i = 0; i < bufferPools.size(); i++) {
                Attributes attributes = attributeSets.get(i);
                long value = extractor.apply(bufferPools.get(i));
                if (value != -1) {
                    measurement.record(value, attributes);
                }
            }
        };
    }
}
