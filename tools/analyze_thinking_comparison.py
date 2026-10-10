#!/usr/bin/env python3
"""Analyze Qwen thinking-ON vs thinking-OFF comparison results.

Reads thinking-on-results.json and thinking-off-results.json from the
2026-10-10-qwen-thinking-a32 benchmark directory and produces a markdown
report explaining the accuracy gap with the September JNI baseline.

Usage:
  python3 tools/analyze_thinking_comparison.py \\
      --thinking-on  benchmarks/agent-routing/2026-10-10-qwen-thinking-a32/thinking-on-results.json \\
      --thinking-off benchmarks/agent-routing/2026-10-10-qwen-thinking-a32/thinking-off-results.json \\
      --jni-accuracy 0.82 --jni-latency-s 53.0 \\
      --out benchmarks/agent-routing/2026-10-10-qwen-thinking-a32/thinking-comparison-report.md
"""
from __future__ import annotations

import argparse
import json
import statistics
from pathlib import Path


def load(path: Path) -> dict:
    return json.loads(path.read_text())


def acc(cases: list[dict], key: str) -> tuple[int, int]:
    correct = sum(1 for c in cases if c.get(key))
    return correct, len(cases)


def per_category(cases: list[dict], key: str) -> dict[str, tuple[int, int]]:
    cats: dict[str, list] = {}
    for c in cases:
        cat = c["id"].rsplit("-", 1)[0]
        cats.setdefault(cat, []).append(c)
    return {cat: acc(cs, key) for cat, cs in sorted(cats.items())}


def mean_latency(cases: list[dict]) -> float:
    vals = [c["latencyMs"] / 1000 for c in cases if "latencyMs" in c]
    return statistics.mean(vals) if vals else float("nan")


def make_table(headers: list[str], rows: list[list]) -> str:
    def fmt(v) -> str:
        if isinstance(v, float):
            return f"{v:.1f}"
        return str(v)
    col_widths = [max(len(h), max((len(fmt(r[i])) for r in rows), default=0))
                  for i, h in enumerate(headers)]
    sep = "| " + " | ".join("-" * w for w in col_widths) + " |"
    header = "| " + " | ".join(h.ljust(col_widths[i]) for i, h in enumerate(headers)) + " |"
    body = "\n".join(
        "| " + " | ".join(fmt(v).ljust(col_widths[i]) for i, v in enumerate(row)) + " |"
        for row in rows
    )
    return "\n".join([header, sep, body])


def report(on_data: dict, off_data: dict, jni_acc: float, jni_lat: float) -> str:
    on_cases  = on_data.get("cases", [])
    off_cases = off_data.get("cases", [])

    on_correct,  on_total  = acc(on_cases,  "rawCorrect")
    off_correct, off_total = acc(off_cases, "rawCorrect")
    on_guard,  _ = acc(on_cases,  "correct")
    off_guard, _ = acc(off_cases, "correct")
    on_lat  = mean_latency(on_cases)
    off_lat = mean_latency(off_cases)

    on_pct  = 100 * on_correct  / on_total  if on_total  else 0.0
    off_pct = 100 * off_correct / off_total if off_total else 0.0

    on_cats  = per_category(on_cases,  "rawCorrect")
    off_cats = per_category(off_cases, "rawCorrect")

    # error classification
    on_errors  = [(c["id"], c.get("error", "")) for c in on_cases  if not c.get("rawCorrect")]
    off_errors = [(c["id"], c.get("error", "")) for c in off_cases if not c.get("rawCorrect")]
    on_parse_fails  = sum(1 for _, e in on_errors  if "JSON" in e or "Expecting" in e or "Unexpected" in e)
    off_parse_fails = sum(1 for _, e in off_errors if "JSON" in e or "Expecting" in e or "Unexpected" in e)

    lines: list[str] = []
    lines.append("# Qwen3.5-0.8B Thinking ON vs OFF — Galaxy A32 (2026-10-11)")
    lines.append("")
    lines.append(f"**Device:** SM-A325F · Android 13 · arm64-v8a  ")
    lines.append(f"**Runtime:** llama.cpp b11443 (f0c41e016) · 4 threads · USB power  ")
    lines.append(f"**Suite:** tool-routing-3-tools-v5 · 50 cases  ")
    lines.append(f"**Purpose:** Determine whether `enable_thinking=False` explains the 20% result from")
    lines.append(f"the Oct-10 matched-chat run vs the 82% September JNI baseline.")
    lines.append("")
    lines.append("## Summary")
    lines.append("")
    lines.append(make_table(
        ["Treatment", "Raw correct", "Raw %", "Guard correct", "Guard %", "Mean lat (s)", "Parse fails"],
        [
            ["Thinking ON",  f"{on_correct}/{on_total}",  f"{on_pct:.1f}%",
             f"{on_guard}/{on_total}",  f"{100*on_guard/on_total:.1f}%",  f"{on_lat:.1f}",  str(on_parse_fails)],
            ["Thinking OFF", f"{off_correct}/{off_total}", f"{off_pct:.1f}%",
             f"{off_guard}/{off_total}", f"{100*off_guard/off_total:.1f}%", f"{off_lat:.1f}", str(off_parse_fails)],
        ]
    ))
    lines.append("")
    lines.append(f"**Reference baselines:**")
    lines.append(f"- Sep-11 JNI (thinking config unknown): 41/50 (82%), mean 53.0 s")
    lines.append(f"- Oct-10 chat OFF (this repo, prior run): 10/50 (20%), mean 64.8 s")
    lines.append("")

    # Per-category table
    all_cats = sorted(set(on_cats) | set(off_cats))
    lines.append("## Per-Category Accuracy")
    lines.append("")
    cat_rows = []
    for cat in all_cats:
        on_c,  on_n  = on_cats.get(cat,  (0, 0))
        off_c, off_n = off_cats.get(cat, (0, 0))
        cat_rows.append([cat, on_n, f"{on_c}/{on_n}", f"{off_c}/{off_n}"])
    lines.append(make_table(
        ["Category", "n", "Thinking ON raw", "Thinking OFF raw"],
        cat_rows
    ))
    lines.append("")

    # Gap analysis
    lines.append("## Gap Analysis")
    lines.append("")
    diff = on_pct - off_pct
    lines.append(f"Thinking ON: **{on_pct:.1f}%** vs Thinking OFF: **{off_pct:.1f}%**. Difference: {diff:+.1f} pp.")
    lines.append("")
    jni_diff = jni_acc * 100 - on_pct
    lines.append(f"Gap from JNI baseline ({jni_acc*100:.0f}%) to thinking-ON: {jni_diff:+.1f} pp.")
    lines.append("")

    if abs(on_pct - off_pct) < 5:
        lines.append("**Conclusion:** Thinking ON/OFF produces no meaningful accuracy difference (<5 pp).")
        lines.append("The `enable_thinking=False` setting does not explain the 20% result. The gap")
        lines.append("between HTTP routing (both treatments) and the September JNI baseline must have")
        lines.append("a different cause: likely prompt-format differences between JNI buildRoutingPrompt")
        lines.append("and the HTTP ROUTING_PROMPT_TEMPLATE, or the llama.cpp version change.")
    elif on_pct > off_pct + 5:
        lines.append(f"**Conclusion:** Thinking enabled recovers **{diff:.1f} pp** of accuracy.")
        lines.append("Disabling thinking was a major contributor to the 20% result.")
        if on_pct >= jni_acc * 100 - 10:
            lines.append(f"Thinking-ON is close to the JNI baseline ({jni_acc*100:.0f}%), suggesting")
            lines.append("the JNI run also had thinking enabled.")
        else:
            lines.append(f"Thinking-ON still falls {jni_diff:.1f} pp short of the JNI baseline.")
            lines.append("Other factors (prompt format, runtime version) also contribute.")
    else:
        lines.append("**Conclusion:** Thinking OFF outperforms thinking ON unexpectedly. This may")
        lines.append("indicate thinking generates poor routing decisions for this task, or a")
        lines.append("grammar/parse compatibility issue with the thinking-ON path.")

    lines.append("")
    lines.append("## Parse Error Analysis")
    lines.append("")
    lines.append(f"Thinking ON parse failures: {on_parse_fails}/{on_total}")
    lines.append(f"Thinking OFF parse failures: {off_parse_fails}/{off_total}")
    lines.append("")
    if on_parse_fails > 0:
        lines.append("Parse errors in thinking-ON indicate the JSON extraction from the")
        lines.append("thinking+response stream failed for some cases. Check thinking-on-results.json")
        lines.append("for the raw response content to diagnose the format.")
    lines.append("")
    lines.append("## JNI vs HTTP Architecture Note")
    lines.append("")
    lines.append("The September JNI baseline used **raw text completion** (no chat template):")
    lines.append("")
    lines.append("```kotlin")
    lines.append("// AgentBackend.kt line 108")
    lines.append("generator.generate(")
    lines.append("    GRAMMAR_ROUTE_PREFIX + buildRoutingPrompt(userPrompt),")
    lines.append("    AGENT_DECISION_TOKENS,")
    lines.append(")")
    lines.append("```")
    lines.append("")
    lines.append("The HTTP runs use `/v1/chat/completions` which applies the Qwen3.5 chat template")
    lines.append("(adding `<|im_start|>system/user/assistant` markers). The JNI path calls the")
    lines.append("llama.cpp completion API directly, bypassing the chat template entirely.")
    lines.append("")
    lines.append("A third treatment — `raw` mode using `/v1/completions` (no template) — directly")
    lines.append("tests this hypothesis. See `run-thinking-on.py` which runs thinking-ON then raw.")
    lines.append("")
    lines.append("**Prediction:** if raw mode reproduces the 82% JNI accuracy, the chat template")
    lines.append("is the root cause, and neither thinking-ON nor thinking-OFF will close the gap.")
    lines.append("")
    lines.append("## Next Steps")
    lines.append("")
    lines.append("1. Check `raw-results.json` from run-thinking-on.py for the raw-mode accuracy.")
    lines.append("2. If raw ≈ 82%: the comparison baseline for reflex-1 is raw mode, not chat mode.")
    lines.append("3. If raw is also ~20%: investigate llama.cpp version difference (2a15f3a72 vs f0c41e016).")
    lines.append("4. Proceed with phone-specific training using `data/phone_routing/train.jsonl`.")
    lines.append("5. Eval target: beat raw-mode accuracy at ≤30s latency (reflex-1 current: 25s mean).")

    return "\n".join(lines) + "\n"


def raw_summary(raw_data: dict) -> str:
    cases = raw_data.get("cases", [])
    if not cases:
        return "raw-mode: no data"
    correct = sum(1 for c in cases if c.get("rawCorrect"))
    total   = len(cases)
    mean    = mean_latency(cases)
    cats    = per_category(cases, "rawCorrect")
    lines   = [f"\n## Raw-Mode Results (no chat template — replicates JNI path)\n",
               f"raw: {correct}/{total} ({100*correct/total:.1f}%)  mean={mean:.1f}s\n",
               "Per-category:"]
    for cat, (c, n) in sorted(cats.items()):
        lines.append(f"  {cat}: {c}/{n}")
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--thinking-on",  type=Path, required=True)
    parser.add_argument("--thinking-off", type=Path, required=True)
    parser.add_argument("--raw",          type=Path, help="raw-results.json from run-thinking-on.py")
    parser.add_argument("--jni-accuracy", type=float, default=0.82)
    parser.add_argument("--jni-latency-s", type=float, default=53.0)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()

    on_data  = load(args.thinking_on)
    off_data = load(args.thinking_off)
    md = report(on_data, off_data, args.jni_accuracy, args.jni_latency_s)

    if args.raw and args.raw.exists():
        raw_data = load(args.raw)
        md += raw_summary(raw_data)

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(md)
    print(f"Report written to {args.out}")

    on_cases  = on_data.get("cases", [])
    off_cases = off_data.get("cases", [])
    on_c,  on_t  = sum(c.get("rawCorrect",0) for c in on_cases),  len(on_cases)
    off_c, off_t = sum(c.get("rawCorrect",0) for c in off_cases), len(off_cases)
    print(f"  Thinking ON:  {on_c}/{on_t}  ({100*on_c/on_t:.1f}%)" if on_t else "  Thinking ON: no data")
    print(f"  Thinking OFF: {off_c}/{off_t} ({100*off_c/off_t:.1f}%)" if off_t else "  Thinking OFF: no data")
    if args.raw and args.raw.exists():
        raw_cases = load(args.raw).get("cases", [])
        raw_c = sum(c.get("rawCorrect",0) for c in raw_cases)
        raw_t = len(raw_cases)
        print(f"  Raw mode:     {raw_c}/{raw_t} ({100*raw_c/raw_t:.1f}%)" if raw_t else "  Raw mode: no data")


if __name__ == "__main__":
    main()
