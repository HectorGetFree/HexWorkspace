#!/usr/bin/env python3
"""Compare frozen C2 candidate identities and RRF order with a later R1 E2E run."""
from __future__ import annotations

import argparse
import json
from pathlib import Path


SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parents[1]


def read_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def candidate_id(candidate: dict) -> tuple[str, str, int]:
    return candidate["documentCode"], candidate["generationId"], int(candidate["chunkNo"])


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--frozen-replay-run", type=Path, required=True,
                        help="R0/R1 frozen-candidate replay run; its config points at sourceC2Run")
    parser.add_argument("--r1-e2e-run", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    replay_run = args.frozen_replay_run.resolve()
    e2e_run = args.r1_e2e_run.resolve()
    replay_summary = json.loads((replay_run / "summary.json").read_text(encoding="utf-8"))
    source_c2 = Path(replay_summary["sourceC2Run"])
    frozen_cases = {case["id"]: case for case in read_jsonl(source_c2 / "cases.jsonl")}
    e2e_cases = {case["id"]: case for case in read_jsonl(e2e_run / "cases.jsonl")}
    if set(frozen_cases) != set(e2e_cases):
        raise ValueError("frozen C2 与 R1 E2E query 集不一致，不能比较")
    rows = []
    for query_id in sorted(frozen_cases):
        frozen = frozen_cases[query_id]
        e2e = e2e_cases[query_id]
        frozen_candidates = [candidate_id(candidate) for candidate in frozen["rrfCandidates"]]
        e2e_candidates = [candidate_id(candidate) for candidate in e2e["rrfCandidates"]]
        same_ids = set(frozen_candidates) == set(e2e_candidates)
        same_order = frozen_candidates == e2e_candidates
        frozen_rank = frozen["goldBestRank"]["rrf"]
        e2e_rank = e2e["goldBestRank"]["rrf"]
        rows.append({
            "queryId": query_id,
            "candidateCount": len(frozen_candidates),
            "candidateIdsEqual": same_ids,
            "candidateOrderEqual": same_order,
            "frozenGoldRrfRank": frozen_rank,
            "e2eGoldRrfRank": e2e_rank,
            "goldRrfRankEqual": frozen_rank == e2e_rank,
        })
    summary = {
        "frozenReplayRun": str(replay_run),
        "sourceC2Run": str(source_c2),
        "r1E2ERun": str(e2e_run),
        "queryCount": len(rows),
        "candidateIdsFullyEqualQueryCount": sum(row["candidateIdsEqual"] for row in rows),
        "candidateOrderFullyEqualQueryCount": sum(row["candidateOrderEqual"] for row in rows),
        "goldRrfRankFullyEqualQueryCount": sum(row["goldRrfRankEqual"] for row in rows),
        "allComparedFieldsFullyEqualQueryCount": sum(
            row["candidateIdsEqual"] and row["candidateOrderEqual"] and row["goldRrfRankEqual"]
            for row in rows),
    }
    output_dir = (args.output_dir or e2e_run / "frozen-candidate-comparison").resolve()
    output_dir.mkdir(parents=True, exist_ok=True)
    write_json(output_dir / "per-query.json", rows)
    write_json(output_dir / "summary.json", summary)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
