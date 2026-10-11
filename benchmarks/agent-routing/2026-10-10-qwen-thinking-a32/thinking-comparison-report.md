# Qwen3.5-0.8B Thinking ON vs OFF — Galaxy A32 (2026-10-11)

**Device:** SM-A325F · Android 13 · arm64-v8a  
**Runtime:** llama.cpp b11443 (f0c41e016) · 4 threads · USB power  
**Suite:** tool-routing-3-tools-v5 · 50 cases  
**Purpose:** Determine whether `enable_thinking=False` explains the 20% result from
the Oct-10 matched-chat run vs the 82% September JNI baseline.

## Summary

| Treatment    | Raw correct | Raw % | Guard correct | Guard % | Mean lat (s) | Parse fails |
| ------------ | ----------- | ----- | ------------- | ------- | ------------ | ----------- |
| Thinking ON  | 3/50        | 6.0%  | 3/50          | 6.0%    | 157.8        | 4           |
| Thinking OFF | 10/50       | 20.0% | 10/50         | 20.0%   | 65.7         | 0           |

**Reference baselines:**
- Sep-11 JNI (thinking config unknown): 41/50 (82%), mean 53.0 s
- Oct-10 chat OFF (this repo, prior run): 10/50 (20%), mean 64.8 s

## Per-Category Accuracy

| Category | n  | Thinking ON raw | Thinking OFF raw |
| -------- | -- | --------------- | ---------------- |
| answer   | 10 | 3/10            | 10/10            |
| battery  | 10 | 0/10            | 0/10             |
| device   | 10 | 0/10            | 0/10             |
| health   | 10 | 0/10            | 0/10             |
| storage  | 10 | 0/10            | 0/10             |

## Gap Analysis

Thinking ON: **6.0%** vs Thinking OFF: **20.0%**. Difference: -14.0 pp.

Gap from JNI baseline (82%) to thinking-ON: +76.0 pp.

**Conclusion:** Thinking OFF outperforms thinking ON unexpectedly. This may
indicate thinking generates poor routing decisions for this task, or a
grammar/parse compatibility issue with the thinking-ON path.

## Parse Error Analysis

Thinking ON parse failures: 4/50
Thinking OFF parse failures: 0/50

Parse errors in thinking-ON indicate the JSON extraction from the
thinking+response stream failed for some cases. Check thinking-on-results.json
for the raw response content to diagnose the format.

## JNI vs HTTP Architecture Note

The September JNI baseline used **raw text completion** (no chat template):

```kotlin
// AgentBackend.kt line 108
generator.generate(
    GRAMMAR_ROUTE_PREFIX + buildRoutingPrompt(userPrompt),
    AGENT_DECISION_TOKENS,
)
```

The HTTP runs use `/v1/chat/completions` which applies the Qwen3.5 chat template
(adding `<|im_start|>system/user/assistant` markers). The JNI path calls the
llama.cpp completion API directly, bypassing the chat template entirely.

A third treatment — `raw` mode using `/v1/completions` (no template) — directly
tests this hypothesis. See `run-thinking-on.py` which runs thinking-ON then raw.

**Prediction:** if raw mode reproduces the 82% JNI accuracy, the chat template
is the root cause, and neither thinking-ON nor thinking-OFF will close the gap.

## Next Steps

1. Check `raw-results.json` from run-thinking-on.py for the raw-mode accuracy.
2. If raw ≈ 82%: the comparison baseline for reflex-1 is raw mode, not chat mode.
3. If raw is also ~20%: investigate llama.cpp version difference (2a15f3a72 vs f0c41e016).
4. Proceed with phone-specific training using `data/phone_routing/train.jsonl`.
5. Eval target: beat raw-mode accuracy at ≤30s latency (reflex-1 current: 25s mean).

## Raw-Mode Results (no chat template — replicates JNI path)

raw: 33/50 (66.0%)  mean=36.5s

Per-category:
  answer: 3/10
  battery: 9/10
  device: 10/10
  health: 2/10
  storage: 9/10