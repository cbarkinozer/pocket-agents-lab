# Qwen3.5 0.8B quantization comparison (v4, partial)

Run ID: `1786815026698`  
Device: Samsung Galaxy A32 4G  
Suite: `tool-routing-3-tools-v4`, 50 fixed prompts per model  
State: Q6_K and Q3_K_M completed successfully

| Metric | Qwen3.5 Q6_K | Qwen3.5 Q3_K_M |
|---|---:|---:|
| GGUF size | 639.0 MB | 470.2 MB |
| Correct routes | 34/50 | 23/50 |
| Final schema accepted | 48/50 | 48/50 |
| Strict first pass | 44/50 | 45/50 |
| Successful repairs | 4/6 | 3/5 |
| Average case latency | 36.91 s | 38.08 s |
| Median case latency | 33.49 s | 33.39 s |
| Average TTFT | 30.34 s | 30.21 s |
| Average exposed pieces/s | 0.673 | 0.846 |
| Peak PSS | 839.1 MB | 682.4 MB |
| Start / maximum battery temperature | 33.3 / 39.5 C | 37.9 / 40.8 C |
| Model load | 10.70 s | 11.31 s |

## Interpretation

Q6_K retained substantially more routing quality: 68% versus 46% accuracy. Q3_K_M saved about 157 MB of peak PSS and generated exposed pieces faster, but did not improve end-to-end latency because it produced more wrong or longer responses. On this workload, Q3_K_M is too aggressive unless minimum memory is the overriding goal.

The earlier Q4_K_M result was 39/50 correct, 50/50 accepted, 28.0 seconds average latency, and 744.9 MB peak PSS, but it used v3. It is suggestive that Q4_K_M may be the best quality/efficiency point, not controlled proof. Run Q4_K_M once under v4 before freezing the model and quantization.

After that controlled run, keep the chosen GGUF fixed while testing harness changes. Full JSON and CSV artifacts remain in the app's private files directory on the phone.
