# llama.cpp Android integration

The app uses the official `examples/llama.android` Kotlin/JNI binding from the pinned `third_party/llama.cpp` submodule. A small local `llama-android` Gradle module adapts that binding to this repository's Android Studio-compatible AGP version.

## Configuration

- ABI: `arm64-v8a` only.
- Backend: statically linked baseline ARM64 CPU backend; no GPU or network API.
- Context: 1024 tokens.
- Maximum generated tokens per request: 512. Generation can stop earlier when the model emits its end-of-sequence token.
- Model access: Android Storage Access Framework selection followed by an atomic copy to app-private `files/models`. Persisted URI permission is requested, while the private copy gives native llama.cpp a reliable filesystem path.
- Upstream revision: recorded by the `third_party/llama.cpp` Git submodule.

The static backend is intentional for the Galaxy A32. Upstream's dynamically selected multi-ISA CPU libraries built successfully but were not discovered by the loader on this Samsung Android 13 runtime. The baseline static ARM64 backend loaded reliably.

## Model used on the A32

`LFM2.5-1.2B-Instruct-Q4_K_M.gguf`:

- GGUF V3
- LFM2 architecture, 1.17B parameters
- Q4_K_M
- 730,895,168 bytes on disk
- Approximately 694.76 MiB mapped model data
- Approximately 12 MiB KV cache at the configured 1024-token context

The test model is available on the device at `Download/models/LFM2.5-1.2B-Instruct-Q4_K_M.gguf`. Model files are intentionally excluded from Git.

## Usage

1. Tap **Select GGUF** and choose a `.gguf` file.
2. Tap **Load Model**. The app copies it to private storage and reports success or failure.
3. Enter a prompt, or retain the default `What is 2+2?`.
4. Tap **Run Agent**. The model either answers directly or selects one of exactly three
   read-only tools: `get_device_info`, `get_battery_info`, or `get_storage_info`.
5. For a tool call, Android executes the tool locally, returns its JSON result to the model,
   and displays the model's final answer. No network or external API is used.

The agent protocol accepts only a bare JSON object in one of these forms:

```json
{"action":"answer","text":"..."}
{"action":"tool","name":"get_battery_info","args":{}}
{"action":"workflow","name":"phone_health_check","args":{}}
```

Malformed JSON, unknown tools, arguments, and repeated tool calls fail closed. **Run Agent
Tests** evaluates twelve fixed prompts, including four health-check phrasings, and reports JSON validity plus route-selection
accuracy. The detailed report is stored in app-private `agent-test-result.json` and logged with
the `PocketAgent` tag. Each test starts with a fresh model context so earlier prompts cannot
pollute later routing measurements; the compact routing contract and examples are repeated in
each request because small models do not always retain system-prompt constraints reliably.

For the tested 1.2B model, the parser also safely normalizes the common shorthand
`{"action":"get_storage_info","args":{}}` into the canonical tool envelope. This repair applies
only when `action` exactly matches one of the three allowlisted read-only tools and `args` is empty;
all other invented actions remain rejected. The UI marks repaired routes as `normalized`.

If a small model copies the final-answer placeholder instead of interpreting a successful tool
result, the backend displays a deterministic answer derived from that same read-only JSON and
marks the route `fallback answer`. This keeps device facts visible without inventing data while
preserving the model's failure as an observable metric.

## Phone Health Check workflow

A health-check request selects one workflow; the workflow is not a fourth tool. Android invokes
all three existing read-only tools, then Kotlin decides the facts using fixed thresholds:

- free internal storage below 10%: `low_storage` warning;
- battery temperature above 40.0°C: `hot_battery` warning;
- otherwise: `okay`.

Values exactly at 10% and 40.0°C are okay. The SLM receives the trusted diagnosis and may only
explain it and repeat the deterministic suggestions. It does not choose warning thresholds or
diagnostic status. The visible response always starts with a deterministic trusted summary, then
labels the model-authored portion `Local SLM suggestions`, so fluent generation cannot replace
the measured diagnosis. Fifteen parameterized JVM scenarios cover healthy, individual-warning,
combined-warning, severe, and boundary conditions. A separate orchestration test verifies that
the workflow calls device, battery, and storage exactly once and preserves the diagnosis.

Every physical-device health run writes `phone-health-check-result.json` in app-private storage
and logs `PocketHealth`. It records the trusted diagnosis, end-to-end workflow latency, process
PSS before/after, generated text-piece count, exposed pieces/second, and selected route. The JNI
binding exposes decoded pieces rather than an authoritative token count, so pieces/second is the
closest available TPS estimate and is labeled accordingly.

The Compose screen exposes the backend's current action while it runs and shows coarse stage
progress. A health workflow advances through model routing, device read, battery read, storage
read, deterministic evaluation, local explanation, and completion. This is stage progress—not a
prediction of remaining generation time—because llama.cpp cannot know in advance when a model
will emit its end token.

## Backend tests

The agent state machine lives in `AgentBackend.kt` and has no Compose dependency. JVM tests use
scripted model responses and fake tools to verify the direct-answer path, the complete two-call
tool path, strict schemas, the three-tool allowlist, empty arguments, and rejection of malformed,
unknown, or repeated actions. Run them on Windows with Java 17:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat testDebugUnitTest
```

These tests prove orchestration and safety behavior deterministically. The on-device **Run Agent
Tests** button remains necessary to measure whether a particular GGUF model follows the protocol.

The **Run Device Benchmark** button retains the original bundled 60-second MobileNet V1 CPU workload. It reports throughput, average latency, battery-temperature change, and a conservative GGUF parameter-size tier based on currently available RAM. The size tier is only a starting estimate: quantization, architecture, context length, KV cache, and runtime overhead all affect whether a model fits, while MobileNet throughput does not directly predict LLM token speed.

Performance events use the `PocketLlamaMetrics` log tag:

```powershell
adb logcat -s PocketLlamaMetrics:I '*:S'
```

## Verified A32 run

The physical `SM-A325F` produced:

```text
model=LFM2.5-1.2B-Instruct-Q4_K_M.gguf load_ms=403 context_tokens=1024 cpu_only=true
generation_ms=5748 generated_token_pieces=27 tokens_per_second=4.697
```

Response:

```text
2 + 2 equals **4**.

This is a basic arithmetic operation where you combine two quantities to get a total.
```

The JNI binding emits decoded text pieces, which usually correspond to tokens but are not guaranteed to be one token each when UTF-8 bytes are buffered. The UI and log therefore provide the best available exposed rate while preserving that caveat.
