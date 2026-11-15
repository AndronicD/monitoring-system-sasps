package sasps.metrics;

import sasps.contracts.Metric;
import sasps.contracts.MetricCollector;
import sasps.contracts.Reading;

import java.io.IOException;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static sasps.TestSuite.check;
import static sasps.TestSuite.expect;

public final class CollectorTests {
    private CollectorTests() {
    }

    public static void run() throws Exception {
        uptimeMetrics();
        threadMetrics();
        networkParsers();
    }

    private static void uptimeMetrics() {
        AtomicLong uptime = new AtomicLong();
        RuntimeMXBean runtime = RuntimeMXBean.class.cast(Proxy.newProxyInstance(
            RuntimeMXBean.class.getClassLoader(), new Class<?>[] {RuntimeMXBean.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getUptime")) {
                    return uptime.get();
                }
                throw new AssertionError("Unexpected RuntimeMXBean call: " + method.getName());
            }));
        MetricCollector collector = new UptimeCollector(runtime);
        check(collector.metrics().equals(List.of(new Metric("jvm.uptime.ms", "ms", "process",
            "RuntimeMXBean.getUptime",
            "Elapsed time since JVM start, not host uptime or time since monitoring began"))),
            "Uptime metadata");
        check(collector.collect(0).equals(Map.of("jvm.uptime.ms", Reading.available(0))),
            "Zero uptime is available");
        uptime.set(12_345);
        check(collector.collect(0).equals(Map.of("jvm.uptime.ms", Reading.available(12_345))),
            "Uptime comes from the JVM in milliseconds");
        uptime.set(3_000_000_000L);
        check(collector.collect(0).get("jvm.uptime.ms").value() == 3_000_000_000.0,
            "Uptime preserves values beyond integer range");
    }

    private static void threadMetrics() {
        ThreadMXBean threads = ThreadMXBean.class.cast(Proxy.newProxyInstance(
            ThreadMXBean.class.getClassLoader(), new Class<?>[] {ThreadMXBean.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getThreadCount" -> 12;
                case "getDaemonThreadCount" -> 7;
                case "getPeakThreadCount" -> 20;
                case "getTotalStartedThreadCount" -> 123L;
                default -> throw new AssertionError("Unexpected ThreadMXBean call: " + method.getName());
            }));
        MetricCollector collector = new ThreadCollector(threads);
        Map<String, Reading> readings = collector.collect(0);
        check(readings.equals(Map.of(
            "jvm.threads.live", Reading.available(12),
            "jvm.threads.daemon", Reading.available(7),
            "jvm.threads.peak", Reading.available(20),
            "jvm.threads.started.total", Reading.available(123))), "Thread counter mapping");
        List<Metric> metrics = collector.metrics();
        check(metrics.size() == 4, "Four thread metric definitions");
        check(readings.keySet().equals(Set.copyOf(metrics.stream().map(Metric::id).toList())),
            "Thread metadata matches readings");
        for (Metric metric : metrics) {
            check(metric.unit().equals("threads"), "Thread metric unit: " + metric.id());
            check(metric.scope().equals("process"), "Thread metric scope: " + metric.id());
            check(metric.source().startsWith("ThreadMXBean."), "Thread metric source: " + metric.id());
            check(!metric.meaning().isBlank(), "Thread metric meaning: " + metric.id());
        }
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
}
