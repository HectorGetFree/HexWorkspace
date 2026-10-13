#!/usr/bin/env python3
"""Create evidence-first, semantically diverse RAG Evaluation V2 review cases.

The draft deliberately uses heterogeneous product, consistency, RAG and auth
documents. It does not use retrieval output to label Gold evidence.
"""
from __future__ import annotations

import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1] / "dataset-v2-draft"


def anchors():
    return {
        row["chunkId"]: row["content"]
        for row in (
            json.loads(line)
            for line in (ROOT / "corpus" / "chunks.jsonl").read_text(encoding="utf-8").splitlines()
            if line.strip()
        )
    }


def quote(content: str, marker: str) -> str:
    """Return one auditable line containing a manually selected marker."""
    return next(line.strip() for line in content.splitlines() if marker in line)


def case(query_id, suite, query, query_type, difficulty, gold, *, negatives=(), strength="unknown", notes=""):
    source = anchors()
    evidence = [
        {"chunkId": chunk_id, "quote": quote(source[chunk_id], marker), "relevanceGrade": 3}
        for chunk_id, marker in gold
    ]
    chunk_ids = [chunk_id for chunk_id, _ in gold]
    return {
        "queryId": query_id,
        "suite": suite,
        "query": query,
        "queryType": query_type,
        "difficulty": difficulty,
        "answerable": True,
        "relevantDocumentIds": sorted({chunk_id.split("#", 1)[0] for chunk_id in chunk_ids}),
        "relevantChunkIds": chunk_ids,
        "evidence": evidence,
        "hardNegativeChunkIds": list(negatives),
        "hardNegativeReasons": {chunk_id: "术语或流程相近，但缺少回答本问题所需的指定事实。" for chunk_id in negatives},
        "expectedRetrievalStrength": strength,
        "generationMethod": "manual",
        "reviewStatus": "VERIFIED",
        "notes": notes,
    }


def unanswered(query_id, query, notes):
    return {
        "queryId": query_id, "suite": "synthetic_hard", "query": query,
        "queryType": "unanswerable", "difficulty": "MEDIUM", "answerable": False,
        "relevantDocumentIds": [], "relevantChunkIds": [], "evidence": [],
        "hardNegativeChunkIds": [], "hardNegativeReasons": {}, "expectedRetrievalStrength": "unknown",
        "generationMethod": "manual", "reviewStatus": "VERIFIED", "notes": notes,
    }


def cases():
    workspace = "workspace-product-boundary"
    personal = "personal-workspace-design"
    upload = "document-upload-consistency"
    rag = "rag-v1-final-acceptance"
    auth = "user-authentication-design"
    return [
        # Normal Workspace needs: product semantics, lifecycle and operational behavior.
        case("v2-workspace-semantic-01", "workspace_business", "一个人注册后，是否能自行再建第二个资料库？", "single_hop_semantic", "EASY", [(f"{personal}#2.-核心产品语义", "用户不能主动创建第二个 Workspace")], strength="dense", notes="个人资料库的单 Workspace 产品规则。"),
        case("v2-workspace-semantic-02", "workspace_business", "删除一个目录会立即物理删除里面的文件吗？", "single_hop_semantic", "MEDIUM", [(f"{personal}#6.-删除语义", "所有删除均为逻辑删除")], strength="dense", notes="删除语义，不涉及检索或权限术语。"),
        case("v2-workspace-semantic-03", "workspace_business", "平台里直接新建的文档目前限定成什么格式？", "single_hop_semantic", "EASY", [(f"{workspace}#5.-文档导入与平台创建边界", "目前只允许 Markdown")], strength="dense", notes="内容创建能力边界。"),
        case("v2-workspace-semantic-04", "workspace_business", "同名的文件夹和文档能放在同一个父目录里吗？", "single_hop_semantic", "MEDIUM", [(f"{personal}#5.-名称与重名规则", "文件夹和云文档可以使用相同展示名称")], strength="dense", notes="名称冲突的资源类型差异。"),
        case("v2-workspace-semantic-05", "workspace_business", "用户被撤销某个目录授权后，Agent 还能读取该目录里的资料吗？", "single_hop_semantic", "MEDIUM", [(f"{workspace}#6.-权限与-agent-边界", "用户可访问资源")], strength="dense", notes="当前授权重校验与 Agent scope 的交集。"),
        # Different natural-language needs, not variations of one rule template.
        case("v2-workspace-paraphrase-01", "workspace_business", "为什么上传完成不能立刻认为文档已经能被读取？", "paraphrase", "MEDIUM", [(f"{upload}#2.-正常链路", "接口创建成功只表示任务已可靠登记")], strength="dense", notes="异步上传任务登记与可读性的区别。"),
        case("v2-workspace-paraphrase-02", "workspace_business", "一份文档的新版本生效后，系统还会异步补齐什么索引工作？", "paraphrase", "MEDIUM", [(f"{rag}#1.-交付范围", "DocumentVersion 生效后的异步索引任务登记")], strength="dense", notes="文档版本生效后进入异步知识索引，而非即时检索结果。"),
        case("v2-workspace-paraphrase-03", "workspace_business", "我退出账号后，系统怎样让旧的访问令牌不再可用？", "paraphrase", "MEDIUM", [(f"{auth}#5.-redis-撤销记录", "auth:revoked:session")], strength="dense", notes="会话撤销的 Redis identity。"),
        case("v2-workspace-paraphrase-04", "workspace_business", "文档的历史版本会被新版本的存储对象覆盖掉吗？", "paraphrase", "MEDIUM", [(f"{upload}#3.-失败与幂等规则", "历史对象绝不覆盖")], strength="dense", notes="不可变版本与固定正式对象路径。"),
        # Lexical-heavy cases retain a small but realistic identifier/configuration slice.
        case("v2-hard-lexical-01", "synthetic_hard", "`auth:revoked:session:{sessionId}` 的值和写入时机分别是什么？", "exact_lexical", "HARD", [(f"{auth}#5.-redis-撤销记录", "| Value | `true` |"), (f"{auth}#5.-redis-撤销记录", "| 写入时机 | 登出 |")], strength="sparse", notes="Redis key、表格字段和值的精确检索。"),
        case("v2-hard-lexical-02", "synthetic_hard", "`PUBLISHING` 状态的 OutboxEvent 正在等待什么确认？", "exact_lexical", "HARD", [(f"{upload}#4.-状态", "| OutboxEvent | `PUBLISHING` |")], strength="sparse", notes="消息发布状态机术语。"),
        case("v2-hard-lexical-03", "synthetic_hard", "RAG 的 Qdrant collection 名称是什么？", "exact_lexical", "HARD", [(f"{rag}#2.-架构概览", "`hex_workspace_rag_v1`")], strength="sparse", notes="精确 collection identifier。"),
        case("v2-hard-lexical-04", "synthetic_hard", "accessToken 和 refreshToken 的默认过期时长各是多少？", "exact_lexical", "HARD", [(f"{auth}#3.-令牌模型", "| 默认有效期 | 15 分钟 | 30 天 |")], strength="sparse", notes="同一表格的两个数值字段。"),
        # Explicit competing evidence from distinct domains.
        case("v2-hard-negative-01", "synthetic_hard", "哪一个组件负责候选召回，哪一个组件仍然是检索权威？", "hard_negative", "HARD", [(f"{rag}#2.-架构概览", "MySQL 中的 Active Generation 是检索权威来源")], negatives=(f"{upload}#1.-目标与边界",), strength="rerank", notes="MinIO 与 MySQL 一致性段包含权威字样，但不是检索权威答案。"),
        case("v2-hard-negative-02", "synthetic_hard", "RabbitMQ 发布出现异常后，Outbox 最终应回到哪个状态？", "hard_negative", "HARD", [(f"{upload}#3.-失败与幂等规则", "会回到 `PENDING`")], negatives=(f"{upload}#4.-状态",), strength="rerank", notes="状态表定义接近，但不包含发布失败转移。"),
        case("v2-hard-negative-03", "synthetic_hard", "刷新 access token 时，浏览器提供的凭证是哪一种？", "hard_negative", "HARD", [(f"{auth}#4.-核心链路", "浏览器携带 refreshToken Cookie")], negatives=(f"{auth}#3.-令牌模型",), strength="rerank", notes="token 模型介绍是近邻；问题要求刷新动作的承载凭证。"),
        case("v2-hard-negative-04", "synthetic_hard", "上传链路中，哪一步会同时落下 DocumentVersion、将 UploadTask 标为成功，并激活文档？", "hard_negative", "HARD", [(f"{upload}#2.-正常链路", "DocumentVersion(vN) + UploadTask(SUCCEEDED) + Document(ACTIVE")], negatives=(f"{upload}#4.-状态",), strength="rerank", notes="状态表解释单个状态，但正常链路才给出三者同事务完成的事实。"),
        case("v2-hard-negative-05", "synthetic_hard", "为什么不能用“同一 Workspace 的协作者”身份直接推断文档访问权限？", "hard_negative", "HARD", [(f"{workspace}#6.-权限与-agent-边界", "不意味着其中一个可以读取另一个未授权的文件")], negatives=(f"{workspace}#4.-workspace-领域设计",), strength="rerank", notes="Workspace 定义是相近主题，但未直接给出协作视图不等于授权的结论。"),
        # Multi-hop cases require heterogeneous evidence; duplicate templates are avoided.
        case("v2-workspace-multihop-01", "workspace_business", "说明文档为什么必须先有正式对象才能 ACTIVE，并列出这一步之前由哪些机制可靠登记任务。", "multi_hop", "HARD", [(f"{upload}#1.-目标与边界", "正式对象已经可用"), (f"{upload}#2.-正常链路", "UploadTask + OutboxEvent(PENDING)")], strength="multi_hop", notes="同一文档的因果链；两个 evidence 分别给承诺与登记机制。"),
        case("v2-workspace-multihop-02", "workspace_business", "用户登录后发业务请求时，除了 JWT 签名校验，还会检查哪两个当前状态？", "multi_hop", "HARD", [(f"{auth}#5.-redis-撤销记录", "| 检查时机 | accessToken 访问"), (f"{auth}#1.-当前实现边界", "`ACTIVE / DISABLED` 用户状态")], strength="multi_hop", notes="会话撤销与用户 ACTIVE 状态是两个独立的 current-state gate。"),
        case("v2-workspace-multihop-03", "workspace_business", "Agent 使用 Workspace 资料生成草稿后，写回已有文件要满足什么版本规则？", "multi_hop", "HARD", [(f"{workspace}#6.-权限与-agent-边界", "写回已有文件必须产生新版本"), (f"{personal}#4.-云文档能力", "编辑 Markdown 内容时创建新版本")], strength="multi_hop", notes="跨产品边界与文档版本不可变性。"),
        case("v2-workspace-multihop-04", "workspace_business", "新用户的资料库和根目录为何要放在一个事务里？同时给出根目录是否允许被删除。", "multi_hop", "HARD", [(f"{personal}#2.-核心产品语义", "处于同一个事务边界"), (f"{personal}#3.-文件夹能力", "根目录不能执行重命名、移动或删除操作")], strength="multi_hop", notes="个人 Workspace 初始化一致性与根目录的不可变操作边界。"),
        case("v2-hard-ambiguous-01", "synthetic_hard", "这里说的 `ACTIVE` 是指可读取的文档状态，不是用户账号状态；它需要满足什么条件？", "ambiguous", "HARD", [(f"{upload}#4.-状态", "正式 MinIO 对象已写入，可读")], negatives=(f"{auth}#1.-当前实现边界",), strength="rerank", notes="ACTIVE 跨文档多义，问题明确消歧为文档。"),
        case("v2-hard-ambiguous-02", "synthetic_hard", "“删除”指资源逻辑删除时，普通目录查询会有什么变化？", "ambiguous", "HARD", [(f"{personal}#6.-删除语义", "常规目录查询不再返回该资源")], negatives=(f"{workspace}#4.-workspace-领域设计",), strength="rerank", notes="目录、文档和 Workspace 都讨论生命周期；此处限定资源删除后的查询语义。"),
        case("v2-hard-ambiguous-03", "synthetic_hard", "系统说“成功”时，到底是 MQ 已确认、上传任务完成，还是文档可读？请定位 UploadTask 成功的定义。", "ambiguous", "HARD", [(f"{upload}#4.-状态", "| UploadTask | `SUCCEEDED` | 文档已激活 |")], negatives=(f"{upload}#3.-失败与幂等规则",), strength="rerank", notes="成功/确认/激活在同一流程中有不同 commit point。"),
        case("v2-hard-long-tail-01", "synthetic_hard", "一次 staging 写入成功但本地事务提交失败后，遗留对象由什么规则清理？", "long_tail", "HARD", [(f"{upload}#3.-失败与幂等规则", "保留 24 小时后删除没有 UploadTask 引用的对象")], strength="dense", notes="异常链路尾部的清理保留期。"),
        case("v2-hard-long-tail-02", "synthetic_hard", "旧的、租约已经到期的消费者若继续运行，哪些关键动作不允许再做？", "long_tail", "HARD", [(f"{upload}#3.-失败与幂等规则", "不能再激活文档、覆盖任务状态或删除临时对象")], strength="dense", notes="执行 token/lease fencing 的长尾行为。"),
        case("v2-hard-long-tail-03", "synthetic_hard", "现在的认证设计有没有实现 refresh token 轮换？", "long_tail", "MEDIUM", [(f"{auth}#1.-当前实现边界", "未完成：refreshToken 轮换")], strength="dense", notes="已实现与未实现边界，不是接口或配置主标题。"),
        unanswered("v2-unanswerable-01", "当前是否支持把 PDF 自动翻译成日文并保留双语版本？", "语料没有翻译或双语版本能力证据。"),
        unanswered("v2-unanswerable-02", "系统是否已经支持 CRDT 多人实时协同编辑？", "语料明确未承诺实时协同，但没有已支持的 Gold 证据。"),
        unanswered("v2-unanswerable-03", "RAG 是否把所有 Qdrant 向量永久保存在 Redis 中？", "语料涉及 Redis 会话撤销，不支持该存储结论。"),
        unanswered("v2-unanswerable-04", "用户能否通过 MFA 短信验证码登录？", "认证设计列出的范围没有 MFA 实现证据。"),
    ]


def main():
    output = ROOT / "cases" / "sample.jsonl"
    output.parent.mkdir(parents=True, exist_ok=True)
    rows = cases()
    if len(rows) != 32 or len({row["queryId"] for row in rows}) != len(rows):
        raise ValueError("sample case count or query id is invalid")
    output.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows), encoding="utf-8")
    print(f"wrote {len(rows)} review sample cases: {output}")


if __name__ == "__main__":
    main()
