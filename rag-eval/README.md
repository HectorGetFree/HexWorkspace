# RAG 评测基线

本目录用于保存 hex-workspace RAG 链路的可复现评测资产，不属于 Spring Boot 运行时模块。

## 目录约定

- `dataset-v1.0/`：冻结的数据集、文档清单与黄金问题标注。
- `configs/`：每次基线评测使用的 RAG 配置快照。
- `scripts/`：评测执行器与指标计算脚本，后续补充。
- `runs/`：每次评测的原始结果与报告，不反向修改数据集。

`dataset-v1.*` 仍是 Workspace 受控回归集，不能当作 General IR 能力证明。V2 还保持三套
**不混分**的 suite：`general`（公开 IR）、`workspace_business`（业务资料）和
`synthetic_hard`（明确失败边界）。

## General：SciFact deterministic sanity subset

General 首个试点复用 SciFact 的公开 corpus/query/qrels，但为了先验证当前 Workspace 上传、
索引、Generation 与 retrieval 这条真实链路，默认只物化确定性的子集：50 条 test query、每条
query 的所有正相关 document 和 350 条 deterministic distractor。它不是完整 BEIR SciFact
benchmark；其 Hit/Recall/MRR 只能作为 `general` 单独报告，不能与 Workspace Business/Hard 混合。

公开原始数据和物化文本放在 Git 忽略的 `rag-eval/cache/`。准备器会写入 archive SHA-256、来源、
subsample seed 和许可证记录；不要把下载数据直接提交。SciFact 原项目的许可证说明为：
claims/evidence annotations 是 CC BY 4.0，corpus abstracts 是 ODC-By 1.0。

```bash
# 如已有下载的 BEIR SciFact archive，优先显式传入本地文件。
python3 rag-eval/scripts/prepare_general_scifact.py \
  --archive /absolute/path/to/scifact.zip

# 或由脚本下载；会保存到被 Git 忽略的 rag-eval/cache/downloads/。
python3 rag-eval/scripts/prepare_general_scifact.py --download

# 使用新的、专用 evaluation Workspace。--upload 只在首次导入时使用。
python3 rag-eval/scripts/run_retrieval_v2.py \
  --dataset-root rag-eval/cache/general-scifact-sanity-v1 \
  --config rag-eval/configs/general-scifact-sanity-v1.yml \
  --upload \
  --candidate-limit 30 \
  --top-k 5 \
  --run-id general-scifact-sanity-$(date -u +%Y%m%dT%H%M%SZ)
```

后续复用该 corpus 时使用首次 run 输出的 `document-map.json`，并固定相同 dataset metadata、
candidateLimit、topK 和 retrieval config。General 只验证 document-level qrels，不走
quote-to-runtime-chunk mapping；后者专属于 Workspace Business/Hard 的 evidence-first 评测。

## General：完整 SciFact test

正式 General 运行使用 SciFact 的全部 5,183 份 corpus 文档和 test split 的全部 300 条有正
qrels query。这里的“全部 query”专指 test split：archive 同时带有 train/dev query，混入它们会破坏
测试集语义。完整上传会创建 5,183 条异步索引任务，应使用新的专用 Workspace，并按实际 Worker
吞吐设置较长的 poll timeout。

```bash
# 只物化完整 corpus 与 test qrels；公开数据仍在被忽略的 rag-eval/cache/ 下。
python3 rag-eval/scripts/prepare_general_scifact.py \
  --archive rag-eval/cache/downloads/scifact.zip \
  --full

# 首次完整导入与评测。不要与 sanity subset 或产品 Workspace 共用 Workspace。
python3 rag-eval/scripts/run_retrieval_v2.py \
  --dataset-root rag-eval/cache/general-scifact-full-v1 \
  --config rag-eval/configs/general-scifact-full-v1.yml \
  --upload \
  --candidate-limit 30 \
  --top-k 5 \
  --upload-batch-size 25 \
  --readiness-batch-size 10 \
  --poll-timeout 14400 \
  --run-id general-scifact-full-$(date -u +%Y%m%dT%H%M%SZ)
```

这仍然不应与 BEIR leaderboard 直接横比：本项目会经过自己的文本物化、上传、chunking、embedding、
Qdrant 与 MySQL authority recheck。它是完整 SciFact test qrels 下的 HexWorkspace General 基线。

### BM25 candidate rescue diagnostic

该离线实验只诊断 BM25 是否能召回 Dense Top30 漏掉的 qrels document。它读取冻结的 SciFact
corpus、test qrels 和既有 C1 的 `qdrantCandidates` diagnostics；不调用生产检索、不 rerank、不做
RRF，也不修改 corpus、query、qrels 或 C1 产物。

```bash
python3 rag-eval/scripts/run_scifact_bm25_rescue.py \
  --dataset-root rag-eval/cache/general-scifact-full-v1 \
  --baseline-run rag-eval/runs/general-scifact-full-20261004T075524Z \
  --top-n 30
```

新 run 会写出 `summary.json`、全部 300 条的 `cases.jsonl`，以及仅包含 Dense candidate miss 的
`dense-candidate-miss-attribution.jsonl`。其中 rescue 原因只是可复现的词法启发式标签，不是人工相关性
判定，也不能直接证明 Hybrid 的最终 Top5 指标会提升。

### C2：Dense + BM25 RRF 对照

C2 使用相同 Active Generation 的 **Chunk** 作为 Dense、BM25 和 RRF 的共同候选单位：Dense Top30 与
离线 BM25 Top30 经 RRF 融合到 30 条候选，再由 WS 评测专用接口以当前 `IRetrievalReranker` 重排为
Top5。它不改用户检索入口；运行前需要部署带 `/internal/v1/evaluation/retrieval/rerank` 的最新 WS。

```bash
python3 rag-eval/scripts/run_scifact_c2_hybrid.py \
  --dataset-root rag-eval/cache/general-scifact-full-v1 \
  --baseline-run rag-eval/runs/general-scifact-full-20261004T075524Z \
  --candidate-limit 30 \
  --top-k 5 \
  --rrf-k 60
```

输出独立保存为新的 C2 run，包含 Active Generation Chunk snapshot、每条 query 的 Dense/BM25/RRF/rerank
候选和最终指标；不得覆盖 C1。只有 C2 的最终 Hit/Recall/MRR 与额外延迟同时被记录后，才讨论正式 Hybrid。

## 数据集原则

1. 文档使用稳定的 `documentKey`，不绑定运行时生成的 `documentCode`。
2. 黄金证据记录 `documentKey + heading + quote`，不绑定 `chunkNo`，以兼容后续分段策略调整。
3. `test.jsonl` 一经发布只新增数据集版本，不修改历史问题和证据。
4. 原始文档必须来源明确，不提交密钥、个人信息或许可证不清晰的材料。

## 运行当前 Retrieval V2

`baseline-v1` 的历史运行只用于保存旧链路的可比证据，不能与当前 Generation 检索链路的结果混称。当前评测复用相同 `dataset-v1.0`、黄金证据和 Hit/Recall/MRR 口径，但改为：

`Document import → IndexJob/Outbox → Worker → Generation READY → IKnowledgeRetrievalService`

检索执行器调用受 service token 保护的内部评测入口；它不调用已关闭的 legacy `/knowledge/search`，也不调用 Chat/Ask，所以结果只代表 RAG retrieval，而不是模型回答质量。

```bash
export HEX_WORKSPACE_TOKEN='仅上传语料时需要的用户 JWT'
export HEX_WORKSPACE_WORKSPACE_ID=1
export HEXWORKSPACE_RAG_EVALUATION_TOKEN='部署时配置的 hexworkspace.rag-evaluation.token'

# 首次：上传 40 份冻结原始语料，等待每份文档的 Active Generation 后跑 test 集
python3 rag-eval/scripts/run_retrieval_v2.py --upload

# 后续：复用已有文档，不重复上传
python3 rag-eval/scripts/run_retrieval_v2.py \
  --document-map rag-eval/runs/retrieval-v2-<timestamp>/document-map.json
```

首次上传必须使用专用、干净的评测 Workspace。脚本不会用固定等待时间猜测就绪，而是轮询每个 Document 的 Active Generation，并要求其 Workspace、Document、current Version 和 index revision 全部匹配。它不会要求每份文档都进入 Top K：这属于待评估的检索结果，而不是索引成功的充分条件。

每次运行会在 `runs/<runId>/` 写入：文档映射、处理状态、逐题结果 `cases.jsonl` 与聚合指标 `summary.json`。`runs/` 被 Git 忽略，避免提交 JWT、真实 API 响应、模型答案和成本相关数据。

`summary.json` 记录 retrieval 参数、就绪时的 Active Generation 身份、浏览器侧与服务端分段延迟、按题型指标、未命中题目及 `retrieval-v2.yml` 的 SHA-256。历史 `runs/baseline-v1-*` 不代表当前实现的指标。

评测题目会逐条追加写入 `cases.jsonl`，遇到单题网络超时会重试 GET 请求；重试仍失败时记录该题的 `status: ERROR` 并继续后续题目。使用相同的 `--run-id` 重新执行可以跳过已落盘题目，继续未完成部分。

当前检索接口经过单文档版本去重后最多返回 20 条命中；评测默认以 `candidateLimit=30` 取 Qdrant 初始候选池，再以 `topK=5` 输出最终结果，因此 V2 输出 `Hit Rate@5`、`Recall@5` 与 `MRR@5`。V2 不输出答案正确率或拒答率；这些属于未来独立的 generation evaluation。

## 使用 dataset-v1.1

`dataset-v1.1` 保留 V1.0 语料，仅修正 test split 中两条确定的重复 query 标注；详细原因见 `dataset-v1.1/CHANGELOG.md`。它的 158 题结果应作为后续优化的新基线，不能与 V1.0 的 160 题结果直接横比。

```bash
python3 rag-eval/scripts/run_retrieval_v2.py \
  --dataset-root rag-eval/dataset-v1.1 \
  --config rag-eval/configs/retrieval-v2-v1.1.yml \
  --document-map rag-eval/runs/<已有V1.0运行目录>/document-map.json \
  --candidate-limit 30 \
  --top-k 5 \
  --run-id retrieval-v2-v1.1-$(date -u +%Y%m%dT%H%M%SZ)
```

由于 V1.0/V1.1 的原始语料完全相同，上述命令可以复用同一专用 Workspace 的既有 document map；不需要重新上传语料。

## Phase B：候选池深度对比

Phase B 只比较 Qdrant 初始候选池 `candidateLimit`，不改变 MySQL 权威校验、每文档最多两段、最终排序或生成链路。固定同一 Workspace、document map、数据集和 `topK=5`，依次运行 `30 / 60 / 100`，再比较 `summary.json` 的 `Hit Rate@5`、`Recall@5`、`MRR@5`、`serverTimingMs` 与 `missedCases`。候选池扩大没有提升 numeric/multi-condition 的召回时，不应继续盲目增加候选数。

## Phase C0：候选阶段诊断

评测接口还会在 `cases.jsonl` 和 `summary.json` 的 `missedCases` 中写入 `retrievalDiagnostics`，仅包含内部评测需要的 `documentCode / rank / score`。它将漏题归因为：

- `QDRANT_TOP_CANDIDATE_MISS`：黄金文档未进入本次 `candidateLimit` 的 Qdrant 候选；
- `AUTHORITY_FILTERED`：进入 Qdrant 候选，但未通过 MySQL 当前权威校验；
- `RANKING_MISS`：通过权威校验，但未进入最终 TopK；
- `DIAGNOSTICS_UNAVAILABLE`：运行中的服务尚未部署该诊断版本。

诊断只由 service-token 保护的评测调用生成；用户和 Agent 的正常检索不会构造或暴露这些候选信息。

## Phase C1：可选 Rerank

Rerank 位于 `Qdrant → MySQL 权威校验` 之后，最终结果仍会再次进行 MySQL 权威复核。默认关闭；服务超时、调用异常或响应不完整时自动回退原始向量排序。

```bash
export HEX_WORKSPACE_RERANK_ENABLED=true
export DASHSCOPE_RERANK_API_KEY='...'
# 使用 Model Studio 工作空间专属域名时，按账号区域覆盖 endpoint。
export DASHSCOPE_RERANK_ENDPOINT='https://<workspace-id>.cn-beijing.maas.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank'
```

启用后，评测诊断额外包含 `rerankedCandidates`。其中 `score` 是单次请求内的 rerank relevance score，不能跨请求比较；新增的服务端耗时位于 `serverTimingMs.rerankMs`。

默认 instruct 保持已验证的通用问答排序规则。可以通过 `HEX_WORKSPACE_RERANK_INSTRUCT` 覆盖该指令进行单变量 A/B 评测；每次只改变 instruct，固定 Workspace、`document-map`、`candidateLimit=30` 与 `topK=5`。

`HEX_WORKSPACE_RERANK_WEIGHT` 控制按候选名次融合 Rerank 与原始 Qdrant 排名：`1.0` 是纯 Rerank，`0.0` 是原始向量顺序。候选名次而非 provider 原始分数参与融合，因为 Rerank relevance score 只在单次请求内可比较。建议固定通用 instruct，依次测量 `0.7 / 0.8 / 0.9 / 1.0`。
