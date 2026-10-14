#!/usr/bin/env python3
"""Attach exact source excerpts to Java Notes query drafts for later review."""
from __future__ import annotations

import json
import re
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DATASET_ROOT = ROOT / "dataset-v2-java-notes-v1-draft"
WORD = re.compile(r"[A-Za-z][A-Za-z0-9_.+#${}@-]{1,}|[\u4e00-\u9fff]{2,}")
IMAGE = re.compile(r"!\[[^\]]*\]\([^)]*\)|<img\b", re.IGNORECASE)


def terms(query: str) -> set[str]:
    values = set()
    for token in WORD.findall(query):
        if re.fullmatch(r"[\u4e00-\u9fff]{2,}", token):
            values.update(token[index:index + 2] for index in range(len(token) - 1))
        else:
            values.add(token.lower())
    return values


def excerpt(query: str, content: str) -> str:
    """Choose one bounded, verbatim prose excerpt from its already-reviewed target chunk."""
    query_terms = terms(query)
    candidates = []
    for line in content.splitlines():
        stripped = line.strip()
        if IMAGE.search(stripped):
            stripped = IMAGE.sub("", stripped).strip()
        # Heading text is represented as runtime headingPath rather than chunk
        # content, so it cannot serve as a quote-to-runtime evidence anchor.
        if len(stripped) < 12 or stripped.startswith(("```", "#")):
            continue
        lowered = stripped.lower()
        score = sum(term in lowered for term in query_terms if not re.fullmatch(r"[\u4e00-\u9fff]{2}", term))
        score += sum(term in stripped for term in query_terms if re.fullmatch(r"[\u4e00-\u9fff]{2}", term))
        candidates.append((score, len(stripped), stripped))
    if not candidates:
        raise ValueError("target chunk has no usable prose excerpt")
    _, _, selected = max(candidates, key=lambda item: (item[0], min(item[1], 220)))
    return selected[:300].rstrip()


def main() -> None:
    chunks = {
        row["chunkId"]: row
        for row in (
            json.loads(line)
            for line in (DATASET_ROOT / "corpus" / "chunks.jsonl").read_text(encoding="utf-8").splitlines()
            if line
        )
    }
    drafts = [
        json.loads(line)
        for line in (DATASET_ROOT / "cases" / "draft-expanded.jsonl").read_text(encoding="utf-8").splitlines()
        if line
    ]
    rows = []
    for draft in drafts:
        evidences = []
        for chunk_id in draft["candidateChunkIds"]:
            chunk = chunks[chunk_id]
            quote = excerpt(draft["query"], chunk["content"])
            if quote not in chunk["content"]:
                raise ValueError(f"non-verbatim excerpt for {draft['queryId']} / {chunk_id}")
            evidences.append({"chunkId": chunk_id, "quote": quote, "relevanceGrade": 3})
        row = {
            **draft,
            "relevantDocumentIds": draft["candidateDocumentIds"],
            "relevantChunkIds": draft["candidateChunkIds"],
            "evidence": evidences,
            "evidenceRequirementGroups": [
                {"requirementId": f"source-{index}", "evidence": [evidence]}
                for index, evidence in enumerate(evidences, start=1)
            ],
            # Exact quote/chunk binding is complete.  A later human pass is
            # still required before promotion to VERIFIED test data.
            "reviewStatus": "EVIDENCE_MAPPED",
            "notes": "Exact source excerpt and canonical chunk binding validated; semantic benchmark review pending.",
        }
        rows.append(row)
    output = DATASET_ROOT / "cases" / "evidence-mapped.jsonl"
    output.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
    dataset_path = DATASET_ROOT / "dataset.json"
    dataset = json.loads(dataset_path.read_text(encoding="utf-8"))
    dataset["queryStatus"] = "EVIDENCE_MAPPED_REVIEW_PENDING"
    dataset["expandedDraftQueryCount"] = len(rows)
    dataset["evidenceMappedQueryCount"] = len(rows)
    dataset_path.write_text(json.dumps(dataset, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {len(rows)} quote-evidenced query drafts: {output}")


if __name__ == "__main__":
    main()
