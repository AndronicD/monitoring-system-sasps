package sasps.monitoring.collectors;

import sasps.monitoring.model.*;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MemoryCollector implements MetricCollector {
    private final OperatingSystemMXBean os;

    public MemoryCollector(OperatingSystemMXBean os) {
        this.os = os;
    }

    @Override
    public List<Metric> metrics() {
        return List.of(
            new Metric("host.ram.total.bytes", "bytes", "host",
                "OperatingSystemMXBean.getTotalMemorySize", "OS/JDK-visible physical memory"),
            new Metric("host.ram.used.bytes", "bytes", "host",
                "OperatingSystemMXBean.getTotalMemorySize/getFreeMemorySize",
                "Total minus free physical memory; includes OS caches, not application-only RAM"),
            new Metric("jvm.heap.used.bytes", "bytes", "process", "MemoryMXBean.getHeapMemoryUsage",
                "Live and not-yet-collected heap allocation; not process RSS"),
            new Metric("jvm.heap.committed.bytes", "bytes", "process", "MemoryMXBean.getHeapMemoryUsage",
                "Heap committed to this JVM"),
            new Metric("jvm.heap.max.bytes", "bytes", "process", "MemoryMXBean.getHeapMemoryUsage",
                "Configured heap limit, or unavailable when undefined"),
            new Metric("jvm.nonheap.used.bytes", "bytes", "process", "MemoryMXBean.getNonHeapMemoryUsage",
                "JVM non-heap used memory; excludes native stacks and other native allocations")
        );
    }

    @Override
    public Map<String, Reading> collect(long nowNanos) {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        long total = os.getTotalMemorySize();
        long free = os.getFreeMemorySize();
        Map<String, Reading> readings = new LinkedHashMap<>();
        readings.put("host.ram.total.bytes", Reading.nonNegative(total, "Host physical RAM unsupported"));
        readings.put("host.ram.used.bytes", total > 0 && free >= 0 && free <= total
            ? Reading.available(total - free) : Reading.unavailable("Host physical RAM unsupported"));
        readings.put("jvm.heap.used.bytes", Reading.available(heap.getUsed()));
        readings.put("jvm.heap.committed.bytes", Reading.available(heap.getCommitted()));
        readings.put("jvm.heap.max.bytes", Reading.nonNegative(heap.getMax(), "Heap maximum undefined"));
        readings.put("jvm.nonheap.used.bytes",
            Reading.available(ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed()));
        return readings;
    }
}
