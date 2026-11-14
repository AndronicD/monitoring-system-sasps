package ro.sasps.monitoring;

import java.util.List;
import java.util.Map;

public interface MetricCollector {
    List<Metric> metrics();

    Map<String, Reading> collect(long nowNanos);
}
