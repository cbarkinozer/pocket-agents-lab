# Pocket Agents Lab roadmap

This is the canonical roadmap for the project and the path toward the user-facing **Pilav Tiny** application.

## Development principle

Develop the project iteratively across all layers. A layer only needs a minimum viable, measurable implementation before work can begin on the next layer. Do not wait to master or exhaust one layer.

The normal loop is:

1. build the smallest useful version of a layer;
2. integrate it into an end-to-end local workflow;
3. measure correctness, latency, memory, energy, and thermal behavior;
4. preserve the result and known limitations;
5. move to the next missing layer;
6. return to earlier layers when evidence identifies the highest-value improvement.

This avoids optimizing an isolated runtime or benchmark while the complete product remains unusable. It also avoids adding many capabilities on top of an unreliable foundation.

## 1. Edge SLM Runtime Layer

Run tiny models efficiently on phones.

- llama.cpp and GGUF
- quantization
- CPU-first inference
- RAM, latency, thermals, and battery measurement
- later custom C/native runtimes

Current MVP: pinned CPU-only arm64 llama.cpp, local GGUF loading, Qwen3.5 0.8B Q4_K_M reference, and runtime telemetry on the Galaxy A32.

## 2. Agent Harness Layer

Make sub-1B and 1B models useful despite their limitations.

- constrained single-turn routing
- grammar-constrained outputs
- externalized state
- deterministic orchestration
- validators and limited repair
- tool retrieval

Current MVP: five-route native grammar, three read-only Android tools, deterministic Phone Health, strict validation, and at most one repair. Both v6 hierarchy and v7 contrastive prompting regressed to 26/50 through different failure distributions. Freeze the v5 40/50 router and require a new held-out set before further prompt optimization.

## 3. Mobile Capability Layer

Give the agent useful things it can actually do.

- phone health
- storage, battery, and device information
- files
- notes
- local search
- automations
- accessibility helpers
- app/device actions

Current MVP: device, battery, storage, and actionable deterministic Phone Health.

## 4. Mobile Control Layer

Build the low-level “Playwright for Android” foundation.

- native Android APIs
- Intents
- AccessibilityService
- Mobilerun/AppAgent-style primitives
- optional Shizuku or ADB
- UI-automation fallback

Current MVP: the local model can propose opening Android Storage or Battery Settings. Deterministic Kotlin displays the proposal and requires an explicit confirmation tap before launching the allowlisted native intent; the model cannot execute or change settings silently. The original direct buttons remain available.

Implementation note: JNI catches native grammar-construction errors and reports them to Kotlin. A malformed experimental grammar must not terminate the Android process.

## 5. Local Intelligence Layer

Add capabilities beyond basic tool routing.

- semantic search
- local RAG
- memory
- STT and TTS
- vision
- constrained code execution
- generated workflows

MVP target: one small local retrieval or memory workflow evaluated end to end; do not build a broad RAG platform first.

## 6. Model Adaptation Layer

Make models specifically effective in this environment.

- Android/tool-use fine-tunes
- LoRA and QLoRA
- personalization
- learning from successful and failed actions
- open Pilav Tiny model releases

MVP target: a fixed dataset and one adapter experiment against the frozen unmodified Qwen baseline. Compare improvement with prompting and deterministic orchestration before claiming value.

## 7. Evaluation Layer

Measure what actually works.

- Edge SLM benchmark
- Edge Agent benchmark
- tool accuracy
- paraphrase robustness
- distractor tools
- latency, RAM, energy, and thermals
- cross-device testing

Current MVP: versioned 50-prompt routing suites, raw JSON/CSV artifacts, quantization comparisons, cooldown rules, and preserved result summaries. Next: repeat v5, then 5- and 10-tool distractor suites.

## 8. Product / UX Layer — Pilav Tiny

Turn the best research into something normal people can use.

- polished UI
- push-to-talk
- local-first and privacy-first behavior
- permissions and approvals
- easy model setup
- high-quality UX
- useful packaged skills

Current MVP: a three-section Android UI for device capability, model evaluation, and the Tiny Agent. Product work should continue alongside research rather than waiting for every underlying layer to be complete.

## Near-term vertical slice

The immediate integrated sequence is:

1. finish and repeat the frozen Qwen Q4 grammar-routing baseline;
2. test hierarchical routing and then distractor tools;
3. package Phone Health as the first polished end-to-end capability;
4. add one safe mobile action with explicit approval;
5. add one small local retrieval or memory capability;
6. evaluate one targeted adapter only after the harness baseline is stable;
7. continuously improve Pilav Tiny setup, permissions, progress, and explanations.

Each step should produce a working demonstration and a preserved evaluation result. None requires the preceding layer to be “finished forever.”
