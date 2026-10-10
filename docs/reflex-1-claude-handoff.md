# Claude Code handoff — reflex-1 / Galaxy A32

Continue the active experiment in `/Users/cbarkinozer/Documents/GitHub/pocket-agents-lab`.
Read `docs/reflex-1-integration.md` and the saved config/results before acting.

## Objective and constraints

Compare the locally trained Qwen3.5-0.8B decision model reflex-1 Q4_K_M with
original Qwen3.5-0.8B Q4_K_M on the connected A32 using the existing 50 routing
cases. Measure accuracy (raw and guard-filtered), latency, PSS and heat/cooldown.
Preserve final prose as a language-model call. The user wants one backbone with
adapters/head switching, not two simultaneously resident models. Do not claim
that the current standalone Clef export already supports this. Do not publish,
push, upload to HF, retrain, or log evaluations to W&B.

## Active processes at handoff, 2026-10-10

These PIDs are a snapshot; inspect live processes/logs first. Do not restart a
running experiment, truncate existing results, or launch duplicate servers.

- ADB: `/Users/cbarkinozer/.cache/reflex-android/platform-tools/adb`
- Serial: `R68T800LZ0H`, model SM-A325F, Android 13.
- Phone server PID: `16594`; Mac ADB server connection PID: `28251`.
- Mac reflex benchmark PID: `28265`.
- Mac comparison manager PID: `28550`.
- ADB forwarding: host localhost:8091 -> phone localhost:8091.
- reflex run was incomplete at 37/50 cases at the latest handoff check, with 14 raw correct / 19 after the
  relevance guard. Counts are changing. Last case was answer-07. The aggregate
  relevance guard occasionally converts an incorrect raw choice to the expected
  answer, so both raw and guarded scores must be reported. Some answer cases are incorrectly
  selected as phone_health_check by the model and rescued by the guard; do not
  call those raw model successes. All first ten battery cases missed their raw
  expected route. No API/schema errors through case 37.
- Individual requests roughly 22–27 seconds; server PSS around 1,062,000 KiB.
  Battery about 35 C, repeated MODERATE thermal pauses. SKIN was around 40 C
  during an earlier pause and the AP later reached about 51 C; the battery
  temperature gate alone missed the hottest components. The harness currently
  waits for both battery <=38 C and thermal <=LIGHT, up to 30 min per case.
  These are preliminary values.

The manager script is
`/Users/cbarkinozer/.cache/reflex-android/run-baseline-after-reflex.py`.
A reproducible copy is saved in the artifact folder as `comparison-orchestration.py`.
It waits for reflex report complete=true, kills its server, loads original
Qwen under the same settings, runs all 50 chat-routing cases, then stops the
server. It does not load both models at once. Monitor it; if the reflex benchmark
fails before complete=true, this simple manager keeps waiting and needs handling.
Do not let it silently wait forever. Its Python/ADB absolute paths are local.

## Artifacts and settings

Everything is written incrementally to
`benchmarks/agent-routing/2026-10-10-reflex-1-a32/`:
config.json, source-manifest.json, device snapshot, reflex-1-results.json,
reflex-benchmark.log, server logs including startup failures, comparison manager
log and script. Qwen results/logs/hash appear after the manager switches models.

Runtime: official Android ARM64 release b11443, reported commit
`f0c41e0168dfd4b5ef72b21d1a311b24cc7a894a`.
Installed at `/data/local/tmp/reflex-runtime` on the phone.
Source/archive caches under `/Users/cbarkinozer/.cache/reflex-android/`.
Archive SHA verified against the official GitHub release digest; config records it.
Flags: `-c 2048 -t 4 -tb 4 -ngl 0 --fit off -fa off --no-repack
--host 127.0.0.1 --port 8091 --jinja`.
Default parallel slots=4. Default repacking failed with a scheduler assertion;
`--no-repack` fixed loading. Do not turn repacking on during this paired run.
Both treatments use USB power and the battery <=38 C / thermal <=LIGHT gate
before every case. Keep cooldown separate from inference latency. No tools or
final answers are executed. The phone app was force-stopped before starting.

Decision mode calls `/v1/systemone`, one choice question with nine routes
matching the existing research grammar, preserves all probabilities and verifies
output_tokens=0. Chat mode uses the existing system prompt, routing prompt,
grammar, temperature=0.3, seed=3407, max_tokens=64, disabled thinking/cache.
All 50 imported prompts were verified present verbatim in MainActivity.kt.
The decision prompt is ~400 tokens; text prompt ~1127 before chat formatting.
Do not attribute speed changes solely to the head; prompt length differs too.

## Models

Phone reflex file: `/sdcard/Download/models/reflex-1-Q4_K_M.gguf`.
Size 545161248 bytes; SHA256
`8480ccc295ac7379106f33ad76c978da5d9e8da0785e827786fe9d17f5a5beb5`,
verified after transfer.
Original phone model: `/sdcard/Download/models/Qwen3.5-0.8B-Q4_K_M.gguf`.
The manager records its SHA before testing.

Local reflex weights and Q8 reference:
`/Users/cbarkinozer/Documents/GitHub/ReflexModels/experiments/unsloth-qwen35-08b-decision/runs/pod-backup/reflex-1-gguf/`.
The complete merged HF model, LoRA, joint head, configs, tokenizer, calibration,
training/evaluation logs and inventories are backed up in the same pod-backup
parent. No GPU pod is needed for these phone tests. Read ReflexModels AGENTS.md
and EXPERIMENT_CLOSEOUT.md if using that repository.

## Code changes, not yet built or installed

- `AgentBackend.kt`: added optional AgentDecisionSelector seam in select();
  final complete() still uses AgentGenerator. Existing generator remains default.
- `AgentBackendTest.kt`: added test that decision routing skips generation and
  final answer still calls the text generator once.
- `tools/benchmark_decision_android.py`: new phone-local HTTP benchmark harness.
- `docs/reflex-1-integration.md`: architecture and protocol notes.

Python help/parsing, `python3 -m py_compile` and `git diff --check` passed. Kotlin tests were NOT run:
this Mac currently lacks a JDK and Android SDK. Do not claim the app was built,
installed or fully integrated. No changes committed by this task.
The parent repository already showed a modified llama.cpp gitlink and the
nested submodule index had thousands of staged deletions at arrival. Leave that
pre-existing state alone. The experiment uses a separate runtime cache.

## Single-backbone limitation and next work

The current GGUF is a merged Clef decision model, not a normal LoRA sidecar.
Saved converter source revision aa94f2086147cd4bfbcff818ce998dffce5fcb39:
`conversion/clef.py` exports Clef; `src/models/clef.cpp` always runs the head,
sets embeddings to decision scores, and does not provide vocabulary logits;
`src/llama-model.cpp` supplies no persistent memory for Clef. Ordinary LoRA
loading does not add the new head or scoring graph. The backup has separate
43,346,432-byte adapter_model.safetensors and 54,661,902-byte joint_head.safetensors.

A true one-loaded-backbone implementation requires decision and text graphs,
separate contexts/prompt formatting, recurrent/KV state for text, and validation
of switching. Using merged weights for prose also needs a text-quality test.
Switching back to original base for prose additionally requires portable LoRA
conversion plus validated adapter enable/disable and head loading. Do not wire
the phone decision server alongside a separately loaded in-app Qwen and call
that an adapter solution. The optional selector is only a code seam so far.

## Finish this experiment

1. Inspect ADB/processes and monitor the active runs rather than restarting.
2. Finish both 50-case treatments and preserve partial/failure artifacts if interrupted.
3. Summarize raw and guard-filtered accuracy by category, mean/median latency,
   input/output tokens, PSS mean/peak, battery range/delta, thermal statuses and
   total cooldown. Report single ordered run/USB power/runtime/prompt caveats.
4. Historical JNI Qwen result: 41/50 (82%), average 53 s, average PSS780270 KiB;
   see docs/results/2026-09-11-ling-qwen-a32-comparison.md. This is context, not
   the matched new HTTP control.
5. Save a result report and artifact hashes/configs, run appropriate checks,
   stop benchmark servers after completion and leave phone models in place.
6. Be candid about whether quality/speed/memory/heat support further work.
   Do not silently deploy a worse router, tune on this development suite and
   call it held-out evidence, or claim two-head integration is completed.

No public release of reflex-1: the user wants further experiments first.
