# reflex-1 next steps

Last updated: 2026-10-10. Benchmarks are complete (reflex-1 50/50, Qwen baseline
running). Return here before any retraining or Kotlin integration work.

## 1 — Before retraining (blockers)

- [ ] Audit battery/phone_health_check label boundary in training data.
      The model routes 100% of battery cases to phone_health_check. This is a
      labeling issue, not a capacity problem. Check whether battery-only queries
      appear under the phone_health_check label in the training set.

- [ ] Create a held-out eval split BEFORE touching training data.
      The existing 50 cases are the development suite (used during design).
      Do not use them as evidence of improvement after retraining — they will
      overfit. Reserve a separate split now.

## 2 — New training dataset

- [ ] Build dataset from HuggingFace text classification datasets (mix of
      existing intent/routing datasets relevant to the 9-route taxonomy).

- [ ] Or synthesize ~100+ queries per category (9 categories × 100 = ~900 min).
      Key categories needing more negative examples:
        - battery vs phone_health_check (single-category vs multi-category)
        - answer (general knowledge with zero phone context)
        - device vs phone_health_check (multi-attribute ≠ multi-category)

- [ ] Keep the 50 development cases as a fixed eval — do not include them in
      training data under any circumstance.

## 3 — Fine-tuning targets (in priority order)

- [ ] Battery category (0/10 raw) — fix label boundary, add clean battery-only
      training examples clearly separated from health_check.

- [ ] Answer category (0/10 raw, rescued only by guard) — add strong negatives:
      general-knowledge queries must never reach a phone tool.

- [ ] phone_health_check over-prediction on device/storage — tighten the
      training criterion: health_check = two or more live tool categories,
      NOT multiple attributes of the same tool.

## 4 — Accuracy target before Kotlin integration

- [ ] Raw accuracy ≥ 80% on the 3 development benchmarks before wiring into
      the app. At 48% raw the decision model is worse than the existing JNI
      Qwen path (82% historical).

## 5 — Kotlin integration (do AFTER accuracy target is met)

- [ ] Implement DecisionHttpClient : AgentDecisionSelector
      POST /v1/systemone → parse response.answers.route.choice → return
      AgentSelection. Wire into AgentBackend via the existing decisionSelector
      constructor parameter.

- [ ] Add server lifecycle management to MainActivity: start llama-server with
      the Clef GGUF on app open, health-poll before first use, stop on destroy.
      Use the runtime already installed at /data/local/tmp/reflex-runtime.

- [ ] Build and run AgentBackendTest on device (requires JDK + Android SDK).
      The test for decision routing skipping generation is written but unrun.

- [ ] Integration path: decision model handles routing only (~25 s); existing
      Qwen JNI path handles text generation. These are sequential, not
      simultaneous — total turn time is routing + generation, not instead of.

## 6 — Longer-term: one-backbone dual-graph architecture

This is a separate engineering project, not a prerequisite for the Kotlin
integration above. Do not start until accuracy and integration are validated.

- [ ] Runtime changes needed in llama.cpp (Clef fork):
        1. One loaded tensor set shared between decision and text paths
        2. Decision graph (head, no vocabulary logits, zero output tokens)
        3. Text graph (vocabulary logits, KV cache, recurrent memory)
        4. Separate contexts with explicit resets between paths
        5. Switching validation: confirm both paths work and context is clean

- [ ] Merged backbone prose quality test: if reusing reflex-1 weights for text
      generation, measure its output quality first. It has not been tested.

- [ ] LoRA sidecar path (alternative): use original Qwen base for text,
      43 MB adapter_model.safetensors for routing. Requires portable LoRA
      conversion and validated enable/disable in the runtime.

## 7 — Guard / relevance filter

- [ ] Remove or redesign isDecisionRelevant once the model is accurate enough.
      The current keyword-substring guard caused 6 regressions in the Oct run.
      It is not safe for production use. It exists only to patch a bad model.

## Known experiment constraints (do not forget)

- Do NOT publish reflex-1, push weights to HuggingFace, or log eval runs to W&B
  until further experiments are complete.
- Do NOT retrain on the 50-case development suite and call it held-out evidence.
- Do NOT claim the Kotlin AgentDecisionSelector is a complete adapter integration
  — it is a code seam only; the app has not been rebuilt with it.
- The Clef GGUF cannot do chat completions (no vocabulary logits). Do not swap
  it in place of the original Qwen GGUF for text generation without testing.
- --no-repack is required for Clef. Do not remove this flag.

## Benchmark artifacts (preserve locally)

  benchmarks/agent-routing/2026-10-10-reflex-1-a32/
    reflex-1-results.json       complete, 50/50
    qwen-results.json           in progress at time of commit
    comparison-report.md        partial (reflex-1 only, Qwen pending)
    config.json, source-manifest.json, device-snapshot.txt
    *.log                       server and benchmark logs

  ReflexModels pod-backup (local, no GPU needed for phone tests):
    experiments/unsloth-qwen35-08b-decision/runs/pod-backup/
