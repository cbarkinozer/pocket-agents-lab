# Reviewed phone-routing data v2

## Provisional dataset using current reviews (2026-10-13)

The user elected to stop annotating and keep the remaining train rows unannotated.
`prepare_phone_routing_available.py` builds a provisional dataset from the current
Argilla snapshot. It retains original synthetic labels on unreviewed training rows,
marks them `label_source=synthetic_unreviewed`, uses human-reviewed validation and
calibration, and quarantines unresolved or unclear reviews. The current output is
`data/prepared/phone-routing-available-v2/`. Its manifest records exact
counts, hashes, excluded IDs, and the review snapshot. Rebuild in a new output
directory after any later annotation changes:

```powershell
py -3.11 tools/prepare_phone_routing_available.py `
  --snapshot ../ReflexModels/experiments/decision-followups/review-ui/review-snapshot.json `
  --csv ../ReflexModels/experiments/decision-followups/phone-routing-review-sheet.csv `
  --output-dir data/prepared/phone-routing-available-v2-new
```

Validation is selected from rows the user happened to review, so it is a
convenience sample. The old test remains historical. Neither is a new sealed
test of generalization. See `../ReflexModels/experiments/decision-followups/review-ui/CURRENT_AUDIT.md`
for label and scenario findings.

For a pilot trained **only on submitted human reviews**, add
`--training-policy reviewed_only` and use a new output directory. The same
reviewed validation selection is used for either training policy when built
from the same snapshot. The training split then excludes every unreviewed row;
the calibration and historical test splits are unchanged. When 300 train
reviews are submitted, export Argilla again before building this dataset.

The completed 301-review build is
`data/prepared/phone-routing-human-only-v2-20261013-r2/` (200 train, 90
validation, 90 calibration, 88 usable historical test). Its validation choice
covers reviewed scenario categories where there are enough examples and keeps
at least one example of each scenario in training. Three route/scenario
mismatches and unclear/conflicting cases are quarantined rather than silently
relabelled. The detailed balance audit is in `CURRENT_AUDIT.md` in ReflexModels.

The frozen v1 JSONL and historical test remain unchanged. `prepare_phone_routing_reviewed.py`
supports two review policies. `full` requires all 1,080 rows. `targeted` requires
450 prespecified rows: 90 validation, 90 calibration, 90 historical test, and a
20-per-route training audit (180). Unreviewed training rows retain their synthetic
labels and are explicitly marked `label_source=synthetic_unreviewed`.

## Audit now (read-only)

From this repository root on Windows:

```powershell
py -3.11 tools/prepare_phone_routing_reviewed.py `
  --snapshot ../ReflexModels/experiments/decision-followups/review-ui/review-snapshot.json `
  --csv ../ReflexModels/experiments/decision-followups/phone-routing-review-sheet.csv
```

The audit reports missing reviews, changed routes marked `keep`, and unclear or
unresolved cases. Correct those in Argilla and export the snapshot again. Never
modify the frozen v1 files to reflect reviews.

## Faster targeted review

The frozen plan is in
`../ReflexModels/experiments/decision-followups/review-ui/targeted-review-plan.json`.
The corresponding CSV worklist is `targeted-review-worklist.csv` in the same
directory. Review its `pending` rows in Argilla and adjudicate its five flagged
responses. Refresh the worklist after exporting a new snapshot:

```powershell
py -3.11 tools/make_phone_review_worklist.py `
  --snapshot ../ReflexModels/experiments/decision-followups/review-ui/review-snapshot.json `
  --plan ../ReflexModels/experiments/decision-followups/review-ui/targeted-review-plan.json `
  --output new-targeted-review-worklist.csv
```

Run the targeted audit with `--review-policy targeted --plan <plan path>`.
The plan is selected from source IDs before viewing human responses, is balanced
by the original route, and is checked against source data at finalization. Six
training rows that overlap evaluation template groups are quarantined. A review
of 20 training rows per route is a limited audit, not proof that all unreviewed
synthetic labels are correct. Measure correction rates by route and inspect
error types before using this data for a strong quality claim.

## Finalize after the audit passes

```powershell
py -3.11 tools/prepare_phone_routing_reviewed.py `
  --snapshot ../ReflexModels/experiments/decision-followups/review-ui/review-snapshot.json `
  --csv ../ReflexModels/experiments/decision-followups/phone-routing-review-sheet.csv `
  --output-dir data/prepared/phone-routing-reviewed-v2 `
  --finalize
```

The script refuses an existing output directory. It verifies the original
1,080 requests, their IDs, the CSV hash, and the frozen JSONL content hash
(normalizing Windows CRLF). It applies submitted routes to both route label and
`gold`, replaces the question schema with `phone_routing_contract_v2.json`, and
records source and output hashes. It does not silently parse a proposed rewrite
from notes; `rewrite` and `uncertain` must be resolved first. `exclude` removes
the row from the resulting split and is counted in the manifest.

Development rows come from the original 900 training and 90 calibration cases.
They are regrouped into train, explicit validation, and calibration with ten
cases per route in each holdout where the reviewed class counts permit it.
Automatic template groups use conservative text similarity; inspect their
assignments and near-duplicate pairs before claiming split isolation. The
original 90-case test is exported separately as **historical**, not a fresh
sealed test. `train_phone_routing.py` uses `validation.jsonl` when present and
checks ID and template-group separation.

## Remaining evidence gates

- Independently author and review a **new** sealed phone test cohort with hard
  route boundaries and real user phrasing. Do not select training settings on it.
- Audit template families manually. Automatic similarity catches the five known
  cross-split near-duplicate pairs but cannot prove semantic independence.
- Run the unchanged model, simple classifier baseline, and fine-tuned model
  under the same v2 question contract. The prior Qwen and reflex-1 numbers used
  the v1 contract, so they are historical references, not matched v2 baselines.
- Keep runtime question text aligned with v2 at deployment. The current app's
  `DecisionHttpClient.kt` still uses the v1 contract until a v2 model is wired in.
- Save validation error slices, per-route accuracy and macro-F1, paired model
  outcomes, calibration on the separate calibration split, and model artifacts.

For targeted finalization, add `--review-policy targeted --plan
../ReflexModels/experiments/decision-followups/review-ui/targeted-review-plan.json`
to the finalization command. Its manifest records the plan hash and human versus
synthetic label counts per split. Full finalization remains the default.

Finishing the selected annotation is a prerequisite for targeted finalization.
It does not alone complete the new test cohort, baseline comparison, or GPU validation.
