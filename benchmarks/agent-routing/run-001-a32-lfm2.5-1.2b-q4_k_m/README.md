# Run 001: Galaxy A32 / LFM2.5 1.2B Q4_K_M

First completed `tool-routing-3-tools-v1` device run, captured on 2026-08-15.

## Configuration

- Device: Samsung Galaxy A32 4G (`SM-A325F`)
- Android: 13
- ABI: `arm64-v8a`
- Model: `LFM2.5-1.2B-Instruct-Q4_K_M.gguf`
- Model bytes: 730,895,168
- llama.cpp: `a94d563ed801d1da1b8c2432946de07d0231bb3d`
- Context: 1024
- CPU-only, four inference threads
- Start battery temperature: 30.4 C

## Results

| Metric | Result |
|---|---:|
| Completed | 50/50 |
| Correct route | 13/50 (26%) |
| Counter labeled `validJson` in v1 | 6/50 |
| Repaired selections | 23 |
| Final accepted selections | 29/50 (58%) |
| Unrecoverable failures | 21/50 |
| Average latency | 42.3 s |
| Median latency | 40.7 s |
| P95 latency | 57.4 s |
| Summed generation latency | 35.3 min |
| PSS range | 825.1-851.4 MB |
| Temperature | 30.4 C to 36.5 C |
| Maximum temperature | 36.7 C |

Category correctness: storage 1/10, device 4/10, battery 0/10, direct answer 4/10, phone health 4/10.

The model over-selected `phone_health_check` (15 cases), never selected `get_battery_info`, and produced 20 invalid-schema failures plus one unknown-action/tool failure. The v1 `validJson` label is ambiguous: it counts selections accepted without a model repair, including any deterministic shorthand normalization; it is not a trustworthy strict first-pass JSON metric. Run 002 corrects the schema accounting and records raw generations in the artifact.

The JSON and CSV files in this directory are the unmodified device artifacts.
