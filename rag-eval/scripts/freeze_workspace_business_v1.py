#!/usr/bin/env python3
"""Apply an explicit human approval to the governed Workspace V1 candidate."""
from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1] / "dataset-v2-workspace-business-full-v1"


def main():
    case_path = ROOT / "cases" / "test.jsonl"
    cases = [json.loads(line) for line in case_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    approved = 0
    for case in cases:
        if case["reviewStatus"] != "REJECTED":
            case["reviewStatus"] = "VERIFIED"
            approved += 1
    case_path.write_text("".join(json.dumps(case, ensure_ascii=False) + "\n" for case in cases), encoding="utf-8")

    dataset_path = ROOT / "dataset.json"
    dataset = json.loads(dataset_path.read_text(encoding="utf-8"))
    dataset.update({
        "status": "VERIFIED_PENDING_C1_BASELINE",
        "frozen": False,
        "reviewApproval": {
            "approvedAt": datetime.now(timezone.utc).isoformat(),
            "approvedActiveCaseCount": approved,
            "rejectedCaseCount": len(cases) - approved,
            "note": "Explicit human approval; frozen=true is deferred until the required runtime mapping and C1 baseline run succeed.",
        },
    })
    dataset_path.write_text(json.dumps(dataset, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"verified": approved, "rejected": len(cases) - approved}, ensure_ascii=False))


if __name__ == "__main__":
    main()
