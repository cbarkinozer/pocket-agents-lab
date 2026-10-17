# Completed in-app benchmark — retrieved 2026-10-18

Run 1792263467671 completed both models on the same 50 development cases. This is **old Reflex beta versus Qwen**, not the released combined Reflex-1. The app-private selected file was verified by SHA-256: beta 8480ccc295ac7379106f33ad76c978da5d9e8da0785e827786fe9d17f5a5beb5. Released combined model abfea5f878331206f65e4117157984e14803b093b634c0ed52513c0723ef0699 is present separately as reflex-1-combined-Q4_K_M.gguf but was not selected in this queue.

| Model | Correct | Accuracy | Mean routing latency | Request errors |
| --- | ---: | ---: | ---: | ---: |
| Old reflex-1-beta | 19/50 | 38% | 28.40 s | 0 |
| Base Qwen JNI grammar | 41/50 | 82% | 50.04 s | 0 |

Beta selected phone_health_check on 41/50 requests. This matches its known lack of phone-specific adaptation; this result neither supports nor refutes the released combined model's published scores. Rerun the same suite with the combined artifact, preserving this baseline and its protocol.

Timing covers route selection, not tools or final generation. Reflex app-only PSS excludes the separate decision server and must not be compared with Qwen JNI as total memory. Models ran sequentially with different starting temperatures. Existing result header runtime fields contain stale hard-coded values; use integration build records for actual JNI/server revisions. No model fitting or W&B run was performed.

Raw results, queue and CSV files are preserved here. summary.json records derived categories, temperatures, route counts and identity caveats. No original phone results were modified.
