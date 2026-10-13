#!/usr/bin/env python3
"""Compare raw-chunk R0 and structural-context R1 reranking on frozen C2 RRF candidates.

This is an evaluation-only paired experiment.  It reuses the C2 run's saved
RRF Top30 identities and never invokes Dense Retrieval, BM25, or RRF again.
R0 submits only each authoritative Chunk body to the existing reranker; R1
submits the same body prefixed with its authoritative title and heading path.
"""
from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
import time
from collections import Counter
from datetime import datetime, timezone
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
    parser.add_argument("--base-url", default=os.getenv("HEX_WORKSPACE_BASE_URL", "http://127.0.0.1:8098"))
    parser.add_argument("--workspace-id", type=int, default=int(os.getenv("HEX_WORKSPACE_WORKSPACE_ID", "0")))
    parser.add_argument("--evaluation-token", default=os.getenv("HEXWORKSPACE_RAG_EVALUATION_TOKEN", ""))
    # 本实验只调用 service-token protected evaluation endpoint；保留该字段以复用 WorkspaceApi。
    parser.add_argument("--user-token", default=os.getenv("HEX_WORKSPACE_TOKEN", ""))
    parser.add_argument("--request-timeout", type=float, default=120.0)
    parser.add_argument("--http-retries", type=int, default=5)
    parser.add_argument("--resume-run", type=Path)
    parser.add_argument("--run-id", default=datetime.now(timezone.utc).strftime("general-scifact-full-v1-c2-rerank-r1-%Y%m%dT%H%M%SZ"))
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


def document_keys(candidates: list[dict], reverse_document_map: dict[str, str]) -> list[str]:
    result: list[str] = []
    for candidate in candidates:
        key = reverse_document_map.get(candidate["documentCode"])
        if key is not None and key not in result:
            result.append(key)
    return result


def best_gold_rank(candidates: list[dict], gold: set[str], reverse_document_map: dict[str, str]) -> int | None:
    for rank, document_key in enumerate(document_keys(candidates, reverse_document_map), start=1):
        if document_key in gold:
            return rank
    return None


def metrics(cases: list[dict], variant: str, top_k: int) -> dict:
    ranks = [case[f"{variant}GoldBestRank"] for case in cases]
    return {
        f"hitRateAt{top_k}": statistics.mean(rank is not None and rank <= top_k for rank in ranks),
        f"recallAt{top_k}": statistics.mean(case[f"{variant}RecallAtK"] for case in cases),
        f"mrrAt{top_k}": statistics.mean(case[f"{variant}ReciprocalRankAtK"] for case in cases),
    }


def rrf_metrics(cases: list[dict], variant: str, top_k: int) -> dict:
    return {
        "rrfHitFinalTop5Miss": sum(
            case["rrfGoldBestRank"] is not None
            and (case[f"{variant}GoldBestRank"] is None or case[f"{variant}GoldBestRank"] > top_k)
            for case in cases),
        "rrfTop5RerankTop5Miss": sum(
            case["rrfGoldBestRank"] is not None and case["rrfGoldBestRank"] <= top_k
            and (case[f"{variant}GoldBestRank"] is None or case[f"{variant}GoldBestRank"] > top_k)
            for case in cases),
        "rrfOutsideTop5RerankTop5Rescue": sum(
            case["rrfGoldBestRank"] is not None and case["rrfGoldBestRank"] > top_k
            and case[f"{variant}GoldBestRank"] is not None and case[f"{variant}GoldBestRank"] <= top_k
            for case in cases),
    }


def transition(r0_rank: int | None, r1_rank: int | None) -> str:
    if r0_rank == r1_rank:
        return "UNCHANGED"
    if r0_rank is None:
        return "R1_RECOVERS_R0_MISS" if r1_rank is not None else "UNCHANGED"
    if r1_rank is None:
        return "R1_LOSES_R0_HIT"
    return "IMPROVED" if r1_rank < r0_rank else "WORSE"


def main() -> None:
    args = parse_args()
    c2_run = args.c2_run.resolve()
    source_summary = json.loads((c2_run / "summary.json").read_text(encoding="utf-8"))
    source_cases = read_jsonl(c2_run / "cases.jsonl")
    if len(source_cases) != 300:
        raise ValueError("R1 requires a complete 300-query frozen C2 cases.jsonl")
    if any(len(case.get("rrfCandidates", [])) != 30 for case in source_cases):
        raise ValueError("R1 requires frozen RRF Top30 candidates for every query")
    baseline_run = Path(source_summary["baselineRun"])
    document_map = json.loads((baseline_run / "document-map.json").read_text(encoding="utf-8"))
    reverse_document_map = {row["documentCode"]: key for key, row in document_map.items()}
    if args.resume_run:
        output_dir = args.resume_run.resolve()
        if not output_dir.is_dir():
            raise FileNotFoundError(f"resume run directory does not exist: {output_dir}")
    else:
        output_dir = PROJECT_ROOT / "rag-eval" / "runs" / args.run_id
        if output_dir.exists() and any(output_dir.iterdir()):
            raise FileExistsError(f"run directory is not empty: {output_dir}")
        output_dir.mkdir(parents=True, exist_ok=True)
    config = {
        "experiment": "general-scifact-full-v1-c2-frozen-candidates-r0-r1",
        "sourceC2Run": str(c2_run),
        "candidateGeneration": "frozen C2 RRF Top30 identities; no Dense/BM25/RRF call",
        "reranker": "same current configured server-side reranker",
        "R0": "RAW_CHUNK",
        "R1": "STRUCTURAL_CONTEXT",
        "topK": 5,
        "metricContract": "document-level: full rerank order -> unique documents -> TopK",
    }
    config_path = output_dir / "run-config.json"
    if config_path.exists() and json.loads(config_path.read_text(encoding="utf-8")) != config:
        raise ValueError("resume configuration differs from the R0/R1 experiment")
    write_json(config_path, config)
    cases_path = output_dir / "cases.jsonl"
    completed = read_jsonl(cases_path) if cases_path.exists() else []
    completed_ids = {case["id"] for case in completed}
    if len(completed_ids) != len(completed) or not completed_ids.issubset({case["id"] for case in source_cases}):
        raise ValueError("invalid R0/R1 checkpoint")
    api = WorkspaceApi(args)
    for index, source_case in enumerate(source_cases, start=1):
        if source_case["id"] in completed_ids:
            continue
        query = source_case["question"]
        candidates = source_case["rrfCandidates"]
        started = time.perf_counter()
        r0_started = time.perf_counter()
        r0 = api.rerank_candidates(args.workspace_id, query, candidates, len(candidates), "RAW_CHUNK")
        r0_ms = round((time.perf_counter() - r0_started) * 1000, 2)
        r1_started = time.perf_counter()
        r1 = api.rerank_candidates(args.workspace_id, query, candidates, len(candidates), "STRUCTURAL_CONTEXT")
        r1_ms = round((time.perf_counter() - r1_started) * 1000, 2)
        gold = set(source_case["goldenDocumentKeys"])
        rrf_rank = source_case["goldBestRank"]["rrf"]
        r0_rank = best_gold_rank(r0, gold, reverse_document_map)
        r1_rank = best_gold_rank(r1, gold, reverse_document_map)
        r0_documents = document_keys(r0, reverse_document_map)[:5]
        r1_documents = document_keys(r1, reverse_document_map)[:5]
        row = {
            "id": source_case["id"], "question": query, "goldenDocumentKeys": sorted(gold),
            "rrfGoldBestRank": rrf_rank,
            "r0GoldBestRank": r0_rank, "r1GoldBestRank": r1_rank,
            "r0RecallAtK": len(set(r0_documents) & gold) / len(gold),
            "r1RecallAtK": len(set(r1_documents) & gold) / len(gold),
            "r0ReciprocalRankAtK": 1 / r0_rank if r0_rank is not None and r0_rank <= 5 else 0,
            "r1ReciprocalRankAtK": 1 / r1_rank if r1_rank is not None and r1_rank <= 5 else 0,
            "r0Candidates": r0, "r1Candidates": r1,
            "r0RerankLatencyMs": r0_ms, "r1RerankLatencyMs": r1_ms,
            "pairedTotalReplayLatencyMs": round((time.perf_counter() - started) * 1000, 2),
            "r0ToR1Transition": transition(r0_rank, r1_rank),
        }
        append_jsonl(cases_path, row)
        completed.append(row)
        completed_ids.add(row["id"])
        if index % 10 == 0 or index == len(source_cases):
            print(f"evaluated {index}/{len(source_cases)}")

    top_k = config["topK"]
    original_attribution_path = c2_run / "rerank-attribution" / "rerank-failure-cases.jsonl"
    original_categories = {row["queryId"]: row["classification"] for row in read_jsonl(original_attribution_path)} \
        if original_attribution_path.is_file() else {}
    category_transitions = [
        {"queryId": case["id"], "originalCategory": original_categories[case["id"]],
         "rrfGoldBestRank": case["rrfGoldBestRank"], "r0GoldBestRank": case["r0GoldBestRank"],
         "r1GoldBestRank": case["r1GoldBestRank"], "transition": case["r0ToR1Transition"]}
        for case in completed if case["id"] in original_categories
        and original_categories[case["id"]] != "REPLAY_DIVERGED"
    ]
    r0_latency = [case["r0RerankLatencyMs"] for case in completed]
    r1_latency = [case["r1RerankLatencyMs"] for case in completed]
    summary = {
        **config, "queryCount": len(completed),
        "r0": {**metrics(completed, "r0", top_k), **rrf_metrics(completed, "r0", top_k),
               "rerankLatencyMs": {"p50": percentile(r0_latency, 50), "p95": percentile(r0_latency, 95)}},
        "r1": {**metrics(completed, "r1", top_k), **rrf_metrics(completed, "r1", top_k),
               "rerankLatencyMs": {"p50": percentile(r1_latency, 50), "p95": percentile(r1_latency, 95)}},
        "transitionCounts": dict(sorted(Counter(case["r0ToR1Transition"] for case in completed).items())),
        "totalLatency": {
            "comparable": False,
            "reason": "R0/R1 replays reuse frozen RRF candidates; they do not execute Dense, BM25, or RRF. "
                      "Only rerank request latency is directly comparable.",
        },
    }
    write_json(output_dir / "summary.json", summary)
    write_json(output_dir / "original-category-r0-r1-transitions.json", category_transitions)
    report = ["# SciFact C2 Frozen Candidate R0/R1 Rerank Experiment", "",
              "- Candidate set: frozen C2 RRF Top30; Dense, BM25 and RRF were not rerun.",
              "- R0: raw authoritative Chunk text only.",
              "- R1: authoritative document title + heading path + Chunk text.",
              "- Total pipeline latency is intentionally not reported as comparable; this run measures paired rerank replay latency only.", "",
              "| Metric | R0 | R1 |", "| --- | ---: | ---: |"]
    for metric in (f"hitRateAt{top_k}", f"recallAt{top_k}", f"mrrAt{top_k}",
                   "rrfHitFinalTop5Miss", "rrfTop5RerankTop5Miss", "rrfOutsideTop5RerankTop5Rescue"):
        report.append(f"| {metric} | {summary['r0'][metric]} | {summary['r1'][metric]} |")
    report.extend(["", "## Paired rerank latency", "", f"- R0 P50/P95: {summary['r0']['rerankLatencyMs']['p50']} / {summary['r0']['rerankLatencyMs']['p95']} ms",
                   f"- R1 P50/P95: {summary['r1']['rerankLatencyMs']['p50']} / {summary['r1']['rerankLatencyMs']['p95']} ms",
                   "", "## R0 to R1 transitions", ""])
    for name, count in summary["transitionCounts"].items():
        report.append(f"- {name}: {count}")
    (output_dir / "report.md").write_text("\n".join(report) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
