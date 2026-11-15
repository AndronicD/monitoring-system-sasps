#!/usr/bin/env python3
"""Bounded smoke check: start the demo monitor, drive real work, verify, stop.

Not a benchmark. It asserts that the server starts, becomes ready, serves the
documented endpoints, and reports real readings that move under load.
"""
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
STARTUP_TIMEOUT_SECONDS = 10
READY_TIMEOUT_SECONDS = 8
WORKLOAD_SECONDS = 3
MAX_WORKLOAD_REQUESTS = 40
ITERATIONS = 500_000
SCRATCH_BYTES = 262_144
EXPECTED_METRICS = 19
REQUIRED_READINGS = (
    "host.ram.total.bytes", "host.ram.used.bytes", "jvm.heap.used.bytes",
    "jvm.gc.collections.total", "host.network.rx.bytes.total", "jvm.threads.live",
    "jvm.threads.daemon", "jvm.threads.peak", "jvm.threads.started.total", "jvm.uptime.ms",
)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def get(base, path):
    with urllib.request.urlopen(base + path, timeout=5) as response:
        require(response.status == 200, f"HTTP status {response.status} for {path}")
        return json.load(response)


def expect_status(base, path, expected):
    try:
        get(base, path)
    except urllib.error.HTTPError as error:
        require(error.code == expected, f"Expected HTTP {expected} for {path}, got {error.code}")
        return
    raise RuntimeError(f"Expected HTTP {expected} for {path}")


def await_ready(base):
    deadline = time.monotonic() + READY_TIMEOUT_SECONDS
    while True:
        try:
            get(base, "/health")
            return
        except urllib.error.HTTPError as error:
            require(error.code == 503 and time.monotonic() < deadline, "Server never became ready")
            time.sleep(0.1)


def drive_work(base):
    deadline = time.monotonic() + WORKLOAD_SECONDS
    requests = 0
    while time.monotonic() < deadline and requests < MAX_WORKLOAD_REQUESTS:
        result = get(base, f"/work?iterations={ITERATIONS}")
        require(result["allocatedBytes"] == SCRATCH_BYTES, "Workload did not allocate expected scratch")
        require(result["iterations"] == ITERATIONS, "Workload ignored the requested iterations")
        requests += 1
    require(requests > 0, "No workload requests completed")
    return requests


def verify_readings(metadata, readings):
    require(len(metadata["metrics"]) == EXPECTED_METRICS,
            f"Expected {EXPECTED_METRICS} metrics, found {len(metadata['metrics'])}")
    require(set(readings) == {metric["id"] for metric in metadata["metrics"]},
            "Metric metadata and readings differ")
    unavailable = {}
    for metric, reading in readings.items():
        if reading["value"] is None:
            require(bool(reading["unavailableReason"]), f"Missing unavailable reason: {metric}")
            unavailable[metric] = reading["unavailableReason"]
        else:
            require(math.isfinite(reading["value"]) and reading["value"] >= 0,
                    f"Invalid real reading: {metric}")
    for metric in REQUIRED_READINGS:
        require(readings[metric]["value"] is not None, f"Required demo source unavailable: {metric}")
    return unavailable


def verify_history(history, metadata, before):
    require(0 < len(history) <= metadata["historyCapacity"], "History empty or past its bound")
    uptimes = [sample["readings"]["jvm.uptime.ms"]["value"] for sample in history]
    require(all(value is not None and math.isfinite(value) and value >= 0 for value in uptimes),
            "Invalid JVM uptime in history")
    require(all(left <= right for left, right in zip(uptimes, uptimes[1:])),
            "JVM uptime decreased in history")
    load_samples = [sample for sample in history if sample["sequence"] > before["sequence"]]
    require(load_samples, "History gained no samples while the workload ran")
    return max(sample["readings"]["process.cpu.percent"]["value"] or 0 for sample in load_samples)


def start_server(log):
    process = subprocess.Popen(
        ["bash", "scripts/run.sh", "--port", "0", "--interface",
         "lo0" if platform.system() == "Darwin" else "lo"],
        cwd=ROOT, stdout=subprocess.PIPE, stderr=log, text=True,
    )
    lines = queue.Queue()

    def read_output():
        for line in process.stdout:
            log.write(line)
            log.flush()
            lines.put(line)

    reader = threading.Thread(target=read_output, daemon=True)
    reader.start()
    return process, reader, lines


def stop_server(process, reader):
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)
    reader.join(timeout=2)
    process.stdout.close()
    require(process.poll() is not None, "Demo server was not stopped")


def main():
    require(platform.system() in ("Darwin", "Linux"), "Smoke check requires macOS or Linux")
    log_path = ROOT / "build/traffic-server.log"
    log_path.parent.mkdir(parents=True, exist_ok=True)
    with log_path.open("w") as log:
        process, reader, lines = start_server(log)
        try:
            try:
                line = lines.get(timeout=STARTUP_TIMEOUT_SECONDS)
            except queue.Empty as error:
                raise RuntimeError(f"No startup response; see {log_path}") from error
            match = re.search(r"LISTENING http://\S+?:(\d+)", line)
            require(match is not None, f"Unexpected startup line: {line.strip()}")
            base = f"http://127.0.0.1:{match.group(1)}"

            await_ready(base)
            metadata = get(base, "/metadata")
            time.sleep(2.2)
            before = get(base, "/metrics")
            require(before["readings"]["process.cpu.percent"]["value"] is not None,
                    "Process CPU delta unavailable after warm-up")

            requests = drive_work(base)
            time.sleep(1.2)
            after = get(base, "/metrics")
            history = get(base, "/history")
            health = get(base, "/health")

            require(health["workRequests"] == requests, "Request count mismatch")
            require(after["sequence"] > before["sequence"], "Sampling did not advance under HTTP load")
            unavailable = verify_readings(metadata, after["readings"])
            peak_cpu = verify_history(history, metadata, before)
            require(peak_cpu > 0, "No nonzero process CPU measurement under load")
            uptime_delta = (after["readings"]["jvm.uptime.ms"]["value"]
                            - before["readings"]["jvm.uptime.ms"]["value"])
            require(uptime_delta > 0, "JVM uptime did not advance under HTTP load")
            network_delta = (after["readings"]["host.network.rx.bytes.total"]["value"]
                             - before["readings"]["host.network.rx.bytes.total"]["value"])
            require(network_delta > 0, "Interface counters did not record actual HTTP traffic")
            expect_status(base, "/work?iterations=5000001", 400)
            expect_status(base, "/not-found", 404)

            print(f"PASS: {requests} work requests, {len(history)} samples retained, "
                  f"{len(metadata['metrics'])} metrics")
            print(f"  peak process CPU {peak_cpu:.2f}% | uptime +{uptime_delta:.0f} ms | "
                  f"interface rx +{network_delta:.0f} bytes")
            print(f"  unavailable: {json.dumps(unavailable) if unavailable else 'none'}")
        finally:
            stop_server(process, reader)


if __name__ == "__main__":
    main()
