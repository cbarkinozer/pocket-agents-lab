#!/usr/bin/env python3
"""Fine-tune the Clef decision head on the combined phone + general mixture.

This is one of three independent training runs (phone-only, general-only, combined)
all starting from the same reflex-1 checkpoint. Phone and general data are mixed
at the item level with configurable per-source weights.

Schema note:
  Phone routing and general routing questions have different structures. Both are
  passed through FastDecisionModel.build_dataset, which uses each row's 'questions'
  field. Verify that DecisionTrainer handles mixed question structures before full run.

Validation:
  Validation is combined from both phone and general val sets.
  Phone val accuracy is used for phone-routing retention; general val accuracy is
  used for general-routing retention. Both are logged separately.

Usage:
  cd /path/to/ReflexModels
  python3 /path/to/pocket-agents-lab/tools/train_combined_routing.py \\
      --phone-data-dir   /path/to/pocket-agents-lab/data/phone_routing \\
      --general-data-dir /path/to/pocket-agents-lab/data/general_routing_v1 \\
      --output-dir runs/combined-routing-v1 \\
      --reflex1-adapter experiments/unsloth-qwen35-08b-decision/runs/pod-backup/extracted/checkpoint-60 \\
      --phone-weight 1.0 --general-weight 1.0 \\
      [--smoke]
"""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
import random
import sys
import time
from pathlib import Path

from decision_training_utils import decision_trainer_class, enable_existing_adapter_and_head, check_build_report


def parse_args():
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--phone-data-dir",   type=Path, required=True,
                   help="phone_routing data dir (train/calibration/test.jsonl)")
    p.add_argument("--general-data-dir", type=Path, required=True,
                   help="general_routing_v1 data dir (train/val/calibration.jsonl)")
    p.add_argument("--output-dir",       type=Path, required=True)
    p.add_argument("--reflex1-adapter",  type=Path, default=None)
    p.add_argument("--reflex1-adapter-sha256", type=str, default=None)
    p.add_argument("--adapter-strategy", choices=["resume", "merge"], default="resume")
    p.add_argument("--base-model",       type=str, default="unsloth/Qwen3.5-0.8B")
    p.add_argument("--phone-weight",     type=float, default=1.0,
                   help="Relative sampling weight for phone data (default 1.0)")
    p.add_argument("--general-weight",   type=float, default=1.0,
                   help="Relative sampling weight for general data (default 1.0)")
    p.add_argument("--phone-val-split",  type=int, default=90,
                   help="Phone val rows to carve from train (default 90, 10/route)")
    p.add_argument("--max-steps",        type=int, default=60)
    p.add_argument("--batch-size",       type=int, default=8)
    p.add_argument("--grad-accum",       type=int, default=4)
    p.add_argument("--lr",               type=float, default=2e-4)
    p.add_argument("--lora-r",           type=int, default=16)
    p.add_argument("--lora-alpha",       type=int, default=16)
    p.add_argument("--seed",             type=int, default=3407)
    p.add_argument("--smoke",            action="store_true")
    p.add_argument("--wandb-project",    type=str, default=None)
    p.add_argument("--wandb-run-name",   type=str, default=None)
    return p.parse_args()


def file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, obj: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(obj, indent=2) + "\n")
    tmp.replace(path)


def load_jsonl(path: Path) -> list[dict]:
    return [json.loads(l) for l in path.read_text().splitlines() if l.strip()]


def carve_validation(rows: list[dict], val_size: int, seed: int) -> tuple[list[dict], list[dict]]:
    """Route-stratified carve — same logic as train_phone_routing.py."""
    rng = random.Random(seed)
    by_route: dict[str, list[dict]] = collections.defaultdict(list)
    for r in rows:
        by_route[r["route__label"]].append(r)
    routes = sorted(by_route.keys())
    per_route = val_size // len(routes)
    val_rows, keep_rows = [], []
    for route in routes:
        pool = list(by_route[route])
        rng.shuffle(pool)
        val_rows.extend(pool[:per_route])
        keep_rows.extend(pool[per_route:])
    return keep_rows, val_rows


def weighted_mix(phone_rows: list[dict], general_rows: list[dict],
                 phone_weight: float, general_weight: float,
                 seed: int) -> tuple[list[dict], dict]:
    """Mix phone and general rows with relative weights.

    The larger source (by weight-scaled size) is kept in full;
    the smaller is sampled to match the desired ratio.
    """
    rng = random.Random(seed)
    phone_target  = round(len(phone_rows) * phone_weight)
    general_target = round(len(general_rows) * general_weight)
    max_target = max(phone_target, general_target)
    phone_scale   = max_target / phone_target   if phone_target > 0 else 0
    general_scale = max_target / general_target if general_target > 0 else 0
    phone_take   = min(len(phone_rows),   round(len(phone_rows) * min(phone_scale, 1.0)))
    general_take = min(len(general_rows), round(len(general_rows) * min(general_scale, 1.0)))
    sampled_phone   = rng.sample(phone_rows, phone_take)
    sampled_general = rng.sample(general_rows, general_take)
    combined = sampled_phone + sampled_general
    rng.shuffle(combined)
    actual = {"phone": phone_take, "general": general_take,
              "phone_weight": phone_weight, "general_weight": general_weight}
    return combined, actual


def count_trainable_params(model) -> tuple[int, int]:
    trainable = sum(p.numel() for p in model.parameters() if p.requires_grad)
    total     = sum(p.numel() for p in model.parameters())
    return trainable, total


def main():
    args = parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)

    run_start = time.time()
    print(f"\n{'='*60}")
    print(f"train_combined_routing  seed={args.seed}  smoke={args.smoke}")
    print(f"  phone-weight={args.phone_weight}  general-weight={args.general_weight}")
    print(f"{'='*60}\n")

    try:
        from unsloth import FastDecisionModel, DecisionTrainer, is_bfloat16_supported
    except ImportError:
        print("ERROR: unsloth not found. pip install unsloth[colab-new]")
        sys.exit(1)

    import math
    from datasets import Dataset
    from transformers import TrainingArguments, TrainerCallback

    class FiniteLossCallback(TrainerCallback):
        def on_log(self, args, state, control, logs=None, **kwargs):
            if logs and state.global_step <= 2:
                loss = logs.get("loss")
                if loss is not None:
                    if not math.isfinite(loss):
                        raise ValueError(f"Non-finite loss at step {state.global_step}: {loss}")
                    print(f"  Step {state.global_step} loss: {loss:.4f} — finite ✓")

    # ── load phone data ───────────────────────────────────────────────────────
    phone_train_raw = load_jsonl(args.phone_data_dir / "train.jsonl")
    phone_calib_raw = load_jsonl(args.phone_data_dir / "calibration.jsonl")
    phone_train_raw, phone_val_raw = carve_validation(
        phone_train_raw, args.phone_val_split, args.seed)

    phone_train_sha = file_sha256(args.phone_data_dir / "train.jsonl")
    phone_calib_sha = file_sha256(args.phone_data_dir / "calibration.jsonl")
    phone_test_sha  = file_sha256(args.phone_data_dir / "test.jsonl")
    print(f"Phone:   {len(phone_train_raw)} train / {len(phone_val_raw)} val (carved)")

    # ── load general data ─────────────────────────────────────────────────────
    general_train_raw = load_jsonl(args.general_data_dir / "train.jsonl")
    general_val_raw   = load_jsonl(args.general_data_dir / "val.jsonl")
    general_calib_raw = load_jsonl(args.general_data_dir / "calibration.jsonl")
    general_train_sha = file_sha256(args.general_data_dir / "train.jsonl")
    print(f"General: {len(general_train_raw)} train / {len(general_val_raw)} val")

    # ── build weighted combined train ────────────────────────────────────────
    combined_train, actual_weights = weighted_mix(
        phone_train_raw, general_train_raw,
        args.phone_weight, args.general_weight, args.seed)
    print(f"\nCombined train: {len(combined_train)} rows {actual_weights}")

    # Combined val: both phone and general (evaluated separately below)
    combined_val = phone_val_raw + general_val_raw
    combined_calib = phone_calib_raw + general_calib_raw

    # Save val manifest
    val_manifest_path = args.output_dir / "val-manifest.jsonl"
    val_manifest_path.write_text(
        "\n".join(json.dumps(r) for r in combined_val) + "\n")
    val_sha = file_sha256(val_manifest_path)

    # ── verify reflex-1 adapter ───────────────────────────────────────────────
    reflex1_adapter_sha = None
    if args.reflex1_adapter:
        adapter_sf = args.reflex1_adapter / "adapter_model.safetensors"
        reflex1_adapter_sha = file_sha256(adapter_sf)
        if args.reflex1_adapter_sha256 and reflex1_adapter_sha != args.reflex1_adapter_sha256:
            raise ValueError(f"SHA-256 mismatch for adapter_model.safetensors")
        print(f"Reflex-1 adapter: {reflex1_adapter_sha[:16]}… verified")

    # ── load model ────────────────────────────────────────────────────────────
    if args.reflex1_adapter and args.adapter_strategy == "resume":
        model, tokenizer = FastDecisionModel.from_pretrained(
            model_name=str(args.reflex1_adapter),
            max_seq_length=2048, dtype=None, load_in_4bit=True)
        n_enabled = enable_existing_adapter_and_head(model)
        checkpoint_source = str(args.reflex1_adapter)
        print(f"RESUME: enabled gradients on {n_enabled} tensors")
    elif args.reflex1_adapter and args.adapter_strategy == "merge":
        from peft import PeftModel
        model, tokenizer = FastDecisionModel.from_pretrained(
            model_name=args.base_model, max_seq_length=2048, dtype=None, load_in_4bit=True)
        model = PeftModel.from_pretrained(model, str(args.reflex1_adapter)).merge_and_unload()
        model = FastDecisionModel.get_peft_model(
            model, r=args.lora_r, lora_alpha=args.lora_alpha, lora_dropout=0,
            use_gradient_checkpointing="unsloth", random_state=args.seed)
        checkpoint_source = f"merged:{args.base_model}+{args.reflex1_adapter}"
    else:
        model, tokenizer = FastDecisionModel.from_pretrained(
            model_name=args.base_model, max_seq_length=2048, dtype=None, load_in_4bit=True)
        model = FastDecisionModel.get_peft_model(
            model, r=args.lora_r, lora_alpha=args.lora_alpha, lora_dropout=0,
            use_gradient_checkpointing="unsloth", random_state=args.seed)
        checkpoint_source = args.base_model

    trainable_params, total_params = count_trainable_params(model)

    # ── build items ──────────────────────────────────────────────────────────
    train_items, tr_report = FastDecisionModel.build_dataset(
        Dataset.from_list(combined_train), tokenizer, model)
    val_items, val_report   = FastDecisionModel.build_dataset(Dataset.from_list(combined_val), tokenizer, model)
    calib_items, calib_report = FastDecisionModel.build_dataset(Dataset.from_list(combined_calib), tokenizer, model)

    # Separate phone val items for per-domain logging
    phone_val_items, phone_val_report = FastDecisionModel.build_dataset(Dataset.from_list(phone_val_raw), tokenizer, model)
    general_val_items, general_val_report = FastDecisionModel.build_dataset(Dataset.from_list(general_val_raw), tokenizer, model)

    print(f"Items: {len(train_items)} train / {len(val_items)} combined val "
          f"(skipped {tr_report.get('skipped', 0)})")

    check_build_report(train_items, tr_report, "train")
    check_build_report(val_items, val_report, "validation")
    check_build_report(calib_items, calib_report, "calibration")
    check_build_report(phone_val_items, phone_val_report, "phone validation")
    check_build_report(general_val_items, general_val_report, "general validation")

    if args.smoke:
        train_items = train_items[:32]
        val_items   = val_items[:16]
        calib_items = calib_items[:16]
        phone_val_items = phone_val_items[:16]
        general_val_items = general_val_items[:16]

    # ── baseline ─────────────────────────────────────────────────────────────
    before_val = FastDecisionModel.evaluate(model, tokenizer, val_items)
    before_phone_val = FastDecisionModel.evaluate(model, tokenizer, phone_val_items)
    before_general_val = FastDecisionModel.evaluate(model, tokenizer, general_val_items)
    print(f"Baseline: combined={before_val.get('accuracy','?'):.4f} "
          f"phone={before_phone_val.get('accuracy','?'):.4f} "
          f"general={before_general_val.get('accuracy','?'):.4f}")
    write_json(args.output_dir / "metrics_before_val.json",
               {"combined": before_val, "phone": before_phone_val, "general": before_general_val})

    # ── W&B ──────────────────────────────────────────────────────────────────
    report_to = "none"
    wandb_run_id = wandb_run_url = None
    if not args.smoke and args.wandb_project:
        try:
            import wandb
            run_name = args.wandb_run_name or f"combined-routing-v1-lora{args.lora_r}-s{args.seed}"
            wandb.init(project=args.wandb_project, name=run_name,
                       config=vars(args), reinit=True)
            wandb_run_id  = wandb.run.id
            wandb_run_url = wandb.run.get_url()
            report_to = "wandb"
        except Exception as e:
            print(f"W&B init failed ({e}).")

    # ── train ─────────────────────────────────────────────────────────────────
    effective_batch = args.batch_size * args.grad_accum
    RoutingDecisionTrainer = decision_trainer_class(DecisionTrainer, FastDecisionModel)
    trainer = RoutingDecisionTrainer(
        model=model,
        processing_class=tokenizer,
        train_dataset=train_items,
        eval_dataset=val_items,
        args=TrainingArguments(
            per_device_train_batch_size=2 if args.smoke else args.batch_size,
            gradient_accumulation_steps=1 if args.smoke else args.grad_accum,
            warmup_steps=0 if args.smoke else 10,
            max_steps=2 if args.smoke else args.max_steps,
            learning_rate=args.lr,
            fp16=not is_bfloat16_supported(),
            bf16=is_bfloat16_supported(),
            logging_steps=1,
            eval_strategy="steps",
            eval_steps=2 if args.smoke else 20,
            save_strategy="steps",
            save_steps=2 if args.smoke else 20,
            load_best_model_at_end=True,
            metric_for_best_model="eval_accuracy",
            greater_is_better=True,
            optim="adamw_8bit",
            weight_decay=0.01,
            lr_scheduler_type="cosine",
            seed=args.seed,
            output_dir=str(args.output_dir / "checkpoints"),
            report_to=report_to,
        ),
        callbacks=[FiniteLossCallback()],
    )
    train_start = time.time()
    trainer.train()
    train_elapsed = time.time() - train_start

    # ── post-training eval ────────────────────────────────────────────────────
    after_val         = FastDecisionModel.evaluate(model, tokenizer, val_items)
    after_phone_val   = FastDecisionModel.evaluate(model, tokenizer, phone_val_items)
    after_general_val = FastDecisionModel.evaluate(model, tokenizer, general_val_items)
    print(f"Post-training: combined={after_val.get('accuracy','?'):.4f} "
          f"phone={after_phone_val.get('accuracy','?'):.4f} "
          f"general={after_general_val.get('accuracy','?'):.4f}")
    write_json(args.output_dir / "metrics_after_val.json",
               {"combined": after_val, "phone": after_phone_val, "general": after_general_val})

    # ── calibration ──────────────────────────────────────────────────────────
    calib_eval = FastDecisionModel.evaluate(model, tokenizer, calib_items)
    write_json(args.output_dir / "metrics_calib_pre.json", calib_eval)

    calibration_ok = False
    calibrate_fn = getattr(FastDecisionModel, "calibrate", None)
    if calibrate_fn is None:
        calib_config = {"calibrated": False, "method": "NOT_CALIBRATED",
                        "reason": "FastDecisionModel.calibrate() not found", "temperature": 1.0}
        print("ERROR: calibrate() not available — checkpoint is NOT calibrated")
    else:
        try:
            calib_config = calibrate_fn(model, tokenizer, calib_items)
            calib_config["calibrated"] = True
            calibration_ok = True
        except Exception as e:
            calib_config = {"calibrated": False, "method": "NOT_CALIBRATED",
                            "reason": str(e), "temperature": 1.0}
            print(f"ERROR: calibrate() failed — NOT calibrated: {e}")

    write_json(args.output_dir / "calibration_config.json", calib_config)

    # ── save adapter ──────────────────────────────────────────────────────────
    adapter_dir = args.output_dir / "adapter"
    model.save_pretrained(str(adapter_dir))
    tokenizer.save_pretrained(str(adapter_dir))

    if report_to == "wandb":
        try:
            import wandb; wandb.finish()
        except Exception:
            pass

    manifest = {
        "run_completed": True,
        "dataset": "combined_routing_v1",
        "checkpoint_source": checkpoint_source,
        "adapter_strategy": args.adapter_strategy if args.reflex1_adapter else "scratch",
        "reflex1_adapter_sha256": reflex1_adapter_sha,
        "phone_train_sha256": phone_train_sha,
        "phone_calib_sha256": phone_calib_sha,
        "phone_test_sha256": phone_test_sha,
        "general_train_sha256": general_train_sha,
        "val_sha256": val_sha,
        "seed": args.seed,
        "actual_weights": actual_weights,
        "max_steps": 2 if args.smoke else args.max_steps,
        "effective_batch_size": 2 if args.smoke else effective_batch,
        "learning_rate": args.lr,
        "trainable_params": trainable_params,
        "training_elapsed_s": round(train_elapsed, 1),
        "wandb_run_id": wandb_run_id,
        "wandb_run_url": wandb_run_url,
        "metrics": {
            "before_val_combined": before_val,
            "before_val_phone": before_phone_val,
            "before_val_general": before_general_val,
            "after_val_combined": after_val,
            "after_val_phone": after_phone_val,
            "after_val_general": after_general_val,
            "calib_pre": calib_eval,
        },
        "calibration_config": calib_config,
        "calibration_ok": calibration_ok,
        "checkpoint_selection_metric": "eval_accuracy on combined val (greater is better)",
        "retention_note": "Phone val accuracy is the primary phone retention signal. "
                          "General val accuracy is the general retention signal. "
                          "Benchmark test scores are for final reporting only.",
        "smoke": args.smoke,
    }
    write_json(args.output_dir / "run-manifest.json", manifest)

    total_elapsed = time.time() - run_start
    print(f"\n{'='*60}")
    print(f"COMBINED TRAINING COMPLETE  ({total_elapsed:.0f}s total)")
    print(f"  Phone val:   {before_phone_val.get('accuracy',0):.4f} → {after_phone_val.get('accuracy',0):.4f}")
    print(f"  General val: {before_general_val.get('accuracy',0):.4f} → {after_general_val.get('accuracy',0):.4f}")
    print(f"  Calibrated:  {'YES' if calibration_ok else 'NO'}")
    print(f"{'='*60}\n")


if __name__ == "__main__":
    main()
