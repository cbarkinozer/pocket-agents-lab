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
- reports hits, misses, evictions, bytes read, and resident bytes;
- clears and closes safely on model lifecycle boundaries.

The Kotlin API is `com.arm.aichat.ExpertCacheRuntime`. It is intentionally not
called by the current inference path yet. The JNI API is a foundation for the
next step: connecting the cache to the BailingMoe3 expert dispatch.

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

## Next implementation step

Add an opt-in BailingMoe3 dispatch hook that records selected experts and probes
the cache without changing tensor values. Once telemetry-only parity is shown,
route expert weight reads through the bounded cache and compare it with vanilla
mmap.
