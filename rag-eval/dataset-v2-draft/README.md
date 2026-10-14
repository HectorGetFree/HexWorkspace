# RAG Evaluation V2 Draft

这是 RAG Evaluation V2 的**人工审核草案**，不是已冻结 benchmark，也不替换
`dataset-v1.0` 或 `dataset-v1.1`。V1.1 及其历史运行结果保持原样。

## 目的与边界

V2 把评测拆为三个互不混算的 Suite：

- `general`：公开 IR benchmark 的适配层。它只复用评测抽象，不映射到 Workspace 的
  `DocumentVersion`、Generation 或权限模型。
- `workspace_business`：来自真实或代表性 Workspace 资料的用户信息需求。
- `synthetic_hard`：用于有意构造 exact lexical、hard negative、multi-hop、歧义和
  长尾边界；它不代表真实用户分布。

本目录当前只有基于五篇异构 repository design/acceptance 文档的 32 条 sample case，已完成第一轮
人工审核，状态为 `VERIFIED`。它们覆盖产品边界、个人资料库、上传最终一致性、RAG 可靠性和认证；
仍然只是草案，不能被误称为最终 Workspace Business benchmark。V1.1 的模板化 corpus 不再作为
V2 sample 的证据来源。

## 稳定 Evidence Identity

`relevantDocumentIds` 里的值是数据集稳定的 document identity；
`relevantChunkIds` 使用 `documentId#section`，例如
`document-upload-consistency#3.-失败与幂等规则`。它是 canonical corpus 的 section anchor，不是一次运行中
Qdrant 的 point ID 或 `chunkNo`。

冻结后的执行器必须把 anchor 映射到当前 chunking artifact 中包含该 evidence quote 的
运行时 chunk；如果映射失败，应把该 run 标为 dataset/chunking incompatibility，而不是静默
把 document-level hit 当作 chunk hit。

## 当前目录

- `dataset.json`：版本、状态、corpus 来源和冻结约束。
- `cases/sample.jsonl`：已完成第一轮审核的 32 条 Evidence-first 样本；仍需第二轮业务复核后才能冻结。
- `schemas/evaluation-case.schema.json`：V2 Case JSON Schema。
- `schemas/canonical-chunk.schema.json`：冻结 canonical anchor 的 schema。
- `corpus/manifest.jsonl`：五篇 repository source document 的相对路径和稳定 identity。
- `corpus/chunks.jsonl`：由 manifest materialize 的 section anchor 内容快照。

## 审核工作流

```text
UNREVIEWED -> REVIEWED -> VERIFIED
                         -> REJECTED
```

只有 `VERIFIED` case 可以进入冻结 benchmark。新增或修改 corpus、Gold、hard negative、
chunking assumption 任一项都必须产生新的数据集版本，不能覆盖历史 version。

## 校验

```bash
python3 rag-eval/scripts/validate_rag_eval_v2.py \
  --dataset-root rag-eval/dataset-v2-draft
```

校验器不调用 Retrieval、Embedding 或 Reranker；它只验证冻结数据、evidence 和 metadata。

## 未来运行契约

同一次策略对比必须固定：dataset version、corpus checksum、canonical chunk manifest、
candidate limit、topK、Reranker config 与 retrieval config。报告按
`suite × queryType × difficulty × strategy` 分组，并额外报告 candidate miss、candidate hit
但 final miss、final hit 及分阶段 P50/P95。
