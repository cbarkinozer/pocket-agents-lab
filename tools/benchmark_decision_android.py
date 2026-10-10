#!/usr/bin/env python3
"""Run the existing 50 routing cases on an Android-local llama-server.

ADB forwards transport only; inference runs on the phone. No tools or final
answers are executed. Keep raw choices separate from the existing relevance
guardrail. This is a new decision-schema treatment, not the old JNI protocol.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import statistics
import subprocess
import time
import urllib.request
from pathlib import Path

from replay_agent_pilot import (
    AGENT_TEST_CASES, AGENT_SYSTEM_PROMPT, AGENT_ROUTE_GRAMMAR,
    ROUTING_PROMPT_TEMPLATE, CLARIFICATION_MESSAGE, expected_route,
    is_decision_relevant,
)

# Match all nine routes allowed by the existing research grammar, including
# routes absent from the 50-case suite. Do not remove distractors using labels.
CRITERIA = {
    "answer": "Writing, jokes, arithmetic, definitions, general knowledge, or unclear requests. No live phone data needed.",
    "get_device_info": "Current phone model, manufacturer, Android version, CPU ABI or physical RAM.",
    "get_battery_info": "Live battery level, charging status, temperature, heat or need to cool down.",
    "get_storage_info": "Live internal disk space, used/free/total storage, capacity to save files or models.",
    "get_media_info": "Currently playing song or video metadata.",
    "search_local_files": "Find a filename or phrase in previously authorized local files.",
    "search_notes": "Recall or find information previously saved to notes.",
    "save_note": "Explicitly remember or save information for later.",
    "phone_health_check": "Overall phone health, condition, AI workload readiness, check everything, or two or more live categories together.",
}
QUESTIONS = {"route": {
    "type": "choice",
    "instructions": "Select exactly one Android agent route by the meaning of the request. Use live phone tools only for current phone facts. Use phone_health_check for overall condition or two or more live categories. Otherwise answer without a tool.",
    "criteria": CRITERIA,
}}


def adb(args, *command):
    prefix = [args.adb] + (["-s", args.serial] if args.serial else [])
    return subprocess.check_output(prefix + list(command), text=True, timeout=30)


def telemetry(args):
    battery = adb(args, "shell", "dumpsys battery")
    thermal = adb(args, "shell", "dumpsys thermalservice")
    temp = re.search(r"temperature:\s*(\d+)", battery)
    status = re.search(r"(?:Thermal Status:|mStatus[=:])\s*(\d+)", thermal)
    pss = None
    if args.pid:
        mem = adb(args, "shell", "dumpsys meminfo " + str(args.pid))
        match = re.search(r"TOTAL PSS:\s*(\d+)", mem) or re.search(r"^\s*TOTAL\s+(\d+)", mem, re.M)
        pss = int(match.group(1)) if match else None
    return {"temperatureC": int(temp.group(1)) / 10 if temp else None,
            "thermalStatus": int(status.group(1)) if status else None,
            "pssKb": pss, "usbPowered": "USB powered: true" in battery}


def request(args, path, body):
    req = urllib.request.Request(args.url + path, json.dumps(body).encode(),
                                 {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as response:
        return json.load(response)


def save(path, report):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(report, indent=2) + "\n")
    tmp.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial")
    parser.add_argument("--url", default="http://127.0.0.1:8091")
    parser.add_argument("--mode", choices=["decision", "chat"], default="decision")
    parser.add_argument("--label", required=True)
    parser.add_argument("--model-sha256", required=True)
    parser.add_argument("--runtime-revision", required=True)
    parser.add_argument("--pid", type=int)
    parser.add_argument("--limit", type=int, default=50)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    report = {"suite": "tool-routing-3-tools-v5", "treatment": args.mode,
              "label": args.label, "modelSha256": args.model_sha256,
              "runtimeRevision": args.runtime_revision, "inferenceDevice": "Android",
              "transport": "ADB forwarded localhost HTTP", "finalAnswersTested": False,
              "questions": QUESTIONS if args.mode == "decision" else None,
              "maxTemperatureC": 38, "maxThermalStatus": 1,
              "caseTimeoutSeconds": 300, "context": 2048,
              "caseOrder": [c.id for c in AGENT_TEST_CASES[:args.limit]],
              "harnessSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
              "complete": False, "cases": []}
    save(args.out, report)
    for case in AGENT_TEST_CASES[:args.limit]:
        cooldown = time.monotonic()
        while True:
            before = telemetry(args)
            if before["temperatureC"] is None or before["thermalStatus"] is None:
                raise RuntimeError("Cannot verify thermal gate; preserve missing readings")
            if before["temperatureC"] <= 38 and before["thermalStatus"] <= 1:
                break
            print("Cooling:", before, flush=True)
            if time.monotonic() - cooldown > 1800:
                raise RuntimeError("Thermal cooldown exceeded 30 minutes")
            time.sleep(10)
        row = {"id": case.id, "prompt": case.prompt, "expected": expected_route(case),
               "before": before, "cooldownSeconds": time.monotonic() - cooldown}
        start = time.monotonic()
        try:
            if args.mode == "decision":
                response = request(args, "/v1/systemone", {
                    "model": "reflex-1", "state": case.prompt, "questions": QUESTIONS})
                result = response["answers"]["route"]
                chosen = result["choice"]
                probabilities = result["probabilities"]
                if chosen not in CRITERIA or set(probabilities) != set(CRITERIA):
                    raise ValueError("Unexpected choice schema")
                if abs(sum(probabilities.values()) - 1) > 0.01:
                    raise ValueError("Probabilities are not normalized")
                if response["usage"]["output_tokens"] != 0:
                    raise ValueError("Decision model generated tokens")
                route = "answer" if chosen == "answer" else (
                    "workflow:" + chosen if chosen == "phone_health_check" else "tool:" + chosen)
            else:
                response = request(args, "/v1/chat/completions", {
                    "messages": [{"role": "system", "content": AGENT_SYSTEM_PROMPT},
                                 {"role": "user", "content": ROUTING_PROMPT_TEMPLATE.format(
                                     user_prompt=case.prompt, clarification=CLARIFICATION_MESSAGE)}],
                    "temperature": 0.3, "seed": 3407, "max_tokens": 64,
                    "grammar": AGENT_ROUTE_GRAMMAR,
                    "cache_prompt": False,
                    "chat_template_kwargs": {"enable_thinking": False}})
                decision = json.loads(response["choices"][0]["message"]["content"])
                chosen = decision.get("name") if decision["action"] != "answer" else "answer"
                if chosen not in CRITERIA:
                    raise ValueError("Unexpected chat route")
                route = "answer" if chosen == "answer" else decision["action"] + ":" + chosen
            row.update(rawRoute=route, rawCorrect=route == row["expected"], response=response)
            guarded = route if chosen == "answer" or is_decision_relevant(chosen, case.prompt) else "answer"
            row.update(route=guarded, correct=guarded == row["expected"])
        except Exception as error:
            row.update(correct=False, rawCorrect=False, error=str(error))
        row["latencyMs"] = (time.monotonic() - start) * 1000
        row["after"] = telemetry(args)
        report["cases"].append(row)
        save(args.out, report)
        print(case.id, row.get("route", row.get("error")), round(row["latencyMs"]), row["after"], flush=True)
    rows = report["cases"]
    report.update(complete=True, correct=sum(r["correct"] for r in rows),
                  rawCorrect=sum(r["rawCorrect"] for r in rows), total=len(rows),
                  meanLatencyMs=statistics.mean(r["latencyMs"] for r in rows),
                  medianLatencyMs=statistics.median(r["latencyMs"] for r in rows))
    save(args.out, report)
    print(json.dumps({k: v for k, v in report.items() if k not in ["cases", "questions", "caseOrder"]}, indent=2))


if __name__ == "__main__":
    main()
