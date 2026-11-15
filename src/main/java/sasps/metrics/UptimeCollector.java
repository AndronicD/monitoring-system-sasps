package sasps.metrics;

import sasps.contracts.Metric;
import sasps.contracts.MetricCollector;
import sasps.contracts.Reading;

import java.lang.management.RuntimeMXBean;
import java.util.List;
import java.util.Map;

public final class UptimeCollector implements MetricCollector {
    private final RuntimeMXBean runtime;

    public UptimeCollector(RuntimeMXBean runtime) {
        this.runtime = runtime;
    }

    @Override
    public List<Metric> metrics() {
        return List.of(
            new Metric("jvm.uptime.ms", "ms", "process", "RuntimeMXBean.getUptime",
                "Elapsed time since JVM start, not host uptime or time since monitoring began")
        );
    }

    @Override
    public Map<String, Reading> collect(long nowNanos) {
        return Map.of("jvm.uptime.ms", Reading.available(runtime.getUptime()));
    }
}
