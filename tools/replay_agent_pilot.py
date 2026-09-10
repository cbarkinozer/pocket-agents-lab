#!/usr/bin/env python3
"""Replay the app's 50-case routing pilot against a llama-server on a non-Android host.

This is a faithful reproduction, not an approximation: the system prompt, the
GBNF routing grammar, the routing-prompt template, the 50 test cases, the
sampler temperature, and the post-decode "is this decision relevant" guardrail
are all copied verbatim (or transliterated 1:1) from
`app/src/main/java/com/pocketagentslab/MainActivity.kt` and `AgentBackend.kt`,
which is the actual source of truth for what runs on the Galaxy A32.

What is NOT reproduced, and why it should still be comparable for these 50
cases: the app's hybrid legacy/Jinja chat formatter only matters when
resuming a multi-turn conversation; `prepareFreshAgent` resets to a fresh
system+user turn before every case, so plain Jinja rendering with thinking
disabled (via `chat_template_kwargs: {"enable_thinking": false}`) is the same
input the app would produce for turn one. What IS a real difference: this
runs on a Windows CPU through llama-server's HTTP path, not the Android JNI
path, so any JNI-specific quirks (documented in docs/tool-agent-evaluation.md)
are out of scope. Do not present these numbers as an on-device result.

Usage:
    llama-server -m MODEL.gguf -c 2048 --jinja --port 8090 &
    python tools/replay_agent_pilot.py --port 8090 --out results.json
"""

from __future__ import annotations

import argparse
import json
import re
import time
import urllib.request
from dataclasses import dataclass, field

AGENT_SYSTEM_PROMPT = """You are an offline Android router. Output one bare JSON object, never markdown.
Allowed routes:
{"action":"answer","text":"..."}
{"action":"tool","name":"get_device_info","args":{}}
{"action":"tool","name":"get_battery_info","args":{}}
{"action":"tool","name":"get_storage_info","args":{}}
{"action":"tool","name":"get_media_info","args":{}}
{"action":"tool","name":"search_local_files","args":{}}
{"action":"tool","name":"search_notes","args":{}}
{"action":"tool","name":"save_note","args":{}}
{"action":"workflow","name":"phone_health_check","args":{}}
{"action":"workflow","name":"phone_optimization_report","args":{}}
{"action":"propose","name":"open_storage_settings","args":{}}
{"action":"propose","name":"open_battery_settings","args":{}}
{"action":"propose","name":"open_camera","args":{}}
{"action":"propose","name":"open_wallpaper_settings","args":{}}
{"action":"propose","name":"review_background_apps","args":{}}
{"action":"propose","name":"launch_app","args":{}}
{"action":"propose","name":"search_spotify","args":{}}
{"action":"propose","name":"search_youtube","args":{}}
{"action":"propose","name":"draft_telegram_message","args":{}}
{"action":"propose","name":"media_play_pause","args":{}}
{"action":"propose","name":"media_next","args":{}}
{"action":"propose","name":"media_previous","args":{}}
{"action":"propose","name":"open_media_access","args":{}}
{"action":"propose","name":"set_timer","args":{}}
{"action":"propose","name":"set_alarm","args":{}}
{"action":"propose","name":"create_calendar_event","args":{}}
Use a tool only for current phone facts. Use the workflow for overall health. Otherwise answer. Never invent names or arguments. After tool data, return action=answer."""

CLARIFICATION_MESSAGE = "I could not understand what you meant. Please rephrase your request."

# Transliterated from AgentBackend.kt's buildRoutingPrompt(userPrompt, allowDeviceActions=true).
ROUTING_PROMPT_TEMPLATE = """Select exactly one route. Native grammar constructs the JSON, so choose by meaning:
Live phone fact: {{"action":"tool","name":"TOOL","args":{{}}}}
TOOL is exactly get_device_info, get_battery_info, get_storage_info, get_media_info, search_local_files, search_notes, or save_note.
Device info covers model, manufacturer, Android, ABI, and RAM. Storage info covers disk space and room for files/models.
Battery info covers live level, charging state, temperature, heat, and whether cooling is needed.
Media info reports the active song/video metadata when Notification Access is enabled.
Local file search finds filenames or text in the folder the user previously authorized. Its query is the original request.
Note search recalls something the user previously asked the app to remember. Save note is for any explicit request to retain information for later. Kotlin uses the original request as the query or content.
Overall phone health: {{"action":"workflow","name":"phone_health_check","args":{{}}}}
Phone optimization/lag diagnosis: {{"action":"workflow","name":"phone_optimization_report","args":{{}}}}
Two or more live categories, overall condition, or AI-workload readiness use phone_health_check.
No live phone data needed: {{"action":"answer","text":""}}
Writing, jokes, arithmetic, definitions, colors, sequences, and general knowledge need no tool.
Unclear: {{"action":"answer","text":"{clarification}"}}
Examples:
Battery level -> {{"action":"tool","name":"get_battery_info","args":{{}}}}
What song is playing -> {{"action":"tool","name":"get_media_info","args":{{}}}}
Free space -> {{"action":"tool","name":"get_storage_info","args":{{}}}}
Find a file or phrase in local files -> {{"action":"tool","name":"search_local_files","args":{{}}}}
Recall something previously saved -> {{"action":"tool","name":"search_notes","args":{{}}}}
Remember or save something to notes -> {{"action":"tool","name":"save_note","args":{{}}}}
Don't forget the experiment number -> {{"action":"tool","name":"save_note","args":{{}}}}
Keep this for later -> {{"action":"tool","name":"save_note","args":{{}}}}
Make a note that the meeting is Monday -> {{"action":"tool","name":"save_note","args":{{}}}}
What was the experiment number? -> {{"action":"tool","name":"search_notes","args":{{}}}}
Android version -> {{"action":"tool","name":"get_device_info","args":{{}}}}
Physical RAM or manufacturer -> {{"action":"tool","name":"get_device_info","args":{{}}}}
Room for another model -> {{"action":"tool","name":"get_storage_info","args":{{}}}}
Check everything -> {{"action":"workflow","name":"phone_health_check","args":{{}}}}
Why is my phone slow / optimize it -> {{"action":"workflow","name":"phone_optimization_report","args":{{}}}}
Explicit request to open Storage Settings -> {{"action":"propose","name":"open_storage_settings","args":{{}}}}
Explicit request to open Battery Settings -> {{"action":"propose","name":"open_battery_settings","args":{{}}}}
Explicit request to open Camera -> {{"action":"propose","name":"open_camera","args":{{}}}}
Explicit request to open Wallpaper Settings -> {{"action":"propose","name":"open_wallpaper_settings","args":{{}}}}
Explicit request to open/manage/review the apps screen -> {{"action":"propose","name":"review_background_apps","args":{{}}}}
Explicit request to open an installed app -> {{"action":"propose","name":"launch_app","args":{{}}}}
Find/play a named song on Spotify -> {{"action":"propose","name":"search_spotify","args":{{}}}}
Find/open a named video on YouTube -> {{"action":"propose","name":"search_youtube","args":{{}}}}
Prepare a Telegram message -> {{"action":"propose","name":"draft_telegram_message","args":{{}}}}
Pause/resume active media -> {{"action":"propose","name":"media_play_pause","args":{{}}}}
Next active media item -> {{"action":"propose","name":"media_next","args":{{}}}}
Previous active media item -> {{"action":"propose","name":"media_previous","args":{{}}}}
Open media/notification access settings -> {{"action":"propose","name":"open_media_access","args":{{}}}}
Set/count down a duration -> {{"action":"propose","name":"set_timer","args":{{}}}}
Wake/remind at a clock time -> {{"action":"propose","name":"set_alarm","args":{{}}}}
Add/schedule an event or appointment -> {{"action":"propose","name":"create_calendar_event","args":{{}}}}
Advice such as how to change wallpaper is an answer, not an action. Unsupported requests are answers or clarifications, never the nearest unrelated tool. A proposal never executes without confirmation.
Request: {user_prompt}
JSON:"""

AGENT_ROUTE_GRAMMAR = r"""root ::= answer | device | battery | storage | media-info | files | notes | save-note | health | optimize | open-storage | open-battery | open-camera | open-wallpaper | review-background | launch-app | spotify-search | youtube-search | telegram-draft | media-toggle | media-next | media-previous | open-media-access | set-timer | set-alarm | calendar-event
answer ::= "{\"action\":\"answer\",\"text\":\"\"}"
device ::= "{\"action\":\"tool\",\"name\":\"get_device_info\",\"args\":{}}"
battery ::= "{\"action\":\"tool\",\"name\":\"get_battery_info\",\"args\":{}}"
storage ::= "{\"action\":\"tool\",\"name\":\"get_storage_info\",\"args\":{}}"
media-info ::= "{\"action\":\"tool\",\"name\":\"get_media_info\",\"args\":{}}"
files ::= "{\"action\":\"tool\",\"name\":\"search_local_files\",\"args\":{}}"
notes ::= "{\"action\":\"tool\",\"name\":\"search_notes\",\"args\":{}}"
save-note ::= "{\"action\":\"tool\",\"name\":\"save_note\",\"args\":{}}"
health ::= "{\"action\":\"workflow\",\"name\":\"phone_health_check\",\"args\":{}}"
optimize ::= "{\"action\":\"workflow\",\"name\":\"phone_optimization_report\",\"args\":{}}"
open-storage ::= "{\"action\":\"propose\",\"name\":\"open_storage_settings\",\"args\":{}}"
open-battery ::= "{\"action\":\"propose\",\"name\":\"open_battery_settings\",\"args\":{}}"
open-camera ::= "{\"action\":\"propose\",\"name\":\"open_camera\",\"args\":{}}"
open-wallpaper ::= "{\"action\":\"propose\",\"name\":\"open_wallpaper_settings\",\"args\":{}}"
review-background ::= "{\"action\":\"propose\",\"name\":\"review_background_apps\",\"args\":{}}"
launch-app ::= "{\"action\":\"propose\",\"name\":\"launch_app\",\"args\":{}}"
spotify-search ::= "{\"action\":\"propose\",\"name\":\"search_spotify\",\"args\":{}}"
youtube-search ::= "{\"action\":\"propose\",\"name\":\"search_youtube\",\"args\":{}}"
telegram-draft ::= "{\"action\":\"propose\",\"name\":\"draft_telegram_message\",\"args\":{}}"
media-toggle ::= "{\"action\":\"propose\",\"name\":\"media_play_pause\",\"args\":{}}"
media-next ::= "{\"action\":\"propose\",\"name\":\"media_next\",\"args\":{}}"
media-previous ::= "{\"action\":\"propose\",\"name\":\"media_previous\",\"args\":{}}"
open-media-access ::= "{\"action\":\"propose\",\"name\":\"open_media_access\",\"args\":{}}"
set-timer ::= "{\"action\":\"propose\",\"name\":\"set_timer\",\"args\":{}}"
set-alarm ::= "{\"action\":\"propose\",\"name\":\"set_alarm\",\"args\":{}}"
calendar-event ::= "{\"action\":\"propose\",\"name\":\"create_calendar_event\",\"args\":{}}"
"""

DEFAULT_SAMPLER_TEMP = 0.3


@dataclass
class TestCase:
    id: str
    prompt: str
    expected_tool: str | None = None
    expected_workflow: str | None = None


# Transliterated verbatim from MainActivity.kt's AGENT_TEST_CASES.
AGENT_TEST_CASES: list[TestCase] = [
    TestCase("storage-01", "How much storage do I have?", "get_storage_info"),
    TestCase("storage-02", "How much free space is left on this phone?", "get_storage_info"),
    TestCase("storage-03", "Am I running out of disk space?", "get_storage_info"),
    TestCase("storage-04", "Show my internal storage usage.", "get_storage_info"),
    TestCase("storage-05", "How many gigabytes can I still save?", "get_storage_info"),
    TestCase("storage-06", "Is there room for another large model?", "get_storage_info"),
    TestCase("storage-07", "Check available space, not battery level.", "get_storage_info"),
    TestCase("storage-08", "What fraction of the phone storage is free?", "get_storage_info"),
    TestCase("storage-09", "Tell me the used and total storage.", "get_storage_info"),
    TestCase("storage-10", "Could a 2 GB file fit on this device right now?", "get_storage_info"),
    TestCase("device-01", "What Android version is this?", "get_device_info"),
    TestCase("device-02", "What CPU ABI does this device use?", "get_device_info"),
    TestCase("device-03", "Which phone model am I using?", "get_device_info"),
    TestCase("device-04", "Who manufactured this handset?", "get_device_info"),
    TestCase("device-05", "Is this device arm64-v8a?", "get_device_info"),
    TestCase("device-06", "Report the model, Android release, and architecture.", "get_device_info"),
    TestCase("device-07", "How much physical RAM does this phone expose?", "get_device_info"),
    TestCase("device-08", "Identify the current device without discussing storage.", "get_device_info"),
    TestCase("device-09", "Which Android SDK and OS version are running?", "get_device_info"),
    TestCase("device-10", "Give me this phone's hardware identity.", "get_device_info"),
    TestCase("battery-01", "Is my battery hot?", "get_battery_info"),
    TestCase("battery-02", "What is my battery percentage?", "get_battery_info"),
    TestCase("battery-03", "Is the phone charging right now?", "get_battery_info"),
    TestCase("battery-04", "Tell me the current battery temperature.", "get_battery_info"),
    TestCase("battery-05", "How much charge remains?", "get_battery_info"),
    TestCase("battery-06", "Check battery heat, not free storage.", "get_battery_info"),
    TestCase("battery-07", "Should I let the battery cool down?", "get_battery_info"),
    TestCase("battery-08", "Read the live charging state.", "get_battery_info"),
    TestCase("battery-09", "Is my current battery temperature above 40 C?", "get_battery_info"),
    TestCase("battery-10", "Give me charge level and temperature.", "get_battery_info"),
    TestCase("answer-01", "Tell me a joke."),
    TestCase("answer-02", "What is 2+2?"),
    TestCase("answer-03", "Write a five-word greeting."),
    TestCase("answer-04", "What is the capital of France?"),
    TestCase("answer-05", "Explain what an ABI is in one sentence."),
    TestCase("answer-06", "Give me a short riddle."),
    TestCase("answer-07", "Say hello without checking my device."),
    TestCase("answer-08", "What does CPU stand for?"),
    TestCase("answer-09", "Name a primary color."),
    TestCase("answer-10", "Complete this sequence: 2, 4, 6, 8, ?"),
    TestCase("health-01", "Run a phone health check.", expected_workflow="phone_health_check"),
    TestCase("health-02", "Check whether my phone is healthy.", expected_workflow="phone_health_check"),
    TestCase("health-03", "Inspect my phone and suggest health improvements.", expected_workflow="phone_health_check"),
    TestCase("health-04", "Are storage and battery temperature okay?", expected_workflow="phone_health_check"),
    TestCase("health-05", "Give this handset a complete health assessment.", expected_workflow="phone_health_check"),
    TestCase("health-06", "Check both free space and battery heat together.", expected_workflow="phone_health_check"),
    TestCase("health-07", "Diagnose the phone's overall condition.", expected_workflow="phone_health_check"),
    TestCase("health-08", "Do a device wellness check and recommend actions.", expected_workflow="phone_health_check"),
    TestCase("health-09", "Is this phone ready for a sustained AI workload?", expected_workflow="phone_health_check"),
    TestCase("health-10", "Review device, battery, and storage health.", expected_workflow="phone_health_check"),
]

# Transliterated from AgentBackend.kt's isDecisionRelevant. Only the branches
# reachable by this test set's tools/workflow are included; unreached branches
# would not affect these 50 cases either way.
RELEVANCE_KEYWORDS = {
    "get_device_info": ["android", "phone", "device", "model", "manufacturer", "ram", "abi", "hardware"],
    "get_battery_info": ["battery", "charge", "charging", "power", "temperature", "hot", "cool"],
    "get_storage_info": ["storage", "space", "disk", "room", "fit", "capacity"],
    "phone_health_check": ["health", "condition", "readiness", "check everything"],
}


def is_decision_relevant(tool_or_workflow: str | None, user_prompt: str) -> bool:
    if tool_or_workflow is None:
        return True
    keywords = RELEVANCE_KEYWORDS.get(tool_or_workflow)
    if keywords is None:
        return True
    text = user_prompt.lower()
    return any(k in text for k in keywords)


def expected_route(case: TestCase) -> str:
    if case.expected_workflow:
        return f"workflow:{case.expected_workflow}"
    if case.expected_tool:
        return f"tool:{case.expected_tool}"
    return "answer"


def parse_route_json(raw: str) -> dict:
    trimmed = raw.strip()
    if trimmed.startswith("```"):
        trimmed = trimmed.strip("`").removeprefix("json").strip()
    return json.loads(trimmed)


def actual_route(decision: dict) -> str:
    action = decision.get("action")
    if action == "workflow":
        return f"workflow:{decision['name']}"
    if action == "tool":
        return f"tool:{decision['name']}"
    return action or "invalid"


def call_completion(port: int, system_prompt: str, user_prompt: str, grammar: str, temperature: float) -> str:
    payload = {
        "messages": [
            {"role": "system", "content": system_prompt},
            {"role": "user", "content": user_prompt},
        ],
        "grammar": grammar,
        "temperature": temperature,
        "n_predict": 64,
        "chat_template_kwargs": {"enable_thinking": False},
        "cache_prompt": False,
    }
    req = urllib.request.Request(
        f"http://127.0.0.1:{port}/v1/chat/completions",
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        body = json.loads(resp.read())
    return body["choices"][0]["message"]["content"]


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8090)
    parser.add_argument("--out", required=True)
    parser.add_argument("--label", default="model", help="Label for this run in the output JSON")
    args = parser.parse_args()

    results = []
    correct = 0
    for case in AGENT_TEST_CASES:
        user_prompt = ROUTING_PROMPT_TEMPLATE.format(clarification=CLARIFICATION_MESSAGE, user_prompt=case.prompt)
        started = time.time()
        raw = call_completion(args.port, AGENT_SYSTEM_PROMPT, user_prompt, AGENT_ROUTE_GRAMMAR, DEFAULT_SAMPLER_TEMP)
        elapsed = time.time() - started
        try:
            decision = parse_route_json(raw)
            route = actual_route(decision)
            tool_or_workflow = decision.get("name") if decision.get("action") in ("tool", "workflow") else None
            if not is_decision_relevant(tool_or_workflow, case.prompt):
                route = "answer"
            error = None
        except Exception as e:
            route = "invalid"
            error = str(e)
        is_correct = route == expected_route(case)
        correct += is_correct
        results.append({
            "id": case.id,
            "prompt": case.prompt,
            "expected": expected_route(case),
            "actual": route,
            "correct": is_correct,
            "raw": raw,
            "error": error,
            "latency_s": round(elapsed, 3),
        })
        print(f"{case.id}: expected={expected_route(case)} actual={route} {'OK' if is_correct else 'MISS'} "
              f"({elapsed:.2f}s)")

    summary = {
        "label": args.label,
        "total": len(AGENT_TEST_CASES),
        "correct": correct,
        "accuracy": correct / len(AGENT_TEST_CASES),
        "cases": results,
    }
    with open(args.out, "w") as f:
        json.dump(summary, f, indent=2)
    print(f"\n{args.label}: {correct}/{len(AGENT_TEST_CASES)} correct ({summary['accuracy']:.1%})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
