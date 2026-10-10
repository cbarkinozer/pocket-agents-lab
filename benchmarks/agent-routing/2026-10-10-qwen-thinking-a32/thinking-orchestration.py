"""Run Qwen3.5-0.8B-Q4_K_M thinking-ON then thinking-OFF back-to-back.

Purpose: establish whether enable_thinking=False caused the 20% result in the
previous matched-chat run, and pin the real comparison baseline for reflex-1.

Outputs:
  thinking-on-results.json   -- enable_thinking=True
  thinking-off-results.json  -- enable_thinking=False (re-run for confirmation)

Run from repo root:
  python3 benchmarks/agent-routing/2026-10-10-qwen-thinking-a32/thinking-orchestration.py
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


def start_server(log_path):
    print(f"Starting Qwen server → {log_path.name}", flush=True)
    log = log_path.open("w")
    proc = subprocess.Popen([ADB, "-s", SERIAL, "shell", SERVER_CMD],
                            stdout=log, stderr=subprocess.STDOUT)
    for attempt in range(90):
        if proc.poll() is not None:
            raise RuntimeError(f"Server exited unexpectedly (attempt {attempt})")
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/health", timeout=2) as r:
                if json.load(r).get("status") == "ok":
                    pid = adb("shell", "pidof llama-server").strip()
                    print(f"Server ready pid={pid}", flush=True)
                    return proc, pid
        except Exception:
            pass
        time.sleep(2)
    raise RuntimeError("Server load timeout after 180s")


def stop_server(proc):
    try:
        adb("shell", "pkill -INT -x llama-server")
    except Exception:
        pass
    try:
        proc.wait(timeout=15)
    except subprocess.TimeoutExpired:
        proc.terminate()
    print("Server stopped", flush=True)


def run_benchmark(pid, out_file, enable_thinking, bench_log_path):
    flag = ["--enable-thinking"] if enable_thinking else []
    thinking_label = "thinking-on" if enable_thinking else "thinking-off"
    cmd = [
        sys.executable,
        str(ROOT / "tools/benchmark_decision_android.py"),
        "--adb", ADB,
        "--serial", SERIAL,
        "--mode", "chat",
        "--label", f"Qwen3.5-0.8B-Q4_K_M-{thinking_label}",
        "--model-sha256", MODEL_SHA,
        "--runtime-revision", RUNTIME_REV,
        "--pid", pid,
        "--out", str(out_file),
    ] + flag
    print(f"\n{'='*60}", flush=True)
    print(f"Running {thinking_label} benchmark → {out_file.name}", flush=True)
    print(f"{'='*60}\n", flush=True)
    with bench_log_path.open("w") as bench_log:
        subprocess.run(cmd, cwd=ROOT, stdout=bench_log, stderr=subprocess.STDOUT, check=True)


def summarise(path, label):
    d = json.loads(path.read_text())
    correct = d.get("correct", 0)
    total = d.get("total", 0)
    mean_s = d.get("meanLatencyMs", 0) / 1000
    print(f"  {label}: {correct}/{total} ({100*correct/total:.1f}%) mean={mean_s:.1f}s", flush=True)


# ── ensure port forwarding ──────────────────────────────────────────────────
fwd = subprocess.check_output([ADB, "-s", SERIAL, "forward", "--list"], text=True)
if f"tcp:{PORT}" not in fwd:
    subprocess.check_call([ADB, "-s", SERIAL, "forward", f"tcp:{PORT}", f"tcp:{PORT}"])
    print(f"ADB port {PORT} forwarded", flush=True)

# ── verify SHA before any run ───────────────────────────────────────────────
live_sha = adb("shell", f"sha256sum {MODEL_PATH}").split()[0]
if live_sha != MODEL_SHA:
    raise RuntimeError(f"SHA mismatch: {live_sha} != {MODEL_SHA}")
print(f"Model SHA verified: {live_sha[:16]}…", flush=True)

# ── run 1: thinking ON ──────────────────────────────────────────────────────
proc, pid = start_server(OUT / "thinking-on-server.log")
try:
    run_benchmark(pid, OUT / "thinking-on-results.json",
                  enable_thinking=True, bench_log_path=OUT / "thinking-on-bench.log")
finally:
    stop_server(proc)

time.sleep(5)  # let sockets drain

# ── run 2: thinking OFF (re-confirm) ────────────────────────────────────────
proc, pid = start_server(OUT / "thinking-off-server.log")
try:
    run_benchmark(pid, OUT / "thinking-off-results.json",
                  enable_thinking=False, bench_log_path=OUT / "thinking-off-bench.log")
finally:
    stop_server(proc)

# ── summary ─────────────────────────────────────────────────────────────────
print("\n===== THINKING COMPARISON SUMMARY =====", flush=True)
for fname, lbl in [("thinking-on-results.json",  "thinking=ON "),
                   ("thinking-off-results.json", "thinking=OFF")]:
    p = OUT / fname
    if p.exists():
        summarise(p, lbl)
print("Previous thinking=OFF run (Oct 10): 10/50 (20%) mean=64.8s", flush=True)
print("Historical JNI (Sep 11):            41/50 (82%) mean=53.0s", flush=True)
print("Done.", flush=True)
