# Three-model routing comparison (v3)

Run ID: `1786801327033`  
Device: Samsung Galaxy A32 4G  
Suite: `tool-routing-3-tools-v3`, 50 fixed prompts per model  
Queue: xLAM 2 1B FC-R, Qwen3.5 0.8B, LFM2.5 1.2B  
State: all three models completed successfully

| Metric | xLAM 2 1B | Qwen3.5 0.8B | LFM2.5 1.2B |
|---|---:|---:|---:|
| Correct routes | 33/50 | 39/50 | 2/50 |
| Final schema accepted | 37/50 | 50/50 | 17/50 |
| Strict first pass | 0/50 | 45/50 | 0/50 |
| Normalized first pass | 37/50 | 0/50 | 1/50 |
| Successful repairs | 0/0 | 5/5 | 16/49 |
| Average case latency | 79.7 s | 28.0 s | 64.65 s |
| Median case latency | 70.9 s | 18.7 s | 66.08 s |
| Average TTFT | 64.1 s | 16.4 s | 38.55 s |
| Average exposed pieces/s | 0.247 | 0.994 | 0.542 |
| Peak PSS | 1169.5 MB | 744.9 MB | 849.9 MB |
| Maximum battery temperature | 40.5 C | 40.0 C | 40.5 C |
| Model load | 19.24 s | 10.65 s | 16.12 s |

## Findings

Qwen was the clear winner on this device and protocol: best accuracy, perfect final schema acceptance, lowest memory, and substantially lower latency. Its GGUF and parameter count are also materially smaller than xLAM's despite xLAM's 1B label.

xLAM improved from 1/50 under v2 to 33/50 after v3 recognized its structurally valid native function-call arrays. Its remaining failures were dominated by ordinary-answer requests emitted as prose or empty arrays, plus a few genuine wrong tool selections. Version 4 additionally maps exact two-category native bundles to deterministic Phone Health.

LFM frequently collapsed unrelated requests into `phone_health_check`. It made 49 repair attempts, produced only 17 accepted final schemas, and missed 48 routes. This is primarily a routing-capability/prompt-alignment failure, not a runtime crash or a parser issue.

## Follow-up protocol

Version 4 clarifies live battery facts, ordinary no-tool questions, combined health checks, and AI-workload readiness. It also accepts exact two- or three-category xLAM native bundles as the deterministic health workflow. Version 3 results above remain unchanged and should be compared separately from future v4 runs.

Full JSON and CSV artifacts remain in the app's private files directory on the test phone.
