package sasps.monitoring.collectors;

import sasps.monitoring.model.*;

import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Map;

public final class ThreadCollector implements MetricCollector {
    private final ThreadMXBean threads;

    public ThreadCollector(ThreadMXBean threads) {
        this.threads = threads;
    }

    @Override
    public List<Metric> metrics() {
        return List.of(
            new Metric("jvm.threads.live", "threads", "process", "ThreadMXBean.getThreadCount",
                "Current live JVM platform threads, including daemon and non-daemon threads"),
            new Metric("jvm.threads.daemon", "threads", "process", "ThreadMXBean.getDaemonThreadCount",
                "Current live daemon platform threads"),
            new Metric("jvm.threads.peak", "threads", "process", "ThreadMXBean.getPeakThreadCount",
                "Peak live platform threads since JVM start or the last peak-count reset"),
            new Metric("jvm.threads.started.total", "threads", "process",
                "ThreadMXBean.getTotalStartedThreadCount",
                "Total platform threads started since JVM start, including terminated threads")
        );
    }

    @Override
    public Map<String, Reading> collect(long nowNanos) {
        return Map.of(
            "jvm.threads.live", Reading.available(threads.getThreadCount()),
            "jvm.threads.daemon", Reading.available(threads.getDaemonThreadCount()),
            "jvm.threads.peak", Reading.available(threads.getPeakThreadCount()),
            "jvm.threads.started.total", Reading.available(threads.getTotalStartedThreadCount())
        );
    }
}
