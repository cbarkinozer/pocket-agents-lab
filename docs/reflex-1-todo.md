# reflex-1 next steps

Last updated: 2026-10-11. Working HTTP baseline: Qwen raw 66% on dev suite.
Next: run that same config on the 90-case phone routing test, then retrain.

## 1 — HTTP baseline established (qualified)

Three-way comparison complete (2026-10-11). See thinking-comparison-report.md.

| Treatment | Test set | Accuracy | Mean latency |
|---|---|---|---|
| chat, thinking=OFF | 50-case dev suite | 10/50 (20%) | 65.7s |
| chat, thinking=ON-v2 | 50-case dev suite | 3/50 (6%) | 157.8s |
| raw (no chat template) | 50-case dev suite | 33/50 (66%) | 36.5s |
| JNI Sep-11 | 50-case dev suite | 41/50 (82%) | 53.0s |

**Qualified conclusions:**
- Removing chat formatting improved this run from 20% to 66%. The exact cause
  within the raw path is not isolated (could be prompt format, grammar handling,
  absence of role markers, or a combination).
- The 16pp gap to JNI (82%) is unexplained. It has not been shown to come from
  the `[[POCKET_GRAMMAR_ROUTE]]` mechanism specifically, nor that it cannot be
  closed over HTTP.
- Thinking mode produced broken JSON without grammar (model omits "action" key).

**The 66% result is a useful working HTTP configuration, not a definitive root
cause analysis.** It is sufficient to use as a matched comparison target.

**IMPORTANT — mismatched test sets:** The 66% raw result and the 20% reflex-1
before-retrain result use different test sets (50-case dev suite vs 90-case
phone routing test). They cannot be directly compared. The matched comparison
run (step 4a below) is required before any win claim.

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

**Known baselines (same test sets cannot yet be compared):**
- reflex-1 before-retrain on phone_routing test.jsonl: 18/90 (20%), 28.1s
  → 82/90 routes to phone_health_check; 5 routes never chosen
- Qwen raw on dev suite (50-case): 33/50 (66%), 36.5s
  → different test set; not directly comparable to the 18/90 result

- [x] **4a — Qwen raw on 90-case phone routing test ✅ DONE**
      Result: 50/90 (55.6%), mean 36.8s — benchmarks/phone-routing-eval/qwen-raw-phone-routing-results.json
      Notable: search_local_files 10/10, battery 9/10, phone_health_check 1/10.
      Win condition: retrained reflex-1 > 50/90 on same test at lower A32 latency.

- [ ] **4b — Retrain Clef head on phone_routing_v1 data:**
        cd ReflexModels && python3 /path/to/pocket-agents-lab/tools/train_phone_routing.py \
            --data-dir /path/to/pocket-agents-lab/data/phone_routing \
            --output-dir runs/phone-routing-v1
        Use calibration split for model selection and probability calibration.
        Smoke test with --smoke first.

- [ ] **4c — Evaluate on all 4 benchmarks** using evaluate_phone_routing_model.py:
        1. Phone routing test.jsonl (90 held-out) — must beat step 4a Qwen raw score
        2. typed-decisions test — must not collapse
        3. BANKING77 (pre-retrain: 44.59%)
        4. CLINC150 (pre-retrain: 47.11%)

- [ ] **Win condition:** retrained reflex-1 on test.jsonl > Qwen raw on test.jsonl,
      with lower measured A32 latency and acceptable memory, heat, regression scores.
      Beating 66% on a different test set does not count.

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
