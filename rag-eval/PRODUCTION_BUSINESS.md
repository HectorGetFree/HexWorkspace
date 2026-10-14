# Java Notes 143 查询：生产混合链路重测

## 当前不限额真实重测

使用 `run_business_production_hybrid.py --require-unlimited`，服务必须返回 `maxChunksPerDocument=0` 且 `documentLimitPolicy=NONE`。该 Gate 在模型查询前拒绝旧后端，不会把限额结果标注为当前生产结果。

真实重测沿用原 143 条查询、34 份资料、Quote Mapping 和 Oracle，不预设会达到离线重放的 83.22%。Evidence Hit 与 P95 均来自本次实际运行；冷快照准备和状态检查不计入查询延迟。鉴权/端点不存在或响应契约异常保存首个错误后停止。

```bash
cd /Users/hector/dev/hex-workspace
export HEXWORKSPACE_RAG_EVALUATION_TOKEN='与后端相同的内部评测令牌'
export BUSINESS_UNLIMITED_OUTPUT="$PWD/rag-eval/runs/business-unlimited-$(date -u +%Y%m%dT%H%M%SZ)"
python3 -u rag-eval/scripts/run_business_production_hybrid.py \
  --base-url http://127.0.0.1:8098 --workspace-id 14 \
  --candidate-limit 30 --top-k 5 \
  --allow-evidence-mapped-draft --require-unlimited \
  --output-dir "$BUSINESS_UNLIMITED_OUTPUT"
```

此前生产文档限额移除后，须重编译并重启后端；下文的启动命令可复用。原有报告不覆盖；续跑追加 `--resume`，仍需原目录和相同参数，失败终态不重发。

脚本：`rag-eval/scripts/run_business_production_hybrid.py`。

- Workspace 14：用户确认的原业务语料库；启动时校验原 34 个 Document Code。
- 原查询：`dataset-v2-java-notes-v1-draft/cases/evidence-mapped.jsonl`，143 条，不筛选替换。
- 原 Document Map 与 Quote Mapping：`runs/java-notes-v1-draft-rerun-20261006T084332Z~/`。
- 数据仍为 `EVIDENCE_MAPPED_REVIEW_PENDING`，不是新冻结/VERIFIED Benchmark。要求显式参数承认这一边界。
- 客户端只做检查与评分，BM25、RRF、结构上下文 Rerank、去重和最终复核均走生产检索服务。

## 执行

上一轮 SciFact 后端仍在运行且配置未改时可直接复用，无需重启或重新上传。

```bash
cd /Users/hector/dev/hex-workspace
export HEXWORKSPACE_RAG_EVALUATION_TOKEN='与服务端相同的内部评测令牌'
export BUSINESS_OUTPUT="$PWD/rag-eval/runs/business-production-hybrid-$(date -u +%Y%m%dT%H%M%SZ)"
python3 -u rag-eval/scripts/run_business_production_hybrid.py \
  --base-url http://127.0.0.1:8098 \
  --workspace-id 14 \
  --candidate-limit 30 --top-k 5 \
  --allow-evidence-mapped-draft \
  --output-dir "$BUSINESS_OUTPUT"
```

需要重新启动后端时，在后端终端设置（沿用真实 Provider 凭证，不打印密钥）：

```bash
cd /Users/hector/dev/hex-workspace
export HEX_WORKSPACE_HYBRID_ENABLED=true
export HEX_WORKSPACE_BM25_MAX_SOURCE_CHARS=12000000
export HEX_WORKSPACE_RERANK_ENABLED=true
export HEX_WORKSPACE_RERANK_WEIGHT=1.0
export HEXWORKSPACE_RAG_EVALUATION_TOKEN='与评测端相同的内部评测令牌'
mvn -q -pl hex-workspace-app -am -DskipTests package
java -jar hex-workspace-app/target/hex-workspace-app.jar --spring.profiles.active=dev
```

先正常停止旧服务，避免端口冲突。不要清库、变更语料或模型配置。新进程首次访问会后台建快照；就绪检查不调用模型，正式 143 条检索会产生 Embedding/Rerank 成本，生产 SDK 重试次数不由客户端控制。

中断后使用完全相同参数与 `$BUSINESS_OUTPUT`，追加 `--resume`。已保存的成功与失败均跳过；已发起但未记录终态的请求拒绝自动重发，需人工审计。

## Gate 与指标

先校验源文件 SHA-256、原映射与当前 Generation、Quote 来源及完整 BM25 快照。若映射陈旧或 Workspace 中包含其他语料，停止而不自动重建 Gold。

逐条保存完整响应、诊断、错误与尝试账本；前后保存就绪状态和快照哈希。主要产物是 `summary.json`、`cases.jsonl`、`REPORT.md`，阻断时有 `stop.json`。

同时报告 Document Hit/Recall/MRR、全部必需证据组命中率、证据组 Recall 与 Client HTTP P50/P95。分别展示全部 143 条、成功执行和完整链路子集，Rerank 回退独立计数。Provider Usage 保持 null，不伪造 Token。

Document 命中不能代替具体证据命中；BM25 仍是英文分词，不能预设中文任务收益。新链路每文档一条与历史多 Chunk 结果不同，历史延迟分位数算法也不同，不将差值包装为严格因果 A/B。

本轮只新增 Eval 脚本和文档，不修改 Production、原样本、Gold 或历史 Artifact。离线 Runner 测试 6/6 通过（包括 143 次模拟执行与恢复跳过检查），不是真实业务查询成功率。
