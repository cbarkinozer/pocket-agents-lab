# xLAM and Qwen routing run (v2)

Run ID: `1786789416199`  
Device: Samsung Galaxy A32 4G  
Suite: `tool-routing-3-tools-v2`, 50 fixed prompts  
Queue state: completed successfully

| Metric | xLAM 2 1B FC-R Q4_K_M | Qwen3.5 0.8B Q4_K_M |
|---|---:|---:|
| Correct routes | 1/50 | 31/50 |
| Final schema accepted | 1/50 | 48/50 |
| Strict first-pass schema | 1/50 | 48/50 |
| Successful repairs | 0/15 | 0/2 |
| Average case latency | 84.26 s | 20.12 s |
| Median case latency | 70.89 s | 18.66 s |
| Average TTFT | 64.07 s | 16.38 s |
| Average exposed pieces/s | 0.247 | 0.994 |
| Peak PSS | 1175.5 MB | 732.4 MB |
| Start / maximum battery temperature | 33.1 / 40.0 C | 35.0 / 38.2 C |
| Cold model load | 20.42 s | 10.15 s |

## Interpretation

Qwen was substantially faster, lighter, and more compliant with the portable action schema. Its 19 route misses were mostly semantic selection errors across ambiguous device/storage/battery prompts, direct-answer distractors, and health-workflow paraphrases. It had no native crash during the completed run.

xLAM primarily emitted its own native function-call array format, for example `[{"name":"get_storage_info","arguments":{}}]`. Version 2 intentionally rejected that format, so its recorded 1/50 score measures portable-schema compliance more than raw tool selection. Applying the subsequently implemented v3 native-call rules to saved first attempts projects 27/50 correct and 30/50 accepted. This projection is not an official rerun and must not replace the v2 result.

## Changes prompted by this run

- `tool-routing-3-tools-v3` narrowly normalizes structurally valid xLAM native calls while continuing to reject empty, partial, duplicate, unknown, argument-bearing, or malformed calls.
- The v3 prompt explicitly separates physical RAM, manufacturer, model, Android, and ABI from filesystem capacity.
- The inter-model cooldown gate is 38 C for future runs; each artifact records its actual threshold.
- The UI uses one Agent Test action for either one selected GGUF or a sequential multi-model queue.

The full JSON and CSV artifacts remain in the app's private files directory on the test phone.
