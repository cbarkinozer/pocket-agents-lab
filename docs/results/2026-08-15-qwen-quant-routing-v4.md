# Qwen3.5 0.8B quantization comparison (v4)

Run ID: `1786815026698`  
Device: Samsung Galaxy A32 4G  
Suite: `tool-routing-3-tools-v4`, 50 fixed prompts per model  
State: Q3_K_M, Q4_K_M, and Q6_K completed successfully

| Metric | Q3_K_M | Q4_K_M | Q6_K |
|---|---:|---:|---:|
| GGUF size | 470.2 MB | 532.5 MB | 639.0 MB |
| Correct routes | 23/50 | 33/50 | 34/50 |
| Final schema accepted | 48/50 | 47/50 | 48/50 |
| Strict first pass | 45/50 | 41/50 | 44/50 |
| Successful repairs | 3/5 | 6/9 | 4/6 |
| Average case latency | 38.08 s | 35.92 s | 36.91 s |
| Median case latency | 33.39 s | 30.86 s | 33.49 s |
| Average TTFT | 30.21 s | 27.78 s | 30.34 s |
| Average exposed pieces/s | 0.846 | 0.811 | 0.673 |
| Peak PSS | 682.4 MB | 743.4 MB | 839.1 MB |
| Maximum battery temperature | 40.8 C | 40.9 C | 39.5 C |
| Model load | 11.31 s | 10.71 s | 10.70 s |

## Interpretation

Q6_K retained substantially more routing quality: 68% versus 46% accuracy. Q3_K_M saved about 157 MB of peak PSS and generated exposed pieces faster, but did not improve end-to-end latency because it produced more wrong or longer responses. On this workload, Q3_K_M is too aggressive unless minimum memory is the overriding goal.

Q4_K_M is the frozen quality/efficiency reference. Q6 gained only one correct route in one stochastic run while requiring about 96 MB more peak PSS, 107 MB more storage, lower throughput, and slightly higher latency. Q3's quality loss was too large. The freeze is an engineering choice, not a claim that Q4 is statistically more accurate than Q6.

After that controlled run, keep the chosen GGUF fixed while testing harness changes. Full JSON and CSV artifacts remain in the app's private files directory on the phone.
