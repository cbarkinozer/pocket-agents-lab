# reflex-1 vs Qwen3.5-0.8B Routing Comparison — Galaxy A32 (2026-10-10)

**Device:** SM-A325F · Android 13 · MT6769V/CT · arm64-v8a · 4 GB RAM
**Runtime:** llama.cpp b11443 (commit f0c41e016) · 4 CPU threads · no GPU · USB power
**Suite:** tool-routing-3-tools-v5 · 50 cases · decision mode vs chat mode
**Caution:** single ordered run; no shuffling. Prompts are the development set, not held-out.
Historical JNI baseline (2026-09-11): 41/50 (82%), mean 53 s, PSS 780,270 KiB — different
protocol (JNI, not HTTP), different runtime; listed for context only.

**reflex-1-Q4_K_M (decision):** complete — 50/50 cases.
**Qwen3.5-0.8B-Q4_K_M (chat):** complete — 50/50 cases.

## Overall Accuracy

| Model               | Mode     | Raw correct | Raw % | Guard correct | Guard % | Complete |
| ------------------- | -------- | ----------- | ----- | ------------- | ------- | -------- |
| reflex-1-Q4_K_M     | decision | 24/50       | 48.0% | 28/50         | 56.0%   | Yes      |
| Qwen3.5-0.8B-Q4_K_M | chat     | 10/50       | 20.0% | 10/50         | 20.0%   | Yes      |

## Per-Category Accuracy

| Category | n  | reflex raw | reflex guard | qwen raw | qwen guard |
| -------- | -- | ---------- | ------------ | -------- | ---------- |
| storage  | 10 | 8/10       | 7/10         | 0/10     | 0/10       |
| device   | 10 | 6/10       | 5/10         | 0/10     | 0/10       |
| battery  | 10 | 0/10       | 0/10         | 0/10     | 0/10       |
| answer   | 10 | 0/10       | 10/10        | 10/10    | 10/10      |
| health   | 10 | 10/10      | 6/10         | 0/10     | 0/10       |

## Relevance Guard Effect

**reflex-1:** 10 rescues (raw wrong → guard correct), 6 regressions (raw correct → guard wrong).
**Qwen:** 0 rescues, 0 regressions.

Guard logic: keyword-substring match transliterated from AgentBackend.kt `isDecisionRelevant`.
Routes without a keyword entry pass through unchanged (phone_health_check uses: 'health',
'condition', 'readiness', 'check everything'). This correctly rescues answer cases where
the model picks phone_health_check, but also wrongly downgrades health-04/06/08/09 whose
prompts lack those exact keywords despite being valid health queries.

  Guard regressions (reflex-1/storage): ['storage-05']
  Guard regressions (reflex-1/device): ['device-04']
  Guard rescues (reflex-1/answer): ['answer-01', 'answer-02', 'answer-03', 'answer-04', 'answer-05', 'answer-06', 'answer-07', 'answer-08', 'answer-09', 'answer-10']
  Guard regressions (reflex-1/health): ['health-04', 'health-06', 'health-08', 'health-09']

## Latency

Decision mode prompt ≈ 400 tokens; chat mode prompt ≈ 1,127 tokens before chat formatting.
Latency difference reflects both the smaller head computation and the shorter input.

| Model               | Mode     | Mean (s) | Median (s) | Min (s) | Max (s) |
| ------------------- | -------- | -------- | ---------- | ------- | ------- |
| reflex-1-Q4_K_M     | decision | 24.9     | 25.3       | 21.5    | 27.8    |
| Qwen3.5-0.8B-Q4_K_M | chat     | 64.8     | 65.4       | 59.7    | 70.4    |

## Memory (PSS)

| Model               | First-case PSS (KiB) | Run-mean PSS (KiB) | Peak PSS (KiB) |
| ------------------- | -------------------- | ------------------ | -------------- |
| reflex-1-Q4_K_M     | 641,079              | 1,062,228          | 1,066,252      |
| Qwen3.5-0.8B-Q4_K_M | 711,081              | 841,594            | 842,002        |

First-case PSS = server PSS before first inference (warm file-cache server load).
Run-mean/peak = PSS after each completed inference.

## Thermal and Cooldown

| Model               | Temp range (C) | MODERATE+ events | Cooldown waits | Total cooldown (s) |
| ------------------- | -------------- | ---------------- | -------------- | ------------------ |
| reflex-1-Q4_K_M     | 31.4–35.4      | 10               | 9              | 446                |
| Qwen3.5-0.8B-Q4_K_M | 35.2–35.6      | 48               | 49             | 2664               |

Battery temperature gate ≤38 C before each case; LIGHT (1) thermal gate also required.
Initial device snapshot showed AP 51.5 C, SKIN 39.7 C — AP temperature not gated.
Cooldown waits shown are inter-case idle time (>1 s); inference latency is separate.

## Architecture Status: One Loaded Backbone

The user's target is **one loaded Qwen backbone with decision routing and text generation**,
switching output path per request without loading two models simultaneously.

### Completed work
- reflex-1-Q4_K_M.gguf: merged Clef model (backbone + decision head) — loads and runs on device.
- `AgentBackend.kt` seam: optional `AgentDecisionSelector` in `select()`; generator remains default.
- `AgentBackendTest.kt`: unit test that decision routing skips generation and final answer uses
  the text generator once.
- Benchmark harness (`benchmark_decision_android.py`): 50-case HTTP runner with guard, telemetry,
  thermal gating, and incremental JSON output.
- Runtime flags tuned: `--no-repack` required for Clef (default repacking hit scheduler assertion).

### What the current GGUF cannot do
- `src/models/clef.cpp` (revision aa94f2086) builds a graph that **always** evaluates the decision
  head. It sets `res->t_embd` to scores and does **not** populate vocabulary logits.
  Standard chat completions will not work on this model file.
- `src/llama-model.cpp` allocates no persistent KV/recurrent memory for Clef.
- A normal llama.cpp LoRA loader does not add the decision head or its scoring graph.

### What shared-backbone support requires
1. **One loaded tensor set** — backbone weights shared between paths (no duplicate load).
2. **Decision graph** — Clef-style head, no vocabulary logits, zero output tokens.
3. **Text graph** — vocabulary logits, KV cache, recurrent memory for the Qwen3.5 architecture.
4. **Separate contexts** — different prompt formatting per path; explicit context resets.
5. **Switching validation** — confirm both paths work and context state is clean after switching.
6. *Optional*: merged backbone prose quality test — if using reflex-1 weights for text generation,
   its quality on open-ended prompts has not been measured.
7. *Optional*: LoRA sidecar path — using the original Qwen base for text and the 43 MB adapter
   only for decisions would require portable LoRA conversion and validated enable/disable behavior.

The Kotlin `AgentDecisionSelector` is a code seam only. The app has not been rebuilt or
switched to reflex-1. Kotlin tests could not be run (JDK/Android SDK absent on this Mac).

## Assessment: Is Further Fine-Tuning Worthwhile?

reflex-1 raw accuracy (48%) is well below the 82% historical Qwen JNI baseline
and below the matched HTTP Qwen baseline (pending). The guard improves the number but
remains a symptom: 6 guard regressions show the guard is not safe either.

**Primary failure mode:** The decision head assigns systematically high probability to
`phone_health_check` across all categories. On battery cases (0/10 raw), every prompt is
routed there despite clear single-category signals. On device and storage, roughly 40% of
prompts leak to phone_health_check. This is a head-calibration issue, not a vocabulary or
context issue — the backbone is the same Qwen weights.

**Why the battery category collapsed:** The RELEVANCE_KEYWORDS for `get_battery_info` include
'battery', 'charge', 'charging', 'power', 'temperature', 'hot', 'cool'. Most prompts contain
these terms, yet the head still prefers phone_health_check with 0.39–0.45 probability against
get_battery_info's 0.10–0.39. The head was trained on `LocalLLaMA/typed-decisions`
(agent_trace_observability, customer_service, invoice_processing, security_incidents) — four
generic business workflows with zero phone-specific labels. `get_battery_info`,
`phone_health_check`, and the other eight phone routes never appeared in training. The Clef
head is generalizing from business-workflow decision boundaries to a phone routing schema it
has never seen, and that generalization is miscalibrated toward phone_health_check. This is a
training domain gap, not a label overrepresentation bug.

**Can fine-tuning fix this?**

Possibly, but with caveats:
- **Do not retrain on this development suite as if it were held-out evidence.** These 50 prompts
  guided early design and would overfit if used as evaluation after further training.
- Reserve a separate held-out split before any further iteration.
- The battery/health boundary issue suggests the training data labels need auditing,
  not just more examples. Check whether battery-only queries appear with phone_health_check
  labels in the training set.
- The Clef head is a separate module added to the frozen backbone. Re-training requires
  the full unsloth + Clef training pipeline from the pod-backup checkpoint.
- If the shared-backbone architecture (single loaded model, two output paths) is the actual
  goal, validate that first before investing further training compute.

**Verdict:** Further fine-tuning makes sense but the prerequisites are not complete.

1. The Qwen HTTP baseline (20%) used `enable_thinking: false`. The September JNI run (82%)
   did not explicitly document thinking on/off. Run Qwen HTTP with thinking enabled first to
   establish the real comparison target. Do not treat 48% vs 20% as the final comparison.

2. There is no phone-specific training data to audit or augment. Building it is a new
   dataset project (~900 examples, 9 routes, phone domain). Only after that dataset exists
   and a held-out eval is reserved does retraining make sense.

3. If Qwen HTTP with thinking enabled also achieves ~82%, the decision model needs to beat
   that at lower latency to justify the architecture. At 2.6× faster (25 s vs 65 s) with
   phone-domain retraining, a 80%+ raw accuracy target is a reasonable goal.

## Caveats and Limitations

1. **Single ordered run.** No shuffling, no repetition, no confidence intervals.
2. **Development suite.** All 50 prompts were used during system design. Not held-out evidence.
3. **Mode mismatch.** Decision mode (≈400 tokens) vs chat mode (≈1,127 tokens). Latency and
   quality differences include both the head architecture and the prompt length.
4. **Runtime mismatch with history.** b11443 (HTTP) vs September JNI baseline. Not directly
   comparable.
5. **Guard is a heuristic.** Keyword-substring matching on the development set produces
   both rescues and regressions. It is not a reliable production filter.
6. **Final answers not tested.** The benchmark measures routing only; no tools are executed.
7. **Kotlin integration incomplete.** AgentDecisionSelector is a seam; app not rebuilt.
8. **PSS includes server overhead.** Not pure model memory.
