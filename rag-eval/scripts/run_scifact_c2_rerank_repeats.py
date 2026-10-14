#!/usr/bin/env python3
"""Run repeated paired R0/R1 rerank trials on frozen C2 RRF Top30 candidates only."""
from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
import time
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from run_retrieval_v2 import WorkspaceApi  # noqa: E402
from run_scifact_bm25_rescue import read_jsonl  # noqa: E402
from run_scifact_c2_rerank_r1 import best_gold_rank, document_keys, metrics, rrf_metrics, transition  # noqa: E402


DEFAULT_C2_RUN = PROJECT_ROOT / "rag-eval" / "runs" / "general-scifact-full-v1-c2-single-rerank-attribution-20261005T062940Z"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--c2-run", type=Path, default=DEFAULT_C2_RUN)
    parser.add_argument("--base-url", default=os.getenv("HEX_WORKSPACE_BASE_URL", "http://127.0.0.1:8098"))
    parser.add_argument("--workspace-id", type=int, default=int(os.getenv("HEX_WORKSPACE_WORKSPACE_ID", "0")))
    parser.add_argument("--evaluation-token", default=os.getenv("HEXWORKSPACE_RAG_EVALUATION_TOKEN", ""))
    parser.add_argument("--user-token", default=os.getenv("HEX_WORKSPACE_TOKEN", ""))
    parser.add_argument("--trials", type=int, default=3, choices=range(3, 6))
    parser.add_argument("--request-timeout", type=float, default=120.0)
    parser.add_argument("--http-retries", type=int, default=5)
    parser.add_argument("--resume-run", type=Path)
    parser.add_argument("--run-id", default=datetime.now(timezone.utc).strftime("general-scifact-full-v1-c2-rerank-repeat-%Y%m%dT%H%M%SZ"))
    args = parser.parse_args()
    if args.workspace_id <= 0 or not args.evaluation_token:
        parser.error("请提供 --workspace-id / --evaluation-token")
    return args


def append_jsonl(path: Path, value: dict) -> None:
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(value, ensure_ascii=False) + "\n")
        handle.flush()
        os.fsync(handle.fileno())


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def percentile(values: list[float], percentage: int) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[round((len(ordered) - 1) * percentage / 100)]


def summarize_trial(rows: list[dict]) -> dict:
    r0_latency = [row["r0RerankLatencyMs"] for row in rows]
    r1_latency = [row["r1RerankLatencyMs"] for row in rows]
    return {
        "queryCount": len(rows),
        "r0": {**metrics(rows, "r0", 5), **rrf_metrics(rows, "r0", 5),
               "rerankLatencyMs": {"p50": percentile(r0_latency, 50), "p95": percentile(r0_latency, 95)}},
        "r1": {**metrics(rows, "r1", 5), **rrf_metrics(rows, "r1", 5),
               "rerankLatencyMs": {"p50": percentile(r1_latency, 50), "p95": percentile(r1_latency, 95)}},
        "transitionCounts": dict(sorted(Counter(row["r0ToR1Transition"] for row in rows).items())),
    }


def main() -> None:
    args = parse_args()
    c2_run = args.c2_run.resolve()
    source_summary = json.loads((c2_run / "summary.json").read_text(encoding="utf-8"))
    source_cases = read_jsonl(c2_run / "cases.jsonl")
    if len(source_cases) != 300 or any(len(case.get("rrfCandidates", [])) != 30 for case in source_cases):
        raise ValueError("requires a complete frozen 300-query C2 run with RRF Top30 candidates")
    document_map = json.loads((Path(source_summary["baselineRun"]) / "document-map.json").read_text(encoding="utf-8"))
    reverse_document_map = {row["documentCode"]: key for key, row in document_map.items()}
    output_dir = args.resume_run.resolve() if args.resume_run else PROJECT_ROOT / "rag-eval" / "runs" / args.run_id
    if args.resume_run and not output_dir.is_dir():
        raise FileNotFoundError(f"resume run directory does not exist: {output_dir}")
    output_dir.mkdir(parents=True, exist_ok=True)
    config = {"experiment": "general-scifact-full-v1-c2-frozen-candidates-r0-r1-repeat",
              "sourceC2Run": str(c2_run), "trials": args.trials, "queryCount": len(source_cases),
              "candidateGeneration": "frozen C2 RRF Top30 identities; no Dense/BM25/RRF call",
              "R0": "RAW_CHUNK", "R1": "STRUCTURAL_CONTEXT", "topK": 5,
              "metricContract": "document-level: full rerank order -> unique documents -> TopK"}
    config_path = output_dir / "run-config.json"
    if config_path.exists() and json.loads(config_path.read_text(encoding="utf-8")) != config:
        raise ValueError("resume configuration differs from the repeated paired rerank experiment")
    write_json(config_path, config)
    rows_path = output_dir / "cases.jsonl"
    rows = read_jsonl(rows_path) if rows_path.exists() else []
    source_by_id = {case["id"]: case for case in source_cases}
    completed = {(row["trial"], row["id"]) for row in rows}
    if len(completed) != len(rows) or any(row["trial"] not in range(1, args.trials + 1) or row["id"] not in source_by_id for row in rows):
        raise ValueError("invalid repeated paired rerank checkpoint")
    api = WorkspaceApi(args)
    for trial in range(1, args.trials + 1):
        for index, source_case in enumerate(source_cases, start=1):
            if (trial, source_case["id"]) in completed:
                continue
            query, candidates = source_case["question"], source_case["rrfCandidates"]
            r0_started = time.perf_counter()
            r0 = api.rerank_candidates(args.workspace_id, query, candidates, 30, "RAW_CHUNK")
            r0_ms = round((time.perf_counter() - r0_started) * 1000, 2)
            r1_started = time.perf_counter()
            r1 = api.rerank_candidates(args.workspace_id, query, candidates, 30, "STRUCTURAL_CONTEXT")
            r1_ms = round((time.perf_counter() - r1_started) * 1000, 2)
            gold = set(source_case["goldenDocumentKeys"])
            r0_rank = best_gold_rank(r0, gold, reverse_document_map)
            r1_rank = best_gold_rank(r1, gold, reverse_document_map)
            r0_documents = document_keys(r0, reverse_document_map)[:5]
            r1_documents = document_keys(r1, reverse_document_map)[:5]
            row = {"trial": trial, "id": source_case["id"], "question": query,
                   "goldenDocumentKeys": sorted(gold), "rrfGoldBestRank": source_case["goldBestRank"]["rrf"],
                   "r0GoldBestRank": r0_rank, "r1GoldBestRank": r1_rank,
                   "r0RecallAtK": len(set(r0_documents) & gold) / len(gold),
                   "r1RecallAtK": len(set(r1_documents) & gold) / len(gold),
                   "r0ReciprocalRankAtK": 1 / r0_rank if r0_rank is not None and r0_rank <= 5 else 0,
                   "r1ReciprocalRankAtK": 1 / r1_rank if r1_rank is not None and r1_rank <= 5 else 0,
                   "r0RerankLatencyMs": r0_ms, "r1RerankLatencyMs": r1_ms,
                   "r0ToR1Transition": transition(r0_rank, r1_rank)}
            append_jsonl(rows_path, row)
            rows.append(row)
            completed.add((trial, row["id"]))
            if index % 25 == 0 or index == len(source_cases):
                print(f"trial {trial}: {index}/{len(source_cases)}")
    per_trial = {str(trial): summarize_trial([row for row in rows if row["trial"] == trial])
                 for trial in range(1, args.trials + 1)}
    per_query = defaultdict(list)
    for row in rows:
        per_query[row["id"]].append(row)
    per_query_stability = [{"queryId": query_id,
                            "r0Ranks": [row["r0GoldBestRank"] for row in sorted(values, key=lambda row: row["trial"])],
                            "r1Ranks": [row["r1GoldBestRank"] for row in sorted(values, key=lambda row: row["trial"])]}
                           for query_id, values in sorted(per_query.items())]
    summary = {**config, "perTrial": per_trial,
               "average": {metric: statistics.mean(item["r1"][metric] - item["r0"][metric]
                                                     for item in per_trial.values())
                           for metric in ("hitRateAt5", "recallAt5", "mrrAt5")},
               "allPairTransitionCounts": dict(sorted(Counter(row["r0ToR1Transition"] for row in rows).items()))}
    write_json(output_dir / "summary.json", summary)
    write_json(output_dir / "per-query-stability.json", per_query_stability)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
