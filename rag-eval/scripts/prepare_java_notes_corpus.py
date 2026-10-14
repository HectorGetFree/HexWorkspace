#!/usr/bin/env python3
"""Freeze local Java Markdown notes as a query-pending RAG evaluation corpus."""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
from datetime import datetime, timezone
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SOURCE_ROOT = Path("/Users/hector/Desktop/Java_Dev/notes")
DEFAULT_OUTPUT_ROOT = PROJECT_ROOT / "rag-eval" / "dataset-v2-java-notes-v1-draft"
DATASET_VERSION = "java-notes-v1-draft"


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=DEFAULT_SOURCE_ROOT)
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    parser.add_argument("--force", action="store_true", help="允许覆盖已有输出目录")
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    source_root = args.source_root.resolve()
    output_root = args.output_root.resolve()
    if not source_root.is_dir():
        raise FileNotFoundError(f"source root does not exist: {source_root}")
    if output_root.exists():
        if not args.force:
            raise FileExistsError(f"output already exists: {output_root}; use --force to replace it")
        shutil.rmtree(output_root)

    sources = sorted(path for path in source_root.rglob("*.md") if path.is_file())
    if not sources:
        raise ValueError(f"no Markdown files under {source_root}")

    source_dir = output_root / "corpus" / "source"
    source_dir.mkdir(parents=True)
    manifest = []
    for index, source in enumerate(sources, start=1):
        relative = source.relative_to(source_root)
        identity = relative.as_posix().encode("utf-8")
        short_hash = hashlib.sha256(identity).hexdigest()[:12]
        destination = source_dir / f"{index:03d}-{short_hash}.md"
        shutil.copy2(source, destination)
        copied_sha256 = sha256(destination)
        if copied_sha256 != sha256(source):
            raise RuntimeError(f"copy checksum mismatch: {relative}")
        manifest.append({
            "documentId": f"java-note-{short_hash}",
            "title": source.stem,
            "sourceFile": str(destination.relative_to(PROJECT_ROOT)),
            "sourceSha256": copied_sha256,
            "sourceRole": "user-authored-java-note",
            "originalRelativePath": relative.as_posix(),
        })

    manifest_path = output_root / "corpus" / "manifest.jsonl"
    manifest_path.write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in manifest), encoding="utf-8"
    )
    write_json(output_root / "dataset.json", {
        "datasetVersion": DATASET_VERSION,
        "status": "CORPUS_FROZEN_QUERY_PENDING",
        "purpose": "raw user-authored Java/backend Markdown notes for a future realistic retrieval benchmark",
        "frozen": False,
        "corpusFrozen": True,
        "documentCount": len(manifest),
        "imageAssetsIncluded": False,
        "queryStatus": "NOT_CREATED",
        "generatedAt": datetime.now(timezone.utc).isoformat(),
    })
    (output_root / "README.md").write_text(
        "# Java Notes Corpus V1 Draft\n\n"
        "该目录冻结用户自有 Java/后端 Markdown 笔记的文本原文，供后续构建检索评测。\n\n"
        "- 当前仅冻结 corpus；尚未定义 query、gold evidence 或评测 split。\n"
        "- Markdown 原文按字节复制并由 `sourceSha256` 校验。\n"
        "- 图片资产未复制，也未进行 OCR；Markdown 中的图片引用仅作为原始文本保留。\n",
        encoding="utf-8",
    )
    print(json.dumps({
        "datasetRoot": str(output_root),
        "documentCount": len(manifest),
        "manifest": str(manifest_path),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
