#!/usr/bin/env python3
"""Run the frozen RAG Evaluation V2 draft against an isolated Workspace.

The runner owns only evaluation setup and reporting. Production retrieval remains
the source of results; Gold labels are never inferred from those results.
"""
from __future__ import annotations

import argparse
import hashlib
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


DEFAULT_ROOT = PROJECT_ROOT / "rag-eval" / "dataset-v2-draft"


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.getenv("HEX_WORKSPACE_BASE_URL", "http://127.0.0.1:8098"))
    parser.add_argument("--workspace-id", type=int, default=int(os.getenv("HEX_WORKSPACE_WORKSPACE_ID", "0")))
    parser.add_argument("--evaluation-token", default=os.getenv("HEXWORKSPACE_RAG_EVALUATION_TOKEN", ""))
    parser.add_argument("--user-token", default=os.getenv("HEX_WORKSPACE_TOKEN", ""), help="仅 --upload 需要")
    parser.add_argument("--dataset-root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--split", choices=("train", "dev", "test"), default="test")
    parser.add_argument(
        "--cases-file",
        type=Path,
        help="覆盖默认 split；用于可审计的 draft/evidence 文件试跑，不改变正式 split。",
    )
    parser.add_argument("--candidate-limit", type=int, default=30)
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--run-id", default=datetime.now(timezone.utc).strftime("rag-eval-v2-%Y%m%dT%H%M%SZ"))
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--upload", action="store_true", help="将冻结 manifest 语料上传到目标独立 Workspace")
    group.add_argument("--document-map", type=Path, help="复用已上传的 document-map.json")
    parser.add_argument("--poll-interval", type=float, default=2.0)
    parser.add_argument("--poll-timeout", type=float, default=900.0)
    parser.add_argument("--request-timeout", type=float, default=60.0)
    parser.add_argument("--http-retries", type=int, default=2)
    parser.add_argument(
        "--allow-unreviewed",
        action="store_true",
        help="仅用于候选集连通性试跑；不得将结果作为冻结 benchmark 指标。",
    )
    args = parser.parse_args()
    if args.workspace_id <= 0 or not args.evaluation_token:
        parser.error("请提供 --workspace-id / --evaluation-token")
    if args.upload and not args.user_token:
        parser.error("--upload 需要 --user-token 或 HEX_WORKSPACE_TOKEN")
    if not 1 <= args.candidate_limit <= 100 or not 1 <= args.top_k <= 20:
        parser.error("candidate-limit 必须为 1..100，top-k 必须为 1..20")
    return args


def read_lines(path: Path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def cases_path(root: Path, split: str, explicit_path: Path | None = None) -> Path:
    """Return the current V2 split file, retaining the historical sample fallback."""
    if explicit_path is not None:
        if not explicit_path.is_file():
            raise ApiError(f"case 文件不存在: {explicit_path}")
        return explicit_path
    split_path = root / "cases" / f"{split}.jsonl"
    return split_path if split_path.is_file() else root / "cases" / "sample.jsonl"


def active_cases(cases):
    """Exclude explicitly rejected cases without hiding unreviewed ones.

    A formal run still rejects UNREVIEWED/REVIEWED cases in map_quotes. REJECTED
    records remain in the dataset for audit history but are not scored.
    """
    return [case for case in cases if case.get("reviewStatus") != "REJECTED"]


def manifest_rows(root: Path):
    rows = read_lines(root / "corpus" / "manifest.jsonl")
    for row in rows:
        source = PROJECT_ROOT / row["sourceFile"]
        actual = hashlib.sha256(source.read_bytes()).hexdigest()
        if actual != row["sourceSha256"]:
            raise ApiError(f"冻结语料已变化: {row['documentId']} expected={row['sourceSha256']} actual={actual}")
        row["sourcePath"] = str(source)
    return rows


def upload_corpus(api, args, rows, run_dir):
    root_folder_id = api.root_folder(args.workspace_id)
    document_map = {}
    dataset_version = json.loads((args.dataset_root / "dataset.json").read_text(encoding="utf-8"))["datasetVersion"]
    for index, row in enumerate(rows, start=1):
        source = Path(row["sourcePath"])
        # Document Domain 限制 Idempotency-Key 不超过 128 字符。数据集版本和
        # 文档标识可能增长，故将完整稳定输入哈希成固定长度 key，而不是把原文拼接进 header。
        identity = f"{dataset_version}:{row['documentId']}:{row['sourceSha256']}"
        key = "rag-eval-v2:" + hashlib.sha256(identity.encode("utf-8")).hexdigest()
        result = api.upload(args.workspace_id, root_folder_id, source, row["title"], key)
        document_map[row["documentId"]] = {
            "documentCode": result["documentCode"], "documentId": result.get("documentId"),
            "sourceFile": row["sourceFile"], "sourceSha256": row["sourceSha256"],
        }
        print(f"uploaded {index}/{len(rows)}: {row['documentId']} -> {result['documentCode']}")
    (run_dir / "document-map.json").write_text(json.dumps(document_map, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return document_map


def wait_ready(api, args, document_map, run_dir):
    reverse = {value["documentCode"]: key for key, value in document_map.items()}
    pending = set(reverse)
    latest = {}
    deadline = time.monotonic() + args.poll_timeout
    while pending and time.monotonic() < deadline:
        batch = list(pending)
        for offset in range(0, len(batch), 50):
            for row in api.readiness(args.workspace_id, batch[offset:offset + 50]):
                latest[reverse[row["documentCode"]]] = row
                if row["ready"]:
                    pending.discard(row["documentCode"])
        if pending:
            print(f"waiting for Active Generation: {len(pending)} document(s) remaining")
            time.sleep(args.poll_interval)
    (run_dir / "readiness.json").write_text(json.dumps(latest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if pending:
        raise TimeoutError(f"timed out waiting for Active Generation: {sorted(pending)}")
    return latest


def map_quotes(api, args, root, document_map, readiness, run_dir):
    canonical = read_lines(root / "corpus" / "chunks.jsonl")
    # New datasets use one JSONL file per split.  Keep the original sample
    # fallback so historical draft runs remain reproducible.
    cases = active_cases(read_lines(cases_path(root, args.split, getattr(args, "cases_file", None))))
    unreviewed = [case["queryId"] for case in cases if case.get("reviewStatus") != "VERIFIED"]
    if unreviewed and not args.allow_unreviewed:
        raise ApiError(
            f"{len(unreviewed)} 个 case 未 VERIFIED，拒绝生成正式质量指标；"
            "人工审核后重试，或仅做连通性试跑时显式传入 --allow-unreviewed"
        )
    quotes_by_chunk = {}
    for case in cases:
        for group in evidence_requirement_groups(case):
            for evidence in group["evidence"]:
                quotes_by_chunk.setdefault(evidence["chunkId"], set()).add(evidence["quote"])
        for evidence in case.get("evidence", []):
            quotes_by_chunk.setdefault(evidence["chunkId"], set()).add(evidence["quote"])
    # Runtime chunking is intentionally independent from the corpus-side
    # canonical anchoring. Only evidence that contributes to a scored case is
    # a Gold contract: requiring every heading-level source section to be
    # fully contained in a runtime chunk incorrectly rejects long documents.
    canonical_by_id = {row["chunkId"]: row for row in canonical}
    missing_anchors = sorted(set(quotes_by_chunk) - set(canonical_by_id))
    if missing_anchors:
        raise ApiError(f"证据引用了不存在的 canonical anchor: {missing_anchors}")
    by_document = {}
    for chunk_id in quotes_by_chunk:
        row = canonical_by_id[chunk_id]
        by_document.setdefault(row["documentId"], []).append(row)
    codes = [value["documentCode"] for value in document_map.values()]
    runtime = []
    for offset in range(0, len(codes), 50):
        runtime.extend(api.active_chunks(args.workspace_id, codes[offset:offset + 50]))
    runtime_by_code = {}
    for chunk in runtime:
        runtime_by_code.setdefault(chunk["documentCode"], []).append(chunk)
    mapping = []
    failures = []
    for document_id, anchors in by_document.items():
        code = document_map[document_id]["documentCode"]
        chunks = runtime_by_code.get(code, [])
        for anchor in anchors:
            full_matches = [chunk for chunk in chunks if anchor["content"] in chunk.get("content", "")]
            # A section anchor may be split by production chunking; quote matching is the contract.
            evidence_matches = [chunk for chunk in chunks if any(
                quote_text in chunk.get("content", "")
                for quote_text in quotes_by_chunk.get(anchor["chunkId"], set())
            )]
            matched = full_matches or evidence_matches
            item = {
                "documentId": document_id, "documentCode": code, "canonicalChunkId": anchor["chunkId"],
                "generationId": readiness[document_id]["generationId"],
                "runtimeChunkNos": [chunk["chunkNo"] for chunk in matched],
                "matchType": "full_anchor" if full_matches else ("quote" if evidence_matches else "UNMAPPED"),
            }
            mapping.append(item)
            if not matched:
                failures.append(item)
    (run_dir / "quote-to-runtime-chunk.json").write_text(json.dumps(mapping, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if failures:
        raise ApiError(f"{len(failures)} canonical anchors could not map to runtime chunks; see quote-to-runtime-chunk.json")
    return mapping


def map_retrieved_document(hit, reverse):
    return reverse.get(hit.get("documentCode"))


def runtime_chunk_key(document_code, generation_id, chunk_no):
    """Build the immutable identity shared by an Active Generation chunk and a hit."""
    return (document_code, generation_id, int(chunk_no))


def evidence_requirement_groups(question):
    """Return AND-ed requirements whose evidence members are ANY alternatives.

    Older datasets have a flat relevantChunkIds list. Preserve their historical
    meaning by treating every legacy chunk as an independent required group.
    """
    groups = question.get("evidenceRequirementGroups")
    if groups is not None:
        return groups
    quotes_by_chunk = {}
    for evidence in question.get("evidence", []):
        quotes_by_chunk.setdefault(evidence["chunkId"], []).append(evidence)
    return [
        {
            "requirementId": f"legacy-{index + 1}",
            "evidence": quotes_by_chunk.get(chunk_id, [{"chunkId": chunk_id, "quote": "", "relevanceGrade": 3}]),
        }
        for index, chunk_id in enumerate(question.get("relevantChunkIds", []))
    ]


def gold_runtime_groups(question, mapping_by_canonical_chunk):
    """Resolve each requirement's alternative canonical evidence to runtime chunks."""
    resolved_groups = []
    missing = []
    for group in evidence_requirement_groups(question):
        alternatives = set()
        for evidence in group["evidence"]:
            canonical_chunk_id = evidence["chunkId"]
            mapping = mapping_by_canonical_chunk.get(canonical_chunk_id)
            if mapping is None or not mapping["runtimeChunkNos"]:
                missing.append(canonical_chunk_id)
                continue
            for chunk_no in mapping["runtimeChunkNos"]:
                alternatives.add(runtime_chunk_key(mapping["documentCode"], mapping["generationId"], chunk_no))
        if not alternatives:
            missing.append(group.get("requirementId", "<unnamed-requirement>"))
        resolved_groups.append(alternatives)
    if missing:
        raise ApiError(f"{question['queryId']} has no runtime chunk mapping for gold evidence: {sorted(set(missing))}")
    return resolved_groups


def ranks_for(retrieved, gold):
    return [rank for rank, item in enumerate(retrieved, 1) if item in gold]


def evaluate(api, args, questions, document_map, mapping, run_dir):
    reverse = {value["documentCode"]: key for key, value in document_map.items()}
    mapping_by_canonical_chunk = {item["canonicalChunkId"]: item for item in mapping}
    results = []
    with (run_dir / "cases.jsonl").open("w", encoding="utf-8") as output:
        for index, question in enumerate(questions, start=1):
            started = time.perf_counter()
            try:
                response = api.retrieve(args.workspace_id, question["query"], args.candidate_limit, args.top_k)
                hits = response.get("hits", [])
                retrieved_documents = []
                retrieved_chunks = []
                for hit in hits:
                    document_id = map_retrieved_document(hit, reverse)
                    if document_id and document_id not in retrieved_documents:
                        retrieved_documents.append(document_id)
                    if all(hit.get(field) is not None for field in ("documentCode", "generationId", "chunkNo")):
                        chunk = runtime_chunk_key(hit["documentCode"], hit["generationId"], hit["chunkNo"])
                        if chunk not in retrieved_chunks:
                            retrieved_chunks.append(chunk)
                groups = gold_runtime_groups(question, mapping_by_canonical_chunk) if question["answerable"] else []
                golden_chunks = set().union(*groups) if groups else set()
                golden_documents = {
                    document_id
                    for group in evidence_requirement_groups(question)
                    for document_id in [
                        evidence["chunkId"].split("#", 1)[0]
                        for evidence in group["evidence"]
                    ]
                }
                document_ranks = ranks_for(retrieved_documents, golden_documents)
                group_ranks = [ranks_for(retrieved_chunks, group) for group in groups]
                group_satisfied = [bool(ranks) for ranks in group_ranks]
                first_evidence_rank = min((ranks[0] for ranks in group_ranks if ranks), default=None)
                full_evidence_hit = all(group_satisfied) if groups else None
                evidence_group_recall = sum(group_satisfied) / len(groups) if groups else None
                result = {
                    "queryId": question["queryId"], "suite": question["suite"], "queryType": question["queryType"],
                    "query": question["query"], "goldenDocumentIds": sorted(golden_documents),
                    "retrievedDocumentIds": retrieved_documents,
                    "goldenRuntimeChunks": [list(chunk) for chunk in sorted(golden_chunks)],
                    "goldenEvidenceRequirementGroups": [
                        [list(chunk) for chunk in sorted(group)] for group in groups
                    ],
                    "retrievedRuntimeChunks": [list(chunk) for chunk in retrieved_chunks],
                    "documentHitAtK": bool(document_ranks),
                    "documentRecallAtK": len(set(retrieved_documents) & golden_documents) / len(golden_documents)
                    if golden_documents else None,
                    "documentReciprocalRankAtK": 1 / document_ranks[0] if document_ranks else 0,
                    # The legacy names remain for report compatibility. In a
                    # governed dataset, they are group-aware: hit means every
                    # required fact is covered, recall is satisfied groups / all
                    # groups, and MRR is the first satisfying evidence rank.
                    "chunkHitAtK": full_evidence_hit,
                    "chunkRecallAtK": evidence_group_recall,
                    "chunkReciprocalRankAtK": 1 / first_evidence_rank if first_evidence_rank else 0,
                    "fullEvidenceHitAtK": full_evidence_hit,
                    "evidenceGroupRecallAtK": evidence_group_recall,
                    "firstEvidenceReciprocalRankAtK": 1 / first_evidence_rank if first_evidence_rank else 0,
                    "evidenceGroupSatisfiedAtK": group_satisfied,
                    "evidenceGroupFirstRanks": [ranks[0] if ranks else None for ranks in group_ranks],
                    "hits": hits,
                    "diagnostics": response.get("diagnostics"), "timingMs": response.get("timingMs"),
                    "latencyMs": round((time.perf_counter() - started) * 1000, 2), "status": "SUCCESS",
                }
            except (ApiError, KeyError) as exc:
                result = {"queryId": question["queryId"], "suite": question["suite"], "query": question["query"],
                          "status": "ERROR", "error": str(exc), "latencyMs": round((time.perf_counter() - started) * 1000, 2)}
            results.append(result)
            output.write(json.dumps(result, ensure_ascii=False) + "\n")
            output.flush()
            if index % 10 == 0 or index == len(questions):
                print(f"evaluated {index}/{len(questions)}")
    return results


def percentile(values, percent):
    return sorted(values)[round((len(values) - 1) * percent / 100)] if values else None


def build_summary(results, args, dataset_version, mapping_count):
    successful = [row for row in results if row["status"] == "SUCCESS"]
    answerable_documents = [row for row in successful if row.get("goldenDocumentIds")]
    answerable_chunks = [row for row in successful if row.get("goldenRuntimeChunks")]
    return {
        "datasetVersion": dataset_version, "split": args.split, "candidateLimit": args.candidate_limit,
        "topK": args.top_k, "questionCount": len(results), "successfulQuestionCount": len(successful),
        "errorQuestionCount": len(results) - len(successful),
        "primaryMetricScope": "evidence_requirement_group",
        "metricDefinitions": {
            "fullEvidenceHit": "all required evidence groups have at least one retrieved alternative",
            "evidenceGroupRecall": "satisfied required evidence groups divided by all required groups",
            "firstEvidenceMrr": "reciprocal rank of the first retrieved item satisfying any evidence group",
            "legacyChunkAliases": "chunkHit/chunkRecall/chunkMrr retain these group-aware semantics for report compatibility",
        },
        f"fullEvidenceHitRateAt{args.top_k}": sum(row["fullEvidenceHitAtK"] for row in answerable_chunks)
        / len(answerable_chunks) if answerable_chunks else None,
        f"evidenceGroupRecallAt{args.top_k}": statistics.mean(row["evidenceGroupRecallAtK"] for row in answerable_chunks)
        if answerable_chunks else None,
        f"firstEvidenceMrrAt{args.top_k}": statistics.mean(row["firstEvidenceReciprocalRankAtK"] for row in answerable_chunks)
        if answerable_chunks else None,
        f"chunkHitRateAt{args.top_k}": sum(row["chunkHitAtK"] for row in answerable_chunks) / len(answerable_chunks)
        if answerable_chunks else None,
        f"chunkRecallAt{args.top_k}": statistics.mean(row["chunkRecallAtK"] for row in answerable_chunks)
        if answerable_chunks else None,
        f"chunkMrrAt{args.top_k}": statistics.mean(row["chunkReciprocalRankAtK"] for row in answerable_chunks)
        if answerable_chunks else None,
        f"documentHitRateAt{args.top_k}": sum(row["documentHitAtK"] for row in answerable_documents)
        / len(answerable_documents) if answerable_documents else None,
        f"documentRecallAt{args.top_k}": statistics.mean(row["documentRecallAtK"] for row in answerable_documents)
        if answerable_documents else None,
        f"documentMrrAt{args.top_k}": statistics.mean(row["documentReciprocalRankAtK"] for row in answerable_documents)
        if answerable_documents else None,
        "unanswerableQueryCount": len([row for row in successful if not row.get("goldenRuntimeChunks")]),
        "latencyMs": {key: percentile([row["latencyMs"] for row in successful], value)
                      for key, value in (("p50", 50), ("p95", 95), ("p99", 99))},
        "mappedCanonicalChunkCount": mapping_count,
    }


def main():
    args = parse_args()
    run_dir = PROJECT_ROOT / "rag-eval" / "runs" / args.run_id
    run_dir.mkdir(parents=True, exist_ok=True)
    root = args.dataset_root
    dataset = json.loads((root / "dataset.json").read_text(encoding="utf-8"))
    rows = manifest_rows(root)
    api = WorkspaceApi(args)
    document_map = upload_corpus(api, args, rows, run_dir) if args.upload else json.loads(args.document_map.read_text(encoding="utf-8"))
    (run_dir / "document-map.json").write_text(json.dumps(document_map, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    readiness = wait_ready(api, args, document_map, run_dir)
    mapping = map_quotes(api, args, root, document_map, readiness, run_dir)
    questions = active_cases(read_lines(cases_path(root, args.split, getattr(args, "cases_file", None))))
    results = evaluate(api, args, questions, document_map, mapping, run_dir)
    report = build_summary(results, args, dataset["datasetVersion"], len(mapping)) | {"readiness": readiness}
    (run_dir / "summary.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
