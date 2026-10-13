# Phone-routing reviewed-only GPU pilot

The frozen input is `data/prepared/phone-routing-human-only-v2-20261013-r2`:
200 reviewed train, 90 reviewed validation, 90 reviewed calibration, and 88
historical test rows. Training must use train/validation/calibration only. The
historical test is for one final comparison after checkpoint selection; a new
independently phrased test cohort is still needed for generalization claims.

The CPU baseline report is
`runs/phone-routing-reviewed-v2-cpu-baseline-20261013-r2/report.json`.
Character TF-IDF plus logistic regression scored 0.800 validation accuracy and
0.806 macro-F1. The word TF-IDF baseline scored 0.700 accuracy and 0.692
macro-F1. The character model recovered only 4/10 `answer` examples and
misrouted six general-knowledge questions to `get_device_info`; this is the
main error slice to compare after fine-tuning. These are routing baselines,
not Clef/Reflex-1 results.

## GPU inputs

`runs/phone-routing-reviewed-v2-gpu-inputs-20261013.zip` contains the reviewed
data, the two training source files, the prior pod setup script, and this
handoff. It contains no credentials or model weights. Verify its SHA-256
against `gpu-inputs-sha256.txt` after upload. The Reflex-1 starting checkpoint
is currently absent from this laptop; recover it from the previous verified
backup before a resume run. Required files in one checkpoint directory:

- `adapter_model.safetensors` SHA-256
  `2eef23b07522d4a371f6e6a5e4e44bdebe29d97b97e066b1ad5e09034ac91cd7`
- `joint_head.safetensors` SHA-256
  `6904172944df6d142c490fadc9beae6b38d83401b5ee71703b1866672094bd6b`
- `adapter_config.json` SHA-256
  `397aeb36809aa70abc5896502d1edd0ccc61efc50260f37a61ed0a614f8ea1c2`

## Run on the GPU host

Unzip the input bundle under `/workspace/reflex-decision`. Install and verify a
CUDA-capable Unsloth environment using the included `setup_pod.sh`, adjusting
its hardcoded workspace path if necessary. Record the exact installed package
versions, GPU model/VRAM, setup duration, provider rate and instance ID.

From `/workspace/reflex-decision`, after putting the verified checkpoint in
`checkpoint-60/`:

```bash
sha256sum checkpoint-60/adapter_model.safetensors checkpoint-60/joint_head.safetensors checkpoint-60/adapter_config.json
.venv/bin/python tools/train_phone_routing.py \
  --data-dir data/prepared/phone-routing-human-only-v2-20261013-r2 \
  --output-dir runs/phone-routing-reviewed-v2-smoke \
  --reflex1-adapter checkpoint-60 \
  --reflex1-adapter-sha256 2eef23b07522d4a371f6e6a5e4e44bdebe29d97b97e066b1ad5e09034ac91cd7 \
  --smoke
```

If the smoke passes, run the same command without `--smoke`, with a new output
directory. Save stdout/stderr, model configuration, baseline validation result,
training metrics, calibration, adapter/head and checkpoints. Do not log smoke
or evaluation to W&B; a W&B run is optional for actual training only. The
training script checks split isolation and starts from the existing Reflex-1
adapter/head. Do not replace the checkpoint with a different LoRA.

The first GPU run should be treated as a pilot. Compare its validation
macro-F1 and per-route errors against the CPU baseline before using the
historical test. Evaluate calibration on the separate 90-row calibration split.
Preserve raw metrics and verify downloaded artifact hashes before releasing the
instance. Follow ReflexModels `EXPERIMENT_CLOSEOUT.md` for cost and backup
records. Do not publish Reflex-1 weights.
