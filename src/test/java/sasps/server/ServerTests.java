package sasps.server;

import sasps.contracts.MetricCollector;
import sasps.contracts.Reading;
import sasps.contracts.Sample;
import sasps.monitoring.demo.MetricService;
import sasps.monitoring.demo.SampleBus;
import sasps.monitoring.demo.SampleHistory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static sasps.TestSuite.check;
import static sasps.TestSuite.constantCollector;
import static sasps.TestSuite.sample;

public final class ServerTests {
    private ServerTests() {
    }

    public static void run() throws Exception {
        json();
        http();
    }

    private static void json() {
        check(Json.quote("\"\\\n\t\u0001").equals("\"\\\"\\\\\\n\\t\\u0001\""), "JSON escaping");
        check(Json.quote(null).equals("null"), "JSON null");
        String encoded = Json.sample(sample(1));
        check(encoded.contains("\"value\":1.0"), "Sample numeric value");
        check(encoded.contains("\"timestamp\":\"2026-01-01T00:00:00Z\""), "Sample timestamp");
        Sample unavailable = new Sample(1, Instant.EPOCH, 0, 0,
            Map.of("x", Reading.unavailable("not supported")));
        check(Json.sample(unavailable).contains("\"value\":null"), "JSON unavailable reading");
        check(Json.sample(unavailable).contains("\"unavailableReason\":\"not supported\""),
            "JSON unavailable reason");
        Sample uptime = new Sample(1, Instant.EPOCH, 0, 0,
            Map.of("jvm.uptime.ms", Reading.available(3_000_000_000L)));
        check(Json.sample(uptime).contains("\"jvm.uptime.ms\":{\"value\":3.0E9,"
            + "\"unavailableReason\":null}"), "Large values serialize as available JSON numbers");
        check(Json.history(List.of(sample(1), sample(2))).startsWith("[{")
            && Json.history(List.of()).equals("[]"), "History JSON array");
    }

    private static void http() throws Exception {
        SampleHistory history = new SampleHistory(2);
        SampleBus bus = new SampleBus();
        bus.subscribe(history);
        MetricCollector collector = constantCollector();
        try (MetricService service = new MetricService(List.of(collector), bus, 1000);
             MonitorServer server = new MonitorServer("127.0.0.1", 0, history, service,
                 Json.metadata(List.of(collector), 1000, 2, "test"))) {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + server.port();
            check(get(client, base + "/health").statusCode() == 503, "Not ready before sample");
            check(get(client, base + "/metrics").statusCode() == 503, "No invented initial sample");
            service.sampleOnce();
            service.sampleOnce();
            check(get(client, base + "/health").statusCode() == 200, "Ready after sample");
            check(get(client, base + "/metrics").body().contains("\"sequence\":2"), "Metrics endpoint");
            check(get(client, base + "/metadata").body().contains("\"scope\":\"test\""), "Metadata endpoint");
            check(get(client, base + "/metadata").body().contains("\"historyCapacity\":2"),
                "Metadata reports the history bound");
            check(get(client, base + "/history").body().startsWith("["), "History endpoint");
            check(get(client, base + "/work?iterations=10").body().contains("\"allocatedBytes\":262144"),
                "Real bounded allocation workload");
            check(get(client, base + "/work?iterations=0").statusCode() == 400, "Reject zero workload");
            check(get(client, base + "/work?iterations=5000001").statusCode() == 400, "Bound workload");
            check(get(client, base + "/work?iterations=bad").statusCode() == 400, "Reject invalid workload");
            check(get(client, base + "/work?x=1").statusCode() == 400, "Reject unknown query");
            check(get(client, base + "/health").body().contains("\"workRequests\":1"),
                "Health counts served workload requests");
            check(get(client, base + "/metrics-extra").statusCode() == 404, "Exact endpoint matching");
            HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create(base + "/work"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            check(response.statusCode() == 405, "Reject unsupported HTTP methods");
            check(response.headers().firstValue("Allow").orElse("").equals("GET"), "Allow header");
        }
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
