# Ling 3.0 Tiny Q3_K_M — Galaxy A32 routing pilot

Date: 2026-09-11  
Device: Samsung Galaxy A32 4G (`SM-A325F`, Android 13, arm64-v8a, 5.52 GiB RAM)  
Model: `Ling-3.0-tiny-Q3_K_M.gguf` (3,785,849,920 bytes, SHA-256 recorded in the feasibility report)  
Runtime: llama.cpp `2a15f3a72dc99d0d04aff8aa8f812335a13612ae`, CPU-only, 2048 context

## Protocol

This is the app's existing 50-case `tool-routing-3-tools-v5` development suite,
with the current nine-route grammar and deterministic relevance guardrail. Each
case starts from a fresh conversation. The run used strict grammar decoding,
600-second case timeout, and a thermal gate before every case: battery at most
38.0 C and Android thermal status at most `LIGHT`. The display was allowed to
turn off while the benchmark held a partial wake lock.

## Result

| Category | Correct | Valid JSON | Mean latency | Mean exposed pieces/s |
|---|---:|---:|---:|---:|
| Storage | 9/10 | 10/10 | 106.9 s | 0.142 |
| Device | 9/10 | 10/10 | 124.8 s | 0.123 |
| Battery | 10/10 | 10/10 | 103.2 s | 0.155 |
| Direct answer | 10/10 | 10/10 | 101.7 s | 0.104 |
| Phone health workflow | 6/10 | 10/10 | 103.9 s | 0.153 |
| **Total** | **44/50 (88%)** | **50/50 (100%)** | **108.1 s** | — |

All 50 first attempts were strict schema-valid and no repair was needed. Mean
post-case PSS was 3,393,888 KiB (3.24 GiB); maximum PSS
was 3,710,172 KiB. The run recorded 2,636 major page faults and 23,300,173,824
process read bytes summed across cases. `Pss_File` was unavailable on the
Samsung kernel and is serialized as JSON null, not zero. Temperatures remained
roughly 35.5–36.4 C during the valid run under the per-case thermal gate.

## Errors and interpretation

The six final-route misses were `storage-05`, `device-04`, and four health
cases. The first two model outputs selected the expected tool, but the
deterministic `isDecisionRelevant` guardrail converted them to a direct answer.
Three health prompts produced the workflow in raw JSON but were also rejected
by that relevance guardrail; one health prompt selected storage. Therefore the
pilot should report both raw model route and final orchestrated route in future
versions. The 44/50 score is the final app behavior, not a claim that the model
itself made six independent routing mistakes.

This validates Ling's ability to complete a full grammar-constrained Android
pilot without crashes or malformed output, but it does not make vanilla
llama.cpp equivalent to Edge0. The 3.8 GB GGUF is file-backed through mmap, yet
the process still reaches roughly 3.5 GiB PSS as pages become resident.

## Artifacts

The authoritative JSON and CSV remain app-private on the phone:

```powershell
adb exec-out run-as com.pocketagentslab cat files/agent-routing-1789070616481-Ling-3.0-tiny-Q3_K_M-result.json > ling-a32-routing.json
adb exec-out run-as com.pocketagentslab cat files/agent-routing-1789070616481-Ling-3.0-tiny-Q3_K_M.csv > ling-a32-routing.csv
```

The next controlled comparison is Qwen3.5-0.8B Q4_K_M under the identical
2048-token, thermal, timeout, and telemetry protocol. The Windows replay is a
proxy only and must not replace this paired Android measurement.
