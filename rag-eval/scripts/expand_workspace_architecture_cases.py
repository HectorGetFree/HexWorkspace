#!/usr/bin/env python3
"""Append curated, evidence-bound cases for the expanded Workspace corpus.

The prompts below are deliberately authored as distinct operational questions. This
is not a heading-to-question template: every tuple names a chosen evidence marker
and has been written to cover one concrete boundary from the source document.
"""
from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1] / "dataset-v2-workspace-architecture-draft"


def anchors() -> dict[str, str]:
    return {
        row["chunkId"]: row["content"]
        for row in (json.loads(line) for line in (ROOT / "corpus" / "chunks.jsonl").read_text(encoding="utf-8").splitlines() if line.strip())
    }


def evidence_line(content: str, marker: str) -> str:
    for line in content.splitlines():
        if marker in line:
            return line.strip()
    raise ValueError(f"missing evidence marker: {marker}")


def row(query_id: str, suite: str, query: str, query_type: str, difficulty: str, chunk_id: str, marker: str, strength: str, notes: str) -> dict:
    content = anchors()[chunk_id]
    return {
        "queryId": query_id,
        "suite": suite,
        "query": query,
        "queryType": query_type,
        "difficulty": difficulty,
        "answerable": True,
        "relevantDocumentIds": [chunk_id.split("#", 1)[0]],
        "relevantChunkIds": [chunk_id],
        "evidence": [{"chunkId": chunk_id, "quote": evidence_line(content, marker), "relevanceGrade": 3}],
        "hardNegativeChunkIds": [],
        "hardNegativeReasons": {},
        "expectedRetrievalStrength": strength,
        "generationMethod": "manual",
        "reviewStatus": "UNREVIEWED",
        "notes": notes,
    }


# query id, suite, question, type, difficulty, canonical anchor, selected source marker, expected signal, review note
CASES = [
    ("full-cmd-01", "workspace_business", "Runtime 接收 StartRun 的 ACCEPTED 回包后，WS 能立即把业务 Run 标为 RUNNING 吗？", "hard_negative", "HARD", "ws-agent-command-reconciliation#命令提交不是状态转换", "ACCEPTED` 只表示 Runtime 已持久化", "rerank", "Command ACK 与 Lifecycle started 的边界。"),
    ("full-cmd-02", "workspace_business", "WS 发现自己的 lifecycle_sequence 落后时，如何补齐遗漏事件而不直接改数据库终态？", "single_hop_semantic", "HARD", "ws-agent-command-reconciliation#显式-reconciliation", "复用 Lifecycle Projector 投影", "dense", "对账必须复用投影器。"),
    ("full-cmd-03", "synthetic_hard", "为什么重试对账时要区分 ACCEPTED + NONE 和 APPLIED，而不是对每个命令重新执行？", "hard_negative", "HARD", "ws-agent-command-reconciliation#command-effect-边界", "重复 reconcile 不能再次产生 effect", "rerank", "effect 幂等性。"),
    ("full-auth-01", "workspace_business", "浏览器 JWT 能否直接拿来调用 WS 的 lifecycle callback 内部接口？", "hard_negative", "HARD", "ws-internal-service-authentication#浏览器与服务调用分离", "必须走独立的 service token 校验", "rerank", "内部认证与前端认证分离。"),
    ("full-auth-02", "synthetic_hard", "如果 Hexgent 调 lifecycle callback 时 service token 缺失，接口应该怎样处理？", "single_hop_semantic", "MEDIUM", "ws-internal-service-authentication#lifecycle-callback", "拒绝为 401", "dense", "callback token 缺失。"),
    ("full-auth-03", "workspace_business", "Capability 请求里的 workspaceId 是谁提供的可信事实，为什么不能相信模型 arguments？", "hard_negative", "HARD", "ws-internal-service-authentication#capability-invoke", "从持久化 AgentRun 构造 trusted CapabilityContext", "rerank", "模型参数不是业务身份。"),
    ("full-life-01", "workspace_business", "为什么 LifecycleEvent 的 sequence 要按 workspace run 而不是 Hexgent internal run_id 连续编号？", "single_hop_semantic", "HARD", "ws-lifecycle-event-idempotency#事件身份与顺序", "同一逻辑 Run 跨多个执行段时，序号仍连续", "dense", "逻辑 Run 跨 execution segment。"),
    ("full-life-02", "workspace_business", "一个 terminal AgentRun 收到迟到的 RUNNING 类事件时，WS 应允许它复活吗？", "hard_negative", "HARD", "ws-lifecycle-event-idempotency#合法状态转换", "terminal AgentRun 不能被迟到事件 resurrect", "rerank", "终态防复活。"),
    ("full-life-03", "synthetic_hard", "WS 为什么不能把 Hexgent Core Event 直接暴露后当作权威业务生命周期？", "hard_negative", "HARD", "ws-lifecycle-event-idempotency#事实来源", "Transcript durable facts 是 lifecycle 的 Runtime source of truth", "rerank", "事实投影而非 Core Event 直通。"),
    ("full-read-01", "workspace_business", "list_folder 返回给 Agent 的 versionId 应该代表什么，而不是 Handler 自己查询的所谓最新版本？", "single_hop_semantic", "MEDIUM", "ws-capability-read-contracts#list_folder", "当前权威版本身份", "dense", "current version identity。"),
    ("full-read-02", "workspace_business", "为什么 search_workspace 的 Handler 不应自己完成 Qdrant、MySQL 和权限校验的完整检索流程？", "hard_negative", "HARD", "ws-capability-read-contracts#search_workspace", "Handler 不应演化为第二个 Retrieval Service", "rerank", "薄 Handler 边界。"),
    ("full-read-03", "synthetic_hard", "指定 V1 调 get_document 失败时，系统可否改读 current V2 给 Agent？", "hard_negative", "HARD", "ws-capability-read-contracts#get_document", "不能贴心地回退到最新版", "rerank", "精确版本引用。"),
    ("full-write-01", "workspace_business", "两次更新都以 V3 作为 expectedVersionId，为什么失败方不能自动以 V4 再创建 V5？", "hard_negative", "HARD", "ws-capability-write-contracts#update_document", "失败方不能自动基于 V4 创建 V5", "rerank", "OCC 冲突语义。"),
    ("full-write-02", "workspace_business", "create_document 因网络丢失响应被同一 runId/callId 重试，会不会创建第二份文档？", "single_hop_semantic", "HARD", "ws-capability-write-contracts#create_document", "不能产生 D2 或第二个 V1", "dense", "create durable idempotency。"),
    ("full-write-03", "synthetic_hard", "同一个 capability commandId 但 title/content fingerprint 不同，WS 应该怎样处理已有 mutation？", "exact_lexical", "HARD", "ws-capability-write-contracts#mutation-recovery", "IDEMPOTENCY_CONFLICT", "sparse", "fingerprint conflict。"),
    ("full-index-01", "workspace_business", "为什么 create/update capability 成功后，不能立即保证 search_workspace 已检索到新内容？", "single_hop_semantic", "MEDIUM", "ws-index-worker-and-leases#异步工作单元", "不同阶段", "dense", "写入成功与检索可见性不同。"),
    ("full-index-02", "synthetic_hard", "知识索引 Worker 的租约失效后，为什么旧执行者必须停下？", "hard_negative", "HARD", "ws-index-worker-and-leases#执行租约", "不应由旧 Worker 继续发布结果", "rerank", "lease fencing。"),
    ("full-index-03", "workspace_business", "新 generation 构建失败时，检索系统应该用失败 generation 还是保留哪一版？", "single_hop_semantic", "HARD", "ws-index-worker-and-leases#完成与失败", "旧 active generation 在新 generation 成功前仍是权威检索版本", "dense", "active generation 发布语义。"),
    ("full-rank-01", "workspace_business", "candidateLimit=30 与 topK=5 在检索调用里各自控制什么？", "exact_lexical", "MEDIUM", "ws-retrieval-ranking-and-latency#三段检索", "candidateLimit 决定进入 rerank 的候选上界", "sparse", "候选窗口与返回窗口。"),
    ("full-rank-02", "synthetic_hard", "若正确证据没有进候选集合，单独加强 rerank 能解决吗？", "hard_negative", "HARD", "ws-retrieval-ranking-and-latency#rerank-边界", "无法救回不在 candidate set 的证据", "rerank", "rerank 作用范围。"),
    ("full-rank-03", "workspace_business", "一次 p99 很高，怎样判断慢点是在 embedding、Qdrant 还是 authority recheck？", "single_hop_semantic", "MEDIUM", "ws-retrieval-ranking-and-latency#延迟解读", "记录", "dense", "分段延迟归因。"),
    ("full-eval-01", "workspace_business", "RAG V2 为什么要把 canonical anchor 映射成 documentCode、generationId、chunkNo，而不是绑定 Qdrant point id？", "single_hop_semantic", "HARD", "ws-evaluation-runner-and-freeze#冻结-corpus", "不依赖 Qdrant point id", "dense", "稳定 gold 身份。"),
    ("full-eval-02", "synthetic_hard", "同一篇文档命中了错误段落，document hit 成功能否说明 RAG 找到了证据？", "hard_negative", "HARD", "ws-evaluation-runner-and-freeze#chunk-指标", "不能证明证据真的被找回", "rerank", "chunk primary metric。"),
    ("full-eval-03", "workspace_business", "为什么带 UNREVIEWED 的评测 run 不能作为正式 benchmark 分数？", "single_hop_semantic", "MEDIUM", "ws-evaluation-runner-and-freeze#可比性", "不能被当成正式 benchmark", "dense", "review/freeze 流程。"),
    ("full-http-01", "workspace_business", "WS Controller 可以直接操作 MySQL mapper 来调用 Hexgent 吗？", "hard_negative", "HARD", "ws-http-api-and-error-envelope#controller-职责", "不应直接访问 Hexgent Core 或 MySQL mapper", "rerank", "Trigger 分层。"),
    ("full-http-02", "synthetic_hard", "StartRun HTTP timeout 能直接被投影为 Runtime FAILED 吗？", "hard_negative", "HARD", "ws-http-api-and-error-envelope#response-与错误", "不能混为同一 Runtime FAILED 状态", "rerank", "unknown outcome。"),
    ("full-http-03", "workspace_business", "SSE proxy 遇到浏览器主动断开时，为什么不应该统一写成系统繁忙？", "long_tail", "HARD", "ws-http-api-and-error-envelope#sse-与长连接", "预期连接关闭", "dense", "async timeout/连接关闭边界。"),
    ("full-artifact-01", "workspace_business", "读取历史版本时，Parsed Artifact 应怎样保证没有误读 current version 的内容？", "single_hop_semantic", "HARD", "ws-document-artifacts-and-parsing#正式对象", "与 sourceChecksum/version identity 对齐", "dense", "精确版本 artifact。"),
    ("full-artifact-02", "synthetic_hard", "部分执行的 UploadTask 重试为什么不能新建 target version？", "hard_negative", "HARD", "ws-document-artifacts-and-parsing#uploadtask", "重试产生新任务/新版本会破坏幂等性", "rerank", "partial execution。"),
    ("full-artifact-03", "workspace_business", "文档能 get_document 读取，是否必然意味着 search_workspace 已能命中它？", "hard_negative", "MEDIUM", "ws-document-artifacts-and-parsing#解析与索引的关系", "已经可被向量检索命中", "rerank", "readability vs searchability。"),
    ("full-folder-01", "workspace_business", "Folder 移动时最关键要避免什么树结构错误？", "single_hop_semantic", "MEDIUM", "ws-folder-deletion-and-authority#目录树约束", "避免循环引用", "dense", "Folder tree invariant。"),
    ("full-folder-02", "workspace_business", "某份文档过去可读，但父 Folder 现在已被删除，get_document 还能作为历史后门成功吗？", "hard_negative", "HARD", "ws-folder-deletion-and-authority#删除后的行为", "不是绕过当前 Folder/Document authority 的后门", "rerank", "current authority recheck。"),
    ("full-folder-03", "synthetic_hard", "Capability Handler 应该自己判断 Folder owner/status，还是交给哪里？", "hard_negative", "HARD", "ws-folder-deletion-and-authority#能力层边界", "调用已有领域服务", "rerank", "Handler 非权限中心。"),
    ("full-model-01", "workspace_business", "为什么 Agent loop 不能只保留模型响应里的文本，而要保留完整 response.output？", "single_hop_semantic", "HARD", "hexgent-model-response-and-function-calls#responses-输出保留", "可能同时含文本、function call", "dense", "Responses protocol。"),
    ("full-model-02", "synthetic_hard", "Function continuation 回传工具结果时，为什么必须使用最初 function call 的 call_id？", "exact_lexical", "HARD", "hexgent-model-response-and-function-calls#responses-输出保留", "使用原始 `call_id`", "sparse", "call correlation。"),
    ("full-model-03", "workspace_business", "模型工具 schema 不合法导致 provider 400，应该先怀疑 WS capability handler 吗？", "hard_negative", "HARD", "hexgent-model-response-and-function-calls#tool-schema", "不能被误诊为 Workspace capability handler 失败", "rerank", "schema error classification。"),
    ("full-registry-01", "workspace_business", "所有 Hexgent 内置工具会自动暴露给每一个 WS AgentRun 吗？", "hard_negative", "MEDIUM", "hexgent-tool-registry-validation-scheduler#registry-与暴露", "不是所有内置工具都应自动对每个 WS Run 暴露", "rerank", "Tool Registry exposure。"),
    ("full-registry-02", "workspace_business", "模型给 tool arguments 带了伪造身份字段，哪一层首先把它当作不可信输入处理？", "single_hop_semantic", "HARD", "hexgent-tool-registry-validation-scheduler#调用前验证", "模型参数不是 credential", "dense", "Tool validation boundary。"),
    ("full-registry-03", "synthetic_hard", "多个工具并发后恢复时，为什么不能靠再次 dispatch 猜测哪个已经做过副作用？", "hard_negative", "HARD", "hexgent-tool-registry-validation-scheduler#并发与顺序", "以 durable transcript/tool result 判断", "rerank", "recovery evidence。"),
    ("full-stream-01", "workspace_business", "为什么 Browser UI 不应依赖 Hexgent Core AgentEvent 的字段？", "single_hop_semantic", "MEDIUM", "hexgent-agent-events-and-external-stream#core-event-不外泄", "不应依赖其字段或 internal run id", "dense", "external stream boundary。"),
    ("full-stream-02", "workspace_business", "SSE envelope 中 integrationRunId 对应哪个系统的稳定身份？", "exact_lexical", "MEDIUM", "hexgent-agent-events-and-external-stream#stream-envelope", "对应 WS logical runId", "sparse", "run identity mapping。"),
    ("full-stream-03", "synthetic_hard", "收到一条 ToolFinished SSE 能直接把 WS AgentRun 标成 SUCCEEDED 吗？", "hard_negative", "HARD", "hexgent-agent-events-and-external-stream#浏览器代理", "权威 RUNNING、FAILED、SUCCEEDED 仍来自 lifecycle callback/projector", "rerank", "stream non-authoritative。"),
    ("full-perm-01", "workspace_business", "工具正在等待人工确认时，为什么 AgentRun 仍是 RUNNING 而不是新增 WAITING？", "single_hop_semantic", "MEDIUM", "hexgent-permission-pending-and-resolution#ask-的等待语义", "仍保持 RUNNING，不新建 WAITING business state", "dense", "V1 permission state boundary。"),
    ("full-perm-02", "workspace_business", "前端处理 PermissionRequest 为什么只需要 workspaceRunId 和 requestId，而不该得到 internal run id？", "single_hop_semantic", "HARD", "hexgent-permission-pending-and-resolution#正式-integration-路径", "不能要求浏览器掌握 Hexgent internal run id", "dense", "permission integration identity。"),
    ("full-perm-03", "synthetic_hard", "用户点 DENY 后工具没有执行，AgentRun 是否必然自动变成 FAILED？", "hard_negative", "HARD", "hexgent-permission-pending-and-resolution#allow-与-deny-结果", "不应因正常 deny 自动 FAILED/CANCELLED", "rerank", "deny normal continuation。"),
    ("full-transcript-01", "workspace_business", "哪些 Runtime facts 在回 ACK 前必须确保 durable append 成功？", "single_hop_semantic", "HARD", "hexgent-transcript-facts-and-projectors#durable-fact-log", "commit-point 事实需 durable append 成功后才能对外 Ack", "dense", "durable decision commit point。"),
    ("full-transcript-02", "workspace_business", "LifecycleProjector 为什么应读取 Transcript facts，而不是订阅瞬时 Core Event？", "hard_negative", "HARD", "hexgent-transcript-facts-and-projectors#lifecycle-projector", "从 Transcript durable facts 产生 deterministic LifecycleEvent", "rerank", "deterministic projection。"),
    ("full-transcript-03", "synthetic_hard", "Runtime adapter reload 后恢复 workspaceRunId 关联，是不是意味着把旧 RunHandle 从磁盘还原？", "hard_negative", "HARD", "hexgent-transcript-facts-and-projectors#关联恢复", "不表示恢复旧 RunHandle", "rerank", "ephemeral handle boundary。"),
    ("full-checkpoint-01", "workspace_business", "Checkpoint 能把 WS 的业务资源权限也恢复成旧值吗？", "hard_negative", "HARD", "hexgent-checkpoint-restorer-and-continuation#checkpoint-语义", "不保存 WS business authority", "rerank", "authority must recheck。"),
    ("full-checkpoint-02", "workspace_business", "进程重启后 Continue/Resume 没有旧 RunHandle，应怎样继续而不伪造旧 execution？", "single_hop_semantic", "HARD", "hexgent-checkpoint-restorer-and-continuation#fresh-engine-hydration", "创建/hydrate 新 AgentEngine", "dense", "fresh engine restore。"),
    ("full-checkpoint-03", "synthetic_hard", "Session 文件还在但没有可继续 checkpoint 时，Continue 应返回什么而不是当作新的 submit？", "exact_lexical", "HARD", "hexgent-checkpoint-restorer-and-continuation#不可继续情形", "REJECTED(RUNTIME_STATE_NOT_CONTINUABLE)", "sparse", "continuable rejection。"),
    ("full-steer-01", "workspace_business", "SteerRun 的 durable intent 和实际修改 Context 之间，为什么需要 commandId identity？", "single_hop_semantic", "HARD", "hexgent-steering-crash-idempotency#两阶段语义", "成为 durable dedup evidence", "dense", "steering exact once。"),
    ("full-steer-02", "synthetic_hard", "Context 已写 steering instruction 但 steering_applied 尚未来得及落盘就 crash，重启会再注入一次吗？", "hard_negative", "HARD", "hexgent-steering-crash-idempotency#crash-window", "不能第二次注入", "rerank", "case B crash window。"),
    ("full-steer-03", "workspace_business", "为什么 Pause/Cancel crash recovery 不能照搬 Steer 的 mailbox 机制？", "hard_negative", "HARD", "hexgent-steering-crash-idempotency#与-pause/cancel-的区别", "不应伪造旧 SteeringMailbox", "rerank", "control recovery distinctions。"),
    ("full-memory-01", "workspace_business", "ContextRecord 与当前 ContextManager 中的活动消息，分别服务什么不同的生命周期？", "single_hop_semantic", "MEDIUM", "hexgent-context-records-and-memory-retrieval#活动上下文与长期记录", "从历史上下文提取出的可召回压缩记录", "dense", "active context vs record。"),
    ("full-memory-02", "synthetic_hard", "只实现了 memory extraction helper，是否就能宣称自动记忆已经在 Agent main loop 中生效？", "hard_negative", "HARD", "hexgent-context-records-and-memory-retrieval#召回边界", "仅有 helper/module 不等于已进入执行链路", "rerank", "implementation boundary。"),
    ("full-memory-03", "workspace_business", "为什么 Hexgent memory retrieval 不能直接代替 WS search_workspace 的权限校验？", "hard_negative", "HARD", "hexgent-context-records-and-memory-retrieval#与-workspace-retrieval-的区别", "必须经 Workspace Capability Gateway", "rerank", "runtime memory vs business retrieval。"),
    ("full-plan-01", "workspace_business", "PlanUpdated 为什么可以给 UI 看，但不能驱动 WS AgentRun 的业务状态？", "hard_negative", "HARD", "hexgent-skill-plan-and-control#skill-与-plan", "不是 WS AgentRun lifecycle 的来源", "rerank", "plan non-authoritative。"),
    ("full-plan-02", "workspace_business", "旧 execution 还存活和已经 crash 消失时，Pause/Cancel/Steer 的控制方式有什么共同边界？", "single_hop_semantic", "HARD", "hexgent-skill-plan-and-control#control-command", "不能调用虚假的 manager.pause/steer/cancel", "dense", "no fake old execution。"),
    ("full-plan-03", "synthetic_hard", "没有 accepted Cancel 时，基础设施 crash 可以投影成 CANCELLED 吗？", "hard_negative", "HARD", "hexgent-skill-plan-and-control#terminal-与预算", "不应被投影成 CANCELLED", "rerank", "crash vs user intent。"),
    ("full-web-01", "workspace_business", "`python -m hexgent.adapters.web` 这个入口启动的边界是什么，是否等价于把 Core 调试对象直接暴露出去？", "hard_negative", "MEDIUM", "hexgent-web-adapter-and-configuration#web-入口", "不是直接暴露 Core 的调试端口", "rerank", "web adapter boundary。"),
    ("full-web-02", "workspace_business", "Hexgent 用于 capability invoke 的 token 与浏览器 JWT 是同一个吗？", "hard_negative", "MEDIUM", "hexgent-web-adapter-and-configuration#环境变量", "它不同于前端 JWT", "rerank", "service identity separation。"),
    ("full-web-03", "synthetic_hard", "Runtime command 返回 HTTP 200/ACCEPTED，怎样确认 Agent 真正完成而非只确认接收？", "single_hop_semantic", "MEDIUM", "hexgent-web-adapter-and-configuration#运行诊断", "检查 transcript lifecycle facts 与 WS projection", "dense", "acceptance vs execution evidence。"),
    ("full-retry-01", "workspace_business", "有外部写副作用的 Tool 超时后，为什么不能直接重试一次？", "hard_negative", "HARD", "hexgent-tool-retry-side-effects#副作用状态", "先验证已有结果", "rerank", "verify before retry。"),
    ("full-retry-02", "workspace_business", "Capability 返回 DENIED 与 capability handler FAILED 在 ToolResult 中为什么要保留不同语义？", "single_hop_semantic", "HARD", "hexgent-tool-retry-side-effects#capability-结果映射", "DENIED 通常是 permission/boundary outcome", "dense", "denied vs failed mapping。"),
    ("full-retry-03", "synthetic_hard", "Workspace Tool Bridge 若替模型生成新的 callId，会破坏哪项写入恢复保证？", "hard_negative", "HARD", "hexgent-tool-retry-side-effects#callid-传递", "依赖 runId + callId", "rerank", "stable mutation identity。"),
]


def main() -> None:
    target = ROOT / "cases" / "test.jsonl"
    existing = [
        json.loads(line)
        for line in target.read_text(encoding="utf-8").splitlines()
        if line.strip() and not json.loads(line)["queryId"].startswith("full-")
    ]
    existing_ids = {item["queryId"] for item in existing}
    additions = [row(*case) for case in CASES]
    if len(additions) != 66 or len({item["queryId"] for item in additions}) != len(additions):
        raise ValueError("curated case count or query identity mismatch")
    overlap = existing_ids & {item["queryId"] for item in additions}
    if overlap:
        raise ValueError(f"query ids already exist: {sorted(overlap)}")
    target.write_text("".join(json.dumps(item, ensure_ascii=False) + "\n" for item in [*existing, *additions]), encoding="utf-8")
    print(f"wrote {len(additions)} curated cases; total={len(existing) + len(additions)}")


if __name__ == "__main__":
    main()
