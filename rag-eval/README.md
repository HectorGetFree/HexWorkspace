# RAG 评测基线

本目录用于保存 hex-workspace RAG 链路的可复现评测资产，不属于 Spring Boot 运行时模块。

## 目录约定

- `dataset-v1.0/`：冻结的数据集、文档清单与黄金问题标注。
- `configs/`：每次基线评测使用的 RAG 配置快照。
- `scripts/`：评测执行器与指标计算脚本，后续补充。
- `runs/`：每次评测的原始结果与报告，不反向修改数据集。

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

`summary.json` 记录 retrieval 参数、就绪时的 Active Generation 身份、延迟与 `retrieval-v2.yml` 的 SHA-256。历史 `runs/baseline-v1-*` 不代表当前实现的指标。

评测题目会逐条追加写入 `cases.jsonl`，遇到单题网络超时会重试 GET 请求；重试仍失败时记录该题的 `status: ERROR` 并继续后续题目。使用相同的 `--run-id` 重新执行可以跳过已落盘题目，继续未完成部分。

当前检索接口经过单文档版本去重后最多返回 20 条命中；默认 `topK=6`，因此 V2 输出 `Hit Rate@6`、`Recall@6` 与 `MRR@6`。V2 不输出答案正确率或拒答率；这些属于未来独立的 generation evaluation。
