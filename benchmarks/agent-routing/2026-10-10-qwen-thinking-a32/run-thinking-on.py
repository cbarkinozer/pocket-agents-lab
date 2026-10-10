"""Run Qwen thinking-ON then raw-completion treatments.

Run this AFTER thinking-orchestration.py completes (thinking-OFF already done).

Two new runs:
  thinking-on-v2-results.json   -- chat mode, enable_thinking=True, no grammar,
                                   1024 max_tokens, regex JSON extraction
  raw-results.json              -- /v1/completions (no chat template), grammar,
                                   replicates Android JNI [[POCKET_GRAMMAR_ROUTE]] path

This directly tests whether the 82% JNI accuracy is due to:
  (a) thinking enabled, or
  (b) raw text completion without the Qwen chat template

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


def start_server(log_path):
    print(f"Starting server → {log_path.name}", flush=True)
    log = log_path.open("w")
    proc = subprocess.Popen([ADB, "-s", SERIAL, "shell", SERVER_CMD],
                            stdout=log, stderr=subprocess.STDOUT)
    for _ in range(90):
        if proc.poll() is not None:
            raise RuntimeError("Server exited unexpectedly")
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/health", timeout=2) as r:
                if json.load(r).get("status") == "ok":
                    pid = adb("shell", "pidof llama-server").strip()
                    print(f"Server ready pid={pid}", flush=True)
                    return proc, pid
        except Exception:
            pass
        time.sleep(2)
    raise RuntimeError("Server timeout")


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


def run_bench(pid, out_file, mode, bench_log, extra_flags=None):
    cmd = [
        sys.executable,
        str(ROOT / "tools/benchmark_decision_android.py"),
        "--adb", ADB, "--serial", SERIAL,
        "--mode", mode,
        "--label", f"Qwen3.5-0.8B-Q4_K_M-{mode}",
        "--model-sha256", MODEL_SHA,
        "--runtime-revision", RUNTIME_REV,
        "--pid", pid,
        "--out", str(out_file),
    ] + (extra_flags or [])
    print(f"\n{'='*60}\nRunning {mode} benchmark → {out_file.name}\n{'='*60}\n", flush=True)
    with bench_log.open("w") as log:
        subprocess.run(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)


def summary(path, label):
    if not path.exists():
        print(f"  {label}: FILE NOT FOUND")
        return
    d = json.loads(path.read_text())
    c, t = d.get("correct", 0), d.get("total", 0)
    mean = d.get("meanLatencyMs", 0) / 1000
    print(f"  {label}: {c}/{t} ({100*c/t:.1f}%) mean={mean:.1f}s" if t else f"  {label}: no results")


# ── ensure port forwarding ───────────────────────────────────────────────────
fwd = subprocess.check_output([ADB, "-s", SERIAL, "forward", "--list"], text=True)
if f"tcp:{PORT}" not in fwd:
    subprocess.check_call([ADB, "-s", SERIAL, "forward", f"tcp:{PORT}", f"tcp:{PORT}"])

# ── verify model SHA ─────────────────────────────────────────────────────────
live_sha = adb("shell", f"sha256sum {MODEL_PATH}").split()[0]
assert live_sha == MODEL_SHA, f"SHA mismatch: {live_sha}"
print(f"Model SHA verified: {live_sha[:16]}…")

# ── run 1: thinking-ON (fixed harness) ──────────────────────────────────────
proc, pid = start_server(OUT / "thinking-on-v2-server.log")
try:
    run_bench(pid, OUT / "thinking-on-v2-results.json", "chat",
              OUT / "thinking-on-v2-bench.log", ["--enable-thinking"])
finally:
    stop_server(proc)

time.sleep(5)

# ── run 2: raw completion (no chat template) ─────────────────────────────────
proc, pid = start_server(OUT / "raw-server.log")
try:
    run_bench(pid, OUT / "raw-results.json", "raw",
              OUT / "raw-bench.log")
finally:
    stop_server(proc)

# ── final summary ────────────────────────────────────────────────────────────
print("\n===== THREE-WAY COMPARISON =====", flush=True)
summary(OUT / "thinking-on-v2-results.json",  "thinking-ON  (chat, no grammar, 1024 tokens)")
summary(OUT / "thinking-off-results.json",     "thinking-OFF (chat, grammar, 64 tokens)     ")
summary(OUT / "raw-results.json",              "raw          (no chat template, grammar)     ")
print("JNI baseline Sep-11:             41/50 (82%) mean=53.0s")
print()
print("If raw ≈ JNI: the chat template is the problem, not thinking.")
print("If thinking-ON ≈ JNI: disabling thinking was the problem.")
print("If both are low: the prompt format or runtime version is the issue.")
