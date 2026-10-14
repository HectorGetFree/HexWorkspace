import unittest
from run_scifact_production_hybrid import evaluate_response


class SciFactChunkTopFiveTest(unittest.TestCase):
    def response(self):
        return {"hits": [{"documentCode": "a", "generationId": "g", "chunkNo": n} for n in range(4)]
                        + [{"documentCode": "b", "generationId": "h", "chunkNo": 0}],
                "diagnostics": {"authorityValidCandidates": [{}, {}], "rerankedCandidates": [{}, {}], "finalCandidates": []}}

    def test_duplicate_documents_consume_chunk_ranks_without_inflating_recall(self):
        q = {"id": "q", "question": "query", "evidences": [{"documentKey": "B"}]}
        row = evaluate_response(q, self.response(), {"a": "A", "b": "B"}, 5, unlimited=True)
        self.assertTrue(row["hitAtK"])
        self.assertEqual(1., row["recallAtK"])
        self.assertEqual(.2, row["reciprocalRankAtK"])
        self.assertEqual(["A", "B"], row["uniqueRetrievedDocumentKeys"])
        self.assertEqual(5, len(row["retrievedDocumentKeys"]))

    def test_original_distinct_document_contract_remains_strict(self):
        q = {"id": "q", "question": "query", "evidences": [{"documentKey": "A"}]}
        with self.assertRaises(ValueError): evaluate_response(q, self.response(), {"a": "A", "b": "B"}, 5)


if __name__ == "__main__": unittest.main()
