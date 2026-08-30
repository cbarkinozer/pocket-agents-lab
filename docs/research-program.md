# Pocket Agents Lab research program

## Research objective

Pocket Agents Lab is primarily an applied-AI research project. Its central question is:

> How useful and reliable can a sub-1B local model become as an Android agent when model weights,
> constrained generation, deterministic orchestration, and mobile resource limits are studied
> separately?

Product polish is not the research contribution. The app is an experimental instrument: it runs
models on real hardware, exposes Android capabilities, records failures, and makes controlled
comparisons possible.

## Product mode and Research mode

The app exposes two routing modes. They must never be mixed in reported results.

### Research mode (default)

- Input is typed text only. STT, wake-word listening, and voice lifecycle behavior are Product-mode
  concerns and must not introduce transcription or timing noise into SLM-routing measurements.
- Every eligible request is routed by the SLM.
- Regex aliases and trusted product shortcuts do not rescue the route before it is scored.
- Grammar constraints, schema validation, and the single constrained repair attempt remain explicit
  experimental factors and are logged.
- Deterministic code may validate arguments, execute a selected tool, and decide factual thresholds,
  but it may not silently replace an incorrect model selection in the reported score.
- Each result identifies strict first-pass behavior, normalization, repair, final acceptance, and
  semantic correctness separately.

### Product mode (deferred)

- Safe deterministic fast paths may handle unambiguous requests before the SLM.
- The SLM handles requests not covered by a trusted fast path.
- Reliability and latency matter more than attribution of capability.
- Product-only backlog: reliable local wake-word control, broader local search, offline STT/TTS,
  vision, background voice lifecycle, onboarding, and consumer UI polish.

Product-mode success is not evidence of SLM capability. Research artifacts must record the mode and
the route source (`slm`, `slm_repair`, `deterministic`, or `fallback`).

## Internal Edge Agent Benchmark

The current 50-case suite is engineering evidence, not the final benchmark. It is now designated
`PocketAgentBench-Pilot-v0` and belongs to the development split: its failures have been inspected,
its labels have influenced prompts and harnesses, and Qwen Q4 was partly selected using its results.
Its historical 40/50 and 44/50 results remain valid pilot findings, but it must never be used as an
unseen confirmatory test for later prompt engineering or model adaptation.

Build a frozen, versioned benchmark with train/development/test separation and no test prompts,
semantic siblings, or answers in routing prompts, checkpoint selection, or tuning data. Split by
semantic intent family rather than surface paraphrase so that closely related requests cannot leak
across partitions.

### Scientific construction plan

1. **Pre-register the constructs and research questions.** Separate model capability, harness
   contribution, mobile deployment cost, and model adaptation rather than changing several at once.
2. **Specify a versioned scenario schema.** Record intent family, task category, difficulty,
   linguistic form, required tools/actions, arguments, approval policy, deterministic device fixture,
   expected final claims, scorer version, provenance, and split.
3. **Create a 200--300-case candidate pool for a 100-case construction pilot.** Use real user
   requests, Android capability boundaries, controlled contrast pairs, and generated paraphrase
   candidates; generated candidates require human review and are never self-labeling ground truth.
4. **Audit the pilot before scaling.** Check ambiguity, duplicates/near-duplicates, class and
   difficulty balance, label agreement, scorer correctness, template-family dominance, and benchmark
   infrastructure failures. Publish exclusions rather than counting defective cases as model errors.
5. **Use the 100-case pilot to validate the instrument, not to make final category rankings.** Near
   50% accuracy, 100 binary cases have roughly a +/-10 percentage-point 95% interval, and subdivision
   across many categories is substantially less precise.
6. **Construct and seal an approximately 300-case confirmatory set.** Freeze its content hash,
   labels, exclusion policy, and scorer before evaluating the selected harness. Do not bundle visible
   sealed cases into ordinary development builds.
7. **Create adaptation data separately.** Training examples may derive from development failures and
   explicitly assigned interactions, never from sealed internal tests or external benchmark answers.
8. **Freeze base internal and external baselines before SFT/QLoRA.** DPO follows only defensible
   chosen/rejected data; PPO or other RL follows only a validated, non-gameable reward.

Maintain two related but distinct tracks:

- **Capability track:** fixed deterministic Android fixtures and exact expected outcomes measure
  route, tool set, arguments, clarification, approval, grounding, and end-to-end correctness.
- **Deployment track:** real devices measure load time, TTFT, latency, authoritative token rate, PSS,
  temperature, thermal status, energy where defensible, sustained degradation, crash, timeout, and
  out-of-memory behavior. Live device state must not change the capability ground truth.

### Task categories

1. **No-tool/direct answer** — resist unnecessary tool use.
2. **Single-tool selection** — device, battery, storage, media, notes, files, and later capabilities.
3. **Similar-tool discrimination** — storage fact vs storage settings; media info vs media control;
   note recall vs note save; timer vs alarm vs calendar.
4. **Multi-tool planning** — select all and only the tools needed for health or readiness workflows.
5. **Argument extraction** — query, application, duration, time, recurrence, title, and recipient.
6. **Paraphrase robustness** — formal, colloquial, abbreviated, noisy-STT, and multilingual forms.
7. **Distractor resistance** — irrelevant tools and semantically adjacent tools.
8. **Abstention/clarification** — missing, contradictory, unsafe, or ambiguous arguments.
9. **Safety and approval** — distinguish read-only, reversible, consequential, and prohibited actions.
10. **Execution grounding** — final answer agrees with deterministic tool output and does not claim an
    external action succeeded merely because Android accepted an intent.
11. **Multi-turn state** — correction, cancellation, reference resolution, and bounded external state.
12. **Scale stress** — repeat at 3, 5, 10, and later more tools to find the routing breaking point.

Report macro accuracy across categories, per-category accuracy, and worst-category accuracy. A single
aggregate score must never hide a category collapse.

### Outcome gates

Report progressively stricter gates separately:

1. parseable output;
2. valid schema;
3. correct route;
4. exact required tool set;
5. correct arguments;
6. correct clarification and approval behavior;
7. successful execution;
8. final response grounded in deterministic tool results;
9. useful end-to-end task success.

Valid JSON is not agent success, and Android accepting an Intent does not prove that the requested
outcome occurred.

### Statistical analysis plan

- Report Wilson 95% confidence intervals for binary outcomes, macro and per-category accuracy,
  worst-category accuracy, and safety-critical failure rates.
- Compare paired systems on identical cases with exact McNemar tests, paired percentage-point
  differences, and effect sizes. Apply Holm--Bonferroni correction within pre-registered contrast
  families; label post-hoc comparisons exploratory.
- Split and resample by semantic intent family to avoid treating paraphrases as independent tasks.
- Report latency/TTFT with median, IQR, p90/p95, and task-cluster bootstrap intervals rather than a
  mean alone.
- Randomize or counterbalance model/quantization run order, repeat deployment runs, and block by
  starting thermal state. Record charging, ambient protocol, cooldown, and background conditions.
- Do not introduce an EdgeScore until weighting, quality gates, uncertainty, and ranking sensitivity
  can be tested against enough raw results.

### Failure taxonomy

- invalid JSON or grammar termination;
- valid JSON with invalid schema;
- unknown action/tool;
- wrong tool or unnecessary tool;
- missed tool / incorrect direct answer;
- incomplete or excessive multi-tool plan;
- wrong/missing argument;
- unsafe action or missing approval;
- repair helped, repair failed, or repair changed a correct decision;
- groundedness failure after correct tool selection;
- timeout, native/runtime failure, or out-of-memory;
- thermal cooldown violation or sustained-performance degradation.

Preserve raw generations. Never collapse formatting failure and semantic selection failure into one
number.

### Required run metadata and metrics

- suite version, split hash, prompt ID/category, expected output, and scorer version;
- model identity, parameter count, GGUF bytes, quantization, adapter/checkpoint hash, and context size;
- llama.cpp commit, build flags, sampler, prompt/grammar version, and repair policy;
- device manufacturer/model, Android version, SoC/ABI, total and available RAM;
- strict and repaired correctness, invalid-output rates, category confusion matrix;
- load time, TTFT, generation latency, exposed pieces/token count, and throughput;
- PSS before/after/peak, battery temperature before/after/max, cooldown time, and thermal status;
- battery/energy measurement where Android exposes a defensible signal;
- repeated-run variance, run order, ambient protocol, and whether the device was charging.

The existing Android harness already records most of these. It now includes `task_category` in CSV
and JSON and declares `routingMode=research`. Authoritative tokenizer token/s, energy, thermal status,
and repeated-run statistics remain additions.

## External benchmark sentinel suite

Do not run every benchmark from the Qwen model card after every experiment. Use a small sentinel
suite during iteration and full official evaluations at paper/release checkpoints. Reproduce the
untuned base model with our own pinned evaluator before using any score as a forgetting baseline;
Qwen's published scores are references, not directly comparable to a different GGUF quantization,
runtime, context, prompt, or sampler.

Initial non-vision selection:

| Category | Sentinel | Qwen3.5-0.8B card reference | Why |
|---|---|---:|---|
| Language | MMLU-Pro, non-thinking | 29.7 | Popular, difficult, broad, lower random-guess success than MMLU. |
| Knowledge/STEM | GPQA, thinking | 11.9 | Hard held-out scientific reasoning; exposes knowledge/reasoning loss. |
| Instruction following | IFEval, non-thinking | 52.1 | Mostly verifiable constraints and directly relevant to agent compliance. |
| Long context | LongBench v2, thinking | 26.1 | Tests long-context behavior, but run off-device if the phone context cannot match protocol. |
| Reasoning | HMMT Nov 2025, thinking | not reported | Diagnostic only until a reproducible base score is established. |
| General agent | BFCL V4, thinking | 25.3 | Closest popular external check for function/tool calling. |
| Multilingual | MMMLU, thinking | 44.3 | Broad multilingual regression sentinel. |

Vision is deferred. The current Android GGUF/JNI path is CPU-first text inference and the Qwen vision
encoder previously caused memory/stability problems on the A32. Add vision only as a separately
versioned runtime and benchmark track; do not mix vision scores into the text-agent baseline.

For fast adaptation loops, use a frozen, stratified sentinel subset from each legally usable suite.
Call it a project sentinel score, not an official benchmark score. Before any paper claim, run the
complete pinned official protocol and report deviations.

Primary references to pin before implementation:

- [Qwen3.5-0.8B model card and reported baselines](https://huggingface.co/Qwen/Qwen3.5-0.8B)
- [MMLU-Pro official repository](https://github.com/TIGER-AI-Lab/MMLU-Pro)
- [GPQA paper and code reference](https://openreview.net/pdf?id=Ti67584b98)
- [Google Research IFEval implementation](https://github.com/google-research/google-research/tree/master/instruction_following_eval)
- [LongBench v2 official repository](https://github.com/THUDM/LongBench)
- [HMMT November 2025 archive](https://www.hmmt.co/www/archive/291)
- [Berkeley Function Calling Leaderboard](https://gorilla.cs.berkeley.edu/leaderboard.html)
- [OpenAI MMMLU dataset card](https://huggingface.co/datasets/openai/MMMLU)

Record repository/data revisions and licenses in the evaluator lockfile. BFCL is a changing
leaderboard, so reproduce against a pinned release rather than an unversioned latest dataset.

## Adaptation and catastrophic-forgetting protocol

1. Freeze base model, quantization, runtime, prompts, benchmark test split, and external evaluators.
2. Collect failures only from training/development prompts and real interactions explicitly assigned
   to training; never train on internal or external test answers.
3. Start with supervised fine-tuning using LoRA/QLoRA. Do not full-fine-tune first.
4. Compare the adapter with prompt/grammar/orchestration changes against the identical frozen tests.
5. Try DPO only after collecting defensible chosen/rejected pairs on held-out failure modes.
6. Treat PPO or other online RL as later work: it needs a robust reward, KL control, rollback, and
   evidence that the model is not exploiting the deterministic scorer.
7. Evaluate multiple seeds and report adapter rank, target modules, dataset mixture, epochs, learning
   rate, hardware, training time, and checkpoint selection rule.
8. Gate promotion on internal held-out improvement **and** external sentinel retention. Report every
   external delta; do not average away a serious regression in one capability.
9. Keep the untouched base checkpoint and adapter-disable path for paired evaluation and rollback.

Define forgetting thresholds only after measuring base-run variance. A reasonable provisional gate
is no statistically credible external regression and no category drop larger than an explicitly
pre-registered tolerance. Replace that tolerance with confidence intervals once repeated baselines
exist.

## Android action research inventory

Already prototyped:

- device, memory, battery, storage, and media information;
- deterministic phone-health and optimization reports;
- Storage/Battery Settings, Camera, Wallpaper Settings, and app-management handoffs;
- installed-app launch, Spotify/YouTube search, Telegram draft handoff;
- play/pause/next/previous through MediaSession;
- timer, alarm, and calendar-event drafts;
- private note save/recall and authorized local-file/document search.

High-value additions for controlled agent experiments:

### Read-only or observational

- network state and metering, Wi-Fi/Bluetooth state, airplane-mode state;
- display brightness, volume levels, ringer/DND state, orientation, locale, and time zone;
- charging source, battery health fields exposed by Android, storage volumes, and low-storage state;
- installed-app inventory and permission summaries where Android permits;
- calendar query, alarm query, notification summary, clipboard presence, and current foreground media,
  each behind the required permission and privacy boundary;
- sensor snapshots: light, proximity, accelerometer, and step counter where present.

### Reversible or Android-mediated

- flashlight, media volume, brightness, rotation lock, and DND with explicit permission/state restore;
- open URL, map/search/navigation intent, share sheet, contact picker, file picker, and app details;
- compose email/SMS/dialer drafts without pressing Send/Call;
- create/edit/cancel timers and alarms with deterministic temporal parsing and confirmation;
- create/update calendar or private notes with preview, confirmation, and an undo path.

### Mobile-control research

- accessibility-tree observation and element grounding;
- open app -> locate element -> click/scroll/type with per-step trace and approval;
- playback seek and richer controls when MediaSession exposes them;
- cross-app workflow planning with deterministic preconditions/postconditions;
- optional ADB/Shizuku primitives as a separately labeled privilege tier.

Never silently send a message, place a call, purchase, delete user data, alter security settings, or
grant permissions. Those are safety-evaluation cases, not default agent powers.
