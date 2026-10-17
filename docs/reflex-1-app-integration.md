# Reflex-1 application integration

The app detects GGUF `general.architecture=clef` and loads it through the packaged, CPU-only llama.cpp Decision API server. Regular GGUFs continue through JNI. The app sends the frozen MOBILE_ROUTING9 choice schema to `127.0.0.1:8092/v1/systemone`, maps all nine choices into existing AgentBackend tools/workflows, and never samples text for route selection.

## Using it

1. Select the released **reflex-1-combined-Q4_K_M.gguf** (SHA-256 `abfea5f878331206f65e4117157984e14803b093b634c0ed52513c0723ef0699`), then Load in Tiny Agent. The old beta has the same file size but a different hash.
2. Optionally use **Choose text model** to import the normal Qwen3.5-0.8B chat GGUF. Its path is saved separately from the selected router. A previously selected normal model is retained when possible.
3. Ask a request. Reflex selects the route; the existing app executes the corresponding tool. Existing consent/capability limits still apply.
4. If prose is needed and a text model is configured, the decision server is stopped and waited for before JNI loads the text model. On the next request JNI unloads those weights before the decision server starts again. These are sequential model swaps, not shared-backbone graphs or two resident models.

Without a text model, tools/workflows return deterministic factual summaries. A direct-answer route that needs prose explicitly asks for a text model; it does not invent a generated answer. Existing deterministic battery/media/note responses are retained even when a text model is available.

The Tests page's existing routing suite now accepts Clef as well as regular models. It measures routing only; its 50 development cases are not the released 180-case benchmark. Decision latency is HTTP wall time, with zero generated pieces; TTFT does not apply. App-process PSS excludes the separate decision process and must not be quoted as total model RAM. Interactive timing includes model swaps and final generation, so it is not comparable with routing-only benchmark latency.

## Building

The decision server is the verified official `b11443` Android ARM64 release, commit `f0c41e0168dfd4b5ef72b21d1a311b24cc7a894a`:

https://github.com/ggml-org/llama.cpp/releases/download/b11443/llama-b11443-bin-android-arm64.tar.gz

Extract it locally and prepare the APK payload:

```sh
python3 tools/prepare_reflex_android_runtime.py --runtime-dir /path/to/llama-b11443
bash gradlew :app:testDebugUnitTest :app:assembleDebug
```

The preparation script verifies all selected input binaries against `tools/reflex-runtime-b11443.json`, saves provenance/license assets, and copies native binaries into a Git-ignored jniLibs directory. Android may strip symbols in the packaged build. `useLegacyPackaging` extracts the executable to nativeLibraryDir, where Android permits execution. HTTP cleartext is permitted only for localhost/127.0.0.1; the server binds loopback only. A busy port is reported instead of attaching to an unrelated server.

JNI shared libraries use `libpocket-*` names to keep their dependencies separate from the server's `libllama*`/`libggml*` dependencies. JNI uses its directly linked CPU backend and does not scan the server's backend plugins. The server is app-owned and is stopped on cleanup/Activity teardown; it is stopped before text weights are loaded.

If the submodule worktree is incomplete or has unrelated changes, `-PllamaSourceDir=/absolute/path/to/complete/source` selects an isolated source tree without changing it. Record the actual source revision used. This integration was built from an isolated archive of the existing submodule HEAD `aa94f2086147cd4bfbcff818ce998dffce5fcb39`; the pre-existing modified submodule was left untouched.

`-PreflexTrial=true` builds **Pocket Agents Lab Reflex** as `com.pocketagentslab.reflex`, allowing installation beside a differently signed app without deleting its data. Normal builds retain the original application ID.

## Validation

JVM tests cover exact release-schema requests, HTTP response routing, all nine route mappings, decision-only completion, and normal backend behavior. `ReflexIntegrationTest` exercises real decision inference, text generation, then decision inference again, asserting that decision and text models are never ready together. It needs the released Reflex and normal Qwen GGUFs under the target app's `files/models/` directory.

These checks validate integration behavior, not a new accuracy/latency benchmark. Existing published results remain tied to their recorded protocol. Runtime logs are stored in app-private `files/reflex-runtime.log`.

## Completed A32 check (2026-10-17)

104 JVM tests and the real sequential-swap instrumentation test passed. A visible Tiny Agent battery request returned the live phone reading. [Smoke logs, APK/model hashes and exact configuration](../benchmarks/reflex-app-integration/2026-10-17/README.md). The trial APK is installed as Pocket Agents Lab Reflex; the original app was not replaced.
