#!/usr/bin/env python3
"""Build a source-backed Workspace/Hexgent RAG-evaluation review pack.

This tool deliberately creates *review candidates*, not a frozen benchmark.
Every answerable case is bound to one real section in a checked-in design
document.  It never calls a retriever to choose Gold evidence.

The pack covers the current, source-backed design documents of both projects.
Historical documents with rules that conflict with the current implementation
are intentionally not included in the business-truth corpus.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import Counter, defaultdict
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATASET = ROOT / "dataset-v2-workspace-design-draft"


# `product-boundary.md` and the root README are deliberately absent.  Both
# contain earlier multi-Workspace statements that conflict with the later
# personal-Workspace contract and current service semantics.
SOURCES = (
    ("workspace-personal", "个人 Workspace 与资源树", "workspace_business", "docs/personal-workspace-design.md"),
    ("workspace-upload", "文档上传与版本一致性", "workspace_business", "docs/document-upload-consistency-design.md"),
    ("workspace-rag", "Workspace RAG V1", "workspace_business", "docs/RAG-V1-Final-Acceptance.md"),
    ("workspace-auth", "Workspace 用户认证", "workspace_business", "docs/user-authentication-design.md"),
    ("hexgent-core", "Hexgent 核心运行机制", "workspace_business", "../hexgent/docs/核心机制设计.md"),
    ("hexgent-tools", "Hexgent 工具系统", "workspace_business", "../hexgent/docs/工具系统设计.md"),
    ("hexgent-context", "Hexgent 上下文管理", "workspace_business", "../hexgent/docs/上下文管理设计.md"),
    ("hexgent-memory", "Hexgent Memory", "workspace_business", "../hexgent/docs/Memory设计.md"),
    ("hexgent-skills", "Hexgent Skill", "workspace_business", "../hexgent/docs/Skill设计.md"),
)

# These cards are deliberately small source-derived contracts, not copied source
# code.  They cover integration modules which have no dedicated design Markdown
# yet.  `source-provenance.jsonl` freezes the exact implementation files from
# which each card was derived, so a code change forces an explicit refresh and
# review instead of silently retaining stale operational claims.
DERIVED_CARDS = (
    {
        "documentId": "workspace-agent-run-control",
        "title": "WS AgentRun Control Contract",
        "sources": (
            "hex-workspace-domain/src/main/java/com/hector/domain/agent/service/impl/AgentRunServiceImpl.java",
        ),
        "content": """# WS AgentRun Control Contract

## Stable business identity

- WS `runId` is the stable logical AgentRun identity. A Conversation can have at most one non-terminal AgentRun.
- Creating a Run locks its Conversation row, records `PENDING`, then dispatches the runtime command outside that creation transaction.

## Start dispatch boundary

- A runtime command acknowledgement does not change WS AgentRun to `RUNNING`; durable lifecycle projection owns that state transition.
- When a network outcome is unknown, the existing `PENDING` Run is retained rather than creating a second Run.

## Control preconditions

- Continue requires `INCOMPLETE`; Pause and Steer require `RUNNING`; Resume requires `PAUSED`; Cancel accepts any non-terminal Run.
- Every control command verifies that the supplied Conversation and user belong to the existing Run before it is dispatched.
""",
    },
    {
        "documentId": "workspace-capability-gateway",
        "title": "WS Capability Gateway Contract",
        "sources": (
            "hex-workspace-domain/src/main/java/com/hector/domain/agent/service/impl/CapabilityGatewayServiceImpl.java",
        ),
        "content": """# WS Capability Gateway Contract

## Protocol validation

- Capability requests must use protocol version `v1` and contain runId, callId and capability before any handler is selected.
- An unknown AgentRun is denied as `RUN_NOT_FOUND`; a non-running Run is denied as `RUN_NOT_ACTIVE`.

## Trusted authority context

- The Gateway obtains userId, workspaceId, conversationId and allowed tools from the persisted AgentRun, not from model-supplied capability arguments.
- A capability outside the persisted allowed-tools boundary is denied as `CAPABILITY_NOT_ALLOWED` before a handler executes.

## Handler isolation

- The Gateway resolves a handler by registered capability name; no registered handler produces `UNKNOWN_CAPABILITY`.
- Handler exceptions are converted to a protocol `FAILED` result with `HANDLER_ERROR` instead of escaping the integration boundary.
""",
    },
    {
        "documentId": "hexgent-runtime-adapter",
        "title": "Hexgent Runtime Adapter Contract",
        "sources": (
            "../hexgent/hexgent/integration/runtime_adapter.py",
        ),
        "content": """# Hexgent Runtime Adapter Contract

## Durable command identity

- `runtime_command_decided` is the durable command-decision fact; in-memory indexes are rebuilt from Transcript at adapter startup.
- A duplicate commandId returns its original decision and must not apply a second runtime effect.

## Stable association

- conversationId resolves to a Hexgent Session, while workspaceRunId remains the logical WS identity.
- Hexgent internal `run_id` identifies an execution segment and may change after Continue or Resume without changing workspaceRunId.

## Recovery boundary

- Runtime state and lifecycle reads are projected from durable Transcript facts rather than requiring an old RunHandle.
- A fresh Engine may restore a Session for a continuable Run; ephemeral locks, streams and handles are not restored as durable state.
""",
    },
    {
        "documentId": "hexgent-tool-permission",
        "title": "Hexgent Tool Permission Contract",
        "sources": (
            "../hexgent/hexgent/tool/tool_permission_pipeline.py",
        ),
        "content": """# Hexgent Tool Permission Contract

## Decision order

- Hard deny rules are evaluated before ask and allow rules, so a permissive Session mode cannot bypass a critical safety refusal.
- The pipeline returns one of allow, ask or deny together with a stable decision source and optional rule identity.

## Workspace boundary

- File reads, writes and edits outside the resolved workspace require confirmation.
- Absolute Glob patterns and parent-directory traversal are treated as paths that can escape the workspace and require confirmation.

## Automatic allowance limits

- Reading and Glob operations inside the workspace can be allowed by explicit rules.
- Plan, Memory and Skill state-management tools have explicit allow rules, while destructive Bash commands retain an ask or deny boundary.
""",
    },
    {
        "documentId": "hexgent-session-recovery",
        "title": "Hexgent Session Recovery Contract",
        "sources": (
            "../hexgent/hexgent/session/session_restore.py",
            "../hexgent/hexgent/session/checkpoint.py",
            "../hexgent/hexgent/session/session_store.py",
        ),
        "content": """# Hexgent Session Recovery Contract

## Transcript authority

- Transcript is the durable source of truth; checkpoints and reports are derived artifacts that can be discarded or rebuilt.
- Recovery validates committed checkpoint identity and replays Transcript facts after the accepted checkpoint boundary.

## Continuation boundary

- Continue is allowed only from a verified continuable state, not by treating an arbitrary historical RunHandle as still alive.
- A restarted process restores a fresh AgentEngine and never persists model streams, locks or live permission waiters as recovery state.

## Incomplete tool calls

- A recovered Function Call without its matching function_call_output is repaired with an explicit unknown-side-effect result.
- This repair preserves model protocol consistency but does not claim that an external side effect did not occur.
""",
    },
    {
        "documentId": "workspace-retrieval-authority",
        "title": "WS Retrieval Authority Contract",
        "sources": (
            "hex-workspace-domain/src/main/java/com/hector/domain/knowledge/service/impl/KnowledgeRetrievalServiceImpl.java",
            "hex-workspace-domain/src/main/java/com/hector/domain/knowledge/service/impl/RetrievalEvaluationServiceImpl.java",
        ),
        "content": """# WS Retrieval Authority Contract

## Candidate versus authority

- Qdrant provides vector candidates; MySQL remains the authority for current Workspace, document, version, folder-chain and active-generation validation.
- A candidate is not returned merely because it has a vector hit; it must pass current authoritative validation.

## Evaluation boundary

- Retrieval evaluation invokes the production retrieval path and records stage timings and diagnostic categories.
- Evaluation credentials grant access to the dedicated internal evaluation surface only; they do not redefine ordinary user authority.

## Result consistency

- Final results are rechecked after optional ranking so a stale candidate cannot become an authoritative hit.
- Retrieval results remain scoped to the requested Workspace rather than accepting a guessed cross-Workspace document identity.
""",
    },
)

UNANSWERABLE = (
    "系统是否已经支持 CRDT 多人实时协同编辑？",
    "能否将一个 Workspace 的所有向量永久复制到 Redis 后删除 Qdrant？",
    "当前 Agent 是否可以绕过用户确认，代表用户提交外部支付？",
    "是否支持把任意 PDF 自动翻译成日文并保存双语版本？",
    "是否已经支持远程下载并自动执行第三方 Skill 脚本？",
    "Memory 是否会自动把每一条用户聊天都提升为跨会话长期事实？",
    "是否已经实现 refresh token 的多设备全端登出管理？",
    "工具缓存是否会跨 Session 永久复用旧的文件读取结果？",
)


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_sections(path: Path):
    heading = None
    body: list[str] = []
    for raw in path.read_text(encoding="utf-8").splitlines():
        if raw.startswith("## "):
            if heading and "\n".join(body).strip():
                yield heading, "\n".join(body).strip()
            heading, body = raw[3:].strip(), []
        elif heading:
            body.append(raw)
    if heading and "\n".join(body).strip():
        yield heading, "\n".join(body).strip()


def slug(value: str) -> str:
    compact = re.sub(r"\s+", "-", value.strip().lower())
    return re.sub(r"[^0-9a-zA-Z\u4e00-\u9fff_-]", "", compact).strip("-") or "section"


def representative_quote(content: str) -> str:
    """Select a compact, auditable sentence rather than an entire section."""
    for raw in content.splitlines():
        line = raw.strip().lstrip("- ").strip()
        if not line or line.startswith("|") or line.startswith("```"):
            continue
        if len(line) >= 16:
            return line[: min(len(line), 240)]
    return next(line.strip() for line in content.splitlines() if line.strip())[:240]


def question_for(document_title: str, section: str) -> tuple[str, str, str]:
    """Create a section-specific review question and its expected retrieval mode.

    These questions are intentionally source-coverage candidates.  They remain
    UNREVIEWED until a reviewer verifies that the chosen quote fully supports
    the wording; this script must not silently turn generated wording into Gold.
    """
    lower = section.lower()
    if "模块边界" in lower:
        return (f"{document_title}的“{section}”如何划分存储、检索与工具模块职责？", "single_hop_semantic", "dense")
    if any(word in lower for word in ("权限", "边界", "范围", "限制")):
        # A boundary question is not automatically a hard-negative case.  The
        # latter requires an explicitly reviewed competing anchor.
        return (f"{document_title}中，关于“{section}”有哪些明确允许与禁止的边界？", "single_hop_semantic", "dense")
    if any(word in lower for word in ("状态", "恢复", "持久化", "一致性", "失败")):
        return (f"{document_title}如何定义“{section}”中的状态、恢复或失败处理约束？", "long_tail", "dense")
    if any(word in lower for word in ("链路", "流程", "入口", "调用")):
        return (f"{document_title}中“{section}”的关键处理流程是什么？", "paraphrase", "dense")
    if any(word in lower for word in ("结构", "模型", "存储", "对象", "模块")):
        return (f"{document_title}里“{section}”由哪些对象或模块负责，各自职责是什么？", "single_hop_semantic", "dense")
    return (f"请说明{document_title}中“{section}”规定的核心行为。", "single_hop_semantic", "dense")


def exact_cases(anchors_by_document: dict[str, list[dict]]):
    """Add a small, deliberately lexical subset with traceable identifiers."""
    candidates = (
        ("workspace-auth", "auth:revoked:session", "`auth:revoked:session` 这类 Redis 撤销记录如何参与认证校验？"),
        ("workspace-upload", "Idempotency-Key", "文档导入的 `Idempotency-Key` 绑定到哪些身份字段？"),
        ("workspace-upload", "executionToken", "UploadTask 的 `executionToken` 在完成或失败更新时防止了什么问题？"),
        ("workspace-rag", "hex_workspace_rag_v1", "RAG V1 使用的 Qdrant collection 标识是什么？"),
        ("hexgent-context", "schema_version = 2", "Hexgent Transcript 使用哪个 schema version，为什么不保存逐字符 delta？"),
        ("hexgent-context", "checkpoint_committed", "Checkpoint 可被恢复接受前必须对应哪一条 Transcript 事实？"),
        ("hexgent-tools", "function_call_output", "工具调用与模型回传结果通过哪个标识保持配对？"),
        ("hexgent-memory", "MEMORY.md", "Hexgent 的 `MEMORY.md` 是 Memory 内容权威，还是可再生索引？"),
        ("hexgent-skills", "allowed_tools", "Skill frontmatter 中的 `allowed_tools` 如何影响运行时可见工具？"),
        ("hexgent-core", "tool_result_persisted", "大工具结果外置后，Transcript 用哪类事实记录其 artifact 引用？"),
    )
    rows = []
    for index, (document_id, marker, query) in enumerate(candidates, start=1):
        anchor = next((item for item in anchors_by_document[document_id] if marker in item["content"]), None)
        if anchor is None:
            raise ValueError(f"exact candidate marker not found: {document_id} / {marker}")
        quote = next(line.strip().lstrip("- ") for line in anchor["content"].splitlines() if marker in line)
        rows.append(case(
            f"workspace-design-exact-{index:02d}", "synthetic_hard", query, "exact_lexical", "HARD",
            anchor, quote, "sparse", "synthetic",
            "精确标识符候选；需要审核 quote 是否足以回答。",
        ))
    return rows


def case(query_id, suite, query, query_type, difficulty, anchor, quote, strength, method, notes):
    return {
        "queryId": query_id,
        "suite": suite,
        "query": query,
        "queryType": query_type,
        "difficulty": difficulty,
        "answerable": True,
        "relevantDocumentIds": [anchor["documentId"]],
        "relevantChunkIds": [anchor["chunkId"]],
        "evidence": [{"chunkId": anchor["chunkId"], "quote": quote, "relevanceGrade": 3}],
        "hardNegativeChunkIds": [],
        "hardNegativeReasons": {},
        "expectedRetrievalStrength": strength,
        "generationMethod": method,
        "reviewStatus": "UNREVIEWED",
        "notes": notes,
    }


def build(output: Path) -> dict:
    corpus = output / "corpus"
    cases_dir = output / "cases"
    corpus.mkdir(parents=True, exist_ok=True)
    cases_dir.mkdir(parents=True, exist_ok=True)

    source_dir = corpus / "source"
    source_dir.mkdir(parents=True, exist_ok=True)
    all_sources = list(SOURCES)
    provenance = []
    for card in DERIVED_CARDS:
        card_path = source_dir / f'{card["documentId"]}.md'
        card_path.write_text(card["content"], encoding="utf-8")
        source_file = str(card_path.relative_to(ROOT.parent))
        all_sources.append((card["documentId"], card["title"], "workspace_business", source_file))
        provenance.append({
            "documentId": card["documentId"],
            "derivedFrom": [
                {"sourceFile": path, "sourceSha256": sha256((ROOT.parent / path).resolve())}
                for path in card["sources"]
            ],
        })

    manifest, anchors, anchors_by_document = [], [], defaultdict(list)
    for document_id, title, suite, source_file in all_sources:
        source = (ROOT.parent / source_file).resolve()
        if not source.is_file():
            raise FileNotFoundError(source)
        manifest.append({
            "documentId": document_id, "title": title, "suite": suite,
            "sourceFile": source_file, "sourceSha256": sha256(source),
            "sourceRole": "current_design_or_runtime_contract",
        })
        for section, content in read_sections(source):
            anchor = {
                "chunkId": f"{document_id}#{slug(section)}", "documentId": document_id,
                "section": section, "contentSha256": hashlib.sha256(content.encode()).hexdigest(),
                "content": content,
            }
            anchors.append(anchor)
            anchors_by_document[document_id].append(anchor)

    exact = exact_cases(anchors_by_document)
    exact_anchor_ids = {
        row["relevantChunkIds"][0]
        for row in exact
    }
    cases = []
    for anchor in anchors:
        # The exact-lexical question is the dedicated candidate for this
        # anchor.  Do not also emit a generic title question with identical
        # evidence just to inflate coverage.
        if anchor["chunkId"] in exact_anchor_ids:
            continue
        title = next(item[1] for item in all_sources if item[0] == anchor["documentId"])
        query, query_type, strength = question_for(title, anchor["section"])
        cases.append(case(
            f"workspace-design-section-{len(cases) + 1:03d}", "workspace_business", query,
            query_type, "MEDIUM", anchor, representative_quote(anchor["content"]), strength,
            "synthetic", "按模块 section 生成的覆盖候选；必须人工复核语义和证据充分性。",
        ))
    cases.extend(exact)
    for index, query in enumerate(UNANSWERABLE, start=1):
        cases.append({
            "queryId": f"workspace-design-unanswerable-{index:02d}", "suite": "synthetic_hard",
            "query": query, "queryType": "unanswerable", "difficulty": "MEDIUM",
            "answerable": False, "relevantDocumentIds": [], "relevantChunkIds": [], "evidence": [],
            "hardNegativeChunkIds": [], "hardNegativeReasons": {}, "expectedRetrievalStrength": "unknown",
            "generationMethod": "manual", "reviewStatus": "UNREVIEWED",
            "notes": "当前冻结语料没有支持该能力的证据；审核时需要确认语料范围未遗漏。",
        })

    (corpus / "manifest.jsonl").write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in manifest), encoding="utf-8")
    (corpus / "chunks.jsonl").write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in anchors), encoding="utf-8")
    (corpus / "source-provenance.jsonl").write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in provenance), encoding="utf-8")
    (cases_dir / "test.jsonl").write_text(
        "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in cases), encoding="utf-8")
    dataset = {
        "datasetVersion": "rag-eval-v2-workspace-design-draft.1", "status": "UNREVIEWED_SOURCE_COVERAGE",
        "purpose": "source-backed corpus and candidate-query coverage review; not a frozen quality benchmark",
        "frozen": False,
        "excludedHistoricalSources": ["docs/product-boundary.md", "README.md"],
    }
    (output / "dataset.json").write_text(json.dumps(dataset, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return coverage(manifest, anchors, cases)


def coverage(manifest, anchors, cases):
    answerable = [row for row in cases if row["answerable"]]
    represented_documents = {document_id for row in answerable for document_id in row["relevantDocumentIds"]}
    represented_anchors = {chunk_id for row in answerable for chunk_id in row["relevantChunkIds"]}
    return {
        "sourceDocumentCount": len(manifest),
        "canonicalAnchorCount": len(anchors),
        "candidateQueryCount": len(cases),
        "answerableCandidateCount": len(answerable),
        "unanswerableCandidateCount": len(cases) - len(answerable),
        "documentCoverage": {"covered": len(represented_documents), "total": len(manifest),
                             "ratio": len(represented_documents) / len(manifest)},
        "anchorCoverage": {"covered": len(represented_anchors), "total": len(anchors),
                           "ratio": len(represented_anchors) / len(anchors)},
        "suiteDistribution": dict(sorted(Counter(row["suite"] for row in cases).items())),
        "queryTypeDistribution": dict(sorted(Counter(row["queryType"] for row in cases).items())),
        "reviewStatusDistribution": dict(sorted(Counter(row["reviewStatus"] for row in cases).items())),
        "perDocumentAnchorCount": dict(sorted(Counter(anchor["documentId"] for anchor in anchors).items())),
        "readyForQualityBenchmark": False,
        "blockers": [
            "所有自动生成 candidate 均为 UNREVIEWED，不能作为正式质量指标。",
            "当前 pack 尚未加入 hard-negative pair 和 required/alternative multi-hop 语义。",
            "历史设计已排除；后续新增语料必须先标明与当前代码的一致性。",
        ],
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_DATASET)
    args = parser.parse_args()
    report = build(args.output)
    (args.output / "coverage-report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
