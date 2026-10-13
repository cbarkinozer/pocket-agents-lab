#!/usr/bin/env python3
"""Audit Argilla responses and build versioned phone data v2.

The source JSONL files are immutable. Running without --finalize is read-only.
Full finalization requires review of every row. Targeted finalization requires a
fixed, route-balanced audit sample plus complete review of evaluation rows.
The historical test remains historical; new validation and calibration groups
are drawn only from the old train/calibration pool.
"""
from __future__ import annotations

import argparse
import collections
import difflib
import hashlib
import json
import random
import re
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_DATA = HERE.parent / "data/phone_routing"
CONTRACT = HERE / "phone_routing_contract_v2.json"
SPLITS = ("train", "calibration", "test")
SCENARIOS = {
    "answer": {"arithmetic", "general_knowledge", "creative", "not_phone"},
    "get_battery_info": {"battery_level", "charging_status", "battery_health", "battery_temperature", "battery_projection"},
    "get_storage_info": {"available_space", "usage_by_type", "capacity_check"},
    "get_device_info": {"memory_ram", "cpu_arch", "model_manufacturer", "os_version", "specs"},
    "get_media_info": {"now_playing", "metadata", "identify", "offline_availability"},
    "phone_health_check": {"multi_category", "overall_condition", "trip_readiness", "issue_diagnosis"},
    "save_note": {"explicit_save", "appointment", "reference_code", "credential"},
    "search_local_files": {"by_filename", "by_type", "by_content", "by_time"},
    "search_notes": {"recall_specific", "by_topic", "list_all"},
}
FROZEN_LF_SHA256 = {
    "train": "76afcebcf1ae72f36514e27cfb42fbd50aaa309d3a31a411d771a5f3d8162925",
    "calibration": "6082bea44e25ffb9f55bfbd60a10185f1fab48424c568c2d3700b318aee5744e",
    "test": "ed41b456f4c7eee7b6311df19bd16f39f594016dbc9b47ffd398906ac6315dec",
}


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def canonical_lf_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes().replace(b"\r\n", b"\n")).hexdigest()


def load_jsonl(path: Path) -> list[dict]:
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def token_set(text: str) -> set[str]:
    return set(re.findall(r"\w+", text.casefold()))


def near_template(a: str, b: str) -> bool:
    aa, bb = token_set(a), token_set(b)
    jac = len(aa & bb) / len(aa | bb) if aa | bb else 1.0
    return jac >= .75 or (jac >= .5 and difflib.SequenceMatcher(None, a.casefold(), b.casefold()).ratio() >= .88)


def template_groups(rows: list[dict]) -> dict[str, str]:
    """Conservative automatic groups; a separate human template audit is still required."""
    parent = {row["id"]: row["id"] for row in rows}

    def find(key: str) -> str:
        while parent[key] != key:
            parent[key] = parent[parent[key]]
            key = parent[key]
        return key

    by_route = collections.defaultdict(list)
    for row in rows:
        by_route[row["route__label"]].append(row)
    for route_rows in by_route.values():
        for i, left in enumerate(route_rows):
            for right in route_rows[i + 1:]:
                if near_template(left["state"], right["state"]):
                    parent[find(right["id"])] = find(left["id"])
    components = collections.defaultdict(list)
    for row in rows:
        components[find(row["id"])].append(row["id"])
    return {rid: min(ids) for ids in components.values() for rid in ids}


def choose_group_ids(groups: dict[str, list[dict]], target: int, rng: random.Random) -> set[str]:
    keys = list(groups)
    rng.shuffle(keys)
    reachable: dict[int, tuple[str, ...]] = {0: ()}
    for key in keys:
        size = len(groups[key])
        for count, selected in sorted(list(reachable.items()), reverse=True):
            new_count = count + size
            if new_count <= target and new_count not in reachable:
                reachable[new_count] = (*selected, key)
    if target not in reachable:
        raise ValueError(f"Cannot form a grouped split with exactly {target} rows")
    return set(reachable[target])


def grouped_split(rows: list[dict], group_ids: dict[str, str], seed: int = 3407) -> dict[str, list[dict]]:
    """Make 810/90/90 train/validation/calibration with ten per route in each holdout."""
    by_route = collections.defaultdict(list)
    for row in rows:
        by_route[row["route__label"]].append(row)
    result = {"train": [], "validation": [], "calibration": []}
    rng = random.Random(seed)
    for route in sorted(by_route):
        groups = collections.defaultdict(list)
        for row in by_route[route]:
            groups[group_ids[row["id"]]].append(row)
        if len(by_route[route]) < 21:
            raise ValueError(f"Too few {route} examples for grouped splitting")
        validation = choose_group_ids(groups, 10, rng)
        remaining = {key: items for key, items in groups.items() if key not in validation}
        calibration = choose_group_ids(remaining, 10, rng)
        for key, items in groups.items():
            split = "validation" if key in validation else "calibration" if key in calibration else "train"
            result[split].extend(items)
    for split in result:
        result[split].sort(key=lambda row: row["id"])
    for a, b in (("train", "validation"), ("train", "calibration"), ("validation", "calibration")):
        if {group_ids[r["id"]] for r in result[a]} & {group_ids[r["id"]] for r in result[b]}:
            raise AssertionError(f"Template group leaked between {a} and {b}")
    return result


def targeted_plan(source: dict[str, list[dict]], groups: dict[str, str], seed: int = 3407) -> dict:
    """Select required IDs without consulting review outcomes."""
    rng = random.Random(seed)
    reserved_groups = {groups[row["id"]] for split in ("calibration", "test") for row in source[split]}
    quarantined = sorted(row["id"] for row in source["train"] if groups[row["id"]] in reserved_groups)
    eligible = [row for row in source["train"] if row["id"] not in set(quarantined)]
    by_route = collections.defaultdict(list)
    for row in eligible:
        by_route[row["route__label"]].append(row)
    validation, training_audit = set(), set()
    for route in sorted(by_route):
        route_groups = collections.defaultdict(list)
        for row in by_route[route]:
            route_groups[groups[row["id"]]].append(row)
        chosen_groups = choose_group_ids(route_groups, 10, rng)
        validation.update(row["id"] for key in chosen_groups for row in route_groups[key])
        remaining = [row["id"] for row in by_route[route] if row["id"] not in validation]
        training_audit.update(rng.sample(sorted(remaining), 20))
    evaluation = validation | {row["id"] for split in ("calibration", "test") for row in source[split]}
    return {"seed": seed, "validation": sorted(validation),
            "training_audit": sorted(training_audit),
            "calibration": sorted(row["id"] for row in source["calibration"]),
            "historical_test": sorted(row["id"] for row in source["test"]),
            "quarantined_train": quarantined, "required": sorted(evaluation | training_audit)}


def response_values(record: dict) -> tuple[dict | None, str | None]:
    responses = record["responses"]
    if len(responses) != 1:
        return None, "missing_response" if not responses else "multiple_responses"
    response = responses[0]
    if response["status"] != "submitted":
        return None, "response_not_submitted"
    return {key: value["value"] for key, value in response["values"].items()}, None


def audit(snapshot: dict, original: dict[str, dict], contract: dict) -> tuple[dict, dict[str, dict]]:
    routes = set(contract["route"]["criteria"])
    issues: dict[str, list[str]] = collections.defaultdict(list)
    reviewed: dict[str, dict] = {}
    counts = collections.Counter()
    submitted_by_split = collections.Counter()
    missing_by_split = collections.Counter()
    submitted_by_route = collections.Counter()
    missing_by_route = collections.Counter()
    snapshot_ids: set[str] = set()
    for dataset in snapshot["datasets"]:
        for record in dataset["records"]:
            rid = record["id"]
            if rid in snapshot_ids or rid not in original:
                raise ValueError(f"Unexpected or duplicate snapshot ID: {rid}")
            snapshot_ids.add(rid)
            source = original[rid]
            if record["fields"]["request"] != source["state"]:
                raise ValueError(f"Source request differs: {rid}")
            values, problem = response_values(record)
            if problem:
                issues[problem].append(rid)
                if problem == "missing_response":
                    missing_by_split[source["split"]] += 1
                    missing_by_route[source["route__label"]] += 1
                continue
            counts["submitted"] += 1
            submitted_by_split[source["split"]] += 1
            submitted_by_route[source["route__label"]] += 1
            route = values.get("correct_route")
            decision = values.get("review_decision")
            if route not in routes or decision not in {"keep", "correct", "rewrite", "exclude", "uncertain"}:
                issues["invalid_response"].append(rid)
                continue
            if decision == "keep" and route != source["route__label"]:
                issues["route_changed_but_keep"].append(rid)
            if decision == "correct" and route == source["route__label"]:
                issues["correct_without_route_change"].append(rid)
            if decision in {"rewrite", "uncertain"}:
                issues["requires_adjudication"].append(rid)
            if values.get("scenario_family") == "unclear":
                issues["unclear_request"].append(rid)
            elif values.get("scenario_family") not in SCENARIOS[route] | {"other"}:
                issues["route_scenario_mismatch"].append(rid)
            counts["decision_" + decision] += 1
            reviewed[rid] = values
    missing = set(original) - snapshot_ids
    if missing:
        raise ValueError(f"Snapshot omitted {len(missing)} source records")
    report = {
        "dataset": "phone_routing_reviewed_v2",
        "source_rows": len(original),
        "submitted": counts["submitted"],
        "submitted_by_split": dict(submitted_by_split),
        "missing_by_split": dict(missing_by_split),
        "submitted_by_original_route": dict(sorted(submitted_by_route.items())),
        "missing_by_original_route": dict(sorted(missing_by_route.items())),
        "decisions": {k.removeprefix("decision_"): v for k, v in counts.items() if k.startswith("decision_")},
        "issues": {key: {"count": len(ids), "sample_ids": sorted(ids)[:20]} for key, ids in sorted(issues.items())},
        "ready_to_finalize": not issues,
    }
    return report, reviewed


def corrected_row(source: dict, values: dict | None, contract: dict, new_split: str) -> dict:
    row = dict(source)
    route = values["correct_route"] if values else source["route__label"]
    probs = {name: float(name == route) for name in contract["route"]["criteria"]}
    row.update(split=new_split, questions=json.dumps(contract, ensure_ascii=False),
               gold=json.dumps({"route": {"type": "choice", "label": route, "confidence": 1.0, "probabilities": probs}}, ensure_ascii=False),
               route__label=route, route__confidence=1.0,
               route__probabilities=json.dumps(probs, ensure_ascii=False),
               label_agreement="{}", label_source="human_reviewed" if values else "synthetic_unreviewed")
    if values and values.get("scenario_family"):
        row["scenario_family"] = values["scenario_family"]
    if "scenario_family" in row:
        row["scenario_source"] = "human_reviewed" if values else "synthetic_unreviewed"
    return row


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", type=Path, required=True)
    parser.add_argument("--data-dir", type=Path, default=DEFAULT_DATA)
    parser.add_argument("--output-dir", type=Path)
    parser.add_argument("--csv", type=Path, help="Original review CSV, for snapshot SHA-256 verification")
    parser.add_argument("--finalize", action="store_true")
    parser.add_argument("--review-policy", choices=("full", "targeted"), default="full")
    parser.add_argument("--plan", type=Path, help="Frozen targeted review plan; required for targeted finalization")
    parser.add_argument("--plan-output", type=Path, help="Write the fixed targeted review plan as JSON")
    args = parser.parse_args()
    if args.finalize and not args.output_dir:
        parser.error("--finalize requires --output-dir")
    if args.finalize and args.review_policy == "targeted" and not args.plan:
        parser.error("targeted finalization requires --plan")
    if args.csv and sha256(args.csv) != json.loads(args.snapshot.read_text(encoding="utf-8"))["csv_sha256"]:
        raise ValueError("Snapshot and review CSV hashes differ")
    snapshot = json.loads(args.snapshot.read_text(encoding="utf-8"))
    contract = json.loads(CONTRACT.read_text(encoding="utf-8"))
    source_files = {split: args.data_dir / f"{split}.jsonl" for split in SPLITS}
    source_lf_hashes = {split: canonical_lf_sha256(path) for split, path in source_files.items()}
    if source_lf_hashes != FROZEN_LF_SHA256:
        raise ValueError(f"Source differs from frozen phone_routing_v1: {source_lf_hashes}")
    source = {split: load_jsonl(path) for split, path in source_files.items()}
    original = {row["id"]: row for rows in source.values() for row in rows}
    if len(original) != 1080 or any(len(source[split]) != expected for split, expected in (("train", 900), ("calibration", 90), ("test", 90))):
        raise ValueError("Expected 1,080 unique source rows with 900/90/90 splits")
    report, reviewed = audit(snapshot, original, contract)
    original_groups = template_groups(list(original.values()))
    by_template = collections.defaultdict(list)
    for row in original.values():
        by_template[original_groups[row["id"]]].append(row)
    cross_split = [sorted(row["id"] for row in items) for items in by_template.values()
                   if len({row["split"] for row in items}) > 1]
    report["source_template_groups"] = len(by_template)
    report["source_cross_split_template_groups"] = sorted(cross_split)
    report["snapshot_sha256"] = sha256(args.snapshot)
    report["source_sha256"] = {split: sha256(path) for split, path in source_files.items()}
    report["source_canonical_lf_sha256"] = source_lf_hashes
    plan = targeted_plan(source, original_groups)
    if args.plan and json.loads(args.plan.read_text(encoding="utf-8")) != plan:
        raise ValueError("Frozen targeted plan differs from current source or selection logic")
    if args.plan_output:
        if args.plan_output.exists():
            raise FileExistsError(f"Plan already exists: {args.plan_output}")
        args.plan_output.parent.mkdir(parents=True, exist_ok=True)
        args.plan_output.write_text(json.dumps(plan, indent=2) + "\n", encoding="utf-8")
    if args.review_policy == "targeted":
        required = set(plan["required"])
        missing_required = sorted(required - reviewed.keys())
        invalid_eval = sorted(rid for rid in set(plan["validation"] + plan["calibration"] + plan["historical_test"])
                              if rid in reviewed and reviewed[rid]["review_decision"] == "exclude")
        report["review_policy"] = "targeted"
        if args.plan:
            report["review_plan_sha256"] = sha256(args.plan)
        report["targeted_review"] = {"required": len(required), "reviewed_required": len(required) - len(missing_required),
                                     "missing_required": len(missing_required), "missing_sample_ids": missing_required[:20],
                                     "excluded_evaluation_ids": invalid_eval,
                                     "quarantined_train_ids": plan["quarantined_train"]}
        report["ready_to_finalize"] = not (missing_required or invalid_eval or
                                          any(key != "missing_response" for key in report["issues"]))
    print(json.dumps(report, indent=2))
    if not args.finalize:
        return
    if not report["ready_to_finalize"]:
        raise ValueError("Review incomplete or inconsistent; resolve audit issues before finalizing")
    output = args.output_dir
    if output.exists():
        raise FileExistsError(f"Output already exists: {output}")
    if args.review_policy == "targeted":
        validation_ids = set(plan["validation"])
        quarantined = set(plan["quarantined_train"])
        partitions = {
            "train": [corrected_row(row, reviewed.get(row["id"]), contract, "train") for row in source["train"]
                      if row["id"] not in validation_ids | quarantined and
                      reviewed.get(row["id"], {}).get("review_decision") != "exclude"],
            "validation": [corrected_row(row, reviewed[row["id"]], contract, "validation") for row in source["train"]
                           if row["id"] in validation_ids],
            "calibration": [corrected_row(row, reviewed[row["id"]], contract, "calibration") for row in source["calibration"]],
            "test": [corrected_row(row, reviewed[row["id"]], contract, "test") for row in source["test"]],
        }
        groups = {row["id"]: original_groups[row["id"]] for rows in partitions.values() for row in rows}
    else:
        dev = [corrected_row(row, reviewed[row["id"]], contract, row["split"])
               for split in ("train", "calibration") for row in source[split]
               if reviewed[row["id"]]["review_decision"] != "exclude"]
        historical = [corrected_row(row, reviewed[row["id"]], contract, "test") for row in source["test"]
                      if reviewed[row["id"]]["review_decision"] != "exclude"]
        groups = template_groups(dev + historical)
        historical_groups = {groups[row["id"]] for row in historical}
        quarantined = sorted(row["id"] for row in dev if groups[row["id"]] in historical_groups)
        dev = [row for row in dev if row["id"] not in set(quarantined)]
        report["quarantined_historical_overlap_ids"] = quarantined
        partitions = grouped_split(dev, groups)
        partitions["test"] = sorted(historical, key=lambda row: row["id"])
    for left, right in (("train", "validation"), ("train", "calibration"), ("train", "test"),
                        ("validation", "calibration"), ("validation", "test"), ("calibration", "test")):
        if {groups[row["id"]] for row in partitions[left]} & {groups[row["id"]] for row in partitions[right]}:
            raise ValueError(f"Template group leaked between {left} and {right}")
    output.mkdir(parents=True)
    for split, rows in partitions.items():
        write_jsonl(output / f"{split}.jsonl", rows)
    (output / "template-groups.json").write_text(json.dumps(groups, indent=2) + "\n", encoding="utf-8")
    report["splits"] = {split: {"rows": len(rows), "routes": dict(collections.Counter(row["route__label"] for row in rows)),
                                "sha256": sha256(output / f"{split}.jsonl")} for split, rows in partitions.items()}
    report["contract_sha256"] = sha256(CONTRACT)
    report["label_sources"] = {split: dict(collections.Counter(row["label_source"] for row in rows))
                               for split, rows in partitions.items()}
    report["historical_test_only"] = True
    report["template_group_method"] = "token Jaccard >= .75 or (Jaccard >= .5 and sequence ratio >= .88); human audit required"
    (output / "manifest.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(f"Prepared {output} with grouped validation and calibration")


if __name__ == "__main__":
    main()
