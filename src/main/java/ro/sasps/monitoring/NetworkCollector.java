package ro.sasps.monitoring;

import java.io.IOException;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public final class NetworkCollector implements MetricCollector {
    public record Counters(long received, long sent) {
        public Counters {
            if (received < 0 || sent < 0) {
                throw new IllegalArgumentException("Network byte counters must be nonnegative");
            }
        }
    }

    private final String interfaceName;
    private final String platform = System.getProperty("os.name").toLowerCase(Locale.ROOT);
    private Counters previous;
    private long previousNanos;
    private String lastWarning;

    public NetworkCollector(String interfaceName) {
        this.interfaceName = interfaceName;
    }

    public static String selectInterface(String requested) throws SocketException {
        if (!requested.equals("auto")) {
            if (NetworkInterface.getByName(requested) == null) {
                throw new IllegalArgumentException("Unknown network interface: " + requested);
            }
            return requested;
        }
        List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
        interfaces.sort(java.util.Comparator.comparing(NetworkInterface::getName));
        for (NetworkInterface candidate : interfaces) {
            if (candidate.isUp() && !candidate.isLoopback()) {
                return candidate.getName();
            }
        }
        throw new IllegalArgumentException("No active non-loopback interface; use --interface explicitly");
    }

    public String interfaceName() {
        return interfaceName;
    }

    @Override
    public List<Metric> metrics() {
        String source = "Linux /proc/net/dev or macOS /usr/sbin/netstat -ibn -I " + interfaceName;
        return List.of(
            new Metric("host.network.rx.bytes.total", "bytes", "host/interface", source,
                "Cumulative received bytes on selected interface; includes other processes"),
            new Metric("host.network.tx.bytes.total", "bytes", "host/interface", source,
                "Cumulative transmitted bytes on selected interface; includes other processes"),
            new Metric("host.network.rx.bytes.per.second", "bytes/s", "host/interface", source,
                "Counter delta / actual monotonic interval; first sample or reset is unavailable"),
            new Metric("host.network.tx.bytes.per.second", "bytes/s", "host/interface", source,
                "Counter delta / actual monotonic interval; first sample or reset is unavailable")
        );
    }

    @Override
    public Map<String, Reading> collect(long nowNanos) {
        try {
            Counters current = readCounters();
            Map<String, Reading> readings = new LinkedHashMap<>();
            readings.put("host.network.rx.bytes.total", Reading.available(current.received()));
            readings.put("host.network.tx.bytes.total", Reading.available(current.sent()));
            boolean validDelta = previous != null && nowNanos > previousNanos
                && current.received() >= previous.received() && current.sent() >= previous.sent();
            double seconds = (nowNanos - previousNanos) / 1_000_000_000.0;
            readings.put("host.network.rx.bytes.per.second", validDelta
                ? Reading.available((current.received() - previous.received()) / seconds)
                : Reading.unavailable("First sample or network counter reset"));
            readings.put("host.network.tx.bytes.per.second", validDelta
                ? Reading.available((current.sent() - previous.sent()) / seconds)
                : Reading.unavailable("First sample or network counter reset"));
            previous = current;
            previousNanos = nowNanos;
            lastWarning = null;
            return readings;
        } catch (IOException e) {
            return unavailable(e.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unavailable("Network counter command interrupted");
        }
    }

    private Map<String, Reading> unavailable(String reason) {
        if (!reason.equals(lastWarning)) {
            System.err.println("NETWORK_UNAVAILABLE: " + reason);
            lastWarning = reason;
        }
        previous = null;
        Map<String, Reading> readings = new LinkedHashMap<>();
        for (Metric metric : metrics()) {
            readings.put(metric.id(), Reading.unavailable(reason));
        }
        return readings;
    }

    private Counters readCounters() throws IOException, InterruptedException {
        if (platform.contains("linux")) {
            return parseLinux(Files.readString(Path.of("/proc/net/dev")), interfaceName);
        }
        if (platform.contains("mac")) {
            Process command = new ProcessBuilder("/usr/sbin/netstat", "-ibn", "-I", interfaceName)
                .redirectErrorStream(true).start();
            try {
                if (!command.waitFor(750, TimeUnit.MILLISECONDS)) {
                    throw new IOException("netstat exceeded 750 ms timeout");
                }
                String output = new String(command.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                if (command.exitValue() != 0) {
                    throw new IOException("netstat failed with exit code " + command.exitValue());
                }
                return parseMac(output, interfaceName);
            } finally {
                command.destroyForcibly();
            }
        }
        throw new IOException("Network counters unsupported on " + System.getProperty("os.name")
            + "; Linux and macOS only");
    }

    public static Counters parseLinux(String text, String name) throws IOException {
        for (String line : text.split("\\R")) {
            int colon = line.indexOf(':');
            if (colon >= 0 && line.substring(0, colon).trim().equals(name)) {
                String[] fields = line.substring(colon + 1).trim().split("\\s+");
                if (fields.length < 16) {
                    throw new IOException("Incomplete /proc/net/dev row for " + name);
                }
                return counters(fields[0], fields[8]);
            }
        }
        throw new IOException("Interface missing from /proc/net/dev: " + name);
    }

    public static Counters parseMac(String text, String name) throws IOException {
        String[] lines = text.strip().split("\\R");
        if (lines.length < 2) {
            throw new IOException("Empty netstat interface output");
        }
        List<String> header = List.of(lines[0].trim().split("\\s+"));
        int rx = header.indexOf("Ibytes");
        int tx = header.indexOf("Obytes");
        if (rx < 0 || tx < 0) {
            throw new IOException("netstat byte columns missing");
        }
        for (int i = 1; i < lines.length; i++) {
            String[] fields = lines[i].trim().split("\\s+");
            if (fields.length > 2 && fields[0].equals(name) && fields[2].startsWith("<Link#")) {
                // Loopback link rows omit Address; IP rows repeat the same counters.
                int shift = fields.length == header.size() - 1 ? -1 : 0;
                if (fields.length <= Math.max(rx, tx) + shift) {
                    throw new IOException("Incomplete netstat link row for " + name);
                }
                return counters(fields[rx + shift], fields[tx + shift]);
            }
        }
        throw new IOException("Interface link row missing from netstat: " + name);
    }

    private static Counters counters(String rx, String tx) throws IOException {
        try {
            return new Counters(Long.parseLong(rx), Long.parseLong(tx));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid network byte counters", e);
        }
    }
}
