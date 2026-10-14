#!/usr/bin/env python3
"""通过生产可信检索服务评测 SciFact 300 Query；客户端不执行 BM25、RRF 或 Rerank。"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import statistics
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path

from run_retrieval_v2 import WorkspaceApi

ROOT = Path(__file__).resolve().parents[2]


def implementation_hashes():
    paths = ["hex-workspace-domain/src/main/java/com/hector/domain/knowledge/service/impl/KnowledgeRetrievalServiceImpl.java",
             "hex-workspace-domain/src/main/java/com/hector/domain/knowledge/service/ReciprocalRankFusion.java",
             "hex-workspace-infrastructure/src/main/java/com/hector/infrastructure/rag/Bm25Index.java",
             "hex-workspace-infrastructure/src/main/java/com/hector/infrastructure/rag/InMemoryBm25RetrievalStore.java",
             "hex-workspace-app/src/main/resources/application-dev.yml",
             "rag-eval/scripts/run_scifact_production_hybrid.py"]
    return {path: digest(ROOT / path) for path in paths}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_rows(path):
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def json_text(value, indent=None):
    """正常保留中文；遇孤立代理码位时转义保存，不能替换或丢弃原始字符。"""
    text = json.dumps(value, ensure_ascii=False, indent=indent)
    try:
        text.encode("utf-8")
    except UnicodeEncodeError:
        text = json.dumps(value, ensure_ascii=True, indent=indent)
    return text


def save(path, value):
    # 先完成可编码性检查再打开文件，避免编码失败截断已有证据。
    text = json_text(value, indent=2) + "\n"
    path.write_text(text, encoding="utf-8")


def append(path, value):
    text = json_text(value) + "\n"
    with path.open("a", encoding="utf-8") as handle:
        handle.write(text)
        handle.flush()
        os.fsync(handle.fileno())


def generation_hash(rows):
    return hashlib.sha256("\n".join(sorted(row["generationId"] for row in rows)).encode()).hexdigest()


def check_profile(status, expected_hash, expected_chunks):
    if not status.get("enabled") or not status.get("rerankEnabled"):
        raise RuntimeError("HYBRID_OR_RERANK_DISABLED：请确认服务配置并重启")
    if status.get("maxChunksPerDocument") not in (0, 1) or status.get("rerankWeight") != 1.0:
        raise RuntimeError("PROFILE_MISMATCH：未知文档策略或非纯 Rerank 权重 1.0")
    if status.get("ready") and (status.get("generationHash") != expected_hash
                                or status.get("chunkCount") != expected_chunks):
        raise RuntimeError("SNAPSHOT_CORPUS_MISMATCH：快照未完整覆盖冻结语料或包含额外文档")


def request_json(api, endpoint, body):
    response = api.request("POST", endpoint, body=json.dumps(body).encode(), content_type="application/json",
                           token=api.evaluation_token, retryable=False)
    return response["data"] if isinstance(response.get("data"), dict) else response


def evaluate_response(question, response, reverse, top_k, unlimited=False):
    hits = response.get("hits")
    if not isinstance(hits, list):
        raise ValueError("INVALID_RESPONSE：缺少 hits")
    codes = [hit.get("documentCode") for hit in hits]
    if len(codes) > top_k or (not unlimited and len(codes) != len(set(codes))) or any(code not in reverse for code in codes):
        raise ValueError("RESULT_CONTRACT_FAILED：重复文档、越界或未知语料")
    retrieved = [reverse[code] for code in codes]
    gold = {item["documentKey"] for item in question["evidences"]}
    ranks = [i for i, key in enumerate(retrieved, 1) if key in gold]
    diagnostics = response.get("diagnostics", {})
    if any(key not in diagnostics for key in ("authorityValidCandidates", "rerankedCandidates", "finalCandidates")):
        raise ValueError("DIAGNOSTICS_MISSING：无法确认权威复核和 Rerank")
    valid = diagnostics["authorityValidCandidates"]
    reranked = diagnostics["rerankedCandidates"]
    # 单候选不需重排；多候选没有重排证据时，独立记录回退，不冒充完整链路成功。
    rerank = "NOT_NEEDED" if len(valid) < 2 else ("APPLIED" if reranked else "FALLBACK")
    return {"id": question["id"], "question": question["question"], "status": "SUCCESS",
            "goldenDocumentKeys": sorted(gold), "retrievedDocumentKeys": retrieved,
            "hitAtK": bool(ranks), "recallAtK": len(set(retrieved) & gold) / len(gold),
            "reciprocalRankAtK": 1 / ranks[0] if ranks else 0, "rerankStatus": rerank,
            "uniqueRetrievedDocumentKeys": list(dict.fromkeys(retrieved)), "response": response}


def percentile(values, p):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * p / 100) - 1)]


def summarize(cases, total, top_k, gate):
    valid = [row for row in cases if row["status"] == "SUCCESS"]
    full = [row for row in valid if row["rerankStatus"] != "FALLBACK"]
    timings = [row["latencyMs"] for row in valid]
    result = {"gate": gate, "queryCount": total, "attemptedQueries": len(cases),
              "successfulQueries": len(valid), "errorQueries": len(cases) - len(valid),
              "completeChainQueries": len(full), "rerankFallbackQueries": len(valid) - len(full),
              "metricUnit": "distinct document Top-K", "topK": top_k,
              "denominatorPolicy": "all 300: errors/unexecuted treated as unsuccessful; valid: successful HTTP/contract responses",
              "latencyScope": "client HTTP wall-clock; snapshot preparation excluded",
              "percentileMethod": "nearest-rank ceil(n*p)",
              "latencyMs": {"p50": percentile(timings, 50), "p95": percentile(timings, 95)},
              "providerUsage": None, "providerUsageReason": "existing retrieval endpoint does not report provider tokens",
              "limitations": ["BM25 uses English regex tokenization", "no new corpus import or rebuild",
                              "backend SDK retries are not observable here", "no offline rerank injected by client",
                              "historical metrics have different runtime and tie-breaking; not a matched causal A/B"]}
    for name, rows, denominator in (("allRegistered", valid, total), ("validExecution", valid, len(valid)),
                                    ("completeChain", full, len(full))):
        result[name] = {"denominator": denominator,
                        "hitAtK": sum(row["hitAtK"] for row in rows) / denominator if denominator else None,
                        "recallAtK": sum(row["recallAtK"] for row in rows) / denominator if denominator else None,
                        "mrrAtK": sum(row["reciprocalRankAtK"] for row in rows) / denominator if denominator else None}
    return result


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.getenv("HEX_WORKSPACE_BASE_URL", "http://127.0.0.1:8098"))
    parser.add_argument("--workspace-id", type=int, required=True)
    parser.add_argument("--evaluation-token", default=os.getenv("HEXWORKSPACE_RAG_EVALUATION_TOKEN", ""))
    parser.add_argument("--document-map", type=Path,
                        default=ROOT / "rag-eval/runs/general-scifact-full-20261004T075524Z/document-map.json")
    parser.add_argument("--dataset-root", type=Path, default=ROOT / "rag-eval/cache/general-scifact-full-v1")
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--candidate-limit", type=int, default=30)
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--expected-chunks", type=int, default=19557)
    parser.add_argument("--snapshot-timeout", type=float, default=900)
    parser.add_argument("--request-timeout", type=float, default=120)
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args()
    if args.workspace_id <= 0 or not args.evaluation_token:
        parser.error("workspace-id 必须为正数，且需 HEXWORKSPACE_RAG_EVALUATION_TOKEN")
    if not 2 <= args.candidate_limit <= 100 or not 1 <= args.top_k <= 20 or args.expected_chunks <= 0:
        parser.error("candidate-limit / top-k / expected-chunks 超出范围")
    args.user_token = ""
    args.http_retries = 0  # 失败不自动重发真实检索请求。
    return args


def main(unlimited=False):
    args = parse_args()
    if unlimited and (args.candidate_limit != 30 or args.top_k != 5):
        raise ValueError("冻结合同要求 candidate-limit=30 / top-k=5")
    questions_path = args.dataset_root / "qa/test.jsonl"
    questions = read_rows(questions_path)
    document_map = json.loads(args.document_map.read_text(encoding="utf-8"))
    reverse = {row["documentCode"]: key for key, row in document_map.items()}
    if len(questions) != 300 or len({row["id"] for row in questions}) != 300 or len(reverse) != 5183:
        raise ValueError("需要完整 300 Query / 5183 文档的冻结 SciFact")
    if any(not q.get("evidences") or any(e["documentKey"] not in document_map for e in q["evidences"]) for q in questions):
        raise ValueError("Oracle 缺失或 Document Map 不完整")
    output = args.output_dir.resolve()
    if args.resume:
        if not (output / "manifest.json").is_file():
            raise ValueError("resume 需要现有 manifest")
    else:
        output.mkdir(parents=True, exist_ok=False)
    identity = {"baseUrl": args.base_url, "workspaceId": args.workspace_id,
                "datasetSha256": digest(questions_path), "documentMapSha256": digest(args.document_map),
                "candidateLimit": args.candidate_limit, "topK": args.top_k, "expectedChunks": args.expected_chunks,
                "localSourceHashes": implementation_hashes(), "unlimitedDocuments": unlimited}
    old = json.loads((output / "manifest.json").read_text()) if args.resume else None
    if old and old["identity"] != identity:
        raise ValueError("RESUME_INPUT_MISMATCH")
    cases_path = output / "cases.jsonl"
    cases = read_rows(cases_path)
    ids = {row["id"] for row in cases}
    if len(ids) != len(cases) or not ids.issubset({q["id"] for q in questions}):
        raise ValueError("INVALID_CHECKPOINT")
    attempts = read_rows(output / "attempts.jsonl")
    if any(row["id"] not in ids for row in attempts):
        raise ValueError("UNSETTLED_ATTEMPT：有已启动但未落盘终态的请求，停止以免重复收费；请人工审计")
    api = WorkspaceApi(args)
    manifest = old or {"createdAt": datetime.now(timezone.utc).isoformat(), "identity": identity,
                       "endpoint": "/internal/v1/evaluation/retrieval/chunks-topk" if unlimited else "/internal/v1/evaluation/retrieval", "httpRetries": 0,
                       "clientAlgorithm": "none", "gitHead": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()}
    save(output / "manifest.json", manifest)
    try:
        # 1. 无模型预检查：当前 Active Generation 与完整 BM25 快照身份相同才计时。
        readiness = []
        codes = sorted(reverse)
        for start in range(0, len(codes), 25):
            readiness.extend(api.readiness(args.workspace_id, codes[start:start + 25]))
        save(output / "readiness-before.json", readiness)
        if (len(readiness) != 5183 or {row["documentCode"] for row in readiness} != set(codes)
                or len({row.get("generationId") for row in readiness}) != 5183
                or any(not row["ready"] or not row.get("generationId") for row in readiness)):
            raise RuntimeError("CORPUS_NOT_READY")
        expected_hash = generation_hash(readiness)
        if old and old.get("generationHash") and old["generationHash"] != expected_hash:
            raise RuntimeError("RESUME_GENERATION_CHANGED")
        manifest["generationHash"] = expected_hash
        save(output / "manifest.json", manifest)
        body = {"workspaceId": args.workspace_id, "documentCodes": codes[:1]}
        def status():
            return request_json(api, "/internal/v1/evaluation/retrieval/hybrid-status", body)
        deadline = time.monotonic() + args.snapshot_timeout
        while True:
            profile = status()
            save(output / "snapshot-before.json", profile)
            check_profile(profile, expected_hash, args.expected_chunks)
            if not unlimited and profile.get("maxChunksPerDocument") == 0:
                raise RuntimeError("生产已取消文档限额；本脚本原口径需使用 run_scifact_chunk_top5.py")
            if profile.get("lastRefreshError"):
                raise RuntimeError("SNAPSHOT_BUILD_FAILED：" + profile["lastRefreshError"])
            if profile.get("ready"):
                break
            if time.monotonic() >= deadline:
                raise RuntimeError("SNAPSHOT_READY_TIMEOUT")
            time.sleep(2)
        print(f"GATE PASS chunks={profile['chunkCount']} chars={profile['sourceChars']}", flush=True)
        # 2. 每条请求使用生产完整链路；所有错误原样保留，不重跑已完成案例。
        for question in questions:
            if question["id"] in ids:
                continue
            profile_now = status()
            check_profile(profile_now, expected_hash, args.expected_chunks)
            if not profile_now.get("ready") or profile_now["servingConfigId"] != profile["servingConfigId"]:
                raise RuntimeError("SNAPSHOT_OR_SERVING_CHANGED")
            append(output / "attempts.jsonl", {"id": question["id"], "startedAt": datetime.now(timezone.utc).isoformat()})
            started = time.perf_counter()
            response = None
            try:
                if unlimited:
                    response = request_json(api, manifest["endpoint"], {"workspaceId": args.workspace_id,
                            "query": question["question"], "candidateLimit": 30, "topK": 5})
                    if response.get("evaluationProfile") != {"variant": "HYBRID_CHUNK_TOPK", "maxChunksPerDocument": 5}:
                        raise ValueError("CHUNK_TOPK_PROFILE_MISMATCH：重新编译/启动后端")
                    if "rerankedChunks" not in response.get("diagnostics", {}):
                        raise ValueError("CHUNK_RANK_DIAGNOSTICS_MISSING")
                else:
                    response = api.retrieve(args.workspace_id, question["question"], args.candidate_limit, args.top_k)
                row = evaluate_response(question, response, reverse, args.top_k, unlimited)
            except Exception as exc:
                row = {"id": question["id"], "question": question["question"], "status": "ERROR",
                       "goldenDocumentKeys": sorted({e["documentKey"] for e in question["evidences"]}),
                       "errorType": type(exc).__name__, "error": str(exc), "response": response}
            row["latencyMs"] = (time.perf_counter() - started) * 1000
            row["snapshotGenerationHash"] = profile_now["generationHash"]
            append(cases_path, row)
            cases.append(row)
            print(f"{len(cases)}/300 {row['id']} {row['status']}", flush=True)
            if unlimited and row.get("errorType") == "ValueError":
                raise ValueError("停止：Chunk TopK 接口/诊断契约错误")
        # 3. 再读全部权威 Generation，阻断实验中语料切换造成的混合统计。
        after = []
        for start in range(0, len(codes), 25):
            after.extend(api.readiness(args.workspace_id, codes[start:start + 25]))
        save(output / "readiness-after.json", after)
        profile_after = status()
        save(output / "snapshot-after.json", profile_after)
        check_profile(profile_after, expected_hash, args.expected_chunks)
        if (len(after) != 5183 or {row["documentCode"] for row in after} != set(codes)
                or any(not row["ready"] for row in after) or generation_hash(after) != expected_hash
                or profile_after["servingConfigId"] != profile["servingConfigId"] or not profile_after.get("ready")):
            raise RuntimeError("CORPUS_CHANGED_DURING_RUN")
        gate = "COMPLETE" if all(row["status"] == "SUCCESS" and row["rerankStatus"] != "FALLBACK" for row in cases) else "COMPLETE_WITH_ERRORS_OR_FALLBACKS"
    except Exception as exc:
        gate = "BLOCKED_OR_INCOMPLETE"
        save(output / "stop.json", {"errorType": type(exc).__name__, "error": str(exc)})
        print(f"STOP {exc}", flush=True)
    summary = summarize(cases, 300, args.top_k, gate)
    if unlimited:
        summary["metricUnit"] = "SciFact document relevance labels evaluated on raw Top5 chunks (duplicates consume ranks)"
        summary["selectionContract"] = {"candidateLimit": 30, "topKChunks": 5, "perDocumentLimit": "none within Top5"}
        summary["limitations"].append("not comparable in K unit to Top5 distinct documents; real rerank is a new call")
    save(output / "summary.json", summary)
    save(output / "manifest.json", manifest)
    (output / "REPORT.md").write_text("# SciFact Production Hybrid\n\n```json\n" + json.dumps(summary, ensure_ascii=False, indent=2) + "\n```\n", encoding="utf-8")
    print(json.dumps({"gate": gate, "report": str(output / "summary.json")}, ensure_ascii=False), flush=True)
    if gate != "COMPLETE":
        raise SystemExit(1)


if __name__ == "__main__":
    main()
