# Qwen3.5 0.8B Q4_K_M hierarchical routing (v6)

Run ID: `1786872521956`  
Device: Samsung Galaxy A32 4G  
Frozen model: `Qwen3.5-0.8B-Q4_K_M.gguf`  
Suite: `tool-routing-3-tools-v6`, 50 fixed prompts  
State: completed successfully

Version 6 split routing into two constrained decisions: first `answer` versus `live_device`, then device, battery, storage, or Phone Health for live requests. Version 5 used one constrained five-route decision.

| Metric | v5 single-stage | v6 hierarchical |
|---|---:|---:|
| Correct routes | **40/50** | 26/50 |
| Final schema accepted | 50/50 | 50/50 |
| Strict first pass | 50/50 | 50/50 |
| Repair attempts | 0 | 0 |
| Average case latency | 29.48 s | **18.70 s** |
| Median case latency | 29.30 s | **21.34 s** |
| Average TTFT | 27.26 s | **6.46 s** |
| Peak PSS | 756.9 MB | **751.2 MB** |
| Start / maximum battery temperature | not recorded / 40.5 C | 30.1 / 36.0 C |

## Accuracy by class

| Route class | v5 | v6 |
|---|---:|---:|
| Storage | **10/10** | 7/10 |
| Device | **10/10** | 3/10 |
| Battery | **10/10** | 7/10 |
| Direct answer | 5/10 | **7/10** |
| Phone Health | **5/10** | 2/10 |

## Interpretation

The hierarchical experiment regressed accuracy by 14 cases and must not replace v5. The first scope decision incorrectly classified six device-information requests and one health request as direct answers. The second decision also over-selected `get_device_info` and confused single battery requests with Phone Health. Decomposing a decision is therefore not automatically easier for this model: the shorter stage prompts removed distinctions that the five-way v5 prompt expressed more effectively.

The latency result is real but not a quality win. Direct-answer cases require only the scope generation, while live requests require two generations. The substantially lower TTFT also reflects a shorter prompt and a cooler run, so it should not be attributed solely to hierarchy. Memory use was essentially unchanged.

Version 5 remains the best measured harness: 40/50 correct with perfect schema validity. The next harness change should be narrow and evidence-driven: retain the v5 five-choice grammar, make direct-answer and Phone Health boundaries more explicit with concise examples or deterministic pre-routing for unambiguous health commands, and evaluate it as a new version on the same fixed cases. Do not rewrite or rescore v5 or v6.

The full JSON and CSV artifacts remain in the app's private files directory on the phone.
