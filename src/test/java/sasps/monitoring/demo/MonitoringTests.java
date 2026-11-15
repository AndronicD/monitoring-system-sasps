package sasps.monitoring.demo;

import sasps.contracts.Metric;
import sasps.contracts.MetricCollector;
import sasps.contracts.Reading;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static sasps.TestSuite.check;
import static sasps.TestSuite.constantCollector;
import static sasps.TestSuite.expect;
import static sasps.TestSuite.sample;

public final class MonitoringTests {
    private MonitoringTests() {
    }

    public static void run() throws Exception {
        busAndHistory();
        sampling();
        scheduledRecovery();
    }

    private static void busAndHistory() {
        SampleBus bus = new SampleBus();
        SampleHistory history = new SampleHistory(2);
        AtomicInteger delivered = new AtomicInteger();
        bus.subscribe(history);
        bus.subscribe(s -> delivered.incrementAndGet());
        bus.publish(sample(1));
        bus.publish(sample(2));
        bus.publish(sample(3));
        check(delivered.get() == 3, "Push delivers each published sample");
        check(history.snapshot().size() == 2, "History bounded at capacity");
        check(history.snapshot().get(0).sequence() == 2, "History evicts oldest");
        check(history.latest().sequence() == 3, "Latest sample");
        expect(UnsupportedOperationException.class, () -> history.snapshot().clear());
        expect(IllegalArgumentException.class, () -> new SampleHistory(0));
        check(new SampleHistory(60).capacity() == 60, "History keeps its configured bound");
    }

    private static void sampling() {
        SampleBus bus = new SampleBus();
        SampleHistory history = new SampleHistory(2);
        bus.subscribe(history);
        MetricCollector large = fixedCollector("large.value", 3_000_000_000L);
        try (MetricService service = new MetricService(List.of(large), bus, 1000)) {
            service.sampleOnce();
            check(history.latest().sequence() == 1, "First sample is sequence 1");
            check(history.latest().intervalMs() == 0, "First sample has no previous interval");
            check(history.latest().readings().get("large.value").value() == 3_000_000_000.0,
                "Sampling preserves values beyond integer range");
            service.sampleOnce();
            check(history.latest().sequence() == 2, "Sequence advances per sample");
            check(history.latest().intervalMs() > 0, "Actual sampling interval");
            check(service.lastError() == null, "Healthy sampling reports no error");
        }
        MetricCollector collector = constantCollector();
        try (MetricService duplicate = new MetricService(List.of(collector, collector),
                new SampleBus(), 100)) {
            expect(IllegalStateException.class, duplicate::sampleOnce);
        }
    }

    private static void scheduledRecovery() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch delivered = new CountDownLatch(1);
        MetricCollector failsOnce = new MetricCollector() {
            @Override
            public List<Metric> metrics() {
                return constantCollector().metrics();
            }

            @Override
            public Map<String, Reading> collect(long nowNanos) {
                if (calls.incrementAndGet() == 1) {
                    throw new IllegalStateException("intentional test failure");
                }
                return Map.of("test.value", Reading.available(1));
            }
        };
        SampleBus bus = new SampleBus();
        bus.subscribe(s -> delivered.countDown());
        try (MetricService service = new MetricService(List.of(failsOnce), bus, 100)) {
            service.start();
            check(delivered.await(3, TimeUnit.SECONDS), "Scheduler recovers after logged collection failure");
            check(calls.get() >= 2, "Scheduler did not silently stop");
        }
    }

    private static MetricCollector fixedCollector(String id, double value) {
        return new MetricCollector() {
            @Override
            public List<Metric> metrics() {
                return List.of(new Metric(id, "count", "test", "fixture", "Test only"));
            }

            @Override
            public Map<String, Reading> collect(long nowNanos) {
                return Map.of(id, Reading.available(value));
            }
        };
    }
}
