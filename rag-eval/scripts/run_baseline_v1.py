#!/usr/bin/env python3
"""Run the dataset-v1.0 baseline against a running hex-doc instance.

The script intentionally uses only the public HTTP APIs.  A fresh run must use
``--upload`` once (preferably against a dedicated evaluation tenant); later runs
should reuse the generated ``document-map.json`` to avoid duplicate documents.
"""
from __future__ import annotations

import argparse
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
DEFAULT_BASE_URL = os.getenv("HEX_DOC_BASE_URL", "http://127.0.0.1:8098")
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
    parser.add_argument("--request-timeout", type=float, default=60.0)
    parser.add_argument("--skip-ask", action="store_true", help="run search metrics only")
    args = parser.parse_args()
    if args.tenant_id <= 0 or not args.token:
        parser.error("请提供 --tenant-id 和 --token，或设置 HEX_DOC_TENANT_ID / HEX_DOC_TOKEN")
    if args.upload == bool(args.document_map):
        parser.error("首次运行使用 --upload；复用已有语料时只使用 --document-map PATH")
    return args


class HexDocApi:
    def __init__(self, base_url, token, timeout):
        self.base_url = base_url.rstrip("/")
        self.headers = {"Authorization": f"Bearer {token}"}
        self.timeout = timeout

    def request(self, method, path, *, body=None, content_type=None):
        headers = dict(self.headers)
        if content_type:
            headers["Content-Type"] = content_type
        request = urllib.request.Request(self.base_url + path, data=body, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError) as exc:
            raise ApiError(f"HTTP {method} {path} failed: {exc}") from exc
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


def wait_until_index_visible(api, args, mapping, manifest):
    """Confirm each freshly parsed document is observable through the public search API."""
    deadline = time.monotonic() + args.poll_timeout
    pending = {document["documentKey"]: (document, index)
               for index, document in enumerate(manifest, start=1)}
    while pending and time.monotonic() < deadline:
        for key, (document, index) in list(pending.items()):
            rule_code = f"{document['domain'].upper()}-{index:03d}"
            result = api.search(args.tenant_id, f"规则 {rule_code} 的默认执行窗口是什么？")
            hit_codes = {hit.get("documentCode") for hit in (result or {}).get("hits", [])}
            if mapping[key]["documentCode"] in hit_codes:
                pending.pop(key)
        if pending:
            print(f"waiting for index visibility: {len(pending)} document(s) remaining")
            time.sleep(args.poll_interval)
    if pending:
        raise TimeoutError("documents parsed but not visible through knowledge search: " + ", ".join(sorted(pending)))


def evaluate(api, args, dataset_root, mapping, questions, run_dir):
    reverse = {value["documentCode"]: key for key, value in mapping.items()}
    results = []
    for index, item in enumerate(questions, start=1):
        started = time.perf_counter()
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
        results.append({
            "id": item["id"], "question": item["question"], "category": item["category"],
            "difficulty": item["difficulty"], "answerable": item["answerable"],
            "goldenDocumentKeys": sorted(golden), "retrievedDocumentKeys": retrieved_keys,
            "goldenRanks": ranks, "hitAt6": bool(ranks), "recallAt6": len(recalled) / len(golden) if golden else None,
            "reciprocalRankAt6": 1 / ranks[0] if ranks else 0,
            "answer": answer, "answerCorrect": answer_correct,
            "retrievedSources": hits, "usedSources": (ask_data or {}).get("sources", []) if ask_data else [],
            "searchMs": search_ms, "askMs": ask_ms,
            "totalMs": round((time.perf_counter() - started) * 1000, 2),
        })
        if index % 10 == 0 or index == len(questions):
            print(f"evaluated {index}/{len(questions)}")
    path = run_dir / "cases.jsonl"
    path.write_text("\n".join(json.dumps(x, ensure_ascii=False) for x in results) + "\n", encoding="utf-8")
    return results


def aggregate(results, args, run_dir):
    total = len(results)
    answerable = [x for x in results if x["answerable"]]
    unanswerable = [x for x in results if not x["answerable"]]
    hit = [x for x in answerable if x["hitAt6"]]
    rr = [x["reciprocalRankAt6"] for x in answerable]
    recall = [x["recallAt6"] for x in answerable]
    correctness = [x["answerCorrect"] for x in results if x["answerCorrect"] is not None]
    latencies = sorted(x["totalMs"] for x in results)
    def percentile(values, p):
        if not values: return None
        return values[min(len(values) - 1, int(round((p / 100) * (len(values) - 1))))]
    summary = {
        "runId": args.run_id, "dataset": "dataset-v1.0", "split": "test", "tenantId": args.tenant_id,
        "questionCount": total, "answerableCount": len(answerable), "unanswerableCount": len(unanswerable),
        "hitRateAt6": round(len(hit) / len(answerable), 4) if answerable else None,
        "recallAt6": round(sum(recall) / len(recall), 4) if recall else None,
        "mrrAt6": round(sum(rr) / len(rr), 4) if rr else None,
        "answerCorrectness": round(sum(correctness) / len(correctness), 4) if correctness else None,
        "refusalRate": round(sum(x["answerCorrect"] for x in unanswerable) / len(unanswerable), 4)
            if unanswerable and not args.skip_ask else None,
        "p50TotalMs": percentile(latencies, 50), "p95TotalMs": percentile(latencies, 95),
        "skipAsk": args.skip_ask,
    }
    (run_dir / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False, indent=2))


def main():
    args = parse_args()
    root = args.dataset_root.resolve()
    manifest = load_manifest(root)
    questions = [json.loads(line) for line in (root / "qa" / "test.jsonl").read_text(encoding="utf-8").splitlines()]
    run_dir = PROJECT_ROOT / "rag-eval" / "runs" / args.run_id
    run_dir.mkdir(parents=True, exist_ok=False)
    config_path = PROJECT_ROOT / "rag-eval" / "configs" / "baseline-v1.yml"
    if config_path.exists():
        shutil.copyfile(config_path, run_dir / "baseline-v1.yml")
    api = HexDocApi(args.base_url, args.token, args.request_timeout)
    if args.upload:
        mapping = upload_corpus(api, args, manifest, run_dir)
    else:
        mapping = json.loads(args.document_map.read_text(encoding="utf-8"))
    statuses = wait_until_ready(api, args, mapping)
    (run_dir / "processing-status.json").write_text(json.dumps(statuses, ensure_ascii=False, indent=2, default=str) + "\n", encoding="utf-8")
    wait_until_index_visible(api, args, mapping, manifest)
    results = evaluate(api, args, root, mapping, questions, run_dir)
    aggregate(results, args, run_dir)


if __name__ == "__main__":
    main()
