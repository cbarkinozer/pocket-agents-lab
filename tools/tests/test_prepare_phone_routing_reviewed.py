import json
import random
import sys
import unittest
from pathlib import Path

TOOLS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(TOOLS))
from prepare_phone_routing_reviewed import (  # noqa: E402
    CONTRACT, audit, corrected_row, grouped_split, load_jsonl, targeted_plan, template_groups,
)
from prepare_phone_routing_available import choose_validation_groups  # noqa: E402


class ReviewedPhoneDataTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        data = TOOLS.parent / "data/phone_routing"
        cls.source = {split: load_jsonl(data / f"{split}.jsonl") for split in ("train", "calibration", "test")}
        cls.original = {row["id"]: row for rows in cls.source.values() for row in rows}
        cls.contract = json.loads(CONTRACT.read_text(encoding="utf-8"))

    def test_known_cross_split_paraphrases_share_group(self):
        groups = template_groups(list(self.original.values()))
        self.assertEqual(groups["pr_get_media_info_train_053"], groups["pr_get_media_info_test_009"])
        self.assertEqual(groups["pr_answer_train_060"], groups["pr_answer_test_005"])
        self.assertEqual(groups["pr_search_notes_train_013"], groups["pr_search_notes_calibration_009"])

    def test_grouped_split_has_no_shared_templates(self):
        groups = template_groups(list(self.original.values()))
        dev = self.source["train"] + self.source["calibration"]
        parts = grouped_split(dev, groups)
        self.assertEqual({name: len(rows) for name, rows in parts.items()},
                         {"train": 810, "validation": 90, "calibration": 90})
        for left, right in (("train", "validation"), ("train", "calibration"), ("validation", "calibration")):
            self.assertFalse({groups[row["id"]] for row in parts[left]} &
                             {groups[row["id"]] for row in parts[right]})

    def test_audit_blocks_unreviewed_and_conflicting_decisions(self):
        source = self.original["pr_save_note_train_092"]
        record = {"id": source["id"], "fields": {"request": source["state"]}, "responses": [{
            "status": "submitted", "values": {
                "correct_route": {"value": "search_notes"},
                "review_decision": {"value": "keep"},
                "scenario_family": {"value": "recall_specific"},
            },
        }]}
        snapshot = {"datasets": [{"records": [record]}]}
        report, _ = audit(snapshot, {source["id"]: source}, self.contract)
        self.assertFalse(report["ready_to_finalize"])
        self.assertEqual(report["issues"]["route_changed_but_keep"]["count"], 1)
        record["responses"] = []
        report, _ = audit(snapshot, {source["id"]: source}, self.contract)
        self.assertEqual(report["issues"]["missing_response"]["count"], 1)

    def test_corrected_route_updates_all_training_targets(self):
        source = self.original["pr_save_note_train_092"]
        row = corrected_row(source, {"correct_route": "search_notes"}, self.contract, "train")
        self.assertEqual(row["route__label"], "search_notes")
        self.assertEqual(json.loads(row["gold"])["route"]["label"], "search_notes")
        self.assertEqual(json.loads(row["route__probabilities"])["search_notes"], 1.0)
        self.assertEqual(json.loads(row["questions"]), self.contract)

    def test_audit_flags_route_scenario_mismatch(self):
        source = self.original["pr_phone_health_check_test_000"]
        record = {"id": source["id"], "fields": {"request": source["state"]}, "responses": [{
            "status": "submitted", "values": {
                "correct_route": {"value": "phone_health_check"},
                "review_decision": {"value": "keep"},
                "scenario_family": {"value": "general_knowledge"},
            },
        }]}
        report, _ = audit({"datasets": [{"records": [record]}]}, {source["id"]: source}, self.contract)
        self.assertEqual(report["issues"]["route_scenario_mismatch"]["sample_ids"], [source["id"]])

    def test_targeted_plan_is_fixed_balanced_and_separates_splits(self):
        groups = template_groups(list(self.original.values()))
        plan = targeted_plan(self.source, groups)
        self.assertEqual(plan, targeted_plan(self.source, groups))
        self.assertEqual(len(plan["validation"]), 90)
        self.assertEqual(len(plan["training_audit"]), 180)
        self.assertEqual(len(plan["required"]), 450)
        self.assertFalse(set(plan["validation"]) & set(plan["training_audit"]))
        for name, expected in (("validation", 10), ("training_audit", 20)):
            counts = {route: sum(self.original[rid]["route__label"] == route for rid in plan[name])
                      for route in self.contract["route"]["criteria"]}
            self.assertEqual(set(counts.values()), {expected})
        eval_groups = {groups[rid] for name in ("validation", "calibration", "historical_test")
                       for rid in plan[name]}
        train_ids = {row["id"] for row in self.source["train"]} - set(plan["validation"] + plan["quarantined_train"])
        self.assertFalse(eval_groups & {groups[rid] for rid in train_ids})

    def test_unreviewed_training_label_has_provenance(self):
        row = corrected_row(self.source["train"][0], None, self.contract, "train")
        self.assertEqual(row["label_source"], "synthetic_unreviewed")
        self.assertEqual(row["route__label"], self.source["train"][0]["route__label"])

    def test_validation_covers_scenarios_and_leaves_training_examples(self):
        categories = ["general_knowledge"] * 18 + ["arithmetic"] * 2 + ["creative"] * 2
        groups = {str(i): [{"id": str(i)}] for i in range(len(categories))}
        reviewed = {str(i): {"scenario_family": category} for i, category in enumerate(categories)}
        selected = choose_validation_groups(groups, reviewed, random.Random(3407))
        self.assertEqual(len(selected), 10)
        for category in set(categories):
            self.assertTrue(any(reviewed[key]["scenario_family"] == category for key in selected))
            self.assertTrue(any(reviewed[key]["scenario_family"] == category for key in groups if key not in selected))


if __name__ == "__main__":
    unittest.main()
