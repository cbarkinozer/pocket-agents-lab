# Model adaptation roadmap

After the frozen multi-model baselines are complete, Pocket Agents Lab will test whether targeted adaptation improves small-model agent reliability. The hypothesis is that structured training can improve exact tool routing and JSON adherence more than prompt tuning alone.

## Experimental order

1. **SFT baseline** — supervised fine-tuning on correct routing, tool-call, repair, and grounded-answer examples.
2. **LoRA / QLoRA** — parameter-efficient SFT, beginning with QLoRA when full-weight training is impractical.
3. **Preference tuning** — DPO or a similarly constrained offline preference method using explicit chosen/rejected pairs.
4. **RL-style optimization** — only after deterministic rewards, rollback, regression tests, and stable training telemetry exist.

SFT, QLoRA, and DPO are not interchangeable labels: SFT describes the supervised objective, QLoRA describes a memory-efficient adapter training method, and DPO uses preference pairs. Each experiment must state its objective, adapter method, dataset, and execution location.

## Preserve the scientific baseline

Do not train on the fixed 50 evaluation prompts or paraphrases that are too close to them. Keep separate:

- training examples;
- validation examples used during development;
- frozen held-out routing evaluation;
- newly authored challenge prompts;
- general-capability regression tasks.

Pin the base-model hash, tokenizer, adapter configuration, seed, dataset version, prompt template, runtime commit, and sampling settings. Compare the adapted model with the exact unadapted base model under the same Android and thermal protocol.

Report at minimum:

- strict first-pass schema validity;
- final schema acceptance and repair rate;
- correct tool/workflow selection;
- false tool-call rate;
- held-out task accuracy;
- general-capability regressions;
- adapter size and storage;
- load time, TTFT, token/s, PSS, temperature, and energy where available.

An adaptation is useful only if held-out agent correctness improves without unacceptable general regressions or device cost.

## Off-device versus on-device training

Begin with training on a capable desktop or cloud machine and deploy only the adapter or merged quantized model to the Galaxy A32. This isolates whether the learning method works before making mobile training itself the research problem.

On-device experiments come later and should start with a 250M–500M model, tiny fixed dataset, supervised session, external power, conservative thermal stop rules, checkpoint recovery, and enough free storage. Measure training RAM, samples/second, energy, temperature, throttling, total time, and whether the resulting adapter can be loaded reliably.

## Candidate datasets

- valid and invalid JSON-schema transformations;
- paraphrased Android tool requests;
- similar-tool and distractor-tool choices;
- explicit no-tool/direct-answer examples;
- Phone Health workflow versus single-tool distinctions;
- validator error followed by one correct repair;
- grounded final answers that do not alter deterministic facts.

Failures from benchmark runs are valuable for authoring training data, but the exact failed evaluation prompts must remain held out. Create new examples that represent the failure class instead of copying the test case.

## Decision gates

Proceed from SFT/QLoRA to DPO only if the supervised adapter gives a repeatable held-out improvement. Proceed to RL-style methods only if a deterministic reward can score schema validity, route correctness, safety, latency, and unnecessary tool calls without rewarding shortcuts. Never optimize only JSON validity; a perfectly formatted wrong tool call is still wrong.
