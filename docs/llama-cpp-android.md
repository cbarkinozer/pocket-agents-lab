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
4. Tap **Generate** and read the streamed local result.

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
