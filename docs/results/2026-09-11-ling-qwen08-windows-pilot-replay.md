# Windows replay of the 50-case routing pilot: Ling-3.0-tiny-Q3_K_M vs Qwen3.5-0.8B

Date: 2026-09-11. Follow-up to `docs/results/2026-09-11-ling-q3-windows-feasibility.md`.

**Read the caveats before the numbers.** This is a same-protocol comparison
run on a Windows CPU via `llama-server`, not an on-device result, and one run
(no fixed seed) is not a statistically adequate sample. It should not be
quoted as "Ling beats Qwen" without the qualifications below.

## What was actually reproduced

`tools/replay_agent_pilot.py` transliterates, verbatim where possible, from
current HEAD:

- `AGENT_SYSTEM_PROMPT` and the 50 `AGENT_TEST_CASES` (`MainActivity.kt`).
- `buildRoutingPrompt(userPrompt, allowDeviceActions=false)` and the
  `isDecisionRelevant` guardrail (`AgentBackend.kt`). `runAgentTests`
  constructs `AgentBackend` without `allowDeviceActions`, which defaults to
  `false`, so the evaluation harness always uses the 9-route
  `POCKET_ROUTING_GRAMMAR` (`RoutingGrammar.ROUTE`, mode 1) - **not**
  `POCKET_AGENT_ROUTING_GRAMMAR` (mode 4, the propose-action grammar used by
  the interactive product UI). An earlier draft of this script used the wrong
  grammar/prompt pair and was discarded before being reported.
- The GBNF grammar itself, copied from
  `llama-android/src/main/cpp/CMakeLists.txt`'s `POCKET_ROUTING_GRAMMAR`
  string-patch.
- `DEFAULT_SAMPLER_TEMP = 0.3` from `ai_chat.cpp`.
- Scoring: `actualRoute == expectedRoute(case)`, matching `runAgentTests`.

**Important naming caveat:** this reproduces *current HEAD's* prompt/grammar,
not necessarily the specific historical snapshot the docs call "frozen v5."
`git log` shows `AgentBackend.kt` was touched by 15+ commits after "fix(agent):
restore frozen v5 routing baseline" (adding notes/files/calendar/alarm/media
routes), so today's prompt is a superset/evolution of that snapshot, not a
byte-identical copy. Do not treat the numbers below as directly comparable to
the historical "44/50" or "40/50" figures in README/`tool-agent-evaluation.md`.

**Not reproduced:** the Android JNI path itself (this uses `llama-server`'s
HTTP `/v1/chat/completions` with `--jinja` and `chat_template_kwargs:
{"enable_thinking": false}`, which should render an equivalent prompt to the
app's fresh-turn formatter, but was not diffed byte-for-byte against JNI
output). No seed was pinned, and temp=0.3 means real sampling variance exists
between runs.

## Results (single run each, no repeats)

| Model | Correct | Accuracy |
|---|---|---|
| Ling-3.0-tiny-Q3_K_M | 44/50 | 88.0% |
| Qwen3.5-0.8B-Q4_K_M | 15/50 | 30.0% |

Ling's per-category misses were concentrated in `health` (4/10 wrong,
mostly falling back to a single tool or `answer` instead of the
`phone_health_check` workflow) plus one `storage` and one `device` miss.
Qwen's misses were broad: it defaulted to `answer` for the large majority of
`storage`/`device`/`battery`/`health` cases.

## Why the Qwen number should not be trusted as a capability finding

Before landing on this grammar/prompt pair, an earlier (incorrect) run used
`POCKET_AGENT_ROUTING_GRAMMAR` (mode 4, the larger propose-action grammar)
with its correspondingly longer prompt. Under that mismatched setup Qwen
scored 31/50 (62%) - roughly double this run's 15/50 (30%) under the
*correct* grammar/prompt pair, using the exact same model weights and
sampler settings. A small model's accuracy should not swing this hard between
two structurally similar grammars/prompts unless something about the
harness (not the model's real capability) differs from the on-device path in
a way this session did not track down. Candidate causes not yet ruled out:
sampler-chain ordering differences between `llama-server`'s default sampling
pipeline and `common_sampler_init`, or a subtle prompt-formatting mismatch
between this script's Jinja rendering and the JNI binding's incremental
formatter. Until one of these is confirmed, **treat the absolute Qwen number
as unreliable and do not cite it as "Qwen underperforms Ling here."** Ling's
44/50 happens to numerically match the historical Qwen "frozen v5
replication: 44/50" figure in README item 11, which is a reassuring
coincidence about this harness's general sanity, but is not proof the same
undiagnosed issue is absent from the Ling run too.

## What this session does support

- The harness mechanics work correctly: both models produced grammar-valid
  JSON for essentially every case (no parse failures were observed), request/
  response plumbing and scoring logic are functioning as designed.
- Ling-3.0-tiny-Q3_K_M can complete a full 50-case grammar-constrained routing
  pilot without crashing, timing out, or producing malformed output - useful
  supporting evidence for README item 40 even though it is not the on-device
  measurement that item ultimately requires.
- Small instruction-tuned models' grammar-constrained routing accuracy can be
  highly sensitive to the exact prompt/grammar variant, which is itself a
  useful methodological warning for anyone comparing routing accuracy across
  prompt versions: pin the exact grammar together with the exact prompt text,
  never just the "route count."

## Next steps

- Before quoting a Qwen number from this harness, diagnose the 62% vs 30%
  swing (likely: dump the exact rendered prompt text from `llama-server`
  alongside a logged JNI prompt dump from the app, byte-diff them).
- Repeat both models 3x with different seeds to get a variance estimate
  before treating any single accuracy figure as meaningful.
- The device-blocked on-device replay (README item 40) remains the
  authoritative measurement; this Windows replay is a faster-iteration
  proxy, not a substitute.
