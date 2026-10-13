"""Focused unit tests for governed evidence-requirement scoring."""
from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_rag_eval_v2 as runner  # noqa: E402


class FakeApi:
    def __init__(self, hits):
        self.hits = hits

    def retrieve(self, workspace_id, query, candidate_limit, top_k):
        return {"hits": self.hits, "timingMs": {}, "diagnostics": {}}


def hit(chunk_no):
    return {"documentCode": "doc-a", "generationId": "gen-a", "chunkNo": chunk_no}


def mapping(*chunk_nos):
    return [
        {"canonicalChunkId": f"source-a#evidence-{index}", "documentCode": "doc-a", "generationId": "gen-a", "runtimeChunkNos": [chunk_no]}
        for index, chunk_no in enumerate(chunk_nos, start=1)
    ]


def question(groups=None):
    value = {
        "queryId": "case-1", "suite": "workspace_business", "queryType": "single_hop_semantic",
        "query": "evidence", "answerable": True, "relevantDocumentIds": ["source-a"],
        "relevantChunkIds": ["source-a#evidence-1"],
        "evidence": [{"chunkId": "source-a#evidence-1", "quote": "one", "relevanceGrade": 3}],
    }
    if groups is not None:
        value["evidenceRequirementGroups"] = groups
    return value


def group(requirement_id, *chunk_ids):
    result = {
        "requirementId": requirement_id,
        "evidence": [{"chunkId": chunk_id, "quote": chunk_id, "relevanceGrade": 3} for chunk_id in chunk_ids],
    }
    if len(chunk_ids) > 1:
        result["equivalenceRationale"] = "same minimum fact"
    return result


class EvidenceRequirementScoringTest(unittest.TestCase):
    args = SimpleNamespace(workspace_id=1, candidate_limit=30, top_k=5, split="test")
    document_map = {"source-a": {"documentCode": "doc-a"}}

    def evaluate_one(self, api, value, rows):
        with tempfile.TemporaryDirectory() as directory:
            return runner.evaluate(api, self.args, [value], self.document_map, rows, Path(directory))[0]

    def test_one_exact_gold_requires_the_mapped_chunk_not_only_the_same_document(self):
        result = self.evaluate_one(FakeApi([hit(0)]), question(), mapping(1))
        self.assertTrue(result["documentHitAtK"])
        self.assertFalse(result["fullEvidenceHitAtK"])
        self.assertEqual(0.0, result["evidenceGroupRecallAtK"])

    def test_any_equivalent_evidence_satisfies_one_requirement(self):
        value = question([group("same-fact", "source-a#evidence-1", "source-a#evidence-2")])
        result = self.evaluate_one(FakeApi([hit(2)]), value, mapping(1, 2))
        self.assertTrue(result["fullEvidenceHitAtK"])
        self.assertEqual(1.0, result["evidenceGroupRecallAtK"])
        self.assertEqual(1.0, result["firstEvidenceReciprocalRankAtK"])

    def test_partial_multi_hop_is_not_a_full_hit(self):
        value = question([group("fact-a", "source-a#evidence-1"), group("fact-b", "source-a#evidence-2")])
        result = self.evaluate_one(FakeApi([hit(1)]), value, mapping(1, 2))
        self.assertFalse(result["fullEvidenceHitAtK"])
        self.assertEqual(0.5, result["evidenceGroupRecallAtK"])
        self.assertEqual([True, False], result["evidenceGroupSatisfiedAtK"])

    def test_complete_multi_hop_hits_every_requirement(self):
        value = question([group("fact-a", "source-a#evidence-1"), group("fact-b", "source-a#evidence-2")])
        result = self.evaluate_one(FakeApi([hit(2), hit(1)]), value, mapping(1, 2))
        self.assertTrue(result["fullEvidenceHitAtK"])
        self.assertEqual(1.0, result["evidenceGroupRecallAtK"])
        self.assertEqual([True, True], result["evidenceGroupSatisfiedAtK"])

    def test_alternatives_do_not_inflate_group_recall_denominator(self):
        value = question([group("fact-a", "source-a#evidence-1", "source-a#evidence-2", "source-a#evidence-3")])
        result = self.evaluate_one(FakeApi([hit(3)]), value, mapping(1, 2, 3))
        self.assertEqual(1.0, result["evidenceGroupRecallAtK"])
        self.assertTrue(result["fullEvidenceHitAtK"])

    def test_missing_anchor_mapping_is_an_error(self):
        value = question([group("missing", "source-a#evidence-2")])
        result = self.evaluate_one(FakeApi([hit(1)]), value, mapping(1))
        self.assertEqual("ERROR", result["status"])
        self.assertIn("no runtime chunk mapping", result["error"])

    def test_summary_exposes_group_aware_metrics_and_legacy_aliases(self):
        row = {
            "status": "SUCCESS", "goldenDocumentIds": ["source-a"], "goldenRuntimeChunks": [["doc-a", "gen-a", 1]],
            "documentHitAtK": True, "documentRecallAtK": 1.0, "documentReciprocalRankAtK": 1.0,
            "chunkHitAtK": False, "chunkRecallAtK": 0.5, "chunkReciprocalRankAtK": 1.0,
            "fullEvidenceHitAtK": False, "evidenceGroupRecallAtK": 0.5, "firstEvidenceReciprocalRankAtK": 1.0,
            "latencyMs": 1.0,
        }
        summary = runner.build_summary([row], self.args, "fixture", 1)
        self.assertEqual("evidence_requirement_group", summary["primaryMetricScope"])
        self.assertEqual(0.0, summary["fullEvidenceHitRateAt5"])
        self.assertEqual(0.5, summary["evidenceGroupRecallAt5"])
        self.assertEqual(1.0, summary["firstEvidenceMrrAt5"])
        self.assertEqual(0.5, summary["chunkRecallAt5"])


class CanonicalAnchorMappingTest(unittest.TestCase):
    def test_uncited_canonical_anchor_does_not_block_quote_mapping(self):
        class MappingApi:
            def active_chunks(self, workspace_id, codes):
                return [{"documentCode": "doc-a", "chunkNo": 0, "content": "anchor one"}]

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "corpus").mkdir()
            (root / "cases").mkdir()
            (root / "corpus" / "chunks.jsonl").write_text(
                "\n".join([
                    '{"chunkId":"source-a#one","documentId":"source-a","content":"anchor one"}',
                    '{"chunkId":"source-a#two","documentId":"source-a","content":"anchor two"}',
                ]) + "\n",
                encoding="utf-8",
            )
            (root / "cases" / "test.jsonl").write_text(
                '{"queryId":"case","reviewStatus":"UNREVIEWED","evidence":[]}\n', encoding="utf-8"
            )
            args = SimpleNamespace(workspace_id=1, split="test", allow_unreviewed=True)
            self.assertEqual([], runner.map_quotes(
                MappingApi(), args, root,
                {"source-a": {"documentCode": "doc-a"}},
                {"source-a": {"generationId": "gen-a"}},
                root,
            ))


if __name__ == "__main__":
    unittest.main()
