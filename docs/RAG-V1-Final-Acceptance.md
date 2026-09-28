# Hex Workspace RAG V1 最终交付验收

## 1. 交付范围

RAG V1 面向 Hex Workspace 内部可信检索，已覆盖：

- DocumentVersion 生效后的异步索引任务登记。
- MySQL IndexJob + Outbox 事务一致性。
- RabbitMQ 投递与 Listener 消费。
- Parser、Chunker、Embedding、Qdrant 索引构建。
- Generation READY 与 Active Generation 发布。
- 基于 MySQL 权威状态的 Retrieval 过滤和最终复核。
- 版本切换、旧 Generation 清理及失败恢复。
- Worker 失败重试、Lease 接管、Outbox 发布失败恢复、Qdrant 部分批次失败恢复。

不包含：公开 Knowledge Search / Ask API、Agent / Model Service 集成、多人协作、历史生产数据迁移和旧 Knowledge MVP 在线迁移。

## 2. 架构概览

```text
DocumentVersion
  → IndexJob + Outbox
  → RabbitMQ
  → Worker
  → Parser / Chunker / Embedding
  → Qdrant
  → Generation READY
  → Active Generation
  → Retrieval / Cleanup
```

MySQL 中的 Active Generation 是检索权威来源。Qdrant 只负责候选召回，权限、文档状态、版本、Revision、目录链、Generation 和 Serving 配置均由 MySQL 复核。

RAG V1 使用独立 Collection：`hex_workspace_rag_v1`。

## 3. 本轮测试矩阵

### 3.1 主链路联合回归

命令：

```bash
mvn -q -pl hex-workspace-app -am test -Dapp.tests.skip=false \
  '-Dtest=Phase3KnowledgeIndexEndToEndIntegrationTest#shouldIndexDocumentVersionAndPublishActiveGenerationEndToEnd+shouldRetrieveChunksFromPublishedGenerationEndToEnd+shouldSwitchRetrievalToNewVersionAndCleanupOldGenerationEndToEnd' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

| 测试类 | Tests run | Failures | Errors | Skipped | 结果 |
|---|---:|---:|---:|---:|---|
| `Phase3KnowledgeIndexEndToEndIntegrationTest` | 3 | 0 | 0 | 0 | PASS |

覆盖索引发布、真实 Retrieval、版本切换和旧 Generation Cleanup。

### 3.2 故障链路联合回归

命令：

```bash
mvn -q -pl hex-workspace-app -am test -Dapp.tests.skip=false \
  '-Dtest=Phase3KnowledgeIndexEndToEndIntegrationTest#shouldRetryFailedIndexJobAndPublishGenerationSuccessfully+shouldTakeOverExpiredWorkerLeaseAndRejectStaleWorker+shouldRecoverFailedOutboxPublishAndCompleteIndexing+shouldRetryWithNewGenerationAfterPartialQdrantBatchFailure' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

| 测试类 | Tests run | Failures | Errors | Skipped | 结果 |
|---|---:|---:|---:|---:|---|
| `Phase3KnowledgeIndexEndToEndIntegrationTest` | 4 | 0 | 0 | 0 | PASS |

覆盖 Worker 失败重试、Lease 接管、Outbox 发布失败恢复和 Qdrant 部分写入后的新 Generation 重建。

### 3.3 Phase 4 集成回归

命令：

```bash
mvn -q -pl hex-workspace-infrastructure -am test -Dapp.tests.skip=false \
  '-Dtest=KnowledgeRetrievalMySqlIntegrationTest,GenerationCleanupIntegrationTest,GenerationPublicationMySqlIntegrationTest,QdrantVectorStoreIntegrationTest' \
  -Dsurefire.failIfNoSpecifiedTests=false
```

| 测试类 | Tests run | Failures | Errors | Skipped | 结果 |
|---|---:|---:|---:|---:|---|
| `KnowledgeRetrievalMySqlIntegrationTest` | 2 | 0 | 0 | 0 | PASS |
| `GenerationCleanupIntegrationTest` | 2 | 0 | 0 | 0 | PASS |
| `GenerationPublicationMySqlIntegrationTest` | 9 | 0 | 0 | 0 | PASS |
| `QdrantVectorStoreIntegrationTest` | 3 | 0 | 0 | 0 | PASS |
| **合计** | **16** | **0** | **0** | **0** | **PASS** |

以上测试使用真实 Testcontainers 基础设施；未使用 Mock MySQL、Qdrant 或 MinIO 冒充集成测试。

## 4. 生产代码审计

### DocumentVersion 与索引一致性

- IndexJob 和 Outbox 由业务索引登记服务在事务中创建。
- Job 通过 `request_key` 和数据库唯一约束实现幂等。
- DocumentVersion / `index_input_revision` 绑定到 Job 和 Generation。
- 发布前重新校验 Document 当前版本、Revision、Workspace 和 Serving 配置。
- 旧版本、旧 Revision 和旧 Generation 不能成为 Active。

### Worker、Lease 与 Retry

- Worker Token 使用 UUID 生成。
- Job 抢占、续期、成功和失败均使用状态、Token、Lease 条件更新。
- Phase 5.3.1 修复了 `next_retry_at` 的 Java/MySQL 时钟不一致，重试时间由 MySQL `CURRENT_TIMESTAMP` 计算。
- Worker Lease 创建、续期、回收和 Java 侧 Lease 判断统一使用 UTC。
- 失效 Worker 的旧 Token 无法更新 Job，也无法发布 Generation。
- 每次重试通过新的 Generation Attempt 构建，不复用失败 Generation。

### Qdrant 与 Generation

- Point ID 由 `generationId + chunkNo` 稳定生成。
- 不同 Generation 的 Point ID 不互相覆盖。
- 批量写入异常向上抛出，阻止 READY 和 Publication。
- READY 前执行数量和完整 Point ID 集合校验。
- Publication 只接受 READY Generation，并再次校验 Worker Token、Lease、版本、Revision 和 Serving 配置。
- Active Generation 在 MySQL 事务中切换。

### Retrieval

`KnowledgeRetrievalServiceImpl` 的结果交付前会校验：

- Workspace 访问权限。
- Qdrant Workspace 过滤。
- Document ACTIVE 状态。
- 当前 DocumentVersion 和 `index_input_revision`。
- Folder 及完整祖先链状态。
- Active Generation 和 READY 状态。
- Generation 所属 Document、Workspace、版本、Revision。
- Serving EmbeddingConfig。
- 最终复核期间的最新 MySQL 状态。

### Cleanup

- Cleanup 通过 Generation 状态和 Lease / Token CAS 领取。
- Active Generation 不会被清理。
- Qdrant 按 Generation 定向删除，Chunk Artifact 按 Generation 删除。
- Parsed Artifact 按当前策略保留。
- 发布和清理通过 Generation 状态及数据库锁避免冲突。

## 5. 全新环境部署准备

初始化 SQL：

`docs/devops/mysql/sql/001-init-hex-workspace.sql`

已包含 RAG V1 所需的 IndexJob、Generation、IndexState、Outbox、Parse Artifact、EmbeddingConfig、Serving Vector Space 和 Lease 字段、索引及约束。

配置使用独立 Collection：

```yaml
hexworkspace:
  knowledge:
    embedding:
      collection: hex_workspace_rag_v1
```

必要依赖：

- MySQL
- MinIO
- Qdrant
- RabbitMQ
- DashScope API Key
- Embedding 模型及维度配置

健康检查应覆盖 MySQL、MinIO、Qdrant、RabbitMQ 连接，以及 Qdrant Collection 的维度和距离配置。

真实 DashScope 在线联调已完成：Smoke Test 和真实索引、Retrieval E2E 均通过，使用 `text-embedding-v4`、1024 维向量和独立 Collection `hex_workspace_rag_v1_real`。API Key 通过本地 `.env` 注入，未写入代码或日志；`.env` 已从 Git 索引移除并由 `.gitignore` 忽略。

## 6. 已知限制与残余风险

### 已实际测试

- 主链路索引、检索、版本切换和 Cleanup。
- Worker 失败自动重试。
- Worker Lease 过期接管和旧 Token 拒绝。
- Outbox 发布失败恢复。
- Qdrant 部分批次失败后的新 Generation 重建。
- Phase 4 Retrieval、Cleanup、Publication 和 Qdrant 集成测试。
- RabbitMQ Confirm 成功后 `markSentIfOwned()` 失败的重复投递恢复：真实 RabbitMQ Confirm、Outbox 释放、第二次 Dispatcher 投递、重复 Listener 消费和 Job 幂等完成均已验证。
- FAILED Generation 在 Chunk Artifact 已生成且 Qdrant 留有部分 Points 时的残留 Cleanup：真实索引失败、重试发布 Active Generation、Retention 保护、生产 Cleanup 回收和 Retrieval 不受影响均已验证。

### 已静态审计但未专项运行时验证


### 时钟风险

索引 Worker、retry 和 Cleanup 路径均使用 UTC 的 `LocalDateTime` 与 MySQL `DATETIME` 条件交互。Cleanup 定时任务的保留期截止时间、服务内的候选扫描时间、租约创建时间、完成时间和异常释放时间已统一为 UTC；专项测试在 JVM Asia/Shanghai、数据库 UTC 的组合下通过，未复现时间基准偏移。

## 7. 交付阻断项

未发现已确认的主链路、权限过滤、Generation 发布或故障恢复阻断项。

真实 DashScope 在线调用已通过，`.env` 已不再被 Git 跟踪。

## 8. 最终结论

**PASS**

依据：本轮主链路 3 项、故障链路 4 项及 Phase 4 集成回归 16 项均已实际执行并通过；未发现当前交付范围内的确定性生产阻断。

## 9. 本轮验收后的待办
