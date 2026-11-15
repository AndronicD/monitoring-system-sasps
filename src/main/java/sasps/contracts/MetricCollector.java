package sasps.contracts;

import java.util.List;
import java.util.Map;

public interface MetricCollector {
    List<Metric> metrics();

    Map<String, Reading> collect(long nowNanos);
}
