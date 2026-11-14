package sasps.monitoring;

import sasps.monitoring.model.*;
import sasps.monitoring.collectors.*;
import sasps.monitoring.store.*;


import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class TestSuite {
    private static int checks;

    private TestSuite() {
    }

    public static void main(String[] args) throws Exception {
        readingsAndJson();
        networkParsers();
        observerAndHistory();
        serviceAndHttp();
        scheduledRecovery();
        System.out.println("PASS: " + checks + " checks");
    }

    private static void readingsAndJson() {
        check(Reading.available(42).value() == 42, "Available value");
        check(Reading.nonNegative(-1, "unsupported").value() == null, "Unsupported is null, not zero");
        expect(IllegalArgumentException.class, () -> Reading.available(Double.NaN));
        expect(IllegalArgumentException.class, () -> Reading.available(Double.POSITIVE_INFINITY));
        expect(IllegalArgumentException.class, () -> Reading.unavailable(""));
        check(Json.quote("\"\\\n\t\u0001").equals("\"\\\"\\\\\\n\\t\\u0001\""), "JSON escaping");
        check(Json.quote(null).equals("null"), "JSON null");
        String encoded = Json.sample(sample(1));
        check(encoded.contains("\"value\":1.0"), "Sample numeric value");
        check(encoded.contains("\"timestamp\":\"2026-01-01T00:00:00Z\""), "Sample timestamp");
        Sample unavailable = new Sample(1, Instant.EPOCH, 0, 0,
            Map.of("x", Reading.unavailable("not supported")));
        check(Json.sample(unavailable).contains("\"value\":null"), "JSON unavailable reading");
        expect(UnsupportedOperationException.class, () -> sample(1).readings().clear());
    }

    private static void networkParsers() throws Exception {
        String linux = "Inter-| Receive | Transmit\n"
            + " face |bytes packets errs drop fifo frame compressed multicast|bytes\n"
            + "  lo: 1200 1 0 0 0 0 0 0 1400 1 0 0 0 0 0 0\n"
            + "eth0: 9900 2 0 0 0 0 0 0 8800 2 0 0 0 0 0 0\n";
        check(NetworkCollector.parseLinux(linux, "lo").received() == 1200, "Linux receive bytes");
        check(NetworkCollector.parseLinux(linux, "eth0").sent() == 8800, "Linux transmit bytes");
        expect(IOException.class, () -> NetworkCollector.parseLinux(linux, "missing"));
        expect(IOException.class, () -> NetworkCollector.parseLinux("lo: 1 2", "lo"));
        expect(IOException.class, () -> NetworkCollector.parseLinux(
            "lo: -1 0 0 0 0 0 0 0 12 0 0 0 0 0 0 0", "lo"));
        String header = "Name Mtu Network Address Ipkts Ierrs Ibytes Opkts Oerrs Obytes Coll\n";
        String mac = header + "en0 1500 <Link#14> aa:bb:cc:dd:ee:ff 12 0 6000 14 0 9000 0\n"
            + "en0 1500 192.168.1 192.168.1.2 12 - 6000 14 - 9000 -\n";
        check(NetworkCollector.parseMac(mac, "en0").received() == 6000, "macOS receive bytes");
        check(NetworkCollector.parseMac(mac, "en0").sent() == 9000, "macOS no duplicate IP rows");
        String loopback = header + "lo0 16384 <Link#1> 12 0 7000 12 0 7000 0\n";
        check(NetworkCollector.parseMac(loopback, "lo0").sent() == 7000, "macOS missing link Address");
        expect(IOException.class, () -> NetworkCollector.parseMac("Name Mtu\nlo0 1", "lo0"));
        expect(IOException.class, () -> NetworkCollector.parseMac(header + "lo0 1 <Link#1> -", "lo0"));
        expect(IOException.class, () -> NetworkCollector.parseMac(
            header + "lo0 16384 <Link#1> 12 0 bad 12 0 7000 0", "lo0"));
        expect(IllegalArgumentException.class, () -> NetworkCollector.selectInterface("nonexistent-m1"));
    }

    private static void observerAndHistory() {
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
    }

    private static void serviceAndHttp() throws Exception {
        SampleHistory history = new SampleHistory(2);
        SampleBus bus = new SampleBus();
        bus.subscribe(history);
        MetricCollector collector = constantCollector();
        try (MetricService service = new MetricService(List.of(collector), bus, 1000);
             MonitorServer server = new MonitorServer(0, history, service,
                 Json.metadata(List.of(collector), 1000, 2, "test"))) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            check(get(client, base + "/health").statusCode() == 503, "Not ready before sample");
            check(get(client, base + "/metrics").statusCode() == 503, "No invented initial sample");
            service.sampleOnce();
            service.sampleOnce();
            check(history.latest().intervalMs() > 0, "Actual sampling interval");
            check(get(client, base + "/health").statusCode() == 200, "Ready after sample");
            check(get(client, base + "/metrics").body().contains("\"sequence\":2"), "Metrics endpoint");
            check(get(client, base + "/metadata").body().contains("\"scope\":\"test\""), "Metadata endpoint");
            check(get(client, base + "/history").body().startsWith("["), "History endpoint");
            check(get(client, base + "/work?iterations=10").body().contains("\"allocatedBytes\":262144"),
                "Real bounded allocation workload");
            check(get(client, base + "/work?iterations=0").statusCode() == 400, "Reject zero workload");
            check(get(client, base + "/work?iterations=5000001").statusCode() == 400, "Bound workload");
            check(get(client, base + "/work?iterations=bad").statusCode() == 400, "Reject invalid workload");
            check(get(client, base + "/work?x=1").statusCode() == 400, "Reject unknown query");
            check(get(client, base + "/metrics-extra").statusCode() == 404, "Exact endpoint matching");
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + "/work"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            check(response.statusCode() == 405, "Reject unsupported HTTP methods");
            check(response.headers().firstValue("Allow").orElse("").equals("GET"), "Allow header");
        }
        try (MetricService duplicate = new MetricService(List.of(collector, collector), new SampleBus(), 100)) {
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

    private static MetricCollector constantCollector() {
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

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Sample sample(long sequence) {
        return new Sample(sequence, Instant.parse("2026-01-01T00:00:00Z"), 1000, 0.1,
            Map.of("test.value", Reading.available(sequence)));
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void expect(Class<? extends Exception> type, CheckedRunnable action) {
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

    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
