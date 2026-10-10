# reflex-1 next steps

Last updated: 2026-10-11. Three-way Qwen benchmark pipeline running autonomously
via wait-then-run.py → run-thinking-on.py. thinking-off: ~10/50 in progress
(~2h remaining). thinking-on-v2 and raw queued. reflex-1 phone routing before-
retrain eval will run automatically after all three. Return here when complete.

## 1 — Establish the real Qwen baseline FIRST (blockers — do before retraining)

**Key architectural finding (2026-10-11):** The September JNI baseline used raw
text completion (no Qwen chat template):
```kotlin
// AgentBackend.kt line 108
generator.generate(GRAMMAR_ROUTE_PREFIX + buildRoutingPrompt(userPrompt), ...)
```
HTTP runs use `/v1/chat/completions` which applies the Qwen chat template
(`<|im_start|>system/user/assistant`). This bypass of the chat template is the
most likely explanation for 82% vs 20%, not thinking configuration.

Three treatments being run via `run-thinking-on.py`:
- `thinking-off-results.json`: chat, thinking=False, grammar (the 20% run)
- `thinking-on-v2-results.json`: chat, thinking=True, no grammar, 1024 tokens
- `raw-results.json`: /v1/completions (NO chat template), grammar — replicates JNI

- [ ] Check `raw-results.json` once `run-thinking-on.py` completes.
      If raw ≈ 82%: chat template is the root cause. The comparison baseline for
      reflex-1 is raw mode, not chat. Thinking on/off is secondary.
      If raw ≈ 20%: the llama.cpp version difference (2a15f3a72 vs f0c41e016) or
      grammar behavior change may be the cause. Investigate further.

- [ ] Compare thinking-on vs thinking-off (both chat mode) to isolate that variable.

- [ ] Do NOT claim reflex-1 (48% raw) beats the baseline until raw-results.json
      is known. If raw = 82%, reflex-1 at 48% is still well below baseline.

## 2 — Audit the training data domain gap ✅ DONE

Confirmed: `LocalLLaMA/typed-decisions` has zero phone-specific labels. All
1,120 records are generic business workflows (observability, customer service,
invoice, security). The phone_health_check bias is a generalization artifact.
There is no phone training data to audit. This section is closed.

## 3 — Build phone-specific training data from scratch ✅ DONE

Dataset built: `data/phone_routing/` — 900 train / 90 calibration / 90 test (held-out).
9 routes × 100/10/10, perfectly balanced. Dev cases excluded. SHA frozen.
Builder: tools/build_phone_routing_dataset.py
Extensions: tools/phone_routing_extra_queries.py
Training script: tools/train_phone_routing.py (unsloth FastDecisionModel)
Before-retrain eval: benchmarks/phone-routing-eval/run-reflex1-before-retrain.py

## 4 — Training and eval targets

- [ ] Retrain Clef head on phone-specific data using the pod-backup checkpoint
      at experiments/unsloth-qwen35-08b-decision/runs/pod-backup/

- [ ] Report on all 4 benchmarks post-retrain:
        1. Phone routing eval (new held-out split) — primary target: ≥80% raw
        2. typed-decisions holdout (80-case calibration set) — should not regress
        3. BANKING77 (44.59% baseline)
        4. CLINC150 (47.11% baseline)
        Note: the "3 benchmarks" from REPORT.md are typed-decisions, BANKING77,
        CLINC150. Phone routing eval is new. Report all four.

- [ ] Only claim "decision model is worthwhile" once raw accuracy on the new
      held-out phone routing eval matches or beats the Qwen HTTP thinking-enabled
      baseline at lower latency (current advantage: ~2.6× faster).

## 5 — Kotlin integration (do AFTER accuracy target met)

- [ ] Implement DecisionHttpClient : AgentDecisionSelector
      POST /v1/systemone → parse response.answers.route.choice → return
      AgentSelection. Wire into AgentBackend via the existing decisionSelector
      constructor parameter.

- [ ] Add server lifecycle management to MainActivity: start llama-server with
      the Clef GGUF on app open, health-poll before first use, stop on destroy.
      Use the runtime already installed at /data/local/tmp/reflex-runtime.

- [ ] Build and run AgentBackendTest on device (requires JDK + Android SDK).
      Test for decision routing skipping generation is written but unrun.

- [ ] Integration path: decision model handles routing only (~25 s); existing
      Qwen JNI path handles text generation. Sequential, not simultaneous —
      total turn time is routing + generation, not instead of.

## 6 — Guard / relevance filter

- [ ] Remove or redesign isDecisionRelevant once the model is accurate enough.
      The current keyword-substring guard caused 6 regressions in the Oct run.
      It rescued 10 answer cases but wrongly downgraded health-04/06/08/09.
      This guard is not production-safe; it exists only to patch a bad model.

## 7 — Longer-term: one-backbone dual-graph architecture

Separate engineering project. Do not start until accuracy and integration validated.

- [ ] Runtime changes needed in llama.cpp (Clef fork):
        1. One loaded tensor set shared between decision and text paths
        2. Decision graph (head, no vocabulary logits, zero output tokens)
        3. Text graph (vocabulary logits, KV cache, recurrent memory)
        4. Separate contexts with explicit resets between paths
        5. Switching validation: confirm both paths work and context is clean
- [ ] Merged backbone prose quality test before using reflex-1 weights for text.
- [ ] LoRA sidecar alternative: original Qwen base for text, 43 MB adapter for
      routing. Requires portable LoRA conversion and validated enable/disable.

## Known experiment constraints (do not forget)

- Do NOT publish reflex-1, push weights to HuggingFace, or log eval runs to W&B.
- Do NOT retrain on the 50-case development suite and call it held-out evidence.
- Do NOT claim the Kotlin AgentDecisionSelector is a complete adapter integration
  — it is a code seam only; the app has not been rebuilt with it.
- The Clef GGUF cannot do chat completions (no vocabulary logits).
- --no-repack is required for Clef. Do not remove this flag.
- Do NOT load both models simultaneously.
- The llama.cpp submodule pre-existing staged deletions — leave untouched.

## Benchmark artifacts (preserve locally)

  benchmarks/agent-routing/2026-10-10-reflex-1-a32/
    reflex-1-results.json       complete, 50/50
    qwen-results.json           complete, 50/50
    comparison-report.md        final (both models complete)
    config.json, source-manifest.json, device-snapshot.txt
    *.log                       server and benchmark logs

  ReflexModels pod-backup (local, no GPU needed for phone tests):
    experiments/unsloth-qwen35-08b-decision/runs/pod-backup/
