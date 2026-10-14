# 取消每文档限额：已取得的指标及证据边界

## 生产变更

普通生产服务仅按总 resultLimit 截断，不按文档限制数量。不修改 Dense、BM25、RRF、Rerank、权限/版本/Generation 复核。受控历史 A/B 入口的限额仍保留以复现冻结合同。原始运行报告未改写，不发起新模型调用。

## SciFact：真实运行

来源：`runs/scifact-chunk-top5-20261014T132110Z/`，300 Query / 19,557 Chunk。

- 原始 Top5 Chunk，使用文档级相关性标签；重复文档占位但不重复计数。
- Hit@5：248/300，82.6667%。
- 宏平均 Recall@5：80.30%。
- MRR@5：72.7444%。
- 实际 HTTP P50 609.19 ms、P95 824.20 ms；快照预热不计入。
- 300/300 成功、无 Rerank 回退。不限额重放与实际响应一致。

## Java Notes：固定真实轨迹的离线重放

来源：`runs/business-ab-20261014T123101Z/cases.jsonl` 的 B 侧 143 条，使用完整 `rerankedChunks` 排名直接取前 5 条，再用同一 Quote Mapping / Evidence Group Oracle 评分。

- Document Hit@5：136/143，95.1049%。
- Document Recall@5：95.1049%。
- 完整证据命中率@5：119/143，83.2168%。
- Evidence Group Recall@5：83.2168%（本批均单证据组，因此与完整命中率相同）。
- 不限额生产真实延迟：NOT_AVAILABLE，不沿用限额为 2 的 A/B 延迟。
- 这是固定排序的截断重放，不包含新一轮模型调用或实时最终复核，不宣称已经真实执行新生产策略。
- 数据仍为 EVIDENCE_MAPPED_REVIEW_PENDING，不升级为语义审核通过。

旧 A/B 截断（每文档 2 条）重放与实际返回集合一致；不限额数值不是用此前每文档 1 条运行结果外推。全部历史 Artifact 保留。
