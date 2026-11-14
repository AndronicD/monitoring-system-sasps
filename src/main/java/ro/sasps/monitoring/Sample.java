package ro.sasps.monitoring;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record Sample(long sequence, Instant timestamp, double intervalMs,
                     double measurementDurationMs, Map<String, Reading> readings) {
    public Sample {
        readings = Collections.unmodifiableMap(new LinkedHashMap<>(readings));
    }
}
