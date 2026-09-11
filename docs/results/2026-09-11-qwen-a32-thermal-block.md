# Qwen3.5-0.8B Q4 Android rerun: thermal-gate observation

Date: 2026-09-11  
Device: Samsung Galaxy A32 4G (`SM-A325F`, Android 13, arm64-v8a)  
Model: `Qwen3.5-0.8B-Q4_K_M.gguf` (532,517,120 bytes)  
Suite: `tool-routing-3-tools-v5` (50 fixed cases, CPU-only, context 2048)

## Observation

Two unattended reruns were started from the Android Tests page with the
selected Q4 model. The first was interrupted by an unexplained coroutine
cancellation after the first case. The second reached the first case and then
waited at the deliberately conservative per-case thermal gate. It was not
marked complete and must not be scored as a 50-case result.

The second run's durable artifact records:

| Field | Value |
|---|---:|
| Run ID | `1789108168652` |
| Completed cases | 1/50 |
| First case | storage-01, correct, strict JSON |
| First-case latency | 54.6 s (TTFT 52.4 s) |
| First-case PSS | 788,269 -> 793,253 KiB |
| Start battery temperature | 37.9 C |
| Later battery temperature | 41.0 C |
| Thermal status | 2 (moderate) |
| USB powered | yes, battery level 100% |

The app's gate is `battery <= 38.0 C` and PowerManager thermal status <=
`LIGHT`. Once the phone was USB-powered and reached 41 C, the app correctly
paused before the next case instead of collecting a hot, incomparable sample.
The queue remained `running`; this is an incomplete run, not a model failure.

After the phone cooled below the gate, the second run resumed `storage-02` but
the native generation did not return within the ten-minute case timeout and
the UI became unresponsive to its cancel action. The process was force-stopped
at 09:54 to release the eight-hour partial wake lock and avoid holding the
phone warm overnight. The durable artifact therefore remains a partial 1/50
run; its queue manifest is intentionally not treated as complete.

## Interpretation

The previously completed on-device Qwen Q4 artifact remains the valid Q4
reference for now: run `1786822328834`, 40/50 correct with 50/50 accepted
schemas (see `docs/results/2026-08-15-qwen-q4-routing-v5.md`). It predates the
corrected Ling/Qwen feasibility protocol and therefore is not a substitute for
the planned same-protocol rerun.

For a comparable rerun, start from a cool, non-charging phone (or wait until
the thermal gate is satisfied), leave the app's wake lock enabled, and do not
touch the display during the run. Do not raise the threshold merely to finish:
that would change the measurement protocol and risk thermal-throttling bias.
