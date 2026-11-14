package sasps.monitoring;

import sasps.monitoring.model.*;
import sasps.monitoring.collectors.*;
import sasps.monitoring.store.*;


import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public final class MonitorServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(64), new ThreadPoolExecutor.CallerRunsPolicy());
    private final SampleHistory history;
    private final MetricService service;
    private final String metadata;
    private final AtomicLong workRequests = new AtomicLong();

    public MonitorServer(int port, SampleHistory history, MetricService service, String metadata)
            throws IOException {
        this.history = history;
        this.service = service;
        this.metadata = metadata;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 32);
        server.setExecutor(executor);
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!exchange.getRequestMethod().equals("GET")) {
                exchange.getResponseHeaders().set("Allow", "GET");
                send(exchange, 405, "{\"error\":\"GET only\"}");
                return;
            }
            switch (exchange.getRequestURI().getPath()) {
                case "/health" -> health(exchange);
                case "/metadata" -> send(exchange, 200, metadata);
                case "/metrics" -> {
                    Sample latest = history.latest();
                    send(exchange, latest == null ? 503 : 200,
                        latest == null ? "{\"error\":\"Waiting for first sample\"}" : Json.sample(latest));
                }
                case "/history" -> send(exchange, 200, Json.history(history.snapshot()));
                case "/work" -> work(exchange);
                case "/" -> send(exchange, 200,
                    "{\"application\":\"Resource monitor M1\",\"endpoints\":[\"/health\",\"/metadata\","
                        + "\"/metrics\",\"/history\",\"/work?iterations=500000\"]}");
                default -> send(exchange, 404, "{\"error\":\"Unknown endpoint\"}");
            }
        } catch (IOException e) {
            System.err.println("HTTP_IO_FAILED: " + e.getMessage());
            throw e;
        }
    }

    private void health(HttpExchange exchange) throws IOException {
        boolean ready = history.latest() != null && service.lastError() == null;
        send(exchange, ready ? 200 : 503, "{\"status\":" + Json.quote(ready ? "ok" : "not_ready")
            + ",\"samplingError\":" + Json.quote(service.lastError())
            + ",\"workRequests\":" + workRequests.get()
            + ",\"note\":\"Per-metric availability is reported in /metrics\"}");
    }

    private void work(HttpExchange exchange) throws IOException {
        int iterations = 500_000;
        String query = exchange.getRequestURI().getRawQuery();
        if (query != null) {
            if (!query.matches("iterations=[0-9]{1,8}")) {
                send(exchange, 400, "{\"error\":\"Expected iterations=1..5000000\"}");
                return;
            }
            iterations = Integer.parseInt(query.substring("iterations=".length()));
            if (iterations < 1 || iterations > 5_000_000) {
                send(exchange, 400, "{\"error\":\"Iterations must be 1..5000000\"}");
                return;
            }
        }
        byte[] scratch = new byte[256 * 1024];
        long checksum = 1;
        for (int i = 0; i < iterations; i++) {
            checksum = checksum * 1_664_525 + 1_013_904_223;
            scratch[i & (scratch.length - 1)] = (byte) checksum;
        }
        for (byte value : scratch) {
            checksum += value;
        }
        long count = workRequests.incrementAndGet();
        send(exchange, 200, "{\"checksum\":" + checksum + ",\"allocatedBytes\":" + scratch.length
            + ",\"iterations\":" + iterations + ",\"workRequests\":" + count + "}");
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
