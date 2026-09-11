# Galaxy A32 corrected routing pilot: Ling vs Qwen

Date: 2026-09-11  
Device: Samsung Galaxy A32 4G (`SM-A325F`, Android 13, arm64-v8a, 5.5 GiB RAM)  
Protocol: `tool-routing-3-tools-v5`, 50 fixed prompts, context 2048, CPU-only,
same llama.cpp commit `2a15f3a72dc99d0d04aff8aa8f812335a13612ae`, strict thermal
gate (battery <=38 C and PowerManager status <= LIGHT), fresh routing turn per
case, no tool execution.

The primary artifacts are the private-device files:

- Ling: `agent-routing-1789070616481-Ling-3.0-tiny-Q3_K_M-result.json`
- Qwen: `agent-routing-1789110056566-Qwen3.5-0.8B-Q4_K_M-result.json`

## Result summary

| Metric | Ling Q3_K_M | Qwen Q4_K_M |
|---|---:|---:|
| Correct routes | 44/50 (88%) | 41/50 (82%) |
| Strict valid first-pass JSON | 50/50 | 50/50 |
| Repair attempts | 0 | 0 |
| Average case latency | 108.1 s | 53.0 s |
| Median case latency | 103.6 s | 51.3 s |
| Min–max latency | 99.2–172.9 s | 48.0–64.1 s |
| Model load time | 90.0 s | 36.7 s |
| Average post-case PSS | 3,393,888 KiB | 780,270 KiB |
| Peak post-case PSS | 3,710,172 KiB | 808,871 KiB |
| Post-case temperature range | 35.4–37.2 C | 35.4–37.9 C |

Qwen is approximately 2.0x faster per case, 2.5x faster to load, and uses
about 4.3x less post-case PSS in this Android run. Ling is modestly more
accurate overall (three additional routes), but its advantage is not uniform:
both models scored 9/10 storage and 9/10 device; Qwen scored 9/10 battery
versus Ling's 10/10; both scored 10/10 direct answers; and both struggled with
the multi-tool health workflow (Qwen 4/10, Ling 6/10).

## Error pattern

Qwen's ten misses were:

- one storage request answered directly;
- one device request answered directly;
- one battery request answered directly;
- six health requests answered directly or routed to one tool;
- six health cases in total were therefore not reliably recognized as the
  deterministic multi-tool workflow.

Ling's six misses had the same shape: one storage, one device, and four health
cases. The models produced valid constrained JSON in every accepted case, so
the remaining problem is semantic route selection, not JSON reliability.

## Measurement caveats

PSS and latency are directly comparable because the Android harness, context,
sampling, telemetry, and case order were pinned. The `readBytesDelta` field is
not a quality score: it is strongly affected by Linux file-cache state and
whether the large mmap'd model pages were already resident. The Qwen run also
required deliberate thermal cooldown periods while USB-powered; those waits
are excluded from each case's generation latency but are part of the device's
real unattended operating cost.

The Qwen run initially exposed a cancellation hang at case two. The binding
was fixed in nested commit `59c5bb019` (parent `4d3d8a9`) and the complete run
above was performed after that fix. The final comparison is therefore valid
for routing/latency/PSS, while a future energy study should repeat both models
with a controlled charging state and multiple randomized repeats.

## Decision

For the current Galaxy A32 agent prototype, Qwen Q4 is the better efficiency
point: it retains 82% routing accuracy while cutting latency and resident
memory roughly in half and by more than fourfold respectively. Freeze neither
model as universally superior yet. Freeze this protocol and run at least
three repeats per model before fine-tuning; use the sealed health-workflow
failures as the first adaptation target while retaining external regression
sentinels.
