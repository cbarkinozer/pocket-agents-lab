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

It also has an opt-in `pageWarm` mode. In this mode a worker maps each
requested range, issues `madvise(MADV_WILLNEED)`, touches one byte per page, and
unmaps it. This warms the kernel page cache without retaining a second copy of
the expert bytes. The mode is exposed as `ExpertCacheRuntime.setPageWarm(true)`
and reports `pageWarm=true` in the stats JSON.

During routed Ling inference, the callback now additionally issues
`MADV_WILLNEED` against the selected slices of the already-loaded ggml tensor
mapping (`directPageWarmRequests`). This is the first path that targets the
actual llama.cpp tensor address rather than only a duplicate `pread` buffer.
It still does not replace ggml dispatch or evict unselected experts, so it is
an incremental storage-residency experiment rather than full Edge0 streaming.

An additional opt-in `ExpertCacheRuntime.setPageEvict(true)` mode now drops
clean pages for experts present in the previous route but absent from the new
route. It preserves the file mapping and lets Android fault the bytes back on
demand; `directPageDropRequests` records successful advice calls. A gated real
Ling inference with page-warm plus page-evict completed on the A32 without a
crash. This remains experimental and is not enabled by the normal app path.

`ExpertCacheRuntime.releaseAllExpertPages()` now provides a controlled cold
residency reset after model load by advising all loaded MoE tensor mappings with
`MADV_DONTNEED`. A32 instrumentation passed this reset followed by Ling
inference, so future A/B runs can start from a reproducible cold expert-page
state instead of relying on whatever the OS happened to retain.

When page-warm mode is enabled, routed inference no longer queues the
duplicate `pread` cache path; it only advises the actual loaded tensor mapping.
This keeps the Edge0-like residency experiment separate from the observational
copy-cache experiment and avoids measuring redundant I/O.

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
The gated page-warm inference test also completed successfully on the A32
(25.7 s instrumentation duration, no crash): selected Ling ranges were mapped,
page-warmed, and released with zero duplicate resident cache bytes.

## Next implementation step

The route-to-cache path is now connected behind the opt-in telemetry switch.
The real Ling A32 integration produced `routeEvents=230`, `selectedExperts=81328`,
`prefetchRequests=11774`, and `predictorRequests=5729`, with no generation
crash and unchanged test output. The first predictor reuses the previous
expert set per layer; it is intentionally a baseline, not a learned prerouter.
The first same-prompt A/B test measured 4,432 ms for vanilla mmap and 11,749 ms
for route/cache/predictor. The generated text was identical. The current cache
is observational: it reads selected ranges into a bounded buffer while ggml
still uses its normal mmap tensor pointers, so the extra I/O cost is expected.
This is evidence of capability, not a performance win. The next implementation
must connect cached/page-warmed ranges to actual ggml tensor access before
repeating cold/warm and budget comparisons. Page warming is therefore a safer
intermediate experiment, not an Edge0-equivalent expert residency mechanism.
