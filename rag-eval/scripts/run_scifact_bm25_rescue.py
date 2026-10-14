#!/usr/bin/env python3
"""Diagnose whether BM25 rescues Dense candidate misses on frozen SciFact C1.

This is an offline, document-level experiment.  It intentionally does not call
the Workspace retrieval API, rerank candidates, merge rankings, or mutate the
frozen SciFact corpus/qrels.  The only Dense signal comes from the existing C1
``qdrantCandidates`` diagnostics.
"""
from __future__ import annotations

import argparse
import json
import math
import re
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DATASET_ROOT = PROJECT_ROOT / "rag-eval" / "cache" / "general-scifact-full-v1"
DEFAULT_BASELINE_RUN = PROJECT_ROOT / "rag-eval" / "runs" / "general-scifact-full-20261004T075524Z"
TOKEN_PATTERN = re.compile(r"[a-z0-9]+(?:[-/][a-z0-9]+)*", re.IGNORECASE)
STOP_WORDS = frozenset(
    "a an and are as at be been being by for from has have if in into is it its of on or that the their this to "
    "was were will with without while whether why how what when where which who whose all any can could did do does "
    "during each either both than then these those they them we you your our not no nor only also show shows shown".split()
)


def read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")


def tokenize(text: str) -> list[str]:
    return [token.lower() for token in TOKEN_PATTERN.findall(text) if token.lower() not in STOP_WORDS]


class Bm25Index:
    """Small deterministic BM25 index backed by term postings, not a new dependency."""

    def __init__(self, documents: list[dict], *, k1: float, b: float) -> None:
        self.documents = documents
        self.k1 = k1
        self.b = b
        self.doc_lengths: list[int] = []
        self.postings: dict[str, dict[int, int]] = defaultdict(dict)
        for index, document in enumerate(documents):
            frequencies = Counter(tokenize(document["content"]))
            self.doc_lengths.append(sum(frequencies.values()))
            for term, frequency in frequencies.items():
                self.postings[term][index] = frequency
        self.average_length = sum(self.doc_lengths) / len(self.doc_lengths) if self.doc_lengths else 0.0
        self.document_count = len(documents)

    def top_n(self, query: str, limit: int) -> list[tuple[int, float]]:
        scores: dict[int, float] = defaultdict(float)
        query_terms = Counter(tokenize(query))
        for term, query_frequency in query_terms.items():
            posting = self.postings.get(term)
            if not posting:
                continue
            document_frequency = len(posting)
            inverse_document_frequency = math.log(1 + (self.document_count - document_frequency + 0.5)
                                                   / (document_frequency + 0.5))
            for document_index, term_frequency in posting.items():
                denominator = term_frequency + self.k1 * (
                    1 - self.b + self.b * self.doc_lengths[document_index] / self.average_length
                )
                scores[document_index] += query_frequency * inverse_document_frequency * (
                    term_frequency * (self.k1 + 1) / denominator
                )
        return sorted(scores.items(), key=lambda item: (-item[1], self.documents[item[0]]["documentKey"]))[:limit]


def dense_candidate_keys(case: dict, reverse_document_map: dict[str, str]) -> set[str]:
    diagnostics = case.get("retrievalDiagnostics") or {}
    return {
        reverse_document_map[candidate["documentCode"]]
        for candidate in diagnostics.get("qdrantCandidates", [])
        if candidate.get("documentCode") in reverse_document_map
    }


def best_rank(ranked_keys: list[str], gold_keys: set[str]) -> int | None:
    for rank, key in enumerate(ranked_keys, start=1):
        if key in gold_keys:
            return rank
    return None


def rescue_reason(query: str, gold_documents: list[dict]) -> str:
    """Return a transparent lexical heuristic, never a relevance judgment."""
    query_terms = set(tokenize(query))
    gold_text = " ".join(document["content"] for document in gold_documents).lower()
    shared = {term for term in query_terms if term in gold_text}
    if any(any(character.isdigit() for character in term) for term in shared):
        return "number_or_identifier"
    if any(len(term) >= 12 for term in shared):
        return "scientific_terminology"
    query_tokens = tokenize(query)
    gold_tokens = set(tokenize(gold_text))
    if any(f"{left} {right}" in gold_text for left, right in zip(query_tokens, query_tokens[1:])):
        return "phrase_overlap"
    if len(shared) >= 2:
        return "exact_lexical_overlap"
    if shared:
        return "entity_or_name"
    return "other"


def mean(values: list[float]) -> float | None:
    return sum(values) / len(values) if values else None


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-root", type=Path, default=DEFAULT_DATASET_ROOT)
    parser.add_argument("--baseline-run", type=Path, default=DEFAULT_BASELINE_RUN)
    parser.add_argument("--top-n", type=int, default=30)
    parser.add_argument("--k1", type=float, default=1.2)
    parser.add_argument("--b", type=float, default=0.75)
    parser.add_argument("--output-dir", type=Path)
    args = parser.parse_args()
    if args.top_n <= 0:
        parser.error("--top-n must be positive")
    if args.k1 <= 0 or not 0 <= args.b <= 1:
        parser.error("BM25 requires k1 > 0 and b in [0, 1]")
    return args


def main() -> None:
    args = parse_args()
    dataset_root = args.dataset_root.resolve()
    baseline_run = args.baseline_run.resolve()
    manifest = read_jsonl(dataset_root / "manifest" / "documents.jsonl")
    questions = read_jsonl(dataset_root / "qa" / "test.jsonl")
    baseline_cases = read_jsonl(baseline_run / "cases.jsonl")
    document_map = json.loads((baseline_run / "document-map.json").read_text(encoding="utf-8"))
    if len(questions) != len(baseline_cases):
        raise ValueError(f"question/baseline count mismatch: {len(questions)} != {len(baseline_cases)}")

    documents = []
    for row in manifest:
        raw_path = dataset_root / row["rawFile"]
        documents.append({"documentKey": row["documentKey"], "content": raw_path.read_text(encoding="utf-8")})
    documents_by_key = {document["documentKey"]: document for document in documents}
    reverse_document_map = {entry["documentCode"]: key for key, entry in document_map.items()}
    cases_by_id = {case["id"]: case for case in baseline_cases}
    if len(cases_by_id) != len(baseline_cases):
        raise ValueError("baseline cases contain duplicate ids")

    index = Bm25Index(documents, k1=args.k1, b=args.b)
    all_rows: list[dict] = []
    dense_miss_rows: list[dict] = []
    matrix: Counter[str] = Counter()
    bm25_recalls: list[float] = []
    dense_recalls: list[float] = []
    dense_candidate_miss_count = 0
    sparse_rescue_count = 0

    for question in questions:
        baseline = cases_by_id.get(question["id"])
        if baseline is None:
            raise ValueError(f"baseline has no case for {question['id']}")
        gold_keys = {evidence["documentKey"] for evidence in question["evidences"]}
        ranked = index.top_n(question["question"], args.top_n)
        ranked_keys = [documents[document_index]["documentKey"] for document_index, _ in ranked]
        bm25_gold_keys = set(ranked_keys) & gold_keys
        dense_gold_keys = dense_candidate_keys(baseline, reverse_document_map) & gold_keys
        bm25_hit = bool(bm25_gold_keys)
        dense_hit = bool(dense_gold_keys)
        bm25_recalls.append(len(bm25_gold_keys) / len(gold_keys))
        dense_recalls.append(len(dense_gold_keys) / len(gold_keys))
        matrix[f"dense_{'hit' if dense_hit else 'miss'}__bm25_{'hit' if bm25_hit else 'miss'}"] += 1
        row = {
            "queryId": question["id"],
            "query": question["question"],
            "relevantDocumentIds": sorted(gold_keys),
            "denseCandidateHitAtN": dense_hit,
            "bm25CandidateHitAtN": bm25_hit,
            "bm25GoldBestRank": best_rank(ranked_keys, gold_keys),
            "bm25CandidateRecallAtN": len(set(ranked_keys) & gold_keys) / len(gold_keys),
            "bm25TopDocumentIds": ranked_keys,
        }
        all_rows.append(row)
        if not dense_hit:
            dense_candidate_miss_count += 1
            if bm25_hit:
                sparse_rescue_count += 1
                row["rescueReasonHeuristic"] = rescue_reason(
                    question["question"], [documents_by_key[key] for key in gold_keys]
                )
            else:
                row["rescueReasonHeuristic"] = None
            dense_miss_rows.append(row)

    if dense_candidate_miss_count != 33:
        raise ValueError(
            "C1 Dense candidate miss count changed: "
            f"expected 33, observed {dense_candidate_miss_count}; provide a matching baseline run explicitly."
        )
    if args.output_dir is None:
        timestamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        args.output_dir = PROJECT_ROOT / "rag-eval" / "runs" / f"general-scifact-full-v1-bm25-rescue-{timestamp}"
    output_dir = args.output_dir.resolve()
    if output_dir.exists() and any(output_dir.iterdir()):
        raise FileExistsError(f"output directory is not empty: {output_dir}")
    output_dir.mkdir(parents=True, exist_ok=True)

    summary = {
        "experiment": "scifact-bm25-rescue-diagnostic-v1",
        "baselineRun": str(baseline_run),
        "datasetRoot": str(dataset_root),
        "corpusDocumentCount": len(documents),
        "queryCount": len(questions),
        "topN": args.top_n,
        "bm25": {"k1": args.k1, "b": args.b, "tokenizer": TOKEN_PATTERN.pattern, "stopWordCount": len(STOP_WORDS)},
        "bm25CandidateHitRateAtN": (matrix["dense_hit__bm25_hit"] + matrix["dense_miss__bm25_hit"]) / len(questions),
        "bm25CandidateRecallAtN": mean(bm25_recalls),
        "denseCandidateHitRateAtN": (matrix["dense_hit__bm25_hit"] + matrix["dense_hit__bm25_miss"]) / len(questions),
        "denseCandidateRecallAtN": mean(dense_recalls),
        "denseOrBm25CandidateHitRateAtN": (
            matrix["dense_hit__bm25_hit"] + matrix["dense_hit__bm25_miss"] + matrix["dense_miss__bm25_hit"]
        ) / len(questions),
        "denseCandidateMissCount": dense_candidate_miss_count,
        "denseMissBm25HitCount": sparse_rescue_count,
        "denseMissBm25MissCount": dense_candidate_miss_count - sparse_rescue_count,
        "sparseRescueRate": sparse_rescue_count / dense_candidate_miss_count,
        "contingency": {
            "denseHitBm25Hit": matrix["dense_hit__bm25_hit"],
            "denseHitBm25Miss": matrix["dense_hit__bm25_miss"],
            "denseMissBm25Hit": matrix["dense_miss__bm25_hit"],
            "denseMissBm25Miss": matrix["dense_miss__bm25_miss"],
        },
        "scope": "offline diagnostic only; no rerank, RRF, production retrieval, corpus, query or qrels changes",
    }
    write_json(output_dir / "summary.json", summary)
    write_jsonl(output_dir / "cases.jsonl", all_rows)
    write_jsonl(output_dir / "dense-candidate-miss-attribution.jsonl", dense_miss_rows)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"wrote {output_dir}")


if __name__ == "__main__":
    main()
