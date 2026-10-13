#!/usr/bin/env python3
"""Evaluate dataset-v1.0 through HexWorkspace's current Generation retrieval path.

The runner deliberately separates corpus ingestion (frontend JWT) from retrieval
evaluation (dedicated service token).  It never calls the disabled legacy
``/knowledge/search`` endpoint and does not invoke a chat model.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
import os
import shutil
import statistics
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DATASET_ROOT = PROJECT_ROOT / "rag-eval" / "dataset-v1.0"
DEFAULT_CONFIG_PATH = PROJECT_ROOT / "rag-eval" / "configs" / "retrieval-v2.yml"


class ApiError(RuntimeError):
    """A non-retriable evaluation transport or protocol failure."""


class TransientReadinessError(ApiError):
    """A readiness response that can be retried within the polling deadline."""


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.getenv("HEX_WORKSPACE_BASE_URL", "http://127.0.0.1:8098"))
    parser.add_argument("--workspace-id", type=int, default=int(os.getenv("HEX_WORKSPACE_WORKSPACE_ID", "0")))
    parser.add_argument("--evaluation-token", default=os.getenv("HEXWORKSPACE_RAG_EVALUATION_TOKEN", ""))
    parser.add_argument("--user-token", default=os.getenv("HEX_WORKSPACE_TOKEN", ""),
                        help="only required by --upload for public document import")
    parser.add_argument("--dataset-root", type=Path, default=DATASET_ROOT)
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG_PATH,
                        help="copied into the run directory and hashed in summary.json")
    parser.add_argument("--split", choices=("train", "dev", "test"), default="test")
    parser.add_argument("--candidate-limit", type=int, default=30,
                        help="Qdrant 初始候选池大小，仅用于受控离线评测")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--run-id", default=datetime.now(timezone.utc).strftime("retrieval-v2-%Y%m%dT%H%M%SZ"))
    parser.add_argument("--upload", action="store_true", help="上传冻结语料；可按 batch 等待索引完成")
    parser.add_argument("--document-map", type=Path, help="existing document map; avoids duplicate imports")
    parser.add_argument("--resume-upload-map", type=Path,
                        help="仅与 --upload 一起使用；从已落盘的部分 document-map 继续导入")
    parser.add_argument("--upload-batch-size", type=int, default=25,
                        help="每上传 N 份文档就等待该 batch 进入 Active Generation，0 表示全部上传后再等待")
    parser.add_argument("--poll-interval", type=float, default=2.0)
    parser.add_argument("--poll-timeout", type=float, default=900.0)
    parser.add_argument("--readiness-batch-size", type=int, default=10,
                        help="每次 readiness 请求携带的 documentCode 数；减小评测轮询的数据库突发压力")
    parser.add_argument("--request-timeout", type=float, default=60.0)
    parser.add_argument("--http-retries", type=int, default=2)
    args = parser.parse_args()
    if args.workspace_id <= 0 or not args.evaluation_token:
        parser.error("请提供 --workspace-id / --evaluation-token，或设置对应 HEX_WORKSPACE_* 环境变量")
    if args.upload == bool(args.document_map):
        parser.error("首次运行使用 --upload；复用已有语料时只使用 --document-map PATH")
    if args.resume_upload_map and not args.upload:
        parser.error("--resume-upload-map 只能与 --upload 一起使用")
    if args.upload and not args.user_token:
        parser.error("--upload 需要 --user-token 或 HEX_WORKSPACE_TOKEN")
    if not 1 <= args.candidate_limit <= 100:
        parser.error("--candidate-limit 必须在 1 到 100 之间")
    if not 1 <= args.top_k <= 20:
        parser.error("--top-k 必须在 1 到 20 之间")
    if not 1 <= args.readiness_batch_size <= 50:
        parser.error("--readiness-batch-size 必须在 1 到 50 之间")
    if not 0 <= args.upload_batch_size <= 100:
        parser.error("--upload-batch-size 必须在 0 到 100 之间")
    return args


class WorkspaceApi:
    def __init__(self, args):
        self.base_url = args.base_url.rstrip("/")
        self.evaluation_token = args.evaluation_token
        self.user_token = args.user_token
        self.timeout = args.request_timeout
        self.retries = args.http_retries
        host = urllib.parse.urlparse(self.base_url).hostname
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({})) if host in {"localhost", "127.0.0.1", "::1"} else urllib.request.build_opener()

    def request(self, method, path, *, body=None, content_type=None, token=None, retryable=False, extra_headers=None):
        headers = {"Authorization": f"Bearer {token}"} if token else {}
        if content_type:
            headers["Content-Type"] = content_type
        if extra_headers:
            headers.update(extra_headers)
        request = urllib.request.Request(self.base_url + path, data=body, headers=headers, method=method)
        for attempt in range((self.retries if retryable else 0) + 1):
            try:
                with self.opener.open(request, timeout=self.timeout) as response:
                    return json.loads(response.read().decode("utf-8"))
            except urllib.error.HTTPError as exc:
                payload = exc.read().decode("utf-8", errors="replace")
                if 500 <= exc.code < 600 and attempt < self.retries and retryable:
                    time.sleep(attempt + 1)
                    continue
                raise ApiError(f"{method} {path} failed: HTTP {exc.code}; {payload}") from exc
            except (urllib.error.URLError, TimeoutError) as exc:
                if attempt < self.retries and retryable:
                    time.sleep(attempt + 1)
                    continue
                raise ApiError(f"{method} {path} failed: {exc}") from exc

    @staticmethod
    def unwrap(payload):
        if payload.get("code") != "0000":
            raise ApiError(f"Workspace response {payload.get('code')}: {payload.get('info')}")
        return payload.get("data")

    @staticmethod
    def internal_field(payload, field, endpoint):
        """Read direct internal responses and the legacy common response envelope.

        Evaluation endpoints were introduced as service-token-only APIs and the
        current controller returns a direct JSON body.  Some already-running
        deployments still wrap the same body as ``{code, info, data}``.  Accept
        both successful shapes, but surface the actual body for every other
        shape instead of masking it with a KeyError.
        """
        if field in payload:
            return payload[field]
        if payload.get("code") is not None:
            data = WorkspaceApi.unwrap(payload)
            if isinstance(data, dict) and field in data:
                return data[field]
        raise ApiError(f"unexpected response from {endpoint}: {json.dumps(payload, ensure_ascii=False)}")

    def root_folder(self, workspace_id):
        return self.unwrap(self.request("GET", f"/api/v1/workspaces/{workspace_id}/folders/root", token=self.user_token, retryable=True))["folderId"]

    def upload(self, workspace_id, parent_id, raw_path, title, idempotency_key):
        boundary = "----hexworkspace-eval-" + uuid.uuid4().hex
        mime = mimetypes.guess_type(raw_path.name)[0] or "application/octet-stream"
        file_bytes = raw_path.read_bytes()
        fields = [
            f"--{boundary}\r\nContent-Disposition: form-data; name=\"parentId\"\r\n\r\n{parent_id}\r\n".encode(),
            (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{raw_path.name}\"\r\n"
             f"Content-Type: {mime}\r\n\r\n").encode() + file_bytes + b"\r\n",
            f"--{boundary}--\r\n".encode(),
        ]
        payload = self.request("POST", f"/api/v1/workspaces/{workspace_id}/documents", body=b"".join(fields),
                               content_type=f"multipart/form-data; boundary={boundary}", token=self.user_token,
                               extra_headers={"Idempotency-Key": idempotency_key})
        return self.unwrap(payload)

    def readiness(self, workspace_id, document_codes):
        payload = json.dumps({"workspaceId": workspace_id, "documentCodes": document_codes}, ensure_ascii=False).encode()
        response = self.request("POST", "/internal/v1/evaluation/retrieval/readiness", body=payload,
                                content_type="application/json", token=self.evaluation_token, retryable=True)
        try:
            return self.internal_field(response, "documents", "/internal/v1/evaluation/retrieval/readiness")
        except ApiError as exception:
            # 9999 是 WS GlobalExceptionHandler 的通用异常 envelope。readiness 仅是等待
            # Active Generation 的观测操作，故在整体 poll timeout 内允许它暂态重试。
            if response.get("code") == "9999":
                raise TransientReadinessError(str(exception)) from exception
            raise

    def active_chunks(self, workspace_id, document_codes):
        payload = json.dumps({"workspaceId": workspace_id, "documentCodes": document_codes}, ensure_ascii=False).encode()
        response = self.request("POST", "/internal/v1/evaluation/retrieval/chunks", body=payload,
                                content_type="application/json", token=self.evaluation_token, retryable=True)
        return self.internal_field(response, "chunks", "/internal/v1/evaluation/retrieval/chunks")

    def retrieve(self, workspace_id, question, candidate_limit, top_k):
        payload = json.dumps({"workspaceId": workspace_id, "query": question,
                              "candidateLimit": candidate_limit, "topK": top_k}, ensure_ascii=False).encode()
        return self.request("POST", "/internal/v1/evaluation/retrieval", body=payload,
                            content_type="application/json", token=self.evaluation_token, retryable=True)

    def retrieve_candidates(self, workspace_id, question, candidate_limit, top_k):
        """Read Dense candidate diagnostics without invoking the service-side reranker."""
        payload = json.dumps({"workspaceId": workspace_id, "query": question,
                              "candidateLimit": candidate_limit, "topK": top_k}, ensure_ascii=False).encode()
        return self.request("POST", "/internal/v1/evaluation/retrieval/candidates", body=payload,
                            content_type="application/json", token=self.evaluation_token, retryable=True)

    def rerank_candidates(self, workspace_id, question, candidates, top_k, input_variant=None):
        """Run only the current service-side reranker for verified Active Generation chunks.

        This is evaluation-only: callers submit identities and source ranks, while
        WS reloads candidate content from the authoritative Chunk Artifact.
        """
        payload_body = {"workspaceId": workspace_id, "query": question,
                        "candidates": candidates, "topK": top_k}
        if input_variant is not None:
            payload_body["inputVariant"] = input_variant
        payload = json.dumps(payload_body, ensure_ascii=False).encode()
        response = self.request("POST", "/internal/v1/evaluation/retrieval/rerank", body=payload,
                                content_type="application/json", token=self.evaluation_token, retryable=True)
        body = response
        if "candidates" not in body and body.get("code") is not None:
            body = self.unwrap(body)
        if not isinstance(body, dict) or "candidates" not in body:
            raise ApiError("unexpected response from /internal/v1/evaluation/retrieval/rerank: "
                           + json.dumps(response, ensure_ascii=False))
        if input_variant is not None and body.get("inputVariant") != input_variant:
            raise ApiError("rerank inputVariant was not acknowledged by Workspace: "
                           f"expected {input_variant}, actual {body.get('inputVariant')!r}; "
                           "please restart WS with the current evaluation endpoint")
        return body["candidates"]


def json_lines(path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def write_json_atomic(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def upload_corpus(api, args, manifest, run_dir):
    root_id = api.root_folder(args.workspace_id)
    known_keys = {item["documentKey"] for item in manifest}
    document_map = {}
    if args.resume_upload_map:
        document_map = json.loads(args.resume_upload_map.read_text(encoding="utf-8"))
        unknown_keys = sorted(set(document_map) - known_keys)
        if unknown_keys:
            raise ApiError(f"resume document-map 包含当前 dataset 不存在的 documentKey: {unknown_keys[:5]}")
        print(f"resuming upload: {len(document_map)}/{len(manifest)} document(s) already mapped")

    map_path = run_dir / "document-map.json"
    write_json_atomic(map_path, document_map)
    current_batch = []
    for index, item in enumerate(manifest, start=1):
        if item["documentKey"] in document_map:
            continue
        raw_path = args.dataset_root / item["rawFile"]
        key = "rag-eval-v2:" + hashlib.sha256(item["rawSha256"].encode()).hexdigest()
        response = api.upload(args.workspace_id, root_id, raw_path, item["title"], key)
        document_map[item["documentKey"]] = {"documentCode": response["documentCode"], "rawFile": item["rawFile"]}
        write_json_atomic(map_path, document_map)
        current_batch.append(item["documentKey"])
        print(f"uploaded {index}/{len(manifest)}: {item['documentKey']} -> {response['documentCode']}")
        if args.upload_batch_size and len(current_batch) >= args.upload_batch_size:
            print(f"waiting for upload batch: {len(current_batch)} document(s)")
            batch_map = {document_key: document_map[document_key] for document_key in current_batch}
            wait_until_ready(api, args, batch_map, run_dir, f"readiness-upload-batch-{index}.json")
            current_batch = []
    if current_batch:
        print(f"waiting for final upload batch: {len(current_batch)} document(s)")
        batch_map = {document_key: document_map[document_key] for document_key in current_batch}
        wait_until_ready(api, args, batch_map, run_dir, "readiness-upload-final-batch.json")
    return document_map


def wait_until_ready(api, args, document_map, run_dir, readiness_filename="readiness.json"):
    reverse = {entry["documentCode"]: key for key, entry in document_map.items()}
    pending = set(reverse)
    latest = {}
    deadline = time.monotonic() + args.poll_timeout
    while pending and time.monotonic() < deadline:
        # 对本轮 pending 做稳定快照；循环中 ready document 会被移除，不能再基于动态集合
        # 重新按 offset 切片，否则可能跳过后续 document。
        pending_snapshot = sorted(pending)
        for batch_start in range(0, len(pending_snapshot), args.readiness_batch_size):
            batch = pending_snapshot[batch_start:batch_start + args.readiness_batch_size]
            try:
                rows = api.readiness(args.workspace_id, batch)
            except TransientReadinessError as exception:
                print(f"readiness 暂态失败，稍后重试 batch={batch[0]}..{batch[-1]}: {exception}")
                continue
            for row in rows:
                latest[reverse[row["documentCode"]]] = row
                if row["ready"]:
                    pending.discard(row["documentCode"])
        if pending:
            print(f"waiting for Active Generation: {len(pending)} document(s) remaining")
            time.sleep(args.poll_interval)
    write_json_atomic(run_dir / readiness_filename, latest)
    if pending:
        raise TimeoutError(f"timed out waiting for Active Generation: {sorted(pending)}")
    return latest


def evaluate(api, args, questions, document_map, run_dir):
    reverse = {entry["documentCode"]: key for key, entry in document_map.items()}
    cases_path = run_dir / "cases.jsonl"
    cases = []
    with cases_path.open("w", encoding="utf-8") as output:
        for index, question in enumerate(questions, start=1):
            started = time.perf_counter()
            try:
                retrieval_response = api.retrieve(
                    args.workspace_id, question["question"], args.candidate_limit, args.top_k)
                hits = retrieval_response["hits"]
                retrieved = []
                for hit in hits:
                    key = reverse.get(hit.get("documentCode"))
                    if key and key not in retrieved:
                        retrieved.append(key)
                golden = {item["documentKey"] for item in question["evidences"]}
                ranks = [rank for rank, key in enumerate(retrieved, 1) if key in golden]
                diagnostics = retrieval_response.get("diagnostics")
                result = {
                    "id": question["id"], "category": question["category"], "question": question["question"],
                    "goldenDocumentKeys": sorted(golden), "retrievedDocumentKeys": retrieved,
                    "goldenRanks": ranks, "hitAtK": bool(ranks),
                    "recallAtK": len(set(retrieved) & golden) / len(golden) if golden else None,
                    "reciprocalRankAtK": 1 / ranks[0] if ranks else 0,
                    "hits": hits, "retrievalTimingMs": retrieval_response.get("timingMs"),
                    "retrievalDiagnostics": diagnostics,
                    "missClassification": classify_miss(golden, diagnostics, reverse),
                    "latencyMs": round((time.perf_counter() - started) * 1000, 2), "status": "SUCCESS",
                }
            except ApiError as exc:
                result = {"id": question["id"], "category": question["category"], "question": question["question"],
                          "status": "ERROR", "error": str(exc), "latencyMs": round((time.perf_counter() - started) * 1000, 2)}
            cases.append(result)
            output.write(json.dumps(result, ensure_ascii=False) + "\n")
            output.flush()
            if index % 10 == 0 or index == len(questions):
                print(f"evaluated {index}/{len(questions)}")
    return cases


def classify_miss(golden, diagnostics, reverse):
    if not golden:
        return "NOT_APPLICABLE"
    if not diagnostics:
        return "DIAGNOSTICS_UNAVAILABLE"
    stages = (
        ("qdrantCandidates", "QDRANT_TOP_CANDIDATE_MISS"),
        ("authorityValidCandidates", "AUTHORITY_FILTERED"),
        ("finalCandidates", "RANKING_MISS"),
    )
    for stage, classification in stages:
        candidates = diagnostics.get(stage, [])
        keys = {reverse.get(candidate.get("documentCode")) for candidate in candidates}
        if not (keys & golden):
            return classification
    return "FINAL_HIT"


def percentile(values, percent):
    if not values:
        return None
    ordered = sorted(values)
    return ordered[round((len(ordered) - 1) * percent / 100)]


def summary(cases, args):
    successful = [case for case in cases if case["status"] == "SUCCESS"]
    answerable = [case for case in successful if case.get("goldenDocumentKeys")]
    latencies = [case["latencyMs"] for case in successful]
    stage_timings = {
        "embeddingMs": [case["retrievalTimingMs"]["embeddingMs"] for case in successful if case.get("retrievalTimingMs")],
        "qdrantMs": [case["retrievalTimingMs"]["qdrantMs"] for case in successful if case.get("retrievalTimingMs")],
        "authorityInitialMs": [case["retrievalTimingMs"]["authorityInitialMs"] for case in successful if case.get("retrievalTimingMs")],
        "rerankMs": [case["retrievalTimingMs"].get("rerankMs", 0) for case in successful if case.get("retrievalTimingMs")],
        "authorityRecheckMs": [case["retrievalTimingMs"]["authorityRecheckMs"] for case in successful if case.get("retrievalTimingMs")],
        "totalMs": [case["retrievalTimingMs"]["totalMs"] for case in successful if case.get("retrievalTimingMs")],
    }
    by_category = {}
    for category in sorted({case["category"] for case in successful}):
        category_cases = [case for case in successful if case["category"] == category]
        category_answerable = [case for case in category_cases if case.get("goldenDocumentKeys")]
        by_category[category] = {
            "questionCount": len(category_cases),
            "hitRateAtK": sum(case["hitAtK"] for case in category_answerable) / len(category_answerable)
            if category_answerable else None,
            "recallAtK": statistics.mean(case["recallAtK"] for case in category_answerable)
            if category_answerable else None,
            "mrrAtK": statistics.mean(case["reciprocalRankAtK"] for case in category_answerable)
            if category_answerable else None,
            "latencyMs": {"p50": percentile([case["latencyMs"] for case in category_cases], 50),
                          "p95": percentile([case["latencyMs"] for case in category_cases], 95)},
        }
    missed_cases = [{"id": case["id"], "category": case["category"], "question": case["question"],
                     "goldenDocumentKeys": case["goldenDocumentKeys"],
                     "retrievedDocumentKeys": case["retrievedDocumentKeys"],
                     "missClassification": case.get("missClassification"),
                     "retrievalDiagnostics": case.get("retrievalDiagnostics")}
                    for case in successful if case.get("goldenDocumentKeys") and not case["hitAtK"]]
    return {
        "datasetVersion": args.dataset_root.name, "split": args.split,
        "candidateLimit": args.candidate_limit, "topK": args.top_k,
        "uploadBatchSize": args.upload_batch_size,
        "questionCount": len(cases), "successfulQuestionCount": len(successful),
        "errorQuestionCount": len(cases) - len(successful),
        f"hitRateAt{args.top_k}": sum(case["hitAtK"] for case in answerable) / len(answerable) if answerable else None,
        f"recallAt{args.top_k}": statistics.mean(case["recallAtK"] for case in answerable) if answerable else None,
        f"mrrAt{args.top_k}": statistics.mean(case["reciprocalRankAtK"] for case in answerable) if answerable else None,
        "latencyMs": {"p50": percentile(latencies, 50), "p95": percentile(latencies, 95), "p99": percentile(latencies, 99)},
        "serverTimingMs": {stage: {"p50": percentile(values, 50), "p95": percentile(values, 95),
                                    "p99": percentile(values, 99)} for stage, values in stage_timings.items()},
        "byCategory": by_category,
        "missedCases": missed_cases,
    }


def main():
    args = parse_args()
    run_dir = PROJECT_ROOT / "rag-eval" / "runs" / args.run_id
    run_dir.mkdir(parents=True, exist_ok=True)
    manifest = json_lines(args.dataset_root / "manifest" / "documents.jsonl")
    api = WorkspaceApi(args)
    if args.upload:
        document_map = upload_corpus(api, args, manifest, run_dir)
    else:
        document_map = json.loads(args.document_map.read_text(encoding="utf-8"))
        shutil.copy2(args.document_map, run_dir / "document-map.json")
    readiness = wait_until_ready(api, args, document_map, run_dir)
    questions = json_lines(args.dataset_root / "qa" / f"{args.split}.jsonl")
    cases = evaluate(api, args, questions, document_map, run_dir)
    report = summary(cases, args) | {"activeGenerationReadiness": readiness}
    report["configSha256"] = hashlib.sha256(args.config.read_bytes()).hexdigest()
    (run_dir / "summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    shutil.copy2(args.config, run_dir / args.config.name)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
