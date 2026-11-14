package sasps.monitoring;

import sasps.monitoring.model.*;
import sasps.monitoring.collectors.*;
import sasps.monitoring.store.*;


import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class Json {
    private Json() {
    }

    public static String quote(String text) {
        if (text == null) {
            return "null";
        }
        StringBuilder json = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (c < 0x20) {
                        json.append(String.format("\\u%04x", (int) c));
                    } else {
                        json.append(c);
                    }
                }
            }
        }
        return json.append('"').toString();
    }

    public static String sample(Sample sample) {
        String values = sample.readings().entrySet().stream().map(Json::reading)
            .collect(Collectors.joining(","));
        return "{\"sequence\":" + sample.sequence() + ",\"timestamp\":" + quote(sample.timestamp().toString())
            + ",\"intervalMs\":" + sample.intervalMs()
            + ",\"measurementDurationMs\":" + sample.measurementDurationMs()
            + ",\"readings\":{" + values + "}}";
    }

    private static String reading(Map.Entry<String, Reading> entry) {
        Reading reading = entry.getValue();
        return quote(entry.getKey()) + ":{\"value\":" + reading.value()
            + ",\"unavailableReason\":" + quote(reading.unavailableReason()) + "}";
    }

    public static String history(List<Sample> samples) {
        return samples.stream().map(Json::sample).collect(Collectors.joining(",", "[", "]"));
    }

    public static String metadata(List<MetricCollector> collectors, int intervalMs,
                                  int historyCapacity, String interfaceName) {
        String metrics = collectors.stream().flatMap(c -> c.metrics().stream()).map(m ->
            "{\"id\":" + quote(m.id()) + ",\"unit\":" + quote(m.unit())
                + ",\"scope\":" + quote(m.scope()) + ",\"source\":" + quote(m.source())
                + ",\"meaning\":" + quote(m.meaning()) + "}")
            .collect(Collectors.joining(",", "[", "]"));
        return "{\"samplingDelayMs\":" + intervalMs + ",\"historyCapacity\":" + historyCapacity
            + ",\"networkInterface\":" + quote(interfaceName)
            + ",\"os\":" + quote(System.getProperty("os.name"))
            + ",\"javaVersion\":" + quote(System.getProperty("java.version"))
            + ",\"logicalProcessors\":" + Runtime.getRuntime().availableProcessors()
            + ",\"processNetwork\":\"unavailable: no per-process network counters in standard JDK\""
            + ",\"metrics\":" + metrics + "}";
    }
}
