package sasps;

import sasps.contracts.MetricCollector;
import sasps.metrics.CpuCollector;
import sasps.metrics.GcCollector;
import sasps.metrics.MemoryCollector;
import sasps.metrics.NetworkCollector;
import sasps.metrics.ThreadCollector;
import sasps.metrics.UptimeCollector;
import sasps.monitoring.demo.MetricService;
import sasps.monitoring.demo.SampleBus;
import sasps.monitoring.demo.SampleHistory;
import sasps.server.Json;
import sasps.server.MonitorServer;

import com.sun.management.OperatingSystemMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;

public final class Main {
    private static final String USAGE =
        "Expected --port N --interval-ms N --interface NAME --bind ADDRESS";

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        int port = 8080;
        int intervalMs = 1000;
        String requestedInterface = "auto";
        String bindAddress = "127.0.0.1";
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 == args.length) {
                throw new IllegalArgumentException(USAGE);
            }
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[i + 1]);
                case "--interval-ms" -> intervalMs = Integer.parseInt(args[i + 1]);
                case "--interface" -> requestedInterface = args[i + 1];
                case "--bind" -> bindAddress = args[i + 1];
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        if (port < 0 || port > 65535 || intervalMs < 100 || intervalMs > 60_000) {
            throw new IllegalArgumentException("Port must be 0..65535; interval must be 100..60000 ms");
        }
        if (bindAddress.isBlank()) {
            throw new IllegalArgumentException("Bind address must not be blank; " + USAGE);
        }
        if (!(ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os)) {
            throw new IllegalStateException("This JDK does not expose com.sun.management.OperatingSystemMXBean");
        }
        String interfaceName = NetworkCollector.selectInterface(requestedInterface);
        List<MetricCollector> collectors = List.of(new CpuCollector(os), new MemoryCollector(os),
            new GcCollector(), new UptimeCollector(ManagementFactory.getRuntimeMXBean()),
            new ThreadCollector(ManagementFactory.getThreadMXBean()),
            new NetworkCollector(interfaceName));
        SampleBus bus = new SampleBus();
        SampleHistory history = new SampleHistory(60);
        bus.subscribe(history);
        MetricService service = new MetricService(collectors, bus, intervalMs);
        MonitorServer server = new MonitorServer(bindAddress, port, history, service,
            Json.metadata(collectors, intervalMs, history.capacity(), interfaceName));
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            service.close();
        }));
        service.start();
        server.start();
        System.out.println("LISTENING http://" + bindAddress + ":" + server.port()
            + " interface=" + interfaceName);
    }
}
