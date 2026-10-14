#!/usr/bin/env python3
"""通过 Production 混合检索重测原 143 条 Java Notes；不上传语料，不改变旧 Gold。"""
from __future__ import annotations

import argparse
import json
import os
import time
from datetime import datetime, timezone
from pathlib import Path

from run_retrieval_v2 import WorkspaceApi
from run_rag_eval_v2 import (manifest_rows, evidence_requirement_groups, gold_runtime_groups,
                             runtime_chunk_key, ranks_for)
from run_scifact_production_hybrid import (ROOT, digest, read_rows, save, append, generation_hash,
                                          check_profile, request_json, percentile, implementation_hashes)

DEFAULT_BASELINE = ROOT / "rag-eval/runs/java-notes-v1-draft-rerun-20261006T084332Z~"
DEFAULT_DATASET = ROOT / "rag-eval/dataset-v2-java-notes-v1-draft"


def require_unlimited_profile(status, required):
    if required and (status.get("maxChunksPerDocument") != 0 or status.get("documentLimitPolicy") != "NONE"):
        raise RuntimeError("PRODUCTION_DOCUMENT_LIMIT_STILL_ENABLED：请重编译并重启不限额后端，不执行真实查询")


def validate_mapping(questions, mapping, chunks, document_map, readiness):
    by_id = {row["canonicalChunkId"]: row for row in mapping}
    if len(by_id) != len(mapping):
        raise ValueError("DUPLICATE_CANONICAL_MAPPING")
    ready = {row["documentCode"]: row["generationId"] for row in readiness}
    by_key = {}
    for chunk in chunks:
        code = chunk["documentCode"]
        if code not in ready or chunk["generationId"] != ready[code]:
            raise ValueError("CHUNK_GENERATION_MISMATCH")
        key = runtime_chunk_key(code, chunk["generationId"], chunk["chunkNo"])
        if key in by_key:
            raise ValueError("DUPLICATE_RUNTIME_CHUNK")
        by_key[key] = chunk["content"]
    for row in mapping:
        if (row["documentId"] not in document_map
                or document_map[row["documentId"]]["documentCode"] != row["documentCode"]
                or ready.get(row["documentCode"]) != row["generationId"] or not row["runtimeChunkNos"]):
            raise ValueError("STALE_OR_INVALID_QUOTE_MAPPING")
        if any(runtime_chunk_key(row["documentCode"], row["generationId"], no) not in by_key
               for no in row["runtimeChunkNos"]):
            raise ValueError("MAPPED_CHUNK_NOT_PRESENT")
    for question in questions:
        gold_runtime_groups(question, by_id)
        for group in evidence_requirement_groups(question):
            for evidence in group["evidence"]:
                row = by_id[evidence["chunkId"]]
                quote = evidence.get("quote", "")
                if not quote or not any(quote in by_key[runtime_chunk_key(row["documentCode"], row["generationId"], no)]
                                        for no in row["runtimeChunkNos"]):
                    raise ValueError("QUOTE_SOURCE_NOT_SUPPORTED")
    return by_id


def evaluate(question, response, reverse, mapping, top_k, max_chunks_per_document=1):
    hits = response.get("hits")
    diagnostics = response.get("diagnostics", {})
    if not isinstance(hits, list) or any(key not in diagnostics for key in
            ("authorityValidCandidates", "rerankedCandidates", "finalCandidates")):
        raise ValueError("RESPONSE_OR_DIAGNOSTICS_MISSING")
    codes = [hit.get("documentCode") for hit in hits]
    if (len(codes) > top_k or any(codes.count(code) > max_chunks_per_document for code in set(codes))
            or any(code not in reverse for code in codes)):
        raise ValueError("DOCUMENT_RESULT_CONTRACT_FAILED")
    documents = list(dict.fromkeys(reverse[code] for code in codes))
    retrieved_chunks = [runtime_chunk_key(hit["documentCode"], hit["generationId"], hit["chunkNo"]) for hit in hits]
    groups = gold_runtime_groups(question, mapping)
    gold_docs = {e["chunkId"].split("#", 1)[0] for group in evidence_requirement_groups(question) for e in group["evidence"]}
    doc_ranks = ranks_for(documents, gold_docs)
    group_ranks = [ranks_for(retrieved_chunks, group) for group in groups]
    satisfied = [bool(ranks) for ranks in group_ranks]
    first = min((ranks[0] for ranks in group_ranks if ranks), default=None)
    rerank = "NOT_NEEDED" if len(diagnostics["authorityValidCandidates"]) < 2 else (
        "APPLIED" if diagnostics["rerankedCandidates"] else "FALLBACK")
    return {"queryId": question["queryId"], "query": question["query"], "status": "SUCCESS",
            "reviewStatus": question["reviewStatus"], "queryType": question["queryType"],
            "goldenDocumentIds": sorted(gold_docs), "retrievedDocumentIds": documents,
            "documentHitAtK": bool(doc_ranks), "documentRecallAtK": len(set(documents) & gold_docs) / len(gold_docs),
            "documentReciprocalRankAtK": 1 / doc_ranks[0] if doc_ranks else 0,
            "fullEvidenceHitAtK": all(satisfied), "evidenceGroupRecallAtK": sum(satisfied) / len(groups),
            "firstEvidenceReciprocalRankAtK": 1 / first if first else 0,
            "evidenceGroupSatisfiedAtK": satisfied,
            "goldenEvidenceRequirementGroups": [[list(key) for key in sorted(group)] for group in groups],
            "retrievedRuntimeChunks": [list(key) for key in retrieved_chunks],
            "rerankStatus": rerank, "response": response}


def summarize(cases, gate, top_k):
    valid = [row for row in cases if row["status"] == "SUCCESS"]
    full = [row for row in valid if row["rerankStatus"] != "FALLBACK"]
    fields = {"documentHitRateAtK": "documentHitAtK", "documentRecallAtK": "documentRecallAtK",
              "documentMrrAtK": "documentReciprocalRankAtK", "fullEvidenceHitRateAtK": "fullEvidenceHitAtK",
              "evidenceGroupRecallAtK": "evidenceGroupRecallAtK", "firstEvidenceMrrAtK": "firstEvidenceReciprocalRankAtK"}
    result = {"gate": gate, "datasetVersion": "java-notes-v1-draft", "qualification": "EVIDENCE_MAPPED_REVIEW_PENDING",
              "registeredQueryCount": 143, "attemptedQueries": len(cases), "successfulQueries": len(valid),
              "errorQueries": len(cases) - len(valid), "completeChainQueries": len(full),
              "rerankFallbackQueries": len(valid) - len(full), "topK": top_k,
              "denominatorPolicy": "all registered: failures/unexecuted count as unsuccessful; valid: successful responses",
              "providerUsage": None, "providerUsageReason": "endpoint does not expose provider tokens",
              "latencyMs": {"p50": percentile([r["latencyMs"] for r in valid], 50),
                            "p95": percentile([r["latencyMs"] for r in valid], 95)},
              "latencyScope": "client HTTP retrieval only; snapshot preparation and status checks excluded",
              "percentileMethod": "nearest-rank ceil(n*p)",
              "limitations": ["query semantic review remains pending; not a newly VERIFIED benchmark",
                              "English BM25 tokenization; Chinese-query gains are not presumed",
                              "one chunk per document differs from historical multi-chunk results",
                              "quote-to-runtime mapping preserves historical canonical-anchor granularity",
                              "historical latency percentile definition differs; not a matched causal A/B",
                              "local source hashes do not attest deployed server binary; SDK retries unobservable"]}
    for name, rows, n in (("allRegistered", valid, 143), ("validExecution", valid, len(valid)), ("completeChain", full, len(full))):
        result[name] = {"denominator": n} | {out: sum(r[field] for r in rows) / n if n else None for out, field in fields.items()}
    return result


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workspace-id", type=int, default=14)
    parser.add_argument("--base-url", default=os.getenv("HEX_WORKSPACE_BASE_URL", "http://127.0.0.1:8098"))
    parser.add_argument("--evaluation-token", default=os.getenv("HEXWORKSPACE_RAG_EVALUATION_TOKEN", ""))
    parser.add_argument("--dataset-root", type=Path, default=DEFAULT_DATASET)
    parser.add_argument("--baseline-run", type=Path, default=DEFAULT_BASELINE)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--candidate-limit", type=int, default=30)
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--request-timeout", type=float, default=120)
    parser.add_argument("--snapshot-timeout", type=float, default=900)
    parser.add_argument("--allow-evidence-mapped-draft", action="store_true")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--require-unlimited", action="store_true", help="确认生产取消文档限额后才执行真实查询")
    args = parser.parse_args()
    if args.workspace_id <= 0 or not args.evaluation_token or not args.allow_evidence_mapped_draft:
        parser.error("需要 Workspace、评测令牌和显式 --allow-evidence-mapped-draft；不能把原草案当作 VERIFIED")
    if not 2 <= args.candidate_limit <= 100 or not 1 <= args.top_k <= 20:
        parser.error("检索参数超出范围")
    args.user_token, args.http_retries = "", 0
    return args


def main():
    args = parse_args()
    questions_path = args.dataset_root / "cases/evidence-mapped.jsonl"
    questions = read_rows(questions_path)
    source_rows = manifest_rows(args.dataset_root)
    map_path, quote_path = args.baseline_run / "document-map.json", args.baseline_run / "quote-to-runtime-chunk.json"
    document_map = json.loads(map_path.read_text(encoding="utf-8"))
    mapping = json.loads(quote_path.read_text(encoding="utf-8"))
    reverse = {row["documentCode"]: key for key, row in document_map.items()}
    if (len(questions) != 143 or len({q["queryId"] for q in questions}) != 143 or len(reverse) != 34
            or set(document_map) != {row["documentId"] for row in source_rows}
            or any(not q["answerable"] or q["reviewStatus"] != "EVIDENCE_MAPPED" for q in questions)):
        raise ValueError("DATASET_CONTRACT_MISMATCH：需要原 143 条已映射查询与 34 份资料，不替换样本")
    output = args.output_dir.resolve()
    if not args.resume:
        output.mkdir(parents=True, exist_ok=False)
    old = json.loads((output / "manifest.json").read_text()) if args.resume else None
    identity = {"workspaceId": args.workspace_id, "baseUrl": args.base_url, "candidateLimit": args.candidate_limit,
                "topK": args.top_k, "queriesSha256": digest(questions_path), "documentMapSha256": digest(map_path),
                "quoteMappingSha256": digest(quote_path), "corpusManifestSha256": digest(args.dataset_root / "corpus/manifest.jsonl"),
                "localSourceHashes": implementation_hashes() | {"businessRunner": digest(Path(__file__))},
                "requireUnlimited": args.require_unlimited}
    if old and old["identity"] != identity:
        raise ValueError("RESUME_INPUT_MISMATCH")
    cases = read_rows(output / "cases.jsonl")
    ids = {row["queryId"] for row in cases}
    if len(cases) != len(ids) or not ids.issubset({q["queryId"] for q in questions}):
        raise ValueError("INVALID_CHECKPOINT")
    if any(row["queryId"] not in ids for row in read_rows(output / "attempts.jsonl")):
        raise ValueError("UNSETTLED_ATTEMPT：已发起但无终态，拒绝自动重发")
    manifest = old or {"createdAt": datetime.now(timezone.utc).isoformat(), "identity": identity,
                       "endpoint": "/internal/v1/evaluation/retrieval", "clientAlgorithm": "none", "httpRetries": 0}
    save(output / "manifest.json", manifest)
    api = WorkspaceApi(args)
    try:
        codes = sorted(reverse)
        readiness = api.readiness(args.workspace_id, codes)
        save(output / "readiness-before.json", readiness)
        if ({r["documentCode"] for r in readiness} != set(codes) or len(readiness) != 34
                or any(not r["ready"] for r in readiness)):
            raise RuntimeError("CORPUS_NOT_READY_OR_WRONG_WORKSPACE")
        expected_hash = generation_hash(readiness)
        if old and old.get("generationHash") and old["generationHash"] != expected_hash:
            raise RuntimeError("RESUME_GENERATION_CHANGED")
        chunks = api.active_chunks(args.workspace_id, codes)
        save(output / "active-chunks.json", chunks)
        by_chunk = validate_mapping(questions, mapping, chunks, document_map, readiness)
        manifest["generationHash"] = expected_hash
        manifest["expectedChunks"] = len(chunks)
        save(output / "manifest.json", manifest)
        def status():
            return request_json(api, "/internal/v1/evaluation/retrieval/hybrid-status",
                                {"workspaceId": args.workspace_id, "documentCodes": codes[:1]})
        deadline = time.monotonic() + args.snapshot_timeout
        while True:
            profile = status()
            save(output / "snapshot-before.json", profile)
            require_unlimited_profile(profile, args.require_unlimited)
            check_profile(profile, expected_hash, len(chunks))
            if profile.get("lastRefreshError"):
                raise RuntimeError("SNAPSHOT_BUILD_FAILED：" + profile["lastRefreshError"])
            if profile.get("ready"): break
            if time.monotonic() >= deadline: raise RuntimeError("SNAPSHOT_TIMEOUT")
            time.sleep(2)
        print(f"GATE PASS workspace={args.workspace_id} docs=34 chunks={len(chunks)} queries=143", flush=True)
        for question in questions:
            if question["queryId"] in ids: continue
            current = status()
            require_unlimited_profile(current, args.require_unlimited)
            check_profile(current, expected_hash, len(chunks))
            if not current.get("ready") or current["servingConfigId"] != profile["servingConfigId"]:
                raise RuntimeError("SNAPSHOT_OR_CONFIG_CHANGED")
            append(output / "attempts.jsonl", {"queryId": question["queryId"], "startedAt": datetime.now(timezone.utc).isoformat()})
            started, response = time.perf_counter(), None
            try:
                response = api.retrieve(args.workspace_id, question["query"], args.candidate_limit, args.top_k)
                row = evaluate(question, response, reverse, by_chunk, args.top_k,
                               max_chunks_per_document=args.top_k if profile.get("maxChunksPerDocument") == 0 else 1)
            except Exception as exc:
                row = {"queryId": question["queryId"], "query": question["query"], "status": "ERROR",
                       "errorType": type(exc).__name__, "error": str(exc), "response": response}
            row["latencyMs"] = (time.perf_counter() - started) * 1000
            row["snapshotGenerationHash"] = current["generationHash"]
            append(output / "cases.jsonl", row)
            cases.append(row)
            print(f"{len(cases)}/143 {row['queryId']} {row['status']}", flush=True)
            if row.get("errorType") == "ValueError" or any(f"HTTP {code}" in row.get("error", "") for code in (401, 403, 404)):
                raise RuntimeError("REQUEST_CONTRACT_OR_AUTH_ROUTE_FAILURE：保存首个失败后停止，不继续消耗调用")
        after, profile_after = api.readiness(args.workspace_id, codes), status()
        save(output / "readiness-after.json", after)
        save(output / "snapshot-after.json", profile_after)
        require_unlimited_profile(profile_after, args.require_unlimited)
        check_profile(profile_after, expected_hash, len(chunks))
        if (len(after) != 34 or {r["documentCode"] for r in after} != set(codes)
                or any(not r["ready"] for r in after) or generation_hash(after) != expected_hash
                or not profile_after.get("ready") or profile_after["servingConfigId"] != profile["servingConfigId"]):
            raise RuntimeError("CORPUS_OR_CONFIG_CHANGED_DURING_RUN")
        gate = "COMPLETE_DRAFT_REMEASUREMENT" if all(r["status"] == "SUCCESS" and r["rerankStatus"] != "FALLBACK" for r in cases) else "COMPLETE_WITH_ERRORS_OR_FALLBACKS"
    except Exception as exc:
        gate = "BLOCKED_OR_INCOMPLETE"
        save(output / "stop.json", {"errorType": type(exc).__name__, "error": str(exc)})
        print(f"STOP {exc}", flush=True)
    summary = summarize(cases, gate, args.top_k)
    if "profile" in locals():
        summary["selectionContract"] = {"topKChunks": args.top_k,
                "perDocumentLimit": "none" if profile.get("maxChunksPerDocument") == 0 else 1}
        if profile.get("maxChunksPerDocument") == 0:
            summary["limitations"] = [s for s in summary["limitations"] if not s.startswith("one chunk per document")]
            summary["limitations"].append("TopK chunks without per-document limit; not TopK distinct documents")
    save(output / "summary.json", summary)
    (output / "REPORT.md").write_text("# Production Business Remeasurement\n\n```json\n" + json.dumps(summary, ensure_ascii=False, indent=2) + "\n```\n", encoding="utf-8")
    print(json.dumps({"gate": gate, "summary": str(output / "summary.json")}, ensure_ascii=False), flush=True)
    if gate != "COMPLETE_DRAFT_REMEASUREMENT": raise SystemExit(1)


if __name__ == "__main__":
    main()
