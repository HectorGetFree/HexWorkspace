#!/usr/bin/env python3
"""Run the dataset-v1.0 baseline against a running hex-doc instance.

The script intentionally uses only the public HTTP APIs.  A fresh run must use
``--upload`` once (preferably against a dedicated evaluation tenant); later runs
should reuse the generated ``document-map.json`` to avoid duplicate documents.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import mimetypes
import os
import shutil
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DATASET_ROOT = PROJECT_ROOT / "rag-eval" / "dataset-v1.0"
DEFAULT_BASE_URL = os.getenv("HEX_DOC_BASE_URL", "http://localhost:8098")
REFUSAL_MARKERS = ("无法回答", "无法提供", "信息不足", "未提供足够", "不能回答", "无法确定")


class ApiError(RuntimeError):
    pass


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL)
    parser.add_argument("--tenant-id", type=int, default=int(os.getenv("HEX_DOC_TENANT_ID", "0")))
    parser.add_argument("--token", default=os.getenv("HEX_DOC_TOKEN", ""))
    parser.add_argument("--dataset-root", type=Path, default=DATASET_ROOT)
    parser.add_argument("--run-id", default=datetime.now(timezone.utc).strftime("baseline-v1-%Y%m%dT%H%M%SZ"))
    parser.add_argument("--upload", action="store_true", help="upload the raw corpus and create a document map")
    parser.add_argument("--document-map", type=Path, help="map created by a previous run; avoids duplicate uploads")
    parser.add_argument("--poll-interval", type=float, default=2.0)
    parser.add_argument("--poll-timeout", type=float, default=300.0)
    parser.add_argument("--index-settle-seconds", type=float, default=5.0,
                        help="all versions parsed successfully after waiting before evaluation starts")
    parser.add_argument("--request-timeout", type=float, default=90.0)
    parser.add_argument("--http-retries", type=int, default=2,
                        help="retry count for idempotent GET requests after transient HTTP failures")
    parser.add_argument("--skip-ask", action="store_true", help="run search metrics only")
    args = parser.parse_args()
    if args.tenant_id <= 0 or not args.token:
        parser.error("请提供 --tenant-id 和 --token，或设置 HEX_DOC_TENANT_ID / HEX_DOC_TOKEN")
    if args.upload == bool(args.document_map):
        parser.error("首次运行使用 --upload；复用已有语料时只使用 --document-map PATH")
    return args


class HexDocApi:
    def __init__(self, base_url, token, timeout, retries):
        self.base_url = base_url.rstrip("/")
        self.headers = {"Authorization": f"Bearer {token}"}
        self.timeout = timeout
        self.retries = retries
        parsed_url = urllib.parse.urlparse(self.base_url)
        # 评测默认访问本机 Spring Boot；不要让 HTTP(S)_PROXY 把 localhost 转发到网关。
        if parsed_url.hostname in {"localhost", "127.0.0.1", "::1"}:
            self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        else:
            self.opener = urllib.request.build_opener()

    def request(self, method, path, *, body=None, content_type=None):
        headers = dict(self.headers)
        if content_type:
            headers["Content-Type"] = content_type
        request = urllib.request.Request(self.base_url + path, data=body, headers=headers, method=method)
        retry_count = self.retries if method == "GET" else 0
        for attempt in range(retry_count + 1):
            try:
                with self.opener.open(request, timeout=self.timeout) as response:
                    payload = json.loads(response.read().decode("utf-8"))
                break
            except urllib.error.HTTPError as exc:
                try:
                    response_body = exc.read().decode("utf-8", errors="replace")
                except OSError:
                    response_body = "<unavailable>"
                if 500 <= exc.code < 600 and attempt < retry_count:
                    time.sleep(attempt + 1)
                    continue
                raise ApiError(
                    f"HTTP {method} {request.full_url} failed: {exc.code} {exc.reason}; body={response_body}"
                ) from exc
            except (urllib.error.URLError, TimeoutError) as exc:
                if attempt < retry_count:
                    time.sleep(attempt + 1)
                    continue
                raise ApiError(f"HTTP {method} {request.full_url} failed: {exc}") from exc
        if payload.get("code") != "0000":
            raise ApiError(f"HTTP {method} {path} returned {payload.get('code')}: {payload.get('info')}")
        return payload.get("data")

    def upload(self, tenant_id, raw_path, title):
        boundary = "----hexdoc-eval-" + uuid.uuid4().hex
        mime = mimetypes.guess_type(raw_path.name)[0] or "application/octet-stream"
        content = raw_path.read_bytes()
        chunks = []
        chunks.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\n{title}\r\n".encode())
        chunks.append((f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"{raw_path.name}\"\r\n"
                       f"Content-Type: {mime}\r\n\r\n").encode() + content + b"\r\n")
        chunks.append(f"--{boundary}--\r\n".encode())
        return self.request("POST", f"/api/v1/tenants/{tenant_id}/documents",
                            body=b"".join(chunks), content_type=f"multipart/form-data; boundary={boundary}")

    def processing_status(self, tenant_id, document_code):
        return self.request("GET", f"/api/v1/tenants/{tenant_id}/documents/{document_code}/processing-status")

    def search(self, tenant_id, question):
        encoded = urllib.parse.quote(question, safe="")
        return self.request("GET", f"/api/v1/tenants/{tenant_id}/knowledge/search?question={encoded}")

    def ask(self, tenant_id, question):
        body = json.dumps({"question": question}, ensure_ascii=False).encode("utf-8")
        return self.request("POST", f"/api/v1/tenants/{tenant_id}/knowledge/ask",
                            body=body, content_type="application/json")

    def runtime_info(self):
        return self.request("GET", "/api/v1/ai/runtime-info")


def load_manifest(root):
    return [json.loads(line) for line in (root / "manifest" / "documents.jsonl").read_text(encoding="utf-8").splitlines()]


def upload_corpus(api, args, manifest, run_dir):
    mapping = {}
    for index, document in enumerate(manifest, start=1):
        raw_path = args.dataset_root / document["rawFile"]
        response = api.upload(args.tenant_id, raw_path, document["title"])
        mapping[document["documentKey"]] = {
            "documentCode": response["documentCode"],
            "versionNumber": response["versionNumber"],
            "rawFile": document["rawFile"],
        }
        print(f"uploaded {index}/{len(manifest)}: {document['documentKey']} -> {response['documentCode']}")
    path = run_dir / "document-map.json"
    path.write_text(json.dumps(mapping, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return mapping


def wait_until_ready(api, args, mapping):
    deadline = time.monotonic() + args.poll_timeout
    pending = set(mapping)
    latest = {}
    while pending and time.monotonic() < deadline:
        for key in list(pending):
            code = mapping[key]["documentCode"]
            status = api.processing_status(args.tenant_id, code)
            latest[key] = status
            state = status.get("parseStatus")
            if state == "SUCCESS":
                pending.remove(key)
            elif state == "FAILED":
                raise ApiError(f"document {key} ({code}) parse failed: {status}")
        if pending:
            print(f"waiting for parsing: {len(pending)} document(s) remaining")
            time.sleep(args.poll_interval)
    if pending:
        raise TimeoutError(f"timed out waiting for parsing: {sorted(pending)}")
    return latest


def evaluate(api, args, dataset_root, mapping, questions, run_dir):
    reverse = {value["documentCode"]: key for key, value in mapping.items()}
    result_path = run_dir / "cases.jsonl"
    existing = {}
    if result_path.exists():
        for line in result_path.read_text(encoding="utf-8").splitlines():
            row = json.loads(line)
            existing[row["id"]] = row
        # 同一 run 恢复时只保留每道题最后一个结果，避免重试造成重复统计。
        result_path.write_text("\n".join(json.dumps(x, ensure_ascii=False) for x in existing.values()) + "\n",
                               encoding="utf-8")
    results = list(existing.values())
    # 只有成功记录才算完成；ERROR 记录在下次使用同一 run-id 时自动重试。
    completed_ids = {item["id"] for item in results if item.get("status") == "SUCCESS"}
    output = result_path.open("a", encoding="utf-8")
    for index, item in enumerate(questions, start=1):
        if item["id"] in completed_ids:
            continue
        started = time.perf_counter()
        try:
            search_started = time.perf_counter()
            search_data = api.search(args.tenant_id, item["question"])
            search_ms = round((time.perf_counter() - search_started) * 1000, 2)
            hits = search_data.get("hits", []) if search_data else []
            retrieved_keys = [reverse.get(hit.get("documentCode"), "unknown") for hit in hits]
            golden = {e["documentKey"] for e in item["evidences"]}
            ranks = [rank for rank, key in enumerate(retrieved_keys, start=1) if key in golden]
            recalled = set(retrieved_keys) & golden
            ask_data = None
            answer = None
            ask_ms = None
            if not args.skip_ask:
                ask_started = time.perf_counter()
                ask_data = api.ask(args.tenant_id, item["question"])
                ask_ms = round((time.perf_counter() - ask_started) * 1000, 2)
                answer = (ask_data or {}).get("answer")
            answer_correct = None if args.skip_ask else (
                all(token in (answer or "") for token in item.get("mustContain", []))
                if item["answerable"] else any(marker in (answer or "") for marker in REFUSAL_MARKERS)
            )
            result = {
            "id": item["id"], "question": item["question"], "category": item["category"],
            "difficulty": item["difficulty"], "answerable": item["answerable"],
            "goldenDocumentKeys": sorted(golden), "retrievedDocumentKeys": retrieved_keys,
            "goldenRanks": ranks, "hitAt6": bool(ranks), "recallAt6": len(recalled) / len(golden) if golden else None,
            "reciprocalRankAt6": 1 / ranks[0] if ranks else 0,
            "answer": answer, "answerCorrect": answer_correct,
            "retrievedSources": hits, "usedSources": (ask_data or {}).get("sources", []) if ask_data else [],
            "searchMs": search_ms, "askMs": ask_ms,
            "totalMs": round((time.perf_counter() - started) * 1000, 2),
            "status": "SUCCESS",
            }
        except ApiError as exception:
            result = {
                "id": item["id"], "question": item["question"], "category": item["category"],
                "difficulty": item["difficulty"], "answerable": item["answerable"],
                "status": "ERROR", "error": str(exception),
                "totalMs": round((time.perf_counter() - started) * 1000, 2),
            }
            print(f"evaluation request failed for {item['id']}; recorded error and continuing")
        existing[item["id"]] = result
        output.write(json.dumps(result, ensure_ascii=False) + "\n")
        output.flush()
        if index % 10 == 0 or index == len(questions):
            print(f"evaluated {index}/{len(questions)}")
    output.close()
    return list(existing.values())


def percentile(values, p):
    if not values:
        return None
    return values[min(len(values) - 1, int(round((p / 100) * (len(values) - 1))))]


def metric_summary(results, skip_ask):
    total = len(results)
    successful = [x for x in results if x.get("status") == "SUCCESS"]
    answerable = [x for x in successful if x["answerable"]]
    unanswerable = [x for x in successful if not x["answerable"]]
    hit = [x for x in answerable if x["hitAt6"]]
    rr = [x["reciprocalRankAt6"] for x in answerable]
    recall = [x["recallAt6"] for x in answerable]
    correctness = [x["answerCorrect"] for x in successful if x.get("answerCorrect") is not None]
    latencies = sorted(x["totalMs"] for x in successful)
    return {
        "questionCount": total, "successfulQuestionCount": len(successful), "errorQuestionCount": total - len(successful),
        "answerableCount": len(answerable), "unanswerableCount": len(unanswerable),
        "hitRateAt6": round(len(hit) / len(answerable), 4) if answerable else None,
        "recallAt6": round(sum(recall) / len(recall), 4) if recall else None,
        "mrrAt6": round(sum(rr) / len(rr), 4) if rr else None,
        "answerCorrectness": round(sum(correctness) / len(correctness), 4) if correctness else None,
        "refusalRate": round(sum(x["answerCorrect"] for x in unanswerable) / len(unanswerable), 4)
            if unanswerable and not skip_ask else None,
        "p50TotalMs": percentile(latencies, 50), "p95TotalMs": percentile(latencies, 95),
    }


def aggregate(results, args, run_dir, runtime_info, config_sha256):
    summary = {
        "runId": args.run_id,
        "dataset": "dataset-v1.0",
        "split": "test",
        "tenantId": args.tenant_id,
        "runtime": {
            "hexDocBaseUrl": args.base_url,
            "chatModel": runtime_info.get("chatModel"),
            "embeddingModel": runtime_info.get("embeddingModel"),
            "requestTimeoutSeconds": args.request_timeout,
            "httpRetries": args.http_retries,
            "indexSettleSeconds": args.index_settle_seconds,
        },
        "configurationSnapshot": {
            "file": "baseline-v1.yml",
            "sha256": config_sha256,
        },
        **metric_summary(results, args.skip_ask),
        "byCategory": {},
        "skipAsk": args.skip_ask,
    }
    categories = sorted({item["category"] for item in results})
    for category in categories:
        summary["byCategory"][category] = metric_summary(
            [item for item in results if item["category"] == category], args.skip_ask
        )
    (run_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


def main():
    args = parse_args()
    root = args.dataset_root.resolve()
    manifest = load_manifest(root)
    questions = [json.loads(line) for line in (root / "qa" / "test.jsonl").read_text(encoding="utf-8").splitlines()]
    run_dir = PROJECT_ROOT / "rag-eval" / "runs" / args.run_id
    run_dir.mkdir(parents=True, exist_ok=True)
    config_path = PROJECT_ROOT / "rag-eval" / "configs" / "baseline-v1.yml"
    config_sha256 = None
    if config_path.exists():
        shutil.copyfile(config_path, run_dir / "baseline-v1.yml")
        config_sha256 = hashlib.sha256(config_path.read_bytes()).hexdigest()
    api = HexDocApi(args.base_url, args.token, args.request_timeout, args.http_retries)
    runtime_info = api.runtime_info()
    if args.upload:
        mapping = upload_corpus(api, args, manifest, run_dir)
    else:
        mapping = json.loads(args.document_map.read_text(encoding="utf-8"))
    statuses = wait_until_ready(api, args, mapping)
    (run_dir / "processing-status.json").write_text(json.dumps(statuses, ensure_ascii=False, indent=2, default=str) + "\n", encoding="utf-8")
    if args.index_settle_seconds > 0:
        print(f"all documents parsed; waiting {args.index_settle_seconds:g}s for the in-process index to settle")
        time.sleep(args.index_settle_seconds)
    results = evaluate(api, args, root, mapping, questions, run_dir)
    aggregate(results, args, run_dir, runtime_info, config_sha256)


if __name__ == "__main__":
    main()
