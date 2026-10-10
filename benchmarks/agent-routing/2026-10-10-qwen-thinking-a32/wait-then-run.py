"""Wait for thinking-off to complete, then run thinking-on-v2 and raw back-to-back.

Usage:
  python3 benchmarks/agent-routing/2026-10-10-qwen-thinking-a32/wait-then-run.py
"""
import json, pathlib, subprocess, sys, time

ROOT   = pathlib.Path("/Users/cbarkinozer/Documents/GitHub/pocket-agents-lab")
OUT    = ROOT / "benchmarks/agent-routing/2026-10-10-qwen-thinking-a32"
RESULTS = OUT / "thinking-off-results.json"
NEXT   = OUT / "run-thinking-on.py"

print("Waiting for thinking-off to complete…", flush=True)
start = time.monotonic()
while True:
    try:
        d = json.loads(RESULTS.read_text())
        if d.get("complete"):
            total = d.get("total", 0)
            raw   = d.get("rawCorrect", sum(c.get("rawCorrect",0) for c in d["cases"]))
            mean  = d.get("meanLatencyMs", 0) / 1000
            print(f"\nthinking-OFF done: {raw}/{total} ({100*raw/total:.1f}%) mean={mean:.1f}s", flush=True)
            break
    except Exception:
        pass
    elapsed = time.monotonic() - start
    if elapsed > 7200:  # 2-hour safety
        print("ERROR: thinking-off did not complete within 2 hours", flush=True)
        sys.exit(1)
    cases_done = len(d.get("cases", []))
    print(f"\r  {cases_done}/50 cases…  ", end="", flush=True)
    time.sleep(30)

print("\nLaunching run-thinking-on.py…", flush=True)
subprocess.run([sys.executable, str(NEXT)], cwd=ROOT, check=True)
print("\nAll three treatments complete.", flush=True)
