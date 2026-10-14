#!/usr/bin/env python3
"""Attribute SciFact C2 cases where an RRF Gold candidate misses final Top5.

The C2 artifact retains RRF inputs but only persisted final Top5 rerank output.
This evaluator-only diagnostic replays the same RRF candidate identities for the
affected cases with a full candidate-limit rerank response. It never changes
qrels, BM25, RRF, production retrieval, or the configured reranker.
"""
from __future__ import annotations

import argparse
import csv
import json
import os
import sys
from collections import Counter
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from run_retrieval_v2 import ApiError, WorkspaceApi  # noqa: E402
from run_scifact_bm25_rescue import read_jsonl  # noqa: E402


DEFAULT_C2_RUN = PROJECT_ROOT / "rag-eval" / "runs" / "general-scifact-full-v1-c2-single-rerank-attribution-20261005T062940Z"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--c2-run", type=Path, default=DEFAULT_C2_RUN)
    parser.add_argument("--active-chunks-cache", type=Path,
                        help="defaults to the C2 run's active-chunks.jsonl; provide the reused cache when absent")
    parser.add_argument("--base-url", default=os.getenv("HEX_WORKSPACE_BASE_URL", "http://127.0.0.1:8098"))
    parser.add_argument("--workspace-id", type=int, default=int(os.getenv("HEX_WORKSPACE_WORKSPACE_ID", "0")))
    parser.add_argument("--evaluation-token", default=os.getenv("HEXWORKSPACE_RAG_EVALUATION_TOKEN", ""))
    parser.add_argument("--request-timeout", type=float, default=120.0)
    parser.add_argument("--http-retries", type=int, default=5)
    parser.add_argument("--output-dir", type=Path)
    args = parser.parse_args()
    return args


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")


def chunk_key(candidate: dict) -> str:
    return f"{candidate['documentCode']}:{candidate['generationId']}:{candidate['chunkNo']}"


def document_rank(candidates: list[dict], gold_document_codes: set[str]) -> int | None:
    seen: set[str] = set()
    for candidate in candidates:
        code = candidate["documentCode"]
        if code in seen:
            continue
        seen.add(code)
        if code in gold_document_codes:
            return len(seen)
    return None


def candidate_details(candidate: dict, chunks: dict[str, dict], titles: dict[str, str], reverse_map: dict[str, str]) -> dict:
    chunk = chunks.get(chunk_key(candidate), {})
    document_key = reverse_map.get(candidate["documentCode"])
    return {
        "documentKey": document_key,
        "sourceDocumentId": document_key.split(":", 1)[1] if document_key and ":" in document_key else None,
        "documentCode": candidate["documentCode"],
        "generationId": candidate["generationId"],
        "chunkNo": candidate["chunkNo"],
        "rank": candidate["rank"],
        "score": candidate["score"],
        "title": titles.get(document_key),
        "headingPath": chunk.get("headingPath"),
        "content": chunk.get("content"),
    }


def classify(rrf_rank: int | None, rerank_rank: int | None, original_final_hit: bool) -> str:
    if rrf_rank is None or rerank_rank is None:
        return "UNCLASSIFIED"
    if not original_final_hit and rerank_rank <= 5:
        # 服务端 Rerank 可能有非确定性；重放结果不能伪装成原事件的排序结果。
        return "REPLAY_DIVERGED"
    if rrf_rank <= 5 and rerank_rank > 5:
        return "RERANK_REGRESSION"
    if rrf_rank > 5 and rerank_rank < rrf_rank and rerank_rank > 5:
        return "IMPROVED_BUT_INSUFFICIENT"
    if rrf_rank > 5 and rerank_rank >= rrf_rank:
        return "UNCHANGED_OR_WORSE"
    return "UNCLASSIFIED"


def main() -> None:
    args = parse_args()
    c2_run = args.c2_run.resolve()
    summary = json.loads((c2_run / "summary.json").read_text(encoding="utf-8"))
    cases = read_jsonl(c2_run / "cases.jsonl")
    baseline_run = Path(summary["baselineRun"])
    document_map = json.loads((baseline_run / "document-map.json").read_text(encoding="utf-8"))
    reverse_map = {value["documentCode"]: key for key, value in document_map.items()}
    dataset_root = Path(summary["datasetRoot"])
    titles = {row["documentKey"]: row["title"]
              for row in read_jsonl(dataset_root / "manifest" / "documents.jsonl")}
    active_cache = args.active_chunks_cache or c2_run / "active-chunks.jsonl"
    if not active_cache.is_file():
        raise FileNotFoundError("请通过 --active-chunks-cache 指定 C2 使用的 Active Chunk cache")
    chunks = {chunk_key(row): row for row in read_jsonl(active_cache)}
    output_dir = (args.output_dir or c2_run / "rerank-attribution").resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    records_path = output_dir / "rerank-failure-cases.jsonl"

    failures = [case for case in cases
                if case.get("goldBestRank", {}).get("rrf") is not None
                and case.get("goldBestRank", {}).get("rerank") is None]
    if records_path.is_file():
        records = read_jsonl(records_path)
        expected_ids = {case["id"] for case in failures}
        cached_ids = {record["queryId"] for record in records}
        if len(records) != len(failures) or cached_ids != expected_ids:
            raise ValueError("已缓存的 attribution JSONL 与当前 C2 failure 集不一致；请删除后重新执行完整 replay")
        print(f"reusing completed rerank attribution: {len(records)} cases from {records_path}")
    else:
        if args.workspace_id <= 0 or not args.evaluation_token:
            raise ValueError("首次 replay 请提供 --workspace-id / --evaluation-token")
        api_args = argparse.Namespace(base_url=args.base_url, evaluation_token=args.evaluation_token, user_token="",
                                      request_timeout=args.request_timeout, http_retries=args.http_retries)
        api = WorkspaceApi(api_args)
        records: list[dict] = []
        for index, case in enumerate(failures, start=1):
            replay = api.rerank_candidates(args.workspace_id, case["question"], case["rrfCandidates"],
                                           len(case["rrfCandidates"]))
            if len(replay) != len(case["rrfCandidates"]):
                raise ApiError(f"rerank replay candidate count mismatch for {case['id']}")
            gold_keys = set(case["goldenDocumentKeys"])
            gold_codes = {document_map[key]["documentCode"] for key in gold_keys}
            rrf_rank = document_rank(case["rrfCandidates"], gold_codes)
            rerank_rank = document_rank(replay, gold_codes)
            rerank_gold = [item for item in replay if item["documentCode"] in gold_codes]
            original_final = case["rerankedCandidates"]
            hybrid_rescue = (case["goldBestRank"]["dense"] is None
                             and case["goldBestRank"]["bm25"] is not None
                             and rrf_rank is not None)
            record = {
                "queryId": case["id"],
                "query": case["question"],
                "relevantDocumentIds": sorted(key.split(":", 1)[1] for key in gold_keys),
                "relevantDocumentKeys": sorted(gold_keys),
                "rrfGoldBestRank": rrf_rank,
                "rerankGoldBestRank": rerank_rank,
                "rankDelta": None if rrf_rank is None or rerank_rank is None else rrf_rank - rerank_rank,
                "goldRerankScore": rerank_gold[0]["score"] if rerank_gold else None,
                "classification": classify(rrf_rank, rerank_rank, bool(case["goldBestRank"]["rerank"])),
                "hybridRescue": hybrid_rescue,
                "goldCandidateChunks": [candidate_details(item, chunks, titles, reverse_map) for item in rerank_gold],
                "finalTop5": [candidate_details(item, chunks, titles, reverse_map) for item in replay[:5]],
                "originalFinalTop5": [candidate_details(item, chunks, titles, reverse_map) for item in original_final],
            }
            records.append(record)
            print(f"attributed {index}/{len(failures)}: {case['id']} -> {record['classification']}")
        write_jsonl(records_path, records)

    category_counts = Counter(record["classification"] for record in records)
    all_cases = cases
    rrf_outside_final_rescue = sum(
        case["goldBestRank"]["rrf"] is not None and case["goldBestRank"]["rrf"] > 5
        and case["goldBestRank"]["rerank"] is not None
        for case in all_cases
    )
    hybrid_lost = [record for record in records if record["hybridRescue"]]
    report = {
        "sourceRun": str(c2_run),
        "rerankReplay": "replayed only the saved RRF candidate identities with full output; replay divergence is reported explicitly",
        "rrfHitFinalTop5MissCount": len(records),
        "rerankRegressionCount": category_counts["RERANK_REGRESSION"],
        "improvedButInsufficientCount": category_counts["IMPROVED_BUT_INSUFFICIENT"],
        "unchangedOrWorseCount": category_counts["UNCHANGED_OR_WORSE"],
        "unclassifiedCount": category_counts["UNCLASSIFIED"] + category_counts["REPLAY_DIVERGED"],
        "replayDivergedCount": category_counts["REPLAY_DIVERGED"],
        "rrfOutsideTop5RerankTop5RescueCount": rrf_outside_final_rescue,
        "hybridRescueRerankMissCount": len(hybrid_lost),
        "classificationCounts": dict(sorted(category_counts.items())),
    }
    write_jsonl(output_dir / "hybrid-rescue-rerank-miss.jsonl", hybrid_lost)
    with (output_dir / "rerank-failure-cases.csv").open("w", encoding="utf-8", newline="") as handle:
        columns = ["queryId", "query", "relevantDocumentIds", "rrfGoldBestRank", "rerankGoldBestRank",
                   "rankDelta", "goldRerankScore", "classification", "hybridRescue", "finalTop5"]
        writer = csv.DictWriter(handle, fieldnames=columns)
        writer.writeheader()
        for record in records:
            writer.writerow({
                "queryId": record["queryId"],
                "query": record["query"],
                "relevantDocumentIds": json.dumps(record["relevantDocumentIds"], ensure_ascii=False),
                "rrfGoldBestRank": record["rrfGoldBestRank"],
                "rerankGoldBestRank": record["rerankGoldBestRank"],
                "rankDelta": record["rankDelta"],
                "goldRerankScore": record["goldRerankScore"],
                "classification": record["classification"],
                "hybridRescue": record["hybridRescue"],
                "finalTop5": json.dumps(record["finalTop5"], ensure_ascii=False),
            })
    write_json(output_dir / "summary.json", report)
    markdown = ["# SciFact C2 Rerank Failure Attribution", "", f"- Source run: `{c2_run}`",
                f"- RRF hit → final Top5 miss: **{len(records)}**", "",
                "| Category | Count |", "| --- | ---: |"]
    for category in ("RERANK_REGRESSION", "IMPROVED_BUT_INSUFFICIENT", "UNCHANGED_OR_WORSE", "REPLAY_DIVERGED", "UNCLASSIFIED"):
        markdown.append(f"| {category} | {category_counts[category]} |")
    markdown.extend(["", f"- RRF >5 → final Top5 rescue: **{rrf_outside_final_rescue}**",
                     f"- Hybrid rescue retained by RRF but missed by rerank: **{len(hybrid_lost)}**",
                     "", "`rerank-failure-cases.jsonl` contains full Gold candidate chunks and replay Top5 competitors."])
    (output_dir / "report.md").write_text("\n".join(markdown) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
