package com.datapipeline.collector.metric;

import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Supplier;

public class CPU implements MBeanObservers {

    private static final String OS_BEAN_J9 = "com.ibm.lang.management.OperatingSystemMXBean";
    private static final String OS_BEAN_HOTSPOT = "com.sun.management.OperatingSystemMXBean";
    private static final String METHOD_PROCESS_CPU_LOAD = "getProcessCpuLoad";
    private static final String METHOD_CPU_LOAD = "getCpuLoad";
    private static final String METHOD_SYSTEM_CPU_LOAD = "getSystemCpuLoad";

    private static final Supplier<Double> processCpu;
    private static final Supplier<Double> systemCpu;

    static {
        OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
        Supplier<Double> processCpuSupplier =
                methodInvoker(osBean, OS_BEAN_HOTSPOT, METHOD_PROCESS_CPU_LOAD);
        if (processCpuSupplier == null) {
            // More users will be on hotspot than j9, so check for j9 second
            processCpuSupplier = methodInvoker(osBean, OS_BEAN_J9, METHOD_PROCESS_CPU_LOAD);
        }
        processCpu = processCpuSupplier;

        // As of java 14, com.sun.management.OperatingSystemMXBean#getCpuLoad() is preferred and
        // #getSystemCpuLoad() is deprecated
        Supplier<Double> systemCpuSupplier = methodInvoker(osBean, OS_BEAN_HOTSPOT, METHOD_CPU_LOAD);
        if (systemCpuSupplier == null) {
            systemCpuSupplier = methodInvoker(osBean, OS_BEAN_HOTSPOT, METHOD_SYSTEM_CPU_LOAD);
        }
        if (systemCpuSupplier == null) {
            // More users will be on hotspot than j9, so check for j9 second
            systemCpuSupplier = methodInvoker(osBean, OS_BEAN_J9, METHOD_SYSTEM_CPU_LOAD);
        }
        systemCpu = systemCpuSupplier;
    }

    @Override
    public void registerObservers(SdkMeterProvider sdk) {
        registerObservers(sdk, ManagementFactory.getOperatingSystemMXBean(), systemCpu, processCpu);
    }

    private void registerObservers(SdkMeterProvider sdk, OperatingSystemMXBean osBean,
                                   Supplier<Double> systemCpuUsage,
                                   Supplier<Double> processCpuUsage) {
        Meter meter = sdk.meterBuilder(INSTRUMENTATION_NAME).build();
        meter
                .gaugeBuilder("process.runtime.jvm.system.cpu.load_1m")
                .setDescription("Average CPU load of the whole system for the last minute")
                .setUnit("1")
                .buildWithCallback(
                        observableMeasurement -> {
                            double loadAverage = osBean.getSystemLoadAverage();
                            if (loadAverage >= 0) {
                                observableMeasurement.record(loadAverage);
                            }
                        });

        if (systemCpuUsage != null) {
            meter
                    .gaugeBuilder("process.runtime.jvm.system.cpu.utilization")
                    .setDescription("Recent cpu utilization for the whole system")
                    .setUnit("1")
                    .buildWithCallback(
                            observableMeasurement -> {
                                Double cpuUsage = systemCpuUsage.get();
                                if (cpuUsage != null && cpuUsage >= 0) {
                                    observableMeasurement.record(cpuUsage);
                                }
                            });
        }

        if (processCpuUsage != null) {
            meter
                    .gaugeBuilder("process.runtime.jvm.cpu.utilization")
                    .setDescription("Recent cpu utilization for the process")
                    .setUnit("1")
                    .buildWithCallback(
                            observableMeasurement -> {
                                Double cpuUsage = processCpuUsage.get();
                                if (cpuUsage != null && cpuUsage >= 0) {
                                    observableMeasurement.record(cpuUsage);
                                }
                            });
        }

    }

    @SuppressWarnings("ReturnValueIgnored")
    private static Supplier<Double> methodInvoker(
            OperatingSystemMXBean osBean, String osBeanClassName, String methodName) {
        try {
            Class<?> osBeanClass = Class.forName(osBeanClassName);
            osBeanClass.cast(osBean);
            Method method = osBeanClass.getDeclaredMethod(methodName);
            return () -> {
                try {
                    return (double) method.invoke(osBean);
                } catch (IllegalAccessException | InvocationTargetException e) {
                    return null;
                }
            };
        } catch (ClassNotFoundException | ClassCastException | NoSuchMethodException e) {
            return null;
        }
    }

}
