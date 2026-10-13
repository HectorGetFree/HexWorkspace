#!/usr/bin/env python3
"""Validate RAG Evaluation V2 draft data without calling any retrieval service."""
from __future__ import annotations

import argparse
import difflib
import json
import re
import sys
from collections import Counter
from pathlib import Path


ALLOWED_SUITES = {"general", "workspace_business", "synthetic_hard"}
ALLOWED_TYPES = {"single_hop_semantic", "paraphrase", "exact_lexical", "hard_negative", "multi_hop", "ambiguous", "long_tail", "unanswerable"}
ALLOWED_DIFFICULTIES = {"EASY", "MEDIUM", "HARD"}
ALLOWED_STRENGTHS = {"dense", "sparse", "both", "rerank", "multi_hop", "unknown"}
ALLOWED_METHODS = {"manual", "synthetic", "public_benchmark", "derived_from_failure_case"}
ALLOWED_REVIEW = {"UNREVIEWED", "REVIEWED", "VERIFIED", "REJECTED"}


def lines(path: Path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def normalize(value: str) -> str:
    return re.sub(r"\s+", "", value).lower()


def character_ngrams(value: str, width: int = 3) -> set[str]:
    normalized = normalize(value)
    return {normalized[index:index + width] for index in range(max(0, len(normalized) - width + 1))}


def jaccard(left: set[str], right: set[str]) -> float:
    return len(left & right) / len(left | right) if left or right else 0.0


def require(condition: bool, message: str, errors: list[str]):
    if not condition:
        errors.append(message)


def statistics(cases):
    def count(field):
        return dict(sorted(Counter(case[field] for case in cases).items()))
    answerable = [case for case in cases if case["answerable"]]
    return {
        "queryCount": len(cases),
        "suiteDistribution": count("suite"),
        "queryTypeDistribution": count("queryType"),
        "difficultyDistribution": count("difficulty"),
        "answerableRatio": len(answerable) / len(cases) if cases else 0,
        "relevantChunksPerAnswerableQuery": sum(len(case["relevantChunkIds"]) for case in answerable) / len(answerable) if answerable else 0,
        "hardNegativesPerQuery": sum(len(case["hardNegativeChunkIds"]) for case in cases) / len(cases) if cases else 0,
        "generationMethodDistribution": count("generationMethod"),
        "reviewStatusDistribution": count("reviewStatus"),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-root", type=Path, default=Path(__file__).resolve().parents[1] / "dataset-v2-draft")
    parser.add_argument("--runtime-mapping", type=Path, help="optional quote-to-runtime-chunk.json to validate frozen evidence mappings")
    args = parser.parse_args()
    root = args.dataset_root
    cases_path = root / "cases" / "test.jsonl"
    if not cases_path.is_file():
        cases_path = root / "cases" / "sample.jsonl"
    cases = lines(cases_path)
    anchors = {row["chunkId"]: row for row in lines(root / "corpus" / "chunks.jsonl")}
    errors, warnings, seen_ids, seen_queries, evidence_fingerprints, required_anchors = [], [], set(), {}, [], set()
    for case in cases:
        label = case.get("queryId", "<missing-queryId>")
        required = {"queryId", "suite", "query", "queryType", "difficulty", "answerable", "relevantDocumentIds", "relevantChunkIds", "evidence", "hardNegativeChunkIds", "hardNegativeReasons", "expectedRetrievalStrength", "generationMethod", "reviewStatus", "notes"}
        require(required <= set(case), f"{label}: missing required field", errors)
        if not required <= set(case):
            continue
        require(label not in seen_ids, f"{label}: duplicate queryId", errors)
        seen_ids.add(label)
        require(bool(case["query"].strip()), f"{label}: empty query", errors)
        require(case["suite"] in ALLOWED_SUITES, f"{label}: invalid suite", errors)
        require(case["queryType"] in ALLOWED_TYPES, f"{label}: invalid queryType", errors)
        require(case["difficulty"] in ALLOWED_DIFFICULTIES, f"{label}: invalid difficulty", errors)
        require(case["expectedRetrievalStrength"] in ALLOWED_STRENGTHS, f"{label}: invalid expectedRetrievalStrength", errors)
        require(case["generationMethod"] in ALLOWED_METHODS, f"{label}: invalid generationMethod", errors)
        require(case["reviewStatus"] in ALLOWED_REVIEW, f"{label}: invalid reviewStatus", errors)
        groups = case.get("evidenceRequirementGroups")
        if groups is None:
            groups = [
                {
                    "requirementId": f"legacy-{index + 1}",
                    "evidence": [evidence for evidence in case["evidence"] if evidence["chunkId"] == chunk_id],
                }
                for index, chunk_id in enumerate(case["relevantChunkIds"])
            ]
        require(isinstance(groups, list), f"{label}: evidenceRequirementGroups must be a list", errors)
        if not isinstance(groups, list):
            continue
        group_fingerprints = set()
        group_evidence = []
        for index, group in enumerate(groups):
            group_label = f"{label}: evidence group {index + 1}"
            require(isinstance(group, dict), f"{group_label} must be an object", errors)
            if not isinstance(group, dict):
                continue
            require(bool(group.get("requirementId")), f"{group_label} missing requirementId", errors)
            evidence_items = group.get("evidence")
            require(isinstance(evidence_items, list) and bool(evidence_items), f"{group_label} must not be empty", errors)
            if not isinstance(evidence_items, list) or not evidence_items:
                continue
            chunk_ids = [item.get("chunkId") for item in evidence_items]
            require(len(chunk_ids) == len(set(chunk_ids)), f"{group_label} has duplicate evidence", errors)
            fingerprint = tuple(sorted(chunk_ids))
            require(fingerprint not in group_fingerprints, f"{label}: duplicate evidence requirement group", errors)
            group_fingerprints.add(fingerprint)
            if len(evidence_items) > 1:
                require(bool(group.get("equivalenceRationale")), f"{group_label} alternatives require equivalenceRationale", errors)
            group_evidence.extend(evidence_items)
        if case["answerable"]:
            require(bool(groups), f"{label}: answerable case has no evidence requirement groups", errors)
        else:
            require(not groups, f"{label}: unanswerable case has evidence requirement groups", errors)
        gold = {item.get("chunkId") for item in group_evidence}
        negatives = set(case["hardNegativeChunkIds"])
        require(not (gold & negatives), f"{label}: hard negative overlaps gold", errors)
        require(set(case["hardNegativeReasons"]) == negatives, f"{label}: hard negative reason keys inconsistent", errors)
        for chunk_id in gold | negatives:
            require(chunk_id in anchors, f"{label}: unknown chunk {chunk_id}", errors)
        for evidence in group_evidence:
            chunk_id, quote = evidence.get("chunkId"), evidence.get("quote", "")
            require(chunk_id in gold, f"{label}: evidence chunk not in relevantChunkIds", errors)
            require(chunk_id in anchors, f"{label}: evidence references unknown chunk", errors)
            if chunk_id in anchors:
                require(quote in anchors[chunk_id]["content"], f"{label}: evidence quote not in canonical chunk", errors)
            require(evidence.get("relevanceGrade") in {1, 2, 3}, f"{label}: invalid evidence relevance grade", errors)
        if group_evidence:
            evidence_fingerprints.append((label, character_ngrams(" ".join(item["quote"] for item in group_evidence))))
        expected_docs = {chunk.split("#", 1)[0] for chunk in gold}
        require(expected_docs == set(case["relevantDocumentIds"]), f"{label}: relevant document/chunk relation inconsistent", errors)
        if not case["answerable"]:
            require(not gold and not case["evidence"], f"{label}: unanswerable case has gold evidence", errors)
            require(case["queryType"] == "unanswerable", f"{label}: unanswerable must use unanswerable type", errors)
        else:
            require(bool(gold) and bool(case["evidence"]), f"{label}: answerable case has no gold evidence", errors)
        if case["queryType"] == "multi_hop":
            require(len(groups) >= 2, f"{label}: multi_hop needs at least two evidence requirement groups", errors)
        if case["reviewStatus"] == "VERIFIED":
            required_anchors.update(gold)
        normalized = normalize(case["query"])
        if normalized in seen_queries:
            errors.append(f"{label}: duplicate query with {seen_queries[normalized]}")
        seen_queries[normalized] = label
    items = list(seen_queries.items())
    for index, (left, left_id) in enumerate(items):
        for right, right_id in items[index + 1:]:
            if difflib.SequenceMatcher(a=left, b=right).ratio() >= 0.92:
                warnings.append(f"highly similar query: {left_id} / {right_id}")
    for index, (left_id, left_terms) in enumerate(evidence_fingerprints):
        for right_id, right_terms in evidence_fingerprints[index + 1:]:
            similarity = jaccard(left_terms, right_terms)
            if similarity >= 0.80:
                warnings.append(f"highly similar evidence quote ({similarity:.2f}): {left_id} / {right_id}")
    if args.runtime_mapping:
        mapping_rows = json.loads(args.runtime_mapping.read_text(encoding="utf-8"))
        mapping_by_anchor = {row.get("canonicalChunkId"): row for row in mapping_rows}
        stale = sorted(set(mapping_by_anchor) - set(anchors))
        for anchor_id in stale:
            errors.append(f"stale runtime mapping anchor: {anchor_id}")
        for anchor_id in sorted(required_anchors):
            row = mapping_by_anchor.get(anchor_id)
            if row is None or not row.get("runtimeChunkNos"):
                errors.append(f"verified evidence anchor is not mapped: {anchor_id}")
    report = statistics(cases)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    for warning in warnings:
        print(f"WARNING: {warning}", file=sys.stderr)
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    if errors:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
