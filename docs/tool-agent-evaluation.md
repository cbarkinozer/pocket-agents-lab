# Tool-agent evaluation protocol

The next result this project targets is a reproducible statement of the form:

> On a Galaxy A32, model X at quantization Y selected the correct route on N/50 requests, with measured latency, output rate, RAM, and temperature.

## Version 6 suite

The Android app's **Run Agent Test** button runs `tool-routing-3-tools-v7`: 50 fixed prompts, with ten prompts in each class:

- `get_storage_info`
- `get_device_info`
- `get_battery_info`
- direct answer (no tool)
- `phone_health_check` workflow

Several prompts mention a distractor (for example, battery versus storage). The suite tests only route selection. It deliberately does not execute tools or generate final explanations, so routing accuracy is not confounded with tool execution and answer quality.

The model is loaded once. Before each prompt, a small JNI operation clears chat, KV, and recurrent conversation memory; it does not unload or reread the GGUF. Clearing recurrent state is essential for hybrid architectures such as LFM2. Every case therefore has an independent warm context without paying a 731 MB cold-load cost. The routing prompt is self-contained rather than relying on cached system tokens. Model loading must be benchmarked separately. Start only when battery temperature is at most 38 C; the run refuses to start above that threshold. The threshold was raised from 35 C after the first xLAM queue run showed a stable 40 C sustained plateau and an unnecessarily long inter-model wait at 36.5 C. Each artifact records the threshold actually used, so results from different thermal protocols can be separated. Keep the phone unplugged, screen brightness and ambient conditions fixed, and do not interact with other apps during a run.

The UI displays completed cases, running accuracy, strict first-pass count, normalized count, successful repair count, final accepted count, elapsed time, and a progress bar. **Cancel Agent Tests** safely cancels generation and does not save a misleading partial report.

## Overnight multi-model queue

Use **Select GGUFs** to select two or more models. The displayed numbered order is the execution order. **Run Selected Models Overnight** then:

1. waits until battery temperature is at most 38 C;
2. copies and loads one model;
3. runs all 50 v2 cases;
4. saves model-specific JSON and CSV files;
5. unloads it and waits for cooldown again;
6. continues even if an individual model is unsupported or fails.

The activity keeps the screen awake while the queue is active. Cancellation stops the queue. A queue manifest named `agent-routing-RUN_ID-queue.json` records selection order and completed/failed outcomes. Per-model artifacts use the same run ID and a sanitized model name, preventing later models from overwriting earlier results.

List and pull overnight artifacts with:

```powershell
adb shell run-as com.pocketagentslab ls files
adb exec-out run-as com.pocketagentslab cat files/ARTIFACT_NAME > ARTIFACT_NAME
```

Model names and expected winners are hypotheses, not scoring inputs. Every model receives the identical suite and protocol.

## Model compatibility normalization

The portable protocol keeps one logical schema across models but applies narrowly recorded compatibility handling:

- A response consisting solely of one `json` Markdown fence around one JSON object is unwrapped, validated normally, and counted as **normalized**, never strict. This covers observed xLAM output without accepting surrounding prose.
- An exact xLAM-style native call array containing one known read-only tool is normalized to that tool. A single `phone_health_check` call or an exact combination of two or all three distinct read-only tools is normalized to the health workflow, which deterministically executes the complete inspection. Empty arrays, duplicate/unknown calls, arguments, and arbitrary prose remain invalid.
- The v3 routing prompt explicitly distinguishes physical RAM/model/manufacturer/ABI facts from filesystem capacity after Qwen v2 confused three such cases. This is a recorded prompt change, not post-hoc rescoring of v2.
- Version 4 explicitly distinguishes live battery facts, ordinary no-tool questions, multi-category health checks, and AI-workload readiness after the completed v3 comparison exposed those shared boundaries. Version 3 artifacts keep their original scores.
- Version 5 freezes Qwen3.5 0.8B Q4_K_M as the quality/efficiency reference and constrains routing in llama.cpp with a five-choice GBNF grammar. The model still chooses the route; native sampling guarantees an exact valid JSON decision. Direct answers use a second, unconstrained generation after the route decision. Compare v5 route accuracy separately from earlier free-form JSON protocols.
- Version 6 tests hierarchical constrained routing with the same frozen weights. Stage one chooses `answer` or `live_device`; live requests then choose device, battery, storage, or Phone Health under a second native grammar. This isolates the two semantic boundaries responsible for every v5 miss. Its extra model call and latency are part of the measured architectural tradeoff.
- The completed v6 run scored 26/50 versus v5's 40/50, while both achieved 50/50 strict schema validity. Hierarchy improved average measured latency to 18.70 seconds but introduced new scope and live-route errors across every class. It is preserved as a negative result; v5 remains the reference harness.
- Version 7 returns to v5's single five-route grammar and changes only the routing prompt. Ordered rules and contrastive examples distinguish current phone facts from general explanations, and single live categories from multi-category or overall health requests. It is evaluated separately against v5; the fixed prompt labels and frozen Qwen Q4 weights are unchanged.
- For user turns, the JNI formatter safely attempts the model's Jinja chat template with `enable_thinking=false`; this is required for Qwen3.5 because the official Android sample's legacy template path cannot pass that setting. System/history turns retain the stable legacy path, and caught Jinja incompatibilities fall back to it instead of crossing JNI and aborting Android. Routing/repair output remains capped at 64 tokens, and the prompt also forbids reasoning and `<think>` output.
- A repair runs in a fresh conversation context and includes the original request plus at most 256 characters of rejected output. This prevents a reasoning trace from being duplicated into the 1024-token context.
- Native generation stops at the context boundary. It never invokes the upstream sample's context-shift path, which was observed aborting in `llama_memory_hybrid::seq_add` for Qwen3.5-0.8B.

These transformations and normalization flags remain visible in raw artifacts. Future native tool-protocol benchmarks should be reported separately from this portable protocol.

Queue manifests are checkpointed before work and after every model. Per-model JSON/CSV files are initialized before case one and rewritten after every completed case, allowing a native crash to leave a clearly marked partial result (`complete: false`) instead of erasing all progress.

The benchmark writes app-private files:

- `agent-test-result.json`: versioned manifest, device/model/build metadata, totals, and per-case records.
- `agent-evaluation.csv`: one row per prompt for analysis in a spreadsheet or script.

Pull them with:

```powershell
adb exec-out run-as com.pocketagentslab cat files/agent-test-result.json > agent-test-result.json
adb exec-out run-as com.pocketagentslab cat files/agent-evaluation.csv > agent-evaluation.csv
```

Recorded fields include expected and actual route, correctness, strict first-attempt schema validity, deterministic normalization, repair attempts, final schema acceptance, validator failure class, every raw model output, generation latency, approximate TTFT, generated JNI pieces, pieces/second, process PSS before/after, and battery temperature before/after. Raw attempts are stored in JSON; the flat metrics are also written to CSV. JNI pieces are not guaranteed to equal tokenizer tokens, so the CSV calls this value `exposed_pieces_per_second`; authoritative token/s requires a future JNI counter.

These counters must not be conflated:

- **Strict first pass:** the first model output exactly matched an allowed schema.
- **Normalized:** deterministic code safely accepted an allowlisted shorthand.
- **Repair attempted:** the validator rejected the first output and made the single allowed retry.
- **Repaired selection:** that retry produced an accepted schema.
- **Final accepted:** strict, normalized, or successfully repaired output was accepted.
- **Correct route:** the final accepted route matched the labeled expectation.

The completed v1 baseline and its known ambiguous `validJson` label are preserved under `benchmarks/agent-routing/run-001-a32-lfm2.5-1.2b-q4_k_m/`.

## Validation and repair

The router accepts only the allowlisted JSON schemas. If initial output is invalid:

```text
generate once -> deterministic validator reports exact error -> one constrained repair attempt -> stop
```

There is no recursive reflection or unlimited retry. Repaired selections are explicitly labeled and should be reported separately from first-pass schema validity.

## Pinned runtime

- llama.cpp commit: `a94d563ed801d1da1b8c2432946de07d0231bb3d`
- ABI: `arm64-v8a`
- CPU-only
- context: 1024 tokens
- `GGML_SYSTEM_ARCH=ARM`
- `GGML_CPU_KLEIDIAI=OFF`
- `GGML_OPENMP=OFF`

The Git submodule and `llama-android/src/main/cpp/CMakeLists.txt` are the source of truth. Any changed commit or flag creates a new benchmark configuration.

## Wireless ADB

For Android 11+ on the same trusted Wi-Fi network, enable **Developer options -> Wireless debugging**, choose **Pair device with pairing code**, then run:

```powershell
adb pair PHONE_IP:PAIRING_PORT
adb connect PHONE_IP:DEBUG_PORT
adb devices
```

The pairing and debugging ports shown by Android can differ. Prefer USB for initial troubleshooting. Disable wireless debugging after the run; do not use it on an untrusted network.

## Scaling experiment

Do not add tools casually. Preserve this three-tool result, then create separately versioned suites for 5 and 10 tools. Add semantically similar and irrelevant distractor tools. For each suite report route accuracy, first-pass JSON validity, repair rate, final schema failure rate, latency, PSS, and temperature. The breaking point is where accuracy or reliability degrades materially under an otherwise identical protocol.
