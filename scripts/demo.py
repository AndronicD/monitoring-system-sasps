#!/usr/bin/env python3
"""Bounded, real-resource end-to-end demo. No third-party Python packages."""
import concurrent.futures
import datetime
import json
import math
import platform
import queue
import re
import subprocess
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def main():
    system = platform.system()
    require(system in ("Darwin", "Linux"), "Demo requires macOS or Linux network counters")
    interface = "lo0" if system == "Darwin" else "lo"
    log_path = ROOT / "build/demo-server.log"
    lines = queue.Queue()
    with log_path.open("w") as log:
        process = subprocess.Popen(
            ["bash", "scripts/run.sh", "--port", "0", "--interface", interface],
            cwd=ROOT, stdout=subprocess.PIPE, stderr=log, text=True,
        )

        def read_output():
            for line in process.stdout:
                log.write(line)
                log.flush()
                lines.put(line)

        reader = threading.Thread(target=read_output, daemon=True)
        reader.start()
        try:
            try:
                line = lines.get(timeout=10)
            except queue.Empty as error:
                raise RuntimeError(f"No startup response; see {log_path}") from error
            match = re.search(r"LISTENING (http://127\.0\.0\.1:\d+)", line)
            require(match is not None, f"Unexpected startup line: {line}")
            base = match.group(1)

            def get(path):
                with urllib.request.urlopen(base + path, timeout=5) as response:
                    require(response.status == 200, f"HTTP status {response.status}")
                    return json.load(response)

            deadline = time.monotonic() + 8
            while True:
                try:
                    get("/health")
                    break
                except urllib.error.HTTPError as error:
                    if error.code != 503 or time.monotonic() >= deadline:
                        raise
                    time.sleep(0.1)
            metadata = get("/metadata")
            time.sleep(2.2)
            before = get("/metrics")
            require(before["readings"]["process.cpu.percent"]["value"] is not None,
                    "Process CPU delta unavailable after warm-up")
            start = time.monotonic()
            stop = start + 6

            def drive_work():
                count = 0
                while time.monotonic() < stop:
                    result = get("/work?iterations=500000")
                    require(result["allocatedBytes"] == 262144, "Workload did not allocate expected scratch")
                    count += 1
                return count

            with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:
                requests = sum(executor.map(lambda _: drive_work(), range(4)))
            workload_seconds = time.monotonic() - start
            time.sleep(1.2)
            after = get("/metrics")
            history = get("/history")
            health = get("/health")
            require(requests > 0 and health["workRequests"] == requests, "Request count mismatch")
            require(len(history) <= metadata["historyCapacity"], "History exceeded its bound")
            require(after["sequence"] > before["sequence"], "Sampling did not advance under HTTP load")
            require(len(metadata["metrics"]) == 14, "Expected 14 resource metrics")
            expected_ids = {metric["id"] for metric in metadata["metrics"]}
            require(set(after["readings"]) == expected_ids, "Metric metadata and readings differ")
            unavailable = {}
            for metric, reading in after["readings"].items():
                if reading["value"] is None:
                    require(bool(reading["unavailableReason"]), f"Missing unavailable reason: {metric}")
                    unavailable[metric] = reading["unavailableReason"]
                else:
                    require(math.isfinite(reading["value"]) and reading["value"] >= 0,
                            f"Invalid real reading: {metric}")
            for metric in ("host.ram.total.bytes", "host.ram.used.bytes", "jvm.heap.used.bytes",
                           "jvm.gc.collections.total", "host.network.rx.bytes.total"):
                require(after["readings"][metric]["value"] is not None,
                        f"Required demo source unavailable: {metric}")
            network_delta = after["readings"]["host.network.rx.bytes.total"]["value"] \
                - before["readings"]["host.network.rx.bytes.total"]["value"]
            require(network_delta > 0, "Loopback counters did not record actual HTTP traffic")
            load_samples = [sample for sample in history if before["sequence"] < sample["sequence"]]
            peak_cpu = max(sample["readings"]["process.cpu.percent"]["value"] or 0
                           for sample in load_samples)
            require(peak_cpu > 0, "No nonzero process CPU measurement under load")
            for path, expected in (("/work?iterations=5000001", 400), ("/not-found", 404)):
                try:
                    get(path)
                    raise RuntimeError(f"Expected HTTP {expected} for {path}")
                except urllib.error.HTTPError as error:
                    require(error.code == expected, f"Wrong status for {path}")
            report = {
                "kind": "Observed M1 smoke demo, NOT pattern-antipattern comparison",
                "recordedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
                "platform": platform.platform(),
                "metadata": metadata,
                "workload": {"concurrentClients": 4, "iterationsPerRequest": 500000,
                             "requests": requests, "durationSeconds": workload_seconds,
                             "scratchBytesPerRequest": 262144},
                "observations": {
                    "peakProcessCpuPercentOfMachineCapacity": peak_cpu,
                    "hostLoopbackReceivedBytesDelta": network_delta,
                    "gcCollectionsDelta": after["readings"]["jvm.gc.collections.total"]["value"]
                    - before["readings"]["jvm.gc.collections.total"]["value"],
                    "latestHeapUsedBytes": after["readings"]["jvm.heap.used.bytes"]["value"],
                    "samplesRetained": len(history), "unavailableMetrics": unavailable,
                },
                "before": before, "after": after, "history": history,
                "limitations": [
                    "Shared host: interface and host CPU/RAM include other processes.",
                    "Loopback bytes count HTTP inspection traffic too; not process network attribution.",
                    "Short smoke run: not a benchmark, no p99 or comparative conclusions.",
                    "GC collection time is not a stop-the-world pause distribution.",
                ],
            }
            output = ROOT / "build/demo-report.json"
            output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
            print(f"PASS: {requests} real HTTP workload requests; {len(history)} real resource samples")
            print(json.dumps(report["observations"], indent=2))
            print(f"Evidence: {output.relative_to(ROOT)}")
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            reader.join(timeout=2)
            process.stdout.close()
            require(process.poll() is not None, "Demo server was not stopped")


if __name__ == "__main__":
    main()
