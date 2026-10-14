# SciFact 300 Query：生产混合检索重测

运行入口：`rag-eval/scripts/run_scifact_production_hybrid.py`。客户端不做离线 BM25、RRF 或 Rerank，仅调用内部评测接口，接口复用生产可信检索服务。沿用现有语料 Document Map，不上传、不重建向量。

## 1. 服务端准备

在启动后端的终端设置（已运行进程不会继承新 export）：

```bash
cd /Users/hector/dev/hex-workspace
export HEX_WORKSPACE_HYBRID_ENABLED=true
export HEX_WORKSPACE_BM25_MAX_SOURCE_CHARS=12000000
export HEX_WORKSPACE_BM25_MAX_CHUNKS=20000
export HEX_WORKSPACE_RERANK_ENABLED=true
export HEX_WORKSPACE_RERANK_WEIGHT=1.0
export HEXWORKSPACE_RAG_EVALUATION_TOKEN='替换为你自己的内部评测令牌'
```

沿用已配置的 Embedding / Rerank 凭证与 endpoint，不在命令行或 Artifact 中打印密钥。重新编译并用上述环境启动后端；如果使用打包运行：

```bash
mvn -q -pl hex-workspace-app -am -DskipTests package
java -jar hex-workspace-app/target/hex-workspace-app.jar --spring.profiles.active=dev
```

先正常停止旧实例，不同时启动两个同端口实例。保留已有 MySQL / Qdrant / MinIO 语料，不清库。评测期间不要更新语料、Serving 配置、Rerank 参数或生产实现。应用默认 8098；如覆盖端口，使用对应 base-url。

## 2. 执行

历史 SciFact 文档位于 Workspace 10（旧 run-config），脚本会验证当前文档仍在指定 Workspace。如果部署数据已更改，应填写真实语料 Workspace，而不是盲信旧值。

另一个终端：

```bash
cd /Users/hector/dev/hex-workspace
export HEXWORKSPACE_RAG_EVALUATION_TOKEN='与服务端相同的内部评测令牌'
export SCIFACT_OUTPUT="$PWD/rag-eval/runs/scifact-production-hybrid-$(date -u +%Y%m%dT%H%M%SZ)"
python3 -u rag-eval/scripts/run_scifact_production_hybrid.py \
  --base-url http://127.0.0.1:8098 \
  --workspace-id 10 \
  --candidate-limit 30 \
  --top-k 5 \
  --output-dir "$SCIFACT_OUTPUT"
```

首次快照构建允许等待 900 秒，不计入查询延迟；期间只下载已存在的 Chunk，不调用模型。完整快照哈希与当前 5,183 个 Active Generation 一致、Chunk 数为 19,557、Rerank 启用且每文档一条时才执行真实查询。额外 Workspace 文档、旧快照、刷新失败、配置不符都会阻断，不会静默标注为完整混合链路。

真正检索阶段会产生真实 Embedding / Rerank 成本：300 条 HTTP 检索尝试，后台模型重试遵循现有生产配置，实际 Provider 请求次数并非严格 300。客户端 HTTP 重试为 0。

中断后可以使用原目录追加 `--resume`，不重跑已完成的成功或失败案例：

```bash
python3 -u rag-eval/scripts/run_scifact_production_hybrid.py \
  --base-url http://127.0.0.1:8098 --workspace-id 10 \
  --candidate-limit 30 --top-k 5 --output-dir "$SCIFACT_OUTPUT" --resume
```

若存在已发起但没有终态落盘的请求，脚本拒绝自动恢复，避免未知重复收费。不要删除尝试账本来绕过它。

## 3. 产物与统计

`manifest.json` 保存输入及本地源码哈希（不等于证明运行服务二进制版本，需先按上文重编译/重启）；`readiness-before/after.json`、`snapshot-before/after.json` 保存语料与就绪证据；`attempts.jsonl`、`cases.jsonl` 保存尝试及原始完整响应；`summary.json`、`REPORT.md` 保存汇总；阻断时有 `stop.json`。

- 主指标：Distinct Document Hit@5、宏平均 Recall@5、MRR@5。
- 分别报告全部 300 个预注册案例、成功执行案例与没有 Rerank 回退的完整链路案例，不隐藏错误。
- Client HTTP P50/P95：nearest-rank；既有服务端 Timing 保留原义，Sparse 耗时包含于 totalMs，但不是 qdrantMs。
- 多候选没有 Rerank 诊断时标为 FALLBACK；该结果保留，不算完整链路成功。
- 不暴露 Provider Tokens，输出 null 与原因；不把本地估算冒充 Provider Usage。
- 不将历史结果差值认定为本轮严格因果 A/B，新的端到端实现、同分规则及运行环境可能不同。

离线验证：`python3 -m unittest discover -s rag-eval/scripts -p test_scifact_production_hybrid.py -v`，不调用服务或模型。
