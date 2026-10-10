#!/usr/bin/env python3
"""Evaluate reflex-1 (or any Clef server) on the phone routing test set.

Sends each test case to the running llama-server's /v1/systemone endpoint
and computes accuracy by route. Uses the held-out test.jsonl — do not
include these cases in training.

Usage (server must be running and ADB-forwarded):
  python3 tools/eval_phone_routing.py \\
      --adb /Users/cbarkinozer/.cache/reflex-android/platform-tools/adb \\
      --serial R68T800LZ0H \\
      --data-dir data/phone_routing \\
      --split test \\
      --model-label reflex-1-Q4_K_M \\
      --out benchmarks/phone-routing-eval/2026-10-11-reflex1-before-retrain.json
"""
from __future__ import annotations

import argparse
import json
import statistics
import subprocess
import time
from collections import defaultdict
from pathlib import Path

import sys
sys.path.insert(0, str(Path(__file__).parent))
from benchmark_decision_android import telemetry, request as bench_request


QUESTIONS_SCHEMA = {
    "route": {
        "type": "choice",
        "instructions": (
            "Select exactly one Android agent route by the meaning of the request. "
            "Use live phone tools only for current phone facts. "
            "Use phone_health_check for overall condition or two or more live categories. "
            "Otherwise answer without a tool."
        ),
        "criteria": {
            "answer": "Writing, jokes, arithmetic, definitions, general knowledge, or unclear requests. No live phone data needed.",
            "get_device_info": "Current phone model, manufacturer, Android version, CPU ABI or physical RAM.",
            "get_battery_info": "Live battery level, charging status, temperature, heat or need to cool down.",
            "get_storage_info": "Live internal disk space, used/free/total storage, capacity to save files or models.",
            "get_media_info": "Currently playing song or video metadata.",
            "search_local_files": "Find a filename or phrase in previously authorized local files.",
            "search_notes": "Recall or find information previously saved to notes.",
            "save_note": "Explicitly remember or save information for later.",
            "phone_health_check": "Overall phone health, condition, AI workload readiness, check everything, or two or more live categories together.",
        },
    }
}


class FakeArgs:
    def __init__(self, adb_path, serial, url, pid):
        self.adb = adb_path
        self.serial = serial
        self.url = url
        self.pid = pid


def save(path: Path, report: dict):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(report, indent=2) + "\n")
    tmp.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb",         required=True)
    parser.add_argument("--serial")
    parser.add_argument("--url",         default="http://127.0.0.1:8091")
    parser.add_argument("--data-dir",    type=Path, required=True)
    parser.add_argument("--split",       choices=["train", "calibration", "test"], default="test")
    parser.add_argument("--model-label", required=True)
    parser.add_argument("--pid",         type=int)
    parser.add_argument("--out",         type=Path, required=True)
    parser.add_argument("--limit",       type=int)
    args = parser.parse_args()

    fa = FakeArgs(args.adb, args.serial, args.url, args.pid)

    # Ensure ADB port forwarding
    port = int(args.url.rsplit(":", 1)[-1])
    fwd_cmd = [args.adb] + (["-s", args.serial] if args.serial else []) + ["forward", "--list"]
    fwd = subprocess.check_output(fwd_cmd, text=True, timeout=10)
    if f"tcp:{port}" not in fwd:
        subprocess.check_call(
            [args.adb] + (["-s", args.serial] if args.serial else []) +
            [f"forward", f"tcp:{port}", f"tcp:{port}"], timeout=10)
        print(f"ADB port {port} forwarded", flush=True)

    # Load cases
    jsonl = args.data_dir / f"{args.split}.jsonl"
    cases = [json.loads(l) for l in jsonl.read_text().splitlines() if l.strip()]
    if args.limit:
        cases = cases[:args.limit]

    report = {
        "dataset": "phone_routing_v1",
        "split": args.split,
        "model": args.model_label,
        "total": len(cases),
        "complete": False,
        "cases": [],
    }
    save(args.out, report)

    correct_by_route: defaultdict[str, list[bool]] = defaultdict(list)

    for i, case in enumerate(cases):
        state   = case["state"]
        gold    = case["route__label"]
        start   = time.monotonic()
        row: dict = {"id": case["id"], "state": state, "gold": gold}
        try:
            before = telemetry(fa)
            response = bench_request(fa, "/v1/systemone", {
                "model": args.model_label,
                "state": state,
                "questions": QUESTIONS_SCHEMA,
            })
            chosen = response["answers"]["route"]["choice"]
            probs  = response["answers"]["route"]["probabilities"]
            row.update(chosen=chosen, correct=(chosen == gold),
                       probs=probs, response=response)
            correct_by_route[gold].append(chosen == gold)
        except Exception as e:
            row.update(correct=False, error=str(e))
            correct_by_route[gold].append(False)
        row["latencyMs"] = (time.monotonic() - start) * 1000
        report["cases"].append(row)

        total_correct = sum(r.get("correct", False) for r in report["cases"])
        n = len(report["cases"])
        mean_lat = statistics.mean(c["latencyMs"] for c in report["cases"])
        print(f"[{n:2d}/{len(cases)}] {case['id']:30s} gold={gold:20s} "
              f"chosen={row.get('chosen','ERROR'):20s} "
              f"acc={total_correct}/{n} lat={row['latencyMs']/1000:.1f}s",
              flush=True)
        save(args.out, report)

    # Final stats
    total = len(report["cases"])
    total_correct = sum(r.get("correct", False) for r in report["cases"])
    report.update(
        complete=True,
        correct=total_correct,
        accuracy=total_correct / total if total else 0.0,
        meanLatencyMs=statistics.mean(c["latencyMs"] for c in report["cases"]),
        perRoute={
            route: {"correct": sum(v), "total": len(v), "accuracy": sum(v)/len(v)}
            for route, v in sorted(correct_by_route.items())
        },
    )
    save(args.out, report)

    print(f"\n{'='*60}")
    print(f"Model: {args.model_label}  Split: {args.split}  Dataset: phone_routing_v1")
    print(f"Overall: {total_correct}/{total} ({100*total_correct/total:.1f}%)")
    print(f"Mean latency: {report['meanLatencyMs']/1000:.1f}s")
    print(f"\nPer route:")
    for route, stats in report["perRoute"].items():
        pct = 100 * stats["accuracy"]
        print(f"  {route:25s}: {stats['correct']}/{stats['total']} ({pct:.0f}%)")
    print(f"\nResults → {args.out}")


if __name__ == "__main__":
    main()
