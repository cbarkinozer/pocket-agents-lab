# Reflex-1 app integration smoke — 2026-10-17

Integrated the released combined Clef GGUF into the Android app using its own packaged Decision API runtime. 104 JVM tests passed (zero failures/errors/skips); one real A32 instrumentation test passed in 61.549 s. The test routes a storage request, stops decision inference before loading Qwen, generates a greeting, unloads Qwen, then routes a battery request with Reflex again. It asserts model readiness/process state at each boundary and explicitly cleans up.

A visible Tiny Agent request, “What is my battery percentage?”, then returned the phone's live battery level and temperature. This is functional integration evidence, not a new benchmark or a claim that published routing accuracy measures assistant completion quality.

The isolated trial app `com.pocketagentslab.reflex` was installed beside the existing app to preserve its differently signed data. The original app and its private data were retained. Source paths, runtime flags, APK checksum and unit counts are in report.json; device model hashes match the released combined checkpoint and Qwen baseline. The working test APK is app/build/outputs/apk/debug/app-debug.apk (Git-ignored).

Logs: build.log, device-smoke.log, device-smoke.json, runtime.log, and device-model-sha256.txt. A test-harness return-type error introduced while adding the report export was fixed before the passing final run; its log is retained as test-harness-correction.log. An earlier successful smoke using buffered full metadata parsing took 89.268 s; the shipped header-only runtime selection and final report use the 61.549 s passing run. This difference is not a latency benchmark.

No GPU rental, W&B run or model training was used. Electricity was not measured. Existing submodule modifications were preserved; native compilation used an isolated archive at aa94f2086147cd4bfbcff818ce998dffce5fcb39. See docs/reflex-1-app-integration.md for rebuilding and limitations.
