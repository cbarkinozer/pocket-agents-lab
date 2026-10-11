#!/usr/bin/env python3
"""Prepare the general routing mixture from typed-decisions, BANKING77, and CLINC150.

Downloads each dataset at a pinned revision, converts to the state/questions/route schema
used by phone_routing_v1, builds train/validation/calibration splits, and writes JSONL
manifests. Run this once on the GPU pod before train_general_routing.py.

Pinned source revisions (update only deliberately):
  typed-decisions:   LocalLLaMA/typed-decisions  commit 4e3bdb2 (2025-04-11)
  BANKING77:         PolyAI/banking77             commit e732d17 (2024-02-07)
  CLINC150 (OOS):    clinc_oos                    commit a89c130 (2024-02-07)
    CLINC150 in-domain only: oos_full → exclude label 42 ("oos")

Output files (written to --output-dir):
  train.jsonl           — combined mixture, sampled per --weights
  val.jsonl             — per-source validation (frozen IDs)
  calibration.jsonl     — for temperature calibration only
  split-manifest.json   — IDs, hashes, per-source counts, weights
  source-revisions.json — pinned revisions for reproducibility

Usage:
  python3 tools/prepare_general_mixture.py \\
      --output-dir data/general_routing_v1 \\
      --hf-cache  /mnt/cache/huggingface \\
      [--weights typed_decisions=1.0,banking77=0.5,clinc150=0.5]
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import os
import random
import sys
from pathlib import Path

# ─── pinned source revisions ──────────────────────────────────────────────────
SOURCE_REVISIONS = {
    "typed_decisions": {
        "hf_path": "LocalLLaMA/typed-decisions",
        "revision": "4e3bdb2",
        "license": "cc-by-4.0",
        "split_map": {"train": "train"},
        "description": "9 business-workflow decision states (1,120 records)",
    },
    "banking77": {
        "hf_path": "PolyAI/banking77",
        "revision": "e732d17",
        "license": "cc-by-4.0",
        "split_map": {"train": "train", "test": "test"},
        "description": "77 banking intent classes (~13K train, 3K test)",
    },
    "clinc150": {
        "hf_path": "clinc_oos",
        "revision": "a89c130",
        "config": "plus",
        "license": "cc-by-3.0",
        "split_map": {"train": "train", "validation": "validation", "test": "test"},
        "description": "150 in-domain + OOS intent classes (15K train); exclude OOS for routing",
        "oos_label_to_exclude": "oos",
    },
}

# Default mixture weights (relative sampling; will be normalized)
DEFAULT_WEIGHTS = {
    "typed_decisions": 1.0,
    "banking77": 0.5,
    "clinc150": 0.5,
}

# Split proportions (applied to each source's training data)
VAL_FRACTION   = 0.10   # ~10% for validation
CALIB_FRACTION = 0.05   # ~5% for calibration


def parse_args():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--output-dir", type=Path, required=True)
    p.add_argument("--hf-cache",   type=str,  default=None,
                   help="HuggingFace cache directory (sets HF_HOME)")
    p.add_argument("--weights",    type=str,  default=None,
                   help="Comma-separated source=weight pairs, e.g. typed_decisions=1.0,banking77=0.5")
    p.add_argument("--seed",       type=int,  default=3407)
    p.add_argument("--dry-run",    action="store_true",
                   help="Print plan without downloading or writing files")
    return p.parse_args()


def parse_weights(weights_str: str | None) -> dict[str, float]:
    if not weights_str:
        return dict(DEFAULT_WEIGHTS)
    result = {}
    for part in weights_str.split(","):
        k, v = part.strip().split("=")
        result[k.strip()] = float(v.strip())
    return result


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_jsonl(path: Path, rows: list[dict]) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(json.dumps(r) for r in rows) + "\n")
    return file_sha256(path)


# ─── converters ───────────────────────────────────────────────────────────────

def convert_typed_decisions(row: dict, source_id_prefix: str) -> dict | None:
    """typed-decisions rows already have state/questions/gold schema.

    Verify required fields and normalize to the shared output schema.
    typed-decisions uses "workflow" as the routing dimension; map to "route".
    """
    state = row.get("state", "").strip()
    if not state:
        return None
    gold = row.get("gold") or {}
    if isinstance(gold, str):
        gold = json.loads(gold)
    workflow_answer = gold.get("workflow", {}).get("choice") or gold.get("route", {}).get("choice")
    if not workflow_answer:
        return None
    questions = row.get("questions") or {}
    if isinstance(questions, str):
        questions = json.loads(questions)
    return {
        "id": f"{source_id_prefix}_{row.get('id', hash(state))}",
        "source": "typed_decisions",
        "split": "train",
        "state": state,
        "route__label": workflow_answer,
        "workflow": "general_routing",
        "questions": questions,
        "gold": gold,
        "route__confidence": 1.0,
    }


def convert_banking77(row: dict, label_names: list[str], source_id_prefix: str) -> dict | None:
    """Convert BANKING77 intent classification row to routing format.

    BANKING77 has 'text' (user utterance) and 'label' (integer index).
    Wrap as a single-choice routing question over the 77 banking intents.
    """
    text = row.get("text", "").strip()
    label_idx = row.get("label")
    if not text or label_idx is None:
        return None
    label = label_names[label_idx] if label_idx < len(label_names) else str(label_idx)
    return {
        "id": f"{source_id_prefix}_{abs(hash(text)) % 1000000:06d}",
        "source": "banking77",
        "split": "train",
        "state": text,
        "route__label": label,
        "workflow": "banking_intent",
        "questions": {
            "intent": {
                "type": "choice",
                "instructions": "Select the banking intent that best matches the request.",
                "criteria": {name: name.replace("_", " ") for name in label_names},
            }
        },
        "route__confidence": 1.0,
    }


def convert_clinc150(row: dict, label_names: list[str], oos_label: str,
                     source_id_prefix: str) -> dict | None:
    """Convert CLINC150 intent row to routing format. Skip OOS examples."""
    text = row.get("text", "").strip()
    label_idx = row.get("intent")
    if not text or label_idx is None:
        return None
    label = label_names[label_idx] if label_idx < len(label_names) else str(label_idx)
    if label == oos_label:
        return None  # exclude out-of-scope from routing training
    return {
        "id": f"{source_id_prefix}_{abs(hash(text)) % 1000000:06d}",
        "source": "clinc150",
        "split": "train",
        "state": text,
        "route__label": label,
        "workflow": "clinc150_intent",
        "questions": {
            "intent": {
                "type": "choice",
                "instructions": "Select the user intent that best matches the request.",
                "criteria": {name: name.replace("_", " ")
                             for name in label_names if name != oos_label},
            }
        },
        "route__confidence": 1.0,
    }


# ─── split carving ────────────────────────────────────────────────────────────

def stratified_split(rows: list[dict], val_frac: float, calib_frac: float,
                     seed: int) -> tuple[list[dict], list[dict], list[dict]]:
    """Split rows into (train, val, calib) stratified by route__label."""
    rng = random.Random(seed)
    by_label: dict[str, list[dict]] = collections.defaultdict(list)
    for r in rows:
        by_label[r["route__label"]].append(r)

    train_rows, val_rows, calib_rows = [], [], []
    for label, group in sorted(by_label.items()):
        rng.shuffle(group)
        n = len(group)
        n_val   = max(1, round(n * val_frac))
        n_calib = max(1, round(n * calib_frac))
        n_train = n - n_val - n_calib
        if n_train < 1:
            # Too few examples; put all in train
            train_rows.extend(group)
        else:
            train_rows.extend(group[:n_train])
            val_rows.extend(group[n_train:n_train + n_val])
            calib_rows.extend(group[n_train + n_val:])
    return train_rows, val_rows, calib_rows


def weighted_sample(source_rows: dict[str, list[dict]], weights: dict[str, float],
                    seed: int) -> list[dict]:
    """Sample from each source proportionally to its weight.

    The largest source is taken in full; other sources are sampled down to
    weight * max_source_size rows, rounded to nearest integer.
    """
    rng = random.Random(seed)
    max_size = max(len(rows) for rows in source_rows.values())
    combined = []
    actual_weights = {}
    for source, rows in sorted(source_rows.items()):
        w = weights.get(source, 1.0)
        target = round(w * max_size)
        sampled = rows if len(rows) <= target else rng.sample(rows, target)
        actual_weights[source] = len(sampled)
        combined.extend(sampled)
    rng.shuffle(combined)
    return combined, actual_weights


# ─── main ─────────────────────────────────────────────────────────────────────

def main():
    args = parse_args()
    weights = parse_weights(args.weights)

    if args.hf_cache:
        os.environ["HF_HOME"] = args.hf_cache

    print(f"Preparing general routing mixture")
    print(f"  output: {args.output_dir}")
    print(f"  weights: {weights}")
    print(f"  seed: {args.seed}")

    if args.dry_run:
        print("\nDRY RUN — sources would be loaded from:")
        for name, meta in SOURCE_REVISIONS.items():
            print(f"  {name}: {meta['hf_path']}@{meta['revision']}")
        return

    try:
        from datasets import load_dataset
    except ImportError:
        print("ERROR: datasets not found. pip install datasets")
        sys.exit(1)

    source_train: dict[str, list[dict]] = {}
    source_val:   dict[str, list[dict]] = {}
    source_calib: dict[str, list[dict]] = {}

    # ── typed-decisions ───────────────────────────────────────────────────────
    print("\nLoading typed-decisions…")
    td = load_dataset(
        SOURCE_REVISIONS["typed_decisions"]["hf_path"],
        revision=SOURCE_REVISIONS["typed_decisions"]["revision"],
        trust_remote_code=True,
    )
    td_rows = []
    for row in td["train"]:
        converted = convert_typed_decisions(row, "td")
        if converted:
            td_rows.append(converted)
    print(f"  Loaded {len(td_rows)} typed-decisions rows")
    source_train["typed_decisions"], source_val["typed_decisions"], source_calib["typed_decisions"] = \
        stratified_split(td_rows, VAL_FRACTION, CALIB_FRACTION, args.seed)

    # ── BANKING77 ─────────────────────────────────────────────────────────────
    print("Loading BANKING77…")
    b77 = load_dataset(
        SOURCE_REVISIONS["banking77"]["hf_path"],
        revision=SOURCE_REVISIONS["banking77"]["revision"],
        trust_remote_code=True,
    )
    b77_labels = b77["train"].features["label"].names
    b77_rows = []
    for split in ("train",):
        for row in b77[split]:
            converted = convert_banking77(row, b77_labels, f"b77_{split}")
            if converted:
                b77_rows.append(converted)
    print(f"  Loaded {len(b77_rows)} BANKING77 rows ({len(b77_labels)} labels)")
    source_train["banking77"], source_val["banking77"], source_calib["banking77"] = \
        stratified_split(b77_rows, VAL_FRACTION, CALIB_FRACTION, args.seed)

    # ── CLINC150 ──────────────────────────────────────────────────────────────
    print("Loading CLINC150 (plus config, in-domain only)…")
    cl150_meta = SOURCE_REVISIONS["clinc150"]
    cl150 = load_dataset(
        cl150_meta["hf_path"],
        cl150_meta.get("config", "plus"),
        revision=cl150_meta["revision"],
        trust_remote_code=True,
    )
    cl150_labels = cl150["train"].features["intent"].names
    oos_label = cl150_meta["oos_label_to_exclude"]
    cl150_rows = []
    for split in ("train",):
        for row in cl150[split]:
            converted = convert_clinc150(row, cl150_labels, oos_label, f"cl150_{split}")
            if converted:
                cl150_rows.append(converted)
    in_domain_labels = [l for l in cl150_labels if l != oos_label]
    print(f"  Loaded {len(cl150_rows)} CLINC150 rows ({len(in_domain_labels)} in-domain labels, {oos_label} excluded)")
    source_train["clinc150"], source_val["clinc150"], source_calib["clinc150"] = \
        stratified_split(cl150_rows, VAL_FRACTION, CALIB_FRACTION, args.seed)

    # ── weighted sampling for train mixture ───────────────────────────────────
    print("\nBuilding weighted train mixture…")
    combined_train, actual_weights = weighted_sample(source_train, weights, args.seed)
    print(f"  Actual train counts by source: {actual_weights}")
    print(f"  Total train rows: {len(combined_train)}")

    all_val   = sum(source_val.values(),   [])
    all_calib = sum(source_calib.values(), [])
    print(f"  Val rows: {len(all_val)}, Calib rows: {len(all_calib)}")

    # ── write outputs ─────────────────────────────────────────────────────────
    args.output_dir.mkdir(parents=True, exist_ok=True)

    train_sha = write_jsonl(args.output_dir / "train.jsonl", combined_train)
    val_sha   = write_jsonl(args.output_dir / "val.jsonl",   all_val)
    calib_sha = write_jsonl(args.output_dir / "calibration.jsonl", all_calib)

    split_manifest = {
        "dataset": "general_routing_v1",
        "generated": __import__("time").strftime("%Y-%m-%dT%H:%M:%SZ", __import__("time").gmtime()),
        "seed": args.seed,
        "requested_weights": weights,
        "actual_train_counts": actual_weights,
        "train_total": len(combined_train),
        "val_total": len(all_val),
        "calib_total": len(all_calib),
        "train_sha256": train_sha,
        "val_sha256": val_sha,
        "calib_sha256": calib_sha,
        "val_fraction": VAL_FRACTION,
        "calib_fraction": CALIB_FRACTION,
        "per_source_val": {k: len(v) for k, v in source_val.items()},
        "per_source_calib": {k: len(v) for k, v in source_calib.items()},
    }
    (args.output_dir / "split-manifest.json").write_text(
        json.dumps(split_manifest, indent=2) + "\n")

    source_revisions_out = {k: {
        "hf_path": v["hf_path"],
        "revision": v["revision"],
        "license": v["license"],
    } for k, v in SOURCE_REVISIONS.items()}
    (args.output_dir / "source-revisions.json").write_text(
        json.dumps(source_revisions_out, indent=2) + "\n")

    print(f"\n{'='*50}")
    print(f"General mixture prepared → {args.output_dir}")
    print(f"  train.jsonl:       {len(combined_train)} rows  {train_sha[:16]}…")
    print(f"  val.jsonl:         {len(all_val)} rows  {val_sha[:16]}…")
    print(f"  calibration.jsonl: {len(all_calib)} rows  {calib_sha[:16]}…")
    print(f"\nNext: python3 tools/train_general_routing.py --data-dir {args.output_dir} ...")


if __name__ == "__main__":
    main()
