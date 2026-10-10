#!/usr/bin/env python3
"""Post-retrain 4-benchmark evaluation for the phone routing model.

Evaluates the retrained Clef head on all four benchmarks:
  1. phone_routing_v1 test split (primary — 90 held-out cases)
  2. typed-decisions test split (regression — should not degrade)
  3. BANKING77 balanced test subset (regression)
  4. CLINC150 balanced test subset (regression)

Run on the GPU pod after training completes.

Usage:
  python3 /path/to/pocket-agents-lab/tools/evaluate_phone_routing_model.py \\
      --adapter  /path/to/runs/phone-routing-v1/adapter \\
      --data-dir /path/to/pocket-agents-lab/data/phone_routing \\
      --out      /path/to/runs/phone-routing-v1/benchmarks.json \\
      [--reflex1-adapter  /path/to/runs/notebook-60steps/adapter]  # optional before-train comparison
"""
from __future__ import annotations

import argparse
import csv
import gc
import hashlib
import io
import json
import math
import os
import random
import time
from pathlib import Path

os.environ.update(
    UNSLOTH_DISABLE_STATISTICS="1",
    UNSLOTH_COMPILE_DISABLE="1",
    WANDB_MODE="disabled",
    TOKENIZERS_PARALLELISM="false",
)

import requests
import torch
from datasets import Dataset, load_dataset
from huggingface_hub import HfApi
from unsloth import FastDecisionModel

SEED = 3407
BANKING77_PER_CLASS = 6
CLINC150_PER_CLASS = 3


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_jsonl(path: Path) -> Dataset:
    records = [json.loads(l) for l in path.read_text().splitlines() if l.strip()]
    return Dataset.from_list(records)


def load_banking77(training_states: set[str]) -> Dataset:
    rev = requests.get(
        "https://api.github.com/repos/PolyAI-LDN/task-specific-datasets/commits/master",
        timeout=60,
    ).json()["sha"]
    url = f"https://raw.githubusercontent.com/PolyAI-LDN/task-specific-datasets/{rev}/banking_data/test.csv"
    resp = requests.get(url, timeout=60)
    resp.raise_for_status()
    rows = [{"text": r["text"], "label": r["category"], "source_index": i}
            for i, r in enumerate(csv.DictReader(io.StringIO(resp.text)))]
    return _balanced_intent_dataset(rows, training_states, BANKING77_PER_CLASS), rev


def load_clinc150(training_states: set[str]) -> Dataset:
    info = HfApi().dataset_info("clinc/clinc_oos")
    raw = load_dataset("clinc/clinc_oos", "plus", split="test", revision=info.sha)
    names = raw.features["intent"].names
    rows = [{"text": r["text"], "label": names[r["intent"]], "source_index": i}
            for i, r in enumerate(raw) if names[r["intent"]] != "oos"]
    return _balanced_intent_dataset(rows, training_states, CLINC150_PER_CLASS), info.sha


def _balanced_intent_dataset(rows: list[dict], training_states: set[str], n: int) -> Dataset:
    normalize = lambda s: " ".join(s.casefold().split())
    groups: dict[str, list] = {}
    for r in rows:
        if normalize(r["text"]) not in training_states:
            groups.setdefault(r["label"], []).append(r)
    rng = random.Random(SEED)
    selected = []
    for label in sorted(groups):
        selected.extend(rng.sample(groups[label], min(n, len(groups[label]))))
    rng.shuffle(selected)
    labels = sorted(groups)
    question = {
        "intent": {
            "type": "choice",
            "instructions": "Which intent best matches the user request? Select exactly one intent.",
            "criteria": {k: k.replace("_", " ").replace("?", "") for k in labels},
        }
    }
    items = [
        {
            "state": r["text"],
            "questions": json.dumps(question),
            "gold": json.dumps({"intent": {"type": "choice", "label": r["label"],
                                           "probabilities": {k: float(k == r["label"]) for k in labels}}}),
        }
        for r in selected
    ]
    return Dataset.from_list(items)


def evaluate_model(adapter_path: str, datasets: dict[str, Dataset],
                   tokenizer=None, model=None) -> dict[str, dict]:
    own = model is None
    if own:
        model, tokenizer = FastDecisionModel.from_pretrained(
            adapter_path, load_in_4bit=True, local_files_only=True, max_seq_length=4096)
    results = {}
    for name, data in datasets.items():
        start = time.time()
        items, build_report = FastDecisionModel.build_dataset(data, tokenizer, model)
        assert items, f"No items for {name}: {build_report}"
        metrics = FastDecisionModel.evaluate(model, tokenizer, items)
        assert all(math.isfinite(metrics.get(k, float("nan"))) for k in ("accuracy", "ece", "loss")), metrics
        results[name] = {
            "metrics": metrics,
            "build_report": build_report,
            "duration_s": time.time() - start,
        }
        print(f"  {name}: acc={metrics['accuracy']:.4f} ece={metrics['ece']:.4f} "
              f"({len(items)} items, {results[name]['duration_s']:.1f}s)", flush=True)
    if own:
        del model, tokenizer
        gc.collect()
        torch.cuda.empty_cache()
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adapter",         type=Path, required=True)
    parser.add_argument("--data-dir",        type=Path, required=True)
    parser.add_argument("--out",             type=Path, required=True)
    parser.add_argument("--reflex1-adapter", type=Path, help="Before-retrain adapter for comparison")
    parser.add_argument("--typed-revision",  type=str,
                        help="HF revision for typed-decisions; defaults to 'main'")
    args = parser.parse_args()

    args.out.parent.mkdir(parents=True, exist_ok=True)

    # Load typed-decisions training states for overlap exclusion
    typed_rev = args.typed_revision or "main"
    train_typed = load_dataset("LocalLLaMA/typed-decisions", "all", split="train", revision=typed_rev)
    training_states = {" ".join(r["state"].casefold().split()) for r in train_typed}

    # Build benchmark datasets
    print("Loading BANKING77…", flush=True)
    banking77_data, banking77_rev = load_banking77(training_states)

    print("Loading CLINC150…", flush=True)
    clinc150_data, clinc150_rev = load_clinc150(training_states)

    print("Loading typed-decisions test…", flush=True)
    typed_test = load_dataset("LocalLLaMA/typed-decisions", "all", split="test", revision=typed_rev)

    phone_test = load_jsonl(args.data_dir / "test.jsonl")
    phone_calib = load_jsonl(args.data_dir / "calibration.jsonl")

    datasets = {
        "phone_routing_test": phone_test,
        "phone_routing_calib": phone_calib,
        "typed_decisions": typed_test,
        "banking77": banking77_data,
        "clinc150": clinc150_data,
    }

    report = {
        "adapter": str(args.adapter),
        "phone_routing_test_sha256": file_sha256(args.data_dir / "test.jsonl"),
        "typed_revision": typed_rev,
        "banking77_github_revision": banking77_rev,
        "clinc150_hf_sha": clinc150_rev,
        "seed": SEED,
    }

    # Evaluate retrained model
    print(f"\nEvaluating retrained model: {args.adapter}", flush=True)
    report["after"] = evaluate_model(str(args.adapter), datasets)

    # Optionally evaluate before-retrain model for comparison
    if args.reflex1_adapter and args.reflex1_adapter.exists():
        print(f"\nEvaluating before-retrain model: {args.reflex1_adapter}", flush=True)
        report["before"] = evaluate_model(str(args.reflex1_adapter), datasets)

    args.out.write_text(json.dumps(report, indent=2) + "\n")

    # Print summary table
    print(f"\n{'='*70}")
    print(f"Phone routing model evaluation")
    after = report["after"]
    before = report.get("before", {})
    header = f"{'Benchmark':30s}  {'After':>10s}"
    if before:
        header += f"  {'Before':>10s}  {'Δ':>7s}"
    print(header)
    print("-" * (len(header)))
    for name in ["phone_routing_test", "phone_routing_calib", "typed_decisions", "banking77", "clinc150"]:
        a_acc = after.get(name, {}).get("metrics", {}).get("accuracy", float("nan"))
        line = f"  {name:28s}  {a_acc*100:>9.1f}%"
        if before:
            b_acc = before.get(name, {}).get("metrics", {}).get("accuracy", float("nan"))
            delta = a_acc - b_acc
            line += f"  {b_acc*100:>9.1f}%  {delta*100:>+6.1f}pp"
        print(line)
    print(f"\nResults → {args.out}")


if __name__ == "__main__":
    main()
