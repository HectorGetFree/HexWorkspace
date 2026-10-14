#!/usr/bin/env python3
"""Materialize a SciFact General IR dataset for HexWorkspace.

The output deliberately uses the existing ``dataset-v1`` document/QA shape, so
``run_retrieval_v2.py`` can exercise the production ingestion and retrieval
path without any special-case production API.  The public source archive and
materialized texts live below the ignored ``rag-eval/cache`` directory; this
script records their checksum, source URL, license notice and subsample inputs
for reproducibility.

Without ``--full`` this produces the fast deterministic sanity subset: a query
sample, every judged relevant document for those queries, and deterministic
distractors.  With ``--full`` it produces the complete SciFact test evaluation
input: all 5,183 corpus documents and all 300 test queries with positive qrels.

The full mode deliberately excludes dev/train queries: they are not part of the
SciFact test split and mixing them into test metrics would invalidate the split.
Both modes remain in the ``general`` suite and must not be combined with
Workspace Business or Synthetic Hard scores.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import urllib.request
import zipfile
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ARCHIVE_URL = "https://public.ukp.informatik.tu-darmstadt.de/thakur/BEIR/datasets/scifact.zip"
DEFAULT_SANITY_OUTPUT_ROOT = PROJECT_ROOT / "rag-eval" / "cache" / "general-scifact-sanity-v1"
DEFAULT_FULL_OUTPUT_ROOT = PROJECT_ROOT / "rag-eval" / "cache" / "general-scifact-full-v1"


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def json_lines_from_zip(archive: zipfile.ZipFile, suffix: str) -> list[dict]:
    names = [name for name in archive.namelist() if name.rstrip("/").endswith(suffix)]
    if len(names) != 1:
        raise ValueError(f"archive must contain exactly one {suffix}; found {names}")
    return [json.loads(line) for line in archive.read(names[0]).decode("utf-8").splitlines() if line.strip()]


def qrels_from_zip(archive: zipfile.ZipFile, split: str) -> dict[str, dict[str, int]]:
    suffix = f"qrels/{split}.tsv"
    names = [name for name in archive.namelist() if name.rstrip("/").endswith(suffix)]
    if len(names) != 1:
        raise ValueError(f"archive must contain exactly one {suffix}; found {names}")
    lines = archive.read(names[0]).decode("utf-8").splitlines()
    if not lines or lines[0].split("\t") != ["query-id", "corpus-id", "score"]:
        raise ValueError(f"unexpected qrels header in {names[0]}")
    qrels: dict[str, dict[str, int]] = {}
    for line in lines[1:]:
        query_id, corpus_id, score = line.split("\t")
        qrels.setdefault(query_id, {})[corpus_id] = int(score)
    return qrels


def deterministic_ids(ids: list[str], limit: int, seed: str) -> list[str]:
    """Return a stable sample independent of Python's random implementation."""
    return sorted(ids, key=lambda value: sha256_bytes(f"{seed}:{value}".encode()))[:limit]


def parse_args():
    parser = argparse.ArgumentParser(description=__doc__)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--archive", type=Path, help="local BEIR-formatted SciFact zip")
    source.add_argument("--download", action="store_true", help="download the official BEIR SciFact archive")
    parser.add_argument("--archive-url", default=DEFAULT_ARCHIVE_URL)
    parser.add_argument("--full", action="store_true",
                        help="物化完整 SciFact corpus + 当前 split 的全部正相关 qrels query")
    parser.add_argument("--output-root", type=Path,
                        help="默认随模式选择 cache/general-scifact-{sanity,full}-v1")
    parser.add_argument("--split", default="test", choices=("train", "dev", "test"))
    parser.add_argument("--query-limit", type=int, default=50)
    parser.add_argument("--distractor-limit", type=int, default=350)
    parser.add_argument("--seed", default="general-scifact-sanity-v1")
    args = parser.parse_args()
    if args.query_limit <= 0 or args.distractor_limit < 0:
        parser.error("--query-limit 必须大于 0，--distractor-limit 不能小于 0")
    if args.full and args.split != "test":
        parser.error("--full 当前只冻结 SciFact test split，避免将 train/dev 混入 General 测试指标")
    if args.output_root is None:
        args.output_root = DEFAULT_FULL_OUTPUT_ROOT if args.full else DEFAULT_SANITY_OUTPUT_ROOT
    return args


def resolve_archive(args) -> Path:
    if args.archive:
        if not args.archive.is_file():
            raise FileNotFoundError(f"SciFact archive not found: {args.archive}")
        return args.archive
    destination = args.output_root.parent / "downloads" / "scifact.zip"
    destination.parent.mkdir(parents=True, exist_ok=True)
    print(f"downloading {args.archive_url} -> {destination}")
    with urllib.request.urlopen(args.archive_url, timeout=120) as response, destination.open("wb") as output:
        shutil.copyfileobj(response, output)
    return destination


def write_jsonl(path: Path, rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")


def materialize(args, archive_path: Path) -> dict:
    with zipfile.ZipFile(archive_path) as archive:
        corpus_rows = json_lines_from_zip(archive, "corpus.jsonl")
        query_rows = json_lines_from_zip(archive, "queries.jsonl")
        qrels = qrels_from_zip(archive, args.split)

    corpus = {str(row["_id"]): row for row in corpus_rows}
    queries = {str(row["_id"]): row for row in query_rows}
    eligible_query_ids = sorted(query_id for query_id, judged in qrels.items()
                                if query_id in queries and any(score > 0 for score in judged.values()))
    selected_query_ids = eligible_query_ids if args.full else deterministic_ids(eligible_query_ids, args.query_limit, args.seed)
    if not args.full and len(selected_query_ids) < args.query_limit:
        raise ValueError(f"requested {args.query_limit} queries, only {len(selected_query_ids)} eligible in {args.split}")

    relevant_document_ids = {
        document_id
        for query_id in selected_query_ids
        for document_id, score in qrels[query_id].items()
        if score > 0
    }
    missing = sorted(relevant_document_ids - corpus.keys())
    if missing:
        raise ValueError(f"qrels reference corpus documents absent from archive: {missing[:5]}")
    if args.full:
        distractor_ids = []
        selected_document_ids = sorted(corpus)
    else:
        distractor_ids = deterministic_ids(sorted(set(corpus) - relevant_document_ids), args.distractor_limit, args.seed)
        selected_document_ids = sorted(relevant_document_ids | set(distractor_ids))

    output_root = args.output_root
    manifest = []
    for document_id in selected_document_ids:
        source = corpus[document_id]
        title = str(source.get("title") or "").strip()
        text = str(source.get("text") or "").strip()
        content = f"{title}\n\n{text}\n" if title else f"{text}\n"
        raw_relative = Path("corpus") / "raw" / f"scifact-{document_id}.txt"
        raw_path = output_root / raw_relative
        raw_path.parent.mkdir(parents=True, exist_ok=True)
        raw_path.write_text(content, encoding="utf-8")
        manifest.append({
            "documentKey": f"scifact:{document_id}",
            "sourceDocumentId": document_id,
            "rawFile": raw_relative.as_posix(),
            "rawSha256": sha256_file(raw_path),
            "title": title or f"SciFact {document_id}",
            "suite": "general",
        })

    questions = []
    for query_id in selected_query_ids:
        relevant = [
            {"documentKey": f"scifact:{document_id}", "relevanceGrade": score}
            for document_id, score in sorted(qrels[query_id].items()) if score > 0
        ]
        questions.append({
            "id": f"scifact:{query_id}",
            "sourceQueryId": query_id,
            "suite": "general",
            "category": "general_scifact",
            "question": str(queries[query_id]["text"]),
            "evidences": relevant,
        })

    write_jsonl(output_root / "manifest" / "documents.jsonl", manifest)
    write_jsonl(output_root / "qa" / f"{args.split}.jsonl", questions)
    archive_sha256 = sha256_file(archive_path)
    metadata = {
        "datasetVersion": "general-scifact-full-v1" if args.full else "general-scifact-sanity-v1",
        "suite": "general",
        "kind": "public_ir_full_test" if args.full else "public_ir_sanity_subset",
        "notComparableToBeirLeaderboard": True,
        "source": {
            "name": "SciFact in BEIR JSONL/qrels format",
            "archiveUrl": args.archive_url,
            "archiveFile": str(archive_path),
            "archiveSha256": archive_sha256,
            "split": args.split,
            "upstreamLicenseNotice": "claims/evidence: CC BY 4.0; corpus abstracts: ODC-By 1.0; see licenses/SOURCES.md",
        },
        "coverage": {
            "mode": "full_test" if args.full else "deterministic_sanity_subset",
            "seed": None if args.full else args.seed,
            "queryLimit": None if args.full else args.query_limit,
            "distractorLimit": 0 if args.full else args.distractor_limit,
            "queryCount": len(questions),
            "relevantDocumentCount": len(relevant_document_ids),
            "corpusDocumentCount": len(manifest),
        },
    }
    (output_root / "dataset.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    sources = """# General SciFact source record

This directory is generated locally and ignored by Git. Do not commit the public
corpus or materialized texts without a separate license review.

- Distribution format: BEIR-compatible SciFact archive.
- Archive URL: {archive_url}
- Archive SHA-256: {archive_sha256}
- Upstream source: https://github.com/allenai/scifact
- Upstream license notice: SciFact claims and evidence annotations are CC BY 4.0;
  corpus abstracts are part of S2ORC and are ODC-By 1.0. The original repository
  code is Apache 2.0.
- Local mode: {mode}.
- Coverage: {query_count} query/qrels records, {relevant_document_count} positive-qrels
  documents, {corpus_document_count} corpus documents.
- This runs HexWorkspace's ingestion/chunking/retrieval implementation. Even in
  full mode, its scores must not be compared directly with BEIR leaderboards.
""".format(
        archive_url=args.archive_url,
        archive_sha256=archive_sha256,
        mode="full SciFact test corpus/qrels" if args.full else "deterministic sanity subset",
        query_count=len(questions),
        relevant_document_count=len(relevant_document_ids),
        corpus_document_count=len(manifest),
    )
    license_path = output_root / "licenses" / "SOURCES.md"
    license_path.parent.mkdir(parents=True, exist_ok=True)
    license_path.write_text(sources, encoding="utf-8")
    return metadata


def main():
    args = parse_args()
    archive_path = resolve_archive(args)
    result = materialize(args, archive_path)
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
