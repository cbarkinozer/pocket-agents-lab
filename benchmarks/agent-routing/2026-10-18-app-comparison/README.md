# Completed in-app routing comparison — retrieved 2026-10-18

User-completed released Reflex run: 1792273819214 (50/50 complete). Saved Qwen baseline: 1792263467671 (50/50 complete). IDs, prompt text and expected routes match exactly. Combined file on the phone matches released SHA abfea5f878331206f65e4117157984e14803b093b634c0ed52513c0723ef0699.

| Model | Correct | Accuracy | Mean routing latency | Request errors |
| --- | ---: | ---: | ---: | ---: |
| Released Reflex-1 combined | 49/50 | 98% | 28.03 s | 0 |
| Base Qwen JNI grammar | 41/50 | 82% | 50.04 s | 0 |
| Old reflex-1-beta (earlier run) | 19/50 | 38% | 28.40 s | 0 |

Combined Reflex improves accuracy by 16 percentage points and lowers mean routing latency by 44.0% (Qwen mean / Reflex mean = 1.79). This supports improvement on this app's 50-case development suite, not unrestricted assistant performance. No tool execution or final prose is included in these timings. These cases differ from the separate published 180-case MOBILE_ROUTING9 cohort.

Reflex category results: storage 9/10; device, battery, direct answer and phone-health workflow each 10/10. Only error: storage-07, 'Check available space, not battery level.', expected tool:get_storage_info, selected workflow:phone_health_check.

Both used the same app's research mode. Runtime implementations/prompts differ (decision HTTP versus JNI grammar); starting battery temperatures were 27.2 C for Reflex and 34 C for Qwen. Runs were sequential, not randomized repeated trials. App-only PSS excludes Reflex's separate server and cannot establish a total RAM advantage. Result header runtime fields are stale hard-coded values; integration build provenance records actual runtime revisions. No thermal, energy or architecture-only causal win is claimed.

Raw JSON/CSV/queue files from both user-completed runs are preserved. summary.json includes category details, paired counts and exact McNemar statistic. The earlier beta result remains distinct. An agent-initiated accidental rerun was stopped with zero completed cases and excluded; its backed-up empty files and explanation are in accidental-run-stopped/.
