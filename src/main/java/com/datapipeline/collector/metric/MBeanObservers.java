package com.datapipeline.collector.metric;

import io.opentelemetry.sdk.metrics.SdkMeterProvider;

public interface MBeanObservers {
    public static final String INSTRUMENTATION_NAME = "io.opentelemetry.runtime-metrics";
    void registerObservers(SdkMeterProvider sdk);
}
