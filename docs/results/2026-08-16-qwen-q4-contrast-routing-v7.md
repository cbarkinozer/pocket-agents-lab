# Qwen3.5 0.8B Q4_K_M contrastive routing (v7)

Run ID: `1786875616698`  
Device: Samsung Galaxy A32 4G  
Frozen model: `Qwen3.5-0.8B-Q4_K_M.gguf`  
Suite: `tool-routing-3-tools-v7`, 50 fixed prompts  
State: completed successfully

Version 7 restored v5's single five-route grammar and changed only the prompt: ordered priority rules and contrastive examples targeted the direct-answer and Phone Health boundaries.

| Metric | v5 reference | v7 contrastive |
|---|---:|---:|
| Correct routes | **40/50** | 26/50 |
| Final schema accepted | 50/50 | 50/50 |
| Strict first pass | 50/50 | 50/50 |
| Repair attempts | 0 | 0 |
| Average case latency | 29.48 s | **25.55 s** |
| Median case latency | 29.30 s | **25.31 s** |
| Average TTFT | 27.26 s | **23.60 s** |
| Peak PSS | **756.9 MB** | 779.3 MB |
| Start / maximum battery temperature | not recorded / 40.5 C | 31.6 / 37.0 C |

| Route class | v5 | v7 |
|---|---:|---:|
| Storage | **10/10** | 2/10 |
| Device | **10/10** | 2/10 |
| Battery | **10/10** | 3/10 |
| Direct answer | 5/10 | **9/10** |
| Phone Health | 5/10 | **10/10** |

## Interpretation

The prompt successfully moved the intended boundaries, but far too aggressively. It gained nine combined correct cases in Direct Answer and Phone Health while losing 23 cases across the three previously perfect single-tool classes. Many ordinary single-tool requests became Phone Health; several became direct answers. The 26/50 total exactly matches v6 through a different failure distribution.

This demonstrates that prompt-level class balancing is fragile for the frozen 0.8B model. Version 5 remains the best overall router at 40/50. Further tuning against these same 50 labels risks test-set overfitting, so the project should freeze v5, add a separate held-out set before any later routing optimization, and proceed iteratively to the next product layer.

The full JSON and CSV artifacts remain in the app's private files directory on the phone.
