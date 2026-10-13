#!/usr/bin/env python3
"""Produce auditable review manifests for the Workspace Business V1 candidate."""
from __future__ import annotations

import difflib
import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DATASET = ROOT / "dataset-v2-workspace-business-full-v1"
RUN = ROOT.parent / "rag-eval" / "runs" / "workspace-architecture-full-draft-20261004T123112Z"


def rows(path: Path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def write_jsonl(path: Path, values):
    path.write_text("".join(json.dumps(value, ensure_ascii=False) + "\n" for value in values), encoding="utf-8")


def normalized(value: str):
    return re.sub(r"\s+", "", value).lower()


def grams(value: str, width=3):
    value = normalized(value)
    return {value[index:index + width] for index in range(max(0, len(value) - width + 1))}


def jaccard(left, right):
    return len(left & right) / len(left | right) if left or right else 0.0


def evidence_summary(case):
    return [
        {"requirementId": group["requirementId"], "anchors": [item["chunkId"] for item in group["evidence"]]}
        for group in case.get("evidenceRequirementGroups", [])
    ]


def main():
    governance = DATASET / "governance"
    governance.mkdir(exist_ok=True)
    cases = rows(DATASET / "cases" / "test.jsonl")
    by_id = {case["queryId"]: case for case in cases}
    draft_results = {row["queryId"]: row for row in rows(RUN / "cases.jsonl")}

    classifications = {
        "arch-002": ("EQUIVALENT_EVIDENCE", "Retrieved retrieval-chain evidence independently states Qdrant is candidate-only and MySQL is authoritative."),
        "doc-002": ("EQUIVALENT_EVIDENCE", "Retrieved get_document contract independently prohibits latest-version fallback."),
        "integration-001": ("AMBIGUOUS_REQUIRES_HUMAN_REVIEW", "Related command-effect evidence was retrieved, but it does not explicitly state duplicate StartRun submission semantics."),
        "wb-012": ("TRUE_RETRIEVAL_MISS", "No retrieved top-5 item independently states that accepted upload is not the formal readable-version commit point."),
        "wb-048": ("EQUIVALENT_EVIDENCE", "Retrieved command-effect boundary independently distinguishes ACCEPTED+NONE from APPLIED."),
        "hard-002": ("EQUIVALENT_EVIDENCE", "Retrieved internal-service-authentication evidence independently rejects frontend JWT as internal service credential."),
        "hard-009": ("EQUIVALENT_EVIDENCE", "Retrieved lifecycle evidence independently prohibits Stream from projecting AgentRun business status."),
        "hard-016": ("GOLD_ANNOTATION_ERROR", "The query asks about external Stream sequence reset while the Gold source only specifies lifecycle sequence; it is rejected pending rewrite."),
        "hard-018": ("AMBIGUOUS_REQUIRES_HUMAN_REVIEW", "Retrieved command ACK boundary is related, but its explicit example is StartRun rather than PauseRun."),
    }
    misses = []
    for case_id, (classification, justification) in classifications.items():
        result = draft_results[case_id]
        misses.append({
            "caseId": case_id, "query": by_id[case_id]["query"], "queryType": by_id[case_id]["queryType"],
            "classification": classification, "justification": justification,
            "currentEvidenceRequirements": evidence_summary(by_id[case_id]),
            "retrievedTop5": result["retrievedRuntimeChunks"],
            "qdrantCandidates": result.get("diagnostics", {}).get("qdrantCandidates", []),
            "candidateContainsCurrentGoldDocument": any(
                item["documentCode"] in {chunk[0] for chunk in result["goldenRuntimeChunks"]}
                for item in result.get("diagnostics", {}).get("qdrantCandidates", [])
            ),
            "annotationDecision": by_id[case_id]["reviewStatus"],
        })
    (governance / "nine-miss-adjudication.json").write_text(json.dumps(misses, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    original_multi_hop = ["auth-003", "run-004", "wb-006", "wb-023", "wb-046"]
    audit = []
    for case_id in original_multi_hop:
        case = by_id[case_id]
        audit.append({
            "caseId": case_id, "query": case["query"], "decision": case["reviewStatus"],
            "currentQueryType": case["queryType"], "evidenceRequirements": evidence_summary(case),
            "finding": case["notes"],
        })
    for case_id in ["v1-multihop-001", "v1-multihop-002", "v1-multihop-003"]:
        case = by_id[case_id]
        audit.append({
            "caseId": case_id, "query": case["query"], "decision": "REVIEWED_CANDIDATE",
            "currentQueryType": case["queryType"], "evidenceRequirements": evidence_summary(case),
            "finding": "Two independent requirement groups are required; one retrieved group yields group recall 0.5 and full evidence hit 0.",
        })
    (governance / "multi-hop-audit.json").write_text(json.dumps(audit, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    similarities = []
    fingerprint = [(case, grams(" ".join(item["quote"] for item in case.get("evidence", [])))) for case in cases if case.get("evidence")]
    for index, (left, left_terms) in enumerate(fingerprint):
        for right, right_terms in fingerprint[index + 1:]:
            score = jaccard(left_terms, right_terms)
            if score < 0.80:
                continue
            pair = {left["queryId"], right["queryId"]}
            recommendation = "MERGE" if pair == {"run-004", "wb-023"} else "HUMAN_REVIEW"
            similarities.append({
                "caseA": {"id": left["queryId"], "query": left["query"], "type": left["queryType"], "difficulty": left["difficulty"], "requirements": evidence_summary(left)},
                "caseB": {"id": right["queryId"], "query": right["query"], "type": right["queryType"], "difficulty": right["difficulty"], "requirements": evidence_summary(right)},
                "evidenceSimilarity": round(score, 4), "recommendation": recommendation,
                "reason": "same lifecycle-gap retrieval condition" if recommendation == "MERGE" else "same or highly overlapping evidence requires human judgment about distinct retrieval conditions",
            })
    write_jsonl(governance / "similarity-review.jsonl", similarities)

    unresolved_reasons = {
        "integration-001": "Determine whether command-effect evidence independently guarantees duplicate StartRun no-op.",
        "wb-012": "Confirm true retrieval miss versus a missing equivalent formal-readability source.",
        "hard-018": "Decide whether generic ACCEPTED semantics are sufficient for Pause-specific wording.",
        "hard-016": "Rewrite against a documented Stream sequence contract or leave rejected.",
    }
    queue = []
    for case in cases:
        if case["reviewStatus"] == "UNREVIEWED" or case["queryId"] in unresolved_reasons:
            queue.append({
                "caseId": case["queryId"], "query": case["query"], "reviewStatus": case["reviewStatus"],
                "evidenceRequirements": evidence_summary(case),
                "reason": unresolved_reasons.get(case["queryId"], "Needs human validation of answerability, evidence sufficiency, alternatives and duplicate value before VERIFIED."),
            })
    write_jsonl(governance / "human-review-queue.jsonl", queue)
    print(json.dumps({"misses": len(misses), "similarityWarnings": len(similarities), "humanReviewQueue": len(queue)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
