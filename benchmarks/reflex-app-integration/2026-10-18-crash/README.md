# Native switching crash fix — 2026-10-18

Actual crash: SIGSEGV in the integrated com.pocketagentslab.reflex app, native frame RoutingGrammar_setModeNative+72. Upstream JNI unload freed g_sampler/g_context/g_model but left their globals dangling. After a text answer, preparing the next decision unloads JNI. Later, the final-answer generator prepares grammar before loading Qwen again; the dangling model pointer passed the null guard and the freed sampler was freed a second time.

Fix: clear each global immediately after freeing it in the generated native binding. The helpers' existing null guards now correctly treat unloaded state as inactive.

Validation: 104 JVM tests pass. Expanded real A32 regression passes in 73.504 s, exercising two decision calls, two generated text answers, grammar/reset helpers after native unload, and those helpers again after complete cleanup. It reproduces the native operation omitted from the original smoke. Test details and stages are in device-regression.json; device-regression.log contains the passing instrumentation result. crash.log is the original native trace.

Updated APK installed over the integrated app without clearing its data; downloadable APK refreshed. Original app and all completed benchmark files retained. No 50-case benchmark was started for this fix, and model weights/results were unchanged.
