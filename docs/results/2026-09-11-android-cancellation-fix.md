# Android JNI generation cancellation fix

The Qwen3.5-0.8B Q4 A32 pilot exposed a recovery problem in the official
Android binding: a generation that emitted many empty UTF-8 fragments could
remain inside the native token loop after the Kotlin timeout or cancel action.
The coroutine had no suspension point before the next JNI call, so the timeout
could not promptly reach the flow collector. One incomplete run had to be
force-stopped after `storage-02` to release its wake lock.

The binding now yields once before every `generateNextToken()` JNI call and
marks `_cancelGeneration` when the flow catches `CancellationException`. This
does not alter sampling or model output; it only makes cancellation observable
between native token calls. The fix is in nested llama.cpp commit
`59c5bb019`, referenced by parent commit `4d3d8a9`.

Verification:

- `testDebugUnitTest` passed.
- `assembleDebug` passed, including the real `buildCMakeRelease[arm64-v8a]`.
- The rebuilt APK installed successfully on the Galaxy A32 without a startup
  crash.
- A fresh Qwen Q4 pilot subsequently advanced beyond the former failure point
  (`storage-01`, `storage-02`, and `storage-03` completed at the time of this
  note), so the fix is exercising the device path. Its final 50-case result is
  recorded separately only after the queue reaches `complete: true`.

