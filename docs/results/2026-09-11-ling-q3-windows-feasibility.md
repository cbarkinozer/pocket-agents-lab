# Ling 3.0 Tiny Q3_K_M Windows feasibility + expert-routing trace

Date: 2026-09-11
Follow-up to `docs/results/2026-09-10-ling-q3-android-feasibility.md` and
`docs/results/2026-09-10-ling-followup-session-status.md`. This session had no
Android device attached, so it completes the Windows feasibility stage that
was originally skipped in favor of going straight to the phone, and adds a
real (non-synthetic) expert-routing analysis that does not require a device.

## Model identity

- File: `Ling-3.0-tiny-Q3_K_M.gguf`, 3,785,849,920 bytes.
- SHA-256: `62f7117aa4e6cbf8d366cabf99c75c72e62332d3ff8ea2f98f5688d3f513252f`.
- This is a byte-for-byte match against the file already validated on the
  Galaxy A32, confirming both platforms are running the identical GGUF.

## Windows host build

- `third_party/llama.cpp` at `2a15f3a72dc99d0d04aff8aa8f812335a13612ae` (same
  pin as the Android app), configured with CMake + MSVC Build Tools 2022
  (`cl.exe` 19.36), Release, x64, AVX2 (`HAS_AVX512_*` failed on this CPU, so
  the AVX2 CPU backend variant was selected automatically).
- Built `llama-cli` and `llama-eval-callback` as plain native Windows
  binaries under `C:\build\llama-win` (outside the repo and outside the
  Android Gradle/CMake tree, so this does not touch the Android build).

## Smoke test (`llama-cli`, `-c 2048 -rea off --temp 0`, single turn)

| Prompt | Output | Wall clock | Peak working set | Prompt / gen speed |
|---|---|---|---|---|
| `What is 2+2?` | `2+2 = 4.` | 3.38 s | 3,285 MB | 55.5 / 25.9 t/s |
| `Reply with exactly: READY` | `READY` | 3.18 s | 3,063 MB | 55.2 / 24.5 t/s |
| `Which tool should inspect battery temperature?` | coherent, on-topic prose (no routing grammar in plain `llama-cli`, so this is not scored as a route) | 6.00 s | 3,566 MB | 53.9 / 25.0 t/s |

All three of Aşama 1's smoke prompts complete without truncation or crashes.
This is faster and lower-variance than the phone (NVMe + desktop CPU vs.
phone flash + thermal-limited ARM), which is expected and is not a claim
about A32 performance - it only confirms the model, chat template
(`bailing`, auto-selected), and thinking-disable path all work correctly
against this llama.cpp build outside of Android's JNI layer.

## Expert-selection trace (real, not synthetic)

`third_party/llama.cpp/examples/eval-callback/eval-callback.cpp` gained a
minimal, env-var-gated hook (`POCKET_EXPERT_TRACE=1`) that dumps every
`ffn_moe_topk-{layer}` tensor as untruncated JSONL instead of using the
existing human-readable debug printer, which truncates any tensor wider than
6 values and would have silently dropped 2 of Ling's 8 selected experts per
token. Default `eval-callback` behavior is unchanged when the variable is
unset.

Trace captured by running `llama-eval-callback` against the actual system
prompt from `MainActivity.kt`'s `AGENT_SYSTEM_PROMPT` (the real Android
router prompt, plus one user turn: "How much storage is left on my phone?"),
499 prompt tokens, greedy/seed 42:

```
POCKET_EXPERT_TRACE=1 llama-eval-callback -m Ling-3.0-tiny-Q3_K_M.gguf \
    -f routing_prompt.txt --seed 42 -ngl 0
```

This produced 11,477 (layer, token) records across 23 MoE layers (layer 0 is
the dense leading layer per `bailingmoe3`'s `n_layer_dense_lead`, confirmed
against `src/models/bailingmoe3.cpp`), 8 expert ids each, matching the
model's `128 experts / 8 selected` configuration exactly.

`tools/gguf_expert_offsets.py` confirms the same layer count and expert
count by reading the GGUF header alone against the same file
(`architecture=bailingmoe3 expert_count=128 layers_with_experts=23`).

### Cache/predictor simulation (`tools/expert_cache_simulator.py`)

Bounded per-layer LRU cache, mean hit rate across all 23 layers, vs. the
random-baseline expectation (cache size / 128 total experts):

| Cache size (experts/layer) | Random baseline | Measured mean LRU hit rate |
|---|---|---|
| 8  | 6.3%  | 26.0% |
| 16 | 12.5% | 51.4% |
| 32 | 25.0% | 76.2% |

Every cache size measured well above its random baseline, which means expert
selection in this real routing session has genuine temporal locality - some
experts are reused across nearby tokens far more than chance would predict.
This is evidence *for* attempting Stage 9's expert-aware cache/prefetch work
(README item 42): a bounded cache is not competing against a uniform
distribution over 128 experts.

Simple predictor recall (of the 8 actually-selected experts per token, using
only the *previous* token's selection, otherwise using a frequency table)
ranged 21-44% for previous-token reuse and 34-48% for an unbounded frequency
table across layers - noticeably weaker than the LRU cache itself, so a naive
predictor alone would not be a good substitute for the cache; if a predictor
is added later (Aşama 10), it should be evaluated as a *prefetch hint on top
of* the LRU cache, not a replacement for it.

### What this does and does not prove

This characterizes Ling-3.0-tiny's routing pattern, which is a property of
the trained model and is independent of device. It is legitimate evidence
for deciding whether Stage 9 (expert-aware caching) is worth building. It is
**not** a measurement of Android latency, energy, page-fault behavior, or
thermal cost - those still require the physical A32 and remain blocked (see
`docs/results/2026-09-10-ling-followup-session-status.md`).

## Reproduction

```
# from repo root
cmake -S third_party/llama.cpp -B /path/to/build -DLLAMA_BUILD_EXAMPLES=ON -DLLAMA_CURL=OFF
cmake --build /path/to/build --config Release --target llama-cli llama-eval-callback

# smoke test
llama-cli -m Ling-3.0-tiny-Q3_K_M.gguf -p "What is 2+2?" -n 64 -c 2048 -st -rea off --temp 0 --perf

# expert trace (see routing_prompt.txt = AGENT_SYSTEM_PROMPT + one user turn)
POCKET_EXPERT_TRACE=1 llama-eval-callback -m Ling-3.0-tiny-Q3_K_M.gguf -f routing_prompt.txt --seed 42 -ngl 0 \
    | grep POCKET_EXPERT_TRACE | sed 's/^POCKET_EXPERT_TRACE //' > trace.jsonl

python tools/gguf_expert_offsets.py Ling-3.0-tiny-Q3_K_M.gguf
python tools/expert_cache_simulator.py trace.jsonl --cache-experts-per-layer 16 --expert-bytes 337920
```

The raw trace JSONL is not committed (regenerable from the command above in
under a minute); the numbers in this document are the full-run results.
