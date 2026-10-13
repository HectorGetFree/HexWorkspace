#!/usr/bin/env python3
"""Run the SciFact C2 Dense+BM25 RRF candidate experiment.

The experiment leaves production retrieval untouched.  It obtains a fresh Dense
chunk Top-N diagnostic snapshot from the existing evaluation endpoint, retrieves
the same Active Generation chunks with offline BM25, fuses the two lists with
RRF, then asks the evaluation-only endpoint to use the currently configured
server-side reranker on those verified chunk identities.
"""
from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
import time
from datetime import datetime, timezone
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parents[1]
sys.path.insert(0, str(SCRIPT_DIR))

from run_retrieval_v2 import ApiError, WorkspaceApi  # noqa: E402
from run_scifact_bm25_rescue import Bm25Index, read_jsonl  # noqa: E402


DEFAULT_DATASET_ROOT = PROJECT_ROOT / "rag-eval" / "cache" / "general-scifact-full-v1"
DEFAULT_BASELINE_RUN = PROJECT_ROOT / "rag-eval" / "runs" / "general-scifact-full-20261004T075524Z"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.getenv("HEX_WORKSPACE_BASE_URL", "http://127.0.0.1:8098"))
    parser.add_argument("--workspace-id", type=int, default=int(os.getenv("HEX_WORKSPACE_WORKSPACE_ID", "0")))
    parser.add_argument("--evaluation-token", default=os.getenv("HEXWORKSPACE_RAG_EVALUATION_TOKEN", ""))
    # C2 只调用 service-token protected evaluation endpoints，不需要用户 JWT。
    # 仍保留这个字段以复用 WorkspaceApi 的统一参数契约。
    parser.add_argument("--user-token", default=os.getenv("HEX_WORKSPACE_TOKEN", ""))
    parser.add_argument("--dataset-root", type=Path, default=DEFAULT_DATASET_ROOT)
    parser.add_argument("--baseline-run", type=Path, default=DEFAULT_BASELINE_RUN)
    parser.add_argument("--candidate-limit", type=int, default=30)
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--rrf-k", type=int, default=60)
    parser.add_argument("--rerank-input-variant", choices=("RAW_CHUNK", "STRUCTURAL_CONTEXT"),
                        default="STRUCTURAL_CONTEXT",
                        help="评测 endpoint 从权威 Chunk Artifact 组装 rerank 文本的形态")
    parser.add_argument("--chunk-batch-size", type=int, default=25)
    parser.add_argument("--request-timeout", type=float, default=120.0)
    parser.add_argument("--http-retries", type=int, default=2)
    parser.add_argument("--active-chunks-cache", type=Path,
                        help="reuse a prior C2 active-chunks.jsonl snapshot only when Generation identities match")
    parser.add_argument("--resume-run", type=Path,
                        help="resume an interrupted C2 run directory; completed cases.jsonl rows are not executed again")
    parser.add_argument("--run-id", default=datetime.now(timezone.utc).strftime("general-scifact-full-v1-c2-%Y%m%dT%H%M%SZ"))
    args = parser.parse_args()
    if args.workspace_id <= 0 or not args.evaluation_token:
        parser.error("请提供 --workspace-id / --evaluation-token")
    if not 2 <= args.candidate_limit <= 100 or not 1 <= args.top_k <= 20:
        parser.error("candidate-limit 必须为 2..100，top-k 必须为 1..20")
    if args.rrf_k <= 0 or not 1 <= args.chunk_batch_size <= 50:
        parser.error("rrf-k 必须为正数，chunk-batch-size 必须为 1..50")
    return args


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")


def append_jsonl_checkpoint(path: Path, row: dict) -> None:
    """Durably expose each completed query so a later retry can skip it."""
    with path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(row, ensure_ascii=False) + "\n")
        handle.flush()
        os.fsync(handle.fileno())


def load_completed_cases(path: Path, known_question_ids: set[str]) -> list[dict]:
    if not path.exists():
        return []
    completed = read_jsonl(path)
    completed_ids = [row.get("id") for row in completed]
    if any(not question_id for question_id in completed_ids):
        raise ValueError(f"invalid checkpoint row in {path}")
    if len(completed_ids) != len(set(completed_ids)):
        raise ValueError(f"duplicate query checkpoint in {path}")
    if any("goldBestRank" not in row for row in completed):
        raise ValueError(f"checkpoint predates stage-rank attribution and cannot be resumed: {path}")
    unknown_ids = set(completed_ids) - known_question_ids
    if unknown_ids:
        raise ValueError(f"checkpoint has unknown question ids: {sorted(unknown_ids)[:5]}")
    return completed


def chunk_key(candidate: dict) -> str:
    return f"{candidate['documentCode']}:{candidate['generationId']}:{candidate['chunkNo']}"


def validate_chunk_candidate(candidate: dict, source: str) -> dict:
    required = ("documentCode", "generationId", "chunkNo", "rank", "score")
    missing = [field for field in required if candidate.get(field) is None]
    if missing:
        raise ApiError(
            f"{source} lacks chunk identity {missing}; deploy the C2 evaluation diagnostics before running this experiment"
        )
    return {
        "documentCode": candidate["documentCode"],
        "generationId": candidate["generationId"],
        "chunkNo": int(candidate["chunkNo"]),
        "rank": int(candidate["rank"]),
        "score": float(candidate["score"]),
    }


def rrf_fuse(dense: list[dict], bm25: list[dict], rrf_k: int, limit: int) -> list[dict]:
    """Fuse two same-granularity ranked chunk lists with deterministic RRF ties."""
    values: dict[str, dict] = {}
    for source, candidates in (("dense", dense), ("bm25", bm25)):
        for candidate in candidates:
            key = chunk_key(candidate)
            current = values.setdefault(key, {
                "documentCode": candidate["documentCode"], "generationId": candidate["generationId"],
                "chunkNo": candidate["chunkNo"], "rrfScore": 0.0, "denseRank": None, "bm25Rank": None,
            })
            current["rrfScore"] += 1.0 / (rrf_k + candidate["rank"])
            current[f"{source}Rank"] = candidate["rank"]
    ordered = sorted(values.values(), key=lambda row: (
        -row["rrfScore"], row["denseRank"] or 10**9, row["bm25Rank"] or 10**9,
        row["documentCode"], row["chunkNo"],
    ))[:limit]
    return [{
        "documentCode": row["documentCode"], "generationId": row["generationId"], "chunkNo": row["chunkNo"],
        "rank": index, "score": row["rrfScore"], "denseRank": row["denseRank"], "bm25Rank": row["bm25Rank"],
    } for index, row in enumerate(ordered, start=1)]


def document_keys(candidates: list[dict], reverse_document_map: dict[str, str]) -> list[str]:
    result = []
    for candidate in candidates:
        key = reverse_document_map.get(candidate["documentCode"])
        if key and key not in result:
            result.append(key)
    return result


def ranks(retrieved: list[str], gold: set[str]) -> list[int]:
    return [index for index, key in enumerate(retrieved, start=1) if key in gold]


def best_gold_rank(retrieved: list[str], gold: set[str]) -> int | None:
    """Return the first rank of any gold document under the evaluator's deduplicated-document metric."""
    matched = ranks(retrieved, gold)
    return matched[0] if matched else None


def transition_counts(cases: list[dict]) -> dict[str, int]:
    """Summarize candidate retention and final-ranking loss without changing metric definitions."""
    counts = {
        "denseHitBm25Hit": 0,
        "denseHitBm25Miss": 0,
        "denseMissBm25Hit": 0,
        "denseMissBm25Miss": 0,
        "denseMissBm25HitRrfRetained": 0,
        "denseMissBm25HitRrfRetainedFinalTop5": 0,
        "denseHitRrfMiss": 0,
        "rrfHitFinalTop5Miss": 0,
    }
    for case in cases:
        stages = case["goldBestRank"]
        dense_hit = stages["dense"] is not None
        bm25_hit = stages["bm25"] is not None
        rrf_hit = stages["rrf"] is not None
        final_hit = stages["rerank"] is not None
        counts[f"dense{'Hit' if dense_hit else 'Miss'}Bm25{'Hit' if bm25_hit else 'Miss'}"] += 1
        if not dense_hit and bm25_hit and rrf_hit:
            counts["denseMissBm25HitRrfRetained"] += 1
            if final_hit:
                counts["denseMissBm25HitRrfRetainedFinalTop5"] += 1
        if dense_hit and not rrf_hit:
            counts["denseHitRrfMiss"] += 1
        if rrf_hit and not final_hit:
            counts["rrfHitFinalTop5Miss"] += 1
    return counts


def percentile(values: list[float], percentage: int) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[round((len(ordered) - 1) * percentage / 100)]


def load_active_chunks(api: WorkspaceApi, args: argparse.Namespace, document_codes: list[str], output_dir: Path) -> list[dict]:
    if args.active_chunks_cache:
        return read_jsonl(args.active_chunks_cache)
    chunks = []
    for start in range(0, len(document_codes), args.chunk_batch_size):
        batch = document_codes[start:start + args.chunk_batch_size]
        chunks.extend(api.active_chunks(args.workspace_id, batch))
        print(f"loaded active chunks for {min(start + len(batch), len(document_codes))}/{len(document_codes)} documents")
    write_jsonl(output_dir / "active-chunks.jsonl", chunks)
    return chunks


def main() -> None:
    args = parse_args()
    if args.resume_run:
        output_dir = args.resume_run.resolve()
        if not output_dir.is_dir():
            raise FileNotFoundError(f"resume run directory does not exist: {output_dir}")
        if not args.active_chunks_cache:
            cached_chunks = output_dir / "active-chunks.jsonl"
            if cached_chunks.is_file() and cached_chunks.stat().st_size > 0:
                args.active_chunks_cache = cached_chunks
            else:
                raise FileNotFoundError(
                    "resume run lacks active-chunks.jsonl; provide --active-chunks-cache from the interrupted run"
                )
    else:
        output_dir = PROJECT_ROOT / "rag-eval" / "runs" / args.run_id
        if output_dir.exists() and any(output_dir.iterdir()):
            raise FileExistsError(f"run directory is not empty: {output_dir}")
        output_dir.mkdir(parents=True, exist_ok=True)
    dataset_root = args.dataset_root.resolve()
    baseline_run = args.baseline_run.resolve()
    questions = read_jsonl(dataset_root / "qa" / "test.jsonl")
    document_map = json.loads((baseline_run / "document-map.json").read_text(encoding="utf-8"))
    reverse_document_map = {entry["documentCode"]: key for key, entry in document_map.items()}
    if len(document_map) != 5183 or len(questions) != 300:
        raise ValueError("C2 requires the frozen full SciFact corpus (5,183 documents / 300 test queries)")

    run_config = {
        "datasetRoot": str(dataset_root), "baselineRun": str(baseline_run), "workspaceId": args.workspace_id,
        "candidateLimit": args.candidate_limit, "topK": args.top_k, "rrfK": args.rrf_k,
        "rerankInputVariant": args.rerank_input_variant,
        "metricContract": "document-level: full rerank order -> unique documents -> TopK",
    }
    config_path = output_dir / "run-config.json"
    if config_path.exists():
        previous_config = json.loads(config_path.read_text(encoding="utf-8"))
        if previous_config != run_config:
            raise ValueError("resume configuration differs from the interrupted C2 run")
    else:
        write_json(config_path, run_config)

    api = WorkspaceApi(args)
    active_chunks = load_active_chunks(api, args, list(reverse_document_map), output_dir)
    valid_chunks = [
        {"documentKey": chunk_key(chunk), "content": chunk["content"], "candidate": {
            "documentCode": chunk["documentCode"], "generationId": chunk["generationId"],
            "chunkNo": int(chunk["chunkNo"]),
        }}
        for chunk in active_chunks
        if chunk.get("documentCode") in reverse_document_map and chunk.get("generationId") is not None
        and chunk.get("chunkNo") is not None and chunk.get("content")
    ]
    if not valid_chunks:
        raise ApiError("未读取到当前 Active Generation Chunk，不能运行 C2")
    bm25 = Bm25Index(valid_chunks, k1=1.2, b=0.75)
    cases_path = output_dir / "cases.jsonl"
    cases = load_completed_cases(cases_path, {question["id"] for question in questions})
    completed_ids = {case["id"] for case in cases}
    latencies = [case["latencyMs"] for case in cases]
    if completed_ids:
        print(f"resuming C2: {len(completed_ids)}/{len(questions)} query(s) already complete")
    for index, question in enumerate(questions, start=1):
        if question["id"] in completed_ids:
            continue
        started = time.perf_counter()
        dense_response = api.retrieve_candidates(args.workspace_id, question["question"], args.candidate_limit, args.top_k)
        dense = [validate_chunk_candidate(candidate, "qdrantCandidates")
                 for candidate in dense_response.get("diagnostics", {}).get("qdrantCandidates", [])]
        if len(dense) != args.candidate_limit:
            raise ApiError(f"expected {args.candidate_limit} Dense chunk candidates for {question['id']}, got {len(dense)}")
        bm25_ranked = bm25.top_n(question["question"], args.candidate_limit)
        bm25_candidates = []
        for rank, (chunk_index, score) in enumerate(bm25_ranked, start=1):
            candidate = dict(valid_chunks[chunk_index]["candidate"])
            candidate.update({"rank": rank, "score": score})
            bm25_candidates.append(candidate)
        fused = rrf_fuse(dense, bm25_candidates, args.rrf_k, args.candidate_limit)
        # SciFact qrels 是 document-level。必须先取得完整的 Chunk rerank 顺序，再按 Document
        # 去重截断 TopK；不能先截断 TopK Chunk，否则同一文档的多个 Chunk 会挤占结果槽位。
        reranked = api.rerank_candidates(
            args.workspace_id, question["question"], fused, len(fused), args.rerank_input_variant)
        reranked = [validate_chunk_candidate(candidate, "rerankCandidates") for candidate in reranked]
        gold = {evidence["documentKey"] for evidence in question["evidences"]}
        dense_documents = document_keys(dense, reverse_document_map)
        bm25_documents = document_keys(bm25_candidates, reverse_document_map)
        fused_documents = document_keys(fused, reverse_document_map)
        final_documents = document_keys(reranked, reverse_document_map)[:args.top_k]
        final_ranks = ranks(final_documents, gold)
        gold_best_rank = {
            "dense": best_gold_rank(dense_documents, gold),
            "bm25": best_gold_rank(bm25_documents, gold),
            "rrf": best_gold_rank(fused_documents, gold),
            "rerank": best_gold_rank(final_documents, gold),
        }
        elapsed = round((time.perf_counter() - started) * 1000, 2)
        latencies.append(elapsed)
        case = {
            "id": question["id"], "question": question["question"], "goldenDocumentKeys": sorted(gold),
            "denseCandidateDocumentIds": dense_documents, "bm25CandidateDocumentIds": bm25_documents,
            "rrfCandidateDocumentIds": fused_documents, "retrievedDocumentKeys": final_documents,
            "goldenRanks": final_ranks, "hitAtK": bool(final_ranks),
            "goldBestRank": gold_best_rank,
            "recallAtK": len(set(final_documents) & gold) / len(gold),
            "reciprocalRankAtK": 1 / final_ranks[0] if final_ranks else 0,
            "denseCandidateHitAtN": bool(set(dense_documents) & gold),
            "bm25CandidateHitAtN": bool(set(bm25_documents) & gold),
            "rrfCandidateHitAtN": bool(set(fused_documents) & gold),
            "denseCandidates": dense, "bm25Candidates": bm25_candidates,
            "rrfCandidates": fused, "rerankedCandidates": reranked, "finalDocumentKeys": final_documents,
            "latencyMs": elapsed,
            "rerankInputVariant": args.rerank_input_variant,
        }
        append_jsonl_checkpoint(cases_path, case)
        cases.append(case)
        completed_ids.add(question["id"])
        if index % 10 == 0 or index == len(questions):
            print(f"evaluated {index}/{len(questions)}")
    summary = {
        "experiment": "general-scifact-full-v1-c2-dense-bm25-rrf-rerank",
        "baselineRun": str(baseline_run), "datasetRoot": str(dataset_root),
        "corpusDocumentCount": len(document_map), "activeChunkCount": len(valid_chunks), "queryCount": len(cases),
        "candidateLimit": args.candidate_limit, "topK": args.top_k,
        "bm25": {"k1": 1.2, "b": 0.75}, "rrf": {"k": args.rrf_k},
        "rerankInputVariant": args.rerank_input_variant,
        "denseCandidateHitRateAtN": statistics.mean(case["denseCandidateHitAtN"] for case in cases),
        "bm25CandidateHitRateAtN": statistics.mean(case["bm25CandidateHitAtN"] for case in cases),
        "rrfCandidateHitRateAtN": statistics.mean(case["rrfCandidateHitAtN"] for case in cases),
        f"hitRateAt{args.top_k}": statistics.mean(case["hitAtK"] for case in cases),
        f"recallAt{args.top_k}": statistics.mean(case["recallAtK"] for case in cases),
        f"mrrAt{args.top_k}": statistics.mean(case["reciprocalRankAtK"] for case in cases),
        "latencyMs": {"p50": percentile(latencies, 50), "p95": percentile(latencies, 95), "p99": percentile(latencies, 99)},
        "transitionCounts": transition_counts(cases),
        "scope": "evaluation-only: Dense chunk TopN + offline BM25 chunk TopN -> RRF -> "
                 "same WS reranker with the recorded authoritative input variant -> "
                 "deduplicate by document -> TopK documents",
    }
    write_json(output_dir / "summary.json", summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
