# RAG Evaluation V2：审计与第一轮设计

> 范围：本设计只增加离线评测资产与校验工具。它不修改线上 Retrieval、Rerank、Embedding、
> Chunking、Qdrant、Agent 或 Workspace 业务行为。

## 1. Current Evaluation Audit

### 当前实现与执行路径

当前可执行评测由 `scripts/run_retrieval_v2.py` 完成：

```text
冻结 raw corpus
  -> 评测专用 Workspace 上传（或复用 document-map）
  -> 轮询 Document / Active Generation readiness
  -> POST /internal/v1/evaluation/retrieval
  -> IKnowledgeRetrievalService
  -> query embedding -> Qdrant candidates -> MySQL authority validation
  -> optional rerank -> final MySQL recheck
  -> cases.jsonl / summary.json
```

它不调用 legacy `/knowledge/search`，也不调用聊天模型；结果只代表 retrieval。

### 当前数据规模与标签

`dataset-v1.1` 有 40 篇 corpus 文档，PDF、DOCX、Markdown、TXT 各 10 篇；共 237 条
query：train 39、dev 40、test 158。总量按现有 `category` 分布：

| 当前 category | 数量 |
| --- | ---: |
| direct_fact | 80 |
| paraphrase | 42 |
| multi_condition | 35 |
| numeric_fact | 25 |
| cross_section | 20 |
| unanswerable | 20 |
| similar_concept | 15 |

217 条 answerable case 中，197 条只有一个 evidence，20 条有两个 evidence；不存在一个
稳定标注为“同一 Document 内多个必要 chunk”的 case。

Gold 的真实粒度是 `documentKey + heading + quote`：运行器按去重后的 `documentKey` 计算
分数；`heading` 和 `quote` 用于人工可追溯，未绑定 immutable `DocumentVersion`、Generation、
Qdrant point 或 runtime `chunkNo`。因此当前结果是**document-level retrieval metric**，不是
chunk-level metric。

### 当前指标定义

- `Hit@K`：最终 TopK 去重 document 中，至少一个属于 Gold document 集。
- `Recall@K`：最终 TopK 去重 document 与 Gold document 的交集数 / Gold document 数。
- `MRR@K`：TopK 中第一个 Gold document 的倒数排名；无命中为 0。
- latency：浏览器侧请求延迟及服务端 embedding、Qdrant、首次 authority、rerank、最终
  authority recheck、total 的 P50/P95/P99。

当前诊断已在单个 case 上区分 `QDRANT_TOP_CANDIDATE_MISS`、`AUTHORITY_FILTERED`、
`RANKING_MISS` 与 `FINAL_HIT`，但还没有聚合的 Candidate Recall@N，也没有 dense/sparse
来源归因或 chunk-level candidate identity。

### 已知偏差与为什么会饱和

V1.1 是可复现工程回归集，不能作为通用 IR 证明。其 corpus 由同一生成器产生，许多文档复用
`ACTIVE / window / retry / SUCCESS / ORIGINAL / DERIVED` 模板；train、dev、test 又共享同一
批文档。因此：

1. 93.24% Recall@5 表明该固定集上的当前策略已接近饱和，但不等价于真实 Workspace 泛化能力。
2. 约 90.8% answerable case 只有一条 evidence，当前 query 明显偏 single-hop。
3. 有规则编号与数值问题，但没有独立 `exact_lexical` suite，也没有文件名、类名、方法名、
   配置键、缩写等更接近真实技术语料的 lexical-heavy 分布。
4. `similar_concept` 不是显式 hard negative：没有记录 competing chunk、为什么它看似相关、
   为什么不能回答，不能可靠评估 reranker。
5. 20 条 `cross_section` 可覆盖双 document 命中，但无法验证同文档 multi-hop 或 chunk 覆盖。
6. Evidence quote 让 Gold 可追溯，但相同模板句可能有多个语义等价文档；强制唯一 Gold 会产生
   评测假阴性。`membership-03` 的泛化来源标记问题就是已观察到的例子。
7. 当前 deterministic generator 按固定问句模板生成大量题目，存在 query template bias。

因此，现有总分不足以公平区分 Dense、Rerank、Sparse 与 Hybrid：它不能说明 sparse 是否补足
candidate miss、reranker 是否胜过 hard negative，也不能从总分分辨检索提升与模板记忆。

## 2. V2 Evaluation Architecture

V2 共用数据 schema、validator、case result schema 和报告维度，但保持三个 Suite 的语义隔离：

| Suite | 数据来源 | 回答的问题 | 是否可计入产品主指标 |
| --- | --- | --- | --- |
| `general` | Public corpus/query/qrels | 通用 IR 能力是否成立 | 否，单独报告 |
| `workspace_business` | 真实或代表性 Workspace corpus | 用户能否找到实际资料 | 是 |
| `synthetic_hard` | Evidence-first 对抗构造 | 失败边界在哪里 | 否，单独报告 |

任何 dashboard 都不得将三者混为一个 Recall。策略对比固定 dataset version、corpus checksum、
canonical chunk manifest、candidateLimit、topK、retrieval config、reranker config；变更任一
冻结输入即产生新 benchmark version。

## 3. EvaluationCase Schema

正式 schema 在 `dataset-v2-draft/schemas/evaluation-case.schema.json`。关键语义：

| 字段 | 语义 |
| --- | --- |
| `queryId` | 冻结的唯一 case identity |
| `suite` / `queryType` / `difficulty` | 分层报告和失败归因标签，不参与检索打分 |
| `relevantDocumentIds` | 数据集稳定 document identity；当前使用 `documentKey` |
| `relevantChunkIds` | canonical `documentKey#section` anchor，不是 runtime `chunkNo` |
| `evidence` | quote + chunk anchor + 1–3 relevance grade；NDCG 仅在有有效 grade 时启用 |
| `hardNegativeChunkIds` / `hardNegativeReasons` | 明确记录竞争证据与其不应回答的原因 |
| `expectedRetrievalStrength` | `dense/sparse/both/rerank/multi_hop/unknown` 分析标签，禁止参与线上评分 |
| `generationMethod` / `reviewStatus` | 追溯生成来源与人工审核状态 |

兼容原则：V1 的 `documentKey + heading + quote` 可以迁移为 document identity、canonical section
anchor 与 quote；不会修改或覆盖 V1 数据。runtime executor 以后必须根据 quote 将 anchor 映射到
当前 chunk artifact；映射失败是 dataset/chunking incompatibility，不可降级成 document hit。

## 4. Query Taxonomy

| 类型 | 目标 | 主要观察能力 |
| --- | --- | --- |
| `single_hop_semantic` | 正常自然语言事实 | Dense 基础能力 |
| `paraphrase` | 低 lexical overlap 的同义信息需求 | Dense 语义泛化 |
| `exact_lexical` | 类名、配置键、error code、编号、版本、文件名 | Sparse candidate rescue |
| `hard_negative` | 明确竞争 chunk 与理由 | Rerank 精排 |
| `multi_hop` | 至少两条必要 evidence，标记同/跨文档 | 覆盖率与 evidence aggregation |
| `ambiguous` | 同术语、多模块、相似状态由上下文消歧 | Candidate + rerank |
| `long_tail` | 局部异常、示例、非主题段事实 | Chunking 与 candidate recall |
| `unanswerable` | Gold 为空 | 后续 abstention / false retrieval，排除 Recall/MRR |

Difficulty 必须可由结构解释：EASY 为直接且干扰少；MEDIUM 至少有 paraphrase、lexical gap 或
相似 chunk；HARD 至少有 hard negative、精确实体、多跳、跨文档、明显竞争 evidence 或长尾。

## 5. Dataset Distribution Proposal

冻结 V2 的建议总量为 180–240 条，而不是机械追求均匀：

| Suite | 建议数量 | 主要组成 |
| --- | ---: | --- |
| General | 60–80 | 先 SciFact 小子集；保留 public qrels 与 license |
| Workspace Business | 80–110 | 真实用户问题分布：语义、配置、状态、版本、长尾、少量多跳 |
| Synthetic Hard | 40–60 | lexical、hard negative、歧义、multi-hop、near-miss refusal |

每种关键类型至少 10 条后再比较策略；每类由真实分布决定，不要求完全均分。当前 32 条 sample
使用五篇异构的既有设计/验收文档（产品边界、个人资料库、上传一致性、RAG 验收、认证），只用于
审查 schema、证据绑定与类型覆盖，不代表最终分布。此前基于 V1.1 模板语料的 sample 已废弃：
它们存在“换规则编号但不换信息需求”的语义重复，不能进入人工审核队列。

## 6. Gold Labeling Strategy

Gold 采用 Evidence First：选择真实 canonical chunk -> 写清 quote -> 生成 query -> 选择 hard
negative -> 人工核验。禁止由 Dense、Sparse、Hybrid 或 Rerank 的输出反推 Gold。

多证据 case 必须明确每条是否必要；答案可由任一证据充分支持时，应标为 alternative evidence，
而不是错误地强制所有 chunk 都 required。当前 sample 暂未引入 alternative relation，审核时应
优先决定该扩展的最小 schema。

## 7. Synthetic Generation Pipeline

```text
Frozen Corpus / canonical chunk
  -> evidence selection
  -> manual or LLM candidate query
  -> bind Gold anchor and quote
  -> retrieve only to discover candidate hard negatives (never to alter Gold)
  -> record hard-negative reason
  -> validator
  -> human review
  -> VERIFIED / REJECTED
```

LLM 可辅助产生候选 query，但不能自动进入正式 benchmark。`derived_from_failure_case` 仅说明
case 来源，不允许把当前策略命中的候选加入 Gold。

## 8. Human Review Workflow

- `UNREVIEWED`：机器或作者已写入，但尚未审核。
- `REVIEWED`：确认 evidence 存在、问题可理解，仍可能需要第二位审阅者。
- `VERIFIED`：确认 query 可由所列 Gold 支持、negative 不可充分回答、类型和难度合理。
- `REJECTED`：保留理由，不进入冻结集。

审核人必须特别检查：无唯一语义答案却仅标一个 Gold 的 case、事实不在 quote 内、multi-hop 的
任一 chunk 非必要、hard negative 实际也可回答等问题。

## 9. Dataset Validator

已实现 `scripts/validate_rag_eval_v2.py`。它检查：唯一 ID、非空 query、合法枚举、已存在的
document/chunk anchor、quote 属于 canonical chunk、Gold/negative 不重叠、unanswerable 空 Gold、
multi-hop 至少两条 Gold、document/chunk 关系、relevance grade、生成/审核 metadata、重复 query
和高相似 query warning、Gold evidence quote 的字符 n-gram 相似度 warning，并输出
suite/type/difficulty/review 等统计。相似度只作为人工复核线索，不能替代 reviewer 对信息需求
是否重复的判断。

它不调用线上服务，故不会将当前 retriever 结果污染数据集。

## 10. Public Benchmark Adapter

General 层第一阶段使用 SciFact 的 BEIR JSONL/qrels 格式。`scripts/prepare_general_scifact.py`
保留确定性的 50-query sanity subset 用于快速回归，并通过 `--full` 物化完整基线：全部 5,183
corpus document 与 test split 的全部 300 条有正 qrels query。输出沿用现有
`manifest/documents.jsonl + qa/test.jsonl` 形状，因此复用 `run_retrieval_v2.py` 的真实上传、
Generation 就绪与检索路径。train/dev query 不混入 General test 指标。

```text
SciFact corpus/query/qrels
  -> deterministic general subset (source checksum + seed + licence record)
  -> dedicated evaluation Workspace
  -> current HexWorkspace retrieval path
  -> General-only document-level Hit/Recall/MRR report
```

该 subset 的目的只是验证通用英文 IR 链路，**不是完整 BEIR SciFact benchmark**，不得与完整
BEIR leaderboard、Workspace Business 或 Synthetic Hard 的分数横比。SciFact 原始项目声明：
claims/evidence annotations 为 CC BY 4.0，corpus abstracts 为 ODC-By 1.0；下载数据保持在被
忽略的本地目录，准备器会记录 archive URL、SHA-256 与许可证说明。

Public data 保持其原始 document/query/qrels identity 与许可证；不得强行伪装成 Workspace
`DocumentVersion` 或 Generation。qrels 是 document-level gold，因此 General 阶段也不使用
Workspace/Hard 的 quote-to-runtime-chunk mapping。跑通 SciFact 后才评估 NFCorpus 或 FiQA。

此前的 adapter 目标为：

```text
Public corpus document id -> GeneralCorpusRecord
Public query id -> EvaluationCase(suite=general)
Public qrels -> relevantDocumentIds + relevanceGrade
```

Public data 保持其原始 document/query/qrels identity 与许可证；不得强行伪装成 Workspace
`DocumentVersion` 或 Generation。adapter 必须保存下载来源、checksum、license、subsample seed
和 qrels version。

## 11. Evaluation Metrics & Report

answerable case 按 Suite × Type × Difficulty × Strategy 输出：Hit@K、Recall@K、MRR@K；存在
graded relevance 时输出 NDCG@K。unanswerable 单独报告 false retrieval / future abstention，
不混入 Recall/MRR。

候选阶段必须记录 chunk identity 和 source provenance，并聚合：

- `candidate_miss`：Gold 不在任一 candidate TopN；
- `candidate_hit_final_miss`：Gold 在 candidate 中但不在 final TopK；
- `final_hit`：Gold 在 final TopK；
- Dense-only / Sparse-only / Both / Both-miss；
- Sparse rescue rate、Hybrid candidate recall、Rerank rescue rate。

性能同时按 stage 输出 P50/P95：embedding、dense、sparse、fusion、rerank、authority validation、
total。只有在同一冻结输入下，才讨论 Quality–Latency Pareto frontier。

## 12. Sample Cases

`dataset-v2-draft/cases/sample.jsonl` 有 32 条 case，已完成第一轮人工审核并标记为 `VERIFIED`，
由 `corpus/chunks.jsonl` 中的 43 个 canonical section anchor 验证。它们覆盖八个 query type，
但不再机械均分：

- Workspace Business：4 semantic、4 paraphrase、4 multi-hop；
- Synthetic Hard：4 exact lexical、4 hard negative、4 ambiguous、4 long-tail、4 unanswerable；
- 每个 hard negative 都显式记录 competing anchor 和排除理由。

它们的评测价值是验证 schema、审核流程、chunk identity 与 failure-attribution 设计；不是以当前
模板语料声称已完成 V2 质量 benchmark。

## 13. Risks / Open Questions

1. V1.1 的同模板 corpus 不足以构成最终 Workspace Business Suite；必须选取更异构、可公开或
   已获授权的真实代表性 Workspace 内容。
2. `relevantDocumentIds` 当前是 dataset identity，不是数据库主键；正式 runner 需维护稳定
   document map 和 quote-to-runtime-chunk mapping。
3. multi-hop 的 `required` 与 `alternative` evidence relation 尚需人工决定后再加 schema。
4. NDCG 的 grade 目前为 schema 能力，sample 中 Gold 都为最高 grade；正式集需要有理由的 graded
   relevance，不能人为调分配合结果。
5. General Suite 的公开数据许可、下载 checksum、中文/英文预处理可比性要在 SciFact 试点前冻结。

## 14. Recommended Next Step

人工逐条审核 32 个 sample，特别是模板语料中的等价 Gold 与 multi-hop 必要性。审核通过后：

1. 固化 V2 schema（必要时新增 alternative/required evidence relation）；
2. 选取并冻结真实 Workspace Business corpus；
3. 用相同 Evidence First 流程扩展至 150–300 条；
4. 实现 V2 executor/report，再在完全相同 V2 数据上比较 Dense、Dense+Rerank、Hybrid、
   Hybrid+Rerank。

在上述审核与冻结完成前，不进入 Hybrid Search 实现，也不修改线上 retrieval。
