# Tool-agent evaluation protocol

The next result this project targets is a reproducible statement of the form:

> On a Galaxy A32, model X at quantization Y selected the correct route on N/50 requests, with measured latency, output rate, RAM, and temperature.

## Version 2 suite

The Android app's **Run Agent Tests** button runs `tool-routing-3-tools-v2`: 50 fixed prompts, with ten prompts in each class:

- `get_storage_info`
- `get_device_info`
- `get_battery_info`
- direct answer (no tool)
- `phone_health_check` workflow

Several prompts mention a distractor (for example, battery versus storage). The suite tests only route selection. It deliberately does not execute tools or generate final explanations, so routing accuracy is not confounded with tool execution and answer quality.

The model is loaded once. Before each prompt, a small JNI operation clears chat, KV, and recurrent conversation memory; it does not unload or reread the GGUF. Clearing recurrent state is essential for hybrid architectures such as LFM2. Every case therefore has an independent warm context without paying a 731 MB cold-load cost. The routing prompt is self-contained rather than relying on cached system tokens. Model loading must be benchmarked separately. Start only when battery temperature is at most 35 C; the run refuses to start above that threshold. Keep the phone unplugged, screen brightness and ambient conditions fixed, and do not interact with other apps during a run.

The UI displays completed cases, running accuracy, strict first-pass count, normalized count, successful repair count, final accepted count, elapsed time, and a progress bar. **Cancel Agent Tests** safely cancels generation and does not save a misleading partial report.

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
