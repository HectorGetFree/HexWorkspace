"""离线 Oracle / Gate 回归；不访问生产服务或模型。"""
import unittest
import io
import json
import tempfile
from pathlib import Path
from contextlib import redirect_stdout
from unittest.mock import patch
import run_scifact_production_hybrid as runner
from run_scifact_production_hybrid import check_profile, evaluate_response, summarize, percentile, generation_hash


class ProductionHybridRunnerTest(unittest.TestCase):
    def profile(self):
        return {"enabled": True, "rerankEnabled": True, "maxChunksPerDocument": 1, "rerankWeight": 1.0,
                "ready": True, "generationHash": "hash", "chunkCount": 19557}

    def test_gate_rejects_disabled_incomplete_or_wrong_profile(self):
        check_profile(self.profile(), "hash", 19557)
        for field, value in (("enabled", False), ("rerankEnabled", False), ("generationHash", "other"),
                             ("chunkCount", 1), ("maxChunksPerDocument", 2), ("rerankWeight", .5)):
            profile = self.profile()
            profile[field] = value
            with self.assertRaises(RuntimeError):
                check_profile(profile, "hash", 19557)

    def test_document_oracle_and_rerank_fallback_are_independent(self):
        question = {"id": "q", "question": "query", "evidences": [{"documentKey": "a"}, {"documentKey": "b"}]}
        response = {"hits": [{"documentCode": "doc-a"}], "diagnostics": {
            "authorityValidCandidates": [{}, {}], "rerankedCandidates": [], "finalCandidates": [{}]}}
        result = evaluate_response(question, response, {"doc-a": "a"}, 5)
        self.assertEqual(.5, result["recallAtK"])
        self.assertTrue(result["hitAtK"])
        self.assertEqual("FALLBACK", result["rerankStatus"])
        response["diagnostics"]["rerankedCandidates"] = [{}, {}]
        self.assertEqual("APPLIED", evaluate_response(question, response, {"doc-a": "a"}, 5)["rerankStatus"])

    def test_duplicate_unknown_and_missing_diagnostics_are_rejected(self):
        question = {"id": "q", "question": "query", "evidences": [{"documentKey": "a"}]}
        for hits in ([{"documentCode": "a"}, {"documentCode": "a"}], [{"documentCode": "unknown"}], []):
            with self.assertRaises(ValueError):
                evaluate_response(question, {"hits": hits}, {"a": "a"}, 5)

    def test_all_registered_denominator_keeps_failed_and_unexecuted_cases(self):
        cases = [{"status": "SUCCESS", "rerankStatus": "APPLIED", "latencyMs": 10,
                  "hitAtK": True, "recallAtK": .5, "reciprocalRankAtK": 1}, {"status": "ERROR"}]
        summary = summarize(cases, 300, 5, "INCOMPLETE")
        self.assertEqual(1 / 300, summary["allRegistered"]["hitAtK"])
        self.assertEqual(1, summary["validExecution"]["hitAtK"])
        self.assertIsNone(summary["providerUsage"])
        self.assertEqual(1, summary["errorQueries"])

    def test_hash_ignores_listing_order_and_percentile_is_nearest_rank(self):
        rows = [{"generationId": "b"}, {"generationId": "a"}]
        self.assertEqual(generation_hash(rows), generation_hash(list(reversed(rows))))
        self.assertEqual(19, percentile(list(range(1, 21)), 95))

    def test_full_offline_run_and_resume_keep_errors_without_reexecuting(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "qa").mkdir()
            questions = [{"id": f"q{i}", "question": f"query{i}", "evidences": [{"documentKey": "d0"}]} for i in range(300)]
            (root / "qa/test.jsonl").write_text("\n".join(json.dumps(q) for q in questions))
            mapping = {f"d{i}": {"documentCode": f"doc{i}"} for i in range(5183)}
            map_path = root / "map.json"
            map_path.write_text(json.dumps(mapping))
            ready = [{"documentCode": f"doc{i}", "generationId": f"g{i}", "ready": True} for i in range(5183)]
            profile = self.profile() | {"servingConfigId": 7, "generationHash": generation_hash(ready), "sourceChars": 9287805}
            response = {"hits": [{"documentCode": "doc0"}], "diagnostics": {
                "authorityValidCandidates": [{}, {}], "rerankedCandidates": [{}, {}], "finalCandidates": [{}]}}
            class FakeApi:
                calls = 0
                def __init__(self, args): self.evaluation_token = "offline"
                def readiness(self, workspace, codes): return [r for r in ready if r["documentCode"] in set(codes)]
                def request(self, *args, **kwargs): return profile
                def retrieve(self, workspace, query, limit, top):
                    self.calls += 1
                    FakeApi.calls += 1
                    if query == "query0": raise RuntimeError("offline injected failure")
                    return response
            argv = ["runner", "--workspace-id", "10", "--evaluation-token", "offline",
                    "--dataset-root", str(root), "--document-map", str(map_path), "--output-dir", str(root / "run")]
            with patch.object(runner, "WorkspaceApi", FakeApi), patch.object(runner, "implementation_hashes", return_value={}), \
                    patch.object(runner.subprocess, "check_output", return_value="offline-head"), redirect_stdout(io.StringIO()):
                with patch("sys.argv", argv), self.assertRaises(SystemExit): runner.main()
                self.assertEqual(300, FakeApi.calls, (root / "run/stop.json").read_text() if (root / "run/stop.json").exists() else "")
                with patch("sys.argv", argv + ["--resume"]), self.assertRaises(SystemExit): runner.main()
                self.assertEqual(300, FakeApi.calls)
            summary = json.loads((root / "run/summary.json").read_text())
            self.assertEqual(299, summary["successfulQueries"])
            self.assertEqual(1, summary["errorQueries"])
            self.assertEqual("COMPLETE_WITH_ERRORS_OR_FALLBACKS", summary["gate"])


if __name__ == "__main__":
    unittest.main()
