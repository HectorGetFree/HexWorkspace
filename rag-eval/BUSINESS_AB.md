# Java Notes：BM25/RRF 单机制 A/B

## 冻结合同

Workspace 14，原 34 份资料 / 143 条 EVIDENCE_MAPPED 查询 / 原 Quote Mapping。

| 环节 | A | B |
| --- | --- | --- |
| 候选 | Dense Top30 | Dense Top30 + BM25 Top30 → RRF(k=60) Top30 |
| Rerank | 现有 Production 模型、参数、标题/章节/正文 | 相同 |
| 每文档 | 2 条 | 2 条 |
| 最终 | Top5 Chunk | Top5 Chunk |

原 `RetrievalOptionsVO` 默认每文档 2 条，原 Eval 调用传 null，符合历史基线口径。当前生产混合默认 1 条不修改；新 `/internal/v1/evaluation/retrieval/ab` 仅受内部评测 Token 保护，通过逐请求参数选择路径，并固定每文档 2 条。不会修改全局开关，也不会影响同时运行的普通查询。

它仍执行真实 Production Embedding、召回、Rerank、权限、最终复核和限额路径，不使用客户端替代召回/重排。A/B 均独立执行完整请求，Dense 顺序可能变化，报告保存并比较该差异。快照、配置前后检查一致；请使用专门的空闲评测服务，不在运行中更改任何配置或语料。

## 离线 Gate 与观测

新增诊断保留既有文档级字段，并追加 `sparseCandidates`、`fusedCandidates`、`rerankedChunks`（完整身份、排名、分数）。Timing 追加 `hybridMs`，测量稀疏召回与融合分支（含该分支诊断/日志开销，不含后台构建）。历史字段口径不变。

Eval 独立按真实 Chunk 顺序标记 `RETURNED`、`PER_DOCUMENT_LIMIT`、`RESULT_LIMIT`、`FINAL_REVALIDATION_REMOVED`，不修改排序结果或 Gold。记录每个证据组在各阶段的排名，用于区分召回/融合/重排/截断。Rerank 回退独立保留，不能计入有效完整配对。

冻结指标：Document Hit/Recall/MRR、完整证据命中、证据组 Recall、候选证据覆盖、A-only/B-only/Both/Neither、配对百分点差；HTTP P50/P95 与全部服务端阶段耗时保留。Provider Usage 未暴露，保持 null。单次配对不证明延迟稳定提升，不重新定义原草案为 VERIFIED Benchmark。

## 启动后端（必须重编译/重启以加载新端点与诊断）

先正常停止旧实例，再在后端终端执行；复用已经配置的真实 Provider 凭证与 endpoint：

```bash
cd /Users/hector/dev/hex-workspace
export HEX_WORKSPACE_HYBRID_ENABLED=true
export HEX_WORKSPACE_BM25_MAX_SOURCE_CHARS=12000000
export HEX_WORKSPACE_RERANK_ENABLED=true
export HEX_WORKSPACE_RERANK_WEIGHT=1.0
export HEXWORKSPACE_RAG_EVALUATION_TOKEN='你的内部评测令牌'
mvn -q -pl hex-workspace-app -am -DskipTests package
java -jar hex-workspace-app/target/hex-workspace-app.jar --spring.profiles.active=dev
```

不更改每文档默认上限，不更改中文分词、模型、Prompt 或语料。英文 BM25 的收益/退化均保留。

## 执行 143 对

另一个终端：

```bash
cd /Users/hector/dev/hex-workspace
export HEXWORKSPACE_RAG_EVALUATION_TOKEN='与后端相同的令牌'
export BUSINESS_AB_OUTPUT="$PWD/rag-eval/runs/business-ab-$(date -u +%Y%m%dT%H%M%SZ)"
python3 -u rag-eval/scripts/run_business_ab.py \
  --base-url http://127.0.0.1:8098 --workspace-id 14 \
  --candidate-limit 30 --top-k 5 \
  --allow-evidence-mapped-draft \
  --output-dir "$BUSINESS_AB_OUTPUT"
```

每对按 AB/BA 交替，每侧一次；共 286 次检索 HTTP 尝试，会产生真实 Embedding/Rerank 成本。客户端不重试；后端 SDK 仍遵循现有配置，不声称实际 Provider 调用严格只有 286 次。

中断后保持所有参数和原目录，追加 `--resume`。已终态失败也跳过；未落盘终态的尝试阻断恢复，避免未知重复执行。语料/源码变化不允许混合续跑。

每次使用新目录，不覆盖历史报告。`manifest.json`、原始响应 `cases.jsonl`、`attempts.jsonl`、前后就绪/快照、`summary.json` 均可审计。输入、隔离、诊断契约失效会停止；普通请求失败保留，不重跑到 PASS。

离线命令：`python3 -m unittest discover -s rag-eval/scripts -p test_business_ab.py -v`。离线测试是契约证明，不是真实 143 对结果。
