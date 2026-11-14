package sasps.monitoring.collectors;

import sasps.monitoring.model.*;

import java.util.List;
import java.util.Map;

public interface MetricCollector {
    List<Metric> metrics();

    Map<String, Reading> collect(long nowNanos);
}
