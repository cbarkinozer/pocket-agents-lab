---
base_model: unsloth/Qwen3.5-0.8B
language:
- en
tags:
- intent-classification
- decision-model
- gguf
- lora
---
# Reflex-1

Private research release for desktop/mobile verification. Fixed combined checkpoint 100 from the 120-step independent Qwen3.5-0.8B run.

## Files

- `adapter/`: LoRA adapter, trained joint decision head, tokenizer/processor and decision/calibration configs. Requires base `unsloth/Qwen3.5-0.8B`, revision `23c69c53358a07516b5827588b3fdb12ae78fd65`. Load using Unsloth FastDecisionModel, not an ordinary text-generation PEFT loader alone.
- `gguf/`: Q4_K_M text-model GGUF, Q8_0 projector sidecar, export metadata and inventory. Use a Clef-compatible Decision API runtime, `/v1/systemone`, with `--no-repack`. This export does not support ordinary chat completions.
- `manifest.json`: file hashes and measured scores.

No merged standalone PyTorch weights are included in this initial package: they were not present in the verified training backup. The GGUF is a complete export; the adapter package requires the base model.

## Measured PyTorch results

Fresh 180-case phone test: 91.11% accuracy (164/180), 90.84% macro-F1. Historical phone: 96.59%; official typed decisions: 60.70%; BANKING77: 74.89%; CLINC150: 81.11%. General results measure supervised in-domain adaptation. Phone review was assisted by author suggestions; exact-duplicate exclusion does not establish semantic or pretraining independence.

GGUF parity and A32 latency/memory/battery testing remain pending. This is tool-route classification, not a measured end-to-end tool-execution benchmark. Dataset release remains separate.

Original backbone authors retain their license/attribution. The base model declares Apache-2.0. Data provenance: BANKING77 CC-BY-4.0, CLINC150 CC-BY-3.0, typed-decisions Apache-2.0; phone corpus is project synthetic data with human review. Review release licensing before making this repository public.

## Phone training dataset

The exact 781-row phone training split is available privately at https://huggingface.co/datasets/cbarkinozer/Reflex-1-Phone-Routing . It excludes validation/calibration/test data and does not include the additional 781 general examples used by the combined model.
