# SciFact：300 Query，无每文档限额，Top5 Chunk

脚本：`rag-eval/scripts/run_scifact_chunk_top5.py`。

继续使用当前生产 Dense + BM25/RRF + 结构上下文 Rerank + 初始/最终权威复核。通过评测 Token 保护的 `/internal/v1/evaluation/retrieval/chunks-topk` 固定候选 30、结果 5、每文档额度 5。在总结果为 5 的前提下，该额度等价于不施加文档限额，普通生产默认仍为 1，不修改。

查询、语料、原 Document Map、Serving/Generation 就绪 Gate、预算、错误保留、客户端零重试与 Resume 规则沿用上一轮 SciFact Runner。完整 `rerankedChunks`、Sparse/Fused 候选和分阶段耗时保留，未来可在同一排序上离线重放其他限额策略。

Hit@5：原始前 5 个 Chunk 中是否有任意一个来自 SciFact 标注的相关文档。
Recall@5：前 5 个 Chunk 涵盖的不同相关文档数 / 全部 Gold 文档数，再对 300 Query 宏平均。
重复文档不能重复累计 Recall，但占用 Chunk 位；MRR 按第一次相关 Chunk 的原始位置计算。
这不等同于“Top5 不同文档”，不将两个 K 口径混为同一优化指标。

## 启动后端

先正常停止旧进程；新端点需要重编译/重启。后端终端沿用已有 Provider 凭证和地址：

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

不上传、不重建向量、不更换模型、分词器或 Rerank Prompt。测试期间保持配置与语料不变。

## 执行（真实查询由用户运行）

```bash
cd /Users/hector/dev/hex-workspace
export HEXWORKSPACE_RAG_EVALUATION_TOKEN='与后端相同的令牌'
export SCIFACT_CHUNK_OUTPUT="$PWD/rag-eval/runs/scifact-chunk-top5-$(date -u +%Y%m%dT%H%M%SZ)"
python3 -u rag-eval/scripts/run_scifact_chunk_top5.py \
  --base-url http://127.0.0.1:8098 --workspace-id 10 \
  --candidate-limit 30 --top-k 5 \
  --output-dir "$SCIFACT_CHUNK_OUTPUT"
```

300 条检索会产生真实 Embedding/Rerank 费用；客户端不自动重试，Provider SDK 重试仍遵循生产配置。冷快照 Gate 不调用模型。每次新建目录，历史 Artifact 保留不覆盖。

中断续跑用同样参数和原目录追加 `--resume`，已完成的失败也不重跑。未知终态的请求阻断自动续跑。报告入口为 `$SCIFACT_CHUNK_OUTPUT/summary.json`，原始响应与排名位于 `cases.jsonl`。

Provider Usage 未暴露则保持 null。新真实 Rerank 可能与旧运行不同，后续优先在本轮同一完整排名内做限额重放；本轮不宣称稳定性或广泛通用收益。
