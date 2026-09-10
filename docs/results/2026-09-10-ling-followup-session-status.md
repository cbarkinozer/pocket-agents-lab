# Ling storage-backed MoE — follow-up session status

Date: 2026-09-10
Continuation of `docs/results/2026-09-10-ling-q3-android-feasibility.md`.

## Environment constraint

This session ran on a Windows workstation with no Android device attached
(`adb devices` reports no daemon/device; `adb` predates PATH in this shell).
Everything requiring a live Galaxy A32 — reproducibility repeats, the 50-case
pilot, thermal/energy measurement, and on-device verification of the new
telemetry counters — is **blocked on physical device access**, not on code.
The items below are what could be verified without the phone.

## Verified this session

- `./gradlew :app:testDebugUnitTest` passes, including the new
  `RuntimeTelemetryTest` (proc/stat parsing with spaces in the command name,
  key/value unit stripping, null-safe deltas).
- `./gradlew :app:assembleDebug` succeeds end-to-end, including a real
  `buildCMakeRelease[arm64-v8a]` run against the bumped llama.cpp submodule
  (`2a15f3a72dc99d0d04aff8aa8f812335a13612ae`, based on upstream
  `df03399b8` plus this repo's Android context-safety commits). This is the
  first confirmation that the updated pin still builds clean since the bump
  landed in the working tree.
- Added `tools/gguf_expert_offsets.py`: reads only a GGUF's header/tensor-info
  table (via the vendored `gguf-py`, `np.memmap`-backed, no full-file read)
  and computes each expert's absolute byte offset and length inside its
  layer's merged `blk.{N}.ffn_{gate,down,up}_exps.weight` tensor. Validated
  against a synthetic 4-expert GGUF built with `gguf.GGUFWriter`: computed
  offsets were checked against a second independent read of the raw bytes
  and matched exactly. This is Stage 9's first sub-step (expert offset
  index) from the TODO; bounded cache, history predictor, and async
  prefetch are not implemented yet.

## Still blocked on device access (README items 40, most of 41 and 42)

1. Repeat the 2048-token Ling smoke test three times for reproducibility.
2. Run the existing 50-case development pilot on Ling and on a freshly
   re-run Qwen3.5-0.8B Q4_K_M reference under the identical protocol.
3. Confirm the new `/proc`-based telemetry fields (`rss*Kb`, `filePss*Kb`,
   `swapPss*Kb`, `minorFaultsDelta`, `majorFaultsDelta`, `readBytesDelta`)
   populate correctly in `agent-evaluation.csv`/`agent-test-result.json` on
   the real A32 — they are unit-tested for parsing logic only, not exercised
   against a real `/proc` tree yet.
4. Record battery temperature and thermal-status gating behavior across a
   full pilot run, not just the single prompt already logged.

## Live A32 continuation (2026-09-11)

The device-blocked note below is superseded for the current session. ADB serial
`R68T800LZ0H` is attached and authorized. A corrected Ling run is active in
`com.pocketagentslab`; its checkpoint is
`files/agent-routing-1789070616481-Ling-3.0-tiny-Q3_K_M-result.json`.
Do not reinstall, force-stop, clear app data, or start another run until the
checkpoint reports `complete: true`. It uses 2048 context, CPU-only llama.cpp
commit `2a15f3a72dc99d0d04aff8aa8f812335a13612ae`, per-case thermal cooldown,
and a screen-off partial wake lock. Always read the checkpoint for current
progress rather than trusting an earlier snapshot.

The first six-case pilot was intentionally invalidated after reaching thermal
status 3. The current run has populated RSS, swap PSS, page-fault, and
read-byte telemetry on the real A32; `Pss_File` is null on this Samsung kernel
and must not be treated as zero. After completion, audit the observed cases
where raw JSON selected the expected tool but stored `actualRoute` as `answer`.

## Suggested next step when the phone is available

Reconnect the A32 over the existing wireless-adb setup documented in
`docs/tool-agent-evaluation.md`, install the debug build already verified
above, and run the item-40 comparison. No code changes should be required
first — the blocker is purely device access.
