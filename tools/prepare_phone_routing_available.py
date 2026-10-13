#!/usr/bin/env python3
"""Build a provisional phone-routing dataset from available human reviews.

Unreviewed training rows retain their synthetic labels and provenance. Validation
uses reviewed training rows only, so it is a convenience sample, not a sealed test.
"""
from __future__ import annotations

import argparse
import collections
import csv
import json
import random
from pathlib import Path

from prepare_phone_routing_reviewed import (
    CONTRACT, DEFAULT_DATA, FROZEN_LF_SHA256, SCENARIOS, SPLITS, audit, canonical_lf_sha256,
    corrected_row, load_jsonl, sha256, template_groups, write_jsonl,
)


def choose_validation_groups(route_groups: dict[str, list[dict]], reviewed: dict[str, dict],
                             rng: random.Random, target: int = 10) -> set[str]:
    """Cover reviewed scenarios in validation without removing one from training."""
    counts = collections.Counter(reviewed[row["id"]]["scenario_family"]
                                 for rows in route_groups.values() for row in rows)
    selected: set[str] = set()
    selected_counts = collections.Counter()
    for category in sorted(counts):
        if counts[category] < 2:
            continue
        candidates = [key for key, rows in route_groups.items() if key not in selected
                      and len(rows) == 1 and reviewed[rows[0]["id"]]["scenario_family"] == category]
        if candidates and len(selected) < target:
            key = rng.choice(sorted(candidates))
            selected.add(key)
            selected_counts[category] += 1
    keys = sorted(key for key in route_groups if key not in selected)
    rng.shuffle(keys)
    for key in keys:
        if sum(len(route_groups[k]) for k in selected) == target:
            break
        group_counts = collections.Counter(reviewed[row["id"]]["scenario_family"]
                                           for row in route_groups[key])
        if sum(len(route_groups[k]) for k in selected) + len(route_groups[key]) > target:
            continue
        if any(counts[cat] - selected_counts[cat] - n < 1 for cat, n in group_counts.items()):
            continue
        selected.add(key)
        selected_counts.update(group_counts)
    if sum(len(route_groups[k]) for k in selected) != target:
        raise ValueError("Cannot make scenario-covered validation split of requested size")
    return selected


def build(source: dict[str, list[dict]], reviewed: dict[str, dict], contract: dict,
          training_policy: str = "mixed", fixed_validation_ids: set[str] | None = None) -> tuple[dict, dict]:
    if training_policy not in {"mixed", "reviewed_only"}:
        raise ValueError(f"Unknown training policy: {training_policy}")
    original = {row["id"]: row for rows in source.values() for row in rows}
    groups = template_groups(list(original.values()))
    eval_ids = {row["id"] for split in ("calibration", "test") for row in source[split]}
    reserved_groups = {groups[rid] for rid in eval_ids}
    bad = set()
    for rid, values in reviewed.items():
        row = original[rid]
        decision, route = values["review_decision"], values["correct_route"]
        if (decision not in {"keep", "correct"}
                or (decision == "keep") != (route == row["route__label"])
                or values.get("scenario_family") not in SCENARIOS[route] | {"other"}):
            bad.add(rid)
    if bad & {row["id"] for row in source["calibration"]}:
        raise ValueError("Calibration has unresolved reviews; cannot use it")

    eligible = collections.defaultdict(lambda: collections.defaultdict(list))
    for row in source["train"]:
        rid = row["id"]
        if rid in reviewed and rid not in bad and groups[rid] not in reserved_groups:
            eligible[row["route__label"]][groups[rid]].append(row)
    if fixed_validation_ids is not None:
        validation_ids = set(fixed_validation_ids)
        eligible_ids = {row["id"] for route_groups in eligible.values()
                        for rows in route_groups.values() for row in rows}
        if len(validation_ids) != 90 or not validation_ids <= eligible_ids:
            raise ValueError("Fixed validation must contain 90 usable reviewed training IDs")
        counts = collections.Counter(original[rid]["route__label"] for rid in validation_ids)
        if set(counts) != set(contract["route"]["criteria"]) or set(counts.values()) != {10}:
            raise ValueError("Fixed validation must have 10 rows per original route")
    else:
        rng = random.Random(3407)
        validation_ids = set()
        for route in sorted(contract["route"]["criteria"]):
            if sum(map(len, eligible[route].values())) < 10:
                raise ValueError(f"Fewer than 10 usable reviewed validation rows for {route}")
            selected = choose_validation_groups(eligible[route], reviewed, rng)
            validation_ids.update(row["id"] for key in selected for row in eligible[route][key])
    held_groups = reserved_groups | {groups[rid] for rid in validation_ids}
    quarantined_train = sorted(row["id"] for row in source["train"]
                               if groups[row["id"]] in held_groups and row["id"] not in validation_ids)
    quarantined_train += sorted(rid for rid in bad if original[rid]["split"] == "train"
                                and rid not in quarantined_train)
    quarantined_train = sorted(set(quarantined_train))
    partitions = {
        "train": [corrected_row(row, reviewed.get(row["id"]), contract, "train")
                  for row in source["train"] if row["id"] not in validation_ids
                  and row["id"] not in quarantined_train
                  and (training_policy == "mixed" or row["id"] in reviewed)],
        "validation": [corrected_row(row, reviewed[row["id"]], contract, "validation")
                       for row in source["train"] if row["id"] in validation_ids],
        "calibration": [corrected_row(row, reviewed[row["id"]], contract, "calibration")
                        for row in source["calibration"]],
        "test": [corrected_row(row, reviewed[row["id"]], contract, "test")
                 for row in source["test"] if row["id"] not in bad],
    }
    for a, b in (("train", "validation"), ("train", "calibration"), ("train", "test"),
                 ("validation", "calibration"), ("validation", "test"), ("calibration", "test")):
        if {groups[r["id"]] for r in partitions[a]} & {groups[r["id"]] for r in partitions[b]}:
            raise ValueError(f"Template overlap between {a} and {b}")
    details = {"training_policy": training_policy,
               "validation_selection": ("fixed reviewed IDs from prior pilot" if fixed_validation_ids is not None
                                        else "10 per original route, covering reviewed scenarios where possible"),
               "validation_ids": sorted(validation_ids),
               "validation_limit": "Reviewed availability is a convenience sample; not an unbiased sealed test",
               "quarantined_train_ids": quarantined_train,
               "quarantined_evaluation_ids": sorted(rid for rid in bad if rid in eval_ids),
               "template_groups": {row["id"]: groups[row["id"]]
                                   for rows in partitions.values() for row in rows}}
    return partitions, details


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", type=Path, required=True)
    parser.add_argument("--csv", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--data-dir", type=Path, default=DEFAULT_DATA)
    parser.add_argument("--training-policy", choices=("mixed", "reviewed_only"), default="mixed")
    parser.add_argument("--validation-ids-from", type=Path,
                        help="Existing validation.jsonl whose IDs must stay held out")
    args = parser.parse_args()
    if args.output_dir.exists():
        raise FileExistsError(args.output_dir)
    snapshot = json.loads(args.snapshot.read_text(encoding="utf-8"))
    if sha256(args.csv) != snapshot["csv_sha256"]:
        raise ValueError("Review CSV hash mismatch")
    source_files = {split: args.data_dir / f"{split}.jsonl" for split in SPLITS}
    if {split: canonical_lf_sha256(path) for split, path in source_files.items()} != FROZEN_LF_SHA256:
        raise ValueError("Frozen source hashes differ")
    source = {split: load_jsonl(path) for split, path in source_files.items()}
    original = {row["id"]: row for rows in source.values() for row in rows}
    if len(original) != 1080 or [len(source[s]) for s in SPLITS] != [900, 90, 90]:
        raise ValueError("Unexpected source rows")
    with args.csv.open(encoding="utf-8-sig", newline="") as handle:
        sheet = list(csv.DictReader(handle))
    if len(sheet) != len(original) or {row["id"] for row in sheet} != set(original):
        raise ValueError("Review CSV IDs differ from source")
    for row in sheet:
        source_row = original[row["id"]]
        if (row["state"] != source_row["state"] or row["route"] != source_row["route__label"]
                or row["split"] != source_row["split"]):
            raise ValueError(f"Review CSV differs from source: {row['id']}")
        source_row["scenario_family"] = row["scenario_family"]
    contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
    audit_report, reviewed = audit(snapshot, original, contract)
    fixed_ids = None
    if args.validation_ids_from:
        prior_rows = load_jsonl(args.validation_ids_from)
        fixed_ids = {row["id"] for row in prior_rows}
        if len(prior_rows) != len(fixed_ids):
            raise ValueError("Duplicate IDs in fixed validation file")
    partitions, details = build(source, reviewed, contract, args.training_policy, fixed_ids)
    if args.validation_ids_from:
        details["validation_ids_from_sha256"] = sha256(args.validation_ids_from)
    args.output_dir.mkdir(parents=True)
    for split, rows in partitions.items():
        write_jsonl(args.output_dir / f"{split}.jsonl", rows)
    (args.output_dir / "template-groups.json").write_text(
        json.dumps(details.pop("template_groups"), indent=2) + "\n", encoding="utf-8")
    manifest = {"status": "provisional", "policy": args.training_policy,
                "snapshot_sha256": sha256(args.snapshot), "csv_sha256": sha256(args.csv),
                "contract_sha256": sha256(CONTRACT),
                "source_sha256": {split: sha256(path) for split, path in source_files.items()},
                "audit_issues": audit_report["issues"], **details,
                "splits": {split: {"rows": len(rows),
                                   "routes": dict(collections.Counter(r["route__label"] for r in rows)),
                                   "label_sources": dict(collections.Counter(r["label_source"] for r in rows)),
                                   "sha256": sha256(args.output_dir / f"{split}.jsonl")}
                           for split, rows in partitions.items()},
                "historical_test_only": True}
    (args.output_dir / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"output": str(args.output_dir), **manifest}, indent=2))


if __name__ == "__main__":
    main()
