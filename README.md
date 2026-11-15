# Monitoring System - SASPS

Java web server that monitors its own process and the host machine:
CPU, RAM, network, JVM heap, GC, JVM threads and uptime. Samples every second, pushes each
sample to subscribers, keeps the last 60, serves them over HTTP.

Needs JDK 17+, Bash, and Python 3.9+ for the demo. No other dependencies.

## Run

    bash scripts/build.sh
    bash scripts/run.sh --port 8080

    curl -sS http://127.0.0.1:8080/metrics
    curl -sS 'http://127.0.0.1:8080/work?iterations=500000'

## Test

    bash scripts/test.sh
    python3 scripts/demo.py

## Endpoints

| GET | Returns |
|---|---|
| `/health` | 503 until the first sample, then 200 |
| `/metadata` | Source, unit and meaning of each metric |
| `/metrics` | Latest sample |
| `/history` | Last 60 samples |
| `/work?iterations=N` | Burns CPU so you can watch the numbers move |

Unavailable readings are `null` with a reason, never a fake zero.

## JVM uptime

`jvm.uptime.ms` reports elapsed time since JVM start in milliseconds, using
`RuntimeMXBean.getUptime()`. It has process scope and is included in `/metrics`
and `/history`, with its definition in `/metadata`.

This is not host uptime or time since sampling began. It restarts from zero
when the JVM restarts. The demo checks that uptime advances under load and does
not decrease across retained samples.

## JVM thread metrics

The following metrics are included in `/metrics` and `/history`, with definitions
in `/metadata`. All use `ThreadMXBean`, have process scope, and are measured in
threads.

| Metric | Meaning |
|---|---|
| `jvm.threads.live` | Current live threads, including daemon and non-daemon threads |
| `jvm.threads.daemon` | Current live daemon threads |
| `jvm.threads.peak` | Peak live threads since JVM start or the last peak-count reset |
| `jvm.threads.started.total` | All threads started since JVM start, including terminated threads |

These are platform-thread counts, not virtual-thread counts. Counters are read
independently, so a sample is not an atomic snapshot of thread activity.

## Code

`src/main/java/sasps/monitoring/` - collectors, `MetricService`
(scheduler), `SampleBus` (push), `SampleHistory` (bounded), `MonitorServer`.
