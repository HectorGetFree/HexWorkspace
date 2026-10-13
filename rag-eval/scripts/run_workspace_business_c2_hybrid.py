#!/usr/bin/env python3
"""Evaluate the frozen Workspace Business corpus with Dense + BM25 + RRF + R1 rerank.

SciFact is document-qrels based, whereas Workspace Business is governed by
runtime evidence requirement groups.  This runner therefore shares the C2
candidate pipeline but preserves the Business C1 evidence-group metric contract.
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

from run_rag_eval_v2 import (  # noqa: E402
    active_cases,
    build_summary,
    evidence_requirement_groups,
    gold_runtime_groups,
    read_lines,
    runtime_chunk_key,
)
from run_retrieval_v2 import ApiError, WorkspaceApi  # noqa: E402
from run_scifact_bm25_rescue import Bm25Index, read_jsonl  # noqa: E402
from run_scifact_c2_hybrid import append_jsonl_checkpoint, rrf_fuse  # noqa: E402


DEFAULT_DATASET_ROOT = PROJECT_ROOT / "rag-eval" / "dataset-v2-workspace-business-full-v1"
DEFAULT_C1_RUN = PROJECT_ROOT / "rag-eval" / "runs" / "workspace-business-full-v1-c1-20261005T021525Z"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.getenv("HEX_WORKSPACE_BASE_URL", "http://127.0.0.1:8098"))
    parser.add_argument("--workspace-id", type=int, default=int(os.getenv("HEX_WORKSPACE_WORKSPACE_ID", "0")))
    parser.add_argument("--evaluation-token", default=os.getenv("HEXWORKSPACE_RAG_EVALUATION_TOKEN", ""))
    parser.add_argument("--user-token", default=os.getenv("HEX_WORKSPACE_TOKEN", ""))
    parser.add_argument("--dataset-root", type=Path, default=DEFAULT_DATASET_ROOT)
    parser.add_argument("--c1-run", type=Path, default=DEFAULT_C1_RUN)
    parser.add_argument("--split", choices=("test",), default="test")
    parser.add_argument("--candidate-limit", type=int, default=30)
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--rrf-k", type=int, default=60)
    parser.add_argument("--rerank-input-variant", choices=("RAW_CHUNK", "STRUCTURAL_CONTEXT"),
                        default="STRUCTURAL_CONTEXT")
    parser.add_argument("--chunk-batch-size", type=int, default=25)
    parser.add_argument("--request-timeout", type=float, default=120.0)
    parser.add_argument("--http-retries", type=int, default=5)
    parser.add_argument("--active-chunks-cache", type=Path)
    parser.add_argument("--resume-run", type=Path)
    parser.add_argument("--run-id", default=datetime.now(timezone.utc).strftime("workspace-business-full-v1-c2-%Y%m%dT%H%M%SZ"))
    args = parser.parse_args()
    if args.workspace_id <= 0 or not args.evaluation_token:
        parser.error("请提供 --workspace-id / --evaluation-token")
    if not 2 <= args.candidate_limit <= 100 or not 1 <= args.top_k <= 20 or args.rrf_k <= 0:
        parser.error("candidate-limit 必须为 2..100，top-k 必须为 1..20，rrf-k 必须为正数")
    return args


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def chunk_key(candidate: dict) -> str:
    return f"{candidate['documentCode']}:{candidate['generationId']}:{candidate['chunkNo']}"


def load_active_chunks(api: WorkspaceApi, args: argparse.Namespace, codes: list[str], output_dir: Path) -> list[dict]:
    if args.active_chunks_cache:
        return read_jsonl(args.active_chunks_cache)
    chunks: list[dict] = []
    for offset in range(0, len(codes), args.chunk_batch_size):
        batch = codes[offset:offset + args.chunk_batch_size]
        chunks.extend(api.active_chunks(args.workspace_id, batch))
        print(f"loaded active chunks for {min(offset + len(batch), len(codes))}/{len(codes)} documents")
    path = output_dir / "active-chunks.jsonl"
    path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in chunks), encoding="utf-8")
    return chunks


def ranks_for(retrieved: list[tuple], gold: set[tuple]) -> list[int]:
    return [rank for rank, item in enumerate(retrieved, start=1) if item in gold]


def load_completed(path: Path, question_ids: set[str]) -> tuple[list[dict], list[dict]]:
    if not path.exists():
        return [], []
    rows = read_jsonl(path)
    if any(row.get("queryId") not in question_ids for row in rows):
        raise ValueError("invalid Business C2 checkpoint")
    successful = [row for row in rows if row.get("status") == "SUCCESS"]
    ids = [row["queryId"] for row in successful]
    if len(ids) != len(set(ids)):
        raise ValueError("Business C2 checkpoint contains duplicate successful query ids")
    return successful, [row for row in rows if row.get("status") != "SUCCESS"]


def replace_checkpoint(path: Path, rows: list[dict]) -> None:
    path.write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8"
    )


def validate_c1_quote_mapping(mapping: list[dict], active_chunks: list[dict]) -> None:
    """Fail rather than score a Business C2 run against stale runtime chunks."""
    active_keys = {
        runtime_chunk_key(chunk["documentCode"], chunk["generationId"], chunk["chunkNo"])
        for chunk in active_chunks
        if all(chunk.get(field) is not None for field in ("documentCode", "generationId", "chunkNo"))
    }
    missing = []
    for row in mapping:
        for chunk_no in row["runtimeChunkNos"]:
            key = runtime_chunk_key(row["documentCode"], row["generationId"], chunk_no)
            if key not in active_keys:
                missing.append(f"{row['canonicalChunkId']} -> {key}")
    if missing:
        preview = "; ".join(missing[:5])
        raise ApiError(
            "Business C1 quote-to-runtime-chunk 映射不再对应当前 Active Generation；"
            f"请重新上传并生成 C1 映射后再评测。missing={preview}"
        )


def main() -> None:
    args = parse_args()
    dataset_root = args.dataset_root.resolve()
    c1_run = args.c1_run.resolve()
    document_map = json.loads((c1_run / "document-map.json").read_text(encoding="utf-8"))
    quote_mapping = json.loads((c1_run / "quote-to-runtime-chunk.json").read_text(encoding="utf-8"))
    mapping_by_chunk = {row["canonicalChunkId"]: row for row in quote_mapping}
    questions = active_cases(read_lines(dataset_root / "cases" / "test.jsonl"))
    if not questions or set(document_map) != {row["documentId"] for row in read_lines(dataset_root / "corpus" / "manifest.jsonl")}:
        raise ValueError("Business C1 document-map 与冻结 corpus 不一致")
    if any(question.get("reviewStatus") != "VERIFIED" for question in questions):
        raise ValueError("Business C2 only accepts VERIFIED cases")
    output_dir = args.resume_run.resolve() if args.resume_run else PROJECT_ROOT / "rag-eval" / "runs" / args.run_id
    if args.resume_run and not output_dir.is_dir():
        raise FileNotFoundError(f"resume run directory does not exist: {output_dir}")
    output_dir.mkdir(parents=True, exist_ok=True)
    if args.resume_run and args.active_chunks_cache is None:
        cached_chunks = output_dir / "active-chunks.jsonl"
        if not cached_chunks.is_file():
            raise FileNotFoundError(
                "resume run 缺少 active-chunks.jsonl；请提供 --active-chunks-cache，"
                "避免在中断后对变化的 Active Generation 继续计分"
            )
        args.active_chunks_cache = cached_chunks
    config = {
        "experiment": "workspace-business-full-v1-c2-dense-bm25-rrf-rerank",
        "datasetRoot": str(dataset_root), "c1Run": str(c1_run), "workspaceId": args.workspace_id,
        "candidateLimit": args.candidate_limit, "topK": args.top_k, "rrfK": args.rrf_k,
        "rerankInputVariant": args.rerank_input_variant,
        "metricContract": "Business evidence requirement groups over rerank TopK chunks",
    }
    config_path = output_dir / "run-config.json"
    if config_path.exists() and json.loads(config_path.read_text(encoding="utf-8")) != config:
        raise ValueError("resume configuration differs from the Business C2 run")
    write_json(config_path, config)
    api = WorkspaceApi(args)
    codes = [row["documentCode"] for row in document_map.values()]
    active_chunks = load_active_chunks(api, args, codes, output_dir)
    validate_c1_quote_mapping(quote_mapping, active_chunks)
    reverse_documents = {row["documentCode"]: document_id for document_id, row in document_map.items()}
    valid_chunks = [{"documentKey": reverse_documents[chunk["documentCode"]], "content": chunk["content"], "candidate": {
        "documentCode": chunk["documentCode"], "generationId": chunk["generationId"], "chunkNo": int(chunk["chunkNo"]),
    }} for chunk in active_chunks if chunk.get("documentCode") in reverse_documents and chunk.get("content")
                      and chunk.get("generationId") is not None and chunk.get("chunkNo") is not None]
    if not valid_chunks:
        raise ApiError("未读取到 Business Active Generation chunks")
    bm25 = Bm25Index(valid_chunks, k1=1.2, b=0.75)
    cases_path = output_dir / "cases.jsonl"
    results, failed_before_resume = load_completed(cases_path, {question["queryId"] for question in questions})
    if failed_before_resume:
        write_jsonl_path = output_dir / "failed-cases-before-resume.jsonl"
        replace_checkpoint(write_jsonl_path, failed_before_resume)
        replace_checkpoint(cases_path, results)
        print(f"retrying {len(failed_before_resume)} previous error case(s)")
    completed = {row["queryId"] for row in results}
    for index, question in enumerate(questions, start=1):
        if question["queryId"] in completed:
            continue
        started = time.perf_counter()
        try:
            dense_response = api.retrieve_candidates(args.workspace_id, question["query"], args.candidate_limit, args.top_k)
            dense = dense_response.get("diagnostics", {}).get("qdrantCandidates", [])
            if len(dense) != args.candidate_limit:
                raise ApiError(f"expected {args.candidate_limit} Dense candidates, got {len(dense)}")
            bm25_candidates = []
            for rank, (chunk_index, score) in enumerate(bm25.top_n(question["query"], args.candidate_limit), start=1):
                candidate = dict(valid_chunks[chunk_index]["candidate"])
                candidate.update({"rank": rank, "score": score})
                bm25_candidates.append(candidate)
            fused = rrf_fuse(dense, bm25_candidates, args.rrf_k, args.candidate_limit)
            reranked = api.rerank_candidates(
                args.workspace_id, question["query"], fused, args.top_k, args.rerank_input_variant)
            retrieved_chunks = [runtime_chunk_key(row["documentCode"], row["generationId"], row["chunkNo"])
                                for row in reranked]
            groups = gold_runtime_groups(question, mapping_by_chunk) if question["answerable"] else []
            golden_chunks = set().union(*groups) if groups else set()
            golden_documents = {
                evidence["chunkId"].split("#", 1)[0]
                for group in evidence_requirement_groups(question) for evidence in group["evidence"]
            }
            retrieved_documents = []
            for row in reranked:
                document_id = reverse_documents.get(row["documentCode"])
                if document_id and document_id not in retrieved_documents:
                    retrieved_documents.append(document_id)
            document_ranks = ranks_for(retrieved_documents, golden_documents)
            group_ranks = [ranks_for(retrieved_chunks, group) for group in groups]
            group_satisfied = [bool(ranks) for ranks in group_ranks]
            first_evidence_rank = min((ranks[0] for ranks in group_ranks if ranks), default=None)
            result = {
                "queryId": question["queryId"], "suite": question["suite"], "queryType": question["queryType"],
                "query": question["query"], "goldenDocumentIds": sorted(golden_documents),
                "retrievedDocumentIds": retrieved_documents, "goldenRuntimeChunks": [list(row) for row in sorted(golden_chunks)],
                "retrievedRuntimeChunks": [list(row) for row in retrieved_chunks],
                "documentHitAtK": bool(document_ranks),
                "documentRecallAtK": len(set(retrieved_documents) & golden_documents) / len(golden_documents) if golden_documents else None,
                "documentReciprocalRankAtK": 1 / document_ranks[0] if document_ranks else 0,
                "chunkHitAtK": all(group_satisfied) if groups else None,
                "chunkRecallAtK": sum(group_satisfied) / len(groups) if groups else None,
                "chunkReciprocalRankAtK": 1 / first_evidence_rank if first_evidence_rank else 0,
                "fullEvidenceHitAtK": all(group_satisfied) if groups else None,
                "evidenceGroupRecallAtK": sum(group_satisfied) / len(groups) if groups else None,
                "firstEvidenceReciprocalRankAtK": 1 / first_evidence_rank if first_evidence_rank else 0,
                "evidenceGroupSatisfiedAtK": group_satisfied,
                "evidenceGroupFirstRanks": [ranks[0] if ranks else None for ranks in group_ranks],
                "denseCandidates": dense, "bm25Candidates": bm25_candidates, "rrfCandidates": fused,
                "rerankedCandidates": reranked, "rerankInputVariant": args.rerank_input_variant,
                "latencyMs": round((time.perf_counter() - started) * 1000, 2), "status": "SUCCESS",
            }
        except (ApiError, KeyError) as exception:
            result = {"queryId": question["queryId"], "suite": question["suite"], "query": question["query"],
                      "status": "ERROR", "error": str(exception), "latencyMs": round((time.perf_counter() - started) * 1000, 2)}
        append_jsonl_checkpoint(cases_path, result)
        results.append(result)
        completed.add(result["queryId"])
        if index % 10 == 0 or index == len(questions):
            print(f"evaluated {index}/{len(questions)}")
    dataset_version = json.loads((dataset_root / "dataset.json").read_text(encoding="utf-8"))["datasetVersion"]
    summary = build_summary(results, args, dataset_version, len(quote_mapping)) | {
        "experiment": config["experiment"], "c1Run": str(c1_run), "bm25": {"k1": 1.2, "b": 0.75},
        "rrf": {"k": args.rrf_k}, "rerankInputVariant": args.rerank_input_variant,
        "scope": "evaluation-only: Dense chunk TopN + offline BM25 chunk TopN -> RRF -> "
                 "same WS reranker with recorded authoritative input variant -> TopK chunks",
    }
    write_json(output_dir / "summary.json", summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
