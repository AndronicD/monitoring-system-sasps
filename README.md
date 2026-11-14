# Monitoring System - SASPS

M1 foundation for a computational resource monitoring system: a monitored Java
web server and a metric service running in the same JVM. It measures the
resources of the Java process and of the host machine.

## What it does so far

- Collects 14 real metrics: host CPU, process CPU, host RAM, network rx/tx,
  JVM heap and non-heap, GC collection count and time.
- Samples periodically and builds one immutable `Sample` per tick.
- Pushes each sample to subscribers (Observer, no polling loop).
- Keeps a bounded in-memory history of the last 60 samples.
- Exposes everything over a plain JDK HTTP server, plus a small CPU workload
  endpoint to drive a demo.
- Ships 47 self-contained checks and a reproducible end-to-end demo.

When a source is unavailable the JSON value is `null` with an
`unavailableReason` - never a fabricated zero.

Not built yet: the SPI/factory collector registry, the pooled ring buffer, the
antipattern variants and the comparative experiments# Monitoring System - rd
M1 foundation for a compul iweb server and a metric service running in the same JVM. It measures the
resothresources of the Java process and of the host machine.

## What it doeset
## What it does so far

- Collects 14 real metrics: ere
- Collects 14 real mlab  JVM heap and non-heap, GC collection count and time.
- Samples periodica -- Samples periodically and builds o
