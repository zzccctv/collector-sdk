package com.datapipeline.collector.metric;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;

public class Threads implements MBeanObservers {

    final AttributeKey<Boolean> DAEMON = AttributeKey.booleanKey("daemon");

    @Override
    public void registerObservers(SdkMeterProvider sdk) {
        registerObservers(sdk, ManagementFactory.getThreadMXBean());
    }

    private void registerObservers(SdkMeterProvider sdk, ThreadMXBean threadBean) {
        Meter meter = sdk.meterBuilder(INSTRUMENTATION_NAME).build();
        meter.upDownCounterBuilder("process.runtime.jvm.threads.count")
                .setDescription("Number of executing threads")
                .setUnit("1")
                .buildWithCallback(
                        observableMeasurement -> {
                            observableMeasurement.record(
                                    threadBean.getDaemonThreadCount(),
                                    Attributes.builder().put(DAEMON, true).build());
                            observableMeasurement.record(
                                    threadBean.getThreadCount() - threadBean.getDaemonThreadCount(),
                                    Attributes.builder().put(DAEMON, false).build());
                        });
    }
}
