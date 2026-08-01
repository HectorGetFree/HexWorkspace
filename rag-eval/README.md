# RAG 评测基线

本目录用于保存 hex-doc RAG 链路的可复现评测资产，不属于 Spring Boot 运行时模块。

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

## 运行 Baseline V1

评测执行器只通过公开 HTTP 接口工作：首次在**专用评测租户**上传语料，之后复用本次运行生成的文档映射，避免重复上传文档污染向量检索结果。

```bash
export HEX_DOC_TOKEN='你的 JWT'
export HEX_DOC_TENANT_ID=1

# 首次：上传 40 份语料、等待解析成功并跑 test 集
python3 rag-eval/scripts/run_baseline_v1.py --upload

# 后续：复用已有文档，不重复上传
python3 rag-eval/scripts/run_baseline_v1.py \
  --document-map rag-eval/runs/baseline-v1-<timestamp>/document-map.json
```

接口 `GET /api/v1/tenants/{tenantId}/documents/{documentCode}/processing-status`
提供当前版本的 `parseStatus`，脚本据此轮询 `PENDING`、`PROCESSING`、`SUCCESS` 与 `FAILED`。
解析成功后，脚本还会以文档的唯一规则编号调用一次 `/knowledge/search`，确认该文档的 `documentCode` 已可被实际检索到，避免解析成功与向量入库完成之间的短暂竞态。

每次运行会在 `runs/<runId>/` 写入：文档映射、处理状态、逐题结果 `cases.jsonl` 与聚合指标 `summary.json`。`runs/` 被 Git 忽略，避免提交 JWT、真实 API 响应、模型答案和成本相关数据。

当前检索接口经过单文档版本去重后最多返回 6 条命中，因此 Baseline 使用 `Hit Rate@6`、`Recall@6` 与 `MRR@6`，而不是未实际暴露的 `@8`。答案正确率为 `mustContain` 的基础自动判定；拒答率使用“无法回答/信息不足”等保守词表判定，后续再引入 LLM Judge。
