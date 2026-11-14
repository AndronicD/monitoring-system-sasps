package ro.sasps.monitoring;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        int port = 8080;
        int intervalMs = 1000;
        String requestedInterface = "auto";
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 == args.length) {
                throw new IllegalArgumentException("Expected --port N --interval-ms N --interface NAME");
            }
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                case "--interval-ms" -> intervalMs = Integer.parseInt(args[i + 1]);
                case "--interface" -> requestedInterface = args[i + 1];
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        if (port < 0 || port > 65535 || intervalMs < 100 || intervalMs > 60_000) {
            throw new IllegalArgumentException("Port must be 0..65535; interval must be 100..60000 ms");
        }
        if (!(ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os)) {
            throw new IllegalStateException("This JDK does not expose com.sun.management.OperatingSystemMXBean");
        }
        String interfaceName = NetworkCollector.selectInterface(requestedInterface);
        List<MetricCollector> collectors = List.of(new CpuCollector(os), new MemoryCollector(os),
            new GcCollector(), new NetworkCollector(interfaceName));
        SampleBus bus = new SampleBus();
        SampleHistory history = new SampleHistory(60);
        bus.subscribe(history);
        MetricService service = new MetricService(collectors, bus, intervalMs);
        MonitorServer server = new MonitorServer(port, history, service,
            Json.metadata(collectors, intervalMs, history.capacity(), interfaceName));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            service.close();
        }));
        service.start();
        server.start();
        System.out.println("LISTENING http://127.0.0.1:" + server.port() + " interface=" + interfaceName);
    }
}
