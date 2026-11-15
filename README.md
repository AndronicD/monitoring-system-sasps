# Monitoring System - SASPS

Java web server that monitors its own process and the host machine: CPU, RAM, network,
JVM heap, GC, JVM threads and uptime. It samples every second, pushes each sample to
subscribers, keeps the last 60, and serves them over HTTP.

Needs JDK 17+, Bash, and Python 3.9+ for the traffic smoke check. No other dependencies.

## Run

    bash scripts/build.sh
    bash scripts/run.sh --port 8080

    curl -sS http://127.0.0.1:8080/metrics
    curl -sS 'http://127.0.0.1:8080/work?iterations=500000'

Options: `--port N` (0 picks a free port), `--interval-ms N`, `--interface NAME`
(`auto` selects the first active non-loopback interface), `--bind ADDRESS`
(default `127.0.0.1`; use `0.0.0.0` only when the port must be reachable from
outside the machine, as in a container).

## Test

    bash scripts/test.sh        # Java tests
    python3 traffic/demo.py     # bounded smoke check: starts the server, drives work, stops it

The smoke check starts its own server on a free port, waits for readiness, exercises
`/work`, verifies the readings and history, and always stops the process it started.
It is a smoke test, not a benchmark. Server output goes to `build/traffic-server.log`.

## Endpoints

| GET | Returns |
|---|---|
| `/health` | 503 until the first sample, then 200 |
| `/metadata` | Source, unit and meaning of each metric |
| `/metrics` | Latest sample |
| `/history` | Last 60 samples |
| `/work?iterations=N` | Burns CPU so you can watch the numbers move |

Unavailable readings are `null` with a reason, never a fake zero. Rate and delta
metrics are unavailable on the first sample, by design.

## Metrics

19 metrics, all defined in `/metadata`.

| Metrics | Scope | Source | Caveat |
|---|---|---|---|
| `host.cpu.percent`, `process.cpu.percent` | host, process | `OperatingSystemMXBean` | Host load includes other processes; process CPU is a delta over wall time |
| `host.ram.total.bytes`, `host.ram.used.bytes` | host | `OperatingSystemMXBean` | Used RAM includes OS caches |
| `jvm.heap.{used,committed,max}.bytes`, `jvm.nonheap.used.bytes` | process | `MemoryMXBean` | Heap use is not process RSS |
| `jvm.gc.collections.total`, `jvm.gc.collection.time.ms.total` | process | `GarbageCollectorMXBean` | Accumulated totals, not a pause distribution or p99 |
| `jvm.threads.{live,daemon,peak,started.total}` | process | `ThreadMXBean` | Platform threads only; counters are read independently, so a sample is not atomic |
| `jvm.uptime.ms` | process | `RuntimeMXBean` | Time since JVM start, not host uptime; resets when the JVM restarts |
| `host.network.{rx,tx}.bytes.total`, `host.network.{rx,tx}.bytes.per.second` | host/interface | `/proc/net/dev` or `netstat -ibn` | Whole-interface counters, including other processes |

## Docker

Both images run this same demo and reuse `scripts/build.sh` and `scripts/run.sh`.
Both use build context **the repository root** and bind `0.0.0.0` so the published
port is reachable.

`Dockerfile` - main project deployment. Multi-stage JDK build, JRE runtime with only
the jar.

    docker build -t sasps-monitor .
    docker run --rm -p 8080:8080 sasps-monitor
    curl -sS http://127.0.0.1:8080/health

`demo/Dockerfile` - demo-specific deployment. Adds Python and `traffic/`, so the
smoke check and the Java tests also run inside the container.

    docker build -f demo/Dockerfile -t sasps-monitor-demo .
    docker run --rm -p 8080:8080 sasps-monitor-demo
    docker run --rm sasps-monitor-demo python3 traffic/demo.py
    docker run --rm sasps-monitor-demo bash scripts/test.sh

In a container, `host.*` metrics describe the container's view of the machine, not the
Docker host: the network interface is the container's namespaced `eth0`, and CPU and
RAM reflect whatever the JVM sees under the container's limits. Process and JVM metrics
are unaffected.

## Layout

    src/main/java/sasps/
      contracts/          Metric, Reading, Sample, MetricCollector
      metrics/            The six collectors defining the 19 metrics
      monitoring/demo/    MetricService (sampling), SampleBus (push), SampleHistory (bounded)
      server/             MonitorServer (endpoints, workload), Json (responses)
      Main.java           Startup wiring
    src/test/java/sasps/  TestSuite entry point plus one test class per package
    traffic/              Bounded traffic generator and smoke checks
    scripts/              build.sh, run.sh, test.sh
