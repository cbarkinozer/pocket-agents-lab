# Android expert cache MVP

Status: native cache foundation implemented; MoE graph dispatch integration is still pending.

## What was added

The Android native library now contains a small `ExpertCache` implementation:

- opens a model file read-only;
- reads an exact `(offset, length)` range with `pread`;
- stores ranges under a `(layer, expert)` key;
- enforces a fixed byte budget;
- evicts least-recently-used entries;
- accepts best-effort asynchronous prefetch requests on a worker thread;
- exposes the loaded llama.cpp tensor metadata as a compact expert index;
- has an opt-in llama scheduler callback for `ffn_moe_topk` telemetry;
- reports hits, misses, evictions, bytes read, and resident bytes;
- clears and closes safely on model lifecycle boundaries.

The Kotlin API is `com.arm.aichat.ExpertCacheRuntime`. It is intentionally not
called by the current inference path yet. The JNI API is a foundation for the
next step: connecting the cache to the BailingMoe3 expert dispatch. The current
`expertIndexJson()` safely reports loaded architecture metadata only; the
llama.cpp loader's tensor-weight map is temporary and must not be read after
model loading. A separate persistent GGUF header/tensor-info parser now
supplies offsets through `indexFileJson()` and `prefetchExpert()`.

## Current limitation

This is not yet an Edge0-equivalent runtime. The existing llama.cpp graph still
uses its normal tensor/mmap path. The cache can read expert byte ranges, but no
MoE operation has been redirected to those buffers. Therefore this change must
not be described as active-expert-only inference.

Keeping the cache separate first avoids changing Qwen, LFM, or existing Ling
inference semantics before the expert tensor access contract is understood.

## Verification

The following completed successfully after adding the cache source and JNI
entry points:

```text
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

This includes the real `buildCMakeRelease[arm64-v8a]` native build. No phone
runtime claim is made until a device run exercises `ExpertCacheRuntime`.

The device instrumentation test was then run on the authorized Galaxy A32 and
passed. It verified a 16-byte budget, two 8-byte resident entries, one cache
hit, at least one LRU eviction, and 24 bytes read from a temporary fixture.
The second test verified that an asynchronous prefetch request reads its range
and is subsequently served as a cache hit.
The telemetry toggle and pre-model-load lifecycle path also passed on-device.
The explicit Ling integration test was rerun after the fix and produced
`routeEvents=230` and `selectedExperts=81328` with no crash. The safe index
response reported `architecture=bailingmoe3`, `expertCount=128`, and
`status=metadata_only`.
The persistent parser was tested against the real 3.52 GiB Ling file without
loading weights: it reported `bailingmoe3`, 128 experts, and 69 merged expert
tensors. A separate device test opened the model with a 64 MiB cache budget and
prefetched layer 1 expert 0; the worker read its ranges from storage.

## Next implementation step

The route-to-cache path is now connected behind the opt-in telemetry switch.
The real Ling A32 integration produced `routeEvents=230`, `selectedExperts=81328`,
and `prefetchRequests=5657`, with no generation crash and unchanged test output.
The remaining work is a previous-token/transition predictor, followed by paired
cache/prefetch versus vanilla mmap measurements.
