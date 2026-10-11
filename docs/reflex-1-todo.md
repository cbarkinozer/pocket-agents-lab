# reflex-1 next steps

Last updated: 2026-10-11. All Qwen baselines established. reflex-1 phone routing
before-retrain eval running. Next: retrain on phone_routing_v1, beat 66% raw.

## 1 — Establish the real Qwen baseline ✅ DONE

Three-way comparison complete (2026-10-11). See thinking-comparison-report.md.

| Treatment | Accuracy | Mean latency | Note |
|---|---|---|---|
| chat, thinking=OFF | 10/50 (20%) | 65.7s | grammar |
| chat, thinking=ON-v2 | 3/50 (6%) | 157.8s | broken JSON format |
| raw (no chat template) | 33/50 (66%) | 36.5s | grammar, guard=70% |
| JNI Sep-11 baseline | 41/50 (82%) | 53.0s | POCKET_GRAMMAR_ROUTE |

**Conclusion:** Chat template caused the 20% result. Raw mode (no template)
reaches 66%. Remaining 16pp gap to JNI (82%) is likely due to the
`[[POCKET_GRAMMAR_ROUTE]]` prefix mechanism vs HTTP GBNF grammar.
Thinking mode broke JSON schema (model generates wrong keys without grammar).

**Comparison baseline for retraining: raw mode = 66%.** reflex-1 at 48%
raw on the dev suite is below this. Training target: ≥70% on phone routing
test.jsonl to beat the raw Qwen baseline at ≤30s latency.

Weakness analysis from raw mode:
- answer category 3/10: "ABI" and "CPU" in prompts trigger device routing
  (the routing prompt lists ABI as a device-info example — confuses the model)
- health category 2/10: multi-category rule not applied to complex phrasings

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

**Baselines now established (2026-10-11):**
- reflex-1 before-retrain: 18/90 (20%), 28.1s mean — 82/90 routes to phone_health_check
- Qwen raw mode (comparison target): 33/50 (66%), 36.5s — the bar to beat
- reflex-1 latency advantage: 28.1s vs 36.5s (already 1.3× faster pre-retrain)

Routes invisible to reflex-1 pre-retrain: answer, get_battery_info,
save_note, search_local_files, search_notes.

- [ ] Retrain Clef head on phone_routing_v1 data:
        cd ReflexModels && python3 /path/to/pocket-agents-lab/tools/train_phone_routing.py \
            --data-dir /path/to/pocket-agents-lab/data/phone_routing \
            --output-dir runs/phone-routing-v1
        (Uses same unsloth env as reflex-1. Smoke test with --smoke first.)

- [ ] Report on all 4 benchmarks post-retrain using evaluate_phone_routing_model.py:
        1. Phone routing test.jsonl (90 held-out) — target: ≥70% to beat raw Qwen (66%)
        2. typed-decisions test — should not collapse (pre-retrain baseline unknown)
        3. BANKING77 (pre-retrain: 44.59%)
        4. CLINC150 (pre-retrain: 47.11%)

- [ ] Claim decision model worthwhile once phone routing test ≥70% at ≤30s mean.
      Current latency: 28.1s — already under the 30s target pre-retrain.

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
