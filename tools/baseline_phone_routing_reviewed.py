#!/usr/bin/env python3
"""CPU baselines for reviewed phone routes; leaves the historical test unopened."""
from __future__ import annotations

import argparse
import collections
import hashlib
import json
from pathlib import Path

import joblib
import numpy as np
from scipy.optimize import minimize_scalar
from scipy.special import softmax
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import accuracy_score, classification_report, confusion_matrix, f1_score, log_loss
from sklearn.pipeline import make_pipeline


def load(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def ece(probs: np.ndarray, labels: list[str], classes: list[str], bins: int = 10) -> float:
    chosen = probs.argmax(axis=1)
    confidence = probs.max(axis=1)
    correct = np.array([classes[i] == gold for i, gold in zip(chosen, labels)])
    edges = np.linspace(0.0, 1.0, bins + 1)
    value = 0.0
    for i in range(bins):
        mask = (confidence >= edges[i]) & (confidence < edges[i + 1] if i < bins - 1 else confidence <= 1)
        if mask.any():
            value += mask.mean() * abs(confidence[mask].mean() - correct[mask].mean())
    return float(value)


def metrics(rows: list[dict], labels: list[str], preds: list[str], probs: np.ndarray,
            classes: list[str]) -> dict:
    return {
        "accuracy": float(accuracy_score(labels, preds)),
        "macro_f1": float(f1_score(labels, preds, labels=classes, average="macro", zero_division=0)),
        "log_loss": float(log_loss(labels, probs, labels=classes)),
        "ece_10_bins": ece(probs, labels, classes),
        "per_route": classification_report(labels, preds, labels=classes, output_dict=True, zero_division=0),
        "confusion_matrix": confusion_matrix(labels, preds, labels=classes).tolist(),
        "errors": [{"id": row["id"], "text": row["state"], "gold": gold, "predicted": pred,
                    "confidence": float(probs[i].max()), "scenario": row.get("scenario_family")}
                   for i, (row, gold, pred) in enumerate(zip(rows, labels, preds)) if gold != pred],
    }


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--data-dir", type=Path, required=True)
    p.add_argument("--output-dir", type=Path, required=True)
    args = p.parse_args()
    if args.output_dir.exists():
        raise FileExistsError(args.output_dir)
    files = {split: args.data_dir / f"{split}.jsonl" for split in ("train", "validation", "calibration")}
    data = {split: load(path) for split, path in files.items()}
    for split, rows in data.items():
        if not rows or any(row.get("label_source") != "human_reviewed" for row in rows):
            raise ValueError(f"{split} has missing or unreviewed rows")
    ids = [row["id"] for rows in data.values() for row in rows]
    if len(ids) != len(set(ids)):
        raise ValueError("Train/validation/calibration IDs overlap")
    classes = sorted({r["route__label"] for r in data["train"]})
    if len(classes) != 9:
        raise ValueError("Expected nine routes")
    x_train = [r["state"] for r in data["train"]]
    y_train = [r["route__label"] for r in data["train"]]
    x_val = [r["state"] for r in data["validation"]]
    y_val = [r["route__label"] for r in data["validation"]]
    candidates = {
        "word_tfidf_logreg": TfidfVectorizer(ngram_range=(1, 2), sublinear_tf=True),
        "char_tfidf_logreg": TfidfVectorizer(analyzer="char_wb", ngram_range=(3, 5), sublinear_tf=True),
    }
    fitted = {}
    results = {}
    for name, vectorizer in candidates.items():
        model = make_pipeline(vectorizer, LogisticRegression(max_iter=2000, random_state=3407))
        model.fit(x_train, y_train)
        probs = model.predict_proba(x_val)
        preds = model.predict(x_val).tolist()
        fitted[name] = model
        results[name] = metrics(data["validation"], y_val, preds, probs, classes)
    winner = max(results, key=lambda name: (results[name]["macro_f1"], results[name]["accuracy"], name))
    model = fitted[winner]
    x_cal = [r["state"] for r in data["calibration"]]
    y_cal = [r["route__label"] for r in data["calibration"]]
    raw = model.predict_proba(x_cal)
    logits = np.log(np.maximum(raw, 1e-12))
    objective = lambda t: log_loss(y_cal, softmax(logits / t, axis=1), labels=classes)
    fit = minimize_scalar(objective, bounds=(0.05, 10.0), method="bounded")
    temperature = float(fit.x)
    scaled = softmax(logits / temperature, axis=1)
    calibration = {
        "rows": len(y_cal), "temperature": temperature,
        "uncalibrated_log_loss": float(objective(1.0)),
        "calibrated_log_loss_in_sample": float(objective(temperature)),
        "uncalibrated_ece_10_bins": ece(raw, y_cal, classes),
        "calibrated_ece_10_bins_in_sample": ece(scaled, y_cal, classes),
        "warning": "Temperature is fitted and measured on the same 90 calibration rows; improvement is in-sample.",
    }
    args.output_dir.mkdir(parents=True)
    joblib.dump(model, args.output_dir / "selected-baseline.joblib")
    report = {
        "data_dir": str(args.data_dir.resolve()),
        "split_sha256": {s: sha256(path) for s, path in files.items()},
        "test_opened": False,
        "train_route_counts": dict(collections.Counter(y_train)),
        "validation_route_counts": dict(collections.Counter(y_val)),
        "classes": classes, "validation": results,
        "selected_by_validation_macro_f1": winner,
        "calibration": calibration,
        "versions": {"scikit_learn": __import__("sklearn").__version__, "numpy": np.__version__},
    }
    (args.output_dir / "report.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"selected": winner,
                      "validation": {name: {"accuracy": r["accuracy"], "macro_f1": r["macro_f1"],
                                             "errors": len(r["errors"])} for name, r in results.items()},
                      "calibration": calibration, "output": str(args.output_dir)}, indent=2))


if __name__ == "__main__":
    main()
