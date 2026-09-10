#!/usr/bin/env python3
"""Offline evaluation of expert-cache/prefetch policies against a captured trace.

Stage 9-10 groundwork (see README's "Near-Term TODO" item 42 and the Aşama
9-10 TODO). Consumes a JSONL trace of per-layer, per-token selected expert ids
(one object per line: {"layer": int, "token_index": int, "expert_ids": [int, ...]})
and reports, per layer, how well a bounded LRU cache plus a few simple
predictors would have done -- hit rate, top-8 recall, and bytes that would
have been read from storage. This is pure trace replay: no model weights or
device are touched, so it can run anywhere the trace file exists.

Column-of-record note: results here characterize Ling-3.0-tiny's *routing
pattern*, which is a property of the trained model, not of the device it runs
on. They inform which predictor is worth prototyping on Android; they are not
a substitute for an on-device measurement of actual latency or energy.

Usage:
    python tools/expert_cache_simulator.py trace.jsonl --expert-bytes 233472 \
        --cache-experts-per-layer 16
"""

from __future__ import annotations

import argparse
import json
from collections import Counter, OrderedDict, defaultdict
from pathlib import Path


class LRUCache:
    def __init__(self, capacity: int):
        self.capacity = capacity
        self._order: "OrderedDict[int, None]" = OrderedDict()

    def access(self, expert_id: int) -> bool:
        """Returns True if this was a cache hit."""
        hit = expert_id in self._order
        if hit:
            self._order.move_to_end(expert_id)
        else:
            self._order[expert_id] = None
            if len(self._order) > self.capacity:
                self._order.popitem(last=False)
        return hit


def previous_token_predictor(history: list[set[int]]) -> set[int]:
    return history[-1] if history else set()


def frequency_predictor(history: list[set[int]], top_k: int) -> set[int]:
    counts: Counter[int] = Counter()
    for experts in history:
        counts.update(experts)
    return {expert_id for expert_id, _ in counts.most_common(top_k)}


def last_n_predictor(history: list[set[int]], n: int, top_k: int) -> set[int]:
    return frequency_predictor(history[-n:], top_k)


def evaluate_layer(records: list[dict], cache_capacity: int, predictor_n: int) -> dict:
    records = sorted(records, key=lambda r: r["token_index"])
    n_expert_used = len(records[0]["expert_ids"]) if records else 0

    cache = LRUCache(cache_capacity)
    history: list[set[int]] = []
    lru_hits = 0
    predicted_hits: dict[str, int] = defaultdict(int)
    total_selections = 0

    for record in records:
        experts = record["expert_ids"]
        total_selections += len(experts)

        for expert_id in experts:
            if cache.access(expert_id):
                lru_hits += 1

        actual = set(experts)
        if history:
            for name, predicted in (
                ("previous_token", previous_token_predictor(history)),
                ("frequency_all", frequency_predictor(history, n_expert_used)),
                (f"last_{predictor_n}", last_n_predictor(history, predictor_n, n_expert_used)),
            ):
                predicted_hits[name] += len(predicted & actual)
        history.append(actual)

    n_tokens = len(records)
    return {
        "n_tokens": n_tokens,
        "n_expert_used": n_expert_used,
        "total_selections": total_selections,
        "lru_hit_rate": lru_hits / total_selections if total_selections else None,
        "predictor_recall": {
            name: hits / total_selections if total_selections else None
            for name, hits in predicted_hits.items()
        },
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("trace_path", help="JSONL trace from parse/capture step")
    parser.add_argument("--cache-experts-per-layer", type=int, default=16,
                         help="Bounded LRU cache size, in experts, per layer")
    parser.add_argument("--predictor-history", type=int, default=8,
                         help="Window size N for the last-N-token frequency predictor")
    parser.add_argument("--expert-bytes", type=int, default=None,
                         help="Bytes per expert (from gguf_expert_offsets.py) to report bytes-read estimates")
    args = parser.parse_args()

    by_layer: dict[int, list[dict]] = defaultdict(list)
    for line in Path(args.trace_path).read_text().splitlines():
        if not line.strip():
            continue
        record = json.loads(line)
        by_layer[record["layer"]].append(record)

    for layer in sorted(by_layer):
        result = evaluate_layer(by_layer[layer], args.cache_experts_per_layer, args.predictor_history)
        print(f"layer {layer}: tokens={result['n_tokens']} n_expert_used={result['n_expert_used']} "
              f"lru_hit_rate={result['lru_hit_rate']:.3f}" if result["lru_hit_rate"] is not None
              else f"layer {layer}: no data")
        for name, recall in result["predictor_recall"].items():
            print(f"    {name}: recall={recall:.3f}" if recall is not None else f"    {name}: n/a")
        if args.expert_bytes and result["lru_hit_rate"] is not None:
            wasted = (1 - result["lru_hit_rate"]) * result["total_selections"] * args.expert_bytes
            print(f"    estimated bytes read on cache miss: {wasted:,.0f}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
