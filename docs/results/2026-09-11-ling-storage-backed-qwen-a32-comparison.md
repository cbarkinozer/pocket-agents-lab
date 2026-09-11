# Galaxy A32: safe storage-backed Ling vs Qwen reference

Date: 2026-09-11

## Protocol

- Device: Samsung Galaxy A32, arm64, Android 13.
- Prompt: `What is 2+2?`
- Output limit: 8 tokens.
- System prompt: the same concise local test assistant prompt.
- Ling: `Ling-3.0-tiny-Q3_K_M.gguf`, 64 MiB cache budget, automatic
  `enableStorageBacked()` mode.
- Qwen: `Qwen3.5-0.8B-Q4_K_M.gguf`, normal mmap path.
- One sequential run per model; this is a smoke comparison, not a 50-case
  quality benchmark.

## Result

| Metric | Ling safe storage-backed | Qwen Q4 reference |
|---|---:|---:|
| Prompt latency | 6,407 ms | 2,233 ms |
| Output | coherent arithmetic explanation | `$2+2=4$` |
| Route events | 230 | not applicable |
| Direct page warms | 7,360 | not applicable |
| Direct page drops | 3,504 | not applicable |
| Duplicate cache bytes | 0 | not applicable |

The storage-backed mode completed successfully and preserved Ling output, but
Qwen was 2.87x faster for this short prompt. This is expected: page warming and
eviction currently add routing/page-management work while llama.cpp still uses
the normal tensor pointers. The result demonstrates feasibility and safe
operation, not a speed advantage.

The comparison also confirms that the safe Android mode does not require a
llama.cpp fork. The optional bounded-buffer `MUL_MAT_ID` fork remains recorded
as a future research experiment only; it is not part of the universal runtime.
