# 进程内混合检索最小接入

## 当前结果契约（取消生产文档限额）

普通生产检索只保留总 TopK Chunk，不按文档去重或限制每文档数量；`hybrid.max-chunks-per-document` 配置已移除。兼容 DTO 中的旧字段在普通路径不参与截断，受控历史 A/B 入口仍显式复现原限额。下文原接入说明中的“默认每文档一条”已被本契约取代，历史指标与 Artifact 不改写。

普通业务重测 Runner 已支持不限额；旧 SciFact 文档级 Runner 会在新策略下停止，须使用 `run_scifact_chunk_top5.py`。服务需重编译/重启才能生效。

## 生产调用链

可信 Workspace 认证 → Query Embedding → Dense 候选 + 本地 BM25 候选 → RRF（k=60，截断候选上限）
→ MySQL 权限、文档版本、输入修订、READY/Active Generation 与 Embedding 配置复核
→ 现有 Rerank（标题 + headingPath + 正文）→ 每文档结果上限 → 返回前再次权威复核。

BM25 不产生新向量，也不增加数据库表或外部搜索服务。读取现有 Generation Chunk 产物，校验 SHA-256、数量与身份；Point ID 与向量构建一致。

## 开关与兼容

- `HEX_WORKSPACE_HYBRID_ENABLED=true`（默认）：启用稀疏召回与 RRF。
- `HEX_WORKSPACE_BM25_REFRESH_MS=60000`（默认）：后台全量刷新已访问 Workspace。
- `HEX_WORKSPACE_RERANK_ENABLED=true`：启用完整重排链路；沿用既有配置，不擅自改变原来的关闭默认值。
- 未明确指定 `maxChunksPerDocument` 的混合检索默认每文档 1 条；显式多 Chunk 请求不覆盖。
- `retrieveCandidatesWithEvaluationTrace` 保持 Dense-only，不调用 BM25/Rerank，避免历史基线漂移。
- 诊断中的 `qdrantCandidates` 始终是原始 Dense 排名；融合后结果分数是 RRF 分数，不是相似度。日志额外记录 Sparse 数量与耗时；既有 Timing 字段不冒充 Sparse 耗时。

## 快照与故障边界

首次查询触发后台构建，不等待对象下载，先回退 Dense。后续查询复用不可变快照；刷新成功单次发布，任何加载失败保持旧快照。进程重启后重建，不保存本地磁盘索引。

快照可能滞后于发布/删除。旧候选仍需权威复核，不能成为越权通道；新 Generation 的 BM25 可见性为最终一致，刷新前依赖 Dense。全量重建会重新下载产物，不是增量索引。

V1 默认上限：最多缓存 8 个 Workspace，每个最多 20,000 Chunk / 4,000,000 正文字符；可通过 `HEX_WORKSPACE_BM25_MAX_WORKSPACES`、`HEX_WORKSPACE_BM25_MAX_CHUNKS`、`HEX_WORKSPACE_BM25_MAX_SOURCE_CHARS` 调整。单产物解码前最多 16 MB。超限保留旧快照或回退 Dense，不承诺大型语料完整稀疏召回。下载端口返回完整 byte[]，单对象下载峰值不能由解码前上限严格约束。

内部 `hybrid-status` 仅接受评测 Token，复用 Workspace 校验，可无模型调用地安排冷启动，并报告快照 Generation 哈希、规模、配置与刷新失败。SciFact 原快照 19,557 Chunk / 9,287,805 正文字符，执行前至少将字符上限设置为 12,000,000，再重启服务；上限提高意味着更高内存开销。

沿用评测的英文 tokenization、停用词与 BM25 参数（k1=1.2，b=0.75）；不增加中文分词。纯中文无命中时回退 Dense。稳定同分顺序使用本地身份/文档 ID，而非 Python 评测的 documentCode，不能宣称逐位复现冻结指标。

## 验证口径

离线测试覆盖 BM25 公式、RRF 去重与 Generation 隔离、冷启动、快照复用/原子发布、失败保留、配置隔离、Sparse 补召回、旧 Generation 阻断、最终撤销复核、Dense 回退与评测入口兼容。

没有执行真实模型、线上部署或重新跑 SciFact / 业务集；旧 Recall/Hit/P95 不能作为此次 Production 实测结果。

本轮离线回归：Domain + Infrastructure 共 27 个测试类、80 项测试，失败/错误/跳过均为 0；排除依赖外部容器的 IntegrationTest 与真实 Provider Smoke。全应用编译及 `git diff --check` 通过。测试需绑定本地 HTTP 模拟服务器，并在 JVM 启动时预加载 Byte Buddy Agent，未调用真实模型。
