#!/usr/bin/env python3
"""Smoke guard against large Jolt throughput regressions. Not a benchmark.

Builds jolt-core, compiles the todo app from benchmarks/tokens/jolt, starts it on a free loopback
port, warms it up, runs benchmarks/load/Load.java (50 clients, 20 s) three times and writes the
median req/s and median mean latency to benchmarks/guard/last-run.json.

  python benchmarks/guard/perf-guard.py                    # measure and record
  python benchmarks/guard/perf-guard.py --baseline benchmarks/guard/baseline.json
  python benchmarks/guard/perf-guard.py --write-baseline   # refresh baseline.json on this machine

With --baseline, exits 1 if median req/s drops more than 15% or latency rises more than 25%.
If the baseline was recorded on a different machine, only warns and records.
"""
import argparse
import json
import os
import platform
import re
import shutil
import socket
import statistics
import subprocess
import sys
import tempfile
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
CLIENTS, SECONDS, RUNS, WARMUP_SECONDS = 50, 20, 3, 8
MAX_RPS_DROP, MAX_LATENCY_RISE = 0.15, 0.25
IS_WINDOWS = os.name == "nt"


def cpu_name():
    try:
        if IS_WINDOWS:
            import winreg

            with winreg.OpenKey(
                winreg.HKEY_LOCAL_MACHINE, r"HARDWARE\DESCRIPTION\System\CentralProcessor\0"
            ) as k:
                return winreg.QueryValueEx(k, "ProcessorNameString")[0].strip()
        for line in Path("/proc/cpuinfo").read_text().splitlines():
            if line.startswith("model name"):
                return line.split(":", 1)[1].strip()
    except Exception:
        pass
    return platform.processor() or platform.machine()


def jdk_version():
    out = subprocess.run(["java", "-version"], capture_output=True, text=True).stderr
    return out.splitlines()[0] if out else "unknown"


def machine():
    cpu = cpu_name()
    threads = os.cpu_count()
    return {
        "id": f"{platform.system()}|{cpu}|{threads}threads",
        "cpu": cpu,
        "logical_cpus": threads,
        "os": f"{platform.system()} {platform.release()}",
        "jdk": jdk_version(),
    }


def run(cmd, **kw):
    subprocess.run(cmd, check=True, **kw)


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


def wait_for(port, proc, timeout=30):
    end = time.time() + timeout
    while time.time() < end:
        if proc.poll() is not None:
            raise RuntimeError("app exited early with code %s" % proc.returncode)
        try:
            socket.create_connection(("127.0.0.1", port), 0.5).close()
            return
        except OSError:
            time.sleep(0.2)
    raise RuntimeError("app did not start listening")


def stop(proc):
    if proc.poll() is None:
        proc.terminate()
        try:
            proc.wait(5)
        except subprocess.TimeoutExpired:
            proc.kill()
            proc.wait(5)


def load(url, seconds):
    out = subprocess.run(
        ["java", str(ROOT / "benchmarks/load/Load.java"), url, str(CLIENTS), str(seconds),
         "Authorization: Bearer secret"],
        check=True, capture_output=True, text=True,
    ).stdout
    rps = float(re.search(r"throughput: ([\d.]+) req/s", out).group(1))
    lat = float(re.search(r"mean latency: ([\d.]+) ms", out).group(1))
    failed = int(re.search(r"(\d+) failed", out).group(1))
    return rps, lat, failed


def measure():
    mvn = shutil.which("mvn") or shutil.which("mvn.cmd")
    run([mvn, "-B", "-q", "-pl", "jolt-core", "-DskipTests", "compile"], cwd=ROOT)
    tmp = Path(tempfile.mkdtemp(prefix="jolt-guard-"))
    proc = None
    try:
        sources = [str(ROOT / "benchmarks/tokens/jolt/src/main/java/todo/TodoApp.java"),
                   str(HERE / "GuardApp.java")]
        core = str(ROOT / "jolt-core/target/classes")
        run(["javac", "--release", "21", "-cp", core, "-d", str(tmp)] + sources)
        port = free_port()
        proc = subprocess.Popen(
            ["java", "-cp", os.pathsep.join([str(tmp), core]), "GuardApp", str(port)],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        wait_for(port, proc)
        url = f"http://127.0.0.1:{port}/todos"
        print(f"warming up {WARMUP_SECONDS}s on port {port}", flush=True)
        load(url, WARMUP_SECONDS)
        results = []
        for i in range(RUNS):
            r = load(url, SECONDS)
            print(f"run {i + 1}: {r[0]:.1f} req/s, {r[1]:.2f} ms, {r[2]} failed", flush=True)
            results.append(r)
        return results
    finally:
        if proc is not None:
            stop(proc)
        shutil.rmtree(tmp, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    ap.add_argument("--baseline", help="baseline.json to compare against")
    ap.add_argument("--write-baseline", action="store_true", help="write baseline.json from this run")
    args = ap.parse_args()

    results = measure()
    last = {
        "median_req_per_s": round(statistics.median(r[0] for r in results), 1),
        "median_mean_latency_ms": round(statistics.median(r[1] for r in results), 2),
        "runs": [{"req_per_s": round(r[0], 1), "mean_latency_ms": round(r[1], 2), "failed": r[2]}
                 for r in results],
        "profile": f"GET /todos (authenticated), {CLIENTS} clients, {SECONDS} s x {RUNS} runs, "
                   f"{WARMUP_SECONDS} s warmup",
        "machine": machine(),
        "recorded": time.strftime("%Y-%m-%d"),
    }
    text = json.dumps(last, indent=2) + "\n"
    (HERE / "last-run.json").write_text(text)
    print(f"median: {last['median_req_per_s']} req/s, {last['median_mean_latency_ms']} ms")

    if args.write_baseline:
        (HERE / "baseline.json").write_text(text)
        print("wrote baseline.json")
    if not args.baseline:
        return 0

    base = json.loads(Path(args.baseline).read_text())
    if base["machine"]["id"] != last["machine"]["id"]:
        print(f"WARNING: baseline machine '{base['machine']['id']}' differs from "
              f"'{last['machine']['id']}'. Recorded only, no comparison.")
        return 0
    rps_change = last["median_req_per_s"] / base["median_req_per_s"] - 1
    lat_change = last["median_mean_latency_ms"] / base["median_mean_latency_ms"] - 1
    print(f"req/s {rps_change:+.1%} (limit -{MAX_RPS_DROP:.0%}), "
          f"latency {lat_change:+.1%} (limit +{MAX_LATENCY_RISE:.0%})")
    if rps_change < -MAX_RPS_DROP or lat_change > MAX_LATENCY_RISE:
        print("FAIL: performance regression beyond smoke-guard thresholds")
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
