# Qwen3.5 0.8B Q4_K_M v5 replication

Run ID: `1786891802877`  
Device: Samsung Galaxy A32 4G  
Suite: frozen `tool-routing-3-tools-v5`, 50 fixed prompts  
State: completed successfully

This run was started while intending to run the separate Mobile Control safety suite. It is nevertheless a valid repetition of the frozen v5 protocol and is retained rather than discarded.

| Metric | Original v5 | v5 repeat |
|---|---:|---:|
| Correct routes | 40/50 | **44/50** |
| Strict schema | 50/50 | 50/50 |
| Final accepted | 50/50 | 50/50 |
| Repairs | 0 | 0 |
| Average latency | 29.48 s | **28.70 s** |
| Peak PSS | 756.9 MB | 759.9 MB |
| Start / maximum temperature | not recorded / 40.5 C | 33.7 / 38.4 C |

| Class | Original v5 | v5 repeat |
|---|---:|---:|
| Storage | 10/10 | 10/10 |
| Device | 10/10 | 10/10 |
| Battery | 10/10 | 10/10 |
| Direct answer | 5/10 | 7/10 |
| Phone Health | 5/10 | 7/10 |

The repeat strengthens the main v5 finding: all three individual tools were again perfect and native grammar again guaranteed valid output. The 40 versus 44 difference is stochastic single-run variation, so neither value should be presented as a stable population estimate. Future reporting should include repeated-run mean and variance.

This run did not exercise `mobile-control-actions-v1`; `action-safety-result.json` was therefore not created.
