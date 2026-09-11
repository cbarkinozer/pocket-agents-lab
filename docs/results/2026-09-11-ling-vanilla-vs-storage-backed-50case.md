# Ling vanilla mmap vs storage-backed MoE on Galaxy A32

Date: 2026-09-11

Both runs used the same 50-case `tool-routing-3-tools-v5` suite, 2048-token
context, CPU-only llama.cpp build, grammar, case order, thermal gate, and
device. The vanilla result is the previously completed reference run. The
storage-backed run used the app's non-fork mode: mmap tensor pages, selected
page warm, previous-route page eviction, and telemetry.

| Metric | Vanilla mmap Ling | Storage-backed Ling |
|---|---:|---:|
| Correct final routes | 44/50 (88%) | 43/50 (86%) |
| Strict valid JSON | 50/50 | 50/50 |
| Repairs | 0 | 0 |
| Average case latency | 108.1 s | 104.4 s |
| Median case latency | 103.6 s | 104.3 s |
| Min-max latency | 99.2-172.9 s | 100.8-107.3 s |
| Model load | 90.0 s | 85.0 s |
| Average post-case PSS | 3,393,888 KiB | 1,085,531 KiB |
| Peak post-case PSS | 3,710,172 KiB | 1,092,960 KiB |
| Major faults (sum) | 2,636 | 2,199 |
| Post-case temperature | 35.4-37.2 C | 31.4-37.4 C |

The storage-backed run completed all 50 cases without a crash, timeout, or
repair. It was 1 route lower than vanilla (43 vs 44), which is within the
observed run-to-run variation and does not indicate that page management
improves or harms model quality. Strict JSON remained 50/50 in both runs.

The important result is memory behavior: storage-backed mode reduced average
post-case PSS by about 68% and peak PSS by about 71% while keeping latency in
the same range. Its latency distribution was also much tighter because no
case experienced the vanilla run's 172.9 s outlier. These measurements show a
real memory-residency benefit for the Android approximation, not an accuracy
benefit. The experiment does not establish energy or sustained-throughput
improvement; those require a controlled charging and longer-duration study.

Artifacts on the device:

```text
files/agent-routing-1789135094873-Ling-3.0-tiny-Q3_K_M-result.json
files/agent-routing-1789135094873-Ling-3.0-tiny-Q3_K_M.csv
```
