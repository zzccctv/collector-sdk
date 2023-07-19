package com.datapipeline.collector.metric;

import com.sun.management.GarbageCollectionNotificationInfo;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.function.Function;
import javax.management.Notification;
import javax.management.NotificationEmitter;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.openmbean.CompositeData;

public class GarbageCollector implements MBeanObservers {


    private static final AttributeKey<String> GC_KEY = AttributeKey.stringKey("gc");
    private static final AttributeKey<String> ACTION_KEY = AttributeKey.stringKey("action");


    private static final NotificationFilter GC_FILTER =
            notification ->
                    notification
                            .getType()
                            .equals(GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION);

    @Override
    public void registerObservers(SdkMeterProvider sdk) {
        if (!isNotificationClassPresent()) {
            return;
        }

        registerObservers(
                sdk,
                ManagementFactory.getGarbageCollectorMXBeans(),
                GarbageCollector::extractNotificationInfo);
    }

    private void registerObservers(
            SdkMeterProvider sdk,
            List<GarbageCollectorMXBean> gcBeans,
            Function<Notification, GarbageCollectionNotificationInfo> notificationInfoExtractor) {
        Meter meter = sdk.meterBuilder(INSTRUMENTATION_NAME).build();

        LongHistogram gcTime =
                meter
                        .histogramBuilder("process.runtime.jvm.gc.duration")
                        .setDescription("Duration of JVM garbage collection actions")
                        .setUnit("ms")
                        .ofLongs()
                        .build();

        for (GarbageCollectorMXBean gcBean : gcBeans) {
            if (!(gcBean instanceof NotificationEmitter)) {
                continue;
            }
            NotificationEmitter notificationEmitter = (NotificationEmitter) gcBean;
            notificationEmitter.addNotificationListener(
                    new GcNotificationListener(gcTime, notificationInfoExtractor), GC_FILTER, null);
        }
    }

    private static final class GcNotificationListener implements NotificationListener {

        private final LongHistogram gcTime;
        private final Function<Notification, GarbageCollectionNotificationInfo>
                notificationInfoExtractor;

        private GcNotificationListener(
                LongHistogram gcTime,
                Function<Notification, GarbageCollectionNotificationInfo> notificationInfoExtractor) {
            this.gcTime = gcTime;
            this.notificationInfoExtractor = notificationInfoExtractor;
        }

        @Override
        public void handleNotification(Notification notification, Object unused) {
            GarbageCollectionNotificationInfo notificationInfo =
                    notificationInfoExtractor.apply(notification);
            gcTime.record(
                    notificationInfo.getGcInfo().getDuration(),
                    Attributes.of(
                            GC_KEY, notificationInfo.getGcName(), ACTION_KEY, notificationInfo.getGcAction()));
        }
    }

    /**
     * Extract {@link GarbageCollectionNotificationInfo} from the {@link Notification}.
     *
     * <p>Note: this exists as a separate function so that the behavior can be overridden with mocks
     * in tests. It's very challenging to create a mock {@link CompositeData} that can be parsed by
     * {@link GarbageCollectionNotificationInfo#from(CompositeData)}.
     */
    private static GarbageCollectionNotificationInfo extractNotificationInfo(
            Notification notification) {
        return GarbageCollectionNotificationInfo.from((CompositeData) notification.getUserData());
    }

    private static boolean isNotificationClassPresent() {
        try {
            Class.forName(
                    "com.sun.management.GarbageCollectionNotificationInfo",
                    false,
                    GarbageCollectorMXBean.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
