#!/usr/bin/env python3
"""Materialize the governed Workspace Business benchmark candidate.

This migration keeps the draft corpus intact. It makes legacy Gold semantics
explicit, records only source-audited alternative evidence, and leaves the
candidate REVIEWED rather than prematurely declaring it VERIFIED/FROZEN.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1] / "dataset-v2-workspace-business-full-v1"


def lines(path: Path):
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def quote(chunk_content: dict[str, str], chunk_id: str, marker: str):
    return next(line.strip() for line in chunk_content[chunk_id].splitlines() if marker in line)


def evidence(chunk_content, chunk_id, marker, grade=3):
    return {"chunkId": chunk_id, "quote": quote(chunk_content, chunk_id, marker), "relevanceGrade": grade}


def exact_groups(case):
    by_chunk = {}
    for item in case["evidence"]:
        by_chunk.setdefault(item["chunkId"], []).append(item)
    return [
        {"requirementId": f"legacy-{index + 1}", "evidence": by_chunk[chunk_id]}
        for index, chunk_id in enumerate(case["relevantChunkIds"])
    ]


def set_groups(case, groups, *, status="REVIEWED", note_suffix=""):
    case["evidenceRequirementGroups"] = groups
    case["evidence"] = [item for group in groups for item in group["evidence"]]
    case["relevantChunkIds"] = list(dict.fromkeys(item["chunkId"] for item in case["evidence"]))
    case["relevantDocumentIds"] = sorted({item["chunkId"].split("#", 1)[0] for item in case["evidence"]})
    case["reviewStatus"] = status
    if note_suffix:
        case["notes"] = f"{case['notes']} {note_suffix}".strip()


def single_group(requirement_id, alternatives, rationale=None):
    group = {"requirementId": requirement_id, "evidence": alternatives}
    if rationale:
        group["equivalenceRationale"] = rationale
    return group


def main():
    chunks = {row["chunkId"]: row["content"] for row in lines(ROOT / "corpus" / "chunks.jsonl")}
    generated_case_ids = {"v1-multihop-001", "v1-multihop-002", "v1-multihop-003", "v1-stream-failure-001"}
    # The governance materialization is intentionally rerunnable. Remove only
    # its own generated cases before rebuilding them; historical draft cases
    # remain untouched.
    cases = [case for case in lines(ROOT / "cases" / "test.jsonl") if case["queryId"] not in generated_case_ids]
    by_id = {case["queryId"]: case for case in cases}

    for case in cases:
        if case["answerable"]:
            case["evidenceRequirementGroups"] = exact_groups(case)
        else:
            case["evidenceRequirementGroups"] = []

    # Source-audited alternative evidence: the alternatives independently answer
    # the same minimum fact. They were not selected merely because C1 retrieved
    # them.
    alternatives = {
        "arch-002": single_group("mysql-authority-validation", [
            evidence(chunks, "ws-architecture-and-boundaries#当前资料事实边界", "必须由 MySQL 复核"),
            evidence(chunks, "ws-retrieval-and-evaluation#检索调用链", "MySQL 是资源有效性和权限语义的权威来源"),
        ], "Both sources independently establish that Qdrant is only candidate retrieval and MySQL is the authoritative validity boundary."),
        "doc-002": single_group("exact-version-no-fallback", [
            evidence(chunks, "ws-document-version-and-upload#内容身份", "不得在旧版本不可读时静默回退最新版本"),
            evidence(chunks, "ws-capability-read-contracts#get_document", "不能贴心地回退到最新版"),
        ], "Both contracts independently prohibit latest-version fallback for an exact documentId/versionId read."),
        "wb-048": single_group("accepted-vs-applied", [
            evidence(chunks, "hexgent-ws-integration-streams#control-与稳定关联", "Command ACCEPTED 与 effect APPLIED 是不同事实"),
            evidence(chunks, "ws-agent-command-reconciliation#command-effect-边界", "区分 `ACCEPTED + NONE` 与已经 `APPLIED`"),
        ], "Both sources distinguish accepted command decision from effect application and require idempotent reconciliation."),
        "hard-002": single_group("internal-service-not-user-jwt", [
            evidence(chunks, "ws-authentication-and-session#安全边界", "不能回退为前端 JWT"),
            evidence(chunks, "ws-internal-service-authentication#浏览器与服务调用分离", "必须走独立的 service token 校验"),
        ], "Both sources independently state that internal service endpoints do not accept a frontend JWT as their caller credential."),
        "hard-009": single_group("stream-is-not-lifecycle-authority", [
            evidence(chunks, "hexgent-ws-integration-streams#权威-lifecycle", "Stream event 不能直接把 WS Run 改成 RUNNING"),
            evidence(chunks, "ws-lifecycle-event-idempotency#事实来源", "Stream event 只供 UI 观察，不能决定 AgentRun business status"),
        ], "Both sources independently prohibit Stream data from projecting WS business lifecycle state."),
    }
    for case_id, group in alternatives.items():
        set_groups(by_id[case_id], [group], note_suffix="已完成 source-level 等价证据审查，待人工签核。")

    # These are no longer labeled multi-hop: one cited source independently
    # answers the actual query. Keep them as REVIEWED single-hop cases instead
    # of inflating the multi-hop denominator.
    for case_id in ("auth-003", "wb-006", "wb-046"):
        case = by_id[case_id]
        case["queryType"] = "single_hop_semantic"
        case["expectedRetrievalStrength"] = "dense"
        set_groups(case, [exact_groups(case)[0]], note_suffix="原 multi-hop 标记复核后降为 single-hop：一段证据已独立回答问题。")

    # run-004 and wb-023 test the same lifecycle-gap condition. Preserve the
    # rejected record for traceability and retain wb-023 as the canonical case.
    by_id["run-004"]["reviewStatus"] = "REJECTED"
    by_id["run-004"]["notes"] += " 治理结论：与 wb-023 属于同一检索条件，保留记录但不纳入评分。"
    by_id["wb-023"]["queryType"] = "single_hop_semantic"
    by_id["wb-023"]["expectedRetrievalStrength"] = "dense"
    set_groups(by_id["wb-023"], [exact_groups(by_id["wb-023"])[0]], note_suffix="原 multi-hop 标记复核后降为 single-hop。")

    # This query conflates external Stream sequence with lifecycle sequence;
    # the cited source only governs the latter. It remains auditable but cannot
    # be scored until a human rewrites it against a documented Stream contract.
    by_id["hard-016"]["reviewStatus"] = "REJECTED"
    by_id["hard-016"]["notes"] += " 治理结论：问题混淆 Stream 与 Lifecycle sequence，现有 Gold 不足以支持原命题。"

    # Current C1 returned related evidence for these two cases, but source audit
    # does not yet prove answer sufficiency. They deliberately remain REVIEWED.
    for case_id in ("integration-001", "hard-018", "wb-012"):
        by_id[case_id]["reviewStatus"] = "REVIEWED"
        by_id[case_id]["notes"] += " C1 命中相关文本，但替代 Gold 是否满足完整问题仍待人工裁定。"

    # Add genuinely multi-hop requirements. Neither question is satisfied by
    # only one required group.
    cases.extend([
        {
            "queryId": "v1-multihop-001", "suite": "workspace_business",
            "query": "创建文档成功后，哪些事实分别证明 V1 已正式可读，以及它何时才可被 search_workspace 检索？",
            "queryType": "multi_hop", "difficulty": "HARD", "answerable": True,
            "hardNegativeChunkIds": [], "hardNegativeReasons": {}, "expectedRetrievalStrength": "multi_hop",
            "generationMethod": "manual", "reviewStatus": "REVIEWED",
            "notes": "两个独立事实：正式版本提交，与 Active Generation 检索可见性。",
            "evidenceRequirementGroups": [
                single_group("formal-version-readable", [evidence(chunks, "ws-capability-write-contracts#update_document", "SUCCEEDED 必须意味着正式 DocumentVersion 已存在且 Document ACTIVE")]),
                single_group("active-generation-searchable", [evidence(chunks, "ws-index-worker-and-leases#异步工作单元", "检索可见性要等待对应 Active Generation")]),
            ],
        },
        {
            "queryId": "v1-multihop-002", "suite": "synthetic_hard",
            "query": "Runtime 重启后继续同一 WS Run 时，既要如何创建新的执行对象，又为何不能沿用 checkpoint 中旧的业务权限？",
            "queryType": "multi_hop", "difficulty": "HARD", "answerable": True,
            "hardNegativeChunkIds": [], "hardNegativeReasons": {}, "expectedRetrievalStrength": "multi_hop",
            "generationMethod": "manual", "reviewStatus": "REVIEWED",
            "notes": "两个独立事实：fresh engine hydration，与 authority recheck。",
            "evidenceRequirementGroups": [
                single_group("fresh-engine", [evidence(chunks, "hexgent-checkpoint-restorer-and-continuation#fresh-engine-hydration", "创建/hydrate 新 AgentEngine")]),
                single_group("authority-not-in-checkpoint", [evidence(chunks, "hexgent-checkpoint-restorer-and-continuation#checkpoint-语义", "不保存 WS business authority")]),
            ],
        },
        {
            "queryId": "v1-multihop-003", "suite": "synthetic_hard",
            "query": "用户拒绝工具权限后，浏览器为何不需要知道 Hexgent internal run id，同时 Agent 为什么不应自动失败？",
            "queryType": "multi_hop", "difficulty": "HARD", "answerable": True,
            "hardNegativeChunkIds": [], "hardNegativeReasons": {}, "expectedRetrievalStrength": "multi_hop",
            "generationMethod": "manual", "reviewStatus": "REVIEWED",
            "notes": "两个独立事实：浏览器身份边界，与正常 DENY 的运行语义。",
            "evidenceRequirementGroups": [
                single_group("browser-no-internal-run", [evidence(chunks, "hexgent-permission-pending-and-resolution#正式-integration-路径", "不能要求浏览器掌握 Hexgent internal run id")]),
                single_group("deny-does-not-fail-run", [evidence(chunks, "hexgent-permission-pending-and-resolution#allow-与-deny-结果", "不应因正常 deny 自动 FAILED/CANCELLED")]),
            ],
        },
        {
            "queryId": "v1-stream-failure-001", "suite": "workspace_business",
            "query": "模型流式响应失败但 StartRun 已经 ACCEPTED 时，哪一个 durable fact 驱动 WS 的失败生命周期，而 UI waiting 又必须如何结束？",
            "queryType": "single_hop_semantic", "difficulty": "HARD", "answerable": True,
            "hardNegativeChunkIds": [], "hardNegativeReasons": {}, "expectedRetrievalStrength": "dense",
            "generationMethod": "manual", "reviewStatus": "REVIEWED",
            "notes": "使流式失败 canonical anchor 成为被测、必须映射的正式证据。",
            "evidenceRequirementGroups": [
                single_group("stream-failure-lifecycle", [evidence(chunks, "hexgent-model-response-and-function-calls#流式失败", "agent_run_finished(status=failed)")]),
            ],
        },
    ])

    for case in cases:
        if case["answerable"]:
            if "evidenceRequirementGroups" not in case:
                case["evidenceRequirementGroups"] = exact_groups(case)
            case["evidence"] = [item for group in case["evidenceRequirementGroups"] for item in group["evidence"]]
            case["relevantChunkIds"] = list(dict.fromkeys(item["chunkId"] for item in case["evidence"]))
            case["relevantDocumentIds"] = sorted({item["chunkId"].split("#", 1)[0] for item in case["evidence"]})
        else:
            case["evidenceRequirementGroups"] = []

    target = ROOT / "cases" / "test.jsonl"
    target.write_text("".join(json.dumps(case, ensure_ascii=False) + "\n" for case in cases), encoding="utf-8")

    manifest = lines(ROOT / "corpus" / "manifest.jsonl")
    for item in manifest:
        item["sourceFile"] = item["sourceFile"].replace("dataset-v2-workspace-architecture-draft", "dataset-v2-workspace-business-full-v1")
    (ROOT / "corpus" / "manifest.jsonl").write_text("".join(json.dumps(item, ensure_ascii=False) + "\n" for item in manifest), encoding="utf-8")

    dataset_path = ROOT / "dataset.json"
    dataset = json.loads(dataset_path.read_text(encoding="utf-8"))
    dataset.update({
        "datasetVersion": "workspace-business-full-v1",
        "status": "GOVERNANCE_REVIEW_PENDING",
        "frozen": False,
        "goldModel": "evidence-requirement-groups-v1",
        "purpose": "governed Workspace Business benchmark candidate; REVIEWED cases await human verification before a frozen baseline is produced",
        "targetComposition": {"workspaceBusiness": 146, "syntheticHard": 74},
    })
    dataset_path.write_text(json.dumps(dataset, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    manifest_hash = hashlib.sha256(target.read_bytes()).hexdigest()
    governance = ROOT / "governance"
    governance.mkdir(exist_ok=True)
    (governance / "migration.json").write_text(json.dumps({
        "sourceDataset": "rag-eval-v2-workspace-architecture-draft.2",
        "targetDataset": "workspace-business-full-v1",
        "caseFileSha256": manifest_hash,
        "adjudicatedEquivalentEvidence": sorted(alternatives),
        "rejectedCases": ["run-004", "hard-016"],
        "newMultiHopCases": ["v1-multihop-001", "v1-multihop-002", "v1-multihop-003"],
    }, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote governed candidate: {len(cases)} cases")


if __name__ == "__main__":
    main()
