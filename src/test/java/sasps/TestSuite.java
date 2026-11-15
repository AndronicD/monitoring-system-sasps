package sasps;

import sasps.contracts.ContractTests;
import sasps.contracts.Metric;
import sasps.contracts.MetricCollector;
import sasps.contracts.Reading;
import sasps.contracts.Sample;
import sasps.metrics.CollectorTests;
import sasps.monitoring.demo.MonitoringTests;
import sasps.server.ServerTests;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class TestSuite {
    private static int checks;

    private TestSuite() {
    }

    public static void main(String[] args) throws Exception {
        ContractTests.run();
        CollectorTests.run();
        MonitoringTests.run();
        ServerTests.run();
        System.out.println("PASS: " + checks + " checks");
    }

    public static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    public static void expect(Class<? extends Exception> type, CheckedRunnable action) {
        checks++;
        try {
            action.run();
        } catch (Exception e) {
            if (type.isInstance(e)) {
                return;
            }
            throw new AssertionError("Expected " + type.getName() + ", got " + e, e);
        }
        throw new AssertionError("Expected " + type.getName());
    }

    public static Sample sample(long sequence) {
        return new Sample(sequence, Instant.parse("2026-01-01T00:00:00Z"), 1000, 0.1,
            Map.of("test.value", Reading.available(sequence)));
    }

    public static MetricCollector constantCollector() {
        return new MetricCollector() {
            @Override
            public List<Metric> metrics() {
                return List.of(new Metric("test.value", "count", "test", "fixture", "Test only"));
            }

            @Override
            public Map<String, Reading> collect(long nowNanos) {
                return Map.of("test.value", Reading.available(1));
            }
        };
    }

    public interface CheckedRunnable {
        void run() throws Exception;
    }
}
