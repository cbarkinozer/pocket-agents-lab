"""Run Qwen thinking-ON only. Run this AFTER thinking-orchestration.py completes.

The orchestration ran thinking-ON with the old harness (grammar+64-token budget,
which produced empty content). This script re-runs thinking-ON with the fixed
harness (no grammar, 1024-token budget, regex JSON extraction).

Usage:
  python3 benchmarks/agent-routing/2026-10-10-qwen-thinking-a32/run-thinking-on.py
"""
import json, pathlib, subprocess, sys, time, urllib.request

ROOT = pathlib.Path("/Users/cbarkinozer/Documents/GitHub/pocket-agents-lab")
OUT  = ROOT / "benchmarks/agent-routing/2026-10-10-qwen-thinking-a32"
ADB  = "/Users/cbarkinozer/.cache/reflex-android/platform-tools/adb"
SERIAL = "R68T800LZ0H"
MODEL_PATH = "/sdcard/Download/models/Qwen3.5-0.8B-Q4_K_M.gguf"
MODEL_SHA  = "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517"
RUNTIME_REV = "f0c41e016-b11443"
PORT = 8091

SERVER_CMD = (
    "cd /data/local/tmp/reflex-runtime; "
    "LD_LIBRARY_PATH=. ./llama-server "
    f"-m {MODEL_PATH} "
    "-c 2048 -t 4 -tb 4 -ngl 0 --fit off -fa off --no-repack "
    f"--host 127.0.0.1 --port {PORT} --jinja"
)


def adb(*args):
    return subprocess.check_output([ADB, "-s", SERIAL] + list(args), text=True, timeout=30)


# ensure port forwarding
fwd = subprocess.check_output([ADB, "-s", SERIAL, "forward", "--list"], text=True)
if f"tcp:{PORT}" not in fwd:
    subprocess.check_call([ADB, "-s", SERIAL, "forward", f"tcp:{PORT}", f"tcp:{PORT}"])

# verify SHA
live_sha = adb("shell", f"sha256sum {MODEL_PATH}").split()[0]
assert live_sha == MODEL_SHA, f"SHA mismatch: {live_sha}"
print(f"Model SHA verified: {live_sha[:16]}…")

# start server
log = (OUT / "thinking-on-v2-server.log").open("w")
proc = subprocess.Popen([ADB, "-s", SERIAL, "shell", SERVER_CMD],
                        stdout=log, stderr=subprocess.STDOUT)
for i in range(90):
    if proc.poll() is not None:
        raise RuntimeError("Server exited")
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/health", timeout=2) as r:
            if json.load(r).get("status") == "ok":
                pid = adb("shell", "pidof llama-server").strip()
                print(f"Server ready pid={pid}")
                break
    except Exception:
        pass
    time.sleep(2)
else:
    raise RuntimeError("Server timeout")

# run benchmark with --enable-thinking
cmd = [
    sys.executable,
    str(ROOT / "tools/benchmark_decision_android.py"),
    "--adb", ADB, "--serial", SERIAL,
    "--mode", "chat",
    "--enable-thinking",
    "--label", "Qwen3.5-0.8B-Q4_K_M-thinking-on",
    "--model-sha256", MODEL_SHA,
    "--runtime-revision", RUNTIME_REV,
    "--pid", pid,
    "--out", str(OUT / "thinking-on-v2-results.json"),
]
print("Running thinking-ON benchmark…")
try:
    with (OUT / "thinking-on-v2-bench.log").open("w") as bench_log:
        subprocess.run(cmd, cwd=ROOT, stdout=bench_log, stderr=subprocess.STDOUT, check=True)
finally:
    try:
        adb("shell", "pkill -INT -x llama-server")
    except Exception:
        pass
    proc.wait(timeout=15)

# summary
d = json.loads((OUT / "thinking-on-v2-results.json").read_text())
c, t = d.get("correct", 0), d.get("total", 0)
mean = d.get("meanLatencyMs", 0) / 1000
print(f"\nThinking-ON: {c}/{t} ({100*c/t:.1f}%) mean={mean:.1f}s")
print(f"Thinking-OFF (prior run): see thinking-off-results.json")
