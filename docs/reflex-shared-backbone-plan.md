# Future experiment: one backbone for decisions and text

Recorded: 2026-10-17. Status: idea and experiment plan, not implemented. This note preserves the discussion after the Reflex-1 Android integration.

## Goal and current recommendation

Keep one backbone loaded and choose either the decision head for tool routing or the vocabulary output head for text generation. Avoid duplicate backbone storage and model reloads while retaining routing accuracy and useful generated answers.

For the A32 today, retain the tested sequential approach: keep Reflex ready, return deterministic factual tool summaries when sufficient, and stop Reflex before loading Qwen when prose is needed. Unload Qwen before the next routing request. No new runtime project or GPU training run is authorized by this note alone.

Keeping both models resident is a separate possible optimization, not an established improvement. Measure its complete-turn latency and combined process RAM before accepting the extra memory pressure. Do not assume it cannot fit, or that Android will keep both processes alive indefinitely.

## Three configurations to compare

| Configuration | Expected benefit | Main uncertainty |
| --- | --- | --- |
| Current sequential loading | Limits simultaneous model-weight memory | Switching overhead on requests needing prose |
| Both models remain loaded | Removes loading/unloading between stages | Combined RAM, Android reclamation and sustained stability |
| Shared backbone with decision/text modes | Removes duplicate backbone allocation and model reloads | Runtime correctness, text quality after decision fine-tuning, context/activation memory |

Routing itself currently takes roughly 25–31 seconds depending on the recorded run/protocol. Avoiding reloads only removes switching overhead; it does not eliminate the backbone forward pass. These historical timings are not guarantees for a new runtime.

## What the runtime investigation established

In the inspected llama.cpp revisions, Clef reuses Qwen3.5 tensor loading but always executes a decision graph. It returns option scores through the embedding-output path instead of vocabulary logits, and its decision path omits the persistent memory needed for ordinary autoregressive generation.

The Qwen loader can tie vocabulary output weights to token embeddings. Therefore lack of a separate output-weight tensor in our text-only GGUF does not by itself prove text generation is impossible. The missing capability is a validated text graph and appropriate inference state, not merely a renamed architecture tag.

Relevant implementation: src/models/clef.cpp, src/models/qwen35.cpp and the architecture-specific memory creation in src/llama-model.cpp. Inspected packaged server revision: f0c41e0168dfd4b5ef72b21d1a311b24cc7a894a; isolated JNI source revision: aa94f2086147cd4bfbcff818ce998dffce5fcb39. Recheck before implementation rather than assuming future upstream behavior is identical.

## Two shared-backbone designs

1. **First prototype: Reflex's fine-tuned backbone serves both heads.** Add a normal text-generation path to the loaded model. This avoids adapter on/off complexity, but text quality is unmeasured: decision-only fine-tuning may have changed it. This is the recommended first experiment.
2. **Later prototype: original Qwen backbone plus switchable routing LoRA and decision head.** Use the original base for prose; activate the trained LoRA and decision head for routing. This requires compatible unmerged adapter/head exports and runtime support. The merged Reflex GGUF alone cannot recover untouched base weights by turning off its head. Prefer supported on-the-fly LoRA operations over repeatedly adding/subtracting updates into quantized tensors.

A single downloadable bundle could contain all required tensors and configuration, but file packaging alone cannot implement the two execution modes. The runtime must explicitly support them.

## Suggested implementation order

- [ ] Finish measuring the existing app path; preserve this tested fallback.
- [ ] On the Mac, build a minimal shared-backbone prototype using Reflex weights for both modes.
- [ ] Add explicit per-request decision/text mode selection and vocabulary-logit output for text mode.
- [ ] Implement/reset the appropriate attention and recurrent state. Use separate/reset mode-specific contexts; they may be allocated sequentially to limit memory. Do not reuse decision-prompt state as chat history without proving equivalence.
- [ ] Preserve the exact decision prompt/schema/calibration and the correct text chat template.
- [ ] Check decision predictions/probabilities against the frozen reference and check text quality against ordinary Qwen. Use validation/development data for design choices; reserve unseen final evidence for new improvement claims.
- [ ] Stress repeated decision → text → decision transitions, cancellations, cleanup and errors. Check for state leakage, crashes and growing memory use.
- [ ] Port to A32 only after desktop correctness is demonstrated.
- [ ] Compare all three configurations on the same device, workload and sustained-run conditions.
- [ ] If Reflex's backbone has unacceptable text regressions, investigate the switchable-LoRA design or joint training with a text-generation preservation objective. Do not assume retraining is required before testing the existing weights.

## Evaluation and acceptance

Record routing accuracy/macro-F1, probability drift/calibration, final-answer correctness, complete assistant-turn latency (median/p95), load/switch time separately, combined app/server PSS, starting/peak temperatures and process survival. Include both tool-only and prose-requiring requests. Use repeated or interleaved treatments and consistent threads, quantization, prompt policy and thermal conditions. Report context/activation overhead instead of equating parameter bytes to total RAM.

Accept a new default only after it preserves useful routing and text quality and demonstrates a worthwhile latency/memory tradeoff on A32. No particular savings or implementation deadline has been established.

## Alphabet-generation idea considered

Repeatedly asking the decision head to select A–Z plus an end marker is technically conceivable, but Reflex was not trained for next-character prediction. It would also repeatedly perform expensive inference rather than efficiently reuse a normal language-model cache. At an illustrative 25 seconds per choice, 60 characters would take 25 minutes; that is not a measured character-generation benchmark. Do not prioritize this approach over restoring the normal text-generation path.

## Existing integration evidence

See [application integration](reflex-1-app-integration.md) and [A32 functional smoke](../benchmarks/reflex-app-integration/2026-10-17/README.md). The tested sequential path passed 104 JVM tests, real decision/text/decision switching and a visible live-battery request. These are integration checks, not new published benchmark scores.
