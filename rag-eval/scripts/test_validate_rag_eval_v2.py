"""Contract tests for governed benchmark dataset validation."""
from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).resolve().parent / "validate_rag_eval_v2.py"


def base_case():
    evidence = {"chunkId": "doc#a", "quote": "evidence text", "relevanceGrade": 3}
    return {
        "queryId": "case", "suite": "workspace_business", "query": "question", "queryType": "single_hop_semantic",
        "difficulty": "MEDIUM", "answerable": True, "relevantDocumentIds": ["doc"], "relevantChunkIds": ["doc#a"],
        "evidence": [evidence], "hardNegativeChunkIds": [], "hardNegativeReasons": {}, "expectedRetrievalStrength": "dense",
        "generationMethod": "manual", "reviewStatus": "REVIEWED", "notes": "fixture",
        "evidenceRequirementGroups": [{"requirementId": "fact", "evidence": [evidence]}],
    }


class DatasetValidatorTest(unittest.TestCase):
    def validate(self, case, mapping=None):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "cases").mkdir()
            (root / "corpus").mkdir()
            (root / "cases" / "test.jsonl").write_text(json.dumps(case) + "\n", encoding="utf-8")
            (root / "corpus" / "chunks.jsonl").write_text(json.dumps({"chunkId": "doc#a", "documentId": "doc", "content": "evidence text"}) + "\n", encoding="utf-8")
            command = [sys.executable, str(SCRIPT), "--dataset-root", str(root)]
            if mapping is not None:
                mapping_path = root / "mapping.json"
                mapping_path.write_text(json.dumps(mapping), encoding="utf-8")
                command.extend(["--runtime-mapping", str(mapping_path)])
            return subprocess.run(command, check=False, text=True, capture_output=True)

    def test_legacy_case_remains_valid(self):
        case = base_case()
        case.pop("evidenceRequirementGroups")
        result = self.validate(case)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_empty_requirement_group_is_an_error(self):
        case = base_case()
        case["evidenceRequirementGroups"][0]["evidence"] = []
        result = self.validate(case)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("must not be empty", result.stderr)

    def test_duplicate_evidence_inside_group_is_an_error(self):
        case = base_case()
        case["evidenceRequirementGroups"][0]["evidence"] *= 2
        result = self.validate(case)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("duplicate evidence", result.stderr)

    def test_unanswerable_cannot_have_positive_requirement(self):
        case = base_case()
        case.update({"answerable": False, "queryType": "unanswerable", "relevantDocumentIds": [], "relevantChunkIds": [], "evidence": []})
        result = self.validate(case)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("unanswerable case has evidence requirement groups", result.stderr)

    def test_hard_negative_cannot_overlap_gold(self):
        case = base_case()
        case["hardNegativeChunkIds"] = ["doc#a"]
        case["hardNegativeReasons"] = {"doc#a": "fixture"}
        result = self.validate(case)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("hard negative overlaps gold", result.stderr)

    def test_verified_anchor_requires_runtime_mapping(self):
        case = base_case()
        case["reviewStatus"] = "VERIFIED"
        result = self.validate(case, mapping=[])
        self.assertNotEqual(0, result.returncode)
        self.assertIn("verified evidence anchor is not mapped", result.stderr)


if __name__ == "__main__":
    unittest.main()
