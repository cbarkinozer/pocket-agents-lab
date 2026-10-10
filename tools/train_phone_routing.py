#!/usr/bin/env python3
"""Fine-tune the Clef decision head on phone routing using phone_routing_v1 data.

This script adapts the Qwen3_5_08B-Decision notebook training flow to the
phone-specific routing dataset. Run on a GPU pod with the unsloth environment
from the reflex-1 experiment.

Prerequisites (same pod / image as reflex-1):
  pip install unsloth[colab-new]@git+https://github.com/unslothai/unsloth.git
  pip install "unsloth_zoo>=2025.3" datasets huggingface_hub

Dataset:  data/phone_routing/{train,calibration,test}.jsonl
Output:   runs/phone-routing-v1/

Usage:
  cd /path/to/ReflexModels  # or wherever the clef training environment is
  python3 /path/to/pocket-agents-lab/tools/train_phone_routing.py \\
      --data-dir /path/to/pocket-agents-lab/data/phone_routing \\
      --output-dir runs/phone-routing-v1 \\
      [--smoke]  # 2 steps for a quick sanity check
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from pathlib import Path


# ─── argument parsing ────────────────────────────────────────────────────────

def parse_args():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--data-dir",    type=Path, required=True, help="Path to phone_routing JSONL dir")
    p.add_argument("--output-dir",  type=Path, required=True, help="Where to write the trained adapter")
    p.add_argument("--base-model",  type=str,  default="Qwen/Qwen3.5-0.8B",
                   help="HuggingFace model ID for the backbone")
    p.add_argument("--max-steps",   type=int,  default=60)
    p.add_argument("--seed",        type=int,  default=3407)
    p.add_argument("--smoke",       action="store_true", help="Run only 2 steps on 32 examples")
    return p.parse_args()


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    args = parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)

    # ── imports (heavy, GPU-required) ────────────────────────────────────────
    try:
        from unsloth import FastDecisionModel
        from unsloth import DecisionTrainer
    except ImportError:
        print("ERROR: unsloth not found. Install with: pip install unsloth[colab-new]")
        sys.exit(1)

    from datasets import Dataset
    from transformers import TrainingArguments

    # ── load data ────────────────────────────────────────────────────────────
    train_path = args.data_dir / "train.jsonl"
    calib_path = args.data_dir / "calibration.jsonl"
    test_path  = args.data_dir / "test.jsonl"

    def load_jsonl(path: Path) -> Dataset:
        records = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
        return Dataset.from_list(records)

    train_dataset = load_jsonl(train_path)
    calib_dataset = load_jsonl(calib_path)
    test_dataset  = load_jsonl(test_path)

    print(f"Loaded: {len(train_dataset)} train, {len(calib_dataset)} calibration, {len(test_dataset)} test")
    print(f"Train SHA256: {file_sha256(train_path)[:16]}…")
    print(f"Calib SHA256: {file_sha256(calib_path)[:16]}…")
    print(f"Test  SHA256: {file_sha256(test_path)[:16]}…")

    # ── load model ───────────────────────────────────────────────────────────
    model, tokenizer = FastDecisionModel.from_pretrained(
        model_name=args.base_model,
        max_seq_length=2048,
        dtype=None,  # auto
        load_in_4bit=True,
    )
    model = FastDecisionModel.get_peft_model(
        model,
        r=16,
        lora_alpha=16,
        lora_dropout=0,
        use_gradient_checkpointing="unsloth",
        random_state=args.seed,
    )

    # ── build training items ─────────────────────────────────────────────────
    train_items, train_report = FastDecisionModel.build_dataset(train_dataset, tokenizer, model)
    calib_items, _            = FastDecisionModel.build_dataset(calib_dataset, tokenizer, model)
    test_items,  _            = FastDecisionModel.build_dataset(test_dataset,  tokenizer, model)

    print(f"Training items: {len(train_items)} (skipped {train_report.get('skipped', 0)})")

    if args.smoke:
        train_items = train_items[:32]
        calib_items = calib_items[:16]

    # ── baseline eval ────────────────────────────────────────────────────────
    before = FastDecisionModel.evaluate(model, tokenizer, calib_items)
    print(f"Before training (calibration): accuracy={before.get('accuracy', '?'):.4f}")

    (args.output_dir / "metrics_before.json").write_text(json.dumps(before, indent=2))

    # ── train ────────────────────────────────────────────────────────────────
    trainer = DecisionTrainer(
        model=model,
        processing_class=tokenizer,
        train_dataset=train_items,
        eval_dataset=calib_items,
        args=TrainingArguments(
            per_device_train_batch_size=2 if args.smoke else 8,
            gradient_accumulation_steps=4,
            warmup_steps=0 if args.smoke else 10,
            max_steps=2 if args.smoke else args.max_steps,
            learning_rate=2e-4,
            fp16=not _is_bf16_supported(),
            bf16=_is_bf16_supported(),
            logging_steps=1,
            optim="adamw_8bit",
            weight_decay=0.01,
            lr_scheduler_type="cosine",
            seed=args.seed,
            output_dir=str(args.output_dir / "checkpoints"),
        ),
    )
    trainer.train()

    # ── holdout calibration eval ─────────────────────────────────────────────
    after_calib = FastDecisionModel.evaluate(model, tokenizer, calib_items)
    print(f"After training (calibration): accuracy={after_calib.get('accuracy', '?'):.4f}")
    (args.output_dir / "metrics_after_calib.json").write_text(json.dumps(after_calib, indent=2))

    # ── test eval (held-out, read-only) ──────────────────────────────────────
    after_test = FastDecisionModel.evaluate(model, tokenizer, test_items)
    print(f"After training (TEST held-out): accuracy={after_test.get('accuracy', '?'):.4f}")
    (args.output_dir / "metrics_after_test.json").write_text(json.dumps(after_test, indent=2))

    # ── save adapter ─────────────────────────────────────────────────────────
    adapter_dir = args.output_dir / "adapter"
    model.save_pretrained(str(adapter_dir))
    tokenizer.save_pretrained(str(adapter_dir))
    print(f"Adapter saved to {adapter_dir}")

    # ── write run manifest ────────────────────────────────────────────────────
    manifest = {
        "dataset": "phone_routing_v1",
        "base_model": args.base_model,
        "train_sha256": file_sha256(train_path),
        "calib_sha256": file_sha256(calib_path),
        "test_sha256":  file_sha256(test_path),
        "metrics_before_calib": before,
        "metrics_after_calib":  after_calib,
        "metrics_after_test":   after_test,
        "max_steps": 2 if args.smoke else args.max_steps,
        "smoke": args.smoke,
        "seed": args.seed,
    }
    (args.output_dir / "manifest.json").write_text(json.dumps(manifest, indent=2))
    print(f"\n=== TRAINING COMPLETE ===")
    print(f"Before (calib): {before.get('accuracy', 0):.4f}")
    print(f"After  (calib): {after_calib.get('accuracy', 0):.4f}")
    print(f"After  (TEST):  {after_test.get('accuracy', 0):.4f}")


def _is_bf16_supported() -> bool:
    try:
        import torch
        return torch.cuda.is_bf16_supported()
    except Exception:
        return False


if __name__ == "__main__":
    main()
