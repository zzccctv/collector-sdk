package com.datapipeline.collector.metric;

import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.ManagementFactory;

public class Classes implements MBeanObservers {
    @Override
    public void registerObservers(SdkMeterProvider sdk) {
        registerObservers(sdk, ManagementFactory.getClassLoadingMXBean());
    }

    private void registerObservers(SdkMeterProvider sdk, ClassLoadingMXBean classBean) {
        Meter meter = sdk.meterBuilder(INSTRUMENTATION_NAME).build();
        meter
                .counterBuilder("process.runtime.jvm.classes.loaded")
                .setDescription("Number of classes loaded since JVM start")
                .setUnit("1")
                .buildWithCallback(
                        observableMeasurement ->
                                observableMeasurement.record(classBean.getTotalLoadedClassCount()));

        meter
                .counterBuilder("process.runtime.jvm.classes.unloaded")
                .setDescription("Number of classes unloaded since JVM start")
                .setUnit("1")
                .buildWithCallback(
                        observableMeasurement ->
                                observableMeasurement.record(classBean.getUnloadedClassCount()));

        meter
                .upDownCounterBuilder("process.runtime.jvm.classes.current_loaded")
                .setDescription("Number of classes currently loaded")
                .setUnit("1")
                .buildWithCallback(
                        observableMeasurement -> observableMeasurement.record(classBean.getLoadedClassCount()));
    }
}
