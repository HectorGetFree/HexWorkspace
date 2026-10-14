#!/usr/bin/env python3
"""Materialize stable section anchors from an evidence-first corpus manifest.

The emitted identifiers are evaluation identities, not Qdrant point IDs.  A runtime
executor must later map an anchor to the chunk that contains its quoted evidence.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--manifest",
        type=Path,
        default=ROOT / "dataset-v2-draft" / "corpus" / "manifest.jsonl",
        help="JSONL manifest whose sourceFile paths are repository-relative.",
    )
    parser.add_argument("--output", type=Path, default=ROOT / "dataset-v2-draft" / "corpus" / "chunks.jsonl")
    return parser.parse_args()


def section_slug(heading: str) -> str:
    mapping = {"文档目的": "purpose", "核心规则": "core-rules", "责任边界": "boundary", "异常处理": "exception", "示例": "example"}
    return mapping.get(heading, heading.strip().lower().replace(" ", "-"))


def split_sections(text: str):
    heading = None
    lines = []
    for line in text.splitlines():
        if line.startswith("## "):
            if heading is not None:
                yield heading, "\n".join(lines).strip()
            heading, lines = line[3:].strip(), []
        elif heading is not None:
            lines.append(line)
    if heading is not None:
        yield heading, "\n".join(lines).strip()


def main():
    args = parse_args()
    rows = [json.loads(line) for line in args.manifest.read_text(encoding="utf-8").splitlines() if line.strip()]
    anchors = []
    for document in rows:
        source_file = ROOT.parent / document["sourceFile"]
        payload = source_file.read_bytes()
        actual_sha256 = hashlib.sha256(payload).hexdigest()
        if actual_sha256 != document.get("sourceSha256"):
            raise ValueError(
                f"source changed for {document['documentId']}: expected {document.get('sourceSha256')}, "
                f"actual {actual_sha256}"
            )
        text = payload.decode("utf-8")
        for heading, content in split_sections(text):
            if not content:
                continue
            anchors.append({
                "chunkId": f'{document["documentId"]}#{section_slug(heading)}',
                "documentId": document["documentId"],
                "section": heading,
                "contentSha256": hashlib.sha256(content.encode("utf-8")).hexdigest(),
                "content": content,
            })
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("".join(json.dumps(anchor, ensure_ascii=False) + "\n" for anchor in anchors), encoding="utf-8")
    print(f"wrote {len(anchors)} canonical anchors: {args.output}")


if __name__ == "__main__":
    main()
