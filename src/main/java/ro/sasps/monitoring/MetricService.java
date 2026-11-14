package ro.sasps.monitoring;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class MetricService implements AutoCloseable {
    private final List<MetricCollector> collectors;
    private final SampleBus bus;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final int intervalMs;
    private long sequence;
    private long previousNanos;
    private volatile String lastError;

    public MetricService(List<MetricCollector> collectors, SampleBus bus, int intervalMs) {
        this.collectors = List.copyOf(collectors);
        this.bus = bus;
        this.intervalMs = intervalMs;
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::sampleSafely, 0, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void sampleSafely() {
        try {
            sampleOnce();
            lastError = null;
        } catch (RuntimeException e) {
            // A scheduled task would otherwise stop permanently after one failed collection.
            lastError = e.toString();
            System.err.println("SAMPLE_FAILED: " + e);
            e.printStackTrace(System.err);
        }
    }

    public void sampleOnce() {
        long start = System.nanoTime();
        Instant timestamp = Instant.now();
        Map<String, Reading> readings = new LinkedHashMap<>();
        for (MetricCollector collector : collectors) {
            for (Map.Entry<String, Reading> entry : collector.collect(start).entrySet()) {
                if (readings.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                    throw new IllegalStateException("Duplicate metric: " + entry.getKey());
                }
            }
        }
        double actualIntervalMs = previousNanos == 0 ? 0 : (start - previousNanos) / 1_000_000.0;
        previousNanos = start;
        Sample sample = new Sample(++sequence, timestamp, actualIntervalMs,
            (System.nanoTime() - start) / 1_000_000.0, readings);
        bus.publish(sample);
    }

    public String lastError() {
        return lastError;
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                System.err.println("SAMPLER_SHUTDOWN_TIMEOUT");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.err.println("SAMPLER_SHUTDOWN_INTERRUPTED");
        }
    }
}
