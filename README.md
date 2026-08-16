# pocket-agents-lab
A playground for pushing local AI agents as far as possible on edge/mobile and local devices.
# Pocket Agents Lab

**A playground for pushing local AI agents as far as possible on mobile devices.**

`pocket-agents-lab` is an experimental open-source repository for building, testing, and benchmarking AI agents that run directly on consumer mobile devices, with an initial focus on Android.

The goal is simple:

> **Make useful AI agents available to everyone by making them local, lightweight, private, practical, and easy to run.**

Instead of assuming that capable agents require datacenter-scale models, constant internet access, or expensive cloud APIs, Pocket Agents Lab explores what can be achieved with Small Language Models (SLMs), local tools, code execution, retrieval, structured memory, and native mobile capabilities.

The project is intentionally PoC-driven.

We will build many small experiments, measure what works, discard what does not, and gradually discover how capable a fully local mobile agent can become.

The canonical layered and iterative plan is maintained in [`docs/roadmap.md`](docs/roadmap.md). Each layer advances to a measurable MVP, then development moves across the stack and returns where evidence shows the highest-value improvement.

---

# Vision

Today, most advanced AI agents live in the cloud.

Pocket Agents Lab asks:

> **What if the agent lived inside your phone?**

Not simply a chatbot running locally.

A real agent that can:

* understand requests,
* search local information,
* reason over files,
* call device tools,
* generate code,
* execute controlled programs,
* inspect device state,
* manage information,
* build small utilities,
* diagnose problems,
* suggest fixes,
* learn reusable workflows,
* and interact with the operating system within clearly defined permissions.

Eventually, the phone becomes more than a device with applications.

It becomes a programmable environment controlled by a local AI agent.

```text
User
 │
 ▼
Local SLM
 │
 ├── Reason
 ├── Search
 ├── Retrieve
 ├── Generate code
 ├── Call tools
 ├── Query device state
 └── Use memory
 │
 ▼
Android / Local Runtime
 │
 ├── Files
 ├── Databases
 ├── Sensors
 ├── Applications
 ├── System APIs
 ├── Local code
 └── User-approved actions
```

---

# Mission

Pocket Agents Lab has three long-term goals.

## 1. Make agents local

Core capabilities should work without depending on cloud inference.

Whenever possible:

```text
user data
prompts
documents
embeddings
memory
tool results
generated code
```

remain on the device.

---

## 2. Make agents useful

A local model that can only chat is not enough.

We want agents that can actually accomplish tasks.

The central hypothesis is:

> **A small model surrounded by good tools may be far more capable than the model alone.**

Capability can come from:

```text
Small Language Model
+
Tool Use
+
Code Generation
+
Code Execution
+
Retrieval
+
Memory
+
Operating-System APIs
+
Execution Feedback
```

---

## 3. Make agents accessible

Eventually, local agents should not require AI engineering knowledge.

The ideal experience is closer to:

```text
Install
↓
Choose capabilities
↓
Grant permissions
↓
Use your agent
```

rather than:

```text
Configure CUDA
↓
Run server
↓
Set API keys
↓
Configure vector database
↓
Write agent framework
↓
Debug environment
```

The long-term ambition is:

> **Everyone should be able to have a personal AI agent.**

---

# Why Mobile?

Smartphones are one of the most interesting environments for local AI.

They combine:

* CPU
* GPU
* neural accelerators
* several gigabytes of memory
* persistent storage
* cameras
* microphones
* accelerometers
* gyroscopes
* GPS
* Bluetooth
* Wi-Fi
* cellular connectivity
* battery telemetry
* notifications
* local databases
* personal files
* application state
* user interaction
* operating-system APIs

And billions of people already carry one.

This makes mobile devices an ideal testbed for democratizing local agents.

The initial platform will be:

> **Android first.**

Android offers a relatively open environment, wide hardware diversity, native APIs, application sandboxing, and enough system access to explore meaningful agent capabilities.

---

# Core Research Question

The main question behind Pocket Agents Lab is:

> **How capable can a fully local AI agent become on a consumer Android phone?**

This breaks down into many smaller questions:

* How small can the model be?
* How much RAM is required?
* How fast can inference be?
* Can CPU-only inference be useful?
* When should GPU or NPU acceleration be used?
* How much does quantization affect quality?
* Can tools compensate for model size?
* Can code execution compensate for model size?
* Can retrieval compensate for limited context windows?
* Can persistent memory make tiny models more useful?
* Can small models reliably select tools?
* Can they repair failed code?
* Can they create new reusable tools?
* Can local agents automate meaningful phone tasks?
* How much energy does an agent consume?
* How quickly does sustained inference cause thermal throttling?
* What permissions are required for useful Android automation?
* Which actions should always require user confirmation?
* Can useful agents remain completely offline?

---

# Project Philosophy

Pocket Agents Lab is not initially trying to build one perfect mobile assistant.

It is a laboratory.

The workflow is:

```text
Question
↓
Small PoC
↓
Benchmark
↓
Measure
↓
Learn
↓
Publish result
↓
Build next PoC
```

Each experiment should answer something concrete.

Examples:

> Can a 3B model reliably call five Android tools?

> Can local semantic search over 10,000 notes run interactively?

> Can an SLM diagnose storage problems using Android APIs?

> Can a model generate a working mini utility from a natural-language request?

> Can code execution let a 1B model solve tasks it otherwise fails?

This keeps the project grounded in measurable capabilities rather than demos alone.

---

# Areas We Want to Explore

## 1. Local Language Model Inference

The foundation of the project.

Run Small Language Models locally on Android.

Target model ranges may include:

```text
~0.5B
~1B
~2B
~3B
~4B
~7B
```

Questions to investigate:

* What sizes are actually usable?
* How much memory does each model require?
* Which quantization levels provide acceptable quality?
* What is the practical tokens-per-second threshold?
* How does performance differ across phones?
* How much does context length affect memory?
* How much does KV-cache growth matter?
* CPU vs GPU vs NPU?
* Which operations dominate latency?
* How much battery does sustained inference consume?

Potential runtimes to experiment with:

```text
llama.cpp
ExecuTorch
ONNX Runtime
MLC-based runtimes
TensorFlow Lite / LiteRT style runtimes
vendor-specific inference backends
```

The project should avoid becoming tied to one runtime.

---

# 2. CPU-First Agents

A particularly interesting direction is:

> **How far can we go using primarily the CPU?**

Why?

Because CPUs are universal.

Hardware accelerators differ substantially between devices.

CPU-first execution gives us:

* portability,
* reproducibility,
* easier debugging,
* wider device support,
* simpler deployment.

Experiments:

* 0.5B model CPU inference
* 1B CPU inference
* 3B CPU inference
* quantization comparisons
* thread-count experiments
* latency vs power
* latency vs temperature
* short-context vs long-context
* interactive agent loop performance

Then we can compare against GPU/NPU acceleration.

---

# 3. Local Tool Calling

The agent should interact with deterministic tools rather than trying to solve everything through language generation.

Possible primitives:

```text
get_device_info()
get_battery_state()
get_storage_usage()
list_files()
read_file()
search_files()
query_database()
get_network_state()
get_memory_state()
calculate()
run_code()
search_local_index()
```

Research questions:

* Which models follow tool schemas reliably?
* JSON vs grammar-constrained output?
* Function calling vs textual commands?
* How many tools can a small model handle?
* Does tool-description length matter?
* Can retrieval dynamically expose only relevant tools?

---

# 4. Code Generation

Code generation can dramatically increase the capability of small models.

Instead of mentally performing a complicated operation, the model can write software.

Example:

```text
User:
Which folders grew the most during the last week?

Agent:
I need to compare historical storage snapshots.

↓ generates code

snapshot_old = ...
snapshot_new = ...

growth = compare(snapshot_old, snapshot_new)

↓ executes

↓ interprets result
```

Possible languages:

* Python
* JavaScript
* SQL
* Kotlin snippets
* shell-like internal DSLs
* purpose-built safe scripting languages

Questions:

* Which language is easiest for small models?
* How much sandbox overhead is acceptable?
* Is a limited DSL more reliable than general Python?
* Can models generate Android-native actions?
* How useful is compilation/execution feedback?

---

# 5. Generate → Execute → Repair

One of the core agent loops:

```text
Generate
↓
Execute
↓
Observe
↓
Repair
↓
Execute again
```

Example:

```text
Attempt 1
NameError

Attempt 2
Incorrect API usage

Attempt 3
Success
```

Measure:

```text
Pass@1
Pass@2
Pass@3
average attempts
execution time
tokens consumed
```

A small model that self-corrects may become significantly more capable than its raw benchmark scores suggest.

---

# 6. Semantic Search

One major PoC family will explore fully local semantic search.

Potential targets:

* notes
* PDFs
* documents
* screenshots
* downloaded files
* messages exposed through user-approved APIs
* application data
* browser history where permitted
* bookmarks
* personal knowledge bases

Architecture:

```text
Local content
↓
Embedding model
↓
Vector index
↓
Semantic search
↓
Retrieved context
↓
Local SLM
```

Research questions:

* Which embedding models work well on phones?
* How many documents can be indexed?
* How large does the vector index become?
* HNSW vs brute-force vs compressed indexes?
* What is search latency?
* How expensive is indexing?
* How much battery does indexing consume?
* Can indexing run incrementally?
* How well does hybrid keyword + vector search work locally?

---

# 7. Personal Local RAG

Semantic search naturally leads to local RAG.

Example:

```text
User:
What was the restaurant my friend recommended last month?
```

Potential flow:

```text
Search permitted local content
↓
Retrieve relevant text
↓
Local SLM
↓
Answer with source
```

Potential applications:

* document assistant
* personal knowledge assistant
* offline company knowledge base
* note assistant
* study assistant
* research assistant

---

# 8. Phone Health Agent

One of the strongest practical PoCs.

A local agent that analyzes device health.

Potential capabilities:

### Storage

```text
Why is my phone almost full?
```

Agent examines:

* storage categories
* large files
* duplicate files
* cache size
* recent growth

Then suggests actions.

---

### Battery

```text
Why has my battery been draining quickly?
```

Agent analyzes available telemetry and explains likely causes.

---

### Memory

```text
Why has my phone become slow?
```

Potential investigation:

* RAM pressure
* storage pressure
* application activity
* background processes exposed to the application
* thermal state

---

### Network

```text
Why is my connection behaving strangely?
```

Agent can inspect permitted:

* network state
* Wi-Fi information
* connectivity
* latency tests
* DNS behavior

---

### Thermal Health

Agent tracks:

```text
temperature
performance
inference load
battery state
```

and identifies potential thermal throttling.

---

# 9. Fix Suggestions

Diagnosis is useful.

Diagnosis + actionable remediation is more useful.

For example:

```text
Problem:
12 GB of duplicate videos.

Suggestion:
Review these 37 candidate duplicates.
```

Or:

```text
Problem:
Storage below 5%.

Suggestion:
Remove temporary files first.
```

The initial project should focus heavily on:

> **recommendation before autonomous modification**

Agents should explain:

```text
what they found
why it matters
what they recommend
what would happen
```

before destructive actions are performed.

---

# 10. Local File Agent

A very practical experiment.

Possible commands:

```text
Find my largest files.

Find all PDFs related to machine learning.

Find duplicate screenshots.

Find files I haven't opened in a year.

Group these documents by topic.

Rename these files consistently.

Summarize these documents.
```

The agent can combine:

```text
filesystem tools
+
semantic embeddings
+
generated scripts
+
SLM reasoning
```

---

# 11. Local Data Analyst

Phones contain structured information.

A local agent could translate natural language into queries.

Example:

```text
User:
How much did my storage usage increase over the last month?

SLM
↓
SQL/code generation
↓
local database
↓
result
↓
explanation
```

This creates a general pattern:

> **Natural language → local computation.**

---

# 12. Mobile App Creation

This is one of the more ambitious directions.

Imagine saying:

> Create me a tiny app that tracks how many glasses of water I drink.

Instead of downloading an existing application, the agent generates one.

Possible progression:

### Level 1

Generate HTML/CSS/JavaScript mini-apps inside a controlled WebView.

### Level 2

Generate declarative UI descriptions.

### Level 3

Generate small executable application workflows.

### Level 4

Generate Kotlin/Compose source code.

### Level 5

Compile and package user-created utilities locally where technically feasible.

Possible examples:

```text
counter
calculator
checklist
timer
habit tracker
expense tracker
simple dashboard
local form
data visualizer
converter
text processor
```

This could lead to an important concept:

> **Instead of installing an app for every small problem, ask your local agent to create the utility you need.**

---

# 13. Personal Automation

Another research direction:

```text
When X happens,
do Y.
```

Examples:

```text
When storage falls below 10%, warn me.

Every evening summarize today's notes.

When I connect to my home Wi-Fi, open my home dashboard.

Collect my battery statistics every hour.

Create a weekly storage-health report.
```

Potential architecture:

```text
Trigger
↓
Local rule engine
↓
Agent
↓
Tool/action
```

The agent should not need to remain continuously running for deterministic tasks.

The LLM can generate the automation once.

A lightweight scheduler executes it afterward.

---

# 14. Agent-Generated Tools

Instead of shipping every possible tool, provide primitives.

For example:

```text
read_file
write_safe_file
query_database
execute_script
search_index
```

Then allow the agent to create:

```text
find_duplicate_photos
battery_drain_analyzer
document_classifier
storage_growth_report
```

Successful utilities can become reusable tools.

This creates:

```text
base primitives
↓
generated utility
↓
validation
↓
saved capability
↓
future reuse
```

---

# 15. Persistent Capability Library

Generated programs could become a local library.

```text
agent_tools/
├── storage_analyzer.py
├── duplicate_detector.py
├── log_parser.py
├── note_classifier.py
└── battery_report.py
```

This raises a fascinating research question:

> **Can agents improve their capabilities over time without changing model weights?**

Instead of neural learning:

```text
new capability → retraining
```

we explore:

```text
new capability → generated program → saved tool
```

---

# 16. Agent Memory

Different memory systems can be explored.

## Short-Term Memory

Recent conversation and task state.

## Long-Term Semantic Memory

Important facts embedded into a local index.

## Episodic Memory

Previous tasks and successful solutions.

## Tool Memory

Previously created tools.

## Error Memory

Known failures and working alternatives.

Example:

```text
Previous attempt:
API X unavailable.

Working solution:
Use API Y.
```

---

# 17. Multimodal Mobile Agents

Eventually, local agents can use device sensors.

Potential inputs:

```text
text
image
camera
audio
sensor signals
screenshots
```

Examples:

```text
Take a photo of this device and explain what might be wrong.

Read this label.

Summarize this screenshot.

Extract information from this receipt.

Classify these photos.
```

The goal remains local-first whenever hardware allows.

---

# 18. Camera Intelligence

Interesting PoCs:

* image classification
* OCR-like understanding
* visual search
* object recognition
* visual question answering
* document scanning
* photo organization

Example:

```text
Find photos containing whiteboards.
```

Potential pipeline:

```text
images
↓
local vision embeddings
↓
semantic index
↓
query
```

---

# 19. Voice Agents

Eventually:

```text
speech
↓
local speech recognition
↓
SLM agent
↓
tool/action
↓
local text-to-speech
```

This can create a fully offline voice assistant.

Questions:

* streaming ASR latency
* memory pressure
* model switching
* simultaneous model residency
* battery impact
* wake-word detection

---

# 20. Agent UI

A mobile agent should not just have a chat box.

Possible interfaces:

### Chat

Traditional conversational interface.

### Command Palette

```text
Search files...
Check battery...
Create utility...
Analyze storage...
```

### Agent Cards

Structured task outputs.

### Approval Screens

```text
The agent wants to delete 74 files.

Estimated space recovered: 3.7 GB

[Review]
[Approve]
[Cancel]
```

### Generated Interfaces

The model generates temporary UI for a specific workflow.

This may become an important direction:

> **Agents generate interfaces instead of forcing every interaction through chat.**

---

# Android Topics We Need to Learn

Pocket Agents Lab is also a learning project.

To push local agents on Android, we need to understand the platform deeply.

---

# 21. Android Application Fundamentals

Learn:

* Android application lifecycle
* Activities
* Services
* foreground services
* background execution
* Intents
* BroadcastReceivers
* ContentProviders
* Android permissions
* application sandboxing
* storage APIs
* process lifecycle
* memory pressure
* application packaging
* Gradle
* APK / Android App Bundle concepts

---

# 22. Kotlin

Kotlin should be the main native language.

Topics:

* language fundamentals
* coroutines
* Flow
* serialization
* networking
* file handling
* native interoperability
* JNI basics
* async programming
* memory management

---

# 23. Jetpack Compose

For rapidly building experimental interfaces.

Learn:

* composables
* state
* navigation
* lists
* forms
* dialogs
* lifecycle integration
* ViewModels

This lets PoCs evolve quickly without spending excessive effort on UI.

---

# 24. Android Permissions

This is extremely important for agents.

Understand permission classes around:

* files
* media
* camera
* microphone
* notifications
* location
* Bluetooth
* contacts
* calendars
* accessibility-related capabilities
* application-specific storage

A useful agent must operate within Android's security model rather than attempting to bypass it.

---

# 25. Android Storage Model

Learn:

```text
internal storage
app-specific storage
shared storage
MediaStore
Storage Access Framework
SQLite
Room
SharedPreferences / DataStore
```

A large fraction of useful personal-agent functionality depends on understanding Android's data-access model.

---

# 26. Android Background Execution

Agents cannot simply run forever.

Learn:

* WorkManager
* foreground services
* background restrictions
* Doze
* battery optimization
* scheduled work
* lifecycle constraints

This will strongly influence agent architecture.

---

# 27. Android IPC

For advanced integrations:

* Intents
* Binder concepts
* AIDL
* ContentProviders
* deep links
* application-to-application communication

This becomes important if agents eventually interact with third-party applications.

---

# 28. Android Security Model

Learn:

* UID-based sandboxing
* permission boundaries
* application signing
* Keystore
* encrypted local storage
* secure IPC
* WebView security
* native-code risks
* sandboxing generated code

This is fundamental because Pocket Agents Lab intentionally explores agents capable of taking actions.

---

# Mobile Hardware Topics We Need to Learn

Understanding models is not enough.

We need to understand the hardware they run on.

---

# 29. Mobile CPUs

Learn:

* ARM architecture basics
* performance cores vs efficiency cores
* thread scheduling
* SIMD/vector instructions
* cache hierarchy
* memory bandwidth
* CPU frequency scaling
* power consumption

Questions:

```text
How many threads should inference use?

Does using every core improve latency?

When does thermal throttling erase the gain?
```

---

# 30. Mobile GPUs

Understand:

* integrated mobile GPUs
* compute APIs
* memory sharing
* GPU inference
* GPU delegation
* kernel overhead
* device fragmentation

Research:

```text
CPU
vs
GPU
vs
CPU+GPU
```

---

# 31. NPUs / AI Accelerators

Modern mobile SoCs contain neural-processing hardware.

Topics:

* NPU architecture at a conceptual level
* supported operators
* model compilation
* quantization requirements
* backend compatibility
* hardware delegation
* vendor differences

One major challenge is fragmentation.

Different phones may expose very different capabilities.

---

# 32. Mobile Memory

Phones typically have shared memory constraints.

Understand:

* model weights
* activations
* KV cache
* embeddings
* vector indexes
* Android application memory
* mmap
* page cache
* memory pressure

Model loading itself is only one component.

A full agent may simultaneously need:

```text
SLM
embedding model
speech model
vector index
UI
code runtime
tool data
```

Memory orchestration may become a major research area.

---

# 33. Quantization

Essential for local models.

Explore:

```text
FP16
INT8
INT4
mixed precision
other compact quantization schemes
```

Questions:

* quality degradation
* model size
* memory use
* inference speed
* hardware compatibility

---

# 34. Thermal Constraints

Phones cannot dissipate heat like servers.

Measure:

```text
temperature
tokens/sec
CPU frequency
battery current
wall-clock duration
```

over prolonged runs.

Interesting experiment:

```text
tokens/sec at minute 1
vs
tokens/sec at minute 10
vs
tokens/sec at minute 30
```

A model that is fast for 30 seconds may perform poorly under sustained agent workloads.

---

# 35. Battery and Energy

Performance alone is insufficient.

Measure:

```text
energy per prompt
energy per generated token
energy per tool call
energy per embedding
energy per indexed document
```

Eventually we may optimize for:

> **successful tasks per battery percentage**

rather than tokens per second.

---

# AI Topics We Need to Learn / Investigate

---

# 36. Small Language Models

Study:

* model architecture
* instruction tuning
* reasoning models
* code-specialized models
* context windows
* tokenizer efficiency
* distillation
* pruning
* quantization
* mobile-oriented architectures

---

# 37. Agent Architecture

Explore:

```text
ReAct
planner/executor
tool calling
state machines
graph agents
code agents
reflection
execution feedback
```

But avoid unnecessary agent-framework complexity.

The mobile environment should favor minimal architectures.

---

# 38. Structured Generation

Tiny models may benefit significantly from constrained outputs.

Explore:

* JSON schema
* grammar-constrained decoding
* typed tool calls
* finite action spaces
* output validation
* retries

---

# 39. Local Embeddings

Explore tiny embedding models for:

```text
semantic search
retrieval
memory
tool retrieval
document clustering
```

---

# 40. Context Management

Mobile inference makes context expensive.

Explore:

```text
summarization
retrieval
context pruning
hierarchical memory
tool-result compression
conversation compression
```

---

# 41. Fine-Tuning

Potential later experiments:

* LoRA
* QLoRA
* tool-use fine-tuning
* execution-repair fine-tuning
* Android-domain fine-tuning
* synthetic trajectory generation
* distillation from stronger models

---

# 42. Benchmarking Mobile Agents

A major long-term contribution could be a benchmark specifically for on-device agents.

Example categories:

```text
device diagnostics
file management
semantic search
structured querying
code generation
tool use
planning
self-repair
memory
automation
```

Each task can define:

```yaml
instruction:
  Find the three largest videos.

tools:
  - list_media
  - run_code

limits:
  max_steps: 5
  max_runtime: 30

expected:
  correct_top_3
```

---

# Core Metrics

## Capability

```text
Task Success Rate
```

## Tool Use

```text
Tool Selection Accuracy
Tool Argument Accuracy
```

## Code Generation

```text
Execution Pass@1
Execution Pass@k
```

## Agent Performance

```text
average steps
repair attempts
completion rate
```

## Runtime

```text
time to first token
tokens per second
task latency
```

## Hardware

```text
peak RAM
CPU usage
GPU/NPU usage
temperature
```

## Energy

```text
battery consumed
energy per task
```

---

# PoC Backlog

The repository should contain many independent experiments.

A rough backlog:

## Foundation

* [x] Local SLM Android chat: select and load GGUF, prompt, and generate through llama.cpp on arm64-v8a
* [ ] benchmark CPU inference
* [ ] model-size comparison
* [ ] quantization comparison
* [ ] memory profiling
* [ ] temperature profiling
* [ ] battery profiling

## Tool Use

* [ ] calculator tool
* [ ] storage-info tool
* [ ] battery-info tool
* [ ] device-info tool
* [ ] file search
* [ ] structured tool calling
* [ ] grammar-constrained tool output
* [ ] dynamic tool retrieval

## Code

* [ ] generate Python
* [ ] local safe Python runtime
* [ ] generate → execute
* [ ] generate → execute → repair
* [ ] SQL generation
* [ ] JavaScript mini-app execution
* [ ] generated reusable tools

## Search

* [ ] local embedding model
* [ ] semantic note search
* [ ] local vector index
* [ ] hybrid keyword + vector search
* [ ] local RAG
* [ ] semantic image search

## Device Intelligence

* [ ] storage-health agent
* [ ] battery-health agent
* [ ] memory-health agent
* [ ] network diagnostic agent
* [ ] phone-health dashboard
* [ ] remediation suggestions

## Personal Agent

* [ ] local memory
* [ ] episodic memory
* [ ] recurring tasks
* [ ] generated automations
* [ ] personal knowledge retrieval

## App Generation

* [ ] generate HTML utility
* [ ] render generated mini-app
* [ ] generated forms
* [ ] generated dashboards
* [ ] generated JavaScript applications
* [ ] generated declarative mobile UI
* [ ] experiment with Kotlin generation

## Multimodal

* [ ] screenshot understanding
* [ ] camera input
* [ ] photo semantic search
* [ ] local speech recognition
* [ ] offline voice agent

## Advanced Agent Capabilities

* [ ] agent-created tools
* [ ] persistent tool library
* [ ] tool validation
* [ ] capability discovery
* [ ] multi-agent experiments
* [ ] tiny-model routing
* [ ] adaptive model selection

---

# Long-Term Research Program

The north-star question for this repository is practical:

> Which model and runtime should someone actually use on an inexpensive Android phone?

The work is organized into seven tracks. These are intended to remain useful as a multi-week backlog, not as promises that every experiment will succeed.

## Track 1 — Edge SLM Benchmark

Build a reproducible benchmark that evaluates both whether a model is useful and whether it runs well at the edge.

Measure:

* task quality on public, versioned, contamination-mitigated evaluations;
* model-load time, time to first token, prompt-processing speed, generation tokens/second, and end-to-end latency;
* peak and steady-state RAM, model storage size, quantization, and supported context length;
* CPU utilization, battery or energy cost, temperature, and sustained thermal throttling;
* supported runtime, acceleration path, ABI, Android version, and device class.

Report the individual measurements first. A composite score is useful for comparison, but must never hide its components or allow a tiny unusable model to win solely through speed.

Candidate score design:

```text
Quality Score    = normalized task capability
Efficiency Score = normalized speed, memory, energy, and sustained-performance metrics
Edge Score       = Quality^alpha × Efficiency^beta
```

An alternative interpretable form to investigate is:

```text
Edge Score = Quality × SpeedFactor × MemoryFactor × EnergyFactor
```

Scores should be normalized against a documented reference device and model. Quality should be a gate or multiplicative factor, not just one small term in a naive weighted average. Publish profiles such as `EdgeScore-General`, `EdgeScore-Agent`, `EdgeScore-Code`, `EdgeScore-LowRAM`, and `EdgeScore-Battery` rather than pretending one weighting fits every use case.

Example result schema:

| Model | Quality | TPS | TTFT | Peak RAM | Energy | Agent score | Edge Score |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Reference model | TBD | TBD | TBD | TBD | TBD | TBD | 100.0 |

Benchmark integrity requirements:

* pin model hashes, runtime commits, prompts, sampling settings, thread count, context, and device state;
* use held-out or freshly generated test variants where possible and document contamination risk;
* repeat runs, report dispersion, and separate cold-load from warm inference;
* begin at a controlled battery level and thermal state, with identical background-app and charging conditions;
* retain raw machine-readable results so future score formulas can be recomputed;
* never report only the composite score.

## Track 2 — Runtime and Implementation Benchmark

Run the same model, quantization, prompt set, device, and thermal protocol across compatible runtimes:

* llama.cpp;
* PicoLM;
* a minimal or custom C/C++ implementation where useful;
* ExecuTorch;
* ONNX Runtime;
* LiteRT / TensorFlow Lite and NNAPI-capable paths.

This isolates model capability from runtime efficiency and answers: how much performance comes from the model, and how much comes from the runtime?

## Track 3 — On-Device LoRA / QLoRA

Start conservatively with a fixed tiny dataset and a 250M–500M model, running while plugged in and supervised before attempting overnight training.

Measure training RAM, samples/second, total time, energy, temperature, throttling, adapter size, and held-out quality improvement. Compare the benefit with the cost and verify that training does not make the base model worse outside the target task.

Before on-device training, test the learning hypothesis in a controlled sequence: supervised fine-tuning (preferably parameter-efficient LoRA/QLoRA), then offline preference tuning such as DPO, and only later RL-style optimization if deterministic rewards and rollback are reliable. Train and validation data must remain separate from the frozen mobile benchmark. See [`docs/model-adaptation-roadmap.md`](docs/model-adaptation-roadmap.md).

## Track 4 — Periodic On-the-Run Adaptation

Explore opt-in collection of user corrections, successful tool calls, failed actions, and preferences. A safer initial loop is:

```text
use during the day → review examples → train while charging → evaluate → activate a new adapter
```

Compare four baselines: no personalization, prompt memory, retrieval-augmented memory, and LoRA personalization. Continuous training is not assumed to be better; measurable retention, privacy, reversibility, and regression testing are requirements.

## Track 5 — Agentic Structures

Test whether scaffolding compensates for smaller models by comparing:

* direct answer;
* structured tool calling;
* ReAct;
* planner/executor;
* generate → execute → repair;
* short- and long-term memory;
* dynamic tool retrieval;
* model-created tools with validation and sandboxing.

This should become an Edge Agent Benchmark distinct from raw language-model quality. Track task success, tool and argument accuracy, steps, repair attempts, total latency, resource use, and unsafe or invalid actions.

## Track 6 — Mobile Capability Proofs

Build focused demonstrations that answer, “Can a local small model actually do this on a normal phone?” Candidate proofs include:

* semantic search across local files and personal knowledge;
* storage diagnosis and duplicate-photo discovery;
* battery-health explanation and device troubleshooting;
* local document Q&A;
* offline voice assistance;
* natural language to SQLite and local automation;
* local code execution in an explicit sandbox;
* generated mini-apps;
* screenshot understanding.

Each proof should define a user-visible task, success criteria, permissions and safety boundaries, supported device class, and measured cost.

## Track 7 — Packaging and Deployment

Turn successful experiments into a PocketPal-like experience:

```text
install app → choose or download model → enable capabilities → use local agent
```

The final user should not need to understand GGUF, JNI, quantization, context configuration, or tool schemas. The research project discovers what works; the application packages the reproducible winners.

## Near-Term TODO

1. [x] Establish the Galaxy A32 hardware and software baseline and preserve the findings in `docs/`.
2. [x] Integrate CPU-only llama.cpp for `arm64-v8a` with GGUF selection, model loading, generation, and timing telemetry.
3. [x] Pin the llama.cpp commit/build flags and define the first versioned 50-prompt, JSON/CSV tool-routing harness with warm context reset, progress, and cancellation.
4. [x] Preserve Run 001 of `tool-routing-3-tools-v1`: 13/50 correct routes, 29/50 final accepted selections, 42.3 s average latency, about 825-851 MB PSS, and 30.4-36.7 C observed temperature.
5. [x] Compare hierarchical grammar-constrained `tool-routing-3-tools-v6` against the preserved v5 baseline. It regressed from 40/50 to 26/50 despite retaining 50/50 strict JSON, so v5 remains the best measured harness. Preserve every version under its original scoring semantics.
   Version 7 restores single-stage routing and tests only concise ordered rules and contrastive examples for the direct-answer and Phone Health boundaries.
   The completed v7 run also scored 26/50: Direct Answer reached 9/10 and Phone Health 10/10, but the three single-tool classes collapsed. Freeze v5 and require a held-out prompt set before further routing tuning.
   The app supports an ordered, thermally gated overnight multi-model queue with isolated artifacts for each GGUF.
   Compatibility guards now cover xLAM fenced JSON and crash-safe Qwen non-thinking Jinja formatting/fresh-repair operation while preserving normalization labels and raw outputs.
6. [ ] Add authoritative native token counts and repeatable cold-load/warm-generation measurements for model-load time, TTFT, token/s, peak RAM, CPU, battery, and thermal samples.
7. [ ] Stress-test the agent at 3 -> 5 -> 10 tools with similar and irrelevant distractors; preserve each versioned suite and identify the empirical breaking point.
8. [ ] Turn Phone Health into the first evaluated end-to-end PoC: deterministic orchestration and facts, model selection/explanation only, scored correctness plus latency and resource cost.
9. [x] Add the first Mobile Control proposal flow: the local model may propose opening allowlisted Storage or Battery Settings, but deterministic UI requires a separate confirmation tap before launching Android Settings.
10. [x] Run and preserve `mobile-control-actions-v1`: deterministically rescored 17/20 from intact raw decisions, 20/20 valid JSON, 8/8 intended navigation proposals, and 2/12 false proposals on non-navigation prompts. Do not expand actions until an explicit-navigation gate reduces false proposals.
11. [x] Preserve a frozen v5 replication: 44/50 correct, 50/50 strict JSON, 28.70 s average latency, and 759.9 MB peak PSS. Treat the difference from the original 40/50 as run-to-run variation rather than post-hoc replacement.
9. [ ] Select a small, contamination-conscious quality suite and document licenses, versions, prompts, and scoring.
10. [ ] Benchmark several model sizes, quantizations, and compatible runtimes under the identical protocol.
11. [ ] Add capabilities only after the routing result: richer Android actions, then constrained local retrieval and sandboxed code execution.
12. [ ] Design Edge Score only after enough raw results exist to test weighting, quality gates, stability, and ranking sensitivity.
13. [ ] After frozen baselines, test SFT with LoRA/QLoRA, then DPO if SFT improves held-out results; consider RL-style optimization only after deterministic rewards and regression controls exist.
14. [ ] Attempt on-device adapter training and custom/minimal runtimes only after inference, thermal, energy, and recovery tooling are reliable.

The immediate priority is measurement, not semantic search. See [`docs/tool-agent-evaluation.md`](docs/tool-agent-evaluation.md) for the pinned protocol, thermal rule, wireless ADB setup, CSV schema, and the single constrained repair policy.

---

# Suggested Repository Structure

```text
pocket-agents-lab/
│
├── README.md
│
├── android/
│   ├── app/
│   ├── inference/
│   ├── tools/
│   ├── storage/
│   └── permissions/
│
├── runtimes/
│   ├── llama_cpp/
│   ├── executorch/
│   ├── onnx/
│   └── experiments/
│
├── agents/
│   ├── basic/
│   ├── tool_agent/
│   ├── code_agent/
│   └── memory_agent/
│
├── tools/
│   ├── battery/
│   ├── storage/
│   ├── files/
│   ├── network/
│   └── device/
│
├── sandbox/
│   ├── python/
│   ├── javascript/
│   └── policies/
│
├── retrieval/
│   ├── embeddings/
│   ├── vector_search/
│   ├── hybrid_search/
│   └── rag/
│
├── memory/
│   ├── semantic/
│   ├── episodic/
│   └── tool_memory/
│
├── poc/
│   ├── 001_local_chat/
│   ├── 002_tool_calling/
│   ├── 003_code_execution/
│   ├── 004_semantic_search/
│   ├── 005_storage_agent/
│   └── ...
│
├── benchmarks/
│   ├── inference/
│   ├── agents/
│   ├── devices/
│   └── tasks/
│
├── experiments/
│   ├── quantization/
│   ├── cpu_vs_accelerator/
│   ├── model_size/
│   └── thermal/
│
├── docs/
│   ├── android.md
│   ├── mobile_hardware.md
│   ├── inference.md
│   ├── agents.md
│   ├── security.md
│   └── research_questions.md
│
└── results/
    ├── phones/
    ├── models/
    └── benchmarks/
```

---

# PoC Philosophy

Every PoC should answer one question.

For example:

```text
PoC 001

Question:
Can a ~1B model produce usable interactive responses
on a modern Android phone using CPU inference?

Result:
<measurement>
```

Then:

```text
PoC 002

Question:
Can the same model reliably choose between five tools?

Result:
<measurement>
```

Then:

```text
PoC 003

Question:
Does local code execution significantly improve task success?

Result:
<measurement>
```

This allows Pocket Agents Lab to gradually become a public body of knowledge about mobile agents.

---

# Initial Roadmap

This historical staged outline is retained for context. The active roadmap is the iterative layered plan in [`docs/roadmap.md`](docs/roadmap.md); stages are not gates that must be perfected before later work begins.

## Stage 1 — Make It Talk

```text
Android
+
local SLM
+
CPU inference
```

Measure everything.

---

## Stage 2 — Make It Use Tools

```text
SLM
+
Android APIs
```

---

## Stage 3 — Make It Compute

```text
SLM
+
safe code execution
```

---

## Stage 4 — Make It Search

```text
SLM
+
embeddings
+
local vector search
```

---

## Stage 5 — Make It Remember

```text
SLM
+
persistent local memory
```

---

## Stage 6 — Make It Useful

Build:

```text
phone-health agent
file agent
semantic-search agent
local knowledge assistant
```

---

## Stage 7 — Make It Create

Allow the agent to generate:

```text
scripts
tools
workflows
mini-apps
interfaces
```

---

## Stage 8 — Push the Limits

Experiment with:

```text
voice
vision
multimodal agents
persistent capability learning
hardware acceleration
smaller models
lower energy
better reliability
```

---

# First Milestone

## PoC 001 — Local Android Agent

The first milestone should stay extremely small.

Build an Android application that:

1. loads a quantized Small Language Model,
2. performs inference completely locally,
3. works without internet access,
4. exposes several safe Android tools,
5. lets the model call those tools,
6. returns tool results to the model,
7. displays the final response.

Initial tools:

```text
get_device_info
get_battery_status
get_storage_status
calculate
```

No semantic search.

No memory.

No code execution.

No automation.

Just prove the basic agent loop.

```text
User
↓
SLM
↓
Tool
↓
Android
↓
Observation
↓
SLM
↓
Answer
```

Once this works reliably, everything else becomes an extension.

---

# First Learning Path

Before building increasingly complicated agents, develop competency in four parallel tracks.

### Mobile

```text
Kotlin
Android lifecycle
Jetpack Compose
permissions
storage
background execution
security
```

### Hardware

```text
ARM CPUs
mobile GPUs
NPUs
memory bandwidth
quantization
thermal constraints
energy consumption
```

### Local AI

```text
SLMs
llama.cpp-style runtimes
tokenization
KV cache
quantization
embeddings
multimodal models
```

### Agents

```text
tool calling
structured generation
code execution
sandboxing
memory
retrieval
planning
evaluation
```

You do not need to learn all of this before starting.

Pocket Agents Lab should be the mechanism through which these topics are learned.

---

# Guiding Principle

The project should constantly ask:

> **Can we move this capability from the cloud onto the phone?**

Then:

> **Can we make it smaller?**

Then:

> **Can we make it faster?**

Then:

> **Can we make it more useful?**

Then:

> **Can an ordinary person actually use it?**

---

# Long-Term Possibility

If local agents become sufficiently capable, a phone could eventually contain:

```text
personal assistant
+
search engine
+
developer
+
data analyst
+
device technician
+
automation engine
+
knowledge base
+
mini-app generator
```

without requiring all personal information to continuously leave the device.

That is the direction Pocket Agents Lab exists to explore.

---

# One-Sentence Mission

> **Pocket Agents Lab explores how far we can push Small Language Model agents running locally on mobile devices, from simple tool use to semantic search, diagnostics, automation, code execution, memory, and eventually the creation of new tools and applications.**

---

# Status

**Experimental / Research / PoC**

This repository is deliberately exploratory.

Expect:

* incomplete experiments,
* benchmark scripts,
* prototype Android applications,
* failed ideas,
* hardware measurements,
* architecture experiments,
* model comparisons,
* and increasingly ambitious local agents.

The objective is not to claim that every experiment works.

The objective is to find out **what actually works on a phone**.

---

# License

Apache-2.0 is a good default candidate for the open-source portions of the project.

---

# Repository

```text
pocket-agents-lab
```

**Local agents. Real devices. Push the edge as far as it goes.**
