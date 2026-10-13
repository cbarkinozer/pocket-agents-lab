"""Conservative lexical leakage audit for reviewed phone train/validation/test."""
from __future__ import annotations

import json
import re
from collections import Counter
from difflib import SequenceMatcher
from pathlib import Path

root = Path(__file__).resolve().parents[2] / "data/prepared/phone-routing-human-reviewed-v2-1080-fixedval-20261013"
if not root.exists():
    root = Path("C:/Users/barkin/Documents/GitHub/pocket-agents-lab/data/prepared/phone-routing-human-reviewed-v2-1080-fixedval-20261013")
splits = {name: [json.loads(line) for line in (root / f"{name}.jsonl").read_text(encoding="utf-8").splitlines()]
          for name in ("train", "validation", "calibration", "test")}

def norm(s: str) -> str:
    return " ".join(re.findall(r"[a-z0-9]+", s.lower()))

def jaccard(a: str, b: str) -> float:
    aa, bb = set(norm(a).split()), set(norm(b).split())
    return len(aa & bb) / len(aa | bb) if aa | bb else 1.0

report = {"counts": {name: len(rows) for name, rows in splits.items()}, "comparisons": {}}
train = splits["train"]
for name in ("validation", "calibration", "test"):
    exact = []
    template_overlap = []
    high_similarity = []
    nearest = []
    for row in splits[name]:
        candidates = []
        for other in train:
            a, b = norm(row["state"]), norm(other["state"])
            jac = jaccard(a, b)
            seq = SequenceMatcher(None, a, b).ratio()
            if a == b:
                exact.append((row["id"], other["id"]))
            if row["route__label"] == other["route__label"] and row.get("scenario_family") == other.get("scenario_family"):
                if jac >= 0.75 or seq >= 0.85:
                    template_overlap.append((row["id"], other["id"], round(jac, 3), round(seq, 3)))
            if jac >= 0.75 or seq >= 0.85:
                high_similarity.append((row["id"], other["id"], round(jac, 3), round(seq, 3)))
            candidates.append((max(jac, seq), other))
        score, other = max(candidates, key=lambda x: x[0])
        nearest.append({"id": row["id"], "state": row["state"], "label": row["route__label"],
                        "nearest_id": other["id"], "nearest_state": other["state"],
                        "nearest_label": other["route__label"], "score": round(score, 3)})
    report["comparisons"][name] = {
        "normalized_exact_pairs": exact,
        "high_similarity_pair_count": len(high_similarity),
        "same_route_scenario_high_similarity_count": len(template_overlap),
        "high_similarity_pairs_first_30": high_similarity[:30],
        "nearest_top_20": sorted(nearest, key=lambda x: x["score"], reverse=True)[:20],
        "nearest_score_buckets": dict(Counter(">=0.9" if x["score"] >= 0.9 else ">=0.8" if x["score"] >= 0.8 else ">=0.7" if x["score"] >= 0.7 else "<0.7" for x in nearest)),
    }

out = Path(__file__).with_name("phone_split_similarity_audit.json")
out.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
print(json.dumps({name: {k: v for k, v in data.items() if k not in {"high_similarity_pairs_first_30", "nearest_top_20", "normalized_exact_pairs"}}
                  for name, data in report["comparisons"].items()}, indent=2))
print(out)
