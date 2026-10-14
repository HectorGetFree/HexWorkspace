#!/usr/bin/env python3
"""143 Query 单机制完整 A/B；A Dense+Rerank，B 加 BM25/RRF，均每文档两条/Top5 Chunk。"""
import json
import time
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path
import run_business_production_hybrid as business
from run_scifact_production_hybrid import (save, append, read_rows, digest, generation_hash,
                                          check_profile, request_json, implementation_hashes)
from run_retrieval_v2 import WorkspaceApi


def key(candidate):
    return candidate["documentCode"], candidate["generationId"], candidate["chunkNo"]


def attribution(response, question, mapping):
    diag = response["diagnostics"]
    required = ("qdrantCandidates", "sparseCandidates", "fusedCandidates", "authorityValidCandidates", "rerankedChunks")
    if any(name not in diag for name in required): raise ValueError("AB_DIAGNOSTICS_MISSING：需要重新编译/启动后端")
    ranked = diag["rerankedChunks"]
    valid = diag["authorityValidCandidates"]
    if len(valid) >= 2 and not ranked:
        return {"status": "RERANK_FALLBACK", "truncation": [], "goldStages": []}
    if ranked and ({key(c) for c in ranked} != {key(c) for c in valid} or len(ranked) != len(valid)):
        raise ValueError("RERANK_CHUNK_SET_MISMATCH")
    ordered = sorted(ranked or valid, key=lambda c: c["rank"])
    actual = {key(h) for h in response["hits"]}
    accepted, counts, decisions = set(), Counter(), []
    for candidate in ordered:
        identity = key(candidate)
        if len(accepted) >= 5: reason = "RESULT_LIMIT"
        elif counts[identity[0]] >= 2: reason = "PER_DOCUMENT_LIMIT"
        else:
            accepted.add(identity)
            counts[identity[0]] += 1
            reason = "RETURNED" if identity in actual else "FINAL_REVALIDATION_REMOVED"
        decisions.append(dict(candidate) | {"decision": reason})
    if not actual.issubset(accepted): raise ValueError("FINAL_ORDER_CONTRACT_MISMATCH")
    groups = business.gold_runtime_groups(question, mapping)
    stages = []
    for group in groups:
        stages.append({"gold": [list(k) for k in sorted(group)],
                       "ranks": {name: [c["rank"] for c in diag[name] if key(c) in group] for name in required},
                       "decisions": [c for c in decisions if key(c) in group]})
    return {"status": "AVAILABLE", "truncation": decisions, "goldStages": stages}


def report(rows, gate):
    arms = {arm: business.summarize([r for r in rows if r["variant"] == arm], gate, 5) for arm in ("A", "B")}
    by_id = {}
    for row in rows: by_id.setdefault(row["queryId"], {})[row["variant"]] = row
    valid_pairs = [p for p in by_id.values() if len(p) == 2 and all(r["status"] == "SUCCESS" and r["rerankStatus"] != "FALLBACK" for r in p.values())]
    transitions = {}
    for metric in ("documentHitAtK", "fullEvidenceHitAtK"):
        transitions[metric] = {"B_only": sum(not p["A"][metric] and p["B"][metric] for p in valid_pairs),
                               "A_only": sum(p["A"][metric] and not p["B"][metric] for p in valid_pairs),
                               "both": sum(p["A"][metric] and p["B"][metric] for p in valid_pairs),
                               "neither": sum(not p["A"][metric] and not p["B"][metric] for p in valid_pairs)}
    dense_equal = sum([key(c) for c in p["A"]["response"]["diagnostics"]["qdrantCandidates"]]
                      == [key(c) for c in p["B"]["response"]["diagnostics"]["qdrantCandidates"]] for p in valid_pairs)
    candidate_coverage = {arm: sum(all(stage["ranks"]["authorityValidCandidates"] for stage in p[arm]["attribution"]["goldStages"])
                                  for p in valid_pairs) for arm in ("A", "B")}
    return {"gate": gate, "registeredPairs": 143, "validPairs": len(valid_pairs), "arms": arms,
            "pairedTransitions": transitions, "mechanismDifference": "BM25/RRF only",
            "pairedDeltaPp": {name: (counts["B_only"] - counts["A_only"]) / len(valid_pairs) * 100 if valid_pairs else None
                              for name, counts in transitions.items()},
            "candidateFullEvidenceHitCount": candidate_coverage,
            "pairedDenseOrderIdenticalCount": dense_equal,
            "fixedContract": {"candidateLimit": 30, "perDocument": 2, "resultLimit": 5},
            "limitations": ["same-environment single paired pass; no statistical stability claim",
                             "independent embedding/rerank calls may differ; Dense identities are saved for comparison",
                             "cost measured only via latency; Provider usage unavailable", "dataset semantic review pending"]}


def main():
    args = business.parse_args()
    if args.candidate_limit != 30 or args.top_k != 5: raise ValueError("冻结合同要求 candidate-limit=30 / top-k=5")
    questions_path = args.dataset_root / "cases/evidence-mapped.jsonl"
    questions = read_rows(questions_path)
    sources = business.manifest_rows(args.dataset_root)
    doc_path = args.baseline_run / "document-map.json"
    quote_path = args.baseline_run / "quote-to-runtime-chunk.json"
    docs = json.loads(doc_path.read_text())
    mapping = json.loads(quote_path.read_text())
    reverse = {r["documentCode"]: d for d, r in docs.items()}
    if (len(questions) != 143 or len({q["queryId"] for q in questions}) != 143 or len(reverse) != 34
            or set(docs) != {r["documentId"] for r in sources}
            or any(q["reviewStatus"] != "EVIDENCE_MAPPED" or not q["answerable"] for q in questions)):
        raise ValueError("FROZEN_DATASET_MISMATCH")
    output = args.output_dir.resolve()
    if not args.resume: output.mkdir(parents=True, exist_ok=False)
    previous = json.loads((output / "manifest.json").read_text()) if args.resume else None
    identity = {"workspaceId": args.workspace_id, "baseUrl": args.base_url, "queriesHash": digest(questions_path),
                "documentMapHash": digest(doc_path), "quoteMapHash": digest(quote_path),
                "sourceHashes": implementation_hashes() | {"abRunner": digest(Path(__file__)), "businessOracle": digest(Path(business.__file__))},
                "contract": {"candidateLimit": 30, "perDocument": 2, "topK": 5, "order": "AB/BA alternating"}}
    if previous and previous["identity"] != identity: raise ValueError("RESUME_INPUT_MISMATCH")
    rows = read_rows(output / "cases.jsonl")
    completed = {(r["queryId"], r["variant"]) for r in rows}
    allowed = {(q["queryId"], arm) for q in questions for arm in ("A", "B")}
    if len(completed) != len(rows) or not completed.issubset(allowed): raise ValueError("INVALID_CHECKPOINT")
    if any((r["queryId"], r["variant"]) not in completed for r in read_rows(output / "attempts.jsonl")):
        raise ValueError("UNSETTLED_ATTEMPT：不自动重发未知结果请求")
    manifest = previous or {"identity": identity, "endpoint": "/internal/v1/evaluation/retrieval/ab", "httpRetries": 0,
                            "createdAt": datetime.now(timezone.utc).isoformat(), "qualification": "EVIDENCE_MAPPED_REVIEW_PENDING"}
    save(output / "manifest.json", manifest)
    api = WorkspaceApi(args)
    try:
        codes = sorted(reverse)
        ready = api.readiness(args.workspace_id, codes)
        save(output / "readiness-before.json", ready)
        if len(ready) != 34 or {r["documentCode"] for r in ready} != set(codes) or any(not r["ready"] for r in ready):
            raise ValueError("CORPUS_NOT_READY")
        expected = generation_hash(ready)
        if previous and previous.get("generationHash") and previous["generationHash"] != expected: raise ValueError("RESUME_GENERATION_CHANGED")
        chunks = api.active_chunks(args.workspace_id, codes)
        save(output / "active-chunks.json", chunks)
        by_chunk = business.validate_mapping(questions, mapping, chunks, docs, ready)
        manifest["generationHash"] = expected
        save(output / "manifest.json", manifest)
        def status():
            return request_json(api, "/internal/v1/evaluation/retrieval/hybrid-status", {"workspaceId": args.workspace_id, "documentCodes": codes[:1]})
        deadline = time.monotonic() + args.snapshot_timeout
        while True:
            profile = status()
            save(output / "snapshot-before.json", profile)
            check_profile(profile, expected, len(chunks))
            if profile.get("lastRefreshError"): raise ValueError("SNAPSHOT_FAILED：" + profile["lastRefreshError"])
            if profile.get("ready"): break
            if time.monotonic() >= deadline: raise ValueError("SNAPSHOT_TIMEOUT")
            time.sleep(2)
        for i, q in enumerate(questions):
            current = status()
            check_profile(current, expected, len(chunks))
            if not current.get("ready") or current["servingConfigId"] != profile["servingConfigId"]: raise ValueError("SERVING_CHANGED")
            for arm in (("A", "B") if i % 2 == 0 else ("B", "A")):
                if (q["queryId"], arm) in completed: continue
                append(output / "attempts.jsonl", {"queryId": q["queryId"], "variant": arm, "startedAt": datetime.now(timezone.utc).isoformat()})
                started, response = time.perf_counter(), None
                try:
                    response = request_json(api, "/internal/v1/evaluation/retrieval/ab", {"workspaceId": args.workspace_id,
                            "query": q["query"], "candidateLimit": 30, "topK": 5, "variant": arm})
                    if response.get("evaluationProfile") != {"variant": arm, "maxChunksPerDocument": 2}:
                        raise ValueError("AB_PROFILE_MISMATCH")
                    row = business.evaluate(q, response, reverse, by_chunk, 5, max_chunks_per_document=2)
                    row["attribution"] = attribution(response, q, by_chunk)
                    if arm == "A" and response["diagnostics"]["sparseCandidates"]: raise ValueError("A_ISOLATION_FAILED")
                except Exception as exc:
                    row = {"queryId": q["queryId"], "status": "ERROR", "error": str(exc), "errorType": type(exc).__name__, "response": response}
                row.update(variant=arm, latencyMs=(time.perf_counter() - started) * 1000, snapshotGenerationHash=expected)
                append(output / "cases.jsonl", row)
                rows.append(row)
                print(f"{len(rows)}/286 {q['queryId']} {arm} {row['status']}", flush=True)
                if row.get("errorType") == "ValueError": raise ValueError("停止：接口/隔离/诊断契约失败")
        after, last = api.readiness(args.workspace_id, codes), status()
        save(output / "readiness-after.json", after)
        save(output / "snapshot-after.json", last)
        check_profile(last, expected, len(chunks))
        if (len(after) != 34 or any(not r["ready"] for r in after) or generation_hash(after) != expected
                or last["servingConfigId"] != profile["servingConfigId"] or not last.get("ready")): raise ValueError("CORPUS_CHANGED")
        gate = "AB_COMPLETE" if len(rows) == 286 and all(r["status"] == "SUCCESS" and r["rerankStatus"] != "FALLBACK" for r in rows) else "AB_COMPLETE_WITH_ERRORS_OR_FALLBACKS"
    except Exception as exc:
        gate = "AB_BLOCKED_OR_INCOMPLETE"
        save(output / "stop.json", {"errorType": type(exc).__name__, "error": str(exc)})
        print(f"STOP {exc}", flush=True)
    summary = report(rows, gate)
    save(output / "summary.json", summary)
    print(json.dumps({"gate": gate, "summary": str(output / "summary.json")}, ensure_ascii=False), flush=True)
    if gate != "AB_COMPLETE": raise SystemExit(1)


if __name__ == "__main__": main()
