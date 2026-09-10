# Ling 3.0 Tiny Q3_K_M Android feasibility

Date: 2026-09-10  
Device: Samsung Galaxy A32 4G (`SM-A325F`, 5.52 GiB physical RAM)  
Model: `Ling-3.0-tiny-Q3_K_M.gguf`  
Model SHA-256: `62f7117aa4e6cbf8d366cabf99c75c72e62332d3ff8ea2f98f5688d3f513252f`  
Model bytes: 3,785,849,920  
Architecture: `bailingmoe3`, 7.89B total / 1.3B active, 128 experts / 8 selected  
llama.cpp base: `df03399b885831b2a1603b3abb0d8c156808e363`, plus the repository's Android safety/context commits

## Result

The Q3_K_M model loaded and completed a real local inference in the Android app. llama.cpp reported
`load_mode = mmap`, a 3,604.25 MiB CPU-mapped model buffer, a 6.75 MiB KV buffer at the initial
1024-token trial context, a 19.27 MiB recurrent-state buffer, and a 320.01 MiB compute buffer.
Android reported approximately 3.45 GiB total PSS and 3.48 GiB RSS after inference, including about
53 MiB swap PSS. The app remained alive.

The prompt `What is 2+2?` ultimately produced the valid constrained decision
`{"action":"answer","text":"4"}` and displayed `4`. The first route attempt was not a valid result:
the 1024-token context truncated 88 routing tokens and stopped at the safe context boundary. The
existing one-shot constrained repair then produced the correct JSON. Timing from the first routing
submission through repaired output was about 207.4 seconds (178.6 seconds until the rejected empty
route, then 28.8 seconds for repair and generation). This is feasibility evidence, not a valid
accuracy or speed benchmark result.

## Interpretation

This proves that a GGUF larger than the phone's initially available RAM can run through file-backed
`mmap` on this device. It does **not** reproduce Edge0. Vanilla llama.cpp relies on kernel demand
paging and had most of the mapped weights resident by the end of this run. It has no trained
prerouter, explicit bounded expert cache, asynchronous expert prefetch, or Recover-LoRA adapters.

The run also found a protocol defect: previous app telemetry documented a 1024-token context while
the inherited Android native default was actually 8192. A literal 1024-token correction was too
small for the current agent prompt. The reproducible configuration is therefore moved to 2048 and
must be revalidated before model comparisons.

## Corrected 2048-token repeat

The rebuilt app repeated the same prompt with `n_ctx = 2048`. No prompt truncation or context
overflow was logged. From tapping Run Agent through the displayed answer took about 339.6 seconds,
including cold model loading and system-prompt processing. Model loading completed about 18 seconds
after the tap; system-prompt processing completed at 98.7 seconds. The routing turn took about
210.3 seconds and selected `answer`; the separate direct-answer turn completed in about 30.1
seconds and displayed `2+2 equals 4.` No validator repair was used.

After completion Android reported 3,715,751 KiB total PSS, 3,676,990 KiB total RSS, and 79,273 KiB
swap PSS. The process had accumulated 215,290 minor and 1,495 major faults since launch. Battery
temperature rose from 36.9 C immediately after starting the repeat to 38.2 C after completion while
connected over USB. These process-wide fault counters are feasibility telemetry, not yet per-request
storage-read measurements.

## Next controlled experiments

- Repeat the one-prompt smoke test at 2048 tokens without truncation or overflow-induced repair.
- Run the development 50-case routing pilot only after the complete prompt fits.
- Record wall time, schema/route accuracy, PSS/RSS/swap, temperature, and page faults.
- Compare vanilla `mmap` against later expert-aware cache/prefetch work; do not call vanilla mmap Edge0-equivalent.
- Keep Qwen3.5-0.8B Q4_K_M as the dense reference, but rerun it under the corrected context before a paired comparison.
