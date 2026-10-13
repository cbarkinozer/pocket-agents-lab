#!/usr/bin/env python3
"""Create a portable Argilla review worklist from a frozen targeted plan."""
from __future__ import annotations

import argparse
import collections
import csv
import json
from pathlib import Path

from prepare_phone_routing_reviewed import (CONTRACT, DEFAULT_DATA, SPLITS, audit, load_jsonl,
                                            response_values, targeted_plan, template_groups)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", type=Path, required=True)
    parser.add_argument("--plan", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    snapshot = json.loads(args.snapshot.read_text(encoding="utf-8"))
    plan = json.loads(args.plan.read_text(encoding="utf-8"))
    original = {row["id"]: row for split in SPLITS for row in load_jsonl(DEFAULT_DATA / f"{split}.jsonl")}
    source = {split: load_jsonl(DEFAULT_DATA / f"{split}.jsonl") for split in SPLITS}
    if plan != targeted_plan(source, template_groups(list(original.values()))):
        raise ValueError("Frozen targeted plan differs from source or selection logic")
    records = {record["id"]: record for dataset in snapshot["datasets"] for record in dataset["records"]}
    contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
    audit(snapshot, original, contract)
    issue_ids = set()
    for rid, record in records.items():
        values, problem = response_values(record)
        if problem and problem != "missing_response":
            issue_ids.add(rid)
        if values:
            decision, route = values.get("review_decision"), values.get("correct_route")
            if (route not in contract["route"]["criteria"] or
                decision not in {"keep", "correct", "rewrite", "exclude", "uncertain"} or
                (decision == "keep" and route != original[rid]["route__label"]) or
                (decision == "correct" and route == original[rid]["route__label"]) or
                decision in {"rewrite", "uncertain"} or values.get("scenario_family") == "unclear"):
                issue_ids.add(rid)
    categories = {rid: name for name in ("training_audit", "validation", "calibration", "historical_test")
                  for rid in plan[name]}
    ids = sorted(set(plan["required"]) | issue_ids,
                 key=lambda rid: (0 if rid in issue_ids else 1,
                                  ("validation", "calibration", "historical_test", "training_audit").index(categories[rid])
                                  if rid in categories else -1, rid))
    output = []
    for rid in ids:
        row = original[rid]
        values, problem = response_values(records[rid])
        output.append({"id": rid, "review_group": categories.get(rid, "adjudication_only"),
                       "status": "adjudicate" if rid in issue_ids else "pending" if problem else "done",
                       "request": row["state"], "proposed_route": row["route__label"],
                       "reviewed_route": (values or {}).get("correct_route", ""),
                       "decision": (values or {}).get("review_decision", ""),
                       "scenario": (values or {}).get("scenario_family", "")})
    if args.output.exists():
        raise FileExistsError(f"Worklist already exists: {args.output}")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=list(output[0]))
        writer.writeheader()
        writer.writerows(output)
    print(json.dumps({"output": str(args.output), "rows": len(output),
                      "status": dict(collections.Counter(row["status"] for row in output))}, indent=2))


if __name__ == "__main__":
    main()
