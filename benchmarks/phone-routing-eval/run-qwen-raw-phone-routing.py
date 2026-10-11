"""Run Qwen3.5-0.8B-Q4_K_M in raw mode against the 90-case phone routing test set.

This is the matched comparison baseline. The result from this run is the score
that a retrained reflex-1 must beat on the same test set to claim a win.

Raw mode: /v1/completions, no chat template, GBNF grammar — same configuration
that produced 33/50 (66%) on the 50-case dev suite.

Do NOT run this after looking at test.jsonl labels for any training decision.
Run it once before training and once (if needed) after to confirm parity.

Usage:
  python3 benchmarks/phone-routing-eval/run-qwen-raw-phone-routing.py
"""
import json, pathlib, subprocess, sys, time, urllib.request, re, statistics
from collections import defaultdict

ROOT   = pathlib.Path("/Users/cbarkinozer/Documents/GitHub/pocket-agents-lab")
OUT_DIR = ROOT / "benchmarks/phone-routing-eval"
ADB    = "/Users/cbarkinozer/.cache/reflex-android/platform-tools/adb"
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

sys.path.insert(0, str(ROOT / "tools"))
from replay_agent_pilot import ROUTING_PROMPT_TEMPLATE, CLARIFICATION_MESSAGE, AGENT_ROUTE_GRAMMAR


def adb(*args):
    return subprocess.check_output([ADB, "-s", SERIAL] + list(args), text=True, timeout=60)


def telemetry():
    battery = adb("shell", "dumpsys battery")
    thermal = adb("shell", "dumpsys thermalservice")
    temp = re.search(r"temperature:\s*(\d+)", battery)
    status = re.search(r"(?:Thermal Status:|mStatus[=:])\s*(\d+)", thermal)
    return {
        "temperatureC": int(temp.group(1)) / 10 if temp else None,
        "thermalStatus": int(status.group(1)) if status else None,
        "usbPowered": "USB powered: true" in battery,
    }


def start_server(log_path):
    print("Starting Qwen server…", flush=True)
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
                    return proc
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


def request(path, body):
    req = urllib.request.Request(
        f"http://127.0.0.1:{PORT}{path}",
        json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as r:
        return json.load(r)


def save(path, report):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(report, indent=2) + "\n")
    tmp.replace(path)


# ── setup ─────────────────────────────────────────────────────────────────────
fwd = subprocess.check_output([ADB, "-s", SERIAL, "forward", "--list"], text=True)
if f"tcp:{PORT}" not in fwd:
    subprocess.check_call([ADB, "-s", SERIAL, "forward", f"tcp:{PORT}", f"tcp:{PORT}"])

print("Verifying model SHA…", flush=True)
live_sha = adb("shell", f"sha256sum {MODEL_PATH}").split()[0]
assert live_sha == MODEL_SHA, f"SHA mismatch: {live_sha}"
print(f"SHA verified: {live_sha[:16]}…", flush=True)

# ── load test cases ───────────────────────────────────────────────────────────
test_path = ROOT / "data/phone_routing/test.jsonl"
cases = [json.loads(l) for l in test_path.read_text().splitlines() if l.strip()]
print(f"Loaded {len(cases)} test cases from {test_path.name}", flush=True)

out_file = OUT_DIR / "qwen-raw-phone-routing-results.json"
report = {
    "dataset": "phone_routing_v1",
    "split": "test",
    "model": "Qwen3.5-0.8B-Q4_K_M",
    "treatment": "raw",
    "mode_note": "no chat template, GBNF grammar — matched comparison baseline for retrained reflex-1",
    "modelSha256": MODEL_SHA,
    "runtimeRevision": RUNTIME_REV,
    "test_sha256": __import__("hashlib").sha256(test_path.read_bytes()).hexdigest(),
    "total": len(cases),
    "complete": False,
    "cases": [],
}
save(out_file, report)

# ── ROUTE_MAP: raw grammar output → phone_routing label ──────────────────────
# The grammar produces {"action":"tool","name":"X"} or {"action":"answer",...}
# or {"action":"workflow","name":"X"}. Map to the 9-route label space.
def parse_route(raw_text: str) -> str:
    d = json.loads(raw_text)
    if d["action"] == "answer":
        return "answer"
    return d.get("name", "answer")


# ── run ───────────────────────────────────────────────────────────────────────
proc = start_server(OUT_DIR / "qwen-raw-phone-routing-server.log")
correct_by_route: defaultdict[str, list[bool]] = defaultdict(list)

try:
    for case in cases:
        state = case["state"]
        gold  = case["route__label"]

        # thermal gate
        cooldown_start = time.monotonic()
        while True:
            t = telemetry()
            if t["temperatureC"] is not None and t["thermalStatus"] is not None:
                if t["temperatureC"] <= 38 and t["thermalStatus"] <= 1:
                    break
            print(f"  Cooling: {t}", flush=True)
            if time.monotonic() - cooldown_start > 1800:
                raise RuntimeError("Thermal cooldown exceeded 30 minutes")
            time.sleep(10)

        row = {"id": case["id"], "state": state, "gold": gold,
               "before": t, "cooldownSeconds": time.monotonic() - cooldown_start}
        start = time.monotonic()
        try:
            prompt = ROUTING_PROMPT_TEMPLATE.format(
                user_prompt=state, clarification=CLARIFICATION_MESSAGE)
            resp = request("/v1/completions", {
                "prompt": prompt,
                "temperature": 0.3, "seed": 3407, "max_tokens": 64,
                "grammar": AGENT_ROUTE_GRAMMAR,
                "cache_prompt": False,
            })
            chosen = parse_route(resp["choices"][0]["text"].strip())
            row.update(chosen=chosen, correct=(chosen == gold))
            correct_by_route[gold].append(chosen == gold)
        except Exception as e:
            row.update(correct=False, error=str(e))
            correct_by_route[gold].append(False)

        row["latencyMs"] = (time.monotonic() - start) * 1000
        row["after"] = telemetry()
        report["cases"].append(row)

        n = len(report["cases"])
        n_correct = sum(r.get("correct", False) for r in report["cases"])
        print(f"[{n:2d}/90] {case['id']:30s} gold={gold:20s} "
              f"chosen={row.get('chosen','ERR'):20s} "
              f"acc={n_correct}/{n} lat={row['latencyMs']/1000:.1f}s",
              flush=True)
        save(out_file, report)

finally:
    stop_server(proc)

# ── final report ──────────────────────────────────────────────────────────────
total = len(report["cases"])
total_correct = sum(r.get("correct", False) for r in report["cases"])
lats = [r["latencyMs"] for r in report["cases"] if "latencyMs" in r]
report.update(
    complete=True,
    correct=total_correct,
    accuracy=total_correct / total if total else 0.0,
    meanLatencyMs=statistics.mean(lats) if lats else 0,
    perRoute={
        route: {"correct": sum(v), "total": len(v), "accuracy": sum(v) / len(v)}
        for route, v in sorted(correct_by_route.items())
    },
)
save(out_file, report)

print(f"\n{'='*60}")
print(f"Qwen raw — phone_routing_v1 test (90 held-out cases)")
print(f"Overall: {total_correct}/{total} ({100*total_correct/total:.1f}%)")
print(f"Mean latency: {report['meanLatencyMs']/1000:.1f}s")
print(f"\nPer route:")
for route, s in report["perRoute"].items():
    pct = 100 * s["accuracy"]
    bar = "█" * s["correct"] + "░" * (s["total"] - s["correct"])
    print(f"  {route:25s}: {s['correct']}/{s['total']} ({pct:.0f}%)  {bar}")
print(f"\nThis is the matched comparison baseline.")
print(f"Retrained reflex-1 must beat this score on the same 90 cases.")
print(f"Results → {out_file}")
