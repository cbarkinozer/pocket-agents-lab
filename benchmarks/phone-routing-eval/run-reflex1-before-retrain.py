"""Evaluate reflex-1 (before retraining) on the phone routing held-out test set.

Start the Clef server, run eval_phone_routing.py against test.jsonl, stop server.
This establishes the before-retrain baseline for comparison after training.

Do NOT run this more than once per model checkpoint — the test set is held-out.
Run after each retrain checkpoint to measure improvement.

Usage:
  python3 benchmarks/phone-routing-eval/run-reflex1-before-retrain.py
"""
import json, pathlib, subprocess, sys, time, urllib.request

ROOT      = pathlib.Path("/Users/cbarkinozer/Documents/GitHub/pocket-agents-lab")
OUT_DIR   = ROOT / "benchmarks/phone-routing-eval"
ADB       = "/Users/cbarkinozer/.cache/reflex-android/platform-tools/adb"
SERIAL    = "R68T800LZ0H"
MODEL_PATH = "/sdcard/Download/models/reflex-1-Q4_K_M.gguf"
PORT      = 8091

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
    print(f"Starting Clef server…", flush=True)
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
    raise RuntimeError("Server timeout after 180s")


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


OUT_DIR.mkdir(parents=True, exist_ok=True)

# Forward port
fwd = subprocess.check_output([ADB, "-s", SERIAL, "forward", "--list"], text=True)
if f"tcp:{PORT}" not in fwd:
    subprocess.check_call([ADB, "-s", SERIAL, "forward", f"tcp:{PORT}", f"tcp:{PORT}"])

proc, pid = start_server(OUT_DIR / "reflex1-before-retrain-server.log")
try:
    out_file = OUT_DIR / "reflex1-before-retrain-results.json"
    cmd = [
        sys.executable, str(ROOT / "tools/eval_phone_routing.py"),
        "--adb",         ADB,
        "--serial",      SERIAL,
        "--data-dir",    str(ROOT / "data/phone_routing"),
        "--split",       "test",
        "--model-label", "reflex-1-Q4_K_M",
        "--pid",         pid,
        "--out",         str(out_file),
    ]
    print(f"\nRunning phone routing eval on held-out test set…\n", flush=True)
    subprocess.run(cmd, cwd=ROOT, check=True)
finally:
    stop_server(proc)

# Print summary
d = json.loads(out_file.read_text())
total = d.get("total", 0)
correct = d.get("correct", 0)
mean_lat = d.get("meanLatencyMs", 0) / 1000
print(f"\n{'='*60}")
print(f"reflex-1-Q4_K_M on phone_routing_v1 test split (before retrain)")
print(f"Overall: {correct}/{total} ({100*correct/total:.1f}%)")
print(f"Mean latency: {mean_lat:.1f}s")
print(f"\nPer route:")
for route, stats in d.get("perRoute", {}).items():
    pct = 100 * stats["accuracy"]
    print(f"  {route:25s}: {stats['correct']}/{stats['total']} ({pct:.0f}%)")
print(f"\nResults → {out_file}")
