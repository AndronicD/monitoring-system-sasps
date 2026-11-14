package sasps.monitoring.collectors;

import sasps.monitoring.model.*;

import com.sun.management.OperatingSystemMXBean;
import java.util.List;
import java.util.Map;

public final class CpuCollector implements MetricCollector {
    private final OperatingSystemMXBean os;
    private long previousNanos;
    private long previousCpuNanos = -1;

    public CpuCollector(OperatingSystemMXBean os) {
        this.os = os;
    }

    @Override
    public List<Metric> metrics() {
        return List.of(
            new Metric("host.cpu.percent", "%", "host", "OperatingSystemMXBean.getCpuLoad",
                "Recent OS/JDK-visible CPU load across logical processors; JDK-defined window"),
            new Metric("process.cpu.percent", "%", "process", "OperatingSystemMXBean.getProcessCpuTime",
                "CPU-time delta / monotonic wall-time delta / availableProcessors * 100")
        );
    }

    @Override
    public Map<String, Reading> collect(long nowNanos) {
        long cpu = os.getProcessCpuTime();
        Reading process = Reading.unavailable("First sample or process CPU time unsupported");
        if (previousNanos != 0 && nowNanos > previousNanos && cpu >= previousCpuNanos
                && previousCpuNanos >= 0) {
            double percent = 100.0 * (cpu - previousCpuNanos)
                / (nowNanos - previousNanos) / os.getAvailableProcessors();
            process = Reading.available(percent);
        }
        previousNanos = nowNanos;
        previousCpuNanos = cpu;
        return Map.of(
            "host.cpu.percent", Reading.nonNegative(os.getCpuLoad() * 100,
                "Host CPU load unsupported or not yet initialized by this JDK"),
            "process.cpu.percent", process
        );
    }
}
