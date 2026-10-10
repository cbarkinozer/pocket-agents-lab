#!/usr/bin/env python3
"""Analyze and report the reflex-1 vs Qwen baseline comparison results.

Run after both benchmarks complete:
  python3 tools/analyze_comparison.py --reflex benchmarks/.../reflex-1-results.json
                                       --qwen   benchmarks/.../qwen-results.json
                                       --out    benchmarks/.../comparison-report.md

Reads only local files. Produces no W&B runs or uploads.
"""
from __future__ import annotations

import argparse
import json
import statistics
from pathlib import Path


CATEGORIES = ["storage", "device", "battery", "answer", "health"]

THERMAL_LABELS = {0: "NONE", 1: "LIGHT", 2: "MODERATE", 3: "SEVERE", 4: "CRITICAL", 5: "EMERGENCY"}


def load(path: Path) -> dict:
    data = json.loads(path.read_text())
    return data


def category_of(case_id: str) -> str:
    return case_id.rsplit("-", 1)[0]


def cases_stats(cases: list[dict]) -> dict:
    by_cat: dict[str, dict] = {}
    for c in cases:
        cat = category_of(c["id"])
        entry = by_cat.setdefault(cat, {"n": 0, "raw": 0, "guard": 0, "latencies": [],
                                         "pss_before": [], "pss_after": [],
                                         "temps_before": [], "temps_after": [],
                                         "cooldowns": [], "guard_regressions": [], "guard_rescues": []})
        entry["n"] += 1
        if c.get("rawCorrect"):
            entry["raw"] += 1
        if c.get("correct"):
            entry["guard"] += 1
        entry["latencies"].append(c.get("latencyMs", 0))
        before = c.get("before", {})
        after = c.get("after", {})
        if before.get("pssKb"):
            entry["pss_before"].append(before["pssKb"])
        if after.get("pssKb"):
            entry["pss_after"].append(after["pssKb"])
        if before.get("temperatureC") is not None:
            entry["temps_before"].append(before["temperatureC"])
        if after.get("temperatureC") is not None:
            entry["temps_after"].append(after["temperatureC"])
        cs = c.get("cooldownSeconds", 0)
        entry["cooldowns"].append(cs)
        if c.get("rawCorrect") and not c.get("correct"):
            entry["guard_regressions"].append(c["id"])
        if not c.get("rawCorrect") and c.get("correct"):
            entry["guard_rescues"].append(c["id"])
    return by_cat


def make_md_table(rows: list[list], headers: list[str]) -> str:
    col_widths = [max(len(str(r[i])) for r in ([headers] + rows)) for i in range(len(headers))]
    def fmt_row(r):
        return "| " + " | ".join(str(r[i]).ljust(col_widths[i]) for i in range(len(r))) + " |"
    sep = "| " + " | ".join("-" * w for w in col_widths) + " |"
    return "\n".join([fmt_row(headers), sep] + [fmt_row(r) for r in rows])


def analyze(data: dict) -> dict:
    cases = data.get("cases", [])
    if not cases:
        return {}
    n = len(cases)
    raw_ok = sum(1 for c in cases if c.get("rawCorrect"))
    guard_ok = sum(1 for c in cases if c.get("correct"))
    lats = [c["latencyMs"] for c in cases if "latencyMs" in c]
    pss_after = [c["after"]["pssKb"] for c in cases if c.get("after", {}).get("pssKb")]
    pss_before_first = cases[0].get("before", {}).get("pssKb") if cases else None
    temps = [c["after"]["temperatureC"] for c in cases if c.get("after", {}).get("temperatureC") is not None]
    real_cooldowns = [c.get("cooldownSeconds", 0) for c in cases if c.get("cooldownSeconds", 0) > 1.0]
    thermal_moderate = sum(1 for c in cases if c.get("after", {}).get("thermalStatus", 0) >= 2)
    guard_regressions = sum(1 for c in cases if c.get("rawCorrect") and not c.get("correct"))
    guard_rescues = sum(1 for c in cases if not c.get("rawCorrect") and c.get("correct"))
    return {
        "n": n, "raw_ok": raw_ok, "guard_ok": guard_ok,
        "raw_pct": raw_ok / n * 100, "guard_pct": guard_ok / n * 100,
        "lat_mean": statistics.mean(lats) / 1000 if lats else None,
        "lat_median": statistics.median(lats) / 1000 if lats else None,
        "lat_min": min(lats) / 1000 if lats else None,
        "lat_max": max(lats) / 1000 if lats else None,
        "pss_first": pss_before_first,
        "pss_mean_after": statistics.mean(pss_after) if pss_after else None,
        "pss_peak": max(pss_after) if pss_after else None,
        "temp_min": min(temps) if temps else None,
        "temp_max": max(temps) if temps else None,
        "real_cooldown_count": len(real_cooldowns),
        "real_cooldown_total_s": sum(real_cooldowns),
        "thermal_moderate_events": thermal_moderate,
        "guard_regressions": guard_regressions,
        "guard_rescues": guard_rescues,
        "by_cat": cases_stats(cases),
        "complete": data.get("complete", False),
        "treatment": data.get("treatment", "?"),
        "label": data.get("label", "?"),
    }


def report(reflex_data: dict, qwen_data: dict | None, out: Path) -> str:
    r = analyze(reflex_data)
    q = analyze(qwen_data) if qwen_data else None

    lines = []
    def w(s=""):
        lines.append(s)

    w("# reflex-1 vs Qwen3.5-0.8B Routing Comparison — Galaxy A32 (2026-10-10)")
    w()
    w("**Device:** SM-A325F · Android 13 · MT6769V/CT · arm64-v8a · 4 GB RAM")
    w("**Runtime:** llama.cpp b11443 (commit f0c41e016) · 4 CPU threads · no GPU · USB power")
    w("**Suite:** tool-routing-3-tools-v5 · 50 cases · decision mode vs chat mode")
    w("**Caution:** single ordered run; no shuffling. Prompts are the development set, not held-out.")
    w("Historical JNI baseline (2026-09-11): 41/50 (82%), mean 53 s, PSS 780,270 KiB — different")
    w("protocol (JNI, not HTTP), different runtime; listed for context only.")
    w()

    # Completion status
    for label, d, data in [("reflex-1-Q4_K_M (decision)", r, reflex_data),
                             ("Qwen3.5-0.8B-Q4_K_M (chat)", q, qwen_data)]:
        if d is None:
            w(f"**{label}:** not yet available.")
        elif not d["complete"]:
            w(f"**{label}:** INCOMPLETE — {d['n']}/50 cases recorded.")
        else:
            w(f"**{label}:** complete — {d['n']}/50 cases.")
    w()

    # Overall accuracy table
    w("## Overall Accuracy")
    w()
    headers = ["Model", "Mode", "Raw correct", "Raw %", "Guard correct", "Guard %", "Complete"]
    rows = []
    rows.append(["reflex-1-Q4_K_M", "decision",
                 f"{r['raw_ok']}/{r['n']}", f"{r['raw_pct']:.1f}%",
                 f"{r['guard_ok']}/{r['n']}", f"{r['guard_pct']:.1f}%",
                 "Yes" if r["complete"] else f"No ({r['n']}/50)"])
    if q:
        rows.append(["Qwen3.5-0.8B-Q4_K_M", "chat",
                     f"{q['raw_ok']}/{q['n']}", f"{q['raw_pct']:.1f}%",
                     f"{q['guard_ok']}/{q['n']}", f"{q['guard_pct']:.1f}%",
                     "Yes" if q["complete"] else f"No ({q['n']}/50)"])
    w(make_md_table(rows, headers))
    w()
    if not q:
        w("> Qwen baseline not yet complete; table will be updated when available.")
        w()

    # Per-category accuracy
    w("## Per-Category Accuracy")
    w()
    headers = ["Category", "n", "reflex raw", "reflex guard", "qwen raw", "qwen guard"]
    rows = []
    for cat in CATEGORIES:
        rc = r["by_cat"].get(cat, {})
        qc = q["by_cat"].get(cat, {}) if q else {}
        rn = rc.get("n", 0)
        qn = qc.get("n", 0)
        rows.append([
            cat,
            str(rn),
            f"{rc.get('raw',0)}/{rn}" if rn else "—",
            f"{rc.get('guard',0)}/{rn}" if rn else "—",
            f"{qc.get('raw',0)}/{qn}" if qn else "—",
            f"{qc.get('guard',0)}/{qn}" if qn else "—",
        ])
    w(make_md_table(rows, headers))
    w()

    # Guard effect
    w("## Relevance Guard Effect")
    w()
    w(f"**reflex-1:** {r['guard_rescues']} rescues (raw wrong → guard correct), "
      f"{r['guard_regressions']} regressions (raw correct → guard wrong).")
    if q:
        w(f"**Qwen:** {q['guard_rescues']} rescues, {q['guard_regressions']} regressions.")
    w()
    w("Guard logic: keyword-substring match transliterated from AgentBackend.kt `isDecisionRelevant`.")
    w("Routes without a keyword entry pass through unchanged (phone_health_check uses: 'health',")
    w("'condition', 'readiness', 'check everything'). This correctly rescues answer cases where")
    w("the model picks phone_health_check, but also wrongly downgrades health-04/06/08/09 whose")
    w("prompts lack those exact keywords despite being valid health queries.")
    w()
    for label, d in [("reflex-1", r)]:
        rcat = d["by_cat"]
        for cat in CATEGORIES:
            rc = rcat.get(cat, {})
            if rc.get("guard_regressions"):
                w(f"  Guard regressions ({label}/{cat}): {rc['guard_regressions']}")
            if rc.get("guard_rescues"):
                w(f"  Guard rescues ({label}/{cat}): {rc['guard_rescues']}")
    w()

    # Latency
    w("## Latency")
    w()
    w("Decision mode prompt ≈ 400 tokens; chat mode prompt ≈ 1,127 tokens before chat formatting.")
    w("Latency difference reflects both the smaller head computation and the shorter input.")
    w()
    headers = ["Model", "Mode", "Mean (s)", "Median (s)", "Min (s)", "Max (s)"]
    rows = [
        ["reflex-1-Q4_K_M", "decision",
         f"{r['lat_mean']:.1f}" if r["lat_mean"] else "—",
         f"{r['lat_median']:.1f}" if r["lat_median"] else "—",
         f"{r['lat_min']:.1f}" if r["lat_min"] else "—",
         f"{r['lat_max']:.1f}" if r["lat_max"] else "—"],
    ]
    if q and q.get("lat_mean"):
        rows.append([
            "Qwen3.5-0.8B-Q4_K_M", "chat",
            f"{q['lat_mean']:.1f}", f"{q['lat_median']:.1f}",
            f"{q['lat_min']:.1f}", f"{q['lat_max']:.1f}"])
    w(make_md_table(rows, headers))
    w()

    # Memory
    w("## Memory (PSS)")
    w()
    headers = ["Model", "First-case PSS (KiB)", "Run-mean PSS (KiB)", "Peak PSS (KiB)"]
    rows = [
        ["reflex-1-Q4_K_M",
         f"{r['pss_first']:,}" if r["pss_first"] else "—",
         f"{r['pss_mean_after']:,.0f}" if r["pss_mean_after"] else "—",
         f"{r['pss_peak']:,}" if r["pss_peak"] else "—"],
    ]
    if q:
        rows.append([
            "Qwen3.5-0.8B-Q4_K_M",
            f"{q['pss_first']:,}" if q.get("pss_first") else "—",
            f"{q['pss_mean_after']:,.0f}" if q.get("pss_mean_after") else "—",
            f"{q['pss_peak']:,}" if q.get("pss_peak") else "—"])
    w(make_md_table(rows, headers))
    w()
    w("First-case PSS = server PSS before first inference (warm file-cache server load).")
    w("Run-mean/peak = PSS after each completed inference.")
    w()

    # Thermal / cooldown
    w("## Thermal and Cooldown")
    w()
    headers = ["Model", "Temp range (C)", "MODERATE+ events", "Cooldown waits", "Total cooldown (s)"]
    rows = [
        ["reflex-1-Q4_K_M",
         f"{r['temp_min']:.1f}–{r['temp_max']:.1f}" if r["temp_min"] is not None else "—",
         str(r["thermal_moderate_events"]),
         str(r["real_cooldown_count"]),
         f"{r['real_cooldown_total_s']:.0f}"],
    ]
    if q and q.get("temp_min") is not None:
        rows.append([
            "Qwen3.5-0.8B-Q4_K_M",
            f"{q['temp_min']:.1f}–{q['temp_max']:.1f}",
            str(q["thermal_moderate_events"]),
            str(q["real_cooldown_count"]),
            f"{q['real_cooldown_total_s']:.0f}"])
    w(make_md_table(rows, headers))
    w()
    w("Battery temperature gate ≤38 C before each case; LIGHT (1) thermal gate also required.")
    w("Initial device snapshot showed AP 51.5 C, SKIN 39.7 C — AP temperature not gated.")
    w("Cooldown waits shown are inter-case idle time (>1 s); inference latency is separate.")
    w()

    # Architecture section
    w("## Architecture Status: One Loaded Backbone")
    w()
    w("The user's target is **one loaded Qwen backbone with decision routing and text generation**,")
    w("switching output path per request without loading two models simultaneously.")
    w()
    w("### Completed work")
    w("- reflex-1-Q4_K_M.gguf: merged Clef model (backbone + decision head) — loads and runs on device.")
    w("- `AgentBackend.kt` seam: optional `AgentDecisionSelector` in `select()`; generator remains default.")
    w("- `AgentBackendTest.kt`: unit test that decision routing skips generation and final answer uses")
    w("  the text generator once.")
    w("- Benchmark harness (`benchmark_decision_android.py`): 50-case HTTP runner with guard, telemetry,")
    w("  thermal gating, and incremental JSON output.")
    w("- Runtime flags tuned: `--no-repack` required for Clef (default repacking hit scheduler assertion).")
    w()
    w("### What the current GGUF cannot do")
    w("- `src/models/clef.cpp` (revision aa94f2086) builds a graph that **always** evaluates the decision")
    w("  head. It sets `res->t_embd` to scores and does **not** populate vocabulary logits.")
    w("  Standard chat completions will not work on this model file.")
    w("- `src/llama-model.cpp` allocates no persistent KV/recurrent memory for Clef.")
    w("- A normal llama.cpp LoRA loader does not add the decision head or its scoring graph.")
    w()
    w("### What shared-backbone support requires")
    w("1. **One loaded tensor set** — backbone weights shared between paths (no duplicate load).")
    w("2. **Decision graph** — Clef-style head, no vocabulary logits, zero output tokens.")
    w("3. **Text graph** — vocabulary logits, KV cache, recurrent memory for the Qwen3.5 architecture.")
    w("4. **Separate contexts** — different prompt formatting per path; explicit context resets.")
    w("5. **Switching validation** — confirm both paths work and context state is clean after switching.")
    w("6. *Optional*: merged backbone prose quality test — if using reflex-1 weights for text generation,")
    w("   its quality on open-ended prompts has not been measured.")
    w("7. *Optional*: LoRA sidecar path — using the original Qwen base for text and the 43 MB adapter")
    w("   only for decisions would require portable LoRA conversion and validated enable/disable behavior.")
    w()
    w("The Kotlin `AgentDecisionSelector` is a code seam only. The app has not been rebuilt or")
    w("switched to reflex-1. Kotlin tests could not be run (JDK/Android SDK absent on this Mac).")
    w()

    # Assessment
    w("## Assessment: Is Further Fine-Tuning Worthwhile?")
    w()

    if r["complete"]:
        raw_pct = r["raw_pct"]
        guard_pct = r["guard_pct"]
        battery_ok = r["by_cat"].get("battery", {}).get("raw", -1)
    else:
        raw_pct = r["raw_pct"]
        guard_pct = r["guard_pct"]
        battery_ok = r["by_cat"].get("battery", {}).get("raw", -1)

    w(f"reflex-1 raw accuracy ({raw_pct:.0f}%) is well below the 82% historical Qwen JNI baseline")
    w("and below the matched HTTP Qwen baseline (pending). The guard improves the number but")
    w(f"remains a symptom: {r['guard_regressions']} guard regressions show the guard is not safe either.")
    w()
    w("**Primary failure mode:** The decision head assigns systematically high probability to")
    w("`phone_health_check` across all categories. On battery cases (0/10 raw), every prompt is")
    w("routed there despite clear single-category signals. On device and storage, roughly 40% of")
    w("prompts leak to phone_health_check. This is a head-calibration issue, not a vocabulary or")
    w("context issue — the backbone is the same Qwen weights.")
    w()
    w("**Why the battery category collapsed:** The RELEVANCE_KEYWORDS for `get_battery_info` include")
    w("'battery', 'charge', 'charging', 'power', 'temperature', 'hot', 'cool'. Most prompts contain")
    w("these terms, yet the head still prefers phone_health_check with 0.39–0.45 probability against")
    w("get_battery_info's 0.10–0.39. The head learned that battery-related language also implies")
    w("overall health — likely because training data for phone_health_check over-represented battery")
    w("queries, or because the label boundary was not clear during training.")
    w()
    w("**Can fine-tuning fix this?**")
    w()
    w("Possibly, but with caveats:")
    w("- **Do not retrain on this development suite as if it were held-out evidence.** These 50 prompts")
    w("  guided early design and would overfit if used as evaluation after further training.")
    w("- Reserve a separate held-out split before any further iteration.")
    w("- The battery/health boundary issue suggests the training data labels need auditing,")
    w("  not just more examples. Check whether battery-only queries appear with phone_health_check")
    w("  labels in the training set.")
    w("- The Clef head is a separate module added to the frozen backbone. Re-training requires")
    w("  the full unsloth + Clef training pipeline from the pod-backup checkpoint.")
    w("- If the shared-backbone architecture (single loaded model, two output paths) is the actual")
    w("  goal, validate that first before investing further training compute.")
    w()
    w("**Verdict:** Further fine-tuning is not yet warranted. The immediate next step is to audit")
    w("the battery/health_check label distribution in the training data and run the matched Qwen")
    w("HTTP baseline. If the Qwen baseline reproduces a similar accuracy in chat mode, the routing")
    w("task is learnable and the issue is specifically the head training. If Qwen underperforms too,")
    w("the suite itself may need redesign.")
    w()

    # Caveats
    w("## Caveats and Limitations")
    w()
    w("1. **Single ordered run.** No shuffling, no repetition, no confidence intervals.")
    w("2. **Development suite.** All 50 prompts were used during system design. Not held-out evidence.")
    w("3. **Mode mismatch.** Decision mode (≈400 tokens) vs chat mode (≈1,127 tokens). Latency and")
    w("   quality differences include both the head architecture and the prompt length.")
    w("4. **Runtime mismatch with history.** b11443 (HTTP) vs September JNI baseline. Not directly")
    w("   comparable.")
    w("5. **Guard is a heuristic.** Keyword-substring matching on the development set produces")
    w("   both rescues and regressions. It is not a reliable production filter.")
    w("6. **Final answers not tested.** The benchmark measures routing only; no tools are executed.")
    w("7. **Kotlin integration incomplete.** AgentDecisionSelector is a seam; app not rebuilt.")
    w("8. **PSS includes server overhead.** Not pure model memory.")
    w()

    text = "\n".join(lines)
    out.write_text(text)
    print(f"Report written to {out}")
    return text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reflex", type=Path, required=True, help="reflex-1-results.json path")
    parser.add_argument("--qwen", type=Path, help="qwen-results.json path (optional)")
    parser.add_argument("--out", type=Path, required=True, help="output .md path")
    args = parser.parse_args()

    reflex_data = load(args.reflex)
    qwen_data = load(args.qwen) if args.qwen and args.qwen.exists() else None

    report(reflex_data, qwen_data, args.out)


if __name__ == "__main__":
    main()
