"""业务集生产 Runner 的离线回归，不调用真实服务或模型。"""
import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch
import run_business_production_hybrid as runner


def question(query_id="q"):
    return {"queryId": query_id, "query": "query", "queryType": "single_hop_semantic", "answerable": True,
            "reviewStatus": "EVIDENCE_MAPPED", "evidenceRequirementGroups": [{"requirementId": "fact",
            "evidence": [{"chunkId": "d0#s1", "quote": "unique fact", "relevanceGrade": 3}]}]}


def mapping():
    return [{"canonicalChunkId": "d0#s1", "documentId": "d0", "documentCode": "doc0",
             "generationId": "g0", "runtimeChunkNos": [1]}]


def response(chunk_no=1):
    return {"hits": [{"documentCode": "doc0", "generationId": "g0", "chunkNo": chunk_no}],
            "diagnostics": {"authorityValidCandidates": [{}, {}], "rerankedCandidates": [{}, {}], "finalCandidates": [{}]}}


class BusinessProductionRunnerTest(unittest.TestCase):
    def test_unlimited_gate_rejects_old_server_before_execution(self):
        runner.require_unlimited_profile({"maxChunksPerDocument": 0, "documentLimitPolicy": "NONE"}, True)
        for profile in ({"maxChunksPerDocument": 1}, {}, {"maxChunksPerDocument": 0}):
            with self.assertRaises(RuntimeError): runner.require_unlimited_profile(profile, True)
        runner.require_unlimited_profile({"maxChunksPerDocument": 1}, False)
    def test_correct_document_does_not_imply_correct_evidence(self):
        by_chunk = {row["canonicalChunkId"]: row for row in mapping()}
        good = runner.evaluate(question(), response(), {"doc0": "d0"}, by_chunk, 5)
        wrong_chunk = runner.evaluate(question(), response(2), {"doc0": "d0"}, by_chunk, 5)
        self.assertTrue(good["fullEvidenceHitAtK"])
        self.assertTrue(wrong_chunk["documentHitAtK"])
        self.assertFalse(wrong_chunk["fullEvidenceHitAtK"])

    def test_all_evidence_groups_are_required(self):
        q = question()
        q["evidenceRequirementGroups"].append({"requirementId": "second", "evidence": [{"chunkId": "d0#s2", "quote": "other"}]})
        by_chunk = {row["canonicalChunkId"]: row for row in mapping()}
        by_chunk["d0#s2"] = mapping()[0] | {"runtimeChunkNos": [2]}
        row = runner.evaluate(q, response(), {"doc0": "d0"}, by_chunk, 5)
        self.assertFalse(row["fullEvidenceHitAtK"])
        self.assertEqual(.5, row["evidenceGroupRecallAtK"])

    def test_mapping_rejects_stale_generation_and_unsupported_quote(self):
        chunks = [{"documentCode": "doc0", "generationId": "g0", "chunkNo": 1, "content": "unique fact"}]
        docs = {"d0": {"documentCode": "doc0"}}
        ready = [{"documentCode": "doc0", "generationId": "g0"}]
        runner.validate_mapping([question()], mapping(), chunks, docs, ready)
        with self.assertRaises(ValueError):
            runner.validate_mapping([question()], mapping(), chunks, docs, [{"documentCode": "doc0", "generationId": "new"}])
        chunks[0]["content"] = "unsupported content"
        with self.assertRaises(ValueError): runner.validate_mapping([question()], mapping(), chunks, docs, ready)

    def test_error_denominator_and_draft_label_are_preserved(self):
        q = question()
        row = runner.evaluate(q, response(), {"doc0": "d0"}, {"d0#s1": mapping()[0]}, 5) | {"latencyMs": 10}
        summary = runner.summarize([row, {"status": "ERROR"}], "INCOMPLETE", 5)
        self.assertEqual(1 / 143, summary["allRegistered"]["documentHitRateAtK"])
        self.assertEqual(1, summary["validExecution"]["documentHitRateAtK"])
        self.assertEqual("EVIDENCE_MAPPED_REVIEW_PENDING", summary["qualification"])
        self.assertIsNone(summary["providerUsage"])

    def test_original_143_cases_keep_existing_gold_mapping(self):
        qs = runner.read_rows(runner.DEFAULT_DATASET / "cases/evidence-mapped.jsonl")
        rows = json.loads((runner.DEFAULT_BASELINE / "quote-to-runtime-chunk.json").read_text())
        by_chunk = {row["canonicalChunkId"]: row for row in rows}
        self.assertEqual(143, len(qs))
        for q in qs:
            self.assertEqual("EVIDENCE_MAPPED", q["reviewStatus"])
            self.assertTrue(all(runner.gold_runtime_groups(q, by_chunk)))

    def test_full_offline_run_and_resume_never_reissue_failed_query(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "data/cases").mkdir(parents=True)
            (root / "data/corpus").mkdir()
            (root / "baseline").mkdir()
            qs = [question(f"q{i}") | {"query": f"query{i}"} for i in range(143)]
            (root / "data/cases/evidence-mapped.jsonl").write_text("\n".join(json.dumps(q) for q in qs))
            (root / "data/corpus/manifest.jsonl").write_text("offline fixture")
            docs = {f"d{i}": {"documentCode": f"doc{i}"} for i in range(34)}
            (root / "baseline/document-map.json").write_text(json.dumps(docs))
            (root / "baseline/quote-to-runtime-chunk.json").write_text(json.dumps(mapping()))
            ready = [{"documentCode": f"doc{i}", "generationId": f"g{i}", "ready": True} for i in range(34)]
            chunks = [{"documentCode": f"doc{i}", "generationId": f"g{i}", "chunkNo": 1, "content": "unique fact"} for i in range(34)]
            profile = {"enabled": True, "ready": True, "rerankEnabled": True, "maxChunksPerDocument": 1,
                       "rerankWeight": 1.0, "servingConfigId": 7, "chunkCount": 34,
                       "generationHash": runner.generation_hash(ready)}
            class FakeApi:
                calls = 0
                def __init__(self, args): self.evaluation_token = "offline"
                def readiness(self, workspace, codes): return ready
                def active_chunks(self, workspace, codes): return chunks
                def request(self, *args, **kwargs): return profile
                def retrieve(self, workspace, query, limit, top):
                    FakeApi.calls += 1
                    if query == "query0": raise RuntimeError("offline injected failure")
                    return response()
            argv = ["runner", "--workspace-id", "14", "--evaluation-token", "offline", "--allow-evidence-mapped-draft",
                    "--dataset-root", str(root / "data"), "--baseline-run", str(root / "baseline"), "--output-dir", str(root / "run")]
            with patch.object(runner, "WorkspaceApi", FakeApi), patch.object(runner, "manifest_rows", return_value=[{"documentId": d} for d in docs]), \
                    patch.object(runner, "implementation_hashes", return_value={}), redirect_stdout(io.StringIO()):
                with patch("sys.argv", argv), self.assertRaises(SystemExit): runner.main()
                self.assertEqual(143, FakeApi.calls, (root / "run/stop.json").read_text() if (root / "run/stop.json").exists() else "")
                with patch("sys.argv", argv + ["--resume"]), self.assertRaises(SystemExit): runner.main()
                self.assertEqual(143, FakeApi.calls)
            summary = json.loads((root / "run/summary.json").read_text())
            self.assertEqual(142, summary["successfulQueries"])
            self.assertEqual(1, summary["errorQueries"])


if __name__ == "__main__":
    unittest.main()
