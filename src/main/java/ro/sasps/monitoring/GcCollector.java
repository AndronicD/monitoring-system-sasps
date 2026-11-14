package ro.sasps.monitoring;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Map;

public final class GcCollector implements MetricCollector {
    @Override
    public List<Metric> metrics() {
        return List.of(
            new Metric("jvm.gc.collections.total", "collections", "process",
                "GarbageCollectorMXBean.getCollectionCount",
                "Sum across JVM GC beans since process start; not a pause distribution"),
            new Metric("jvm.gc.collection.time.ms.total", "ms", "process",
                "GarbageCollectorMXBean.getCollectionTime",
                "Approximate accumulated collection time; NOT individual stop-the-world pauses or p99")
        );
    }

    @Override
    public Map<String, Reading> collect(long nowNanos) {
        List<GarbageCollectorMXBean> beans = ManagementFactory.getGarbageCollectorMXBeans();
        long count = 0;
        long time = 0;
        boolean countsSupported = !beans.isEmpty();
        boolean timesSupported = !beans.isEmpty();
        for (GarbageCollectorMXBean bean : beans) {
            long beanCount = bean.getCollectionCount();
            long beanTime = bean.getCollectionTime();
            countsSupported &= beanCount >= 0;
            timesSupported &= beanTime >= 0;
            count += Math.max(0, beanCount);
            time += Math.max(0, beanTime);
        }
        return Map.of(
            "jvm.gc.collections.total", countsSupported ? Reading.available(count)
                : Reading.unavailable("At least one GC bean does not support collection counts"),
            "jvm.gc.collection.time.ms.total", timesSupported ? Reading.available(time)
                : Reading.unavailable("At least one GC bean does not support collection time")
        );
    }
}
