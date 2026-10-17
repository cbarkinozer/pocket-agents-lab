# reflex-1 on the Galaxy A32

**App integration update (2026-10-17):** see [the application integration guide](reflex-1-app-integration.md) for the implemented packaged runtime, exact release schema, sequential text-model swapping and build/device checks. The sections below preserve the earlier benchmark/design context.

The first experiment replaces route selection with a Clef decision head. It
does not replace the final language-model answer. The existing 50 development
cases test routing only, without executing tools or generating final answers.

## Code boundaries

- `AgentBackend.select()` performs routing. Its optional
  `AgentDecisionSelector` accepts a structured `AgentSelection` without text
  generation. The default generator route remains available.
- `AgentBackend.complete()` executes the selected capability and uses
  `AgentGenerator` for the final answer where needed. Existing deterministic
  product responses and trusted phone-health summaries remain in place.
- `MainActivity.runAgentTests()` owns the existing Android routing benchmark.
  The new `tools/benchmark_decision_android.py` imports the same 50 prompts from
  `replay_agent_pilot.py`, sends a nine-option choice schema, and saves raw and
  relevance-filtered accuracy separately. ADB transports requests to a server
  running on the phone; the Mac does no model inference.

The Kotlin selector is now used by ReflexInferenceEngine for Clef GGUFs; the application integration guide records the current behavior. The decision-only export itself still cannot generate text.

## One backbone with two output paths

The downloaded `reflex-1-Q4_K_M.gguf` is a complete merged model: trained
backbone weights plus decision head and calibration. It is not a LoRA sidecar
for the phone's original Qwen GGUF.

The saved export/runtime source at revision
`aa94f2086147cd4bfbcff818ce998dffce5fcb39` provides important evidence:

- `conversion/clef.py` exports architecture `clef` and a `systemone` schema template.
- `src/models/clef.cpp` inherits the Qwen3.5 tensor loader but builds a graph
  that always evaluates the decision head. It sets `res->t_embd` to scores and
  does not populate vocabulary logits.
- `src/llama-model.cpp` gives Clef no persistent inference memory.
- `tools/server/server-common.cpp` advertises Clef's output as `decisions`.

Therefore the current GGUF/runtime cannot simply alternate ordinary chat
requests and decision requests. A normal llama.cpp LoRA loader also does not
create the new head or its scoring graph. The backed-up PEFT weights consist
of a 43,346,432-byte LoRA file and a separate 54,661,902-byte joint head.

A shared-weight implementation needs one loaded tensor set, a decision
context/graph, a text context/graph with vocabulary logits and recurrent/KV
memory, separate prompt formatting, and explicit context resets. It must
first prove both paths and account for context memory. Using the merged
backbone for prose is a possible first experiment, but its text quality has
not been measured. Returning to the untouched base weights for prose would
also require a portable LoRA sidecar and validated adapter enable/disable
behavior; attaching only the decision head would omit the trained LoRA.

## Current device experiment

Artifacts: `benchmarks/agent-routing/2026-10-10-reflex-1-a32/`.

The phone uses the upstream Android ARM64 release `b11443`, which reports
commit `f0c41e016`. This is a different revision from the export converter and
the September JNI baseline; record it explicitly rather than treating the
historical result as a matched control.

Default CPU weight repacking caused a graph-scheduler assertion when loading
Clef. `--no-repack` allowed it to load. The comparison must apply this flag to
both models. Initial successful server load was approximately 1.9 seconds;
this is a warm file-cache HTTP server load, not comparable to the historical
app import/load measurement.

Both model treatments use CPU-only, four inference/batch threads, context
2048, flash attention disabled, automatic fit disabled, localhost-only
server and USB charging. Each case requires battery <=38 C and thermal status
<=LIGHT. Transport overhead is included in reported request latency; cooldown
and telemetry time are reported separately/excluded. Device PSS includes
server overhead. Probabilities and complete API responses are preserved.

This development suite is not sealed evidence for future fine-tuning: if its
errors guide training, reserve separate held-out tasks before claiming a
generalization improvement. No W&B runs are created for this evaluation.
