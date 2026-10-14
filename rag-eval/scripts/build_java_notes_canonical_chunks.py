#!/usr/bin/env python3
"""Build stable # / ## heading-path anchors for the Java Notes corpus."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATASET = ROOT / "dataset-v2-java-notes-v1-draft"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset-root", type=Path, default=DEFAULT_DATASET)
    return parser.parse_args()


def sha256(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def heading(line: str) -> tuple[int, str] | None:
    stripped = line.strip()
    if not stripped.startswith("#"):
        return None
    level = len(stripped) - len(stripped.lstrip("#"))
    if level not in (1, 2) or len(stripped) <= level or not stripped[level].isspace():
        return None
    title = stripped[level:].strip()
    return level, title


def split_sections(text: str):
    """Yield # / ## sections without splitting code fences or treating ### as a unit."""
    active_path: list[str] = []
    content: list[str] = []
    in_fence = False

    def flush():
        if active_path:
            body = "\n".join(content).strip()
            if body:
                yield list(active_path), body

    for line in text.splitlines():
        if line.strip().startswith("```"):
            in_fence = not in_fence
        marker = None if in_fence else heading(line)
        if marker is None:
            if active_path:
                content.append(line)
            continue
        yield from flush()
        content = []
        level, title = marker
        if level == 1:
            active_path = [title]
        else:
            active_path = (active_path[:1] if active_path else []) + [title]
    yield from flush()


def main() -> None:
    args = parse_args()
    dataset_root = args.dataset_root.resolve()
    manifest_path = dataset_root / "corpus" / "manifest.jsonl"
    manifest = [json.loads(line) for line in manifest_path.read_text(encoding="utf-8").splitlines() if line]
    anchors = []
    for document in manifest:
        source = ROOT.parent / document["sourceFile"]
        payload = source.read_bytes()
        if sha256(payload) != document["sourceSha256"]:
            raise ValueError(f"source changed: {document['documentId']}")
        for ordinal, (heading_path, content) in enumerate(split_sections(payload.decode("utf-8")), start=1):
            anchors.append({
                "chunkId": f"{document['documentId']}#s{ordinal:04d}",
                "documentId": document["documentId"],
                "headingPath": heading_path,
                "contentSha256": sha256(content.encode("utf-8")),
                "content": content,
            })
    output = dataset_root / "corpus" / "chunks.jsonl"
    output.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in anchors), encoding="utf-8")
    print(f"wrote {len(anchors)} Java Notes canonical anchors: {output}")


if __name__ == "__main__":
    main()
