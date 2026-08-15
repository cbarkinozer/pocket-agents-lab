# Qwen3.5 0.8B Q4_K_M grammar routing (v5)

Run ID: `1786822328834`  
Device: Samsung Galaxy A32 4G  
Frozen model: `Qwen3.5-0.8B-Q4_K_M.gguf`  
Suite: `tool-routing-3-tools-v5`, 50 fixed prompts  
State: completed successfully

| Metric | Q4 v4 free-form | Q4 v5 grammar |
|---|---:|---:|
| Correct routes | 33/50 | 40/50 |
| Final schema accepted | 47/50 | 50/50 |
| Strict first pass | 41/50 | 50/50 |
| Repair attempts | 9 | 0 |
| Average case latency | 35.92 s | 29.48 s |
| Median case latency | 30.86 s | 29.30 s |
| Average TTFT | 27.78 s | 27.26 s |
| Peak PSS | 743.4 MB | 756.9 MB |
| Maximum battery temperature | 40.9 C | 40.5 C |

## Accuracy by class

| Route class | Correct |
|---|---:|
| Storage | 10/10 |
| Device | 10/10 |
| Battery | 10/10 |
| Direct answer | 5/10 |
| Phone Health | 5/10 |

## Findings

Native GBNF grammar achieved its intended output-reliability goal: every routing response was strict valid JSON and no repair was needed. Accuracy increased by seven cases and average latency fell by about 18% relative to Q4 v4. TTFT changed little because the model still evaluates the full routing prompt before emitting the constrained choice.

All remaining failures lie at two semantic boundaries. Five ordinary writing/knowledge/reasoning requests unnecessarily selected a device tool. Five multi-category or overall-health requests selected only one constituent tool. The three individual live-tool classes were perfect at 30/30.

The next defensible harness experiment is hierarchical constrained routing: first classify `ANSWER` versus `LIVE_DEVICE`, then classify live requests as device, battery, storage, or Phone Health. This tests whether decomposing the two remaining decisions improves the same frozen weights. It must be versioned separately and should not rewrite this 40/50 baseline.

Full JSON and CSV artifacts remain in the app's private files directory on the phone.
