---
language:
- en
task_categories:
- text-classification
pretty_name: Reflex-1 Phone Routing
size_categories:
- n<1K
configs:
- config_name: default
  data_files:
  - split: train
    path: train.jsonl
---
# Reflex-1 Phone Routing

Private release of the **exact 781-row phone training split** used in the independently trained phone-only model and in the selected combined Reflex-1 model. The combined model also used 781 general examples; those general rows are not part of this phone dataset.

`train.jsonl` is preserved byte-for-byte from the verified GPU training backup. SHA-256: `c01503a65e182b0eb68181fcfcdd0e775c3ea4b9a3039ee9f3abcb4f05d7a2b4`.

## Format

`state` is the user request; `route__label` is one of nine tool routes. `questions` and `gold` preserve the serialized Decision API schema and targets. Other columns preserve source IDs, scenario metadata and human-review provenance. Scenarios were audit metadata; the model was trained on routes, not roughly 40 scenario categories.

All rows derive from project synthetic phone requests reviewed in Argilla by one reviewer with proposed labels visible. Synthetic agreement fields do not establish independent human agreement. Route counts are recorded in `dataset-manifest.json`.

Validation, calibration, historical test and the fresh 180-case test are deliberately not included. `source-manifest.json` preserves the original exporter audit status, split hashes, excluded IDs and review caveats; its provisional status refers to the original full-review audit, not to a newly revised dataset. No rows or labels were silently changed for upload.

Model: https://huggingface.co/cbarkinozer/Reflex-1 (private). This dataset is a phone-route training split, not a complete bundle of all general training data. Public licensing/privacy release decisions remain pending; keep this repository private.
