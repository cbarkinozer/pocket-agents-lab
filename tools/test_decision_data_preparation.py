"""CPU regression checks for decision data and metric correctness."""
import json
import unittest
from types import SimpleNamespace

import numpy as np
from decision_training_utils import compute_decision_metrics, decision_metrics_for_items, decision_trainer_class
from prepare_general_mixture import clean_pool, intent_row, stratified_split, weighted_sample


class DecisionPreparationTests(unittest.TestCase):
    def test_native_evaluation_keeps_all_questions(self):
        calls = []
        class Native:
            @staticmethod
            def evaluate(model, tokenizer, items, batch_size):
                calls.append(sum(len(item["labels"]) for item in items))
                return {"accuracy": .8, "loss": .4}
        trainer = decision_trainer_class(object, Native)()
        trainer.eval_dataset = [{"labels": [0] * 5}, {"labels": [1] * 5}]
        trainer.model = trainer.processing_class = trainer.state = None
        trainer.args = SimpleNamespace(per_device_eval_batch_size=4)
        trainer.control = "control"
        trainer.log = lambda metrics: None
        trainer.callback_handler = SimpleNamespace(on_evaluate=lambda *args: args[2])
        self.assertEqual(trainer.evaluate()["eval_accuracy"], .8)
        self.assertEqual(calls, [10])

    def test_intent_gold_and_stable_source_ids(self):
        row = intent_row("battery", "a", ["a", "b"], "source", "train", 12)
        self.assertEqual(row["id"], "source_train_000012")
        self.assertEqual(json.loads(row["gold"])["intent"]["label"], "a")
        self.assertEqual(json.loads(row["gold"])["intent"]["probabilities"], {"a": 1., "b": 0.})

    def test_soft_targets_and_padding(self):
        pred = SimpleNamespace(predictions=np.array([[0., 2., 99.], [3., 1., -100.]]),
                               label_ids=np.array([[.1, .9, -100.], [.8, .2, -100.]]))
        self.assertEqual(compute_decision_metrics(pred)["accuracy"], 1.)

    def test_explicit_gold_over_soft_argmax(self):
        pred = SimpleNamespace(predictions=np.array([[2., 0.]]), label_ids=np.array([[.4, .6]]))
        metrics = decision_metrics_for_items([{"labels": [0], "source": {"questions": {"q": "same"}}}])
        self.assertEqual(metrics(pred)["accuracy"], 1.)

    def test_mixed_schema_macro_f1_not_reported(self):
        pred = SimpleNamespace(predictions=np.array([[2., 0.], [0., 2.]]),
                               label_ids=np.array([[1., 0.], [0., 1.]]))
        items = [{"labels": [0], "source": {"questions": {"a": 1}}},
                 {"labels": [1], "source": {"questions": {"b": 2}}}]
        self.assertEqual(decision_metrics_for_items(items)(pred), {"accuracy": 1.})

    def test_splits_are_disjoint_and_repeatable(self):
        rows = [{"id": f"{label}{i}", "route__label": label} for label in ("a", "b") for i in range(20)]
        first = stratified_split(rows, 3407)
        self.assertEqual(first, stratified_split(rows, 3407))
        ids = [{r["id"] for r in split} for split in first]
        self.assertFalse(ids[0] & ids[1] or ids[0] & ids[2] or ids[1] & ids[2])

    def test_weighted_mixture_keeps_every_class(self):
        sources = {name: [{"id": f"{name}{label}{i}", "route__label": label}
                          for label in ("a", "b", "c") for i in range(n)]
                   for name, n in (("small", 4), ("large", 30))}
        rows, counts = weighted_sample(sources, {"small": 1., "large": .5}, 3407)
        self.assertEqual(counts, {"large": 6, "small": 12})
        self.assertEqual(len(rows), len({r["id"] for r in rows}))
        self.assertEqual({r["route__label"] for r in rows if r["id"].startswith("large")}, {"a", "b", "c"})

    def test_protected_states_and_normalized_duplicates_removed(self):
        rows = [{"id": str(i), "state": text} for i, text in enumerate(("hello!", "HELLO", "protected"))]
        excluded = []
        self.assertEqual(len(clean_pool(rows, {"protected"}, excluded)), 1)
        self.assertEqual(len(excluded), 2)


if __name__ == "__main__":
    unittest.main()
