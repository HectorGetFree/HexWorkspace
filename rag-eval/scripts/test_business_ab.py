"""单机制 A/B 离线 Gate，不调用真实模型。"""
import unittest
import json
import io
import tempfile
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from contextlib import redirect_stdout
import run_business_ab as runner
from run_business_ab import attribution, report
from run_business_production_hybrid import evaluate


class BusinessABTest(unittest.TestCase):
    def candidate(self, no, rank):
        return {"documentCode": "d", "generationId": "g", "chunkNo": no, "rank": rank, "score": .5}

    def fixture(self):
        q = {"queryId": "q", "query": "query", "reviewStatus": "EVIDENCE_MAPPED", "queryType": "single",
             "evidenceRequirementGroups": [{"evidence": [{"chunkId": "doc#c", "quote": "fact"}]}]}
        mapping = {"doc#c": {"documentCode": "d", "generationId": "g", "runtimeChunkNos": [2]}}
        ranked = [self.candidate(no, i + 1) for i, no in enumerate([1, 2, 3])]
        response = {"hits": ranked[:2], "diagnostics": {"qdrantCandidates": ranked, "sparseCandidates": [],
                    "fusedCandidates": ranked, "authorityValidCandidates": ranked, "rerankedCandidates": [ranked[0]],
                    "rerankedChunks": ranked, "finalCandidates": ranked[:2]}}
        return q, mapping, response

    def test_two_chunks_are_allowed_without_weakening_document_or_evidence_oracle(self):
        q, mapping, response = self.fixture()
        with self.assertRaises(ValueError): evaluate(q, response, {"d": "doc"}, mapping, 5)
        row = evaluate(q, response, {"d": "doc"}, mapping, 5, max_chunks_per_document=2)
        self.assertEqual(["doc"], row["retrievedDocumentIds"])
        self.assertTrue(row["fullEvidenceHitAtK"])
        reasons = attribution(response, q, mapping)["truncation"]
        self.assertEqual(["RETURNED", "RETURNED", "PER_DOCUMENT_LIMIT"], [r["decision"] for r in reasons])

    def test_missing_complete_rank_or_wrong_set_is_rejected(self):
        q, mapping, response = self.fixture()
        response["diagnostics"]["rerankedChunks"] = response["diagnostics"]["rerankedChunks"][:1]
        with self.assertRaises(ValueError): attribution(response, q, mapping)
        del response["diagnostics"]["rerankedChunks"]
        with self.assertRaises(ValueError): attribution(response, q, mapping)

    def test_total_limit_and_final_revalidation_are_separate(self):
        q, mapping, response = self.fixture()
        ranked = [self.candidate(i, i + 1) | {"documentCode": f"d{i}"} for i in range(6)]
        response["diagnostics"]["authorityValidCandidates"] = ranked
        response["diagnostics"]["rerankedChunks"] = ranked
        response["hits"] = ranked[:4]
        reasons = attribution(response, q, mapping)["truncation"]
        self.assertEqual("FINAL_REVALIDATION_REMOVED", reasons[4]["decision"])
        self.assertEqual("RESULT_LIMIT", reasons[5]["decision"])

    def test_pair_failure_is_not_discarded_as_success(self):
        q, mapping, response = self.fixture()
        row = evaluate(q, response, {"d": "doc"}, mapping, 5, 2) | {"latencyMs": 10, "variant": "A"}
        summary = report([row, {"queryId": "q", "variant": "B", "status": "ERROR"}], "INCOMPLETE")
        self.assertEqual(0, summary["validPairs"])
        self.assertEqual(1, summary["arms"]["B"]["errorQueries"])

    def test_286_attempts_alternate_order_and_resume_never_reissues(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "data/cases").mkdir(parents=True)
            (root / "baseline").mkdir()
            q, _, _ = self.fixture()
            q["answerable"] = True
            q["evidenceRequirementGroups"][0]["evidence"][0]["chunkId"] = "d0#c"
            qs = [q | {"queryId": f"q{i}", "query": f"query{i}"} for i in range(143)]
            (root / "data/cases/evidence-mapped.jsonl").write_text("\n".join(json.dumps(q) for q in qs))
            docs = {f"d{i}": {"documentCode": f"doc{i}"} for i in range(34)}
            mapping = [{"canonicalChunkId": "d0#c", "documentId": "d0", "documentCode": "doc0", "generationId": "g0", "runtimeChunkNos": [2]}]
            (root / "baseline/document-map.json").write_text(json.dumps(docs))
            (root / "baseline/quote-to-runtime-chunk.json").write_text(json.dumps(mapping))
            ready = [{"documentCode": f"doc{i}", "generationId": f"g{i}", "ready": True} for i in range(34)]
            chunks = [{"documentCode": f"doc{i}", "generationId": f"g{i}", "chunkNo": 2, "content": "fact"} for i in range(34)]
            profile = {"enabled": True, "ready": True, "rerankEnabled": True, "maxChunksPerDocument": 1,
                       "rerankWeight": 1., "servingConfigId": 7, "chunkCount": 34, "generationHash": runner.generation_hash(ready)}
            ranked = [self.candidate(no, no) | {"documentCode": "doc0", "generationId": "g0"} for no in (1, 2, 3)]
            args = SimpleNamespace(dataset_root=root / "data", baseline_run=root / "baseline", output_dir=root / "run",
                                   resume=False, workspace_id=14, base_url="offline", candidate_limit=30, top_k=5,
                                   evaluation_token="offline", snapshot_timeout=1)
            class FakeApi:
                calls = []
                def __init__(self, args): self.evaluation_token = "offline"
                def readiness(self, *args): return ready
                def active_chunks(self, *args): return chunks
                def request(self, method, endpoint, **kwargs):
                    body = json.loads(kwargs["body"])
                    if endpoint.endswith("hybrid-status"): return profile
                    FakeApi.calls.append((body["query"], body["variant"]))
                    if body["query"] == "query0" and body["variant"] == "B": raise RuntimeError("offline failure")
                    return {"hits": ranked[:2], "evaluationProfile": {"variant": body["variant"], "maxChunksPerDocument": 2},
                            "diagnostics": {"qdrantCandidates": ranked, "sparseCandidates": [] if body["variant"] == "A" else ranked,
                            "fusedCandidates": ranked, "authorityValidCandidates": ranked, "rerankedCandidates": ranked[:1],
                            "rerankedChunks": ranked, "finalCandidates": ranked[:2]}}
            with patch.object(runner, "WorkspaceApi", FakeApi), patch.object(runner.business, "parse_args", return_value=args), \
                    patch.object(runner.business, "manifest_rows", return_value=[{"documentId": d} for d in docs]), \
                    patch.object(runner, "implementation_hashes", return_value={}), redirect_stdout(io.StringIO()):
                with self.assertRaises(SystemExit): runner.main()
                self.assertEqual(286, len(FakeApi.calls), (root / "run/stop.json").read_text() if (root / "run/stop.json").exists() else "")
                self.assertEqual([("query0", "A"), ("query0", "B"), ("query1", "B"), ("query1", "A")], FakeApi.calls[:4])
                args.resume = True
                with self.assertRaises(SystemExit): runner.main()
                self.assertEqual(286, len(FakeApi.calls))
            summary = json.loads((root / "run/summary.json").read_text())
            self.assertEqual(142, summary["validPairs"])


if __name__ == "__main__": unittest.main()
